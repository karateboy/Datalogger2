package models

import akka.actor.{ActorContext, ActorRef}
import com.github.nscala_time.time.Imports._
import play.api.Logger
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json._

case class ProtocolInfo(id: Protocol.Value, desp: String)

case class InstrumentTypeInfo(id: String, desp: String, protocolInfo: List[ProtocolInfo])

case class InstrumentType(id: String, desp: String, protocol: List[Protocol.Value],
                          driver: DriverOps, diFactory: AnyRef, analog: Boolean)

object InstrumentType {
  def apply(driver: DriverOps, diFactory: AnyRef, analog: Boolean = false): InstrumentType =
    InstrumentType(driver.id, driver.description, driver.protocol, driver, diFactory, analog)
}

trait DriverOps {

  import Protocol.ProtocolParam
  import akka.actor._

  def id: String
  def description: String
  def protocol: List[Protocol.Value]

  def verifyParam(param: String): String

  def getMonitorTypes(param: String): List[String]

  def getCalibrationTime(param: String): Option[LocalTime]

  def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor

  def isDoInstrument: Boolean = false

  def isCalibrator:Boolean = false
}

import javax.inject._

@Singleton
class InstrumentTypeOp @Inject()
(adam4017Drv: Adam4017, adam4017Factory: Adam4017Collector.Factory, adam4068Factory: Adam4068Collector.Factory,
 adam6017Drv: Adam6017, adam6017Factory: Adam6017Collector.Factory,
 adam6066Drv: Adam6066, adam6066Factory: Adam6066Collector.Factory,
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
 akDrvFactory: AkDrv.Factory, duoFactory: Duo.Factory,
 tcpModbusFactory: TcpModbusDrv2.Factory, sabio4010Factory: Sabio4010.Factory,monitorTypeOp: MonitorTypeOp) extends InjectedActorSupport {

  import Protocol._

  implicit val prtocolWrite = Json.writes[ProtocolInfo]
  implicit val write = Json.writes[InstrumentTypeInfo]

  val tcpModbusDeviceTypeMap: Map[String, InstrumentType] =
    TcpModbusDrv2.getInstrumentTypeList(environment, tcpModbusFactory, monitorTypeOp)
      .map(dt => dt.id -> dt).toMap

  val akDeviceTypeMap: Map[String, InstrumentType] =
    AkDrv.getInstrumentTypeList(environment, akDrvFactory, monitorTypeOp)
    .map(dt => dt.id -> dt).toMap

  val otherDeviceList = Seq(InstrumentType(Duo, duoFactory))

  val otherMap = otherDeviceList.map(dt=> dt.id->dt).toMap
  val map: Map[String, InstrumentType] = otherMap

  val DoInstruments = otherDeviceList.filter(_.driver.isDoInstrument)
  var count = 0

  def getInstInfoPair(instType: InstrumentType) = {
    instType.id -> instType
  }

  def start(instType: String, id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext): ActorRef = {
    val actorName = s"${instType}_${count}"
    Logger.info(s"$actorName is created.")
    count += 1

    val instrumentType = map(instType)
    injectedChild(instrumentType.driver.factory(id, protocol, param)(instrumentType.diFactory), actorName)
  }
}

