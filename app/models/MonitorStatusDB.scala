package models

import com.google.inject.ImplementedBy
import models.MonitorStatus.{BelowNormalStat, CalibrationDeviation, CalibrationResume, ExceedRangeStat, InvalidDataStat, MaintainStat, NormalStat, OverNormalStat, SpanCalibrationStat, ZeroCalibrationStat}
import play.api.libs.json.Json

@ImplementedBy(classOf[mongodb.MonitorStatusOp])
trait MonitorStatusDB {

  implicit val reads = Json.reads[MonitorStatus]
  implicit val writes = Json.writes[MonitorStatus]
  val defaultStatus = List(
    MonitorStatus(NormalStat, "正常"),
    MonitorStatus(OverNormalStat, "超過預設高值"),
    MonitorStatus(BelowNormalStat, "低於預設低值"),
    MonitorStatus(ZeroCalibrationStat, "零點偏移校正"),
    MonitorStatus(SpanCalibrationStat, "全幅偏移校正"),
    MonitorStatus(CalibrationDeviation, "校正偏移"),
    MonitorStatus(CalibrationResume, "校正恢復"),
    MonitorStatus(InvalidDataStat, "無效數據"),
    MonitorStatus(MaintainStat, "維修、保養"),
    MonitorStatus(ExceedRangeStat, "超過量測範圍"))

  def map(key: String): MonitorStatus

  def getExplainStr(tag: String): String
}
