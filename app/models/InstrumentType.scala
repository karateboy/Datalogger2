package models
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
case class ProtocolInfo(id: Protocol.Value, desp: String)
case class InstrumentTypeInfo(id: String, desp: String, protocolInfo: List[ProtocolInfo])
case class InstrumentType(id: String, desp: String, protocol: List[Protocol.Value],
                          driver: DriverOps, analog: Boolean = false) {
  def infoPair = id -> this
}

trait DriverOps {
  import Protocol.ProtocolParam
  import akka.actor._

  def verifyParam(param: String): String
  def getMonitorTypes(param: String): List[String]
  def getCalibrationTime(param: String): Option[LocalTime]
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext): ActorRef
}

import javax.inject._

@Singleton
class InstrumentTypeOp @Inject()
(adam4017Drv: Adam4017, moxaE1240Drv: MoxaE1240, moxaE1212Drv: MoxaE1212)
{
  import Protocol._

  implicit val prtocolWrite = Json.writes[ProtocolInfo]
  implicit val write = Json.writes[InstrumentTypeInfo]

  val baseline9000 = "baseline9000"
  val adam4017 = "adam4017"
  val adam4068 = "adam4068"
  val adam5000 = "adam5000"
  
  val t100 = "t100"
  val t200 = "t200"
  val t201 = "t201"
  val t300 = "t300"
  val t360 = "t360"
  val t400 = "t400"
  val t700 = "t700"

  val TapiTypes = List(t100, t200, t201, t300, t360, t400, t700)

  val verewa_f701 = "verewa_f701"

  val moxaE1240 = "moxaE1240"
  val moxaE1212 = "moxaE1212"

  val horiba370 = "horiba370"
  val gps = "gps"

  def getInstInfoPair(instType: InstrumentType) = {
    instType.id -> instType
  }

  val map = Map(
    InstrumentType(baseline9000, "Baseline 9000 MNME Analyzer", List(serial), Baseline9000).infoPair,
    InstrumentType(adam4017, "Adam 4017", List(serial), adam4017Drv, true).infoPair,
    InstrumentType(adam4068, "Adam 4068", List(serial), Adam4068, true).infoPair,
    InstrumentType(t100, "TAPI T100", List(tcp), TapiT100).infoPair,
    InstrumentType(t200, "TAPI T200", List(tcp), TapiT200).infoPair,
    InstrumentType(t201, "TAPI T201", List(tcp), TapiT201).infoPair,
    InstrumentType(t300, "TAPI T300", List(tcp), TapiT300).infoPair,
    InstrumentType(t360, "TAPI T360", List(tcp), TapiT360).infoPair,
    InstrumentType(t400, "TAPI T400", List(tcp), TapiT400).infoPair,
    InstrumentType(t700, "TAPI T700", List(tcp), TapiT700).infoPair,

    InstrumentType(verewa_f701, "Verewa F701-20", List(serial), VerewaF701_20).infoPair,
    InstrumentType(moxaE1240, "MOXA E1240", List(tcp), moxaE1240Drv).infoPair,
    InstrumentType(moxaE1212, "MOXA E1212", List(tcp), moxaE1212Drv).infoPair,
    InstrumentType(horiba370, "Horiba APXX-370", List(tcp), Horiba370).infoPair,
    InstrumentType(gps, "GPS", List(serial), GPS).infoPair)
}

