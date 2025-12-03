package models

import models.MonitorStatus._
import play.api.libs.json.{Json, OWrites, Reads}

trait MonitorStatusDB {
  implicit val reads: Reads[MonitorStatus] = Json.reads[MonitorStatus]
  implicit val writes: OWrites[MonitorStatus] = Json.writes[MonitorStatus]
  private var prioritySeed = 0
  private def MonitorStatusFactory(_id:String, name:String) : MonitorStatus = {
    prioritySeed = prioritySeed + 1
    MonitorStatus(_id, name, prioritySeed)
  }

  val defaultStatus = List(
    MonitorStatusFactory(NormalStat, "正常"),
    MonitorStatusFactory(OverNormalStat, "超過預設高值"),
    MonitorStatusFactory(BelowNormalStat, "低於預設低值"),
    MonitorStatusFactory(ZeroCalibrationStat, "零點偏移校正"),
    MonitorStatusFactory(SpanCalibrationStat, "全幅偏移校正"),
    MonitorStatusFactory(CalibrationDeviation, "校正偏移"),
    MonitorStatusFactory(CalibrationResume, "校正恢復"),
    MonitorStatusFactory(CalibrationPoint3, "校正點3校正"),
    MonitorStatusFactory(CalibrationPoint4, "校正點4校正"),
    MonitorStatusFactory(CalibrationPoint5, "校正點5校正"),
    MonitorStatusFactory(CalibrationPoint6, "校正點6校正"),
    MonitorStatusFactory(InvalidDataStat, "無效數據"),
    MonitorStatusFactory(MaintainStat, "維修、保養"),
    MonitorStatusFactory(ExceedRangeStat, "超過量測範圍"))

  val _map: Map[String, MonitorStatus] = refreshMap()

  val nameStatusMap: Map[String, String] = _map.map(pair => pair._2.name -> pair._1)

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
