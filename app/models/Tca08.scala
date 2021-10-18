package models

import akka.actor.{Actor, ActorSystem}
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

import javax.inject.Inject

object Tca08Drv extends AbstractDrv(_id="TCA08", desp="TCA08",
  protocols = List(Protocol.serial, Protocol.tcp)){
  override def getDataRegList: List[DataReg] = List(
    DataReg(monitorType = "N2O", address = 1, multiplier = 1),
    DataReg(monitorType = "NH3", address = 2, multiplier = 1),
    DataReg(monitorType = "H2O", address = 3, multiplier = 1),
    DataReg(monitorType = "CH4", address = 4, multiplier = 1),
    DataReg(monitorType = "CO2", address = 5, multiplier = 1)
  )

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc:String, config: DeviceConfig,
              @Assisted("protocol") protocol: ProtocolParam): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    val f2 = f.asInstanceOf[Tca08Drv.Factory]
    val config = validateParam(param)
    f2(id, "TCA08", config, protocol: ProtocolParam)
  }
}

class Tca08Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                               alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                               calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)
                              (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                               deviceConfig: DeviceConfig, protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
    alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
    calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)(instId, desc, deviceConfig, protocolParam){
  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = List(
    InstrumentStatusType(key="N2O", addr = 1, desc = "N2O", "ppm"),
    InstrumentStatusType(key="NH3", addr = 2, desc = "NH3", "ppm"),
    InstrumentStatusType(key="H2O", addr = 3, desc = "H2O", "ppm"),
    InstrumentStatusType(key="CH4", addr = 4, desc = "CH4", "ppm"),
    InstrumentStatusType(key="CO2", addr = 5, desc = "CO2", "ppm")
  )

  override def readReg(statusTypeList: List[InstrumentStatusType]): ModelRegValue =
    ModelRegValue(inputRegs = List.empty[(InstrumentStatusType, Float)],
      holdingRegs = List.empty[(InstrumentStatusType, Float)],
      modeRegs = List.empty[(InstrumentStatusType, Boolean)],
      warnRegs= List.empty[(InstrumentStatusType, Boolean)])


  override def connectHost: Unit = {

  }

  override def getDataRegList: Seq[DataReg] = {
    Seq.empty[DataReg]
  }

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {

  }
}

