package models

import com.google.inject.ImplementedBy
import models.MonitorStatus.{BelowNormalStat, CalibrationDeviation, CalibrationResume, ExceedRangeStat, InvalidDataStat, MaintainStat, NormalStat, OverNormalStat, SpanCalibrationStat, ZeroCalibrationStat, getTagInfo}
import play.api.libs.json.Json

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

  var _map: Map[String, MonitorStatus] = refreshMap

  protected def refreshMap(): Map[String, MonitorStatus] = {
    _map = Map(msList.map { s => s.info.toString() -> s }: _*)
    _map
  }

  def msList: Seq[MonitorStatus]

  def map(key: String): MonitorStatus = {
    _map.getOrElse(key, {
      val tagInfo = getTagInfo(key)
      tagInfo.statusType match {
        case StatusType.Auto =>
          val ruleId = tagInfo.auditRule.get.toLower
          MonitorStatus(key, s"自動註記:${ruleId}")
        case StatusType.ManualInvalid =>
          MonitorStatus(key, StatusType.map(StatusType.ManualInvalid))
        case StatusType.ManualValid =>
          MonitorStatus(key, StatusType.map(StatusType.ManualValid))
        case StatusType.Internal =>
          MonitorStatus(key, "未知:" + key)
      }
    })
  }

}
