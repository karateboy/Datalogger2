package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.{BulkWriteResult, MongoCollection}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model._
import org.mongodb.scala.result.UpdateResult
import play.api._
import play.api.libs.json._

case class ThresholdConfig(elapseTime: Int)

case class MonitorType(var _id: String,
                       desp: String,
                       unit: String,
                       prec: Int,
                       order: Int,
                       signalType: Boolean = false,
                       std_law: Option[Double] = None,
                       std_internal: Option[Double] = None,
                       zd_internal: Option[Double] = None,
                       zd_law: Option[Double] = None,
                       span: Option[Double] = None,
                       span_dev_internal: Option[Double] = None,
                       span_dev_law: Option[Double] = None,
                       var measuringBy: Option[List[String]] = None,
                       thresholdConfig: Option[ThresholdConfig] = None,
                       var group: Option[String] = None,
                       alarmPauseTime: Option[Int] = None,
                       alarmWarnTime: Option[Int] = None) {
  def defaultUpdate: Bson = {
    Updates.combine(
      Updates.setOnInsert("_id", _id),
      Updates.setOnInsert("desp", desp),
      Updates.setOnInsert("unit", unit),
      Updates.setOnInsert("prec", prec),
      Updates.setOnInsert("order", order),
      Updates.setOnInsert("signalType", signalType))
  }

  def addMeasuring(instrumentId: String, append: Boolean): MonitorType = {
    val newMeasuringBy =
      if (measuringBy.isEmpty)
        List(instrumentId)
      else {
        val currentMeasuring = measuringBy.get
        if (currentMeasuring.contains(instrumentId))
          currentMeasuring
        else {
          if (append)
            measuringBy.get ++ List(instrumentId)
          else
            instrumentId :: measuringBy.get
        }
      }

    MonitorType(_id, desp, unit,
      prec, order, signalType, std_law, std_internal,
      zd_internal, zd_law,
      span, span_dev_internal, span_dev_law,
      Some(newMeasuringBy))
  }

  def stopMeasuring(instrumentId: String): MonitorType = {
    val newMeasuringBy =
      if (measuringBy.isEmpty)
        None
      else
        Some(measuringBy.get.filter { id => id != instrumentId })

    MonitorType(_id, desp, unit,
      prec, order, signalType, std_law, std_internal,
      zd_internal, zd_law,
      span, span_dev_internal, span_dev_law,
      newMeasuringBy)
  }
}

//MeasuredBy => History...
//MeasuringBy => Current...

import javax.inject._

object MonitorType {
  val SO2 = "SO2"
  val NOx = "NOx"
  val NO2 = "NO2"
  val NO = "NO"
  val CO = "CO"
  val CO2 = "CO2"
  val CH4 = "CH4"
  val PM10 = "PM10"
  val PM25 = "PM25"
  val O3 = "O3"
  val THC = "THC"

  val LAT = "LAT"
  val LNG = "LNG"
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
  val VOC = "VOC"
  val SPRAY = "SPRAY"
  val SPRAY_WARN = "SPRAY_WARN"
  var rangeOrder = 0
  var signalOrder = 1000

}

@Singleton
class MonitorTypeOp @Inject()(mongoDB: MongoDB, alarmOp: AlarmOp, groupOp: GroupOp) {

  import MonitorType._
  import org.mongodb.scala.bson._

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent._

  implicit val configWrite: OWrites[ThresholdConfig] = Json.writes[ThresholdConfig]
  implicit val configRead: Reads[ThresholdConfig] = Json.reads[ThresholdConfig]
  implicit val mtWrite: OWrites[MonitorType] = Json.writes[MonitorType]
  implicit val mtRead: Reads[MonitorType] = Json.reads[MonitorType]

