package models

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import models.MetOne1020.{instrumentStatusKeyList, instrumentStatusKeyListOld}
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.Json

import javax.inject.Inject
import scala.concurrent.{Future, blocking}

object MetOne1020 extends AbstractDrv(_id = "MetOne1020", desp = "MetOne 1020",
  protocols = List(Protocol.serial)) {
  val instrumentStatusKeyList = List(
    InstrumentStatusType(key = MonitorType.PM25, addr = 1, desc = "Conc", "mg/m3"),
    InstrumentStatusType(key = "Qtot", addr = 2, desc = "Qtot", "m3"),
    InstrumentStatusType(key = "WS", addr = 3, desc = "WS", "MPS"),
    InstrumentStatusType(key = "WD", addr = 4, desc = "WD", "DEG"),
    InstrumentStatusType(key = "BP", addr = 5, desc = "BP", "MM"),
    InstrumentStatusType(key = "RH", addr = 6, desc = "RH", "%"),
    InstrumentStatusType(key = "Delta", addr = 7, desc = "Delta", "C"),
    InstrumentStatusType(key = "AT", addr = 8, desc = "AT", "C")
  )
  val instrumentStatusKeyListOld = List(
    InstrumentStatusType(key = MonitorType.PM25, addr = 1, desc = "Conc", "mg/m3")
  )
  val map: Map[Int, InstrumentStatusType] = instrumentStatusKeyList.map(p => p.addr -> p).toMap
  val dataAddress = List(1)

  override def getDataRegList: List[DataReg] = instrumentStatusKeyList.filter(p => dataAddress.contains(p.addr)).map {
    ist =>
      DataReg(monitorType = ist.key, ist.addr, multiplier = 1000)
  }

  override def getMonitorTypes(param: String): List[String] =
    List(MonitorType.PM25)

  override def getCalibrationTime(param: String) = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[MetOne1020.Factory]
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

  var serialOpt: Option[SerialComm] = None


  Logger.info(s"MetOne1020 collector start with protocolType ${deviceConfig.slaveID}")

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] =
    if (deviceConfig.slaveID.contains(1))
      MetOne1020.instrumentStatusKeyList
    else
      MetOne1020.instrumentStatusKeyListOld

  def oldProtocol() = {
    try {
      val ret = {
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
            if(replies.size != 4)
              throw new Exception(s"Unexpected return ${replies.size}")
            else{
              val value = replies(3).split(",")(1).trim.toDouble
              Some(ModelRegValue2(inputRegs = List((instrumentStatusKeyListOld(0), value)),
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            }
          } else {
            Logger.error("no reply")
            None
          }
        }
      }
      ret.flatten
    } catch {
      case ex: Throwable =>
        Logger.error("MetOne readReg error", ex)
        None
    }
  }

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      if(deviceConfig.slaveID.contains(1))
        newProtocol
      else
        oldProtocol()
    }
  }

  def newProtocol(): Option[ModelRegValue2] = {
    try {
      val ret = {
        for (serial <- serialOpt) yield {
          val cmd = BayernHessenProtocol.dataQuery()
          serial.port.writeBytes(cmd)
          val replies = serial.getMessageByCrWithTimeout(timeout = 2)
          if (replies.nonEmpty) {
            replies.foreach(line => Logger.info(s"MetOne=>${line.trim}"))
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
            Logger.error("no reply")
            None
          }
        }
      }
      ret.flatten
    } catch {
      case ex: Throwable =>
        Logger.error("MetOne readReg error", ex)
        None
    }
  }

  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(9600)))
  }

  override def getDataRegList: Seq[DataReg] = MetOne1020.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }
}
