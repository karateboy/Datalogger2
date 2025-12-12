package models

import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import models.MonitorType.{CH4, NMHC, THC}
import models.Protocol.{ProtocolParam, tcp}
import play.api._
import play.api.libs.json.{JsError, Json}

object Horiba370Collector extends DriverOps {
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

  override def getMonitorTypes(param: String): List[String] = List(CH4, NMHC, THC)


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
    assert(f.isInstanceOf[Horiba370Collector.Factory])
    val f2 = f.asInstanceOf[Horiba370Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  override def id: String = "horiba370"

  override def description: String = "Horiba APXX-370"

  override def protocol: List[String] = List(tcp)

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: HoribaConfig): Actor
  }
}

import javax.inject._

class Horiba370Collector @Inject()
(instrumentOp: InstrumentDB, instrumentStatusOp: InstrumentStatusDB,
 calibrationOp: CalibrationDB, monitorTypeOp: MonitorTypeDB)
(@Assisted id: String, @Assisted protocol: ProtocolParam, @Assisted config: HoribaConfig) extends
  HoribaCollector(instrumentOp, instrumentStatusOp, calibrationOp, monitorTypeOp)(id, protocol, config) {

  override val name: String = "CH4/NMHC/THC"
  override val mtList: List[String] = List(MonitorType.CH4, MonitorType.NMHC, MonitorType.THC)
  override val RECEIVE_ID: Byte = '2'.toByte
  override val logger: Logger = Logger(this.getClass)
}