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

object HydreonRainGauge extends AbstractDrv(_id = "HydreonRainGauge", desp = "Hydreon Rain Gauge RG-15",
  protocols = List(Protocol.serial)) {
  val instrumentStatusKeyList = List(
    InstrumentStatusType(key = MonitorType.RAIN, addr = 1, desc = "Rain", "mm"))

  val map: Map[Int, InstrumentStatusType] = instrumentStatusKeyList.map(p => p.addr -> p).toMap
  val dataAddress = List(1)

  override def getDataRegList: List[DataReg] = instrumentStatusKeyList.filter(p => dataAddress.contains(p.addr)).map {
    ist =>
      DataReg(monitorType = ist.key, ist.addr, 1.0f)
  }

  override def getMonitorTypes(param: String): List[String] =
    List(MonitorType.RAIN)

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] =None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[HydreonRainGauge.Factory]
    val config = Json.parse(param).validate[DeviceConfig].asOpt.get
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

import scala.concurrent.ExecutionContext.Implicits.global

class HydreonRainGaugeCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                       alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                       calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                      (@Assisted("instId") instId: String,
                                       @Assisted("desc") desc: String,
                                       @Assisted("config") deviceConfig: DeviceConfig,
                                       @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {


  Logger.info(s"HydreonRainGauge collector $instId start")
  var serialOpt: Option[SerialComm] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] =
    HydreonRainGauge.instrumentStatusKeyList

  def getData(reply: String) = {
    val accDataStr = reply.split(",").head.trim
    if(!accDataStr.contains("Acc"))
      throw new Exception("reply not contain Acc rain")

    val tokens = accDataStr.split("\\s+")
    tokens(1).toDouble
  }

  var resetCounter = 0
  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      try {
        val ret =
          for (serial <- serialOpt) yield {
            serial.port.writeBytes("R\r\n".getBytes)
            Thread.sleep(500)
            val reply = serial.port.readString()
            Logger.debug(reply)
            if (reply != null) {
              val regs = List((HydreonRainGauge.instrumentStatusKeyList(0), getData(reply)))
              Some(ModelRegValue2(inputRegs = regs,
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            } else {
              Logger.error("no reply")
              None
            }
          }
        resetCounter = resetCounter + 1
        if(resetCounter % 24*60 == 0) {
          resetCounter = 0
          resetRainAccumulator()
        }

        ret.flatten
      } catch {
        case ex: Throwable =>
          Logger.error("failed to read reg", ex)
          None
      }
    }
  }

  def resetRainAccumulator(): Unit ={
    for(serial<-serialOpt)
      serial.port.writeBytes("O\r\n".getBytes)
  }
  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(
        protocolParam.comPort.get,
        protocolParam.speed.getOrElse(9600)))

    resetRainAccumulator()
  }

  override def getDataRegList: Seq[DataReg] = HydreonRainGauge.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}
}
