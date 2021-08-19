package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import com.typesafe.config.ConfigFactory
import models.Protocol.ProtocolParam
import play.api._
import play.api.libs.json._

import java.io.File

case class DeviceConfig(slaveID: Int, calibrationTime: Option[LocalTime], monitorTypes: Option[List[String]],
                        raiseTime: Option[Int], downTime: Option[Int], holdTime: Option[Int],
                        calibrateZeoSeq: Option[Int], calibrateSpanSeq: Option[Int],
                        calibratorPurgeSeq: Option[Int], calibratorPurgeTime: Option[Int],
                        calibrateZeoDO: Option[Int], calibrateSpanDO: Option[Int], skipInternalVault: Option[Boolean])

case class DataReg(monitorType: String, address: Int)

case class CalibrationReg(zeroAddress: Int, spanAddress: Int)

case class TcpModelReg(dataRegs: List[DataReg], calibrationReg: CalibrationReg,
                       inputRegs: List[InputReg], holdingRegs: List[HoldingReg],
                       modeRegs: List[DiscreteInputReg], warnRegs: List[DiscreteInputReg], coilRegs: List[CoilReg])
case class TcpModbusDeviceModel(id: String, description: String, tcpModelReg: TcpModelReg)

object TcpModbusDrv2 {
  val deviceTypeHead = "TcpModbus."
  def getInstrumentTypeList(environment: play.api.Environment, factory: TcpModbusDrv2.Factory, monitorTypeOp: MonitorTypeOp) = {
    val docRoot = environment.rootPath + "/conf/TcpModbus/"
    val files = new File(docRoot).listFiles()
    for (file <- files) yield {
      val device: TcpModbusDeviceModel = getDeviceModel(file)
      device.tcpModelReg.dataRegs.foreach(reg=>monitorTypeOp.ensureMonitorType(reg.monitorType))
      InstrumentType(s"${deviceTypeHead}${device.id}", device.description, List(Protocol.tcp),
        new TcpModbusDrv2(device.tcpModelReg), factory)
    }
  }

  def getDeviceModel(modelFile: File): TcpModbusDeviceModel = {
    val driverConfig = ConfigFactory.parseFile(modelFile)
    import java.util.ArrayList

    val id = driverConfig.getString("ID")
    val description = driverConfig.getString("description")
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

    TcpModbusDeviceModel(id, description,
    TcpModelReg(dataRegList.toList, calibrationReg, inputRegList.toList, holdingRegList.toList, modeRegList.toList, warnRegList.toList, coilRegList.toList))
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: TcpModelReg, config: DeviceConfig, @Assisted("host") host: String): Actor
  }
}

class TcpModbusDrv2(tcpModelReg: TcpModelReg) extends DriverOps {
  implicit val cfgReads = Json.reads[DeviceConfig]
  implicit val cfgWrites = Json.writes[DeviceConfig]

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

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[TcpModbusDrv2.Factory])
    val f2 = f.asInstanceOf[TcpModbusDrv2.Factory]
    val config = validateParam(param)
    f2(id, tcpModelReg, config, protocol.host.get)
  }
}