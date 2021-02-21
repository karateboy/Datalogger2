package models

import akka.actor._
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import jssc.SerialPort
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._
import play.api.libs.json.{JsError, Json}
import play.libs.Scala.None

import scala.concurrent.ExecutionContext.Implicits.global

case class ThetaConfig(model: String)

object ThetaCollector extends DriverOps {
  var count = 0

  case object OpenComPort
  case object ReadData

  implicit val configRead = Json.reads[ThetaConfig]
  implicit val configWrite = Json.writes[ThetaConfig]

  def start(id: String, protocolParam: ProtocolParam, mt: String)(implicit context: ActorContext) = {
    val actorName = s"F701_${mt}_${count}"
    count += 1
    val collector = context.actorOf(Props(classOf[VerewaF701Collector], id, protocolParam, mt), name = actorName)
    Logger.info(s"$actorName is created.")

    collector
  }

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
    // val config = validateParam(param)
    List("WD_SPEED", "WD_DIR", "PRESS", "HUMID", "TEMP")
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

  import akka.actor._

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: ThetaConfig): Actor
  }
}

import javax.inject._

class ThetaCollector @Inject()
(alarmOp: AlarmOp, monitorStatusOp: MonitorStatusOp, instrumentOp: InstrumentOp, system: ActorSystem)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted mt: String) extends Actor {

  import VerewaF701Collector._

  import scala.concurrent.duration._

  var cancelable = system.scheduler.scheduleOnce(Duration(1, SECONDS), self, OpenComPort)
  var serialOpt: Option[SerialComm] = None[SerialComm]

  import scala.concurrent.{Future, blocking}

  var collectorStatus = MonitorStatus.NormalStat

  def handlMessage(lines: Seq[String]) = {
    lines map {
      line =>
        val data: Array[Option[MonitorTypeData]] =
          for (part <- line.split(",")) yield {
            part match {
              case x: String if x.split("=").length != 2 =>
                None
              case x: String if x.charAt(0).isDigit =>
                None
              case x: String =>
                val token = x.split("=")
                val mt = token(0)
                val vstr = token(1).trim
                val v = vstr.substring(0, vstr.length - 1).toDouble
                mt match {
                  case "Dm" =>
                    Some(MonitorTypeData("WD_DIR", v, collectorStatus))
                  case "Sm" =>
                    Some(MonitorTypeData("WD_SPEED", v, collectorStatus))
                  case "Pa" =>
                    Some(MonitorTypeData("PRESS", v, collectorStatus))
                  case "Ua" =>
                    Some(MonitorTypeData("HUMID", v, collectorStatus))
                  case "Ta" =>
                    Some(MonitorTypeData("TEMP", v, collectorStatus))
                  case _ =>
                    None
                }
            }
          } //end of yield
        val mtData: Array[MonitorTypeData] = data.flatten
        context.parent ! ReportData(mtData.toList)
    }
  }

  def receive = {
    case OpenComPort =>
      try {
        serialOpt = Some(SerialComm.open(protocolParam.comPort.get, SerialPort.BAUDRATE_19200))
        cancelable = system.scheduler.schedule(scala.concurrent.duration.Duration(1, SECONDS), Duration(1, SECONDS), self, ReadData)
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
          Logger.info("Reopen 1 min latter...")
          cancelable = system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort)
      }

    case ReadData =>
      Future {
        blocking {
          for(serial <- serialOpt){
            val lines = serial.getLine2
            handlMessage(lines)
          }
        } //end of blocking
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