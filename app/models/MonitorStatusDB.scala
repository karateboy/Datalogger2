package models

import models.MonitorStatus._
import play.api.libs.json.{Json, OWrites, Reads}

trait MonitorStatusDB {
  implicit val reads: Reads[MonitorStatus] = Json.reads[MonitorStatus]
  implicit val writes: OWrites[MonitorStatus] = Json.writes[MonitorStatus]

  val InternalPriorityMax = 100

  val defaultStatus: List[MonitorStatus] = List(
    MonitorStatus(NormalStat, "正常", 12, "監測設施正常運轉期間之量測值。"),
    MonitorStatus(OverNormalStat, "高於偵測極限", 10, "監測設施正常運轉期間之量測值，高於儀器可量測範圍或全幅設定值。"),
    MonitorStatus(BelowNormalStat, "低於偵測極限", 11, "監測設施正常運轉期間之量測值，低於儀器可量測範圍或零點設定值。"),
    MonitorStatus(ZeroCalibrationStat, "零點校正", 6, "監測設施依品質保證計畫書進行例行零點偏移校正測試。"),
    MonitorStatus(SpanCalibrationStat, "全幅校正", 7, "監測設施依品質保證計畫書進行例行全幅偏移校正測試。"),
    MonitorStatus(CalibrationDeviation, "氣象與粒狀物校正(分鐘狀態)/同小時零點及全幅(小時狀態)", 5,
      "氣象或粒狀污染物自動監測設施，執行校正測試；監測設施於同一小時依品質保證計畫書進行例行零點及全幅偏移測試。"),
    MonitorStatus(AuditStat, "查核", 2, "配合各級主管機關稽查或查核作業，以致監測設施非正常操作。"),
    MonitorStatus(CalibrationResume, "校正恢復", InternalPriorityMax, "監測設施依品質保證計畫書完成校正測試後，儀器恢復至環境濃度期間之監測數據。"),
    MonitorStatus(InvalidDataStat, "無效數據", 8, "監測設施正常操作，但零點偏移或全幅偏移測試結果不符合規定。"),
    MonitorStatus(MaintenanceStat, "保養", 4, "監測設施進行預防性保養作業或執行設備維護期間之監測數據，其紀錄應保存五年備查。"),
    MonitorStatus(RepairStat, "維修", 3, "監測設施進行非例行修復性維修作業期間之監測數據，其紀錄應保存五年備查。"),
    MonitorStatus(DataLost, "斷線", 9,
      "監測設施正常運轉期間，發生監測設施無產生訊號、量測值為空值、無法採擷數據，如故障、DAHS 損壞等，或監測數據未記錄保存、監測數據已記錄但無法取得數據等情形。"),
    MonitorStatus(NotActivated, "儀器未啟用", 1, "監測設施暫停運轉期間以致無法取樣。"))

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
