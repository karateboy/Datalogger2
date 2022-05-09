package models.mongodb

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models.{Alarm, AlarmDB}
import org.mongodb.scala._
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

@Singleton
class AlarmOp @Inject()(mongodb: MongoDB) extends AlarmDB {
  lazy private val collectionName = "alarms"
  lazy private val collection = mongodb.database.getCollection(collectionName)
  private def toDocument(ar: Alarm) = {
    import org.mongodb.scala.bson._
    Document("time" -> (ar.time: BsonDateTime), "src" -> ar.src, "level" -> ar.level, "desc" -> ar.desc)
  }

  private def toAlarm(doc: Document) = {
    val time = new DateTime(doc.get("time").get.asDateTime().getValue)
    val src = doc.get("src").get.asString().getValue
    val level = doc.get("level").get.asInt32().getValue
    val desc = doc.get("desc").get.asString().getValue
    Alarm(time, src, level, desc)
  }

  private def init() {
    import org.mongodb.scala.model.Indexes._
    for(colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(collectionName)) {
        val f = mongodb.database.createCollection(collectionName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            collection.createIndex(ascending("time", "level", "src"))
        })
      }
    }
  }

  init

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Sorts._

  override def getAlarms(level: Int, start: DateTime, end: DateTime): Seq[Alarm] = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val f = collection.find(and(gte("time", startB), lt("time", endB), gte("level", level))).sort(ascending("time")).toFuture()

    val docs = waitReadyResult(f)
    docs.map { toAlarm }
  }

  override def getAlarmsFuture(start: DateTime, end: DateTime): Future[Seq[Alarm]] = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val f = collection.find(and(gte("time", startB), lt("time", endB))).sort(ascending("time")).toFuture()

    for (docs <- f)
      yield docs.map { toAlarm }
  }

  private def logFilter(ar: Alarm, coldPeriod:Int = 30){
    import org.mongodb.scala.bson.BsonDateTime
    //None blocking...
    val start: BsonDateTime = ar.time - coldPeriod.minutes
    val end: BsonDateTime = ar.time

    val countObserver = collection.countDocuments(and(gte("time", start), lt("time", end),
      equal("src", ar.src), equal("level", ar.level), equal("desc", ar.desc)))

    countObserver.subscribe(
      (count: Long) => {
        if (count == 0){
          val f = collection.insertOne(toDocument(ar)).toFuture()
        }
      }, // onNext
      (ex: Throwable) => Logger.error("Alarm failed:", ex), // onError
      () => {} // onComplete
    )

  }

  override def log(src: String, level: Int, desc: String, coldPeriod:Int = 30): Unit = {
    val ar = Alarm(DateTime.now(), src, level, desc)
    logFilter(ar, coldPeriod)
  }
}