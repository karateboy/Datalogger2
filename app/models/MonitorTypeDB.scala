package models

import models.MonitorType._
import org.mongodb.scala.result.UpdateResult
import play.api.Logger
import play.api.libs.json.{Json, OWrites, Reads}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.math.BigDecimal.RoundingMode

trait MonitorTypeDB {
  val logger: Logger = Logger(this.getClass)
  implicit val mtmWrite: OWrites[MonitorTypeMore] = Json.writes[MonitorTypeMore]
  implicit val mtmRead: Reads[MonitorTypeMore] = Json.reads[MonitorTypeMore]
  implicit val mtWrite: OWrites[MonitorType] = Json.writes[MonitorType]
  implicit val mtRead: Reads[MonitorType] = Json.reads[MonitorType]
  val defaultMonitorTypes: List[MonitorType] = List(
    rangeType(SO2, "二氧化硫", "ppb", 2),
    rangeType(NOX, "氮氧化物", "ppb", 2),
    rangeType(NO2, "二氧化氮", "ppb", 2),
    rangeType(NO, "一氧化氮", "ppb", 2),
    rangeType(NOY, "NOY", "ppb", 2),
    rangeType(NOY_NO, "NOY-NO", "ppb", 2),
    rangeType(CO, "一氧化碳", "ppm", 2),
    rangeType(CO2, "二氧化碳", "ppm", 2),
    rangeType(O3, "臭氧", "ppb", 2),
    rangeType(THC, "總碳氫化合物", "ppm", 2),
    rangeType(TS, "總硫", "ppb", 2),
    rangeType(CH4, "甲烷", "ppm", 2),
    rangeType(NMHC, "非甲烷碳氫化合物", "ppm", 2),
    rangeType(NH3, "氨", "ppb", 2),
    rangeType("TSP", "TSP", "μg/m3", 2),
    rangeType(PM10, "PM10懸浮微粒", "μg/m3", 2),
    rangeType(PM25, "PM2.5細懸浮微粒", "μg/m3", 2),
    rangeType(WIN_SPEED, "風速", "m/sec", 2),
    rangeType(WIN_DIRECTION, "風向", "degrees", 2),
    rangeType(WS_SPEED, "風速(算術平均)", "m/sec", 2),
    rangeType(TEMP, "溫度", "℃", 2),
    rangeType(HUMID, "濕度", "%", 2),
    rangeType(PRESS, "氣壓", "hPa", 2),
    rangeType(RAIN, "雨量", "mm/h", 2),
    rangeType(LAT, "緯度", "度", 5),
    rangeType(LNG, "經度", "度", 5),
    rangeType("RT", "室內溫度", "℃", 1),
    rangeType("O2", "氧氣 ", "%", 1),
    rangeType(SOLAR, "日照", "W/m2", 2),
    rangeType(CH2O, "CH2O", "ppb", 2),
    rangeType(TVOC, "TVOC", "ppb", 2),
    rangeType(NOISE, "NOISE", "dB", 2),
    rangeType(H2S, "H2S", "ppb", 2),
    rangeType(H2, "H2", "ppb", 2),
    rangeType(LEQA, "LeqA", "dB", 2, accumulated = true),
    rangeType(LEQZ, "LeqZ", "dB", 2, accumulated = true),
    rangeType(LDN, "LDN", "dB", 2, accumulated = true),
    rangeType(WD10, "前10分風向向量平均", "degrees", 2),
    rangeType(WS10, "前10分風速算術平均", "m/sec", 2),
    /////////////////////////////////////////////////////
    signalType(DOOR, "門禁"),
    signalType(SMOKE, "煙霧"),
    signalType(FLOW, "採樣流量"),
    signalType("SPRAY", "灑水"))


