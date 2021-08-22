package models

import akka.actor.{Actor, ActorSystem, Cancellable}
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.ThetaCollector.OpenComPort
import play.api.Logger
import play.api.libs.json.{JsError, Json}

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, MINUTES}

case class Sabio4010Config(addr: String)

object Sabio4010 extends DriverOps {
  implicit val reads = Json.reads[Sabio4010Config]
  override def id: String = "sabio4010"

  override def description: String = "Sabio 4010 Calibrator"

  override def protocol: List[Protocol.Value] = List(Protocol.serial)

  override def verifyParam(param: String): String = {
    val ret = Json.parse(param).validate[Sabio4010Config]
    ret.fold(err => {
      Logger.error(JsError.toJson(err).toString())
      throw new Exception(JsError.toJson(err).toString())
    },
      config => param)
  }

  override def getMonitorTypes(param: String): List[String] = List.empty[String]

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = None

  override def factory(id: String, protocol: Protocol.ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val config = Json.parse(param).validate[Sabio4010Config].get
    f2(id, protocol, config)
  }

  override def isCalibrator: Boolean = true

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, config:Sabio4010Config): Actor
  }
}

object Sabio4010Collector {
  case object OpenComPort

}

class Sabio4010Collector @Inject()(system: ActorSystem)
                                  (@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted config:Sabio4010Config) extends Actor {
  var cancelable: Cancellable = _
  var comm: SerialComm = _

  self ! OpenComPort

  def getCmdString(cmd:String, param:String): String = {
    val cmdStr=
      if(param.isEmpty)
        s"@$cmd,${config.addr}"
      else
        s"@$cmd,${config.addr},${param}"

    assert(!cmdStr.endsWith(","))
    def checkSumString: String = {
      val sum: Int = cmdStr.map(c=>c.toInt).sum
      val residual = sum % 256
      "%h".format(residual)
    }
    s"${cmdStr}${checkSumString}\r"
  }

  override def receive: Receive = {
    case OpenComPort =>
      try {
        comm = SerialComm.open(protocolParam.comPort.get)
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
          Logger.info("Try again 1 min later...")
          //Try again
          cancelable = system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort)
      }

    case ExecuteSeq(seq, on) =>
  }

}
