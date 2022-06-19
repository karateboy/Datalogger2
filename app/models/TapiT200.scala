package models

import akka.actor.ActorSystem
import com.google.inject.assistedinject.Assisted
import models.MonitorType.{NO, NO2, NOX}
import models.Protocol.{ProtocolParam, tcp, tcpCli}

object T200Collector extends TapiTxx(ModelConfig("T200",
  List(MonitorType.NOX, MonitorType.NO, MonitorType.NO2))) {
  lazy val modelReg = readModelSetting

  import akka.actor._

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

  override def id: String = "t200"

  override def description: String = "TAPI T200"

  override def protocol: List[String] = List(tcp, tcpCli)
}

import javax.inject._

class T200Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted host:String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host) {


  override def reportData(regValue: ModelRegValue) =
    for {
      idxNox <- findDataRegIdx(regValue)(30)
      idxNo <- findDataRegIdx(regValue)(34)
      idxNo2 <- findDataRegIdx(regValue)(38)
      vNOx = regValue.inputRegs(idxNox)
      vNO = regValue.inputRegs(idxNo)
      vNO2 = regValue.inputRegs(idxNo2)
    } yield {
      ReportData(List(
        MonitorTypeData(NOX, vNOx._2.toDouble, collectorState),
        MonitorTypeData(NO, vNO._2.toDouble, collectorState),
        MonitorTypeData(NO2, vNO2._2.toDouble, collectorState)))

    }

  import com.serotonin.modbus4j.locator.BaseLocator

  override def triggerZeroCalibration(v: Boolean) {
    try {
      super.triggerZeroCalibration(v)

      if (config.skipInternalVault != Some(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, 20)
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
        val locator = BaseLocator.coilStatus(config.slaveID, 21)
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
        masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 20), false)
        masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 21), false)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }
} 