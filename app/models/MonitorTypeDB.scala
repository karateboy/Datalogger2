package models

import com.google.inject.ImplementedBy
import models.MonitorType.{CH2O, CH4, CO, CO2, DOOR, FLOW, H2, H2S, HUMID, LAT, LNG, NH3, NO, NO2, NOISE, NOY, NOY_NO, NOx, O3, PM10, PM25, PRESS, RAIN, SMOKE, SO2, SOLAR, TEMP, THC, TS, TVOC, WIN_DIRECTION, WIN_SPEED, rangeOrder, signalOrder}
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import play.api.Logger
import play.api.libs.json.Json

import javax.inject.Singleton
import scala.concurrent.Future

trait MonitorTypeDB {
  implicit val configWrite = Json.writes[ThresholdConfig]
  implicit val configRead = Json.reads[ThresholdConfig]
  implicit val mtWrite = Json.writes[MonitorType]
  implicit val mtRead = Json.reads[MonitorType]
  val defaultMonitorTypes = List(
    rangeType(SO2, "二氧化硫", "ppb", 2),
    rangeType(NOx, "氮氧化物", "ppb", 2),
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
    rangeType("NMHC", "非甲烷碳氫化合物", "ppm", 2),
    rangeType(NH3, "氨", "ppb", 2),
    rangeType("TSP", "TSP", "μg/m3", 2),
    rangeType(PM10, "PM10懸浮微粒", "μg/m3", 2),
    rangeType(PM25, "PM2.5細懸浮微粒", "μg/m3", 2),
    rangeType(WIN_SPEED, "風速", "m/sec", 2),
    rangeType(WIN_DIRECTION, "風向", "degrees", 2),
    rangeType(TEMP, "溫度", "℃", 2),
    rangeType(HUMID, "濕度", "%", 2),
    rangeType(PRESS, "氣壓", "hPa", 2),
    rangeType(RAIN, "雨量", "mm/h", 2),
    rangeType(LAT, "緯度", "度", 4),
    rangeType(LNG, "經度", "度", 4),
    rangeType("RT", "室內溫度", "℃", 1),
    rangeType("O2", "氧氣 ", "%", 1),
    rangeType(SOLAR, "日照", "W/m2", 2),
    rangeType(CH2O, "CH2O", "ppb", 2),
    rangeType(TVOC, "TVOC", "ppb", 2),
    rangeType(NOISE, "NOISE", "dB", 2),
    rangeType(H2S, "H2S", "ppb", 2),
    rangeType(H2, "H2", "ppb", 2),
    /////////////////////////////////////////////////////
    signalType(DOOR, "門禁"),
    signalType(SMOKE, "煙霧"),
    signalType(FLOW, "採樣流量"),
    signalType("SPRAY", "灑水"))
  var (mtvList, signalMtvList, map) = refreshMtv

  def signalType(_id: String, desp: String): MonitorType = {
    signalOrder += 1
    MonitorType(_id, desp, "N/A", 0, signalOrder, true)
  }

  var diValueMap = Map.empty[String, Boolean]

  def logDiMonitorType(mt: String, v: Boolean): Unit

  def refreshMtv: (List[String], List[String], Map[String, MonitorType]) = {
    val list = getList.sortBy {
      _.order
    }
    val mtPair =
      for (mt <- list) yield {
        try {
          val mtv = mt._id
          mtv -> mt
        } catch {
          case _: NoSuchElementException =>
            mt._id -> mt
        }
      }

    val rangeList = list.filter { mt => mt.signalType == false }
    val rangeMtvList = rangeList.map(mt => (mt._id))
    val signalList = list.filter { mt => mt.signalType }
    val signalMvList = signalList.map(mt => (mt._id))
    mtvList = rangeMtvList
    signalMtvList = signalMvList
    map = mtPair.toMap
    (rangeMtvList, signalMvList, mtPair.toMap)
  }

  def getList: List[MonitorType]

  def exist(mt: MonitorType): Boolean = map.contains(mt._id)

  def ensureMonitorType(id: String): Unit = {
    if (!map.contains(id)) {
      val mt = rangeType(id, id, "??", 2)
      newMonitorType(mt)
    }
  }

  def rangeType(_id: String, desp: String, unit: String, prec: Int): MonitorType = {
    rangeOrder += 1
    MonitorType(_id, desp, unit, prec, rangeOrder)
  }

  def ensureMonitorType(mt: MonitorType) = {
    if (!map.contains(mt._id))
      newMonitorType(mt)
  }

  def newMonitorType(mt: MonitorType): Future[InsertOneResult]

  def deleteMonitorType(_id: String): Unit

  def allMtvList: List[String] = mtvList ++ signalMtvList

  def diMtvList: List[String] = List(RAIN) ++ signalMtvList

  def activeMtvList: List[String] = mtvList.filter { mt => map(mt).measuringBy.isDefined }

  def addMeasuring(mt: String, instrumentId: String, append: Boolean): Future[UpdateResult] = {
    map(mt).addMeasuring(instrumentId, append)
    upsertMonitorTypeFuture(map(mt))
  }

  def upsertMonitorTypeFuture(mt: MonitorType): Future[UpdateResult]

  def stopMeasuring(instrumentId: String): Unit = {
    for {
      mt <- realtimeMtvList
      instrumentList <- map(mt).measuringBy if instrumentList.contains(instrumentId)
    } {
      val newMt = map(mt).stopMeasuring(instrumentId)
      map = map + (mt -> newMt)
      upsertMonitorTypeFuture(newMt)
    }

    for {
      mt <- signalMtvList if map(mt).measuringBy.nonEmpty
      instrumentList <- map(mt).measuringBy if instrumentList.contains(instrumentId)
    } {
      val newMt = map(mt).stopMeasuring(instrumentId)
      map = map + (mt -> newMt)
      upsertMonitorTypeFuture(newMt)
    }
  }

  def realtimeMtvList: List[String] = mtvList.filter { mt =>
    val measuringBy = map(mt).measuringBy
    measuringBy.isDefined && (!measuringBy.get.isEmpty)
  }

  //def upsertMonitorType(mt: MonitorType): Future[UpdateResult]

  def format(mt: String, v: Option[Double]): String = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def getOverStd(mt: String, r: Option[Record]): Boolean = {
    if (r.isEmpty)
      false
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      overInternal || overLaw
    }
  }

  def overStd(mt: String, vOpt: Option[Double]): (Boolean, Boolean) = {
    val mtCase = map(mt)
    val overInternal =
      for (std <- mtCase.std_internal; v <- vOpt) yield
        if (v > std)
          true
        else
          false

    val overLaw =
      for (std <- mtCase.std_law; v <- vOpt) yield
        if (v > std)
          true
        else
          false
    (overInternal.getOrElse(false), overLaw.getOrElse(false))
  }

  def formatRecord(mt: String, r: Option[Record]): String = {
    val ret =
      for (rec <- r if rec.value.isDefined) yield {
        val prec = map(mt).prec
        s"%.${prec}f".format(r.get.value.get)
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
}
