package controllers

object ReportUnit extends Enumeration {
  val Sec: Value = Value
  val Min: Value = Value
  val SixMin: Value = Value
  val TenMin: Value = Value
  val FifteenMin: Value = Value
  val Hour: Value = Value
  val Day: Value = Value
  val Month: Value = Value
  val Quarter: Value = Value
  val Year: Value = Value
  val map: Map[Value, String] =
    Map(
      Sec -> "秒",
      Min -> "分",
      SixMin -> "六分",
      TenMin -> "十分",
      FifteenMin -> "十五分",
      Hour -> "小時",
      Day -> "日",
      Month -> "月",
      Quarter -> "季",
      Year -> "年")
}