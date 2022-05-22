package models

import akka.actor._
import models.Protocol.tcp
import play.api._
import play.api.libs.json._

import javax.inject._

case class MoxaE1240Param(addr: Int, chs: Seq[AiChannelCfg])

@Singleton
class MoxaE1240 @Inject()() extends DriverOps {
  override def getMonitorTypes(param: String) = {
    val e1240Param = validateParam(param)
    val mtList = e1240Param.chs.filter {
      _.enable
    }.flatMap {
      _.mt
    }.toList

    mtList
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
        if (param.chs.length != 8) {
          throw new Exception("ch # shall be 8")
        }

        for (cfg <- param.chs) {
          if (cfg.enable) {
            assert(cfg.mt.isDefined)
            assert(cfg.max.get > cfg.min.get)
            assert(cfg.mtMax.get > cfg.mtMin.get)
          }
        }
        json
      })
  }

  import Protocol.ProtocolParam

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
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

  override def id: String = "MOXAE1240"

  override def description: String = "MOXA E1240"

  override def protocol: List[String] = List(tcp)
}