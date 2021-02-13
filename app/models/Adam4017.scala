package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._
import javax.inject._

case class AiChannelCfg(enable: Boolean, mt: Option[String], max: Option[Double], mtMax: Option[Double],
                        min: Option[Double], mtMin: Option[Double], repairMode: Option[Boolean])
case class Adam4017Param(addr: String, ch: Seq[AiChannelCfg])

@Singleton
class Adam4017 @Inject()(monitorTypeOp: MonitorTypeOp) extends DriverOps {

  implicit val cfgReads = Json.reads[AiChannelCfg]
  implicit val reads = Json.reads[Adam4017Param]

  override def getMonitorTypes(param: String) = {
    val paramList = validateParam(param)
    val mtList = paramList.flatMap { p => p.ch.filter { _.enable }.flatMap { _.mt }.toList }
    val rawMtList = mtList map {
      monitorTypeOp.getRawMonitorType(_)
    }
    mtList ++ rawMtList
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[List[Adam4017Param]]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      paramList => {
        for (param <- paramList) {
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
        }
        json
      })
  }

  import Protocol.ProtocolParam

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor ={
    assert(f.isInstanceOf[Adam4017Collector.Factory])
    val f2 = f.asInstanceOf[Adam4017Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  def stop = {}

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[List[Adam4017Param]]
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