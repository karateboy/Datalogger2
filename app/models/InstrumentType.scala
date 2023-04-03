package models

import akka.actor.{ActorContext, ActorRef}
import com.github.nscala_time.time.Imports._
import play.api.Logger
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json._

case class ProtocolInfo(id: String, desp: String)

case class InstrumentTypeInfo(id: String, desp: String, protocolInfo: List[ProtocolInfo])

case class InstrumentType(id: String, desp: String, protocol: List[String],
                          driver: DriverOps, diFactory: AnyRef, analog: Boolean, diFactory2: Option[AnyRef])

object InstrumentType {
  def apply(driver: DriverOps, diFactory: AnyRef, analog: Boolean = false, diFactory2: Option[AnyRef] = None): InstrumentType =
    InstrumentType(driver.id, driver.description, driver.protocol, driver, diFactory, analog, diFactory2)
}

trait DriverOps {

  import Protocol.ProtocolParam
  import akka.actor._

  def id: String
  def description: String
  def protocol: List[String]

  def verifyParam(param: String): String

  def getMonitorTypes(param: String): List[String]

  def getCalibrationTime(param: String): Option[LocalTime]

  def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, f2:Option[AnyRef]): Actor

  def isDoInstrument: Boolean = false

  def isCalibrator:Boolean = false

}

import javax.inject._

@Singleton
class InstrumentTypeOp @Inject()
(adam6017Drv: Adam6017, adam6017Factory: Adam6017Collector.Factory,
 adam6066Drv: Adam6066, adam6066Factory: Adam6066Collector.Factory,
 adam4000Factory: Adam4000Collector.Factory,
 moxaE1240Drv: MoxaE1240, moxaE1240Factory: MoxaE1240Collector.Factory,
 verewaF701Factory: VerewaF701Collector.Factory,
 thetaFactory: ThetaCollector.Factory,
 moxaE1212Drv: MoxaE1212, moxaE1212Factory: MoxaE1212Collector.Factory,
 mqtt2Factory: MqttCollector2.Factory,
 baseline9000Factory: Baseline9000Collector.Factory,
 horiba370Factory: Horiba370Collector.Factory,
 gpsFactory: GpsCollector.Factory,
 t100Factory: T100Collector.Factory, t200Factory: T200Collector.Factory, t201Factory: T201Collector.Factory,
 t300Factory: T300Collector.Factory, t360Factory: T360Collector.Factory, t400Factory: T400Collector.Factory,
 t700Factory: T700Collector.Factory, environment: play.api.Environment,
 t100CliFactory: T100Collector.CliFactory,
 t200CliFactory: T200Collector.CliFactory,
 t300CliFactory: T300Collector.CliFactory,
 t400CliFactory: T400Collector.CliFactory,
 t700CliFactory: T700Collector.CliFactory,
 akDrvFactory: AkDrv.Factory, duoFactory: Duo.Factory,
 tcpModbusFactory: TcpModbusDrv2.Factory,
 sabio4010Factory: Sabio4010.Factory,
 tca08Factory: Tca08Drv.Factory,
 picarroG2401Factory: PicarroG2401.Factory,
 picarroG2131iFactory: PicarroG2131i.Factory,
 ma350Factory: Ma350Drv.Factory,
 metOne1020Factory: MetOne1020.Factory,
 ecoPhysics88PFactory: EcoPhysics88P.Factory,
 hydreonRainGaugeFactory: HydreonRainGauge.Factory,
 upsFactory: UpsDrv.Factory,
 monitorTypeOp: MonitorTypeDB) extends InjectedActorSupport {

  import Protocol._

  implicit val prtocolWrite = Json.writes[ProtocolInfo]
  implicit val write = Json.writes[InstrumentTypeInfo]

  val tcpModbusDeviceTypeMap: Map[String, InstrumentType] =
    TcpModbusDrv2.getInstrumentTypeList(environment, tcpModbusFactory, monitorTypeOp)
      .map(dt => dt.id -> dt).toMap

  val akDeviceTypeMap: Map[String, InstrumentType] =
    AkDrv.getInstrumentTypeList(environment, akDrvFactory, monitorTypeOp)
    .map(dt => dt.id -> dt).toMap

  val otherDeviceList = Seq(
    InstrumentType(Adam4000, adam4000Factory, true),
    InstrumentType(adam6017Drv, adam6017Factory, true),
    InstrumentType(adam6066Drv, adam6066Factory, true),
    InstrumentType(Baseline9000Collector, baseline9000Factory),
    InstrumentType(GpsCollector, gpsFactory),
    InstrumentType(Horiba370Collector, horiba370Factory),
    InstrumentType(moxaE1240Drv, moxaE1240Factory),
    InstrumentType(moxaE1212Drv, moxaE1212Factory),
    InstrumentType(MqttCollector2, mqtt2Factory),
    InstrumentType(T100Collector, t100Factory, false, Some(t100CliFactory)),
    InstrumentType(T200Collector, t200Factory, false, Some(t200CliFactory)),
    InstrumentType(T201Collector, t201Factory),
    InstrumentType(T300Collector, t300Factory, false, Some(t300CliFactory)),
    InstrumentType(T360Collector, t360Factory),
    InstrumentType(T400Collector, t400Factory, false, Some(t400CliFactory)),
    InstrumentType(T700Collector, t700Factory, false, Some(t700CliFactory)),
    InstrumentType(VerewaF701Collector, verewaF701Factory),
    InstrumentType(ThetaCollector, thetaFactory),
    InstrumentType(Sabio4010, sabio4010Factory),
    InstrumentType(Duo, duoFactory),
    InstrumentType(Tca08Drv, tca08Factory),
    InstrumentType(PicarroG2401, picarroG2401Factory),
    InstrumentType(PicarroG2131i, picarroG2131iFactory),
    InstrumentType(Ma350Drv, ma350Factory),
    InstrumentType(MetOne1020, metOne1020Factory),
    InstrumentType(EcoPhysics88P, ecoPhysics88PFactory),
    InstrumentType(HydreonRainGauge, hydreonRainGaugeFactory),
    InstrumentType(UpsDrv, upsFactory)
  )

  val otherMap = otherDeviceList.map(dt=> dt.id->dt).toMap
  val map: Map[String, InstrumentType] = tcpModbusDeviceTypeMap ++ akDeviceTypeMap ++ otherMap

  val DoInstruments = otherDeviceList.filter(_.driver.isDoInstrument)
  var count = 0

  def getInstInfoPair(instType: InstrumentType): (String, InstrumentType) = {
    instType.id -> instType
  }

  def start(instType: String, id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext): ActorRef = {
    val actorName = s"${instType}_${count}"
    Logger.info(s"$actorName is created.")
    count += 1

    val instrumentType = map(instType)
    injectedChild(instrumentType.driver.factory(id, protocol, param)(instrumentType.diFactory, instrumentType.diFactory2), actorName)
  }
}

