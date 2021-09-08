package models
import akka.actor.{Actor, ActorSystem}
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

case class DuoMonitorType(id: String, desc:String, configID:String)

case class DuoConfig(monitorTypes:Seq[DuoMonitorType])

object Duo extends DriverOps {
  implicit val readMt= Json.reads[DuoMonitorType]
  implicit val writeMt = Json.writes[DuoMonitorType]
  implicit val reads = Json.reads[DuoConfig]
  implicit val writes = Json.writes[DuoConfig]

  override def id: String = "duo"

  override def description: String = "01dB Duo"

  override def protocol: List[Protocol.Value] = List(Protocol.tcp)

  override def verifyParam(param: String): String = {
    val ret = Json.parse(param).validate[DuoConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      config => {
        //Append monitor Type into config
      })
    param
  }

  override def getMonitorTypes(param: String): List[String] = {
    val ret = Json.parse(param).validate[DuoConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      config => {
        config.monitorTypes.map{_.id}.toList
      })
  }

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = None

  trait Factory {
    def apply(instId: String, protocolParam: ProtocolParam, config: DuoConfig): Actor
  }

  override def factory(id: String, protocolParam: Protocol.ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Duo.Factory])
    val f2 = f.asInstanceOf[Duo.Factory]
    val config: DuoConfig = Json.parse(param).validate[DuoConfig].get
    f2(id, protocolParam, config)
  }

  case object ReadData

}

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
class DuoCollector @Inject()
(alarmOp: AlarmOp, monitorStatusOp: MonitorStatusOp, instrumentOp: InstrumentOp, wsClient: WSClient, system: ActorSystem)
(@Assisted instId: String, @Assisted protocolParam:ProtocolParam, @Assisted config: DuoConfig) extends Actor {
  import Duo._

  import scala.concurrent.duration._
  val timer = system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData)
  val host = protocolParam.host.get

  override def receive: Receive = {
    case ReadData=>
      val f = wsClient.url(s"http://${host}/pub/GetRealTimeValues.asp").get()
      for(ret<-f){
        // FIXME
        ret.xml
      }
  }

  override def postStop() {
    timer.cancel()
    super.postStop()
  }
}
