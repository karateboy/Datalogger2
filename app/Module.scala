import com.google.inject.AbstractModule
import models._
import play.api._
import play.api.libs.concurrent.AkkaGuiceSupport
/**
 * This class is a Guice module that tells Guice how to bind several
 * different types. This Guice module is created when the Play
 * application starts.

 * Play will automatically use any class called `Module` that is in
 * the root package. You can create modules in other locations by
 * adding `play.modules.enabled` settings to the `application.conf`
 * configuration file.
 */
class Module(environment: Environment,
             configuration: Configuration) extends AbstractModule with AkkaGuiceSupport {
  override def configure(): Unit = {
    LoggerConfig.init(configuration)
    val db = LoggerConfig.config.db
    if(db == "sql"){
      import models.sql._
      bind(classOf[AlarmDB]).to(classOf[AlarmOp])
      bind(classOf[CalibrationDB]).to(classOf[CalibrationOp])
      bind(classOf[ConstantRuleDB]).to(classOf[ConstantRuleOp])
      bind(classOf[EmailTargetDB]).to(classOf[EmailTargetOp])
      bind(classOf[GroupDB]).to(classOf[GroupOp])
      bind(classOf[InstrumentDB]).to(classOf[InstrumentOp])
      bind(classOf[InstrumentStatusDB]).to(classOf[InstrumentStatusOp])
      bind(classOf[ManualAuditLogDB]).to(classOf[ManualAuditLogOp])
      bind(classOf[MonitorDB]).to(classOf[MonitorOp])
      bind(classOf[MonitorStatusDB]).to(classOf[MonitorStatusOp])
      bind(classOf[MonitorTypeDB]).to(classOf[MonitorTypeOp])
      bind(classOf[MqttSensorDB]).to(classOf[MqttSensorOp])
      bind(classOf[RecordDB]).to(classOf[RecordOp])
      bind(classOf[SpikeRuleDB]).to(classOf[SpikeRuleOp])
      bind(classOf[SysConfigDB]).to(classOf[SysConfig])
      bind(classOf[UserDB]).to(classOf[UserOp])
      bind(classOf[VariationRuleDB]).to(classOf[VariationRuleOp])
      bind(classOf[AlarmRuleDb]).to(classOf[AlarmRuleOp])
      bind(classOf[CalibrationConfigDB]).to(classOf[CalibrationConfigOp])
      bind(classOf[MonitorTypeGroupDb]).to(classOf[MonitorTypeGroupOp])
    }else{
      import models.mongodb._
      bind(classOf[AlarmDB]).to(classOf[AlarmOp])
      bind(classOf[CalibrationDB]).to(classOf[CalibrationOp])
      bind(classOf[ConstantRuleDB]).to(classOf[ConstantRuleOp])
      bind(classOf[EmailTargetDB]).to(classOf[EmailTargetOp])
      bind(classOf[GroupDB]).to(classOf[GroupOp])
      bind(classOf[InstrumentDB]).to(classOf[InstrumentOp])
      bind(classOf[InstrumentStatusDB]).to(classOf[InstrumentStatusOp])
      bind(classOf[ManualAuditLogDB]).to(classOf[ManualAuditLogOp])
      bind(classOf[MonitorDB]).to(classOf[MonitorOp])
      bind(classOf[MonitorStatusDB]).to(classOf[MonitorStatusOp])
      bind(classOf[MonitorTypeDB]).to(classOf[MonitorTypeOp])
      bind(classOf[MqttSensorDB]).to(classOf[MqttSensorOp])
      bind(classOf[RecordDB]).to(classOf[RecordOp])
      bind(classOf[SpikeRuleDB]).to(classOf[SpikeRuleOp])
      bind(classOf[SysConfigDB]).to(classOf[SysConfig])
      bind(classOf[UserDB]).to(classOf[UserOp])
      bind(classOf[VariationRuleDB]).to(classOf[VariationRuleOp])
      bind(classOf[AlarmRuleDb]).to(classOf[AlarmRuleOp])
      bind(classOf[CalibrationConfigDB]).to(classOf[CalibrationConfigOp])
      bind(classOf[MonitorTypeGroupDb]).to(classOf[MonitorTypeGroupOp])
    }

    bindActor[DataCollectManager]("dataCollectManager")
    bindActor[OpenDataReceiver]("openDataReceiver")

    bindActorFactory[Adam6017Collector, Adam6017Collector.Factory]
    bindActorFactory[Adam6066Collector, Adam6066Collector.Factory]
    bindActorFactory[Adam4000Collector, Adam4000Collector.Factory]
    bindActorFactory[Baseline9000Collector, Baseline9000Collector.Factory]
    bindActorFactory[GpsCollector, GpsCollector.Factory]
    bindActorFactory[Horiba370Collector, Horiba370Collector.Factory]
    bindActorFactory[HoribaApnaCollector, HoribaApnaCollector.Factory]
    bindActorFactory[HoribaApsaCollector, HoribaApsaCollector.Factory]
    bindActorFactory[HoribaApoaCollector, HoribaApoaCollector.Factory]
    bindActorFactory[HoribaApmaCollector, HoribaApmaCollector.Factory]
    bindActorFactory[MoxaE1212Collector, MoxaE1212Collector.Factory]
    bindActorFactory[MoxaE1240Collector, MoxaE1240Collector.Factory]
    bindActorFactory[MqttCollector2, MqttCollector2.Factory]
    bindActorFactory[T100Collector, T100Collector.Factory]
    bindActorFactory[T200Collector, T200Collector.Factory]
    bindActorFactory[T201Collector, T201Collector.Factory]
    bindActorFactory[T300Collector, T300Collector.Factory]
    bindActorFactory[T360Collector, T360Collector.Factory]
    bindActorFactory[T400Collector, T400Collector.Factory]
    bindActorFactory[T700Collector, T700Collector.Factory]
    bindActorFactory[T100CliCollector, T100Collector.CliFactory]
    bindActorFactory[T200CliCollector, T200Collector.CliFactory]
    bindActorFactory[T300CliCollector, T300Collector.CliFactory]
    bindActorFactory[T400CliCollector, T400Collector.CliFactory]
    bindActorFactory[T700CliCollector, T700Collector.CliFactory]
    bindActorFactory[VerewaF701Collector, VerewaF701Collector.Factory]
    bindActorFactory[ThetaCollector, ThetaCollector.Factory]
    bindActorFactory[TcpModbusCollector, TcpModbusDrv2.Factory]
    bindActorFactory[Sabio4010Collector, Sabio4010.Factory]
    bindActorFactory[Tca08Collector, Tca08Collector.Factory]
    bindActorFactory[PicarroG2401Collector, PicarroG2401.Factory]
    bindActorFactory[PicarroG2131iCollector, PicarroG2131iCollector.Factory]
    bindActorFactory[PicarroG2307Collector, PicarroG2307.Factory]
    bindActorFactory[Ma350Collector, Ma350Drv.Factory]
    bindActorFactory[MetOne1020Collector, MetOne1020.Factory]
  	bindActorFactory[AkDrvCollector, AkDrv.Factory]
	  bindActorFactory[DuoCollector, Duo.Factory]
    bindActorFactory[EcoPhysics88PCollector, EcoPhysics88P.Factory]
    bindActorFactory[EcoPhysics88PNOCollector, EcoPhysics88PNO.Factory]
    bindActorFactory[HydreonRainGaugeCollector, HydreonRainGauge.Factory]
    bindActorFactory[UpsCollector, UpsDrv.Factory]
    bindActorFactory[PseudoDeviceCollector, PseudoDevice.Factory]
    bindActorFactory[PseudoDevice2, PseudoDevice2.Factory]
    bindActorFactory[PseudoDevice3, PseudoDevice3.Factory]
    bindActorFactory[ForwardManager, ForwardManager.Factory]
    bindActorFactory[HourRecordForwarder, HourRecordForwarder.Factory]
    bindActorFactory[MinRecordForwarder, MinRecordForwarder.Factory]
    bindActorFactory[CalibrationForwarder, CalibrationForwarder.Factory]
    bindActorFactory[AlarmForwarder, AlarmForwarder.Factory]
    bindActorFactory[InstrumentStatusForwarder, InstrumentStatusForwarder.Factory]
    bindActorFactory[InstrumentStatusTypeForwarder, InstrumentStatusTypeForwarder.Factory]
  }

}
