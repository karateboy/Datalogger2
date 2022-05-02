package models
import akka.actor.ActorSystem
import com.google.inject.assistedinject.Assisted
import models.Protocol.{ProtocolParam, tcp}
import models.mongodb.{AlarmOp, CalibrationOp, InstrumentStatusOp}
import play.api._

object T700Collector extends TapiTxx(ModelConfig("T700", List.empty[String])) {
  lazy val modelReg = readModelSetting

  import akka.actor._

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: ModelReg, config: TapiConfig, host:String): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor ={
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val driverParam = validateParam(param)
    f2(id, modelReg, driverParam, protocol.host.get)
  }

  override def id: String = "t700"

  override def description: String = "TAPI T700"

  override def protocol: List[String] = List(tcp)

  override def isCalibrator: Boolean = true
}

import javax.inject._
class T700Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted host:String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, system, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host){

  import TapiTxx._
  import com.github.nscala_time.time.Imports._

  var lastSeqNo = 0
  var lastSeqOp = true
  var lastSeqTime = DateTime.now

  override def reportData(regValue: ModelRegValue) = None

  import com.serotonin.modbus4j.locator.BaseLocator
  override def executeSeq(seqName: String, on: Boolean) {
    Logger.info(s"T700 execute $seqName sequence.")
    val seq = Integer.parseInt(seqName)
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