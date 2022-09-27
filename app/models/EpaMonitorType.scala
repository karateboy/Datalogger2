package models

object EpaMonitorType {
  val itemIdMap = Map(
    MonitorType.TEMP -> "14",
    MonitorType.CH4 -> "31",
    MonitorType.CO -> "02",
    MonitorType.CO2 -> "36",
    MonitorType.NMHC -> "09",
    MonitorType.NO -> "06",
    MonitorType.NO2 -> "07",
    MonitorType.NOX -> "05",
    MonitorType.O3 -> "03",
    MonitorType.PH_RAIN -> "21",
    MonitorType.PM10 -> "04",
    MonitorType.PM25 -> "33",
    MonitorType.RAIN -> "23",
    MonitorType.SO2 -> "01",
    MonitorType.THC -> "08",
    MonitorType.WIN_DIRECTION -> "11",
    MonitorType.WIN_SPEED -> "10")

  val epaToMonitorTypeMap = itemIdMap.map(pair => pair._2 -> pair._1)
}