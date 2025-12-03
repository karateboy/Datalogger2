package models

import models.DataCollectManager.MonitorTypeData

import java.time.ZoneId
import java.util.Date

case class ThresholdConfig(elapseTime: Int)

case class MonitorType(_id: String,
                       desp: String,
                       unit: String,
                       prec: Int,
                       order: Int,
                       signalType: Boolean = false,
                       std_law: Option[Double] = None,
                       zd_law: Option[Double] = None,
                       span: Option[Double] = None,
                       span_dev_law: Option[Double] = None,
                       var measuringBy: Option[List[String]] = None,
                       acoustic: Option[Boolean] = None,
                       spectrum: Option[Boolean] = None,
                       levels: Option[Seq[Double]] = None,
                       calibrate: Option[Boolean] = None,
                       accumulated: Option[Boolean] = None,
                       fixedM: Option[Double] = None,
                       fixedB: Option[Double] = None,
                       overLawSignalType: Option[String] = None) {

  def addMeasuring(instrumentId: String, append: Boolean): Unit = {
    if (measuringBy.isEmpty)
      measuringBy = Some(List(instrumentId))
    else {
      val current = measuringBy.get
      if (!current.contains(instrumentId)) {
        if (append)
          measuringBy = Some(current :+ (instrumentId))
        else
          measuringBy = Some(current.+:(instrumentId))
      }
    }
  }

  def stopMeasuring(instrumentId: String): MonitorType = {
    val newMeasuringBy =
      if (measuringBy.isEmpty)
        None
      else
        Some(measuringBy.get.filter { id => id != instrumentId })

    this.copy(measuringBy = newMeasuringBy)
  }
}

//MeasuredBy => History...
//MeasuringBy => Current...

object MonitorType {
  val SO2 = "SO2"
  val NOX = "NOX"
  val NO2 = "NO2"
  val NO = "NO"
  val CO = "CO"
  val CO2 = "CO2"
  val CH4 = "CH4"
  val PM10 = "PM10"
  val PM25 = "PM25"
  val O3 = "O3"
  val THC = "THC"
  val NMHC = "NMHC"
  val LAT = "LAT"
  val LNG = "LNG"
  val ALTITUDE = "ALTITUDE"
  val SPEED = "SPEED"
  val WIN_SPEED = "WD_SPEED"
  val WIN_DIRECTION = "WD_DIR"
  val RAIN = "RAIN"
  val TEMP = "TEMP"
  val TS = "TS"
  val PRESS = "PRESS"
  val DOOR = "DOOR"
  val SMOKE = "SMOKE"
  val FLOW = "FLOW"
  val HUMID = "HUMID"
  val SOLAR = "SOLAR"
  val CH2O = "CH2O"
  val TVOC = "TVOC"
  val NOISE = "NOISE"
  val H2S = "H2S"
  val H2 = "H2"
  val NH3 = "NH3"
  val NOY_NO = "NOY-NO"
  val NOY = "NOY"
  val PH_RAIN = "PH_RAIN"
  val LEQA = "LEQA"
  val LEQZ = "LEQZ"

  var rangeOrder = 0
  var signalOrder = 1000

  //Calculated types
  val DIRECTION = "DIRECTION"
  val LDN = "LDN"
  val WS_SPEED = "WS_SPEED"
  val WS10 = "WS10"
  val WD10 = "WD10"


  /*
  * GeneratingFunction(required MonitorTypes, generated MonitorType, rawData)
  * */
  val calculatedMonitorTypeEntries: Seq[(Seq[String], String, (Seq[MonitorTypeData], Date) => Option[MonitorTypeData])] =
    Seq(
      (Seq(LEQA), LDN, (mtDataList, now) =>
        for (mtData <- mtDataList.find(_.mt == LEQA))
          yield {
            val localTime = now.toInstant
              .atZone(ZoneId.systemDefault())
              .toLocalTime;

            if (localTime.getHour < 7 || localTime.getHour >= 22)
              MonitorTypeData(LDN, mtData.value + 10, mtData.status)
            else
              MonitorTypeData(LDN, mtData.value, mtData.status)
          }
      ),
      (Seq(WIN_SPEED), WS_SPEED, (mtDataList, _) =>
        for (speedData <- mtDataList.find(_.mt == WIN_SPEED)) yield
          speedData.copy(mt = WS_SPEED)
      ),
      (Seq(WIN_SPEED), WS10, (mtDataList, _) =>
        for (speedData <- mtDataList.find(_.mt == WIN_SPEED)) yield
          speedData.copy(mt = WS10)
      ),
      (Seq(WIN_DIRECTION), WD10, (mtDataList, _) =>
        for (speedData <- mtDataList.find(_.mt == WIN_DIRECTION)) yield
          speedData.copy(mt = WD10)
      ),
    )

  def getCalculatedMonitorTypeData(mtDateList: Seq[MonitorTypeData], now: Date): Seq[MonitorTypeData] =
    calculatedMonitorTypeEntries.flatMap {
      case (mtList, _, func) =>
        if (mtList.forall(mtName => mtDateList.exists(_.mt == mtName)))
          func(mtDateList, now)
        else
          None
    }

  def getRawType(mt: String): String = mt + "_raw"

  def getRealType(rawMt: String): String = rawMt.reverse.drop(4).reverse

  def isRawValueType(mt: String): Boolean = mt.endsWith("_raw")

}

