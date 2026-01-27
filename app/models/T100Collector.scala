package models

import com.google.inject.assistedinject.Assisted
import play.api.Logger

object T100Collector extends TapiTxx(ModelConfig("T100", List("SO2"))) {
  lazy val modelReg: ModelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: ModelReg, config: TapiConfig, @Assisted("host") host: String): Actor
  }

  trait CliFactory {
    def apply(@Assisted("instId") instId: String,
              @Assisted("desc") desc: String,
              @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }

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

  override def id: String = "t100"

  override def description: String = "TAPI T100"
}

import javax.inject._

class T100Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted("host") host: String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host) {

  val logger = Logger(this.getClass)

  import DataCollectManager._
  import com.serotonin.modbus4j.locator.BaseLocator

  override def reportData(regValue: ModelRegValue): Option[ReportData] =
    for (idx <- findDataRegIdx(regValue)(22)) yield {
      val v = regValue.inputs(idx)
      ReportData(List(MonitorTypeData(MonitorType.SO2, v._2.toDouble, collectorState)))
    }

  override def triggerZeroCalibration(v: Boolean): Unit = {

    try {
      super.triggerZeroCalibration(v)
      if (!config.skipInternalVault.contains(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, 20)
        for(master<-masterOpt)
          master.setValue(locator, v)
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
        val locator = BaseLocator.coilStatus(config.slaveID, 21)
        for(master<-masterOpt)
          master.setValue(locator, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def resetToNormal(): Unit = {
    try {
      super.resetToNormal()

      logger.info("Reset to normal state coil 20, 21 to false")
      if (!config.skipInternalVault.contains(true)) {
        for (master <- masterOpt) {
          master.setValue(BaseLocator.coilStatus(config.slaveID, 20), false)
          master.setValue(BaseLocator.coilStatus(config.slaveID, 21), false)
        }
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    val addr = if (zero) 20 else 21
    val locator = BaseLocator.coilStatus(config.slaveID, addr)
    for (master <- masterOpt)
      master.setValue(locator, on)
  }
}