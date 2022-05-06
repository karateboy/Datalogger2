package models

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models.mongodb.{MongoDB, MonitorTypeOp}
import org.mongodb.scala._
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.result.{InsertManyResult, UpdateResult}
import play.api._
import play.api.libs.json.Json

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success

case class MtRecord(mtName: String, value: Option[Double], status: String)

object RecordList {
  def apply(time: Date, mtDataList: Seq[MtRecord], monitor: String): RecordList =
    RecordList(mtDataList, RecordListID(time, monitor))

  def apply(dt: DateTime, dataList: List[(String, (Double, String))], monitor: String = Monitor.SELF_ID): RecordList = {
    val mtDataList = dataList map { t => MtRecord(t._1, Some(t._2._1), t._2._2) }
    RecordList(mtDataList, RecordListID(dt, monitor))
  }

  implicit val mtRecordWrite = Json.writes[MtRecord]
  implicit val idWrite = Json.writes[RecordListID]
  implicit val idRead = Json.reads[RecordListID]
  implicit val recordListWrite = Json.writes[RecordList]
}

case class RecordList(var mtDataList: Seq[MtRecord], _id: RecordListID) {
  def mtMap: Map[String, MtRecord] = {
    val pairs =
      mtDataList map { data => data.mtName -> data }
    pairs.toMap
  }

  def doCalibrate(monitorTypeOp: MonitorTypeDB, calibrationMap: Map[String, List[(DateTime, Calibration)]]): Unit = {
    def getCalibrateItem(mt: String): Option[(time.Imports.DateTime, Calibration)] = {
      def findCalibration(calibrationList: List[(DateTime, Calibration)]): Option[(Imports.DateTime, Calibration)] = {
        val recordTime: DateTime = new DateTime(_id.time)
        val candidate = calibrationList.takeWhile(p => p._1 < recordTime)
        if (candidate.length == 0)
          None
        else
          Some(candidate.last)
      }

      if (calibrationMap.contains(mt))
        findCalibration(calibrationMap(mt))
      else
        None
    }

    var calibratedMtDataList = Seq.empty[MtRecord]
    mtDataList.foreach(rec => {
      if (monitorTypeOp.map(rec.mtName).calibrate.getOrElse(false)) {
        val calibratedValue =
          for (calibrationItem <- getCalibrateItem(rec.mtName)) yield
            calibrationItem._2.calibrate(rec.value)

        calibratedMtDataList = calibratedMtDataList.:+(MtRecord(rec.mtName, calibratedValue.getOrElse(rec.value), rec.status))
      } else
        calibratedMtDataList = calibratedMtDataList.:+(rec)
    })
    mtDataList = calibratedMtDataList
  }
}

case class RecordListID(time: Date, monitor: String)

case class Record(time: DateTime, value: Option[Double], status: String, monitor: String)

import javax.inject._

@Singleton
class RecordOp @Inject()(mongodb: MongoDB, monitorTypeOp: MonitorTypeOp, calibrationOp: CalibrationDB) {

  import org.mongodb.scala.model._
  import play.api.libs.json._

  implicit val writer = Json.writes[Record]

  val HourCollection = "hour_data"
  val MinCollection = "min_data"
  val SecCollection = "sec_data"

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  private val codecRegistry = fromRegistries(fromProviders(classOf[RecordList], classOf[MtRecord], classOf[RecordListID]), DEFAULT_CODEC_REGISTRY)

