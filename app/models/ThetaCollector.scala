package models

import akka.actor._
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.MonitorType.{CH2O, CO, CO2, H2, H2S, HUMID, NH3, NO, NO2, NOISE, O3, PM10, PM25, PRESS, RAIN, SO2, TEMP, TVOC, WIN_DIRECTION, WIN_SPEED}
import models.Protocol.ProtocolParam
import play.api._
import play.api.libs.json.{JsError, Json}
import play.libs.Scala.None

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Future, blocking}

case class ThetaConfig(monitorTypes: Seq[String])

object ThetaCollector extends DriverOps {
  var count = 0

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

  implicit val configRead = Json.reads[ThetaConfig]
  implicit val configWrite = Json.writes[ThetaConfig]

  override def getMonitorTypes(param: String): List[String] = {
    //val config = validateParam(param)
    //config.monitorTypes.toList
    Seq(WIN_SPEED, WIN_DIRECTION, HUMID, TEMP, PRESS,
      RAIN,
      PM25, PM10, CH2O, TVOC, CO2,
      NOISE, CO, SO2, NO2, O3,
      NO, H2S, H2, NH3).toList
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
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

  import akka.actor._

  case object ReadData

  case object ConnectHost

  case object Collect
}

import javax.inject._

class ThetaCollector @Inject()
(alarmOp: AlarmOp, monitorStatusOp: MonitorStatusOp, instrumentOp: InstrumentOp, system: ActorSystem)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted config: ThetaConfig) extends Actor {

  import ThetaCollector._

  var timer: Cancellable = _

  override def receive: Receive = init()

  self ! ConnectHost

  def init(): Receive = {
    case ConnectHost =>
      Future {
        blocking {
          try {
            assert(protocolParam.protocol == Protocol.serial)
            val com = protocolParam.comPort.get
            val serial = SerialComm.open(com)
            context become connected(MonitorStatus.NormalStat, serial)
            timer = system.scheduler.scheduleOnce(Duration(1, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              alarmOp.log(alarmOp.instStr(id), alarmOp.Level.ERR, s"Unable to open:${ex.getMessage}")
              import scala.concurrent.duration._
              system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }
        }
      }
  }

  def decode(numSeq: Seq[String]): Unit = {
    import MonitorType._
    val ignore = "_"
    val monitorTypeList = Seq(WIN_SPEED, WIN_DIRECTION, HUMID, TEMP, PRESS,
      RAIN, ignore, ignore, ignore, ignore,
      PM25, PM10, CH2O, TVOC, CO2,
      NOISE, CO, SO2, NO2, O3,
      NO, H2S, H2, NH3)
    val result: Seq[Option[MonitorTypeData]] = {
      for ((mt, valueStr) <- monitorTypeList.zip(numSeq)) yield {
        if (mt == ignore)
          None
        else {
          try {
            val value = valueStr.toDouble
            Some(MonitorTypeData(mt, value, MonitorStatus.NormalStat))
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
            val lines = serial.getLine2
            for (line <- lines) {
              val target = line.dropWhile(_ == ">").drop(1)
              val numArray = target.split(",")
              if (numArray.length == 24)
                decode(numArray)
            }

            import scala.concurrent.duration._
            timer = system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
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
    if (timer != null)
      timer.cancel()
  }

}