package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.MetOne1020.{instrumentStatusKeyList, instrumentStatusKeyListOld}
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.Json

import java.io.{InputStream, OutputStream}
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import scala.concurrent.{Future, blocking}

object MetOne1020 extends AbstractDrv(_id = "MetOne1020", name = "MetOne 1020",
  protocols = List(Protocol.serial, Protocol.tcp)) {
  override val logger: Logger = Logger(this.getClass)
  val instrumentStatusKeyList: List[InstrumentStatusType] = List(
    InstrumentStatusType(key = MonitorType.PM25, addr = 1, desc = "Conc", "mg/m3"),
    InstrumentStatusType(key = "Qtot", addr = 2, desc = "Qtot", "m3"),
    InstrumentStatusType(key = "WS", addr = 3, desc = "WS", "MPS"),
    InstrumentStatusType(key = "WD", addr = 4, desc = "WD", "DEG"),
    InstrumentStatusType(key = "BP", addr = 5, desc = "BP", "MM"),
    InstrumentStatusType(key = "RH", addr = 6, desc = "RH", "%"),
    InstrumentStatusType(key = "Delta", addr = 7, desc = "Delta", "C"),
    InstrumentStatusType(key = "AT", addr = 8, desc = "AT", "C")
  )
  val instrumentStatusKeyListOld: List[InstrumentStatusType] = List(
    InstrumentStatusType(key = MonitorType.PM25, addr = 1, desc = "Conc", "mg/m3")
  )
  val map: Map[Int, InstrumentStatusType] = instrumentStatusKeyList.map(p => p.addr -> p).toMap
  val dataAddress: List[Int] = List(1)

  override def getDataRegList: List[DataReg] = instrumentStatusKeyList.filter(p => dataAddress.contains(p.addr)).map {
    ist =>
      DataReg(monitorType = ist.key, ist.addr, multiplier = 1000)
  }

  override def getMonitorTypes(param: String): List[String] =
    List(MonitorType.PM25)

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[MetOne1020.Factory]
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    f2(id, desc = super.description, config, protocol)
  }

  override def verifyParam(json: String): String = {
    logger.info(json)
    json
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

import scala.concurrent.ExecutionContext.Implicits.global

class MetOne1020Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                   (@Assisted("instId") instId: String,
                                    @Assisted("desc") desc: String,
                                    @Assisted("config") deviceConfig: DeviceConfig,
                                    @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  @volatile var serialOpt: Option[SerialComm] = None
  @volatile var socketOpt: Option[Socket] = None
  @volatile var outOpt: Option[OutputStream] = None
  @volatile var inOpt: Option[InputStream] = None

  val logger: Logger = Logger(this.getClass)

  logger.info(s"MetOne1020 collector start $protocolParam ${deviceConfig.slaveID}")

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] =
    if (deviceConfig.slaveID.contains(1))
      MetOne1020.instrumentStatusKeyList
    else
      MetOne1020.instrumentStatusKeyListOld

  private def readLines(in:InputStream): Array[String] = {
    val buffer = new Array[Byte](in.available())
    in.read(buffer)
    val str = new String(buffer, StandardCharsets.UTF_8)
    str.split('\n').map(_.trim)
  }

  private def tcpHandler: Option[Option[ModelRegValue2]] = {
    for (in <- inOpt; out <- outOpt) yield {
      out.write("\r\n".getBytes())
      out.write("\r\n".getBytes())
      Thread.sleep(1000)
      readLines(in)
      out.write("4\r\n".getBytes)
      Thread.sleep(2000)
      val replies = readLines(in)
      if (replies.nonEmpty) {
        try{
          val dataLine = replies.last
          val dataPart = dataLine.split(",")
          val value = dataPart(1).trim.toDouble/1000
          Some(ModelRegValue2(inputRegs = List((instrumentStatusKeyListOld.head, value)),
            modeRegs = List.empty[(InstrumentStatusType, Boolean)],
            warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
        }catch {
          case ex:Throwable=>
            logger.error("tcpHandler", ex)
            None
        }

      } else {
        logger.error("no reply")
        None
      }
    }
  }

  private def oldProtocol(): Option[ModelRegValue2] = {
    try {
      val ret: Option[Option[ModelRegValue2]] = {
        if (protocolParam.protocol == Protocol.serial) {
          for (serial <- serialOpt) yield {
            serial.port.writeBytes("\u000d".getBytes)
            Thread.sleep(600)
            serial.port.writeBytes("\u000d".getBytes)
            Thread.sleep(600)
            serial.port.writeBytes("6".getBytes)
            Thread.sleep(1200)
            serial.purgeBuffer()
            serial.port.writeBytes("4".getBytes)
            Thread.sleep(1500)
            val replies = serial.getMessageByLfWithTimeout(timeout = 2)
            if (replies.nonEmpty) {
              if (replies.size != 4)
                throw new Exception(s"Unexpected return ${replies.size}")
              else {
                val value = replies(3).split(",")(1).trim.toDouble
                Some(ModelRegValue2(inputRegs = List((instrumentStatusKeyListOld.head, value)),
                  modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                  warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
              }
            } else {
              logger.error("no reply")
              None
            }
          }
        } else
          tcpHandler
      }
      ret.flatten
    } catch {
      case ex: Throwable =>
        logger.error("oldProtocol error", ex)
        None
    }
  }


  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      if (deviceConfig.slaveID.contains(1))
        newProtocol()
      else
        oldProtocol()
    }
  }

  private def newProtocol(): Option[ModelRegValue2] = {
    try {
      val ret =
        if (protocolParam.protocol == Protocol.serial) {
          for (serial <- serialOpt) yield {
            val cmd = BayernHessenProtocol.dataQuery()
            serial.port.writeBytes(cmd)
            val replies = serial.getMessageByCrWithTimeout(timeout = 2)
            if (replies.nonEmpty) {
              replies.foreach(line => logger.info(s"MetOne=>${line.trim}"))
              val measure =
                for (line <- replies if line.contains(",")) yield {
                  val inputs = line.trim.split(",")
                  for (statusKey <- instrumentStatusKeyList if statusKey.addr < inputs.length) yield
                    (statusKey, inputs(statusKey.addr).toDouble)
                }
              Some(ModelRegValue2(inputRegs = measure.flatten,
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            } else {
              logger.error("no reply")
              None
            }
          }
        } else
          tcpHandler

      ret.flatten
    } catch {
      case ex: Throwable =>
        logger.error("newProtocol error", ex)
        None
    }
  }

  override def connectHost(): Unit = {
    if (protocolParam.protocol == Protocol.serial) {
      serialOpt =
        Some(SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(9600)))
    } else {
      val socket = new Socket(protocolParam.host.get, 7500)
      socket.setSoTimeout(1000)
      socketOpt = Some(socket)
      outOpt = Some(socket.getOutputStream)
      inOpt = Some(socket.getInputStream)
    }

  }

  override def getDataRegList: Seq[DataReg] = MetOne1020.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    for (in <- inOpt)
      in.close()

    for (out <- outOpt)
      out.close()

    for (socket <- socketOpt)
      socket.close()


    super.postStop()
  }
}
