package models

import akka.actor._
import play.api._
import play.api.libs.json._

import javax.inject._

// case class ChannelCfg(enable: Boolean, mt: Option[String], scale: Option[Double], repairMode: Option[Boolean])

case class Adam6017Param(chs: Seq[AiChannelCfg])

@Singleton
class Adam6017 @Inject()
(monitorTypeOp: MonitorTypeOp)
  extends DriverOps {

  implicit val cfgReads = Json.reads[AiChannelCfg]
  implicit val reads = Json.reads[Adam6017Param]

  override def getMonitorTypes(param: String) = {
    val adam6017Param = validateParam(param)
    adam6017Param.chs.filter {
      _.enable
    }.flatMap {
      _.mt
    }.toList.filter { mt => monitorTypeOp.allMtvList.contains(mt) }
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[Adam6017Param]
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
    val ret = Json.parse(json).validate[Adam6017Param]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        if (param.chs.length != 8) {
          throw new Exception("ch # shall be 8")
        }

        for (cfg <- param.chs) {
          if (cfg.enable) {
            // FIXME
            // assert(cfg.mt.isDefined)
            // assert(cfg.scale.isDefined && cfg.scale.get != 0)
          }
        }
        json
      })
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Adam6017Collector.Factory])
    val f2 = f.asInstanceOf[Adam6017Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def stop = {

  }

  override def getCalibrationTime(param: String) = None

}