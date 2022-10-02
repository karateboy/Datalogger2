package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import org.mongodb.scala.result.UpdateResult
import play.api.libs.json.Json

import java.util.Date
import scala.concurrent.Future

case class Alarm2JSON(time: Long, src: String, level: Int, info: String)

case class Alarm(time: Date, src: String, level: Int, desc: String, monitor:String){
  def toJson = Alarm2JSON(time.getTime, src, level, desc)
}

object Alarm {
  implicit val write = Json.writes[Alarm]
  implicit val jsonWrite = Json.writes[Alarm2JSON]

}

trait AlarmDB {

  def src(mt: String) = s"T:$mt"

  def src(inst: Instrument) = s"I:${inst._id}"

  def instrumentSrc(id: String) = s"I:$id"

  def src() = "S:System"

  def srcCDX() = "S:CDX"

  def getAlarmsFuture(level: Int, start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]]

  def getMonitorAlarmsFuture(monitors:Seq[String], start: Date, end: Date): Future[Seq[Alarm]]

  def getAlarmsFuture(src:String, level: Int, start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]]

  def getAlarmsFuture(start: Imports.DateTime, end: Imports.DateTime): Future[Seq[Alarm]]

  def log(src: String, level: Int, desc: String, coldPeriod: Int = 30): Unit

  def getLatestMonitorRecordTimeAsync(monitor:String) : Future[Option[DateTime]]

  def insertAlarms(alarms:Seq[Alarm]) : Future[UpdateResult]

  object Level {
    val INFO = 1
    val WARN = 2
    val ERR = 3
  }

}
