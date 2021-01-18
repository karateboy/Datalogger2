package models
import akka.actor.ActorSystem
import play.api._

object TapiT700 extends TapiTxx(ModelConfig("T700", List.empty[String])) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T700Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

import javax.inject._
class T700Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                              alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                              calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)(instId: String, modelReg: ModelReg, config: TapiConfig)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, system, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config){

  import TapiTxx._
  import com.github.nscala_time.time.Imports._

  var lastSeqNo = 0
  var lastSeqOp = true
  var lastSeqTime = DateTime.now

  override def reportData(regValue: ModelRegValue) = None

  import com.serotonin.modbus4j.locator.BaseLocator
  override def executeSeq(seq: Int, on: Boolean) {
    Logger.info(s"T700 execute $seq sequence.")
    if ((seq == lastSeqNo && lastSeqOp == on) && (DateTime.now() < lastSeqTime + 5.second)) {
      Logger.info(s"T700 in cold period, ignore same seq operation")
    } else {
      lastSeqTime = DateTime.now
      lastSeqOp = on
      lastSeqNo = seq
      try {
        val locator = BaseLocator.coilStatus(config.slaveID, seq)
        masterOpt.get.setValue(locator, on)
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }
    }
  }

  override def triggerZeroCalibration(v: Boolean) {}

  override def triggerSpanCalibration(v: Boolean) {}

  override def resetToNormal {
    executeSeq(T700_STANDBY_SEQ, true)
  }
} 