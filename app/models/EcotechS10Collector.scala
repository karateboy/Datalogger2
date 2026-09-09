package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import jssc.SerialPort
import models.ModelHelper.getChecksum
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.Json

import java.nio.ByteBuffer
import javax.inject.Inject
import javax.xml.bind.DatatypeConverter
import scala.collection.mutable
import scala.concurrent.{Future, blocking}

object EcotechS10Collector extends AbstractDrv(_id = "EcotechS10", name = "Ecotech Serinus 10 (O3)",
  protocols = List(Protocol.serial)) {
  override val logger: Logger = Logger(this.getClass)
  private val instrumentStatusKeyList: List[InstrumentStatusType] = List(
    InstrumentStatusType(key = MonitorType.O3, addr = 1, desc = "O3", "ppm")
  )

  val map: Map[Int, InstrumentStatusType] = instrumentStatusKeyList.map(p => p.addr -> p).toMap
  val dataAddress: List[Int] = List(1)

  override def getDataRegList(deviceConfig: DeviceConfig): List[DataReg] = instrumentStatusKeyList.filter(p => dataAddress.contains(p.addr)).map {
    ist =>
      DataReg(monitorType = ist.key, ist.addr)
  }

  override def getMonitorTypes(param: String): List[String] =
    List(MonitorType.O3)

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = {
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    config.calibrationTime
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[EcotechS10Collector.Factory]
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

import scala.concurrent.ExecutionContext.Implicits.global

class EcotechS10Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                   (@Assisted("instId") instId: String,
                                    @Assisted("desc") desc: String,
                                    @Assisted("config") deviceConfig: DeviceConfig,
                                    @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {
  val logger: Logger = Logger(this.getClass)

  logger.info(s"Ecotech S10 Collector start")
  logger.debug(deviceConfig.toString)

  private val STX = '\u0002'.toByte
  private val ETX = '\u0003'.toByte
  private val EOT = '\u0004'.toByte
  @volatile var serialOpt: Option[SerialComm] = None

  import EcotechS10Collector._

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = instrumentStatusKeyList


  def getMsg(resp: Array[Byte]): Array[Byte] = {
    val msgLen = resp(4)
    resp.slice(5, msgLen)
  }

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      def getData(resp: Array[Byte]): Array[Double] = {
        val msgLen = resp(4)
        val msg = resp.slice(5, 5 + msgLen)

        logger.debug(s"msg = ${DatatypeConverter.printHexBinary(msg)}")
        val o3 = msg.slice(1, 5)
        logger.debug(s"o3 = ${DatatypeConverter.printHexBinary(o3)}")

        Array(o3).map(ByteBuffer.wrap(_).getFloat.toDouble)
      }

      try {
        val ret =
          for (serial <- serialOpt) yield {
            val cmd = makeCmd(1, Array[Byte](50))
            logger.debug("read cmd=>" + DatatypeConverter.printHexBinary(cmd))

            serial.port.writeBytes(cmd)
            Thread.sleep(100)
            val reply = serial.port.readBytes()

            if (reply != null) {
              logger.debug(s"reply => ${DatatypeConverter.printHexBinary(reply)}")

              val values = getData(reply)
              logger.debug(s"values = ${values.mkString("Array(", ", ", ")")}")

              val regs =
                for ((statusKey, index) <- instrumentStatusKeyList.zipWithIndex) yield
                  (statusKey, values(index))

              Some(ModelRegValue2(inputRegs = regs,
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            } else {
              logger.error("no reply")
              None
            }
          }
        ret.flatten
      } catch {
        case ex: Throwable =>
          logger.error("readReg failed", ex)
          None
      }
    }
  }

  def makeCmd(cmd: Byte, msg: Array[Byte]): Array[Byte] = {
    val packetBuffer = mutable.Buffer.empty[Byte]
    val serialID = deviceConfig.slaveID.getOrElse(1).toByte
    packetBuffer.append(STX, serialID, cmd, ETX, msg.length.toByte)
    packetBuffer.appendAll(msg)
    packetBuffer.append(STX, ETX)
    val checksum = getChecksum(packetBuffer.toArray)
    packetBuffer.remove(packetBuffer.length - 2, 2)
    packetBuffer.append(checksum, EOT)
    packetBuffer.toArray
  }


  override def connectHost(): Unit = {
    serialOpt =
      Some(SerialComm.open(
        protocolParam.comPort.get,
        protocolParam.speed.getOrElse(9600),
        SerialPort.DATABITS_8,
        SerialPort.STOPBITS_1,
        SerialPort.PARITY_NONE))
  }

  override def getDataRegList(deviceConfig: DeviceConfig): Seq[DataReg] = EcotechS10Collector.getDataRegList(deviceConfig)

  val ZERO_ADDR = 0
  val SPAN_ADDR = 1
  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(ZERO_ADDR, SPAN_ADDR))

  override def onCalibrationStart(): Unit = {}

  override def onCalibrationEnd(): Unit = {}

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {
    // address 0 => zero, 1 => span
    //
    for (serial <- serialOpt) {
      val zero = address == ZERO_ADDR

      if (zero)
        logger.info(s"set zero calibration value=$on")
      else
        logger.info(s"set span calibration value=$on")

      val msg = if (!on) {
        Array[Byte](85, 0, 0, 0, 0) // 0.0 float
      } else {
        if (zero)
          Array[Byte](85, 64, 0, 0, 0) // 2.0 float
        else
          Array[Byte](85, 64, 64, 0, 0) // 3.0 float
      }

      serial.port.writeBytes(makeCmd(4, msg))
      Thread.sleep(100)
      val response = serial.port.readBytes()
      if (response != null) {
        logger.info(s"response=>${DatatypeConverter.printHexBinary(response)}")
      }
    }

  }

  override def getDelayAfterCalibrationStart: Int = 0

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    if (on)
      onCalibrationStart()
    else
      onCalibrationEnd()


    if (zero)
      setCalibrationReg(ZERO_ADDR, on)
    else
      setCalibrationReg(SPAN_ADDR, on)
  }

}
