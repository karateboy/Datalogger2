package models

import akka.actor.{ActorContext, ActorRef}
import com.github.nscala_time.time.Imports._
import play.api.Logger
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json._

case class ProtocolInfo(id: Protocol.ProtocolType, desp: String)

case class InstrumentTypeInfo(id: String, desp: String, protocolInfo: List[ProtocolInfo])

case class InstrumentType(id: String, desp: String, protocol: List[Protocol.ProtocolType],
                          driver: DriverOps, diFactory: AnyRef, analog: Boolean = false) {
  def infoPair = id -> this
}

object InstrumentType {
  implicit val prtocolWrite = Json.writes[ProtocolInfo]
  implicit val write = Json.writes[InstrumentTypeInfo]

  val BASELINE9000 = "baseline9000"
  val ADAM4017 = "adam4017"
  val ADAM4068 = "adam4068"
  val ADAM5000 = "adam5000"
  val ADAM6017 = "adam6017"
  val ADAM6066 = "adam6066"

  val T100 = "t100"
  val T200 = "t200"
  val T201 = "t201"
  val T300 = "t300"
  val T360 = "t360"
  val T400 = "t400"
  val T700 = "t700"

  val TapiTypes = List(T100, T200, T201, T300, T360, T400, T700)

  val VEREWA_F701 = "verewa_f701"

  val MOXAE1240 = "moxaE1240"
  val MOXAE1212 = "moxaE1212"

  val HORIBA370 = "horiba370"
  val GPS = "gps"
  val MQTT_CLIENT = "mqtt_client"
  val MQTT_CLIENT2 = "mqtt_client2"

  val THETA = "theta"

  val DoInstruments = Seq(ADAM6017, ADAM6066, MOXAE1212)
}

trait DriverOps {

  import Protocol.ProtocolParam
  import akka.actor._

  def verifyParam(param: String): String

  def getMonitorTypes(param: String): List[String]

  def getCalibrationTime(param: String): Option[LocalTime]

  def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor

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
 mqttFactory: MqttCollector.Factory,
 mqtt2Factory: MqttCollector2.Factory,
 baseline9000Factory: Baseline9000Collector.Factory,
 horiba370Factory: Horiba370Collector.Factory,
 gpsFactory: GpsCollector.Factory,
 t100Factory: T100Collector.Factory, t200Factory: T200Collector.Factory, t201Factory: T201Collector.Factory,
 t300Factory: T300Collector.Factory, t360Factory: T360Collector.Factory, t400Factory: T400Collector.Factory, t700Factory: T700Collector.Factory) extends InjectedActorSupport {

  import Protocol._
  import InstrumentType._

  val map = Map(
    InstrumentType(ADAM4017, "Adam 4017", List(Serial()), adam4017Drv, adam4017Factory, true).infoPair,
    InstrumentType(ADAM4068, "Adam 4068", List(Serial()), Adam4068, adam4068Factory, true).infoPair,
    InstrumentType(ADAM6017, "Adam 6017", List(Tcp()), adam6017Drv, adam6017Factory, true).infoPair,
    InstrumentType(ADAM6066, "Adam 6066", List(Tcp()), adam6066Drv, adam6066Factory, true).infoPair,
    InstrumentType(BASELINE9000, "Baseline 9000 MNME Analyzer", List(Serial()), Baseline9000Collector, baseline9000Factory).infoPair,
    InstrumentType(GPS, "GPS", List(Serial()), GpsCollector, gpsFactory).infoPair,
    InstrumentType(HORIBA370, "Horiba APXX-370", List(Tcp()), Horiba370Collector, horiba370Factory).infoPair,
    InstrumentType(MOXAE1240, "MOXA E1240", List(Tcp()), moxaE1240Drv, moxaE1240Factory).infoPair,
    InstrumentType(MOXAE1212, "MOXA E1212", List(Tcp()), moxaE1212Drv, moxaE1212Factory).infoPair,
    InstrumentType(MQTT_CLIENT, "MQTT Client", List(Tcp()), MqttCollector, mqttFactory).infoPair,
    InstrumentType(MQTT_CLIENT2, "MQTT Client2", List(Tcp()), MqttCollector2, mqtt2Factory).infoPair,
    InstrumentType(T100, "TAPI T100", List(Tcp()), T100Collector, t100Factory).infoPair,
    InstrumentType(T200, "TAPI T200", List(Tcp()), T200Collector, t200Factory).infoPair,
    InstrumentType(T201, "TAPI T201", List(Tcp()), T201Collector, t201Factory).infoPair,
    InstrumentType(T300, "TAPI T300", List(Tcp()), T300Collector, t300Factory).infoPair,
    InstrumentType(T360, "TAPI T360", List(Tcp()), T360Collector, t360Factory).infoPair,
    InstrumentType(T400, "TAPI T400", List(Tcp()), T400Collector, t400Factory).infoPair,
    InstrumentType(T700, "TAPI T700", List(Tcp()), T700Collector, t700Factory).infoPair,
    InstrumentType(VEREWA_F701, "Verewa F701-20", List(Serial()), VerewaF701Collector, verewaF701Factory).infoPair,
    InstrumentType(THETA, "THETA", List(Serial()), ThetaCollector, thetaFactory).infoPair
  )

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

