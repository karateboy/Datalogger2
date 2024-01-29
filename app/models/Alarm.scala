package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import org.mongodb.scala._
import play.api._
import play.api.libs.json._

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

//alarm src format: 'T':"MonitorType"
//                  'I':"Instrument"
//                  'S':"System"
import javax.inject._
case class Alarm2JSON(time: Long, src: String, level: Int, info: String)

case class Alarm(time: Date, src: String, level: Int, desc: String) {
  def toJson = Alarm2JSON(time.getTime, src, level, desc)
}

@Singleton
class AlarmOp @Inject()(mongoDB: MongoDB) {
  object Level {
    val INFO = 1
    val WARN = 2
    val ERR = 3
    val map = Map(INFO -> "資訊", WARN -> "警告", ERR -> "嚴重")
  }
  val alarmLevelList: Seq[Int] = Level.INFO to Level.ERR

  def Src(src: String) = s"T:$src"
  def Src(inst: Instrument) = s"I:${inst._id}"

  def Src(sensor: Sensor) = s"S:${sensor.id}"

  def instStr(id: String) = s"I:$id"
  def Src() = "S:System"

  implicit val write = Json.writes[Alarm]
  implicit val jsonWrite = Json.writes[Alarm2JSON]

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[Alarm]), DEFAULT_CODEC_REGISTRY)
  val colName = "alarms"
  val collection = mongoDB.database.getCollection[Alarm](colName).withCodecRegistry(codecRegistry)


  def init() {
    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) { // New
        val f = mongoDB.database.createCollection(colName).toFuture()
        f.onFailure(errorHandler)
        waitReadyResult(f)
      }
    }
  }

  init

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Sorts._

  def getAlarms(level: Int, src:String, start: DateTime, end: DateTime): Future[Seq[Alarm]] = {
    val f = collection.find(and(gte("time", start.toDate),
      lt("time", end.toDate),
      gte("level", level),
      regex("src", src)
    )).sort(ascending("time")).toFuture()
    f onFailure(errorHandler)
    f
  }


  def getAlarmsFuture(start: DateTime, end: DateTime): Future[Seq[Alarm]] = {
    val f = collection.find(and(gte("time", start.toDate), lt("time", end.toDate))).sort(ascending("time")).toFuture()
    f onFailure(errorHandler)
    f
  }

  private def logFilter(ar: Alarm, coldPeriod:Int = 30): Unit = {
    val start = new DateTime(ar.time).minusMinutes(coldPeriod).toDate
    val end = new DateTime(ar.time).toDate

    val countObserver = collection.countDocuments(and(gte("time", start), lt("time", end),
      equal("src", ar.src), equal("level", ar.level), equal("desc", ar.desc)))

    countObserver.subscribe(
      (count: Long) => {
        if (count == 0){
          val f = collection.insertOne(ar).toFuture()
          f onFailure(errorHandler)
        }
      }, // onNext
      (ex: Throwable) => Logger.error("Alarm failed:", ex), // onError
      () => {} // onComplete
      )

  }

  def log(src: String, level: Int, desc: String, coldPeriod:Int = 30): Unit = {
    val ar = Alarm(DateTime.now().toDate, src, level, desc)
    logFilter(ar, coldPeriod)
  }  
}