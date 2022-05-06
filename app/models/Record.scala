package models

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api.libs.json.Json

import java.util.Date

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