  def insertManyRecord(docs: Seq[RecordList])(colName: String): Future[InsertManyResult] = {
    val col = getCollection(colName)
    val f = col.insertMany(docs).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  init

  def replaceRecord(doc: RecordList)(colName: String): Future[UpdateResult] = {
    val col = getCollection(colName)

    val f = col.replaceOne(Filters.equal("_id", RecordListID(doc._id.time, doc._id.monitor)),
      doc,
      ReplaceOptions().upsert(true)).toFuture()

    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def upsertRecord(doc: RecordList)(colName: String): Future[UpdateResult] = {
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

  def updateRecordStatus(dt: Long, mt: String, status: String, monitor: String = Monitor.SELF_ID)(colName: String): Future[UpdateResult] = {
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
                  (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime): Map[String, Seq[Record]] = {
    val f = getRecordMapFuture(colName)(monitor, mtList, startTime, endTime)
    waitReadyResult(f)
  }

  def getRecordMapFuture(colName: String)
                        (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime): Future[Map[String, Seq[Record]]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val operation: FindObservable[RecordList] = col.find(
      and(equal("_id.monitor", monitor),
        gte("_id.time", startTime.toDate()),
        lt("_id.time", endTime.toDate())))
      .sort(ascending("time"))

    val f: Future[Seq[RecordList]] = if (mongodb.below44)
      operation.toFuture()
    else
      operation.allowDiskUse(true).toFuture()

    val needCalibration = mtList.map { mt => monitorTypeOp.map(mt).calibrate.getOrElse(false) }.exists(p => p)
    val allF =
      if (needCalibration) {
        val f2 = calibrationOp.getCalibrationMap(startTime, endTime)(monitorTypeOp)
        for {
          docs <- f
          calibrationMap <- f2
        } yield {
          docs.foreach(_.doCalibrate(monitorTypeOp, calibrationMap))
          docs
        }
      } else
        f

    for (docs <- allF) yield {
      val pairs =
        for {
          mt <- mtList
        } yield {
          val list =
            for {
              doc <- docs
              time = doc._id.time
              mtMap = doc.mtMap if mtMap.contains(mt) && mtMap(mt).value.isDefined
            } yield {
              Record(new DateTime(time.getTime), mtMap(mt).value, mtMap(mt).status, monitor)
            }

          mt -> list
        }
      Map(pairs: _*)
    }
  }

  def getRecordListFuture(colName: String)(startTime: DateTime, endTime: DateTime, monitors: Seq[String] = Seq(Monitor.SELF_ID)): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)

    val f = col.find(and(in("_id.monitor", monitors: _*), gte("_id.time", startTime.toDate()), lt("_id.time", endTime.toDate())))
      .sort(ascending("_id.time")).toFuture()
    f onFailure errorHandler
    val needCalibration = monitorTypeOp.mtvList.map { mt => monitorTypeOp.map(mt).calibrate.getOrElse(false) }.exists(p => p)
    if (needCalibration) {
      val f2 = calibrationOp.getCalibrationMap(startTime, endTime)(monitorTypeOp)
      for {
        docs <- f
        calibrationMap <- f2
      } yield {
        docs.foreach(_.doCalibrate(monitorTypeOp, calibrationMap))
        docs
      }
    } else
      f
  }

  def getRecordWithLimitFuture(colName: String)(startTime: DateTime, endTime: DateTime, limit: Int, monitor: String = Monitor.SELF_ID):
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

  def getLatestRecordFuture(colName: String)(monitor: String): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val f = col.find(equal("_id.monitor", monitor))
      .sort(descending("_id.time")).limit(1).toFuture()
    f onFailure errorHandler
    f
  }

  private def getCollection(colName: String): MongoCollection[RecordList] = mongodb.database.getCollection[RecordList](colName).withCodecRegistry(codecRegistry)

  def getWindRose(colName: String)(monitor: String, monitorType: String,
                                   start: DateTime, end: DateTime,
                                   level: List[Double], nDiv: Int = 16): Future[Map[Int, Array[Double]]] = {
    for (windRecords <- getRecordValueSeqFuture(colName)
    (Seq(MonitorType.WIN_DIRECTION, monitorType), start, end, monitor)) yield {

      val step = 360f / nDiv
      import scala.collection.mutable.ListBuffer
      val windDirPair =
        for (d <- 0 to nDiv - 1) yield
          d -> ListBuffer.empty[Double]
      val windMap: Map[Int, ListBuffer[Double]] = windDirPair.toMap
      var total = 0
      for (record <- windRecords if record.forall(r =>
        MonitorStatusFilter.isMatched(MonitorStatusFilter.ValidData, r.status) && r.value.isDefined)) {
        val dir = Math.ceil((record(0).value.get - (step / 2)) / step).toInt % nDiv
        windMap(dir) += record(1).value.get
        total += 1
      }

      def winSpeedPercent(winSpeedList: ListBuffer[Double]) = {
        val count = new Array[Double](level.length + 1)

        def getIdx(v: Double): Int = {
          for (i <- 0 to level.length - 1) {
            if (v < level(i))
              return i
          }
          level.length
        }

        for (w <- winSpeedList) {
          val i = getIdx(w)
          count(i) += 1
        }
        //assert(total != 0)
        if (total != 0)
          count.map(_ * 100 / total)
        else
          count
      }

      windMap.map(kv => (kv._1, winSpeedPercent(kv._2)))
    }
  }

  def getRecordValueSeqFuture(colName: String)
                             (mtList: Seq[String],
                              startTime: DateTime,
                              endTime: DateTime,
                              monitor: String): Future[Seq[Seq[MtRecord]]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val col = getCollection(colName)
    val f = col.find(and(equal("_id.monitor", monitor), gte("_id.time", startTime.toDate()), lt("_id.time", endTime.toDate())))
      .sort(ascending("_id.time")).toFuture()
    f onFailure (errorHandler)
    val needCalibration = mtList.map { mt => monitorTypeOp.map(mt).calibrate.getOrElse(false) }.exists(p => p)
    if (needCalibration) {
      val f2 = calibrationOp.getCalibrationMap(startTime, endTime)(monitorTypeOp)
      for {
        calibrationMap <- f2
        docs <- f} yield {
        for {
          doc <- docs if mtList.forall(mt => {
            doc.mtMap.contains(mt) && MonitorStatusFilter.isMatched(MonitorStatusFilter.ValidData, doc.mtMap(mt).status)
          })
        } yield {
          doc.doCalibrate(monitorTypeOp, calibrationMap)
          val mtMap = doc.mtMap
          mtList.map {
            mtMap
          }
        }
      }
    } else {
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
  }

  def upsertManyRecords(colName: String)(records: Seq[RecordList])(): Future[BulkWriteResult] = {
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
}