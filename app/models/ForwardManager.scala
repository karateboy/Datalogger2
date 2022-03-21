package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import play.api._
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json._
import play.api.libs.ws.WSClient

import javax.inject.Inject
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}

case class LatestRecordTime(time: Long)

case class ForwardManagerConfig(server: String, monitor: String)

object ForwardManager {
  implicit val latestRecordTimeRead: Reads[LatestRecordTime] = Json.reads[LatestRecordTime]
  var managerOpt: Option[ActorRef] = None
  var count = 0

  def getConfig(configuration: Configuration): Option[ForwardManagerConfig] = {
    try {
      for (serverConfig <- configuration.getConfig("server")
           if serverConfig.getBoolean("enable").getOrElse(false)) yield {
        val server = serverConfig.getString("host").getOrElse("localhost")
        val monitor = serverConfig.getString("monitor").getOrElse("A01")
        ForwardManagerConfig(server, monitor)
      }
    } catch {
      case ex: Exception =>
        Logger.error("failed to get server config", ex)
        None
    }
  }

  def updateInstrumentStatusType = {
    managerOpt map {
      _ ! UpdateInstrumentStatusType
    }

  }

  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }

  case class ForwardHourRecord(start: DateTime, end: DateTime)

  case class ForwardMinRecord(start: DateTime, end: DateTime)

  case object ForwardHour

  case object ForwardMin

  case object ForwardCalibration

  case object ForwardAlarm

  case object ForwardInstrumentStatus

  case object UpdateInstrumentStatusType

  case object GetInstrumentCmd
}

class ForwardManager @Inject()(hourRecordForwarderFactory: HourRecordForwarder.Factory,
                               minRecordForwarderFactory: MinRecordForwarder.Factory,
                               calibrationForwarderFactory: CalibrationForwarder.Factory,
                               alarmForwarderFactory: AlarmForwarder.Factory,
                               instrumentStatusForwarderFactory: InstrumentStatusForwarder.Factory,
                               instrumentStatusTypeForwarderFactory: InstrumentStatusTypeForwarder.Factory)
                              (@Assisted("server") server: String, @Assisted("monitor") monitor: String) extends Actor with InjectedActorSupport {

  import ForwardManager._

  Logger.info(s"create forwarder to $server/$monitor")

  val hourRecordForwarder = injectedChild(hourRecordForwarderFactory(server, monitor), "hourForwarder")

  val minRecordForwarder = injectedChild(minRecordForwarderFactory(server, monitor), "minForwarder")

  val calibrationForwarder = injectedChild(calibrationForwarderFactory(server, monitor), "calibrationForwarder")

  val alarmForwarder = injectedChild(alarmForwarderFactory(server, monitor), "alarmForwarder")

  val instrumentStatusForwarder = injectedChild(instrumentStatusForwarderFactory(server, monitor),
    "instrumentStatusForwarder")

  val statusTypeForwarder = injectedChild(instrumentStatusTypeForwarderFactory(server, monitor),
    "statusTypeForwarder")

  {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(FiniteDuration(30, SECONDS), statusTypeForwarder, UpdateInstrumentStatusType)
  }

  val timer = {
    import context.dispatcher
    context.system.scheduler.schedule(FiniteDuration(30, SECONDS), FiniteDuration(10, MINUTES), instrumentStatusForwarder, ForwardInstrumentStatus)
  }

  val timer2 = {
    import context.dispatcher
    context.system.scheduler.schedule(FiniteDuration(30, SECONDS), FiniteDuration(5, MINUTES), calibrationForwarder, ForwardCalibration)
  }

  val timer3 = {
    import context.dispatcher

    import scala.concurrent.duration._
    context.system.scheduler.schedule(FiniteDuration(30, SECONDS), FiniteDuration(3, MINUTES), alarmForwarder, ForwardAlarm)
  }

  val timer4 = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(FiniteDuration(3, SECONDS), self, GetInstrumentCmd)
  }

  def receive = {
    case ForwardHour =>
      hourRecordForwarder ! ForwardHour

    case fhr: ForwardHourRecord =>
      hourRecordForwarder ! fhr

    case ForwardMin =>
      minRecordForwarder ! ForwardMin

    case fmr: ForwardMinRecord =>
      minRecordForwarder ! fmr

    case ForwardCalibration =>
      Logger.info("Forward Calibration")
      calibrationForwarder ! ForwardCalibration

    case ForwardAlarm =>
      alarmForwarder ! ForwardAlarm

    case ForwardInstrumentStatus =>
      instrumentStatusForwarder ! ForwardInstrumentStatus

    case UpdateInstrumentStatusType =>
      statusTypeForwarder ! UpdateInstrumentStatusType

    /*
  case GetInstrumentCmd =>
    val url = s"http://$server/InstrumentCmd/$monitor"
    val f = ws.url(url).get().map {
      response =>

        val result = response.json.validate[Seq[InstrumentCommand]]
        result.fold(
          error => {
            Logger.error(JsError.toJson(error).toString())
          },
          cmdSeq => {
            if (!cmdSeq.isEmpty) {
              Logger.info("receive cmd from server=>")
              Logger.info(cmdSeq.toString())
              for (cmd <- cmdSeq) {
                cmd.cmd match {
                  case InstrumentCommand.AutoCalibration.cmd =>
                    dataCollectManagerOp.autoCalibration(cmd.instId)

                  case InstrumentCommand.ManualZeroCalibration.cmd =>
                    dataCollectManagerOp.zeroCalibration(cmd.instId)

                  case InstrumentCommand.ManualSpanCalibration.cmd =>
                    dataCollectManagerOp.spanCalibration(cmd.instId)

                  case InstrumentCommand.BackToNormal.cmd =>
                    dataCollectManagerOp.setInstrumentState(cmd.instId, MonitorStatus.NormalStat)
                }
              }
            }
          })

    }
    f onFailure {
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
    f onComplete { x =>
      {
        import scala.concurrent.duration._
        system.scheduler.scheduleOnce(Duration(10, SECONDS), self, GetInstrumentCmd)
      }
    } */
  }

  override def postStop(): Unit = {
    timer.cancel
    timer2.cancel
    timer3.cancel
    timer4.cancel
  }
}