package models

import akka.actor._
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import com.serotonin.modbus4j.{ModbusFactory, ModbusMaster}
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._
import play.api.libs.json.{JsError, Json}
import play.libs.Scala.None

import java.io.{InputStream, OutputStream}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Future, blocking}

case class ThetaConfig(monitorTypes: Seq[String])

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
    val config = validateParam(param)
    config.monitorTypes.toList
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

  case object ConnectHost
  case object Collect
}

import javax.inject._

class ThetaCollector @Inject()
(alarmOp: AlarmOp, monitorStatusOp: MonitorStatusOp, instrumentOp: InstrumentOp, system: ActorSystem)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted mt: String) extends Actor {

  import ThetaCollector._

  override def receive: Receive = init()

  var timer: Cancellable = _

  self ! ConnectHost

  def init() : Receive = {
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

  def connected(state:String, serial: SerialComm):Receive = {
    case Collect =>
      Future {
        blocking {
          try {
            serial.os.write("#01\r\n".getBytes)
            val lines = serial.getLine2
            for(line<-lines) {
              val target = line.dropWhile(_ == ">").drop(1)
              Logger.info(target)
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
    if(timer != null)
      timer.cancel()
  }

}