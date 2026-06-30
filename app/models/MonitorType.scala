package models

import models.DataCollectManager.MonitorTypeData

import java.time.ZoneId
import java.util.Date
import scala.collection.JavaConverters._

case class MonitorTypeMore(rangeMin: Option[Double] = None,
                           rangeMax: Option[Double] = None)

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
                       overLawSignalType: Option[String] = None,
                       more: Option[MonitorTypeMore] = Some(MonitorTypeMore())) {

  def addMeasuring(instrumentId: String, append: Boolean): Unit = {
    if (measuringBy.isEmpty)
      measuringBy = Some(List(instrumentId))
    else {
      val current = measuringBy.get
      if (!current.contains(instrumentId)) {
        if (append)
          measuringBy = Some(current :+ instrumentId)
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
  val LFN = "LFN"

  var rangeOrder = 0
  val SIGNAL = "SIGNAL"
  var signalOrder = 1000

  //Calculated types
  val DIRECTION = "DIRECTION"
  val LDN = "LDN"
  val WS_SPEED = "WS_SPEED"
  val WS10 = "WS10"
  val WD10 = "WD10"
  val PM10D = "PM10D"
  val PM25D = "PM25D"


  val DailyAvgMonitorTypeMap: Map[String, String] = Map(
    PM10 -> PM10D,
    PM25 -> PM25D
  )
  val DailyAvgInputMonitorTypes: Seq[String] = DailyAvgMonitorTypeMap.keys.toSeq
  val DailyAvgTargetMonitorTypes: Seq[String] = DailyAvgMonitorTypeMap.values.toSeq

  private case class CalculatedMonitorType(requiredMonitorTypes: Seq[String],
                                           targetMonitorType: String,
                                           generator: (Seq[MonitorTypeData], Date) => Option[MonitorTypeData])

  /*
  * GeneratingFunction(required MonitorTypes, generated MonitorType, rawData)
  * */
  val lfnRequiredMonitorTypoes = Seq("S1_5", "S1_6", "S1_7", "S1_8", "S1_9", "S1_10", "S1_11", "S1_12", "S1_13", "S1_14", "S1_15")
  private val calculatedMonitorTypeList: List[CalculatedMonitorType] =
    List(
      CalculatedMonitorType(Seq(LEQA), LDN, (mtDataList, now) =>
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
      CalculatedMonitorType(lfnRequiredMonitorTypoes, LFN,
        (mtDataList, now) => {
          val aWeightings = Seq(-50.5, -44.7, -39.4, -34.6, -30.2, -26.2, -22.5, -19.1, -16.1, -13.4, -10.9)
          val mtMap = mtDataList.map(dl=>dl.mt->dl).toMap
          val values = lfnRequiredMonitorTypoes.map(mtMap(_).value)
          val statuses = lfnRequiredMonitorTypoes.map(mtMap(_).status)
          val aWeightingValues = values.zipWithIndex.map(pair=>pair._1 + aWeightings(pair._2))
          val lfn = 10 * Math.log10(aWeightingValues.map(v => Math.pow(10, v / 10)).sum)

          Some(MonitorTypeData(LFN, lfn, statuses.head))
        }
      ),
      CalculatedMonitorType(Seq(WIN_SPEED), WS_SPEED, (mtDataList, _) =>
        for (target <- mtDataList.find(_.mt == WIN_SPEED)) yield
          target.copy(mt = WS_SPEED)
      ),
      CalculatedMonitorType(Seq(WIN_SPEED), WS10, (mtDataList, _) =>
        for (target <- mtDataList.find(_.mt == WIN_SPEED)) yield
          target.copy(mt = WS10)
      ),
      CalculatedMonitorType(Seq(WIN_DIRECTION), WD10, (mtDataList, _) =>
        for (target <- mtDataList.find(_.mt == WIN_DIRECTION)) yield
          target.copy(mt = WD10)
      ),
    )

  val calculatedMonitorTypes: Seq[String] = calculatedMonitorTypeList.map(_.targetMonitorType)

  def getCalculatedMonitorTypeData(mtDateList: Seq[MonitorTypeData], now: Date): Seq[MonitorTypeData] =
    calculatedMonitorTypeList.flatMap { mt =>
      if (mt.requiredMonitorTypes.forall(mtName => mtDateList.exists(_.mt == mtName)))
        mt.generator(mtDateList, now)
      else
        None
    }

  def getCalculatedMtRecord(mtRecords: Seq[MtRecord], now: Date): List[MtRecord] = {
    val mtData = mtRecords flatMap { mtRecord =>
      for (value <- mtRecord.value) yield
        MonitorTypeData(mtRecord.mtName, value, mtRecord.status)
    }

    val mtList = mtData.map(_.mt)
    val qualifiedMtList = calculatedMonitorTypeList.filter(cmt=>cmt.requiredMonitorTypes.forall(mtList.contains))
    val calculatedMtd = qualifiedMtList.flatMap { mt => mt.generator(mtData, now) }
    calculatedMtd map { mtd => MtRecord(mtd.mt, Some(mtd.value), mtd.status) }
  }

  def populateCalculatedTypes(mtList: Seq[String]): Seq[String] = {
    val calculatedMtvList: Seq[String] = calculatedMonitorTypeList.flatMap(cmt =>
      if (cmt.requiredMonitorTypes.forall(mtList.contains))
        Seq(cmt.targetMonitorType)
      else
        Seq.empty[String]
    )
    mtList ++ calculatedMtvList
  }


  def IsCalculated(mt: String): Boolean = calculatedMonitorTypeList.exists(_.targetMonitorType == mt)

  def getRawType(mt: String): String = mt + "_raw"

  def getRealType(rawMt: String): String = rawMt.reverse.drop(4).reverse

  def isRawValueType(mt: String): Boolean = mt.endsWith("_raw")

}

