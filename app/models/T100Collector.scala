package models
import com.google.inject.assistedinject.Assisted
import models.Protocol.{tcp, tcpCli}
object T100Collector extends TapiTxx(ModelConfig("T100", List("SO2"))) {
  lazy val modelReg: ModelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: ModelReg, config: TapiConfig, @Assisted("host") host:String): Actor
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

  override def id: String = "t100"

  override def description: String = "TAPI T100"

  override def protocol: List[String] = List(tcp, tcpCli)
}

import javax.inject._

class T100Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted("host") host:String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host) {

  import DataCollectManager._
  import com.serotonin.modbus4j.locator.BaseLocator

  override def reportData(regValue: ModelRegValue): Option[ReportData] =
    for (idx <- findDataRegIdx(regValue)(22)) yield {
      val v = regValue.inputRegs(idx)
      ReportData(List(MonitorTypeData(MonitorType.SO2, v._2.toDouble, collectorState)))
    }

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