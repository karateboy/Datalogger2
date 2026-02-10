package models

import models.MonitorStatus._
import play.api.libs.json.{Json, OWrites, Reads}

trait MonitorStatusDB {
  implicit val reads: Reads[MonitorStatus] = Json.reads[MonitorStatus]
  implicit val writes: OWrites[MonitorStatus] = Json.writes[MonitorStatus]

  val InternalPriorityMax = 100

  val defaultStatus: List[MonitorStatus] = List(
    MonitorStatus(NormalStat, "Normal", 11),
    MonitorStatus(OverNormalStat, "Over Range", 9),
    MonitorStatus(BelowNormalStat, "Lower Range", 10),
    MonitorStatus(ZeroCalibrationStat, "Zero Calibration", 5),
    MonitorStatus(SpanCalibrationStat, "Span Calibration", 6),
    MonitorStatus(CalibrationDeviation, "Calibration", 4),
    MonitorStatus(Auditing,"Audit", 2),
    MonitorStatus(CalibrationResume, "Calibration Resume", InternalPriorityMax),
    MonitorStatus(InvalidDataStat, "Invalid", 7),
    MonitorStatus(MaintainStat, "Maintenance", 3),
    MonitorStatus(DataLost, "Data Loss", 8),
    MonitorStatus(NotActivated, "Deactivated", 1))

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
          MonitorStatus(key, s"Remark:$ruleId", InternalPriorityMax)
        case StatusType.ManualInvalid =>
          MonitorStatus(key, StatusType.map(StatusType.ManualInvalid), InternalPriorityMax)
        case StatusType.ManualValid =>
          MonitorStatus(key, StatusType.map(StatusType.ManualValid), InternalPriorityMax)
        case StatusType.Internal =>
          MonitorStatus(key, "Unknown:" + key, InternalPriorityMax)
      }
    })
  }

}
