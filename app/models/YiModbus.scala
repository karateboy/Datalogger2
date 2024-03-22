package models

import akka.actor._
import models.Protocol.tcp
import play.api._
import play.api.libs.json._

import javax.inject._

object YiModbus extends DriverOps {
  override def getMonitorTypes(param: String): List[String] = {
    List(MonitorType.WIND_VOLUME, MonitorType.WIND_VOLUME_RAW)
  }

  override def verifyParam(json: String): String = json


  import Protocol.ProtocolParam

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[YiModbusCollector.Factory])
    val f2 = f.asInstanceOf[YiModbusCollector.Factory]
    f2(id, protocol)
  }

  def validateParam(json: String) = {}

  override def getCalibrationTime(param: String): Option[Nothing] = None

  override def id: String = "YiModbus"

  override def description: String = "易增 Modbus"

  override def protocol: List[String] = List(tcp)
}