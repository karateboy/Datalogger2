package models
import akka.actor.{Actor, ActorSystem}
import models.Protocol.ProtocolParam

import javax.inject.Inject


object PicarroG2401 extends AbstractDrv(_id="PicarroG2401", desp="Picarro G2401",
  protocols = List(Protocol.serial, Protocol.tcp)){
  override def getDataRegList: List[DataReg] = ???

  override def factory(id: String, protocol: Protocol.ProtocolParam, param: String)(f: AnyRef): Actor = ???
}

class PicarroG2401Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                                      alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                                      calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)
                                     (instId: String, desc: String, deviceConfig: DeviceConfig, protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
    alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
    calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)(instId, desc, deviceConfig, protocolParam){
  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = ???

  override def readReg(statusTypeList: List[InstrumentStatusType]): ModelRegValue = ???

  override def connectHost: Unit = ???

  override def getDataRegList: Seq[DataReg] = ???

  override def getCalibrationReg: Option[CalibrationReg] = ???

  override def setCalibrationReg(address: Int, on: Boolean): Unit = ???
}
