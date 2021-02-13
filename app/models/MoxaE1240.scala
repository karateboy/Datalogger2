package models

import akka.actor._
import play.api._
import play.api.libs.json._

import javax.inject._

case class MoxaE1240Param(addr: Int, ch: Seq[AiChannelCfg])

@Singleton
class MoxaE1240 @Inject()(monitorTypeOp: MonitorTypeOp) extends DriverOps {
  override def getMonitorTypes(param: String) = {
    val e1240Param = validateParam(param)
    val mtList = e1240Param.ch.filter {
      _.enable
    }.flatMap {
      _.mt
    }.toList
    val rawMtList = mtList map {
      monitorTypeOp.getRawMonitorType(_)
    }

    mtList ++ rawMtList
  }

  override def verifyParam(json: String) = {
    implicit val cfgReads = Json.reads[AiChannelCfg]
    implicit val reads = Json.reads[MoxaE1240Param]

    val ret = Json.parse(json).validate[MoxaE1240Param]
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
            assert(cfg.max.get > cfg.min.get)
            assert(cfg.mtMax.get > cfg.mtMin.get)
            monitorTypeOp.ensureRawMonitorType(cfg.mt.get, "V")
          }
        }
        json
      })
  }

  import Protocol.ProtocolParam

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[MoxaE1240Collector.Factory])
    val f2 = f.asInstanceOf[MoxaE1240Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def validateParam(json: String) = {
    implicit val cfgReads = Json.reads[AiChannelCfg]
    implicit val reads = Json.reads[MoxaE1240Param]
    val ret = Json.parse(json).validate[MoxaE1240Param]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      params => {
        params
      })
  }

  def stop = {

  }

  override def getCalibrationTime(param: String) = None

}