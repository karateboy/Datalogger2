package models

import com.google.inject.assistedinject.Assisted
import models.Protocol.{ProtocolParam, tcp}
import play.api.Logger

object T201Collector extends
  TapiTxx(ModelConfig("T201", List("TNX",
    MonitorType.NH3, MonitorType.NOX,
    MonitorType.NO, MonitorType.NO2))) {
  lazy val modelReg = readModelSetting

  import akka.actor._

  trait Factory {
    def apply(@Assisted("instId") instId: String, modelReg: ModelReg, config: TapiConfig, host: String): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val driverParam = validateParam(param)
    f2(id, modelReg, driverParam, protocol.host.get)
  }

  override def id: String = "t201"

  override def description: String = "TAPI T201"

  override def protocol: List[String] = List(tcp)
}

import javax.inject._

class T201Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted modelReg: ModelReg,
                              @Assisted config: TapiConfig, @Assisted host: String)
  extends TapiTxxCollector(instrumentOp, monitorStatusOp,
    alarmOp, monitorTypeOp,
    calibrationOp, instrumentStatusOp)(instId, modelReg, config, host) {
  val logger: Logger = Logger(this.getClass)

  import DataCollectManager._

  val TNX = "TNX"
  val NH3 = MonitorType.NH3
  val NO = MonitorType.NO
  val NO2 = MonitorType.NO2
  val NOX = MonitorType.NOX

  override def reportData(regValue: ModelRegValue): Option[ReportData] =
    for {
      idxTNX <- findDataRegIdx(regValue)(46)
      idxNH3 <- findDataRegIdx(regValue)(50)
      idxNOx <- findDataRegIdx(regValue)(54)
      idxNO <- findDataRegIdx(regValue)(58)
      idxNO2 <- findDataRegIdx(regValue)(62)
      vTNX = regValue.inputs(idxTNX)
      vNH3 = regValue.inputs(idxNH3)
      vNOx = regValue.inputs(idxNOx)
      vNO = regValue.inputs(idxNO)
      vNO2 = regValue.inputs(idxNO2)
    } yield {
      ReportData(List(
        MonitorTypeData(TNX, vTNX._2.toDouble, collectorState),
        MonitorTypeData(NH3, vNH3._2.toDouble, collectorState),
        MonitorTypeData(NOX, vNOx._2.toDouble, collectorState),
        MonitorTypeData(NO, vNO._2.toDouble, collectorState),
        MonitorTypeData(NO2, vNO2._2.toDouble, collectorState)))
    }

  import com.serotonin.modbus4j.locator.BaseLocator

  override def triggerZeroCalibration(v: Boolean): Unit = {
    try {
      super.triggerZeroCalibration(v)

      if (!config.skipInternalVault.contains(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, 20)
        for (master <- masterOpt)
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
        for (master <- masterOpt)
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

      logger.info("Resetting T201 internal zero/span calibration to off")
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