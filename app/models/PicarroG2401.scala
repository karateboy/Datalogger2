package models
import akka.actor.{Actor, ActorSystem}
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.Tca08Drv.validateParam

import javax.inject.Inject


object PicarroG2401 extends AbstractDrv(_id="picarroG2401", desp="Picarro G2401",
  protocols = List(Protocol.serial, Protocol.tcp)){
  val predefinedIST = List(
    InstrumentStatusType(key = "N2O", addr = 1, desc = "N2O", "ppm"),
    InstrumentStatusType(key = "NH3", addr = 2, desc = "NH3", "ppm"),
    InstrumentStatusType(key = "H2O", addr = 3, desc = "H2O", "ppm"),
    InstrumentStatusType(key = "CH4", addr = 4, desc = "CH4", "ppm"),
    InstrumentStatusType(key = "CO2", addr = 5, desc = "CO2", "ppm"))

  val dataAddress = List(1, 2, 3, 4, 5)

  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    val f2 = f.asInstanceOf[PicarroG2401.Factory]
    val config = validateParam(param)
    f2(id, desc = super.description, config, protocol)
  }
}

class PicarroG2401Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                                      alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                                      calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)
                                     (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                      @Assisted("config") deviceConfig: DeviceConfig,
                                      @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
    alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
    calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)(instId, desc, deviceConfig, protocolParam){

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = PicarroG2401.predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType]): ModelRegValue2 = {

    val inputs = Tca08Drv.predefinedIST map { ist => (ist, 1.0) }

    ModelRegValue2(inputRegs = inputs,
      modeRegs = List.empty[(InstrumentStatusType, Boolean)],
      warnRegs = List.empty[(InstrumentStatusType, Boolean)])
  }


  override def connectHost: Unit = {
    protocolParam.protocol match {
      case Protocol.tcp =>

      case Protocol.serial =>

    }
  }

  override def getDataRegList: Seq[DataReg] = Tca08Drv.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {

  }
}
