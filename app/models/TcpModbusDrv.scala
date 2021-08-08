package models

import com.github.nscala_time.time.Imports._
import com.typesafe.config.ConfigFactory
import play.api._
import play.api.libs.json._

case class DeviceConfig(slaveID: Int, calibrationTime: Option[LocalTime], monitorTypes: Option[List[String]],
    raiseTime:Option[Int], downTime:Option[Int], holdTime:Option[Int], 
    calibrateZeoSeq:Option[Int], calibrateSpanSeq:Option[Int], 
    calibratorPurgeSeq:Option[Int], calibratorPurgeTime:Option[Int],
    calibrateZeoDO:Option[Int], calibrateSpanDO:Option[Int], skipInternalVault:Option[Boolean])

case class DataReg(monitorType:String, address:Int)
case class CalibrationReg(zeroAddress:Int, spanAddress: Int)
case class TcpModelReg(dataRegs:List[DataReg], calibrationReg: CalibrationReg,
                       inputRegs: List[InputReg], holdingRegs: List[HoldingReg],
                    modeRegs: List[DiscreteInputReg], warnRegs: List[DiscreteInputReg], coilRegs: List[CoilReg])

case class DeviceModelConfig(model: String, monitorTypeIDs: List[String])

abstract class TcpModbusDrv(model: String) extends DriverOps {
  implicit val cfgReads = Json.reads[DeviceConfig]
  implicit val cfgWrites = Json.writes[DeviceConfig]


  def readModelSetting = {
    val driverConfig = ConfigFactory.load(model)
    import java.util.ArrayList

    val inputRegList = {
      val inputRegAnyList = driverConfig.getAnyRefList(s"$model.Input.reg")
      for {
        i <- 0 to inputRegAnyList.size() - 1
        reg = inputRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        InputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String], v.get(2).asInstanceOf[String])
      }
    }

    val holdingRegList = {
      val holdingRegAnyList = driverConfig.getAnyRefList(s"$model.Holding.reg")
      for {
        i <- 0 to holdingRegAnyList.size() - 1
        reg = holdingRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        HoldingReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String], v.get(2).asInstanceOf[String])
      }
    }
    
    val modeRegList = {
      val modeRegAnyList = driverConfig.getAnyRefList(s"$model.DiscreteInput.mode")
      for {
        i <- 0 to modeRegAnyList.size() - 1
        reg = modeRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DiscreteInputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }

    val warnRegList = {
      val warnRegAnyList = driverConfig.getAnyRefList(s"$model.DiscreteInput.warning")
      for {
        i <- 0 to warnRegAnyList.size() - 1
        reg = warnRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DiscreteInputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }
        
    val coilRegList = {
      val coilRegAnyList = driverConfig.getAnyRefList(s"$model.Coil.reg")
      for {
        i <- 0 to coilRegAnyList.size() - 1
        reg = coilRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        CoilReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }

    val dataRegList = {
      val coilRegAnyList = driverConfig.getAnyRefList(s"$model.Data")
      for {
        i <- 0 to coilRegAnyList.size() - 1
        reg = coilRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DataReg(v.get(0).asInstanceOf[String], v.get(1).asInstanceOf[Int])
      }
    }

    val calibrationReg = {
      val addressList = driverConfig.getAnyRefList(s"$model.Calibration")
      CalibrationReg(addressList.get(0).asInstanceOf[Int],
        addressList.get(1).asInstanceOf[Int])
    }

    TcpModelReg(dataRegList.toList, calibrationReg, inputRegList.toList, holdingRegList.toList, modeRegList.toList, warnRegList.toList, coilRegList.toList)
  }

  val tcpModelReg = readModelSetting

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[DeviceConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        val mt = tcpModelReg.dataRegs.map(_.monitorType)
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
    if (config.monitorTypes.isDefined)
      config.monitorTypes.get.toList
    else
      List.empty[String]
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

  override def getCalibrationTime(param: String) = {
    val config = validateParam(param)
    config.calibrationTime
  }
}