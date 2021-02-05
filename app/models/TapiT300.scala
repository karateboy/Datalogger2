package models
import akka.actor.ActorSystem
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

object T300Collector extends TapiTxx(ModelConfig("T300", List("CO"))) {
  lazy val modelReg = readModelSetting

  import akka.actor._

  var vCO: Option[Double] = None

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: ModelReg, config: TapiConfig, host:String): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor ={
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val driverParam = validateParam(param)
    f2(id, modelReg, driverParam, protocol.host.get)
  }
}

import javax.inject._

class T300Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                              alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                              calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted host:String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, system, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host){
  val CO = ("CO")

  override def reportData(regValue: ModelRegValue) = {

    for (idx <- findDataRegIdx(regValue)(18)) yield {
      val vCO = regValue.inputRegs(idx)
      val measure = vCO._2.toDouble
      if (MonitorTypeCollectorStatus.map.get(CO).isEmpty ||
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