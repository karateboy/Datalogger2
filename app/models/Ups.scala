package models

import akka.actor.{Actor, ActorSystem}
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.UpsDrv.UPS_SHUTDOWN
import models.mongodb.{AlarmOp, CalibrationOp, InstrumentStatusOp}
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

object UpsDrv extends AbstractDrv(_id = "Ups", desp = "Ups 1 Series",
  protocols = List(Protocol.serial)) {

  val predefinedIST = List.empty[InstrumentStatusType]

  val map: Map[Int, InstrumentStatusType] = predefinedIST.map(p=>p.addr->p).toMap


  val dataAddress = List.empty[Int]

  override def getMonitorTypes(param: String): List[String] = List.empty[String]

  override def verifyParam(json: String) = json

  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  override def getCalibrationTime(param: String) = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[UpsDrv.Factory]
    val config = DeviceConfig.default
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
  val UPS_SHUTDOWN = "UPS_SHUTDOWN"
}

class UpsCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                               alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                               calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                              (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                               @Assisted("config") deviceConfig: DeviceConfig,
                               @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  monitorTypeOp.ensure(monitorTypeOp.signalType(UPS_SHUTDOWN, "中斷UPS電源"))

  context.parent ! AddSignalTypeHandler(UPS_SHUTDOWN, bit=>{
    self ! WriteSignal(UPS_SHUTDOWN, bit)
  })


  var serialOpt: Option[SerialComm] = None

  override def onWriteSignal(mt: String, bit: Boolean): Unit = {
    Logger.info(s"UPS receive $mt signal cmd")
    for(serial <- serialOpt){
      if(bit) {
        serial.port.writeBytes("S.2R0600\r".getBytes)
        serial.readPort
      }
    }
  }

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = Ma350Drv.predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType], full:Boolean): Future[Option[ModelRegValue2]] =
    Future.successful(None)

  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(2400)))
  }

  override def getDataRegList: Seq[DataReg] = UpsDrv.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }
}
