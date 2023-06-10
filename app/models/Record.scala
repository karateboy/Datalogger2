package models

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.Calibration.CalibrationListMap
import play.api.libs.json.{Json, OWrites, Reads}

import java.util.Date
import scala.collection.mutable

case class MtRecord(mtName: String, value: Option[Double], status: String, rawValue: Option[Double] = None)

object RecordList {
  def factory(time: Date, mtDataList: Seq[MtRecord], monitor: String): RecordList =
    RecordList(mtDataList, RecordListID(time, monitor))

  implicit val mtRecordWrite: OWrites[MtRecord] = Json.writes[MtRecord]
  implicit val idWrite: OWrites[RecordListID] = Json.writes[RecordListID]
  implicit val idRead: Reads[RecordListID] = Json.reads[RecordListID]
  implicit val recordListWrite: OWrites[RecordList] = Json.writes[RecordList]
}

case class RecordList(var mtDataList: Seq[MtRecord], _id: RecordListID) {
  def mtMap: mutable.Map[String, MtRecord] = {
    val map = mutable.Map.empty[String, MtRecord]
    mtDataList.foreach( data=>map.update(data.mtName, data))
    map
  }
}

case class RecordListID(time: Date, monitor: String)

case class Record(time: DateTime, value: Option[Double], status: String, monitor: String)

