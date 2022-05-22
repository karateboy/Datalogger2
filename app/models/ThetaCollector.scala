package models

import akka.actor._
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.MonitorType._
import models.Protocol.{ProtocolParam, serial}
import models.mongodb.AlarmOp
import play.api._
import play.api.libs.json.{JsError, Json}
import play.libs.Scala.None

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Future, blocking}

case class CalibrationConfig(monitorType: String, value: Double)

case class ThetaConfig(calibrations: Seq[CalibrationConfig])

object ThetaCollector extends DriverOps {

  var count = 0
  implicit val calibrationWrite = Json.writes[CalibrationConfig]
  implicit val calibrationRead = Json.reads[CalibrationConfig]
  implicit val configRead = Json.reads[ThetaConfig]
  implicit val configWrite = Json.writes[ThetaConfig]

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[ThetaConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def getCalibrationTime(param: String): Option[LocalTime] = None[LocalTime]

  override def getMonitorTypes(param: String): List[String] = {
    //val config = validateParam(param)
    //config.monitorTypes.toList
    Seq(WIN_SPEED, WIN_DIRECTION, HUMID, TEMP, PRESS,
      RAIN, SOLAR,
      PM25, PM10, CH2O, TVOC, CO2, CO, SO2, NO2, O3,
      NO, H2S, H2, NH3).toList
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[ThetaConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: ThetaConfig): Actor
  }

  case object OpenComPort

  case object ConnectHost

  case object Collect

  override def id: String = "theta"

  override def description: String = "THETA"

  override def protocol: List[String] = List(serial)
}

import javax.inject._

class ThetaCollector @Inject()
(alarmOp: AlarmDB, instrumentOp: InstrumentDB)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted config: ThetaConfig) extends Actor {

  import ThetaCollector._

  val calibrationMap: Map[String, Double] = {
    val pairs = config.calibrations map { c => c.monitorType -> c.value }
    pairs.toMap
  }
  assert(protocolParam.protocol == Protocol.serial)
  val com = protocolParam.comPort.get
  val serial = SerialComm.open(com)
  var timer: Cancellable = _

  override def receive: Receive = init()

  self ! ConnectHost

  def init(): Receive = {
    case ConnectHost =>
      Future {
        blocking {
          try {
            context become connected(MonitorStatus.NormalStat, serial)
            timer = context.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              alarmOp.log(alarmOp.instrumentSrc(id), alarmOp.Level.ERR, s"Unable to open:${ex.getMessage}")
              import scala.concurrent.duration._
              context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }
        }
      }
  }

  def decode(numSeq: Seq[String], state:String): Unit = {
    import MonitorType._
    val ignore = "_"
    val monitorTypeList = Seq(WIN_SPEED, WIN_DIRECTION, TEMP, HUMID, PRESS,
      RAIN, ignore, ignore, ignore, SOLAR,
      PM25, PM10, CH2O, TVOC, CO2,
      ignore, CO, SO2, NO2, O3,
      NO, H2S, H2, NH3)
    val result: Seq[Option[MonitorTypeData]] = {
      for ((mt, valueStr) <- monitorTypeList.zip(numSeq)) yield {
        if (mt == ignore)
          None
        else {
          try {
            val value: Double = valueStr.toDouble
            val calibration: Double = calibrationMap.get(mt).getOrElse(0)
            val v: Double = value + calibration
            Some(MonitorTypeData(mt, v, state))
          } catch {
            case _: Throwable =>
              None
          }
        }
      }
    }
    context.parent ! ReportData(result.flatten.toList)
  }

  def connected(state: String, serial: SerialComm): Receive = {
    case Collect =>
      Future {
        blocking {
          try {
            serial.os.write("#01\r\n".getBytes)
            val lines = serial.getLineWithTimeout(2)
            for (line <- lines) {
              val target = line.dropWhile(_ == ">").drop(1)
              val numArray = target.split(",")
              if (numArray.length == 24)
                decode(numArray, state)
            }

            import scala.concurrent.duration._
            timer = context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Throwable =>
              Logger.error("Read serial failed", ex)
              serial.close
              context become init()
              self ! ConnectHost
          }
        }
      } onFailure errorHandler

    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      instrumentOp.setState(id, state)
      context become connected(state, serial)
  }

  override def postStop(): Unit = {
    super.postStop()
    serial.close
    if (timer != null)
      timer.cancel()
  }

}