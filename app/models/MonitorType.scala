package models

import models.ModelHelper._
import org.mongodb.scala.model._
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import play.api._
import play.api.libs.json._

case class ThresholdConfig(elapseTime: Int)

case class MonitorType(_id: String, desp: String, unit: String,
                       prec: Int, order: Int,
                       signalType: Boolean = false,
                       std_law: Option[Double] = None,
                       std_internal: Option[Double] = None,
                       zd_internal: Option[Double] = None, zd_law: Option[Double] = None,
                       span: Option[Double] = None, span_dev_internal: Option[Double] = None, span_dev_law: Option[Double] = None,
                       var measuringBy: Option[List[String]] = None,
                       thresholdConfig: Option[ThresholdConfig] = None,
                       acoustic: Option[Boolean] = None,
                       spectrum: Option[Boolean] = None,
                       levels: Option[Seq[Double]] = None,
                       calibrate: Option[Boolean] = None,
                       accumulated: Option[Boolean] = None) {
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
  val NOX = "NOX"
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
  val NOY_NO = "NOY-NO"
  val NOY = "NOY"
  var rangeOrder = 0
  var signalOrder = 1000

}

@Singleton
class MonitorTypeOp @Inject()(mongoDB: MongoDB, alarmOp: AlarmOp) {

  import MonitorType._
  import org.mongodb.scala.bson._

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent._

  implicit val configWrite = Json.writes[ThresholdConfig]
  implicit val configRead = Json.reads[ThresholdConfig]
  implicit val mtWrite = Json.writes[MonitorType]
  implicit val mtRead = Json.reads[MonitorType]

  implicit object TransformMonitorType extends BsonTransformer[MonitorType] {
    def apply(mt: MonitorType): BsonString = new BsonString(mt.toString)
  }

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val codecRegistry = fromRegistries(fromProviders(classOf[MonitorType], classOf[ThresholdConfig]), DEFAULT_CODEC_REGISTRY)
  val colName = "monitorTypes"
  val collection = mongoDB.database.getCollection[MonitorType](colName).withCodecRegistry(codecRegistry)
  val MonitorTypeVer = 2
  val defaultMonitorTypes = List(
    rangeType(WIN_SPEED, "風速", "m/sec", 2),
    rangeType(WIN_DIRECTION, "風向", "degrees", 2),
    rangeType(LAT, "緯度", "度", 4),
    rangeType(LNG, "經度", "度", 4),
    /////////////////////////////////////////////////////
    signalType("SPRAY", "灑水"))

  var (mtvList, signalMtvList, map) = refreshMtv

  def signalType(_id: String, desp: String): MonitorType = {
    signalOrder += 1
    MonitorType(_id, desp, "N/A", 0, signalOrder, true)
  }

  def logDiMonitorType(mt: String, v: Boolean) = {
    if (!signalMtvList.contains(mt))
      Logger.warn(s"${mt} is not DI monitor type!")

    val mtCase = map(mt)
    if (v) {
      alarmOp.log(alarmOp.Src(), alarmOp.Level.WARN, s"${mtCase.desp}=>觸發", 1)
    } else {
      alarmOp.log(alarmOp.Src(), alarmOp.Level.INFO, s"${mtCase.desp}=>解除", 1)
    }
  }

  def updateMt(): (List[String], List[String], Map[String, MonitorType]) = {
    val updateModels =
      for (mt <- defaultMonitorTypes) yield {
        UpdateOneModel(
          Filters.eq("_id", mt._id),
          mt.defaultUpdate, UpdateOptions().upsert(true))
      }

    val f = collection.bulkWrite(updateModels.toList, BulkWriteOptions().ordered(true)).toFuture()
    f.onFailure(errorHandler)
    waitReadyResult(f)
    refreshMtv
  }

  {
    val colNames = waitReadyResult(mongoDB.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) { // New
      waitReadyResult(mongoDB.database.createCollection(colName).toFuture())
      updateMt
    }
  }

