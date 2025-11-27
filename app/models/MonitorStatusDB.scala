package models

import models.MonitorStatus._
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
    MonitorStatus(CalibrationPoint3, "校正點3校正"),
    MonitorStatus(CalibrationPoint4, "校正點4校正"),
    MonitorStatus(CalibrationPoint5, "校正點5校正"),
    MonitorStatus(CalibrationPoint6, "校正點6校正"),
    MonitorStatus(InvalidDataStat, "無效數據"),
    MonitorStatus(MaintainStat, "維修、保養"),
    MonitorStatus(ExceedRangeStat, "超過量測範圍"))

  val _map: Map[String, MonitorStatus] = refreshMap()

  val nameStatusMap: Map[String, String] = _map.map(pair => pair._2.desp -> pair._1)

  private def refreshMap(): Map[String, MonitorStatus] = {
    Map(msList.map { s => s.info.toString() -> s }: _*)
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
