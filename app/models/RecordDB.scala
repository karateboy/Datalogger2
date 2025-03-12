package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.waitReadyResult
import org.mongodb.scala.BulkWriteResult
import org.mongodb.scala.result.{InsertManyResult, UpdateResult}
import play.api.libs.json.{Json, OWrites, Reads}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait RecordDB {

  implicit val mtRecordWrite: OWrites[MtRecord] = Json.writes[MtRecord]
  implicit val idWrite: OWrites[RecordListID] = Json.writes[RecordListID]
  implicit val recordListWrite: OWrites[RecordList] = Json.writes[RecordList]

  implicit val mtRecordRead: Reads[MtRecord] = Json.reads[MtRecord]
  implicit val idRead: Reads[RecordListID] = Json.reads[RecordListID]
  implicit val recordListRead: Reads[RecordList] = Json.reads[RecordList]

  val HourCollection = "hour_data"
  val MinCollection = "min_data"
  val SecCollection = "sec_data"

  def ensureMonitorType(mt:String): Unit

  def insertManyRecord(colName: String)(docs: Seq[RecordList]): Future[InsertManyResult]

  def upsertRecord(colName: String)(doc: RecordList): Future[UpdateResult]

  def updateRecordStatus(colName: String)(dt: Long, mt: String, status: String, monitor: String = Monitor.activeId): Future[UpdateResult]

  def getRecordMap(colName: String)
                  (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime, includeRaw:Boolean = false): Map[String, Seq[Record]] = {
    val f = getRecordMapFuture(colName)(monitor, mtList, startTime, endTime, includeRaw)
    waitReadyResult(f)
  }

  def getRecordMapFuture(colName: String)
                        (monitor: String, mtList: Seq[String], startTime: Imports.DateTime, endTime: Imports.DateTime, includeRaw:Boolean = false): Future[Map[String, Seq[Record]]]

  def getRecordListFuture(colName: String)(startTime: Imports.DateTime, endTime: Imports.DateTime, monitors: Seq[String] = Seq(Monitor.activeId)): Future[Seq[RecordList]]

  def getMtRecordMapFuture(colName: String)
                           (monitor: String, mtList: Seq[String], startTime: Imports.DateTime, endTime: Imports.DateTime): Future[mutable.Map[String, ListBuffer[MtRecord]]] = {
    for(recordLists <- getRecordListFuture(colName)(startTime, endTime, Seq(monitor))) yield {
      val map = mutable.Map.empty[String, ListBuffer[MtRecord]]
      for{recordList<-recordLists
          mtMap = recordList.mtMap
          mt<-mtList
          }{
        if(mtMap.contains(mt)){
          val lb = map.getOrElseUpdate(mt, ListBuffer.empty[MtRecord])
          lb.append(mtMap(mt))
        }
      }
      map
    }
  }

  def getRecordWithLimitFuture(colName: String)(startTime: Imports.DateTime, endTime: Imports.DateTime, limit: Int, monitor: String = Monitor.activeId):
  Future[Seq[RecordList]]

  def getWindRose(colName: String)(monitor: String, monitorType: String,
                                   start: DateTime, end: DateTime,
                                   level: List[Double], nDiv: Int = 16): Future[Map[Int, Array[Double]]] = {
    for (windRecords <- getRecordValueSeqFuture(colName)
    (Seq(MonitorType.WIN_DIRECTION, monitorType), start, end, monitor)) yield {

      val step = 360f / nDiv
      import scala.collection.mutable.ListBuffer
      val windDirPair =
        for (d <- 0 until nDiv) yield
          d -> ListBuffer.empty[Double]
      val windMap: Map[Int, ListBuffer[Double]] = windDirPair.toMap
      var total = 0
      for (record <- windRecords if record.forall(r =>
        MonitorStatusFilter.isMatched(MonitorStatusFilter.ValidData, r.status) && r.value.isDefined)) {
        val dir = Math.ceil((record.head.value.get - (step / 2)) / step).toInt % nDiv
        windMap(dir) += record(1).value.get
        total += 1
      }

      def winSpeedPercent(winSpeedList: ListBuffer[Double]): Array[Double] = {
        val count = new Array[Double](level.length + 1)

        def getIdx(v: Double): Int = {
          for (i <- level.indices) {
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
                              startTime: Imports.DateTime,
                              endTime: Imports.DateTime,
                              monitor: String): Future[Seq[Seq[MtRecord]]]

  def upsertManyRecords(colName: String)(records: Seq[RecordList])(): Future[BulkWriteResult]

  def getRecordMapFromRecordList(mtList:Seq[String], records: Seq[RecordList], includeRaw:Boolean): Map[String, Seq[Record]] = {
    val resultMap = mutable.Map.empty[String, Seq[Record]]
    for (mt <- mtList) {
      val recordSeq = records flatMap {
        doc => {
          val mtMap = doc.mtMap
          if (mtMap.contains(mt) && mtMap(mt).value.isDefined)
            Some(Record(new DateTime(doc._id.time.getTime), mtMap(mt).value, mtMap(mt).status, doc._id.monitor))
          else
            None
        }
      }
      resultMap.update(mt, recordSeq)

      if (includeRaw) {
        val recordSeq = records flatMap {
          doc => {
            val mtMap = doc.mtMap
            if (mtMap.contains(mt) && mtMap(mt).rawValue.isDefined)
              Some(Record(new DateTime(doc._id.time.getTime), mtMap(mt).rawValue, mtMap(mt).status, doc._id.monitor))
            else
              None
          }
        }
        resultMap.update(MonitorType.getRawType(mt), recordSeq)
      }
    }
    resultMap.toMap
  }

  def moveRecordToYearTable(colName:String)(year:Int): Future[Boolean]


  def getHourCollectionList: Future[Seq[String]]

  def getMinCollectionList: Future[Seq[String]]
}
