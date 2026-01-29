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
import java.util

case class DeviceConfig(slaveID: Option[Int], calibrationTime: Option[LocalTime] = None,
                        monitorTypes: Option[List[String]] = None,
                        raiseTime: Option[Int] = None,
                        downTime: Option[Int] = None,
                        holdTime: Option[Int] = None,
                        calibrateZeoSeq: Option[String] = None,
                        calibrateSpanSeq: Option[String] = None,
                        calibratorPurgeSeq: Option[String] = None,
                        calibratorPurgeTime: Option[Int] = None,
                        calibrateZeoDO: Option[Int] = None,
                        calibrateSpanDO: Option[Int] = None,
                        skipInternalVault: Option[Boolean] = None)

object DeviceConfig {
  val default: DeviceConfig = DeviceConfig(Some(1))

  import ModelHelper._

  implicit val cfgReads: Reads[DeviceConfig] = Json.reads[DeviceConfig]
  implicit val cfgWrites: OWrites[DeviceConfig] = Json.writes[DeviceConfig]
}

case class DataReg(monitorType: String, address: Int, multiplier: Float = 1.0f)

case class CalibrationReg(zeroAddress: Int, spanAddress: Int)

case class FilterRule(monitorType: String, min: Double, max: Double)

case class TcpModelReg(data: List[DataReg],
                       calibrationReg: Option[CalibrationReg],
                       inputs: List[ValueReg],
                       input64: List[ValueReg],
                       holding: List[ValueReg],
                       holding64: List[ValueReg],
                       modes: List[ModeReg],
                       warnings: List[ModeReg],
                       coils: List[ModeReg],
                       multiplier: Float,
                       byteSwapMode: Int,
                       filterRules: Seq[FilterRule],
                       byteSwapMode64: Int = DataType.EIGHT_BYTE_FLOAT)


case class TcpModbusDeviceModel(id: String, description: String, tcpModelReg: TcpModelReg, protocols: Seq[String])

object TcpModbusDrv2 {
  val logger: Logger = Logger(this.getClass)
  val deviceTypeHead = "TcpModbus."

  def getInstrumentTypeList(environment: play.api.Environment, factory: TcpModbusDrv2.Factory, monitorTypeOp: MonitorTypeDB): Array[InstrumentType] = {
    val docRoot = environment.rootPath + "/conf/TcpModbus/"
    val files = Option(new File(docRoot).listFiles()).getOrElse(Array.empty[File]).filter(p => p.getName.toLowerCase().endsWith("conf"))
    for (file <- files) yield {
      val device: TcpModbusDeviceModel = getDeviceModel(file)

      InstrumentType(
        new TcpModbusDrv2(s"${deviceTypeHead}${device.id}", device.description, device.protocols.toList, device.tcpModelReg), factory)
    }
  }

  def getDeviceModel(modelFile: File): TcpModbusDeviceModel = {
    val driverConfig = ConfigFactory.parseFile(modelFile)
    import scala.collection.JavaConverters._

    val id = driverConfig.getString("ID")
    val description = driverConfig.getString("description")
    val protocols: Seq[String] = try {
      driverConfig.getStringList("protocol").asScala
    } catch {
      case _: Throwable =>
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
    } catch {
      case _: Throwable =>
        DataType.FOUR_BYTE_FLOAT
    }

    val byteSwapMode64: Int = try {
      driverConfig.getInt("byteSwapMode64")
    } catch {
      case _: Throwable =>
        DataType.EIGHT_BYTE_FLOAT
    }

    def getAnyRefList(path: String): List[util.ArrayList[Any]] =
      if (!driverConfig.hasPath(path))
        List.empty[util.ArrayList[Any]]
      else {
        val anyList = driverConfig.getAnyRefList(path)
        for {
          i <- Range(0, anyList.size()).toList
          reg = anyList.get(i)
          v = reg.asInstanceOf[util.ArrayList[Any]]
        } yield {
          v
        }
      }

    def getValueRegList(path: String): List[ValueReg] =
      if (!driverConfig.hasPath(path))
        List.empty[ValueReg]
      else {
        val anyList = getAnyRefList(path: String)
        anyList.map(v => ValueReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String], v.get(2).asInstanceOf[String]))
      }

    val inputRegList = getValueRegList("Input.reg")
    val input64RegList = getValueRegList("Input64.reg")
    val holdingRegList = getValueRegList("Holding.reg")
    val holding64RegList = getValueRegList("Holding64.reg")

    def getModeRegList(path: String): List[ModeReg] =
      if (!driverConfig.hasPath(path))
        List.empty[ModeReg]
      else {
        val anyList = getAnyRefList(path: String)
        anyList.map(v => ModeReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String]))
      }

    val modeRegList = getModeRegList("DiscreteInput.mode")
    val warnRegList = getModeRegList("DiscreteInput.warning")

    val coilRegList = getModeRegList("Coil.reg")

    val dataRegList = {
      val anyList = getAnyRefList("Data")
      anyList.map(v => {
        val multiplier = if (v.size() < 3)
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
      })
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
      def getNumber(v: Any): Double = {
        v match {
          case v: Integer =>
            v.toDouble
          case v: Double =>
            v
          case v: Float =>
            v.toDouble
        }
      }

      try {
        val anyList = getAnyRefList(s"Filter")
        anyList.map(v => FilterRule(v.get(0).asInstanceOf[String], getNumber(v.get(1)), getNumber(v.get(2))))
      } catch {
        case _: Throwable =>
          Seq.empty[FilterRule]
      }
    }
    if (filterRules.nonEmpty)
      logger.info(s"$id applies filters=>$filterRules")

    val modelRegs = TcpModelReg(data = dataRegList.toList,
      calibrationReg = calibrationReg,
      inputs = inputRegList,
      input64 = input64RegList,
      holding = holdingRegList,
      holding64 = holding64RegList,
      modes = modeRegList,
      warnings = warnRegList,
      coils = coilRegList,
      multiplier = multiplier,
      byteSwapMode = byteSwapMode,
      filterRules = filterRules,
      byteSwapMode64 = byteSwapMode64)

    logger.debug(s"Load TcpModbus device model: $id")
    logger.debug(s"  Description: $description")
    logger.debug(s"  Protocols: $protocols")
    logger.debug(s"  Model Regs: $modelRegs")
    TcpModbusDeviceModel(id = id, description = description, protocols = protocols,
      tcpModelReg = modelRegs)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, modelReg: TcpModelReg, config: DeviceConfig,
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
  val logger: Logger = Logger(this.getClass)

  import DeviceConfig._

  override def verifyParam(json: String): String = {
    val ret = Json.parse(json).validate[DeviceConfig]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        val mt = tcpModelReg.data.map(_.monitorType)
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

  override def getCalibrationTime(param: String): Option[LocalTime] = {
    val config = validateParam(param)
    config.calibrationTime
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[TcpModbusDrv2.Factory])
    val f2 = f.asInstanceOf[TcpModbusDrv2.Factory]
    val config = validateParam(param)
    f2(id, desp, tcpModelReg, config, protocol: ProtocolParam)
  }

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

  override def description: String = desp

  override def protocol: List[String] = protocols
}