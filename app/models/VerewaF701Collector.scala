package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import Protocol.{ProtocolParam, serial}

import scala.concurrent.ExecutionContext.Implicits.global
import ModelHelper._
import com.google.inject.assistedinject.Assisted
import models.mongodb.AlarmOp
import play.api.libs.json.{JsError, Json}

case class F701_20Config(monitorType: String)
object VerewaF701Collector extends DriverOps{
  case object OpenComPort
  case object ReadData

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, mt: String)(implicit context: ActorContext) = {
    import Protocol.ProtocolParam
    val actorName = s"F701_${mt}_${count}"
    count += 1
    val collector = context.actorOf(Props(classOf[VerewaF701Collector], id, protocolParam, mt), name = actorName)
    Logger.info(s"$actorName is created.")

    collector
  }

  lazy val supportedMonitorTypes = List(("PM25"), ("PM10"))
  implicit val configRead = Json.reads[F701_20Config]
  implicit val configWrite = Json.writes[F701_20Config]

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[F701_20Config]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def getCalibrationTime(param: String) = {
    None
  }

  /*
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext): ActorRef = {
    val mtList = getMonitorTypes(param)
    assert(mtList.length == 1)
    VerewaF701Collector.start(id, protocol, mtList(0))
  }*/

  override def getMonitorTypes(param: String): List[String] = {
    val config = validateParam(param)
    List(config.monitorType)
  }

  import akka.actor._

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[F701_20Config]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: F701_20Config): Actor
  }

  override def id: String = "VEREWA_F701"

  override def description: String = "Verewa F701-20"

  override def protocol: List[String] = List(serial)
}

import javax.inject._
class VerewaF701Collector @Inject()
(alarmOp: AlarmDB, monitorStatusOp: MonitorStatusDB, instrumentOp: InstrumentDB, system: ActorSystem)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted config: F701_20Config) extends Actor {
  import VerewaF701Collector._
  import scala.concurrent.duration._
  var cancelable = system.scheduler.scheduleOnce(Duration(1, SECONDS), self, OpenComPort)
  var serialOpt: Option[SerialComm] = None

  import scala.concurrent.Future
  import scala.concurrent.blocking

  var collectorStatus = MonitorStatus.NormalStat
  var instrumentStatus: Byte = 0
  def checkStatus(status: Byte) {
    import alarmOp._
    if ((instrumentStatus & 0x1) != (status & 0x1)) {
      if ((status & 0x1) == 1)
        alarmOp.log(alarmOp.instrumentSrc(id), alarmOp.Level.INFO, "standby")
      else
        alarmOp.log(instrumentSrc(id), Level.INFO, "concentration")
    }

    if ((instrumentStatus & 0x2) != (status & 0x2)) {
      if ((status & 0x2) == 1)
        alarmOp.log(alarmOp.instrumentSrc(id), alarmOp.Level.INFO, "Film measurement")
    }

    if ((instrumentStatus & 0x4) != (status & 0x4)) {
      if ((status & 0x4) == 1)
        alarmOp.log(alarmOp.instrumentSrc(id), Level.INFO, "Zero point measurement")
    }

    if ((instrumentStatus & 0x8) != (status & 0x8)) {
      if ((status & 0x8) == 1)
        log(instrumentSrc(id), Level.INFO, "Reference measurement (Reference check)")
    }

    if ((instrumentStatus & 0x80) != (status & 0x80)) {
      if ((status & 0x80) == 1)
        log(instrumentSrc(id), Level.INFO, "Measurement")
    }

    instrumentStatus = status
  }

  def checkErrorStatus(error: Byte) {
    import alarmOp._
    if ((error & 0x1) != 0) {
      log(instrumentSrc(id), Level.WARN, "Volume error")
    }

    if ((error & 0x2) != 0) {
      log(instrumentSrc(id), Level.WARN, "Vacuum break")
    }

    if ((error & 0x4) != 0) {
      log(instrumentSrc(id), Level.WARN, "Volume<500 liter and 250 liter at 1/2 h sample time, respectively.")
    }

    if ((error & 0x10) != 0) {
      log(instrumentSrc(id), Level.ERR, "Volume < 25 liter")
    }

    if ((error & 0x20) != 0) {
      log(instrumentSrc(id), Level.ERR, "Change battery")
    }

    if ((error & 0x40) != 0) {
      log(instrumentSrc(id), Level.ERR, "Filter crack")
    }
  }

  def checkErrorStatus(channel: Int, error: Byte) {
    import alarmOp._
    if (channel == 0)
      checkErrorStatus(error)
    else {
      val msgMap = Map(
        1 -> "Sampled volume: Volume sensor defective.",
        2 -> "Sampled mass: GM tube or amplifier defective.",
        3 -> "Filter adapter temperature: Temperature measurement defective.",
        4 -> "Temperature ambient air: Air pressure measurement defective (sensor not connected, parametrization).",
        5 -> "Ambient air pressure: Air pressure measurement defective (sensor not connected, parametrization)",
        6 -> "Relative humidity: Relative humidity measurement defective (sensor not connected, parametrization)",
        7 -> "Temperature sample tube: Temperature measurement erroneous (PT100 not connected)",
        8 -> "Temperature sample tube: Temperature measurement erroneous",
        9 -> "0 rate: GM tube or amplifier defective",
        10 -> "M rate: GM tube or amplifier defective")

      if ((error & 0x1) != 0) {
        if (msgMap.contains(channel)) {
          //FIXME disable error alarm
          //log(instStr(id), Level.ERR, msgMap(channel))
        }
      }
    }
  }

  def receive = {
    case OpenComPort =>
      try {
        serialOpt = Some(SerialComm.open(protocolParam.comPort.get))
        cancelable = system.scheduler.schedule(scala.concurrent.duration.Duration(3, SECONDS), Duration(3, SECONDS), self, ReadData)
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
          Logger.info("Reopen 1 min latter...")
          cancelable = system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort)
      }

    case ReadData =>
      Future {
        blocking {
          val cmd = HessenProtocol.dataQuery
          for(serial<-serialOpt){
            serial.port.writeBytes(cmd)
            val replies = serial.getMessageByCrWithTimeout(timeout = 3)
            for (reply <- replies if reply.contains("MD")) {
              val pos = reply.indexOf("MD")
              val measureList = HessenProtocol.decode(reply.drop(pos))
              for {
                ma_ch <- measureList.zipWithIndex
                measure = ma_ch._1
                channel = ma_ch._2
              } {
                if (channel == 0) {
                  checkStatus(measure.status)
                  //Logger.debug(s"$mt, $measure.value, $collectorStatus")
                  context.parent ! ReportData(List(MonitorTypeData(config.monitorType, measure.value, collectorStatus)))
                }

                checkErrorStatus(channel, measure.error)
              }
              //Log instrument status...

            }
          }
        }
      } onFailure errorHandler

    case SetState(id, state) =>
      Logger.info(s"SetState(${monitorStatusOp.map(state).desp})")
      collectorStatus = state
      instrumentOp.setState(id, state)
  }

  override def postStop(): Unit = {
    cancelable.cancel()
    serialOpt map {
      serial =>
        SerialComm.close(serial)
    }

  }

}