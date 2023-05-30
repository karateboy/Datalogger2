package models.mongodb

import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.errorHandler
import models._
import org.mongodb.scala.result.{InsertManyResult, UpdateResult}
import org.mongodb.scala.{BulkWriteResult, FindObservable, MongoCollection}
import play.api.Logger

import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success

@Singleton
class RecordOp @Inject()(mongodb: MongoDB, monitorTypeOp: MonitorTypeOp, calibrationOp: CalibrationOp) extends RecordDB {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.model._

  private val codecRegistry = fromRegistries(fromProviders(classOf[RecordList], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)

  override def insertManyRecord(colName: String)(docs: Seq[RecordList]): Future[InsertManyResult] = {
    val col = getCollection(colName)
    val f = col.insertMany(docs).toFuture()
    f onFailure errorHandler(s"insertManyRecord $colName")
    f
  }

  init()

  override def upsertRecord(colName: String)(doc: RecordList): Future[UpdateResult] = {
    val col = getCollection(colName)

    val updates =
      Updates.addEachToSet("mtDataList", doc.mtDataList: _*)

    val f = col.updateOne(Filters.equal("_id", RecordListID(doc._id.time, doc._id.monitor)), updates,
      UpdateOptions().upsert(true)).toFuture()

    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  override def updateRecordStatus(colName: String)(dt: Long, mt: String, status: String, monitor: String = Monitor.activeId): Future[UpdateResult] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    val col = getCollection(colName)

    val f = col.updateOne(
      and(equal("_id", RecordListID(new Date(dt), monitor)),
        equal("mtDataList.mtName", mt)), set("mtDataList.$.status", status)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  override def getRecordMapFuture(colName: String)
                                 (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime, includeRaw:Boolean): Future[Map[String, Seq[Record]]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val operation: FindObservable[RecordList] = col.find(
      and(equal("_id.monitor", monitor),
        gte("_id.time", startTime.toDate),
        lt("_id.time", endTime.toDate)))
      .sort(ascending("time"))

    val f: Future[Seq[RecordList]] = if (mongodb.below44)
      operation.toFuture()
    else
      operation.allowDiskUse(true).toFuture()

    for (recordLists <- f) yield {
      getRecordMapFromRecordList(mtList, recordLists, includeRaw)
    }
  }

  override def getRecordListFuture(colName: String)(startTime: DateTime, endTime: DateTime, monitors: Seq[String] = Seq(Monitor.activeId)): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(in("_id.monitor", monitors: _*), gte("_id.time", startTime.toDate()), lt("_id.time", endTime.toDate())))
      .sort(ascending("_id.time")).toFuture()
    f onFailure errorHandler
    f
  }

  override def getRecordWithLimitFuture(colName: String)(startTime: DateTime, endTime: DateTime, limit: Int, monitor: String = Monitor.activeId):
  Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val f = col.find(and(equal("_id.monitor", monitor),
      gte("_id.time", startTime.toDate()), lt("_id.time", endTime.toDate())))
      .limit(limit).sort(ascending("_id.time")).toFuture()
    f onFailure errorHandler
    f
  }

  override def getRecordValueSeqFuture(colName: String)
                                      (mtList: Seq[String],
                                       startTime: DateTime,
                                       endTime: DateTime,
                                       monitor: String): Future[Seq[Seq[MtRecord]]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val f = col.find(and(equal("_id.monitor", monitor), gte("_id.time", startTime.toDate()), lt("_id.time", endTime.toDate())))
      .sort(ascending("_id.time")).toFuture()
    f onFailure errorHandler

    for (docs <- f) yield {
      for {
        doc <- docs
        mtMap = doc.mtMap if mtList.forall(doc.mtMap.contains(_))
      } yield
        mtList map {
          mtMap
        }
    }
  }

  private def getCollection(colName: String): MongoCollection[RecordList] = mongodb.database.getCollection[RecordList](colName).withCodecRegistry(codecRegistry)

  override def upsertManyRecords(colName: String)(records: Seq[RecordList])(): Future[BulkWriteResult] = {
    /*
    val pullUpdates: Seq[UpdateOneModel[Nothing]] =
      for (record <- records) yield {
        val mtDataPullUdates: Seq[Bson] = record.mtDataList.map(mtr => Updates.pullByFilter(Document("mtDataList" -> Document("mtName" -> mtr.mtName))))
        val updates = Updates.combine(mtDataPullUdates: _*)
        UpdateOneModel(Filters.equal("_id", RecordListID(record._id.time, record._id.monitor)), updates, UpdateOptions().upsert(true))
      }
    */
    val setUpdates = for (record <- records) yield {
      val updates =
        Updates.addEachToSet("mtDataList", record.mtDataList: _*)

      UpdateOneModel(Filters.equal("_id", RecordListID(record._id.time, record._id.monitor)), updates, UpdateOptions().upsert(true))
    }

    val collection = getCollection(colName)
    val f = collection.bulkWrite(setUpdates, BulkWriteOptions().ordered(true)).toFuture()
    f onFailure errorHandler
    f
  }

  private def init() {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(HourCollection)) {
        val f = mongodb.database.createCollection(HourCollection).toFuture()
        f.onFailure(errorHandler)
        f.andThen({
          case Success(_) =>
            getCollection(HourCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true))
        })
      }

      if (!colNames.contains(MinCollection)) {
        val f = mongodb.database.createCollection(MinCollection).toFuture()
        f.onFailure(errorHandler)
        f.andThen({
          case Success(_) =>
            getCollection(MinCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true))
        })
      }

      if (!colNames.contains(SecCollection)) {
        val f = mongodb.database.createCollection(SecCollection).toFuture()
        f.onFailure(errorHandler)
        f.andThen({
          case Success(_) =>
            getCollection(SecCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true))
        })
      }
    }
  }

  override def ensureMonitorType(mt: String): Unit = {}
}
