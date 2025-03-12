package models

/**
 * @author user
 */
object MonitorStatusFilter extends Enumeration {

  val ValidData: Value = Value("valid")
  private val Normal: Value = Value("normal")
  private val Calibration: Value = Value("calibration")
  private val Maintenance: Value = Value("maintenance")
  private val InvalidData: Value = Value("invalid")
  private val All: Value = Value("all")

  val map: Map[Value, String] = Map(
    All -> "全部",
    Normal -> "正常量測值",
    Calibration -> "校正",
    Maintenance -> "維修",
    InvalidData -> "無效數據",
    ValidData -> "有效數據")

  def isMatched(msf: MonitorStatusFilter.Value, stat: String): Boolean = {
    msf match {
      case MonitorStatusFilter.All =>
        true

      case MonitorStatusFilter.Normal =>
        stat == MonitorStatus.NormalStat

      case MonitorStatusFilter.Calibration=>
        MonitorStatus.isCalibration(stat)
        
      case MonitorStatusFilter.Maintenance =>
        MonitorStatus.isMaintenance(stat)

      case MonitorStatusFilter.InvalidData =>
        MonitorStatus.isError(stat)
        
      case MonitorStatusFilter.ValidData =>
        MonitorStatus.isValid(stat)
    }
  }
}