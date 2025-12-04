package models

import models.MonitorStatus._
import play.api.libs.json.{Json, OWrites, Reads}

trait MonitorStatusDB {
  implicit val reads: Reads[MonitorStatus] = Json.reads[MonitorStatus]
  implicit val writes: OWrites[MonitorStatus] = Json.writes[MonitorStatus]

  val InternalPriorityMax = 100

  val defaultStatus = List(
    MonitorStatus(NormalStat, "正常", 11),
    MonitorStatus(OverNormalStat, "超於偵測極限", 9),
    MonitorStatus(BelowNormalStat, "低於偵測極限", 10),
    MonitorStatus(ZeroCalibrationStat, "零點校正", 5),
    MonitorStatus(SpanCalibrationStat, "全幅校正", 6),
    MonitorStatus(CalibrationDeviation, "氣象與粒狀物校正", InternalPriorityMax),
    MonitorStatus(Auditing,"查核", 2),
    MonitorStatus(CalibrationResume, "校正恢復", InternalPriorityMax),
    MonitorStatus(InvalidDataStat, "無效數據", 7),
    MonitorStatus(MaintainStat, "維修保養", 3),
    MonitorStatus(DataLost, "斷線", 8),
    MonitorStatus(NotActivated, "儀器未啟用", 1))

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
          MonitorStatus(key, s"自動註記:$ruleId", InternalPriorityMax)
        case StatusType.ManualInvalid =>
          MonitorStatus(key, StatusType.map(StatusType.ManualInvalid), InternalPriorityMax)
        case StatusType.ManualValid =>
          MonitorStatus(key, StatusType.map(StatusType.ManualValid), InternalPriorityMax)
        case StatusType.Internal =>
          MonitorStatus(key, "未知:" + key, InternalPriorityMax)
      }
    })
  }

}
