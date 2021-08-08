package models

import com.google.inject.assistedinject.Assisted

object Thermal43i extends TcpModbusDrv("Thermal43i") {
  lazy val modelReg: TcpModelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: TcpModelReg, config: DeviceConfig, @Assisted("host") host:String): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor ={
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val config = validateParam(param)
    f2(id, modelReg, config, protocol.host.get)
  }
}

import akka.actor.ActorSystem

import javax.inject._

class Thermal43iCollector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                              alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                              calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)
                             (@Assisted("instId") instId: String, @Assisted modelReg: TcpModelReg,
                              @Assisted config: DeviceConfig, @Assisted("host") host:String)
  extends TcpModbusCollector(instrumentOp, monitorStatusOp,
    alarmOp, system, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host) {

  import com.serotonin.modbus4j.locator.BaseLocator

  override def reportData(regValue: ModelRegValue) = {

    val optValues: Seq[Option[(String, (InstrumentStatusType, Float))]] = {
      for (dataReg <- modelReg.dataRegs) yield {
        for (idx <- findDataRegIdx(regValue)(dataReg.address)) yield
          (dataReg.monitorType, regValue.inputRegs(idx))
      }
    }

    val monitorTypeData = optValues.flatten.map(
      t=> MonitorTypeData(t._1, t._2._2.toDouble, collectorState))

      if(monitorTypeData.isEmpty)
        None
      else
        Some(ReportData(monitorTypeData.toList))
  }

  override def triggerZeroCalibration(v: Boolean) {

    try {
      super.triggerZeroCalibration(v)
      if (config.skipInternalVault != Some(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, modelReg.calibrationReg.zeroAddress)
        masterOpt.get.setValue(locator, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerSpanCalibration(v: Boolean) {
    try {
      super.triggerSpanCalibration(v)

      if (config.skipInternalVault != Some(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, modelReg.calibrationReg.spanAddress)
        masterOpt.get.setValue(locator, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def resetToNormal = {
    try {
      super.resetToNormal

      if (config.skipInternalVault != Some(true)) {
        masterOpt.get.setValue(BaseLocator.coilStatus(
          config.slaveID, modelReg.calibrationReg.zeroAddress), false)
        masterOpt.get.setValue(BaseLocator.coilStatus(
          config.slaveID, modelReg.calibrationReg.spanAddress), false)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }
}