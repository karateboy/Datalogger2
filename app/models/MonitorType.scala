package models

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

  def addMeasuring(instrumentId: String, append: Boolean) = {
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

  def stopMeasuring(instrumentId: String) = {
    val newMeasuringBy =
      if (measuringBy.isEmpty)
        None
      else
        Some(measuringBy.get.filter { id => id != instrumentId })

    MonitorType(_id, desp, unit,
      prec, order, signalType, std_law, zd_law,
      span, span_dev_law,
      newMeasuringBy)
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
  var rangeOrder = 0
  var signalOrder = 1000

}

