package models.mongodb

import com.github.nscala_time.time.Imports._
import com.mongodb.client.result.UpdateResult
import models.ModelHelper._
import models.{Alarm, AlarmDB, Monitor}
import org.mongodb.scala._
import org.mongodb.scala.model._
import org.mongodb.scala.result.UpdateResult
import play.api._

import java.time.Instant
import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

@Singleton
class AlarmOp @Inject()(mongodb: MongoDB) extends AlarmDB {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy private val colName = "alarms"
  lazy val codecRegistry = fromRegistries(fromProviders(classOf[Alarm]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[Alarm](colName).withCodecRegistry(codecRegistry)

  private def init() {
    import org.mongodb.scala.model.Indexes._
    for(colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongodb.database.createCollection(colName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            collection.createIndex(ascending("monitor","time", "level", "src"))
        })
      }
    }
  }

  init

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Sorts._

  override def getAlarmsFuture(level: Int, start: DateTime, end: DateTime): Future[Seq[Alarm]] = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val f = collection.find(and(gte("time", startB), lt("time", endB), gte("level", level))).sort(descending("time")).toFuture()
    f onFailure errorHandler()
    f
  }

  override def getAlarmsFuture(src: String, level: Int, start: DateTime, end: DateTime): Future[Seq[Alarm]] = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val f = collection.find(and(equal("src", src),
      gte("time", startB),
      lt("time", endB),
      gte("level", level))).sort(descending("time")).toFuture()

    f onFailure errorHandler()
    f
  }
  override def getAlarmsFuture(start: DateTime, end: DateTime): Future[Seq[Alarm]] = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    collection.find(and(gte("time", startB), lt("time", endB))).sort(descending("time")).toFuture()
  }

  private def logFilter(ar: Alarm, coldPeriod:Int = 30){
    import org.mongodb.scala.bson.BsonDateTime
    //None blocking...
    val start = Date.from(ar.time.toInstant.minusSeconds(coldPeriod * 60))
    val end = ar.time

    val countObserver = collection.countDocuments(and(gte("time", start), lt("time", end),
      equal("src", ar.src), equal("level", ar.level), equal("desc", ar.desc)))

    countObserver.subscribe(
      (count: Long) => {
        if (count == 0){
          val f = collection.insertOne(ar).toFuture()
        }
      }, // onNext
      (ex: Throwable) => Logger.error("Alarm failed:", ex), // onError
      () => {} // onComplete
    )

  }

  override def log(src: String, level: Int, desc: String, coldPeriod:Int = 30): Unit = {
    val ar = Alarm(Date.from(Instant.now()), src, level, desc, Monitor.activeId)
    logFilter(ar, coldPeriod)
  }

  override def getLatestMonitorRecordTimeAsync(monitor: String): Future[Option[DateTime]] = {
    val f = collection.find(Filters.equal("monitor", monitor))
      .sort(Sorts.descending("time")).limit(1).toFuture()
    for(ret<-f) yield {
      if(ret.isEmpty)
        None
      else
        Some(new DateTime(ret(0).time))
    }
  }


  override def insertAlarms(alarms: Seq[Alarm]): Future[UpdateResult] =
    for(ret<-collection.insertMany(alarms).toFuture()) yield
      UpdateResult.acknowledged(alarms.length, alarms.length, null)

  override def getMonitorAlarmsFuture(monitors: Seq[String], start: Date, end: Date): Future[Seq[Alarm]] = {
    val filter = Filters.and(Filters.in("monitor", monitors:_*),
      Filters.gt("time", start), Filters.lt("time", end))

    collection.find(filter).toFuture()
  }
}