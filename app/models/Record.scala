package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import org.mongodb.scala._
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.{BsonArray, BsonInt32}
import org.mongodb.scala.result.DeleteResult
import play.api._

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class MtRecord(mtName: String, value: Double, status: String)

object RecordList {
  def apply(time: Date, mtDataList: Seq[MtRecord], monitor: String): RecordList =
    RecordList(mtDataList, RecordListID(time, monitor))
}
case class MonitorRecord(time: Date, mtDataList: Seq[MtRecord], _id: String, var location: Option[Seq[Double]],
                         count: Option[Int], pm25Max: Option[Double], pm25Min: Option[Double],
                         var shortCode: Option[String], var code: Option[String], var tags: Option[Seq[String]],
                         var locationDesc: Option[String])

case class RecordList(var mtDataList: Seq[MtRecord], _id: RecordListID) {
  def mtMap = {
    val pairs =
      mtDataList map { data => data.mtName -> data }
    pairs.toMap

  }
}

case class RecordListID(time: Date, monitor: String)

case class Record(time: DateTime, value: Double, status: String, monitor: String)

import javax.inject._

@Singleton
class RecordOp @Inject()(mongoDB: MongoDB, monitorTypeOp: MonitorTypeOp, monitorOp: MonitorOp) {

  import org.mongodb.scala.model._
  import play.api.libs.json._

  implicit val writer = Json.writes[Record]

