package models

import play.api.libs.json.{Json, OWrites}

import java.util.Date
import scala.concurrent.Future

case class Alarm(time: Date, src: String, level: Int, desc: String)

trait AlarmDB {

  def src(mt: String) = s"T:$mt"

  def srcInstrumentID(id:String) = s"I:$id"

  def src(inst: Instrument) = s"I:${inst._id}"

  def instrumentSrc(id: String) = s"I:$id"

  def src() = "S:System"

  def srcCDX() = "S:CDX"

  def getAlarmsFuture(level: Int, start: Date, end: Date): Future[Seq[Alarm]]

  def getAlarmsFuture(src:String, level: Int, start: Date, end: Date): Future[Seq[Alarm]]

  implicit val write: OWrites[Alarm] = Json.writes[Alarm]

  def getAlarmsFuture(start: Date, end: Date): Future[Seq[Alarm]]

  def log(src: String, level: Int, desc: String, coldPeriod: Int = 30): Unit

  def log(ar: Alarm): Unit = log(ar.src, ar.level, ar.desc)

  object Level {
    val INFO = 1
    val WARN = 2
    val ERR = 3
  }
}
