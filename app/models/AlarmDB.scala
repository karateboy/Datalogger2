package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import play.api.libs.json.Json

import scala.concurrent.Future

case class Alarm2JSON(time: Long, src: String, level: Int, info: String)

case class Alarm(time: DateTime, src: String, level: Int, desc: String) {
  def toJson = Alarm2JSON(time.getMillis, src, level, desc)
}

trait AlarmDB {

  def src(mt: String) = s"T:$mt"

  def src(inst: Instrument) = s"I:${inst._id}"

  def instrumentSrc(id: String) = s"I:$id"

  def src() = "S:System"

  def srcCDX() = "S:CDX"

  def getAlarmsFuture(level: Int, start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]]

  def getAlarmsFuture(src:String, level: Int, start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]]

  implicit val write = Json.writes[Alarm]
  implicit val jsonWrite = Json.writes[Alarm2JSON]

  def getAlarmsFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]]

  def log(src: String, level: Int, desc: String, coldPeriod: Int = 30): Unit

  object Level {
    val INFO = 1
    val WARN = 2
    val ERR = 3
  }
}