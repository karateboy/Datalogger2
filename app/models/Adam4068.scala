package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._
import models.Protocol.serial

object Adam4068 extends DriverOps {
  case class ChannelCfg(enable: Boolean, evtOp: Option[EventOperation.Value], duration: Option[Int])
  case class Adam4068Param(addr: String, ch: Seq[ChannelCfg])

  override def id: String = "adam4068"

  override def description: String = "Adam 4068"

  override def protocol: List[Protocol.Value] = List(serial)

  implicit val cfgReads = Json.reads[ChannelCfg]
  implicit val reads = Json.reads[Adam4068Param]

  override def getMonitorTypes(param: String) = {
    List.empty[String]
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[Adam4068Param]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      paramList => 
        json
      )
  }

  import Protocol.ProtocolParam

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor ={
    assert(f.isInstanceOf[Adam4068Collector.Factory])
    val f2 = f.asInstanceOf[Adam4068Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[Adam4068Param]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      params => {
        params
      })
  }

  override def getCalibrationTime(param: String) = None
}