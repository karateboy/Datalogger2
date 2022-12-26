package models

import models.MonitorType._
import org.mongodb.scala.result.UpdateResult
import play.api.Logger
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

trait MonitorTypeDB {
  implicit val configWrite = Json.writes[ThresholdConfig]
  implicit val configRead = Json.reads[ThresholdConfig]
  implicit val mtWrite = Json.writes[MonitorType]
  implicit val mtRead = Json.reads[MonitorType]
  val defaultMonitorTypes = List(
    rangeType("KW1", "用電量1", "kW", 2),
    rangeType("KW2", "用電量2", "kW", 2),
    rangeType("KW3", "用電量3", "kW", 2),
    rangeType("KW4", "用電量4", "kW", 2),
    rangeType("KW5", "用電量5", "kW", 2),
    rangeType("KW6", "用電量6", "kW", 2),
    rangeType(WIN_SPEED, "風速", "m/sec", 2),
    rangeType(WIN_DIRECTION, "風向", "degrees", 2),
    rangeType(LAT, "緯度", "度", 5),
    rangeType(LNG, "經度", "度", 5),
    rangeType("normalUsage", desp="用電量", "kW", prec=2),
    rangeType("reverseUsage", desp="逆用電量", "kW", prec=2),
    rangeType("totalSoldElectricity", desp="售電量", "kW", prec=2)
    /////////////////////////////////////////////////////
    )
  var mtvList = List.empty[String]
  var signalMtvList = List.empty[String]
  var map = Map.empty[String, MonitorType]
  var diValueMap = Map.empty[String, Boolean]

  def signalType(_id: String, desp: String): MonitorType = {
    signalOrder += 1
    MonitorType(_id, desp, "N/A", 0, signalOrder, true)
  }

  def logDiMonitorType(alarmDB: AlarmDB, mt: String, v: Boolean): Unit = {
    if (!signalMtvList.contains(mt))
      Logger.warn(s"${mt} is not DI monitor type!")

    val previousValue = diValueMap.getOrElse(mt, !v)
    diValueMap = diValueMap + (mt -> v)
    if (previousValue != v) {
      val mtCase = map(mt)
      if (v)
        alarmDB.log(alarmDB.src(), alarmDB.Level.WARN, s"${mtCase.desp}=>觸發", 1)
      else
        alarmDB.log(alarmDB.src(), alarmDB.Level.INFO, s"${mtCase.desp}=>解除", 1)
    }
  }

  protected def refreshMtv(): Unit = {
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
  }

  def getList: List[MonitorType]

  def ensure(id: String): Unit = {
    synchronized {
      if (!map.contains(id)) {
        val mt = rangeType(id, id, "??", 2)
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

  def rangeType(_id: String, desp: String, unit: String, prec: Int, accumulated:Boolean = false): MonitorType = {
    rangeOrder += 1
    MonitorType(_id, desp, unit, prec, rangeOrder, accumulated = Some(accumulated))
  }

  def deleteMonitorType(_id: String) = {
    synchronized {
      if (map.contains(_id)) {
        val mt = map(_id)
        map = map - _id
        if (mt.signalType)
          signalMtvList = signalMtvList.filter(p => p != _id)
        else
          mtvList = mtvList.filter(p => p != _id)

        deleteItemFuture(_id)
      }
    }
  }

  def deleteItemFuture(_id:String):Unit

  def allMtvList: List[String] = mtvList ++ signalMtvList

  def activeMtvList: List[String] = mtvList.filter { mt => map(mt).measuringBy.isDefined }

  def addMeasuring(mt: String, instrumentId: String, append: Boolean, recordDB: RecordDB): Future[UpdateResult] = {
    recordDB.ensureMonitorType(mt)
    synchronized {
      if (!map.contains(mt)) {
        val mtCase = rangeType(mt, mt, "??", 2)
        mtCase.addMeasuring(instrumentId, append)
        upsertMonitorType(mtCase)
      } else {
        val mtCase = map(mt)
        mtCase.addMeasuring(instrumentId, append)
        upsertItemFuture(mtCase)
      }
    }
  }

  def upsertMonitorType(mt:MonitorType): Future[UpdateResult] = {
    synchronized {
      map = map + (mt._id -> mt)
      if (mt.signalType) {
        if (!signalMtvList.contains(mt._id))
          signalMtvList = signalMtvList :+ mt._id
      } else {
        if (!mtvList.contains(mt._id))
          mtvList = mtvList :+ mt._id
      }

      upsertItemFuture(mt)
    }
  }

  protected def upsertItemFuture(mt: MonitorType): Future[UpdateResult]

  def stopMeasuring(instrumentId: String): Future[Seq[UpdateResult]] = {
    val mtSet = realtimeMtvList.toSet ++ signalMtvList.toSet
    val allF: Seq[Future[UpdateResult]] =
    for{mt<-mtSet.toSeq
        instrumentList <- map(mt).measuringBy if instrumentList.contains(instrumentId)
        } yield {
      val newMt = map(mt).stopMeasuring(instrumentId)
      map = map + (mt -> newMt)
      upsertItemFuture(newMt)
    }
    Future.sequence(allF)
  }

  def realtimeMtvList: List[String] = mtvList.filter { mt =>
    val measuringBy = map(mt).measuringBy
    measuringBy.isDefined && (!measuringBy.get.isEmpty)
  }

  def format(mt: String, v: Option[Double]): String = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
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
}