  implicit object TransformMonitorType extends BsonTransformer[MonitorType] {
    def apply(mt: MonitorType): BsonString = new BsonString(mt.toString)
  }

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[MonitorType], classOf[ThresholdConfig]), DEFAULT_CODEC_REGISTRY)
  val colName = "monitorTypes"
  val collection: MongoCollection[MonitorType] = mongoDB.database.getCollection[MonitorType](colName).withCodecRegistry(codecRegistry)
  val MonitorTypeVer = 2
  private val defaultMonitorTypes = List(
    rangeType(SO2, "二氧化硫", "ppb", 1),
    rangeType(NOx, "氮氧化物", "ppb", 1),
    rangeType(NO2, "二氧化氮", "ppb", 1),
    rangeType(NO, "一氧化氮", "ppb", 1),
    rangeType(CO, "一氧化碳", "ppm", 1),
    rangeType(CO2, "二氧化碳", "ppm", 1),
    rangeType(O3, "臭氧", "ppb", 1),
    rangeType(THC, "總碳氫化合物", "ppm", 1),
    rangeType(TS, "總硫", "ppb", 1),
    rangeType(CH4, "甲烷", "ppm", 1),
    rangeType("NMHC", "非甲烷碳氫化合物", "ppm", 1),
    rangeType(NH3, "氨", "ppb", 1),
    rangeType("TSP", "TSP", "μg/m3", 1),
    rangeType(PM10, "PM10懸浮微粒", "μg/m3", 1),
    rangeType(PM25, "PM2.5細懸浮微粒", "μg/m3", 1),
    rangeType(WIN_SPEED, "風速", "m/sec", 1),
    rangeType(WIN_DIRECTION, "風向", "degrees", 1),
    rangeType(TEMP, "溫度", "℃", 1),
    rangeType(HUMID, "濕度", "%", 1),
    rangeType(PRESS, "氣壓", "hPa", 1),
    rangeType(RAIN, "雨量", "mm/h", 1),
    rangeType(LAT, "緯度", "度", 4),
    rangeType(LNG, "經度", "度", 4),
    rangeType("HCl", "氯化氫", "ppm", 1),
    rangeType("H2O", "水", "ppm", 1),
    rangeType("RT", "室內溫度", "℃", 1),
    rangeType("OPA", "不透光率 ", "%", 1),
    rangeType("HCl", "氯化氫 ", "ppm", 1),
    rangeType("O2", "氧氣 ", "%", 1),
    rangeType(FLOW, "流率 ", "Nm3/h", 1),
    rangeType(SOLAR, "日照", "W/m2", 1),
    rangeType(CH2O, "CH2O", "ppb", 1),
    rangeType(TVOC, "TVOC", "ppb", 1),
    rangeType(NOISE, "NOISE", "dB", 1),
    rangeType(H2S, "H2S", "ppb", 1),
    rangeType(H2, "H2", "ppb", 1),
    /////////////////////////////////////////////////////
    signalType(DOOR, "門禁"),
    signalType(SMOKE, "煙霧"),
    signalType(FLOW, "採樣流量"),
    signalType(SPRAY, "灑水"),
    signalType(SPRAY_WARN, "灑水預警"))

  var (mtvList, signalMtvList, map) = refreshMtv
  private var groupSignalValueMap = Map.empty[String, Map[String, (DateTime, Boolean)]]

  private def signalType(_id: String, desp: String) = {
    signalOrder += 1
    MonitorType(_id, desp, "N/A", 0, signalOrder, true)
  }

  def getGroupMapAsync(groupID: String): Future[Map[String, MonitorType]] = {
    val f = collection.find(Filters.equal("group", groupID)).toFuture()
    f onFailure errorHandler()
    var groupMap = map
    for(groupMtList<-f) yield {
      groupMtList.foreach{
        mtCase =>
          val mtID = mtCase._id.reverse.drop(groupID.length + 1).reverse
          mtCase._id = mtID
          groupMap = groupMap + (mtID -> mtCase)
      }
      groupMap
    }
  }

  def updateSignalValueMap(groupID: String, mt: String, v: Boolean): Unit = {
    var signalValueMap = groupSignalValueMap.getOrElse(groupID, Map.empty[String, (DateTime, Boolean)])
    signalValueMap = signalValueMap + (mt -> (DateTime.now, v))
    groupSignalValueMap = groupSignalValueMap + (groupID -> signalValueMap)
  }

  def getSignalValueMap(groupID: String): Map[String, Boolean] = {
    val now = DateTime.now()
    var signalValueMap = groupSignalValueMap.getOrElse(groupID, Map.empty[String, (DateTime, Boolean)])
    signalValueMap = signalValueMap.filter(p => p._2._1.after(now - 6.seconds))
    signalValueMap map { p => p._1 -> p._2._2 }
  }

  def logDiMonitorType(mt: String, v: Boolean, groupID:String = "") = {
    if (!signalMtvList.contains(mt))
      Logger.warn(s"${mt} is not DI monitor type!")

    val mtCase = map(mt)
    val group = groupOp.map(groupID)
    val groupName = group.name
    if (v) {
      alarmOp.log(alarmOp.Src(groupID), alarmOp.Level.WARN, s"$groupName> ${mtCase.desp}=>觸發", 1)
    } else {
      alarmOp.log(alarmOp.Src(groupID), alarmOp.Level.INFO, s"$groupName> ${mtCase.desp}=>解除", 1)
    }

  }

  def init(): Future[Any] = {
    def updateMt(): Future[BulkWriteResult] = {
      val updateModels =
        for (mt <- defaultMonitorTypes) yield {
          UpdateOneModel(
            Filters.eq("_id", mt._id),
            mt.defaultUpdate, UpdateOptions().upsert(true))
        }

      val f = collection.bulkWrite(updateModels, BulkWriteOptions().ordered(false)).toFuture()

      f.onFailure(errorHandler)
      f.onComplete { x =>
        refreshMtv
      }
      f
    }

    for (colNames <- mongoDB.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) { // New
        val f = mongoDB.database.createCollection(colName).toFuture()
        f.onFailure(errorHandler)
        waitReadyResult(f)
      }
    }

    updateMt()
  }

  init

  private def refreshMtv: (List[String], List[String], Map[String, MonitorType]) = {
    val list = mtList.sortBy {
      _.order
    }
    val mtPair =
      for (mt <- list) yield {
        try {
          val mtv = (mt._id)
          (mtv -> mt)
        } catch {
          case _: NoSuchElementException =>
            (mt._id -> mt)
        }
      }

    val rangeList = list.filter { mt => !mt.signalType }
    val rangeMtvList = rangeList.map(mt => mt._id)
    val signalList = list.filter { mt => mt.signalType }
    val signalMvList = signalList.map(mt => mt._id)
    mtvList = rangeMtvList
    signalMtvList = signalMvList
    map = mtPair.toMap
    (rangeMtvList, signalMvList, mtPair.toMap)
  }

  private def mtList: List[MonitorType] = {
    val f = collection.find(Filters.equal("group", null)).toFuture()
    waitReadyResult(f).toList
  }

  def BFName(mt: String): String = {
    val mtCase = map(mt)
    mtCase._id.replace(".", "_")
  }

  def getRawMonitorType(mt: String): String =
    (rawMonitorTypeID(mt.toString()))

  private def rawMonitorTypeID(_id: String) = s"${_id}_raw"

  def ensureRawMonitorType(mt: String, unit: String): Unit = {
    val mtCase = map(mt)

    if (!map.contains(s"${mtCase._id}_raw")) {
      val rawMonitorType = rangeType(
        rawMonitorTypeID(mtCase._id),
        s"${mtCase.desp}(原始值)", unit, 3)
      newMonitorType(rawMonitorType)
    }
  }

  private def rangeType(_id: String, desp: String, unit: String, prec: Int) = {
    rangeOrder += 1
    MonitorType(_id, desp, unit, prec, rangeOrder)
  }

  private def newMonitorType(mt: MonitorType): Unit = {
    val f = collection.insertOne(mt).toFuture()
    f.onSuccess({
      case x =>
        refreshMtv
    })
  }

  def allMtvList: List[String] = mtvList ++ signalMtvList

  def diMtvList = List(RAIN) ++ signalMtvList

  def activeMtvList: Seq[String] = mtvList.filter { mt => map(mt).measuringBy.isDefined }

  def addMeasuring(mt: String, instrumentId: String, append: Boolean): Future[UpdateResult] = {
    val newMt = map(mt).addMeasuring(instrumentId, append)
    map = map + (mt -> newMt)
    upsertMonitorTypeFuture(newMt)
  }

  private def upsertMonitorTypeFuture(mt: MonitorType) = {
    import org.mongodb.scala.model.ReplaceOptions

    val f = collection.replaceOne(Filters.equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def stopMeasuring(instrumentId: String): Unit = {
    for {
      mt <- realtimeMtvList
      instrumentList = map(mt).measuringBy.get if instrumentList.contains(instrumentId)
    } {
      val newMt = map(mt).stopMeasuring(instrumentId)
      map = map + (mt -> newMt)
      upsertMonitorTypeFuture(newMt)
    }
  }

  import org.mongodb.scala.model.Filters._

  def realtimeMtvList: List[String] = mtvList.filter { mt =>
    val measuringBy = map(mt).measuringBy
    measuringBy.isDefined && (!measuringBy.get.isEmpty)
  }

  def upsertMonitorType(mt: MonitorType): Boolean = {
    import org.mongodb.scala.model.ReplaceOptions

    val f = collection.replaceOne(equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    map = map + (mt._id -> mt)
    true
  }

  def format(mt: String, v: Option[Double]): String = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def getOverStd(mt: String, r: Option[Record], mtMap:Map[String, MonitorType]): Boolean = {
    if (r.isEmpty)
      false
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value, mtMap)
      overInternal || overLaw
    }
  }

  def formatRecord(mt: String, r: Option[Record],  mtMap:Map[String, MonitorType]): String = {
    if (r.isEmpty)
      "-"
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value, mtMap)
      val prec = map(mt).prec
      val value = s"%.${prec}f".format(r.get.value)
      if (overInternal || overLaw)
        s"$value"
      else
        s"$value"
    }
  }

  def overStd(mt: String, v: Double, map:Map[String, MonitorType]): (Boolean, Boolean) = {
    val mtCase = map(mt)
    val overInternal =
      if (mtCase.std_internal.isDefined) {
        if (v > mtCase.std_internal.get)
          true
        else
          false
      } else
        false
    val overLaw =
      if (mtCase.std_law.isDefined) {
        if (v > mtCase.std_law.get)
          true
        else
          false
      } else
        false
    (overInternal, overLaw)
  }

  def getCssClassStr(record: MtRecord,  mtMap:Map[String, MonitorType]): Seq[String] = {
    val (overInternal, overLaw) = overStd(record.mtName, record.value, mtMap)
    MonitorStatus.getCssClassStr(record.status, overInternal, overLaw)
  }

  def getCssClassStr(mt: String, r: Option[Record], mtMap:Map[String, MonitorType]): Seq[String] = {
    if (r.isEmpty)
      Seq.empty[String]
    else {
      val v = r.get.value
      val (overInternal, overLaw) = overStd(mt, v, mtMap)
      MonitorStatus.getCssClassStr(r.get.status, overInternal, overLaw)
    }
  }

  def displayMeasuringBy(mt: String) = {
    val mtCase = map(mt)
    if (mtCase.measuringBy.isDefined) {
      val instrumentList = mtCase.measuringBy.get
      if (instrumentList.isEmpty)
        "外部儀器"
      else
        instrumentList.mkString(",")
    } else
      "-"
  }
}