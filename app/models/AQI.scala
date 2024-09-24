package models

import com.github.nscala_time.time.Imports._
import play.api.Logger

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

sealed trait AqiMonitorType

case class AqiSubExplain(mtName: String, explain: AqiExplain)

case class AqiExplain(aqi: String, value: String, css: String)

case class AqiExplainReport(value: AqiExplain, subExplain: Seq[AqiSubExplain])

case class AqiSubReport(value: Option[Double], aqi: Option[Double])

case class AqiReport(aqi: Option[Double], sub_map: Map[AqiMonitorType, AqiSubReport])

object AQI {
  private final case object O3_8hr extends AqiMonitorType

  final case object O3 extends AqiMonitorType

  private final case object pm25 extends AqiMonitorType

  final case object pm10 extends AqiMonitorType

  private final case object CO_8hr extends AqiMonitorType

  final case object SO2 extends AqiMonitorType

  private final case object SO2_24hr extends AqiMonitorType

  final case object NO2 extends AqiMonitorType

  private val aqiMonitorType: Seq[AqiMonitorType] = Seq(O3_8hr, O3, pm25, pm10, CO_8hr, SO2, SO2_24hr, NO2)
  val defaultMappingTypes: Seq[String] = Seq(MonitorType.O3, MonitorType.O3,
    MonitorType.PM25,
    MonitorType.PM10,
    MonitorType.CO,
    MonitorType.SO2,
    MonitorType.SO2,
    MonitorType.NO2)

  private def getAqiMonitorTypeName: Map[AqiMonitorType, String] = Map(
    O3_8hr -> "臭氧(ppm) 八小時平均值",
    O3 -> "臭氧(ppm) 小時平均值",
    pm25 -> "PM2.5(μg/m3) 平均值",
    pm10 -> "PM10(μg/m3) 平均值",
    CO_8hr -> "CO(ppm) 8小時平均值",
    SO2 -> "SO2(ppb) 小時平均值",
    SO2_24hr -> "SO2(ppb) 24小時平均值",
    NO2 -> "NO2(ppb) 小時平均值")

  val mtMap: mutable.Map[AqiMonitorType, String] = mutable.Map(
    O3_8hr -> MonitorType.O3,
    O3 -> MonitorType.O3,
    pm25 -> MonitorType.PM25,
    pm10 -> MonitorType.PM10,
    CO_8hr -> MonitorType.CO,
    SO2 -> MonitorType.SO2,
    SO2_24hr -> MonitorType.SO2,
    NO2 -> MonitorType.NO2)

  def updateAqiTypeMapping(monitorTypes: Seq[String]): Unit = {
    if (monitorTypes.length != aqiMonitorType.length) {
      Logger.error(s"AQI type mapping length not equal!")
    } else
      for ((k, v) <- aqiMonitorType.zip(monitorTypes)) {
        mtMap.update(k, v)
      }
  }

  def mtList: Set[String] = mtMap.values.toSet

  def getAqiExplain(report: AqiReport)(monitorTypeDB: MonitorTypeDB): AqiExplainReport = {
    val value = AqiExplain(report.aqi.map(_.toInt.toString).getOrElse("-"), "",
      report.aqi.map(getAqiLevel).getOrElse(""))
    val subExplain =
      report.sub_map.map(pair => {
        val (aqiMt, subReport) = pair
        if (aqiMt == O3_8hr || aqiMt == O3)
          AqiSubExplain(getAqiMonitorTypeName(aqiMt), AqiExplain(subReport.aqi.map(_.toInt.toString).getOrElse("-"),
            monitorTypeDB.format(mtMap(aqiMt), subReport.value.map(_ / 1000), Some(3)),
            subReport.aqi.map(getAqiLevel).getOrElse("")))
        else {
          val precision = if (aqiMt == pm10) Some(0)
          else if (aqiMt == pm25)
            Some(1)
          else
            None


          AqiSubExplain(getAqiMonitorTypeName(aqiMt), AqiExplain(subReport.aqi.map(_.toInt.toString).getOrElse("-"),
            monitorTypeDB.format(mtMap(aqiMt), subReport.value, precision),
            subReport.aqi.map(getAqiLevel).getOrElse("")))
        }
      })
    AqiExplainReport(value, subExplain.toSeq)
  }

