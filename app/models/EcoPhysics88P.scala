package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import jssc.SerialPort
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.Json

import javax.inject.Inject
import scala.concurrent.{Future, blocking}

object EcoPhysics88P extends AbstractDrv(_id = "EcoPhysics88P", desp = "Eco Physics 88P",
  protocols = List(Protocol.serial)) {
  val instrumentStatusKeyList = List(
    InstrumentStatusType(key = "NO_eco", addr = 1, desc = "NO", "ppm"),
    InstrumentStatusType(key = "NO2_eco", addr = 2, desc = "NO2", "ppm"),
    InstrumentStatusType(key = "NOX_eco", addr = 3, desc = "NOX", "ppm"),

    InstrumentStatusType(key = "Instrument internal temp", addr = 4,
      desc = "Instrument internal temperature", "C"),
    InstrumentStatusType(key = "Peltier cooling temp", addr = 5,
      desc = "Peltier cooling temperature", "C"),
    InstrumentStatusType(key = "reaction chamber temp", addr = 6,
      desc = "reaction chamber temperature", "C"),
    InstrumentStatusType(key = "converter temp", addr = 7,
      desc = "converter temperature", "C"),
    InstrumentStatusType(key = "hot tubing temp", addr = 8,
      desc = "hot tubing temperature", "C"),
    InstrumentStatusType(key = "ozone destroyer temp", addr = 9,
      desc = "ozone destroyer temperature", "C"),
    InstrumentStatusType(key = "ozone generator temp", addr = 10,
      desc = "ozone generator temperature", "C"),
    InstrumentStatusType(key = "vacumm pump temp", addr = 11,
      desc = "vacumm pump temperature", "C"),

    InstrumentStatusType(key = "bypass regulation pressure.", addr = 12,
      desc = "bypass regulation pressure.", "mbar"),
    InstrumentStatusType(key = "reaction chamber pressure.", addr = 13,
      desc = "reaction chamber pressure.", "mbar"),
    InstrumentStatusType(key = "zero calbration gas pressure.", addr = 14,
      desc = "zero calbration gas pressure.", "mbar"),
    InstrumentStatusType(key = "span calbration gas pressure.", addr = 15,
      desc = "span calbration gas pressure.", "mbar")
  )

  val map: Map[Int, InstrumentStatusType] = instrumentStatusKeyList.map(p => p.addr -> p).toMap
  val dataAddress = List(1, 2, 3)

  override def getDataRegList: List[DataReg] = instrumentStatusKeyList.filter(p => dataAddress.contains(p.addr)).map {
    ist =>
      DataReg(monitorType = ist.key, ist.addr, 1.0f)
  }

  override def getMonitorTypes(param: String): List[String] =
    List("NO_eco", "NO2_eco", "NOX_eco")

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = {
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    config.calibrationTime
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[EcoPhysics88P.Factory]
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    f2(id, desc = super.description, config, protocol)
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
  Logger.info(deviceConfig.toString)
  val ETX = "\u0003"
  var serialOpt: Option[SerialComm] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] =
    EcoPhysics88P.instrumentStatusKeyList

  def getDataPart(reply: String): Array[String] = {
    val dataStr = reply.dropWhile(_ != '\u0002').drop(1).takeWhile(_ != '\u0003')
    dataStr.split(",")
  }

  def handleReply(reply:String, statusTypeList: List[InstrumentStatusType]): List[(InstrumentStatusType, Double)] = {
    val tokens = getDataPart(reply)
    statusTypeList.zip(tokens).flatMap(pair=>
      try{
        Some((pair._1, pair._2.replace(" ","").trim.toDouble))
      }catch {
        case _:Throwable=>
          None
      })
  }

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      try {
        val ret =
          for (serial <- serialOpt) yield {
            serial.port.writeBytes(makeCmd("RD0"))
            Thread.sleep(500)
            val reply = serial.port.readString()
            if (reply != null) {
              val tokens = getDataPart(reply)
              val regs = List((EcoPhysics88P.instrumentStatusKeyList(0), tokens(0).toDouble),
                (EcoPhysics88P.instrumentStatusKeyList(2), tokens(1).toDouble),
                (EcoPhysics88P.instrumentStatusKeyList(1), tokens(4).toDouble))

              if(full){
                serial.port.writeBytes(makeCmd("RP"))
                Thread.sleep(500)
                val presureRegs = handleReply(serial.port.readString(), statusTypeList.filter(st=>st.addr>=12 && st.addr <=15))
                serial.port.writeBytes(makeCmd("RT"))
                Thread.sleep(500)
                val tempRegs = handleReply(serial.port.readString(), statusTypeList.filter(st=>st.addr>=4 && st.addr <=11))
                Some(ModelRegValue2(inputRegs = regs ++ presureRegs ++ tempRegs,
                  modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                  warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
              }else{
                Some(ModelRegValue2(inputRegs = regs,
                  modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                  warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
              }
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
    buffer :+ BCC
  }


  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(
        protocolParam.comPort.get,
        protocolParam.speed.getOrElse(9600),
        SerialPort.DATABITS_7,
        SerialPort.STOPBITS_1,
        SerialPort.PARITY_NONE))
  }

  override def getDataRegList: Seq[DataReg] = EcoPhysics88P.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 2))

  override def onCalibrationStart(): Unit = {
    for (serial <- serialOpt) {
      Logger.info(s"$instId Switch to Remote mode")
      serial.port.writeBytes(makeCmd("HR1"))
      Thread.sleep(1000)
      serial.port.readHexString()
    }
  }

  override def onCalibrationEnd(): Unit = {
    for (serial <- serialOpt) {
      Logger.info(s"$instId Switch to Local mode")
      serial.port.writeBytes(makeCmd("HR0"))
      Thread.sleep(1000)
      serial.port.readHexString()
    }
  }
  override def setCalibrationReg(address: Int, on: Boolean): Unit = {
    for (serial <- serialOpt) {
      val m = "00"
      val n = if(on)
        "1"
      else
        "0"
      val cmd = s"TV$m,$n"
      serial.port.writeBytes(makeCmd(cmd))
      Thread.sleep(1000)
      serial.port.readHexString()
    }
  }

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }
}
