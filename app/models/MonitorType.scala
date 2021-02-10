package models
import play.api._
import EnumUtils._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import com.github.nscala_time.time.Imports._

import scala.concurrent.ExecutionContext.Implicits.global
import org.mongodb.scala.model._
import org.mongodb.scala.bson._

case class MonitorType(_id: String, desp: String, unit: String,
                       prec: Int, order: Int,
                       signalType:   Boolean        = false,
                       std_law:      Option[Double] = None,
                       std_internal: Option[Double] = None,
                       zd_internal:  Option[Double] = None, zd_law: Option[Double] = None,
                       span: Option[Double] = None, span_dev_internal: Option[Double] = None, span_dev_law: Option[Double] = None,
                       var measuringBy: Option[List[String]] = None) {
  def defaultUpdate = {
    Updates.combine(
      Updates.setOnInsert("_id", _id),
      Updates.setOnInsert("desp", desp),
      Updates.setOnInsert("unit", unit),
      Updates.setOnInsert("prec", prec),
      Updates.setOnInsert("order", order),
      Updates.setOnInsert("signalType", signalType))
  }

  def addMeasuring(instrumentId: String, append: Boolean) = {
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

  def stopMeasuring(instrumentId: String) = {
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

@Singleton
class MonitorTypeOp @Inject()(mongoDB: MongoDB, alarmOp: AlarmOp) {
  import org.mongodb.scala.bson._
  import scala.concurrent._
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val mtWrite = Json.writes[MonitorType]
  implicit val mtRead = Json.reads[MonitorType]
  implicit object TransformMonitorType extends BsonTransformer[MonitorType] {
    def apply(mt: MonitorType): BsonString = new BsonString(mt.toString)
  }

  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{ fromRegistries, fromProviders }

  val codecRegistry = fromRegistries(fromProviders(classOf[MonitorType]), DEFAULT_CODEC_REGISTRY)
  val colName = "monitorTypes"
  val collection = mongoDB.database.getCollection[MonitorType](colName).withCodecRegistry(codecRegistry)

  val MonitorTypeVer = 2

  //Logger.info(s"MonitorType upgrade = $mtUpgrade")

  var rangeOrder = 0
  def rangeType(_id: String, desp: String, unit: String, prec: Int) = {
    rangeOrder += 1
    MonitorType(_id, desp, unit, prec, rangeOrder)
  }

  var signalOrder = 1000
  def signalType(_id: String, desp: String) = {
    signalOrder += 1
    MonitorType(_id, desp, "N/A", 0, signalOrder, true)
  }

  val defaultMonitorTypes = List(
    rangeType("SO2", "二氧化硫", "ppb", 1),
    rangeType("NOx", "氮氧化物", "ppb", 1),
    rangeType("NO2", "二氧化氮", "ppb", 1),
    rangeType("NO", "一氧化氮", "ppb", 1),
    rangeType("CO", "一氧化碳", "ppm", 1),
    rangeType("CO2", "二氧化碳", "ppm", 1),
    rangeType("O3", "臭氧", "ppb", 1),
    rangeType("THC", "總碳氫化合物", "ppm", 1),
    rangeType("TS", "總硫", "ppb", 1),
    rangeType("CH4", "甲烷", "ppm", 1),
    rangeType("NMHC", "非甲烷碳氫化合物", "ppm", 1),
    rangeType("NH3", "氨", "ppb", 1),
    rangeType("TSP", "TSP", "μg/m3", 1),
    rangeType("PM10", "PM10懸浮微粒", "μg/m3", 1),
    rangeType("PM25", "PM2.5細懸浮微粒", "μg/m3", 1),
    rangeType("WD_SPEED", "風速", "m/sec", 1),
    rangeType("WD_DIR", "風向", "degrees", 1),
    rangeType("TEMP", "溫度", "℃", 1),
    rangeType("HUMID", "濕度", "%", 1),
    rangeType("PRESS", "氣壓", "hPa", 1),
    rangeType("RAIN", "雨量", "mm/h", 1),
    rangeType("LAT", "緯度", "度", 4),
    rangeType("LNG", "經度", "度", 4),
    rangeType("HCl", "氯化氫", "ppm", 1),
    rangeType("H2O", "水", "ppm", 1),
    rangeType("RT", "室內溫度", "℃", 1),
    rangeType("OPA", "不透光率 ", "%", 1),
    rangeType("HCl", "氯化氫 ", "ppm", 1),
    rangeType("O2", "氧氣 ", "%", 1),
    rangeType("Flow", "流率 ", "Nm3/h", 1),
    /////////////////////////////////////////////////////
    signalType("DOOR", "門禁"),
    signalType("SMOKE", "煙霧"),
    signalType("FLOW", "採樣流量"))

  lazy val WIN_SPEED = ("WD_SPEED")
  lazy val WIN_DIRECTION = ("WD_DIR")
  lazy val RAIN = ("RAIN")
  lazy val PM25 = ("PM25")
  lazy val PM10 = ("PM10")
  lazy val LAT = ("LAT")
  lazy val LNG = ("LNG")

  val DOOR = "DOOR"
  val SMOKE = "SMOKE"
  val FLOW = "FLOW"

  var signalMtOldValue = Map.empty[MonitorType, Boolean]

  def logDiMonitorType(mt: String, v: Boolean) = {
    if (!signalMtvList.contains(mt))
      Logger.warn(s"${mt} is not DI monitor type!")

    if (v) {
      val mtCase = map(mt)
      alarmOp.log(alarmOp.Src(), alarmOp.Level.WARN, s"${mtCase.desp}=>觸發", 1)
    }

  }

  def init(colNames: Seq[String]): Future[Any] = {
    def updateMt = {
      val updateModels =
        for (mt <- defaultMonitorTypes) yield {
          val filter = Filters.eq("_id", mt._id)
          UpdateOneModel(
            Filters.eq("_id", mt._id),
            mt.defaultUpdate, UpdateOptions().upsert(true))
        }

      val f = collection.bulkWrite(updateModels.toList, BulkWriteOptions().ordered(false)).toFuture()

      f.onFailure(errorHandler)
      f.onComplete { x =>
        refreshMtv
      }
      f
    }

    if (!colNames.contains(colName)) { // New
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
      waitReadyResult(f)
    }

    updateMt
  }

  def BFName(mt: String) = {
    val mtCase = map(mt)
    mtCase._id.replace(".", "_")
  }

  def toDocument(mt: MonitorType) = {
    val json = Json.toJson(mt)
    Document(json.toString())
  }

  def toMonitorType(d: Document) = {
    val ret = Json.parse(d.toJson()).validate[MonitorType]

    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString)
      },
      mt =>
        mt)
  }

  private def mtList: List[MonitorType] =
    {
      val f = mongoDB.database.getCollection(colName).find().toFuture()
      val r = waitReadyResult(f)
      r.map { toMonitorType }.toList
    }

  def exist(mt: MonitorType) =  map.contains(mt._id)

  def rawMonitorTypeID(_id: String) = s"${_id}_raw"

  def getRawMonitorType(mt: String) =
    (rawMonitorTypeID(mt.toString()))

  def ensureRawMonitorType(mt: String, unit: String) {
    val mtCase = map(mt)
    try {
      (s"${mtCase._id}_raw")
    } catch {
      case _: NoSuchElementException =>
        val rawMonitorType = rangeType(
          rawMonitorTypeID(mtCase._id),
          s"${mtCase.desp}(原始值)", unit, 3)
        newMonitorType(rawMonitorType)
    }
  }

  def refreshMtv: (List[String], List[String], Map[String, MonitorType]) = {
    val list = mtList.sortBy { _.order }
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

    val rangeList = list.filter { mt => mt.signalType == false }
    val rangeMtvList = rangeList.map(mt => (mt._id))
    val signalList = list.filter { mt => mt.signalType }
    val signalMvList = signalList.map(mt => (mt._id))
    mtvList = rangeMtvList
    signalMtvList = signalMvList
    map = mtPair.toMap
    (rangeMtvList, signalMvList, mtPair.toMap)
  }

  var (mtvList, signalMtvList, map) = refreshMtv
  def allMtvList = mtvList ++ signalMtvList
  def diMtvList = List(RAIN) ++ signalMtvList

  def activeMtvList = mtvList.filter { mt => map(mt).measuringBy.isDefined }
  def realtimeMtvList = mtvList.filter { mt =>
    val measuringBy = map(mt).measuringBy
    measuringBy.isDefined && (!measuringBy.get.isEmpty)
  }

  def newMonitorType(mt: MonitorType) = {
    val f = collection.insertOne(mt).toFuture()
    f.onSuccess({
      case x =>
        refreshMtv
    })
  }

  def addMeasuring(mt: String, instrumentId: String, append: Boolean) = {
    val newMt = map(mt).addMeasuring(instrumentId, append)
    map = map + (mt -> newMt)
    upsertMonitorTypeFuture(newMt)
  }

  def stopMeasuring(instrumentId: String) = {
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
  def upsertMonitorType(mt: MonitorType) = {
    import org.mongodb.scala.model.ReplaceOptions

    val f = collection.replaceOne(equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    map = map + (mt._id -> mt)
    true
  }

  def upsertMonitorTypeFuture(mt: MonitorType) = {
    import org.mongodb.scala.model.ReplaceOptions

    val f = collection.replaceOne(equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  //  def insertNewMonitorTypeFuture(mt: MonitorType) = {
  //    import org.mongodb.scala.model.UpdateOptions
  //    import org.mongodb.scala.bson.BsonString
  //    import org.mongodb.scala.model.Updates
  //    //Updates.setOnInsert(fieldName, value)
  //
  //    val f = collection.updateOne(equal("_id", mt._id), update, UpdateOptions().upsert(true)).toFuture()
  //    f.onFailure(errorHandler)
  //    f
  //  }

  def updateMonitorType(mt: String, colname: String, newValue: String) = {
    import org.mongodb.scala._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._
    import org.mongodb.scala.model.FindOneAndUpdateOptions

    import scala.concurrent.ExecutionContext.Implicits.global
    val idFilter = equal("_id", map(mt)._id)
    val opt = FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER)
    val f =
      if (colname == "desp" || colname == "unit" || colname == "measuringBy" || colname == "measuredBy") {
        if (newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else
          collection.findOneAndUpdate(idFilter, set(colname, newValue), opt).toFuture()
      } else if (colname == "prec" || colname == "order") {
        import java.lang.Integer
        val v = Integer.parseInt(newValue)
        collection.findOneAndUpdate(idFilter, set(colname, v), opt).toFuture()
      } else {
        if (newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else {
          import java.lang.Double
          collection.findOneAndUpdate(idFilter, set(colname, Double.parseDouble(newValue)), opt).toFuture()
        }
      }

    val mtCase = waitReadyResult(f)
    Logger.debug(mtCase.toString)
    map = map + (mt -> mtCase)
  }

  def format(mt: String, v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def overStd(mt: String, v: Double) = {
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

  def getOverStd(mt: String, r: Option[Record]) = {
    if (r.isEmpty)
      false
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      overInternal || overLaw
    }
  }

  def formatRecord(mt: String, r: Option[Record]) = {
    if (r.isEmpty)
      "-"
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      val prec = map(mt).prec
      val value = s"%.${prec}f".format(r.get.value)
      if (overInternal || overLaw)
        s"<i class='fa fa-exclamation-triangle'></i>$value"
      else
        s"$value"
    }
  }

  def getCssClassStr(record:MtRecord) = {
      val (overInternal, overLaw) = overStd(record.mtName, record.value)
      MonitorStatus.getCssClassStr(record.status, overInternal, overLaw)
  }

  def getCssClassStr(mt: String, r: Option[Record]) = {
    if (r.isEmpty)
      Seq.empty[String]
    else {
      val v = r.get.value
      val (overInternal, overLaw) = overStd(mt, v)
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