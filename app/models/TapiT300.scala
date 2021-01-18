package models
import akka.actor.ActorSystem

object TapiT300 extends TapiTxx(ModelConfig("T300", List("CO"))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T300Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }

  var vCO: Option[Double] = None
}

import javax.inject._

class T300Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                              alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                              calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)(instId: String, modelReg: ModelReg, config: TapiConfig)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, system, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config){
  val CO = ("CO")

  override def reportData(regValue: ModelRegValue) = {

    for (idx <- findDataRegIdx(regValue)(18)) yield {
      val vCO = regValue.inputRegs(idx)
      val measure = vCO._2.toDouble
      if (MonitorTypeCollectorStatus.map.get(CO).isEmpty ||
        MonitorTypeCollectorStatus.map(CO) != collectorState) {
        MonitorTypeCollectorStatus.map = MonitorTypeCollectorStatus.map + (CO -> collectorState)
      }

      if (TapiT300.vCO.isDefined)
        ReportData(List(MonitorTypeData(CO, TapiT300.vCO.get, collectorState)))
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