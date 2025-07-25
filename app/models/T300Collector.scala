package models
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

object T300Collector extends TapiTxx(ModelConfig("T300", List(MonitorType.CO))) {
  lazy val modelReg = readModelSetting

  import akka.actor._

  var vCO: Option[Double] = None

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: ModelReg, config: TapiConfig, host:String): Actor
  }

  trait CliFactory {
    def apply(@Assisted("instId") instId: String,
              @Assisted("desc") desc: String,
              @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor ={
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val driverParam = validateParam(param)
    if(protocol.protocol == Protocol.tcp)
      f2(id, modelReg, driverParam, protocol.host.get)
    else {
      assert(fOpt.get.isInstanceOf[CliFactory])
      val cliFactory = fOpt.get.asInstanceOf[CliFactory]
      cliFactory(id, s"$id CLI", driverParam.toDeviceConfig, protocol)
    }
  }

  override def id: String = "t300"

  override def description: String = "TAPI T300"

}

import javax.inject._

class T300Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted host:String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host){
  val CO = MonitorType.CO

  import DataCollectManager._

  override def reportData(regValue: ModelRegValue): Option[ReportData] = {

    for (idx <- findDataRegIdx(regValue)(18)) yield {
      val vCO = regValue.inputRegs(idx)
      val measure = vCO._2.toDouble
      if (!MonitorTypeCollectorStatus.map.contains(CO) ||
        MonitorTypeCollectorStatus.map(CO) != collectorState) {
        MonitorTypeCollectorStatus.map = MonitorTypeCollectorStatus.map + (CO -> collectorState)
      }

      if (T300Collector.vCO.isDefined)
        ReportData(List(MonitorTypeData(CO, T300Collector.vCO.get, collectorState)))
      else
        ReportData(List(MonitorTypeData(CO, measure, collectorState)))
    }
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
        val locator = BaseLocator.coilStatus(config.slaveID, 21)
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
        masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 21), false)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    val addr = if (zero) 20 else 21
    val locator = BaseLocator.coilStatus(config.slaveID, addr)
    masterOpt.get.setValue(locator, on)
  }
} 