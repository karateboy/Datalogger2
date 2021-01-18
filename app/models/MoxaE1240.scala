package models

import akka.actor._
import play.api._
import play.api.libs.json._

import javax.inject._

case class MoxaE1240Param(addr: Int, ch: Seq[ChannelCfg])

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
    implicit val cfgReads = Json.reads[ChannelCfg]
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

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val driverParam = validateParam(param)

    MoxaE1240Collector.start(id, protocolParam, driverParam)
  }

  def stop = {

  }

  def validateParam(json: String) = {
    implicit val cfgReads = Json.reads[ChannelCfg]
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

  override def getCalibrationTime(param: String) = None
}