  private def getMonitorTypeAvg(monitor: String, monitorType: String,
                                start: DateTime, end: DateTime, validMin: Int)(implicit recordDB: RecordDB): Future[Option[Double]] = {
    for (records <- recordDB.getRecordListFuture(recordDB.HourCollection)(start, end, Seq(monitor))) yield {
      val validRecords = records.flatMap(_.mtMap.get(monitorType))
        .filter(mtRecord => MonitorStatusFilter.isMatched(MonitorStatusFilter.ValidData, mtRecord.status))

      val validValues = validRecords.flatMap(_.value)
      if (validValues.size < validMin)
        None
      else {
        Some(validValues.sum / validValues.size)
      }
    }
  }

  def getAqiLevel(v: Double) = {
    if (v <= 50)
      "AQI1"
    else if (v <= 100)
      "AQI2"
    else if (v <= 150)
      "AQI3"
    else if (v <= 200)
      "AQI4"
    else if (v <= 300)
      "AQI5"
    else
      "AQI6"
  }

  def getRealtimeAQI(recordDB: RecordDB)(lastHour: DateTime): Unit = {
    /*
     val recordMap = recordDB.get
     val result =
       for {
         m <- Monitor.mvList
       } yield {
         m -> getMonitorRealtimeAQI(m, lastHour)
       }
     Map(result: _*)

     */
  }

  def getMonitorRealtimeAQI(monitor: String, thisHour: DateTime)(implicit recordDB: RecordDB): Future[AqiReport] = {
    for {
      o3 <- getMonitorTypeAvg(monitor, MonitorType.O3, thisHour, thisHour + 1.hour, 1)
      o3_8 <- getMonitorTypeAvg(monitor, MonitorType.O3, thisHour - 7.hour, thisHour + 1.hour, 6)
      pm10_12 <- getMonitorTypeAvg(monitor, MonitorType.PM10, thisHour - 11.hour, thisHour + 1.hour, 6)
      pm10_4 <- getMonitorTypeAvg(monitor, MonitorType.PM10, thisHour - 3.hour, thisHour + 1.hour, 1)
      pm25_12 <- getMonitorTypeAvg(monitor, MonitorType.PM25, thisHour - 11.hour, thisHour + 1.hour, 6)
      pm25_4 <- getMonitorTypeAvg(monitor, MonitorType.PM25, thisHour - 3.hour, thisHour + 1.hour, 1)
      pm10 = for (v1 <- pm10_12; v2 <- pm10_4) yield (v1 + v2) / 2
      pm25 = for (v1 <- pm25_12; v2 <- pm25_4) yield (v1 + v2) / 2
      co_8 <- getMonitorTypeAvg(monitor, MonitorType.CO, thisHour - 7.hour, thisHour + 1.hour, 6)
      so2 <- getMonitorTypeAvg(monitor, MonitorType.SO2, thisHour, thisHour + 1.hour, 1)
      so2_24 <- getMonitorTypeAvg(monitor, MonitorType.SO2, thisHour - 23.hour, thisHour + 1.hour, 1)
      no2 <- getMonitorTypeAvg(monitor, MonitorType.NO2, thisHour, thisHour + 1.hour, 1)
    } yield {
      val result = Map[AqiMonitorType, AqiSubReport](
        AQI.O3_8hr -> AqiSubReport(o3_8, o3_8AQI(o3_8)),
        AQI.O3 -> AqiSubReport(o3, o3AQI(o3)),
        AQI.pm25 -> AqiSubReport(pm25, pm25AQI(pm25)),
        AQI.pm10 -> AqiSubReport(pm10, pm10AQI(pm10)),
        AQI.CO_8hr -> AqiSubReport(co_8, co_8AQI(co_8)),
        AQI.SO2 -> AqiSubReport(so2, so2AQI(so2)),
        AQI.SO2_24hr -> AqiSubReport(so2_24, so2_24AQI(so2_24)),
        AQI.NO2 -> AqiSubReport(no2, no2AQI(no2)))
      val sub_aqi = result.values.map(_.aqi)
      val aqi = sub_aqi.max

      AqiReport(aqi, result)
    }
  }

