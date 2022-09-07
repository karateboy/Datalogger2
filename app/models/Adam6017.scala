package models

import akka.actor._
import models.Protocol.tcp
import play.api._
import play.api.libs.json._

import javax.inject._

case class Adam6017Param(chs: Seq[AiChannelCfg], doChannels: Seq[SignalConfig])

@Singleton
class Adam6017 @Inject()(monitorTypeOp: MonitorTypeDB) extends DriverOps {

  implicit val signalConfigRead = Json.reads[SignalConfig]
  implicit val cfgReads = Json.reads[AiChannelCfg]
  implicit val reads = Json.reads[Adam6017Param]

  override def getMonitorTypes(param: String) = {
    val adam6017Param = validateParam(param)
    val aiMonitorTypes = adam6017Param.chs.filter {
      _.enable
    }.flatMap {
      _.mt
    }.toList.filter { mt => monitorTypeOp.allMtvList.contains(mt) }
    val doMonitorTypes = adam6017Param.doChannels.flatMap(_.monitorType)
    aiMonitorTypes ++ doMonitorTypes
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
            assert(cfg.mt.isDefined)
            assert(cfg.max.isDefined && cfg.min.isDefined)
            assert(cfg.mtMax.isDefined && cfg.mtMin.isDefined)
          }
        }

        if(param.doChannels.length != 2) {
          throw new Exception("DO # shall be 2")
        }

        json
      })
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Adam6017Collector.Factory])
    val f2 = f.asInstanceOf[Adam6017Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def stop = {

  }

  override def getCalibrationTime(param: String) = None

  override def id: String = "adam6017"

  override def description: String = "Adam 6017"

  override def protocol: List[String] = List(tcp)

  override def isDoInstrument: Boolean = true
}