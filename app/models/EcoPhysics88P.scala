package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import jssc.SerialPort
import models.MetOne1020.instrumentStatusKeyList
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.Json

import javax.inject.Inject
import scala.concurrent.{Future, blocking}

object EcoPhysics88P extends AbstractDrv(_id = "EcoPhysics88P", desp = "Eco Physics 88P",
  protocols = List(Protocol.serial)) {
  val instrumentStatusKeyList = List(
    InstrumentStatusType(key = MonitorType.NO, addr = 1, desc = "NO", "ppm"),
    InstrumentStatusType(key = MonitorType.NO2, addr = 2, desc = "NO2", "ppm"),
    InstrumentStatusType(key = MonitorType.NOX, addr = 3, desc = "NOX", "ppm")
  )

  val map: Map[Int, InstrumentStatusType] = instrumentStatusKeyList.map(p => p.addr -> p).toMap
  val dataAddress = List(1, 2, 3)

  override def getDataRegList: List[DataReg] = instrumentStatusKeyList.filter(p => dataAddress.contains(p.addr)).map {
    ist =>
      DataReg(monitorType = ist.key, ist.addr, 1.0f)
  }

  override def getMonitorTypes(param: String): List[String] =
    List(MonitorType.NO, MonitorType.NO2, MonitorType.NOX)

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = {
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    config.calibrationTime
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[EcoPhysics88P.Factory]
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    f2(id, desc = super.description, config, protocol)
  }

  override def verifyParam(json: String) = {
    Logger.info(json)
    json
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

import scala.concurrent.ExecutionContext.Implicits.global

class EcoPhysics88PCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                       alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                       calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                      (@Assisted("instId") instId: String,
                                       @Assisted("desc") desc: String,
                                       @Assisted("config") deviceConfig: DeviceConfig,
                                       @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  val STX = "\u0002"

  Logger.info(s"EcoPhysics88P collector start")
  val ETX = "\u0003"
  var serialOpt: Option[SerialComm] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] =
    EcoPhysics88P.instrumentStatusKeyList

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      try {
        val ret =
          for (serial <- serialOpt) yield {
            serial.port.writeBytes(makeCmd("RD0"))
            Thread.sleep(1000)
            val replies = serial.port.readString()
            Logger.info(s"#=${replies.length} ${replies}")
            if (replies != null) {
              val dataStr = replies.dropWhile(_ != '\u0002').drop(1).takeWhile(_ != '\u0003')
              val tokens = dataStr.split(",")
              val regs = List((EcoPhysics88P.instrumentStatusKeyList(0), tokens(0).toDouble),
                (EcoPhysics88P.instrumentStatusKeyList(2), tokens(1).toDouble),
                (EcoPhysics88P.instrumentStatusKeyList(1), tokens(4).toDouble))
              Some(ModelRegValue2(inputRegs = regs,
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            } else {
              Logger.error("no reply")
              None
            }
          }
        ret.flatten
      } catch {
        case _: Throwable =>
          None
      }
    }
  }

  def makeCmd(cmd: String): Array[Byte] = {
    val cmdTxt = s"${STX}0${deviceConfig.slaveID}$cmd$ETX"
    val buffer: Array[Byte] = cmdTxt.getBytes
    val BCC = buffer.foldLeft(0: Byte)((a, b) => (a ^ b).toByte)
    buffer:+ BCC
  }


  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(
        protocolParam.comPort.get,
        protocolParam.speed.getOrElse(9600),
        SerialPort.DATABITS_7,
        SerialPort.STOPBITS_1,
        SerialPort.PARITY_NONE))
    val cmd = makeCmd("HR1")
    for(serial<-serialOpt){
      serial.port.writeBytes(cmd)
      Thread.sleep(1500)
      val replies = serial.port.readHexString()
      Logger.info(s"${instId}> #=${replies.length} ${replies}")
    }



  }

  override def getDataRegList: Seq[DataReg] = EcoPhysics88P.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }
}