  def refreshMtv: (List[String], List[String], Map[String, MonitorType]) = {
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

    val rangeList = list.filter { mt => mt.signalType == false }
    val rangeMtvList = rangeList.map(mt => (mt._id))
    val signalList = list.filter { mt => mt.signalType }
    val signalMvList = signalList.map(mt => (mt._id))
    mtvList = rangeMtvList
    signalMtvList = signalMvList
    map = mtPair.toMap
    (rangeMtvList, signalMvList, mtPair.toMap)
  }

  private def mtList: List[MonitorType] = {
    val f = mongoDB.database.getCollection(colName).find().toFuture()
    val r = waitReadyResult(f)
    r.map {
      toMonitorType
    }.toList
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

  def BFName(mt: String) = {
    val mtCase = map(mt)
    mtCase._id.replace(".", "_")
  }

  def exist(mt: MonitorType) = map.contains(mt._id)

  def ensureMonitorType(id: String) = {
    if (!map.contains(id)) {
      val mt = rangeType(id, id, "??", 2)
      newMonitorType(mt)
    }
  }

  def rangeType(_id: String, desp: String, unit: String, prec: Int) = {
    rangeOrder += 1
    MonitorType(_id, desp, unit, prec, rangeOrder)
  }

  def ensureMonitorType(mt: MonitorType) = {
    if (!map.contains(mt._id))
      newMonitorType(mt)
  }

  def newMonitorType(mt: MonitorType): Future[InsertOneResult] = {
    map = map + (mt._id -> mt)
    if (mt.signalType)
      signalMtvList = signalMtvList.:+(mt._id)
    else
      mtvList = mtvList.:+(mt._id)

    val f = collection.insertOne(mt).toFuture()
    f onFailure errorHandler
    f
  }

  def deleteMonitorType(_id: String) = {
    if (map.contains(_id)) {
      val mt = map(_id)
      map = map - _id
      if (mt.signalType)
        signalMtvList = signalMtvList.filter(p => p != _id)
      else
        mtvList = mtvList.filter(p => p != _id)

      val f = collection.deleteOne(Filters.equal("_id", _id)).toFuture()
      f onFailure errorHandler
    }
  }

  def allMtvList = mtvList ++ signalMtvList

  def diMtvList = List(RAIN) ++ signalMtvList

  def activeMtvList = mtvList.filter { mt => map(mt).measuringBy.isDefined }

  def addMeasuring(mt: String, instrumentId: String, append: Boolean) = {
    map(mt).addMeasuring(instrumentId, append)
    upsertMonitorTypeFuture(map(mt))
  }

  def upsertMonitorTypeFuture(mt: MonitorType) = {
    import org.mongodb.scala.model.ReplaceOptions

    val f = collection.replaceOne(Filters.equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
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

    for {
      mt <- signalMtvList if map(mt).measuringBy.nonEmpty
      instrumentList = map(mt).measuringBy.get if instrumentList.contains(instrumentId)
    } {
      val newMt = map(mt).stopMeasuring(instrumentId)
      map = map + (mt -> newMt)
      upsertMonitorTypeFuture(newMt)
    }
  }

  import org.mongodb.scala.model.Filters._

  def realtimeMtvList = mtvList.filter { mt =>
    val measuringBy = map(mt).measuringBy
    measuringBy.isDefined && (!measuringBy.get.isEmpty)
  }

  def upsertMonitorType(mt: MonitorType): Future[UpdateResult] = {
    import org.mongodb.scala.model.ReplaceOptions
    map = map + (mt._id -> mt)
    if (mt.signalType) {
      if (!signalMtvList.contains(mt._id))
        signalMtvList = signalMtvList :+ mt._id
    } else {
      if (!mtvList.contains(mt._id))
        mtvList = mtvList :+ mt._id
    }

    val f = collection.replaceOne(equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    f onFailure errorHandler

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

  def format(mt: String, v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def getOverStd(mt: String, r: Option[Record]) = {
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

  def getCssClassStr(record: MtRecord) = {
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