  private val mtEpaIdMap: Map[String, String] = Map(
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

  val epaToMtMap: Map[Int, String] = mtEpaIdMap.map(pair => pair._2.toInt -> pair._1)

  @volatile var rangeList = List.empty[String]
  @volatile var signalList = List.empty[String]
  @volatile var map = Map.empty[String, MonitorType]
  private var diValueMap = Map.empty[String, Boolean]

  def nameIdMap: Map[String, String] = map map { pair => pair._2.desp -> pair._1 }

  def signalType(_id: String, desp: String): MonitorType = {
    signalOrder += 1
    MonitorType(_id, desp, "N/A", 0, signalOrder, signalType = true)
  }

  def logDiMonitorType(alarmDB: AlarmDB, mt: String, v: Boolean): Unit = {
    if (!signalList.contains(mt))
      logger.warn(s"${mt} is not DI monitor type!")

    val previousValue = diValueMap.getOrElse(mt, !v)
    diValueMap = diValueMap + (mt -> v)
    if (previousValue != v) {
      val mtCase = map(mt)
      if (v)
        alarmDB.log(alarmDB.src(), Alarm.Level.WARN, s"${mtCase.desp}=>觸發", 1)
      else
        alarmDB.log(alarmDB.src(), Alarm.Level.INFO, s"${mtCase.desp}=>解除", 1)
    }
  }

  protected def refreshMtv(): Unit = {
    val mtListFromDb = getList.sortBy {
      _.order
    }

    map = (mtListFromDb map { mt => mt._id -> mt }).toMap
    rangeList = mtListFromDb.filter { mt => !mt.signalType } map (mt => mt._id)
    signalList = mtListFromDb.filter { mt => mt.signalType } map (mt => mt._id)

    // ensure calculated types
    for (mt <- calculatedMonitorTypes)
      ensure(mt)
  }

  def getList: List[MonitorType]

  def ensure(id: String): Unit =
    synchronized {
      if (!map.contains(id)) {
        val mt = defaultMonitorTypes.find(_._id == id).getOrElse(rangeType(id, id, "??", 2))
        mt.measuringBy = Some(List.empty[String])
        upsertMonitorType(mt)
      } else {
        val mtCase = map(id)
        if (mtCase.measuringBy.isEmpty) {
          mtCase.measuringBy = Some(List.empty[String])
          upsertMonitorType(mtCase)
        }
      }
    }

  def ensure(mtCase: MonitorType): Unit = {
    synchronized {
      if (!map.contains(mtCase._id)) {
        mtCase.measuringBy = Some(List.empty[String])
        upsertMonitorType(mtCase)
      } else {
        if (mtCase.measuringBy.isEmpty) {
          mtCase.measuringBy = Some(List.empty[String])
          upsertMonitorType(mtCase)
        }
      }
    }
  }

  def rangeType(_id: String, desp: String, unit: String, prec: Int, accumulated: Boolean = false, acoustic: Boolean = false): MonitorType = {
    rangeOrder += 1
    MonitorType(_id, desp, unit, prec, rangeOrder, accumulated = Some(accumulated), acoustic = Some(acoustic))
  }

  def deleteMonitorType(_id: String): Unit = {
    synchronized {
      if (map.contains(_id)) {
        val mt = map(_id)
        map = map - _id
        if (mt.signalType)
          signalList = signalList.filter(p => p != _id)
        else
          rangeList = rangeList.filter(p => p != _id)

        deleteItemFuture(_id)
      }
    }
  }

  def deleteItemFuture(_id: String): Unit

  def allMtvList: List[String] = rangeList ++ signalList

  def measuredList: List[String] = rangeList.filter { mt => map(mt).measuringBy.isDefined }

  // Realtime measuring monitorTypes exclude calculated monitorTypes
  def measuringList: List[String] = rangeList.filter { mt =>
    map(mt).measuringBy.getOrElse(Seq.empty[String]).nonEmpty
  }

  def nonCalculatedMeasuringList: List[String] = measuringList.filter(!IsCalculated(_))

  def addMeasuring(mt: String, instrumentId: String, append: Boolean, recordDB: RecordDB): Future[UpdateResult] = {
    recordDB.ensureMonitorType(mt)
    synchronized {
      if (!map.contains(mt)) {
        val mtCase = defaultMonitorTypes.find(_._id == mt).getOrElse(rangeType(mt, mt, "??", 2))
        mtCase.addMeasuring(instrumentId, append)
        upsertMonitorType(mtCase)
      } else {
        val mtCase = map(mt)
        mtCase.addMeasuring(instrumentId, append)
        upsertItemFuture(mtCase)
      }
    }
  }

  def upsertMonitorType(mt: MonitorType): Future[UpdateResult] = {
    synchronized {
      map = map + (mt._id -> mt)
      if (mt.signalType) {
        if (!signalList.contains(mt._id))
          signalList = signalList :+ mt._id
      } else {
        if (!rangeList.contains(mt._id))
          rangeList = rangeList :+ mt._id
      }

      upsertItemFuture(mt)
    }
  }

  protected def upsertItemFuture(mt: MonitorType): Future[UpdateResult]

  def stopMeasuring(instrumentId: String): Future[Seq[UpdateResult]] = {
    val mtSet = measuringList.toSet ++ signalList.toSet
    val allF: Seq[Future[UpdateResult]] =
      for {mt <- mtSet.toSeq
           instrumentList <- map(mt).measuringBy if instrumentList.contains(instrumentId)
           } yield {
        val newMt = map(mt).stopMeasuring(instrumentId)
        map = map + (mt -> newMt)
        upsertItemFuture(newMt)
      }
    Future.sequence(allF)
  }

  def format(mt: String, v: Option[Double], precisionOpt: Option[Int] = None): String = {
    if (v.isEmpty)
      "-"
    else {
      val precision = precisionOpt.getOrElse(map(mt).prec)
      s"%.${precision}f".format(v.get)
    }
  }

  def formatRecord(mt: String, r: Option[Record]): String = {
    val ret =
      for (rec <- r if rec.value.isDefined) yield {
        val precision = map(mt).prec
        s"%.${precision}f".format(r.get.value.get)
      }
    ret.getOrElse("-")
  }

  def getCssClassStr(record: MtRecord): Seq[String] = {
    val (overInternal, overLaw) = overStd(record.mtName, record.value)
    MonitorStatus.getCssClassStr(record.status, overInternal, overLaw)
  }

  def getCssClassStr(mt: String, r: Option[Record]): Seq[String] = {
    if (r.isEmpty)
      Seq.empty[String]
    else {
      val v = r.get.value
      val (overInternal, overLaw) = overStd(mt, v)
      MonitorStatus.getCssClassStr(r.get.status, overInternal, overLaw)
    }
  }

  def overStd(mt: String, vOpt: Option[Double]): (Boolean, Boolean) = {
    val mtCase = map(mt)

    val overLaw =
      for (std <- mtCase.std_law; v <- vOpt) yield
        if (v > std)
          true
        else
          false
    (overLaw.getOrElse(false), overLaw.getOrElse(false))
  }

  def getMinMtRecordByRawValue(mt: String, rawValue: Option[Double], status: String)(mOpt: Option[Double], bOpt: Option[Double]): MtRecord = {
    val mtCase = map(mt)
    val value: Option[Double] = rawValue.map(v => {
      if (mtCase.calibrate.getOrElse(false)) {
        val calibratedValue =
          for {
            m <- mOpt
            b <- bOpt
          } yield
            BigDecimal((v * m) + b).setScale(mtCase.prec, RoundingMode.HALF_EVEN).doubleValue()

        calibratedValue.getOrElse(v)
      } else
        v
    })
    MtRecord(mt, value, status, rawValue = rawValue)
  }

}
