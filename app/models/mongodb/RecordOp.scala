package models.mongodb

import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.{errorHandler, waitReadyResult}
import models._
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.result.{InsertManyResult, UpdateResult}
import org.mongodb.scala.{BulkWriteResult, FindObservable, MongoCollection}
import play.api.Logger

import java.util.Date
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success

@Singleton
class RecordOp @Inject()(mongodb: MongoDB) extends RecordDB {
  val logger: Logger = Logger(this.getClass)

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.model._

  private val codecRegistry = fromRegistries(fromProviders(classOf[RecordList], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)

  override def insertManyRecord(colName: String)(docs: Seq[RecordList]): Future[InsertManyResult] = {
    val col = getCollection(colName)
    val f = col.insertMany(docs).toFuture()
    f.failed.foreach(errorHandler(s"insertManyRecord $colName"))
    f
  }

  init()

  override def upsertRecord(colName: String)(doc: RecordList): Future[UpdateResult] = {
    val col = getCollection(colName)

    val updates =
      Updates.addEachToSet("mtDataList", doc.mtDataList: _*)

    val f = col.updateOne(Filters.equal("_id", RecordListID(doc._id.time, doc._id.monitor)), updates,
      UpdateOptions().upsert(true)).toFuture()

    f.failed.foreach(errorHandler)
    f
  }

  override def updateRecordStatus(colName: String)(dt: Long, mt: String, status: String, monitor: String = Monitor.activeId): Future[UpdateResult] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    val col = getCollection(colName)

    val f = col.updateOne(
      and(equal("_id", RecordListID(new Date(dt), monitor)),
        equal("mtDataList.mtName", mt)), set("mtDataList.$.status", status)).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  override def getRecordMapFuture(colName: String)
                                 (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime, includeRaw: Boolean): Future[Map[String, Seq[Record]]] = {
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

    val f = col.find(and(in("_id.monitor", monitors: _*), gte("_id.time", startTime.toDate), lt("_id.time", endTime.toDate)))
      .sort(ascending("_id.time")).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  override def getRecordWithLimitFuture(colName: String)(startTime: DateTime,
                                                         endTime: DateTime,
                                                         limit: Int,
                                                         monitor: String = Monitor.activeId,
                                                         asc: Boolean = true):
  Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val sort =
      if (asc)
        ascending("_id.time")
      else
        descending("_id.time")

    val f =
      col.find(and(equal("_id.monitor", monitor),
          gte("_id.time", startTime.toDate), lt("_id.time", endTime.toDate)))
        .limit(limit).sort(sort).toFuture()

    f.failed.foreach(errorHandler)
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
    val f = col.find(and(equal("_id.monitor", monitor), gte("_id.time", startTime.toDate), lt("_id.time", endTime.toDate)))
      .sort(ascending("_id.time")).toFuture()
    f.failed.foreach(errorHandler)

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

    val pullUpdates =
      for (record <- records) yield {
        val mtDataPullUpdates = record.mtDataList.map(mtr => Updates.pullByFilter(Document("mtDataList" -> Document("mtName" -> mtr.mtName))))
        val updates = Updates.combine(mtDataPullUpdates: _*)
        UpdateOneModel(Filters.equal("_id", RecordListID(record._id.time, record._id.monitor)), updates, UpdateOptions().upsert(true))
      }

    val setUpdates = for (record <- records) yield {
      val updates =
        Updates.addEachToSet("mtDataList", record.mtDataList: _*)

      UpdateOneModel(Filters.equal("_id", RecordListID(record._id.time, record._id.monitor)), updates, UpdateOptions().upsert(true))
    }

    val collection = getCollection(colName)
    val f = collection.bulkWrite(pullUpdates ++ setUpdates, BulkWriteOptions().ordered(true)).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  private def init(): Unit = {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(HourCollection)) {
        val f = mongodb.database.createCollection(HourCollection).toFuture()
        f.failed.foreach(errorHandler)
        f.andThen({
          case Success(_) =>
            getCollection(HourCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true))
        })
      }

      if (!colNames.contains(MinCollection)) {
        val f = mongodb.database.createCollection(MinCollection).toFuture()
        f.failed.foreach(errorHandler)
        f.andThen({
          case Success(_) =>
            getCollection(MinCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true))
        })
      }

      if (!colNames.contains(SecCollection)) {
        val f = mongodb.database.createCollection(SecCollection).toFuture()
        f.failed.foreach(errorHandler)
        f.andThen({
          case Success(_) =>
            getCollection(SecCollection).createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true))
        })
      }
    }
  }

  override def ensureMonitorType(mt: String): Unit = {}

  override def moveRecordToYearTable(colName: String)(year: Int): Future[Boolean] = {
    val col = getCollection(colName)
    val yearColName = s"${colName}_$year"
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) yield {
      if (!colNames.contains(yearColName)) {
        val f = mongodb.database.createCollection(yearColName).toFuture()
        waitReadyResult(f)
        val yearCollection = getCollection(yearColName)
        yearCollection.createIndex(Indexes.descending("_id.time", "_id.monitor"), new IndexOptions().unique(true))
      }

      val splitDate = new DateTime(year + 1, 1, 1, 0, 0)
      val f1 = col.aggregate(
        Seq(
          Aggregates.filter(Filters.lte("_id.time", splitDate.toDate)),
          Aggregates.out(yearColName))).toFuture()
      for (_ <- f1) yield {
        col.deleteMany(Filters.lte("_id.time", splitDate.toDate)).toFuture()
      }
      true
    }
  }

  override def getHourCollectionList: Future[Seq[String]] = {
    val f = mongodb.database.listCollectionNames().toFuture()
    for (colNames <- f) yield {
      colNames.filter(_.startsWith(HourCollection))
    }
  }

  override def getMinCollectionList: Future[Seq[String]] = {
    val f = mongodb.database.listCollectionNames().toFuture()
    for (colNames <- f) yield {
      colNames.filter(_.startsWith(MinCollection))
    }
  }
}