  val HourCollection = "hour_data"
  val MinCollection = "min_data"
  val SecCollection = "sec_data"


  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[RecordList], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)

  def init() {
    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(HourCollection)) {
        val f = mongoDB.database.createCollection(HourCollection).toFuture()
        f.onFailure(errorHandler)
      }

      if (!colNames.contains(MinCollection)) {
        val f = mongoDB.database.createCollection(MinCollection).toFuture()
        f.onFailure(errorHandler)
      }

      if (!colNames.contains(SecCollection)) {
        val f = mongoDB.database.createCollection(SecCollection).toFuture()
        f.onFailure(errorHandler)
      }
    }
  }

  getCollection(HourCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true)).toFuture()
  getCollection(MinCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true)).toFuture()
  getCollection(HourCollection).createIndex(Indexes.descending("_id.monitor", "_id.time"), new IndexOptions().unique(true)).toFuture()
  getCollection(MinCollection).createIndex(Indexes.descending("_id.monitor", "_id.time"), new IndexOptions().unique(true)).toFuture()

  init

  def toRecordList(dt: DateTime, dataList: List[(String, (Double, String))], monitor: String = Monitor.SELF_ID) = {
    val mtDataList = dataList map { t => MtRecord(t._1, t._2._1, t._2._2) }
    RecordList(mtDataList, RecordListID(dt, monitor))
  }

  def insertManyRecord(docs: Seq[RecordList])(colName: String) = {
    val col = getCollection(colName)
    val f = col.insertMany(docs).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def upsertRecord(doc: RecordList)(colName: String) = {
    import org.mongodb.scala.model.ReplaceOptions

    val col = getCollection(colName)

    val f = col.replaceOne(Filters.equal("_id", RecordListID(doc._id.time, doc._id.monitor)), doc, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def updateRecordStatus(dt: Long, mt: String, status: String, monitor: String = Monitor.SELF_ID)(colName: String) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    val col = getCollection(colName)

    val f = col.updateOne(
      and(equal("_id", RecordListID(new DateTime(dt), monitor)),
        equal("mtDataList.mtName", mt)), set("mtDataList.$.status", status)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def getRecordMap(colName: String)
                  (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(equal("_id.monitor", monitor), gte("_id.time", startTime.toDate()), lt("_id.time", endTime.toDate())))
      .sort(ascending("_id.time")).toFuture()
    val docs = waitReadyResult(f)
    val pairs =
      for {
        mt <- mtList
      } yield {
        val list =
          for {
            doc <- docs
            time = doc._id.time
            mtMap = doc.mtMap if mtMap.contains(mt)
          } yield {
            Record(new DateTime(time.getTime), mtMap(mt).value, mtMap(mt).status, monitor)
          }

        mt -> list
      }
    Map(pairs: _*)
  }

  def getRecord2Map(colName: String)(mtList: List[String], startTime: DateTime, endTime: DateTime, monitor: String = Monitor.SELF_ID)
                   (skip: Int = 0, limit: Int = 500) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(equal("_id.monitor", monitor), gte("_id.time", startTime.toDate()), lt("_id.time", endTime.toDate())))
      .sort(ascending("_id.time")).skip(skip).limit(limit).toFuture()
    val docs = waitReadyResult(f)

    val pairs =
      for {
        mt <- mtList
      } yield {
        val list =
          for {
            doc <- docs
            time = doc._id.time
            mtMap = doc.mtMap if mtMap.contains(mt)
          } yield {
            Record(new DateTime(time.getTime), mtMap(mt).value, mtMap(mt).status, monitor)
          }

        mt -> list
      }
    Map(pairs: _*)
  }

  def getCollection(colName: String) = mongoDB.database.getCollection[RecordList](colName).withCodecRegistry(codecRegistry)

  implicit val mtRecordWrite = Json.writes[MtRecord]
  implicit val idWrite = Json.writes[RecordListID]
  implicit val recordListWrite = Json.writes[RecordList]

  def getRecordListFuture(colName: String)(startTime: DateTime, endTime: DateTime, monitors: Seq[String] = Seq(Monitor.SELF_ID)): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    col.find(and(in("_id.monitor", monitors: _*), gte("_id.time", startTime.toDate), lt("_id.time", endTime.toDate)))
      .sort(ascending("_id.time")).toFuture()
  }

  def getRecordWithLimitFuture(colName: String)(startTime: DateTime, endTime: DateTime, limit: Int, monitor: String = Monitor.SELF_ID): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    col.find(and(equal("_id.monitor", monitor), gte("_id.time", startTime.toDate), lt("_id.time", endTime.toDate)))
      .limit(limit).sort(ascending("_id.time")).toFuture()

  }

  def getLatestRecordFuture(colName: String)(monitor: String): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    col.find(equal("_id.monitor", monitor))
      .sort(descending("_id.time")).limit(1).toFuture()

  }

  def getLatestRecordWithOldestLimitFuture(colName: String)(monitor: String, oldest:Date): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    col.find(and(equal("_id.monitor", monitor), gte("_id.time", oldest)))
      .sort(descending("_id.time")).limit(1).toFuture()

  }

  def cleanupOldData(colName: String)() = {
    val col = getCollection(colName)
    val twoMonthBefore = DateTime.now().minusMonths(2).toDate
    val f  = col.deleteMany(Filters.lt("_id.time", twoMonthBefore)).toFuture()
    f onFailure(errorHandler())
    f
  }

  val addPm25DataStage: Bson = {
    val filterDoc = Document("$filter" -> Document(
      "input" -> "$mtDataList",
      "as" -> "mtData",
      "cond" -> Document(
        "$eq" -> Seq("$$mtData.mtName", "PM25")
      )))
    val bsonArray = BsonArray(filterDoc.toBsonDocument, new BsonInt32(0))
    Aggregates.addFields(Field("pm25Data",
      Document("$arrayElemAt" -> bsonArray)))
  }

  def addMtDataStage(mt:String): Bson = {
    val filterDoc = Document("$filter" -> Document(
      "input" -> "$mtDataList",
      "as" -> "mtData",
      "cond" -> Document(
        "$eq" -> Seq("$$mtData.mtName", mt)
      )))
    val bsonArray = BsonArray(filterDoc.toBsonDocument, new BsonInt32(0))
    Aggregates.addFields(Field(s"${mt}Data",
      Document("$arrayElemAt" -> bsonArray)))
  }

  def getSensorCount(colName: String)
                    (start: DateTime = DateTime.now()): Future[Seq[MonitorRecord]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val targetMonitors = monitorOp.mvList
    val monitorFilter =
      Aggregates.filter(Filters.in("_id.monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("_id.time"), descending("_id.monitor")))
    val begin = start.minusDays(1)
    val end = start
    val timeFrameFilter = Aggregates.filter(Filters.and(
      Filters.gte("_id.time", begin.toDate),
      Filters.lt("_id.time", end.toDate)))

    val latestFilter = Aggregates.group(id = "$_id.monitor", Accumulators.first("time", "$_id.time"),
      Accumulators.first("mtDataList", "$mtDataList"),
      Accumulators.sum("count", 1))

    val projectStage = Aggregates.project(fields(
      Projections.include("time", "id", "mtDataList", "count")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage, latestFilter, projectStage))
      .allowDiskUse(true).toFuture()
  }

  def getLast30MinConstantSensor(colName: String): Future[Seq[MonitorRecord]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val targetMonitors = monitorOp.mvList
    val monitorFilter =
      Aggregates.filter(Filters.in("_id.monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("_id.time"), descending("_id.monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("_id.time", DateTime.now.minusMinutes(30).toDate)))

    val addPm25ValueStage = Aggregates.addFields(Field("pm25", "$pm25Data.value"))
    val latestFilter = Aggregates.group(id = "$_id.monitor", Accumulators.first("time", "$_id.time"),
      Accumulators.first("mtDataList", "$mtDataList"),
      Accumulators.sum("count", 1),
      Accumulators.max("pm25Max", "$pm25"),
      Accumulators.min("pm25Min", "$pm25"))
    val constantFilter = Aggregates.filter(Filters.and(Filters.gte("count", 8),
      Filters.expr(Document("$eq" -> Seq("$pm25Max", "$pm25Min")))
    ))
    val projectStage = Aggregates.project(fields(
      Projections.include("time", "id", "mtDataList", "count", "pm25Max", "pm25Min")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addPm25DataStage,
      addPm25ValueStage, latestFilter, constantFilter, projectStage))
      .allowDiskUse(true).toFuture()
  }

  def getLast30MinMonitorTypeConstantSensor(colName: String, mt:String): Future[Seq[MonitorRecord]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val targetMonitors = monitorOp.mvList
    val monitorFilter =
      Aggregates.filter(Filters.in("_id.monitor", targetMonitors: _*))

    val sortFilter = Aggregates.sort(orderBy(descending("_id.time"), descending("_id.monitor")))
    val timeFrameFilter = Aggregates.filter(Filters.and(Filters.gt("_id.time", DateTime.now.minusMinutes(30).toDate)))

    val latestFilter = Aggregates.group(id = "$_id.monitor", Accumulators.first("time", "$_id.time"),
      Accumulators.first("mtDataList", "$mtDataList"),
      Accumulators.sum("count", 1),
      Accumulators.max(s"${mt}Max", s"$$$mt"),
      Accumulators.min(s"${mt}Min", s"$$$mt"))
    val constantFilter = Aggregates.filter(Filters.and(Filters.gte("count", 8),
      Filters.expr(Document("$eq" -> Seq(s"${mt}Max", s"${mt}Min")))
    ))
    val addMtValueStage = Aggregates.addFields(Field(s"$mt", s"$$${mt}Data.value"))
    val projectStage = Aggregates.project(fields(
      Projections.include("time", "id")))
    val codecRegistry = fromRegistries(fromProviders(classOf[MonitorRecord], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)
    val col = mongoDB.database.getCollection[MonitorRecord](colName).withCodecRegistry(codecRegistry)
    col.aggregate(Seq(sortFilter, timeFrameFilter, monitorFilter, addMtDataStage(mt),
      addMtValueStage, latestFilter, constantFilter, projectStage))
      .allowDiskUse(true).toFuture()
  }

  def delete45dayAgoRecord(colName: String): Future[DeleteResult] = {
    val date = DateTime.now().withMillisOfDay(0).minusDays(45).toDate()
    val f = getCollection(colName).deleteMany(Filters.lt("time", date)).toFuture()
    f onFailure (errorHandler)
    f
  }

  def upsertManyRecord(docs: Seq[RecordList])(colName: String): Future[BulkWriteResult] = {
    val col = getCollection(colName)
    val writeModels = docs map {
      doc =>
        ReplaceOneModel(Filters.equal("_id", RecordListID(doc._id.time, doc._id.monitor)),
          doc, ReplaceOptions().upsert(true))
    }
    val f = col.bulkWrite(writeModels, BulkWriteOptions().ordered(false)).toFuture()
    f onFailure errorHandler()
    f
  }

  def lowerH2SOver150(colName: String) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    val col = getCollection(colName)
    val f = col.updateMany(Filters.equal("mtDataList.mtName", "H2S"),
      set("mtDataList.$.value", 149.9)).toFuture()
    f onFailure errorHandler()
    f
  }
}