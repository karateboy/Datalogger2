package models

import akka.actor._
import models.Protocol.ProtocolParam
import play.api._
import play.api.libs.json._

import javax.inject._

// case class ChannelCfg(enable: Boolean, mt: Option[String], scale: Option[Double], repairMode: Option[Boolean])

@Singleton
class Adam6066 @Inject()
(monitorTypeOp: MonitorTypeOp)
  extends DriverOps {

  implicit val cfgReads = Json.reads[Adam6066ChannelCfg]
  implicit val reads = Json.reads[Adam6066Param]
  implicit val w1 = Json.writes[Adam6066ChannelCfg]
  implicit val write = Json.writes[Adam6066Param]

  override def getMonitorTypes(param: String) = {
    val ret = Json.parse(param).validate[Adam6066Param]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      params => {
        val ret = params.chs map {_.mt}

        ret.flatten.toList
      })
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[Adam6066Param]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      params => {
        params
      })
  }

  import Protocol.ProtocolParam

  override def verifyParam(json: String) = {

    val ret = Json.parse(json).validate[Adam6066Param]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Adam6066Collector.Factory])
    val f2 = f.asInstanceOf[Adam6066Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def stop = {

  }

  override def getCalibrationTime(param: String) = None

}