  private def so2_24AQI(ov: Option[Double]): Option[Double] = {
    if (ov.isEmpty || ov.get < 186)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(0, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 304f) {
            (v - 186f) * 49 / (304f - 186f) + 151
          } else if (v <= 604f) {
            (v - 305f) * 99 / (604f - 305f) + 201
          } else if (v <= 804f) {
            (v - 605f) * 99 / (804f - 605f) + 301
          } else {
            (v - 805f) * 99 / (1004f - 805f) + 401
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def o3_8AQI(ov: Option[Double]) = {
    if (ov.isEmpty || ov.get > 200)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(0, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 54) {
            v * 50 / 54
          } else if (v <= 70) {
            (v - 55) * 49 / (70 - 55) + 51
          } else if (v <= 85) {
            (v - 71) * 49 / (85 - 71) + 101
          } else if (v <= 105) {
            (v - 86) * 49 / (105 - 86) + 151
          } else {
            (v - 106) * 49 / (200 - 106) + 201
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def o3AQI(ov: Option[Double]) = {
    if (ov.isEmpty || ov.get < 125d)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(0, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 164) {
            (v - 125) * 49 / (164 - 125) + 101
          } else if (v <= 204) {
            (v - 165) * 49 / (204 - 165) + 151
          } else if (v <= 404) {
            (v - 205) * 99 / (404 - 205) + 201
          } else if (v <= 504f) {
            (v - 405f) * 99 / (504 - 405) + 301
          } else {
            (v - 505f) * 99 / (604 - 505) + 401
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def pm25AQI(ov: Option[Double]) = {
    if (ov.isEmpty)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(1, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 15.4f) {
            v / 15.4f * 50
          } else if (v <= 35.4f) {
            (v - 15.5f) * 49 / (35.4f - 15.5f) + 51
          } else if (v <= 54.4f) {
            (v - 35.5f) * 49 / (54.4f - 35.5f) + 101
          } else if (v <= 150.4f) {
            (v - 54.5f) * 49 / (150.4f - 54.5f) + 151
          } else if (v <= 250.4f) {
            (v - 150.5f) * 99 / (250.4f - 150.5f) + 201
          } else if (v <= 350.4f) {
            (v - 250.5f) * 99 / (350.4f - 250.5f) + 301
          } else {
            (v - 350.5f) / (500.4f - 350.5f) * 100 + 401
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def pm10AQI(ov: Option[Double]) = {
    if (ov.isEmpty)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(0, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 50f) {
            v / 50f * 50
          } else if (v <= 100f) {
            (v - 51f) * 49 / (100f - 51f) + 51
          } else if (v <= 254f) {
            (v - 126f) * 49 / (254f - 101f) + 101
          } else if (v <= 354f) {
            (v - 255f) * 49 / (354f - 255f) + 151
          } else if (v <= 424f) {
            (v - 355f) * 99 / (424f - 355f) + 201
          } else if (v <= 504f) {
            (v - 425f) * 99 / (504f - 425f) + 301
          } else {
            (v - 505f) * 99 / (604f - 505f) + 401
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def co_8AQI(ov: Option[Double]) = {
    if (ov.isEmpty)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(1, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 4.4f) {
            v / 4.4f * 50
          } else if (v <= 9.4f) {
            (v - 4.5f) * 49 / (9.4f - 4.5f) + 51
          } else if (v <= 12.4f) {
            (v - 9.5f) * 49 / (12.4f - 9.5f) + 101
          } else if (v <= 15.4f) {
            (v - 12.5f) * 49 / (15.4f - 12.5f) + 151
          } else if (v <= 30.4f) {
            (v - 15.5f) * 99 / (30.4f - 15.5f) + 201
          } else if (v <= 40.4f) {
            (v - 30.5f) * 99 / (40.4f - 30.4f) + 301
          } else {
            (v - 40.5f) * 99 / (604f - 50.4f) + 401
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def so2AQI(ov: Option[Double]) = {
    if (ov.isEmpty || ov.get >= 186)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(0, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 20) {
            v * 50 / 20
          } else if (v <= 75f) {
            (v - 21) * 49 / (75f - 21f) + 51
          } else {
            (v - 76f) * 49 / (185f - 76f) + 101
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def no2AQI(ov: Option[Double]) = {
    if (ov.isEmpty)
      None
    else
      Some {
        val bd = BigDecimal(ov.get.toString)
        val v = bd.setScale(0, BigDecimal.RoundingMode.HALF_UP)
        val result =
          if (v <= 30f) {
            v * 50 / 30f
          } else if (v <= 100f) {
            (v - 31f) * 49 / (100f - 31f) + 51
          } else if (v <= 360f) {
            (v - 101f) * 49 / (360f - 101f) + 101
          } else if (v <= 649f) {
            (v - 361f) * 49 / (649f - 361f) + 151
          } else if (v <= 1249f) {
            (v - 650f) * 99 / (1249f - 650f) + 201
          } else if (v <= 1649f) {
            (v - 1250f) * 99 / (1649f - 1250f) + 301
          } else {
            (v - 1650f) * 99 / (2049f - 1650f) + 401
          }
        result.setScale(0, BigDecimal.RoundingMode.HALF_UP).doubleValue()
      }
  }

  def getRealtimeAqiTrend(m: String, start: DateTime, end: DateTime)(implicit recordDB: RecordDB): Future[Map[DateTime, AqiReport]] = {

    for (recordLists <- recordDB.getRecordListFuture(recordDB.HourCollection)(start.minusDays(1), end, Seq(m))) yield {
      val recordMap = mutable.Map.empty[String, ListBuffer[Option[MtRecord]]]
      for {recordlist <- recordLists
           mtMap = recordlist.mtMap
           mt <- mtList
           } {

        val lb = recordMap.getOrElseUpdate(mt, ListBuffer.empty[Option[MtRecord]])
        lb.append(mtMap.get(mt))
      }
      val duration = new Duration(start, end)
      val pairs =
        for {
          hr <- 24 to (24 + duration.getStandardDays.toInt * 24)
        } yield {
          start + (hr - 24).hour -> getMonitorRealtimeAQIfromMap(hr, recordMap)
        }
      pairs.toMap
    }
  }

  def getMonitorRealtimeAQIfromMap(thisHour: Int,
                                   map: mutable.Map[String, ListBuffer[Option[MtRecord]]]): AqiReport = {
    def getValidValues(mt: String, start: Int, end: Int): ListBuffer[Double] = {
      val mtRecords = map(mt).slice(start, end)
      mtRecords.flatten.filter(mtRecord =>
        MonitorStatusFilter.isMatched(MonitorStatusFilter.ValidData, mtRecord.status)).flatMap(_.value)
    }

    def getMonitorTypeAvg(mt: String,
                          start: Int, end: Int, validMin: Int): Option[Double] = {
      val validValues = getValidValues(mt, start, end)
      if (validValues.length < validMin)
        None
      else {
        val sum = validValues.sum
        Some(sum / validValues.length)
      }
    }

    val o3 = getMonitorTypeAvg(MonitorType.O3, thisHour, thisHour + 1, 1)
    val o3_8 = getMonitorTypeAvg(MonitorType.O3, thisHour - 7, thisHour + 1, 6)
    val pm10_12 = getMonitorTypeAvg(MonitorType.PM10, thisHour - 11, thisHour + 1, 6)
    val pm10_4 = getMonitorTypeAvg(MonitorType.PM10, thisHour - 3, thisHour + 1, 1)
    val pm10 = for (v1 <- pm10_12; v2 <- pm10_4) yield (v1 + v2) / 2

    val pm25_12 = getMonitorTypeAvg(MonitorType.PM25, thisHour - 11, thisHour + 1, 6)
    val pm25_4 = getMonitorTypeAvg(MonitorType.PM25, thisHour - 3, thisHour + 1, 1)
    val pm25 = for (v1 <- pm25_12; v2 <- pm25_4) yield (v1 + v2) / 2

    val co_8 = getMonitorTypeAvg(MonitorType.CO, thisHour - 7, thisHour + 1, 6)
    val so2 = getMonitorTypeAvg(MonitorType.SO2, thisHour, thisHour + 1, 1)
    val so2_24 = getMonitorTypeAvg(MonitorType.SO2, thisHour - 23, thisHour + 1, 1)
    val no2 = getMonitorTypeAvg(MonitorType.NO2, thisHour, thisHour + 1, 1)

    val result = Map[AqiMonitorType, AqiSubReport](
      AQI.O3_8hr -> AqiSubReport(o3_8, o3_8AQI(o3_8)),
      AQI.O3 -> AqiSubReport(o3, o3AQI(o3)),
      AQI.pm25 -> AqiSubReport(pm25, pm25AQI(pm25)),
      AQI.pm10 -> AqiSubReport(pm10, pm10AQI(pm10)),
      AQI.CO_8hr -> AqiSubReport(co_8, co_8AQI(co_8)),
      AQI.SO2 -> AqiSubReport(so2, so2AQI(so2)),
      AQI.SO2_24hr -> AqiSubReport(so2_24, so2_24AQI(so2_24)),
      AQI.NO2 -> AqiSubReport(no2, no2AQI(no2)))

    val aqi = result.values.map(_.aqi).max

    AqiReport(aqi, result)
  }

  def getMonitorDailyAQI(monitor: String, thisDay: DateTime, myTableType: TableType#Value)(implicit recordDB: RecordDB, tableType: TableType): Future[AqiReport] = {
    for (recordLists <- recordDB.getRecordListFuture(tableType.mapCollection(myTableType))(thisDay, thisDay.plusDays(1), Seq(monitor))) yield {
      val dayMap = mutable.Map.empty[String, ListBuffer[Option[MtRecord]]]
      for {recordList <- recordLists
           mtMap = recordList.mtMap
           mt <- mtList} {

        val lb = dayMap.getOrElseUpdate(mt, ListBuffer.empty[Option[MtRecord]])
        lb.append(mtMap.get(mt))
      }

      getMonitorDailyAQIfromMap(0, dayMap)
    }
  }

  private def getMonitorDailyAQIfromMap(dayStartHour: Int,
                                        map: mutable.Map[String, ListBuffer[Option[MtRecord]]]): AqiReport = {

    def getValidValues(mt: String, start: Int, end: Int): List[Double] = {
      val mtRecords: Iterable[Option[MtRecord]] = map.get(mt).map(lb => lb.slice(start, end)).getOrElse(ListBuffer.empty[Option[MtRecord]])
      mtRecords.flatten.filter(mtRecord =>
        MonitorStatusFilter.isMatched(MonitorStatusFilter.ValidData, mtRecord.status)).flatMap(_.value).toList
    }

    def getMonitorTypeAvg(mt: String,
                          start: Int, end: Int, validMin: Int): Option[Double] = {
      val validValues = getValidValues(mt, start, end)
      if (validValues.length < validMin)
        None
      else {
        val sum = validValues.sum
        Some(sum / validValues.length)
      }
    }

    def getMonitorTypeMax(mt: String,
                          start: Int, end: Int): Option[Double] = {
      val validValues = getValidValues(mt, start, end)

      if (validValues.isEmpty)
        None
      else
        Some(validValues.max)
    }

    def getMonitorType8HourAvgMax(mt: String, start: Int, end: Int): Option[Double] = {
      def get8hrAvg(validValues: List[Double]): Option[Double] =
        if (validValues.length < 6)
          None
        else
          Some(validValues.sum / validValues.length)

      val movingAvg =
        for {
          index <- start to end - 8
          validValues = getValidValues(mt, index, index + 8)
        } yield get8hrAvg(validValues)

      if (movingAvg.nonEmpty)
        movingAvg.max
      else
        None
    }

    val pm25 = getMonitorTypeAvg(MonitorType.PM25, dayStartHour, dayStartHour + 24, 16)
    val pm10 = getMonitorTypeAvg(MonitorType.PM10, dayStartHour, dayStartHour + 24, 16)
    val so2 = getMonitorTypeMax(MonitorType.SO2, dayStartHour, dayStartHour + 24)
    val no2 = getMonitorTypeMax(MonitorType.NO2, dayStartHour, dayStartHour + 24)

    val o3 = getMonitorTypeMax(MonitorType.O3, dayStartHour, dayStartHour + 24)
    val o3_8 = getMonitorType8HourAvgMax(MonitorType.O3, dayStartHour, dayStartHour + 24)
    val co_8 = getMonitorType8HourAvgMax(MonitorType.CO, dayStartHour, dayStartHour + 24)

    val result = Map[AqiMonitorType, AqiSubReport](
      AQI.O3_8hr -> AqiSubReport(o3_8, o3_8AQI(o3_8)),
      AQI.O3 -> AqiSubReport(o3, o3AQI(o3)),
      AQI.pm25 -> AqiSubReport(pm25, pm25AQI(pm25)),
      AQI.pm10 -> AqiSubReport(pm10, pm10AQI(pm10)),
      AQI.CO_8hr -> AqiSubReport(co_8, co_8AQI(co_8)),
      AQI.SO2 -> AqiSubReport(so2, so2AQI(so2)),
      AQI.NO2 -> AqiSubReport(no2, no2AQI(no2)))
    val aqi = result.values.map(_.aqi).max

    AqiReport(aqi, result)
  }

}
