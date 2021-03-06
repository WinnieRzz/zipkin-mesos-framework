package net.elodina.mesos.zipkin.mesos


import java.util
import java.util.{Collections, Date}

import net.elodina.mesos.zipkin.Config
import net.elodina.mesos.zipkin.http.HttpServer
import net.elodina.mesos.zipkin.storage.Cluster
import net.elodina.mesos.zipkin.utils.Str
import net.elodina.mesos.zipkin.components._
import com.google.protobuf.ByteString
import org.apache.log4j._
import org.apache.mesos.Protos._
import org.apache.mesos.{MesosSchedulerDriver, SchedulerDriver}
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.language.postfixOps

object Scheduler extends org.apache.mesos.Scheduler {

  private[zipkin] val cluster = Cluster()
  private val logger: Logger = Logger.getLogger(this.getClass)
  private var driver: SchedulerDriver = null

  def registered(driver: SchedulerDriver, id: FrameworkID, master: MasterInfo): Unit = {
    logger.info("[registered] framework:" + Str.id(id.getValue) + " master:" + Str.master(master))

    cluster.frameworkId = Some(id.getValue)
    cluster.save()

    this.driver = driver
    reconcileTasks(force = true)
  }

  def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    logger.info("[reregistered] master:" + Str.master(master))
    this.driver = driver
    reconcileTasks(force = true)
  }

  def resourceOffers(driver: SchedulerDriver, offers: util.List[Offer]): Unit = {
    logger.info("[resourceOffers]\n" + Str.offers(offers))

    onResourceOffers(offers.toList)
  }

  private def onResourceOffers(offers: List[Offer]) {
    offers.foreach { offer =>
      val declinedReasons = acceptOffer(offer)

      if (declinedReasons.size == 3) {
        driver.declineOffer(offer.getId)
        logger.info(s"Declined offer:\n${declinedReasons.mkString("\n")}")
      } else if (declinedReasons.nonEmpty) {
        logger.info(s"Some components have declined an offer:\n${declinedReasons.mkString("\n")}")
      }
    }

    reconcileTasks()
    Scheduler.cluster.save()
  }

  private[zipkin] def acceptOffer(offer: Offer): List[String] = {
    val tryAccept: List[() => Option[String]] = List({ () =>
      acceptOfferForComponent(offer, cluster.collectors.toList, "collector")
    }, { () =>
      acceptOfferForComponent(offer, cluster.queryServices.toList, "query")
    }, { () =>
      acceptOfferForComponent(offer, cluster.webServices.toList, "web")
    })
    val declineReasons = tryAccept.foldLeft(ListBuffer[String]()) { (list, elem) =>
      elem() match {
        case Some(declineReason) => list.append(declineReason); list
        case None => return list.toList
      }
    }
    declineReasons.toList
  }

  private[zipkin] def acceptOfferForComponent[E <: ZipkinComponent](offer: Offer, components: List[E], componentName: String): Option[String] = {
    components.filter(_.state == Stopped) match {
      case Nil => Some(s"all $componentName instances are running")
      case nonEmptyComponents =>
        val reason = nonEmptyComponents.flatMap { component =>
          component.matches(offer, otherAttributes = otherTasksAttributes) match {
            case Some(declineReason) => Some(s"$componentName instance ${component.id}: $declineReason")
            case None =>
              launchTask(component, offer)
              return None
          }
        }.mkString(", ")

        if (reason.isEmpty) None else Some(reason)
    }
  }

  private[zipkin] def otherTasksAttributes(name: String): List[String] = {

    cluster.fetchAllComponents.filter(_.task != null).flatMap { zc =>
      if (name == "hostname") {
        zc.task.attributes.get(name)
      } else {
        Option(zc.config.hostname)
      }
    }.toList
  }

  private def launchTask[E <: ZipkinComponent](component: E, offer: Offer) {
    val task = component.createTask(offer)
    val taskId = task.getTaskId.getValue
    val attributes = offer.getAttributesList.toList.filter(_.hasText).map(attr => attr.getName -> attr.getText.getValue).toMap

    component.task = Task(taskId, task.getSlaveId.getValue, task.getExecutor.getExecutorId.getValue, attributes)
    component.state = Staging
    driver.launchTasks(util.Arrays.asList(offer.getId), util.Arrays.asList(task), Filters.newBuilder().setRefuseSeconds(1).build)

    logger.info(s"Starting Zipkin ${component.componentName} instance ${component.id}: launching task $taskId for offer ${offer.getId.getValue}")
  }

  def offerRescinded(driver: SchedulerDriver, id: OfferID): Unit = {
    logger.info("[offerRescinded] " + Str.id(id.getValue))
  }

  def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    logger.info("[statusUpdate] " + Str.taskStatus(status))

    onServerStatus(driver, status)
  }

  def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]): Unit = {
    logger.info("[frameworkMessage] executor:" + Str.id(executorId.getValue) + " slave:" + Str.id(slaveId.getValue) + " data: " + new String(data))
    // Here's where tracing goes
  }

  def disconnected(driver: SchedulerDriver): Unit = {
    logger.info("[disconnected]")
    this.driver = null
  }

  def slaveLost(driver: SchedulerDriver, id: SlaveID): Unit = {
    logger.info("[slaveLost] " + Str.id(id.getValue))
  }

  def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int): Unit = {
    logger.info("[executorLost] executor:" + Str.id(executorId.getValue) + " slave:" + Str.id(slaveId.getValue) + " status:" + status)
  }

  def error(driver: SchedulerDriver, message: String): Unit = {
    logger.info("[error] " + message)
  }

  private def onServerStatus(driver: SchedulerDriver, status: TaskStatus) {
    val taskId = status.getTaskId.getValue
    val components = ZipkinComponent.getComponentFromTaskId(taskId) match {
      case "collector" => cluster.collectors
      case "query" => cluster.queryServices
      case "web" => cluster.webServices
    }
    val zipkinComponent = components.find(ZipkinComponent.idFromTaskId(taskId) == _.id)

    status.getState match {
      case TaskState.TASK_RUNNING =>
        new Thread {
          override def run() {
            onServerStarted(zipkinComponent, driver, status)
          }
        }.start()
      case TaskState.TASK_LOST | TaskState.TASK_FAILED | TaskState.TASK_ERROR |
           TaskState.TASK_FINISHED | TaskState.TASK_KILLED =>
        onServerStop(zipkinComponent, status, taskId)
      case _ => logger.warn("Got unexpected task state: " + status.getState)
    }

    Scheduler.cluster.save()
  }

  private def onServerStop[E <: ZipkinComponent](zipkinComponent: Option[E], status: TaskStatus, taskId: String) {
    zipkinComponent.flatMap(zc => Option(zc.stickiness)).foreach(_.registerStop())
    status.getState match {
      case TaskState.TASK_LOST | TaskState.TASK_FAILED | TaskState.TASK_ERROR =>
        onServerFailed(zipkinComponent, status)
      case TaskState.TASK_FINISHED | TaskState.TASK_KILLED => logger.info(s"Task $taskId has finished")
      case _ => logger.warn("Got unexpected task state: " + status.getState)
    }
  }

  private def onServerStarted[E <: ZipkinComponent](serverOpt: Option[E], driver: SchedulerDriver, status: TaskStatus) {
    serverOpt match {
      case Some(server) =>
        this.synchronized {
          if (server.state != Running) {
            logger.info(s"Set zipkin ${server.componentName} ${server.id} as running")
            server.state = Running
            Option(server.stickiness).foreach(_.registerStart(server.config.hostname, server.fetchRunningInstancePort()))
          }
        }
      case None =>
        logger.info(s"Got ${status.getState} for unknown/stopped server, killing task ${status.getTaskId}")
        driver.killTask(status.getTaskId)
    }
  }

  private def onServerFailed[E <: ZipkinComponent](serverOpt: Option[E], status: TaskStatus) {
    serverOpt match {
      case Some(server) => server.state = Stopped
      case None => logger.info(s"Got ${status.getState} for unknown/stopped server with task ${status.getTaskId}")
    }
  }

  private[zipkin] def stopInstance[E <: ZipkinComponent](component: E) = {
    if (component.state == Staging || component.state == Running)
      driver.killTask(TaskID.newBuilder().setValue(component.task.id).build())

    component.state = Added
    component.task = null
    component
  }

  def start() {
    initLogging()
    logger.info(s"Starting ${getClass.getSimpleName}:\n$Config")

    cluster.load()
    HttpServer.start()

    val frameworkBuilder = FrameworkInfo.newBuilder()
    frameworkBuilder.setUser(Config.user.getOrElse(""))
    cluster.frameworkId.foreach(id => frameworkBuilder.setId(FrameworkID.newBuilder().setValue(id)))
    frameworkBuilder.setRole(Config.frameworkRole)

    frameworkBuilder.setName(Config.frameworkName)
    frameworkBuilder.setFailoverTimeout(Config.frameworkTimeout.ms / 1000)
    frameworkBuilder.setCheckpoint(true)

    var credsBuilder: Credential.Builder = null
    Config.principal.foreach {
      principal =>
        frameworkBuilder.setPrincipal(principal)

        credsBuilder = Credential.newBuilder()
        credsBuilder.setPrincipal(principal)
        Config.secret.foreach { secret => credsBuilder.setSecret(ByteString.copyFromUtf8(secret)) }
    }

    val driver =
      if (credsBuilder != null) new MesosSchedulerDriver(Scheduler, frameworkBuilder.build, Config.getMaster, credsBuilder.build)
      else new MesosSchedulerDriver(Scheduler, frameworkBuilder.build, Config.getMaster)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() = HttpServer.stop()
    })

    val status = if (driver.run eq Status.DRIVER_STOPPED) 0 else 1
    System.exit(status)
  }

  private def initLogging() {
    HttpServer.initLogging()
    BasicConfigurator.resetConfiguration()

    val root = Logger.getRootLogger
    root.setLevel(Level.INFO)

    Logger.getLogger("org.apache.zookeeper").setLevel(Level.WARN)
    Logger.getLogger("org.I0Itec.zkclient").setLevel(Level.WARN)

    val logger = Logger.getLogger(Scheduler.getClass)
    logger.setLevel(if (Config.debug) Level.DEBUG else Level.INFO)

    val layout = new PatternLayout("%d [%t] %-5p %c %x - %m%n")

    val appender = Config.log match {
      case Some(log) => new DailyRollingFileAppender(layout, log.getPath, "'.'yyyy-MM-dd")
      case None => new ConsoleAppender(layout)
    }

    root.addAppender(appender)
  }

  private[zipkin] val RECONCILE_DELAY = 10 seconds
  private[zipkin] val RECONCILE_MAX_TRIES = 3

  private[zipkin] var reconciles = 0
  private[zipkin] var reconcileTime = new Date(0)

  private[zipkin] def reconcileTasks(force: Boolean = false, now: Date = new Date()) {
    if (now.getTime - reconcileTime.getTime >= RECONCILE_DELAY.toMillis) {
      if (!cluster.isReconciling) reconciles = 0
      reconciles += 1
      reconcileTime = now

      if (reconciles > RECONCILE_MAX_TRIES) {
        killReconcilingTasks(cluster.collectors.toList)
        killReconcilingTasks(cluster.queryServices.toList)
        killReconcilingTasks(cluster.webServices.toList)
      } else {
        val statuses = setTasksToReconciling(cluster.collectors.toList, force) ++
          setTasksToReconciling(cluster.queryServices.toList, force) ++
          setTasksToReconciling(cluster.webServices.toList, force)

        if (force || statuses.nonEmpty) driver.reconcileTasks(if (force) Collections.emptyList() else statuses)
      }
    }
  }

  private[zipkin] def killReconcilingTasks[E <: ZipkinComponent](componentList: List[E]): Unit = {
    for (zc <- componentList.filter(b => b.task != null && b.isReconciling)) {
      logger.info(s"Reconciling exceeded $RECONCILE_MAX_TRIES tries for ${zc.componentName} ${zc.id}, sending killTask for task ${zc.task.id}")
      driver.killTask(TaskID.newBuilder().setValue(zc.task.id).build())
      zc.task = null
      // Resurrects the task in case if TASK_LOST update has not been send
      Option(zc.stickiness).foreach(_.registerStop())
      zc.state = Stopped
    }
  }

  private[zipkin] def setTasksToReconciling[E <: ZipkinComponent](componentList: List[E], force: Boolean): List[TaskStatus] = {
    componentList.filter(x => x.task != null && (force || x.isReconciling)).map { zc =>
      zc.state = Reconciling
      logger.info(s"Reconciling $reconciles/$RECONCILE_MAX_TRIES state of ${zc.componentName} ${zc.id}, task ${zc.task.id}")
      TaskStatus.newBuilder()
        .setTaskId(TaskID.newBuilder().setValue(zc.task.id))
        .setState(TaskState.TASK_STAGING)
        .build
    }
  }
}
