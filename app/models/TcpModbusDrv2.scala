package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import com.serotonin.modbus4j.code.DataType
import com.serotonin.modbus4j.serial.SerialPortWrapper
import com.typesafe.config.ConfigFactory
import models.Protocol.ProtocolParam
import play.api._
import play.api.libs.json._

import java.io.{File, InputStream, OutputStream}

case class DeviceConfig(slaveID: Option[Int], calibrationTime: Option[LocalTime] = None,
                        monitorTypes: Option[List[String]] = None,
                        raiseTime: Option[Int]= None,
                        downTime: Option[Int]= None,
                        holdTime: Option[Int]= None,
                        calibrateZeoSeq: Option[String]= None,
                        calibrateSpanSeq: Option[String]= None,
                        calibratorPurgeSeq: Option[String]= None,
                        calibratorPurgeTime: Option[Int]= None,
                        calibrateZeoDO: Option[Int]= None,
                        calibrateSpanDO: Option[Int]= None,
                        skipInternalVault: Option[Boolean]= None)
object DeviceConfig{
  val default = DeviceConfig(Some(1))
}

case class DataReg(monitorType: String, address: Int, multiplier: Float)

case class CalibrationReg(zeroAddress: Int, spanAddress: Int)

case class FilterRule(monitorType:String, min:Double, max:Double)

case class TcpModelReg(dataRegs: List[DataReg], calibrationReg: Option[CalibrationReg],
                       inputRegs: List[InputReg], holdingRegs: List[HoldingReg],
                       modeRegs: List[DiscreteInputReg], warnRegs: List[DiscreteInputReg],
                       coilRegs: List[CoilReg], multiplier: Float = 1, byteSwapMode:Int = DataType.FOUR_BYTE_FLOAT,
                       filterRules: Seq[FilterRule] = Seq.empty[FilterRule])


case class TcpModbusDeviceModel(id: String, description: String, tcpModelReg: TcpModelReg, protocols: Seq[String])

object TcpModbusDrv2 {
  val deviceTypeHead = "TcpModbus."

  def getInstrumentTypeList(environment: play.api.Environment, factory: TcpModbusDrv2.Factory, monitorTypeOp: MonitorTypeDB): Array[InstrumentType] = {
    val docRoot = environment.rootPath + "/conf/TcpModbus/"
    val files = new File(docRoot).listFiles()
    for (file <- files) yield {
      val device: TcpModbusDeviceModel = getDeviceModel(file)

      InstrumentType(
        new TcpModbusDrv2(s"${deviceTypeHead}${device.id}", device.description, device.protocols.toList, device.tcpModelReg), factory)
    }
  }

  def getDeviceModel(modelFile: File): TcpModbusDeviceModel = {
    val driverConfig = ConfigFactory.parseFile(modelFile)
    import java.util.ArrayList
    import scala.collection.JavaConverters._

    val id = driverConfig.getString("ID")
    val description = driverConfig.getString("description")
    val protocols: Seq[String] = try {
      driverConfig.getStringList("protocol").asScala
    }catch{
      case _:Throwable =>
        Seq(Protocol.tcp)
    }

    val multiplier: Float = try {
      driverConfig.getDouble("multiplier").toFloat
    } catch {
      case _: Throwable =>
        1f
    }

    val byteSwapMode: Int = try {
      driverConfig.getInt("byteSwapMode")
    }catch {
      case _:Throwable =>
        DataType.FOUR_BYTE_FLOAT
    }

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
      val dataRegList = driverConfig.getAnyRefList(s"Data")
      for {
        i <- 0 to dataRegList.size() - 1
        reg = dataRegList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        val multiplier = if(v.size() <3 )
          1f
        else {
          val test = v.get(2)
          test match {
            case _: Int =>
              test.asInstanceOf[Int].toFloat
            case _: Double =>
              test.asInstanceOf[Double].toFloat
            case _: Float =>
              test.asInstanceOf[Float]
          }
        }
        DataReg(v.get(0).asInstanceOf[String], v.get(1).asInstanceOf[Int], multiplier)
      }
    }

    val calibrationReg: Option[CalibrationReg] = {
      try {
        val addressList = driverConfig.getAnyRefList(s"Calibration")
        Some(CalibrationReg(addressList.get(0).asInstanceOf[Int],
          addressList.get(1).asInstanceOf[Int]))
      } catch {
        case _: Throwable =>
          None
      }
    }

    val filterRules: Seq[FilterRule] = {
      def getNumber(v:Any):Double={
        v match {
          case v: Integer =>
            v.asInstanceOf[Integer].toDouble
          case v: Double =>
            v.asInstanceOf[Double]
          case v:Float=>
            v.asInstanceOf[Float].toDouble
        }
      }

      try{
        val filterRules = driverConfig.getAnyRefList(s"Filter")
        for {
          i <- 0 to filterRules.size() - 1
          rule = filterRules.get(i)
          v = rule.asInstanceOf[ArrayList[Any]]
        } yield {
          FilterRule(v.get(0).asInstanceOf[String], getNumber(v.get(1)), getNumber(v.get(2)))
        }
      } catch {
        case ex: Throwable =>
          Seq.empty[FilterRule]
      }
    }
    if(filterRules.nonEmpty)
      Logger.info(s"$id applies filters=>$filterRules")

    TcpModbusDeviceModel(id = id, description = description, protocols = protocols,
      tcpModelReg = TcpModelReg(dataRegList.toList, calibrationReg, inputRegList.toList,
        holdingRegList.toList, modeRegList.toList, warnRegList.toList, coilRegList.toList,
        multiplier, byteSwapMode = byteSwapMode, filterRules = filterRules))
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc:String,modelReg: TcpModelReg, config: DeviceConfig,
              @Assisted("protocol") protocol: ProtocolParam): Actor
  }

  def getSerialWrapper(protocolParam: ProtocolParam): SerialPortWrapper = {
    assert(protocolParam.protocol == Protocol.serial)
    new SerialPortWrapper {
      var comm: SerialComm = null

      override def close(): Unit = comm.close

      override def open(): Unit = {
        comm = SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(9600))
      }

      override def getInputStream: InputStream = comm.is

      override def getOutputStream: OutputStream = comm.os

      override def getBaudRate: Int = protocolParam.speed.getOrElse(9600)

      override def getFlowControlIn: Int = 0

      override def getFlowControlOut: Int = 0

      override def getDataBits: Int = 8

      override def getStopBits: Int = 1

      override def getParity: Int = 0
    }
  }
}

class TcpModbusDrv2(_id: String, desp: String, protocols: List[String], tcpModelReg: TcpModelReg) extends DriverOps {
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

  override def getCalibrationTime(param: String) = {
    val config = validateParam(param)
    config.calibrationTime
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[TcpModbusDrv2.Factory])
    val f2 = f.asInstanceOf[TcpModbusDrv2.Factory]
    val config = validateParam(param)
    f2(id, desp, tcpModelReg, config, protocol: ProtocolParam)
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

  override def id: String = _id

  override def description: String = desp

  override def protocol: List[String] = protocols
}