package models

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import com.typesafe.config.ConfigFactory
import models.Protocol.ProtocolParam
import models.TcpModbusDrv.{Factory, readModelSetting, validateParam}
import play.api._
import play.api.libs.json._

object TcpModbusDrv2 {
  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: TcpModelReg, config: DeviceConfig, @Assisted("host") host: String): Actor
  }
}

class TcpModbusDrv2(model: String) extends DriverOps {
  implicit val cfgReads = Json.reads[DeviceConfig]
  implicit val cfgWrites = Json.writes[DeviceConfig]
  val tcpModelReg = readModelSetting

  def readModelSetting = {
    val driverConfig = ConfigFactory.load(model)
    import java.util.ArrayList

    val inputRegList = {
      val inputRegAnyList = driverConfig.getAnyRefList(s"Input.reg")
      for {
        i <- 0 to inputRegAnyList.size() - 1
        reg = inputRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        InputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String], v.get(2).asInstanceOf[String])
      }
    }

    val holdingRegList = {
      val holdingRegAnyList = driverConfig.getAnyRefList(s"Holding.reg")
      for {
        i <- 0 to holdingRegAnyList.size() - 1
        reg = holdingRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        HoldingReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String], v.get(2).asInstanceOf[String])
      }
    }

    val modeRegList = {
      val modeRegAnyList = driverConfig.getAnyRefList(s"DiscreteInput.mode")
      for {
        i <- 0 to modeRegAnyList.size() - 1
        reg = modeRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DiscreteInputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }

    val warnRegList = {
      val warnRegAnyList = driverConfig.getAnyRefList(s"DiscreteInput.warning")
      for {
        i <- 0 to warnRegAnyList.size() - 1
        reg = warnRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DiscreteInputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }

    val coilRegList = {
      val coilRegAnyList = driverConfig.getAnyRefList(s"Coil.reg")
      for {
        i <- 0 to coilRegAnyList.size() - 1
        reg = coilRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        CoilReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }

    val dataRegList = {
      val coilRegAnyList = driverConfig.getAnyRefList(s"Data")
      for {
        i <- 0 to coilRegAnyList.size() - 1
        reg = coilRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DataReg(v.get(0).asInstanceOf[String], v.get(1).asInstanceOf[Int])
      }
    }

    val calibrationReg = {
      val addressList = driverConfig.getAnyRefList(s"Calibration")
      CalibrationReg(addressList.get(0).asInstanceOf[Int],
        addressList.get(1).asInstanceOf[Int])
    }

    TcpModelReg(dataRegList.toList, calibrationReg, inputRegList.toList, holdingRegList.toList, modeRegList.toList, warnRegList.toList, coilRegList.toList)
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[DeviceConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        val mt = tcpModelReg.dataRegs.map(_.monitorType)
        val newParam = DeviceConfig(param.model, param.slaveID, param.calibrationTime, Some(mt),
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
    if (config.monitorTypes.isDefined)
      config.monitorTypes.get.toList
    else
      List.empty[String]
  }

  override def getCalibrationTime(param: String) = {
    val config = validateParam(param)
    config.calibrationTime
  }

  def validateParam(json: String): DeviceConfig = {
    val ret = Json.parse(json).validate[DeviceConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val config = validateParam(param)
    val tcpModelReg = readModelSetting
    f2(id, tcpModelReg, config, protocol.host.get)
  }
}