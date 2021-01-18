package models
import akka.actor.ActorSystem
object TapiT360 extends TapiTxx(ModelConfig("T360", List("CO2"))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T360Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}
import javax.inject._
class T360Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                              alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                              calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)(instId: String, modelReg: ModelReg, config: TapiConfig)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, system, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config){

  import com.serotonin.modbus4j.locator.BaseLocator

  override def reportData(regValue: ModelRegValue) =
    for (idx <- findDataRegIdx(regValue)(18)) yield {
      val v = regValue.inputRegs(idx)
      ReportData(List(MonitorTypeData(("CO2"), v._2.toDouble, collectorState)))
    }

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