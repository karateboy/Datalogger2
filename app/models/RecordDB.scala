package models

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.waitReadyResult
import org.mongodb.scala.BulkWriteResult
import org.mongodb.scala.result.{InsertManyResult, UpdateResult}
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait RecordDB {

  implicit val writer = Json.writes[Record]
  val HourCollection = "hour_data"
  val MinCollection = "min_data"
  val SecCollection = "sec_data"

  def ensureMonitorType(mt:String)

  def insertManyRecord(docs: Seq[RecordList])(colName: String): Future[InsertManyResult]

  def replaceRecord(doc: RecordList)(colName: String): Future[UpdateResult]

  def upsertRecord(doc: RecordList)(colName: String): Future[UpdateResult]

  def updateRecordStatus(dt: Long, mt: String, status: String, monitor: String = Monitor.activeId)(colName: String): Future[UpdateResult]

  def getRecordMap(colName: String)
                  (monitor: String, mtList: Seq[String], startTime: DateTime, endTime: DateTime): Map[String, Seq[Record]] = {
    val f = getRecordMapFuture(colName)(monitor, mtList, startTime, endTime)
    waitReadyResult(f)
  }

  def getRecordMapFuture(colName: String)
                        (monitor: String, mtList: Seq[String], startTime: Imports.DateTime, endTime: Imports.DateTime): Future[Map[String, Seq[Record]]]

  def getRecordListFuture(colName: String)(startTime: Imports.DateTime, endTime: Imports.DateTime, monitors: Seq[String] = Seq(Monitor.activeId)): Future[Seq[RecordList]]

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
                              startTime: Imports.DateTime,
                              endTime: Imports.DateTime,
                              monitor: String): Future[Seq[Seq[MtRecord]]]

  def upsertManyRecords(colName: String)(records: Seq[RecordList])(): Future[BulkWriteResult]

  def getLatestMonitorRecordTimeAsync(colName: String)(monitor:String) : Future[Option[DateTime]]

  def getLatestMonitorRecordAsync(colName: String)(monitor:String) : Future[Option[RecordList]]
}
