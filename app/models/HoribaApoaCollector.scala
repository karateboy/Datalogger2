package models

import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import models.Protocol.{ProtocolParam, tcp}
import play.api._
import play.api.libs.json.{JsError, Json}

object HoribaApoaCollector extends DriverOps {
  val logger: Logger = Logger(this.getClass)
  import HoribaCollector._

  override def verifyParam(json: String): String = {
    val ret = Json.parse(json).validate[HoribaConfig]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def getMonitorTypes(param: String): List[String] = List(MonitorType.O3)


  import Protocol.ProtocolParam
  import akka.actor._

  override def getCalibrationTime(param: String): Option[LocalTime] = {
    val config = validateParam(param)
    config.calibrationTime
  }

  def validateParam(json: String): HoribaConfig = {
    val ret = Json.parse(json).validate[HoribaConfig]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[HoribaApoaCollector.Factory])
    val f2 = f.asInstanceOf[HoribaApoaCollector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  override def id: String = "HoribaAPOA"

  override def description: String = "Horiba APOA (O3)"

  override def protocol: List[String] = List(tcp)

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: HoribaConfig): Actor
  }

}

import javax.inject._

class HoribaApoaCollector @Inject()
(instrumentOp: InstrumentDB, instrumentStatusOp: InstrumentStatusDB,
 calibrationOp: CalibrationDB, monitorTypeOp: MonitorTypeDB)
(@Assisted id: String, @Assisted protocol: ProtocolParam, @Assisted config: HoribaConfig) extends
  HoribaCollector(instrumentOp, instrumentStatusOp, calibrationOp, monitorTypeOp)(
    id = id,
    protocol = protocol,
    config = config,
    name = "O3",
    mtList = List(MonitorType.O3),
    RECEIVE_ID = '5'.toByte,
    logger = Logger(HoribaApoaCollector.getClass))