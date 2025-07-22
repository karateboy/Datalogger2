package models

import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

object T400Collector extends TapiTxx(ModelConfig("T400", List("O3"))) {
  lazy val modelReg: ModelReg = readModelSetting

  import akka.actor._

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val driverParam = validateParam(param)
    if (protocol.protocol == Protocol.tcp)
      f2(id, modelReg, driverParam, protocol.host.get)
    else {
      assert(fOpt.get.isInstanceOf[CliFactory])
      val cliFactory = fOpt.get.asInstanceOf[CliFactory]
      cliFactory(id, s"$id CLI", driverParam.toDeviceConfig, protocol)
    }
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: ModelReg, config: TapiConfig, host: String): Actor
  }

  trait CliFactory {
    def apply(@Assisted("instId") instId: String,
              @Assisted("desc") desc: String,
              @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }

  override def id: String = "t400"

  override def description: String = "TAPI T400"

}

import javax.inject._

class T400Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted host: String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host) {

  import DataCollectManager._

  override def reportData(regValue: ModelRegValue): Option[ReportData] =
    for (idx <- findDataRegIdx(regValue)(18)) yield {
      val v = regValue.inputRegs(idx)
      ReportData(List(MonitorTypeData(MonitorType.O3, v._2.toDouble, collectorState)))
    }

  import com.serotonin.modbus4j.locator.BaseLocator

  override def triggerZeroCalibration(v: Boolean): Unit = {
    try {
      super.triggerZeroCalibration(v)

      if (!config.skipInternalVault.contains(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, 20)
        masterOpt.get.setValue(locator, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerSpanCalibration(v: Boolean): Unit = {
    try {
      super.triggerSpanCalibration(v)

      if (!config.skipInternalVault.contains(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, 22)
        masterOpt.get.setValue(locator, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def resetToNormal(): Unit = {
    try {
      super.resetToNormal()

      if (!config.skipInternalVault.contains(true)) {
        masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 20), false)
        masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 22), false)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    val addr = if (zero) 20 else 22
    val locator = BaseLocator.coilStatus(config.slaveID, addr)
    masterOpt.get.setValue(locator, on)
  }
}