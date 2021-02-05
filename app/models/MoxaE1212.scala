package models

import akka.actor._
import play.api._
import play.api.libs.json._

import javax.inject._

case class E1212ChannelCfg(enable: Boolean, mt: Option[String], scale: Option[Double], repairMode: Option[Boolean])

case class MoxaE1212Param(addr: Int, ch: Seq[E1212ChannelCfg])

@Singleton
class MoxaE1212 @Inject()
(monitorTypeOp: MonitorTypeOp)
  extends DriverOps {

  implicit val cfgReads = Json.reads[E1212ChannelCfg]
  implicit val reads = Json.reads[MoxaE1212Param]

  override def getMonitorTypes(param: String) = {
    val e1212Param = validateParam(param)
    e1212Param.ch.filter {
      _.enable
    }.flatMap {
      _.mt
    }.toList.filter { mt => monitorTypeOp.allMtvList.contains(mt) }
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[MoxaE1212Param]
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
    val ret = Json.parse(json).validate[MoxaE1212Param]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        if (param.ch.length != 8) {
          throw new Exception("ch # shall be 8")
        }

        for (cfg <- param.ch) {
          if (cfg.enable) {
            assert(cfg.mt.isDefined)
            assert(cfg.scale.isDefined && cfg.scale.get != 0)
          }
        }
        json
      })
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[MoxaE1212Collector.Factory])
    val f2 = f.asInstanceOf[MoxaE1212Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def stop = {

  }

  override def getCalibrationTime(param: String) = None

}