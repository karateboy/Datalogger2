package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.{JsError, Json}

object AbstractDrv {

}

abstract class AbstractDrv(_id: String, name: String, protocols: List[String]) extends DriverOps {
  import DeviceConfig._
  val logger: Logger = Logger(this.getClass)
  def getDataRegList: List[DataReg]

  override def verifyParam(json: String): String = {
    val ret = Json.parse(json).validate[DeviceConfig]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        val mt = getDataRegList.map(_.monitorType)
        val newParam = DeviceConfig(param.slaveID, param.calibrationTime, Some(mt),
          param.raiseTime, param.downTime, param.holdTime,
          param.calibrateZeoSeq, param.calibrateSpanSeq,
          param.calibratorPurgeSeq, param.calibratorPurgeTime,
          param.calibrateZeoDO, param.calibrateSpanDO,
          param.skipInternalVault)

        Json.toJson(newParam).toString()
      })
  }

  override def getMonitorTypes(param: String): List[String] = {
    val config = validateParam(param)
    config.monitorTypes.getOrElse(List.empty[String])
  }

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = {
    val config = validateParam(param)
    config.calibrationTime
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor

  def validateParam(json: String): DeviceConfig = {
    val ret = Json.parse(json).validate[DeviceConfig]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def id: String = _id

  override def description: String = name

  override def protocol: List[String] = protocols

}
