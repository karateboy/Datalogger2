package models.mongodb

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import org.mongodb.scala.model._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CalibrationOp @Inject()(mongodb: MongoDB, monitorTypeOp: MonitorTypeDB) extends CalibrationDB {

  val collectionName = "calibration"
  val collection = mongodb.database.getCollection(collectionName)

  import org.mongodb.scala._
  import org.mongodb.scala.model.Indexes._

  override def calibrationReport(start: DateTime, end: DateTime): Seq[Calibration] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(gte("startTime", start.toDate()), lt("startTime", end.toDate()))).sort(ascending("startTime")).toFuture()
    val docs = waitReadyResult(f)
    docs.map {
      toCalibration
    }
  }

  init

  def toCalibration(doc: Document) = {
    import org.mongodb.scala.bson.BsonDouble
    def doublePf: PartialFunction[org.mongodb.scala.bson.BsonValue, Double] = {
      case t: BsonDouble =>
        t.getValue
    }

    val startTime = new DateTime(doc.get("startTime").get.asDateTime().getValue)
    val endTime = new DateTime(doc.get("endTime").get.asDateTime().getValue)
    val monitorType = (doc.get("monitorType").get.asString().getValue)
    val zero_val = doc.get("zero_val").collect(doublePf)

    val span_std = doc.get("span_std").collect(doublePf)
    val span_val = doc.get("span_val").collect(doublePf)
    Calibration(monitorType, startTime, endTime, zero_val, span_std, span_val)
  }

  override def calibrationReportFuture(start: DateTime, end: DateTime): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(gte("startTime", start.toDate()), lt("startTime", end.toDate()))).sort(ascending("startTime")).toFuture()
    for (docs <- f)
      yield docs.map {
        toCalibration
      }
  }

  override def calibrationReportFuture(start: DateTime): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(gte("startTime", start.toDate())).sort(ascending("startTime")).toFuture()
    for (docs <- f)
      yield docs.map {
        toCalibration
      }
  }

  override def calibrationReport(mt: String, start: DateTime, end: DateTime): Seq[Calibration] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(equal("monitorType", mt.toString), gte("startTime", start.toDate()), lt("startTime", end.toDate()))).sort(ascending("startTime")).toFuture()
    val docs = waitReadyResult(f)
    docs.map {
      toCalibration
    }
  }

  override def getCalibrationMap(startDate: DateTime, endDate: DateTime)
                                (implicit monitorTypeOp: MonitorTypeDB): Future[Map[String, List[(Imports.DateTime, Calibration)]]] = {
    val begin = (startDate - 5.day).toDate
    val end = (endDate + 1.day).toDate
    val filter = Filters.and(Filters.gte("startTime", begin), Filters.lt("endTime", end))

    val f = collection.find(filter).sort(ascending("startTime")).toFuture()
    f onFailure (errorHandler())
    for (docs <- f)
      yield {
        val calibrationList = docs.map {
          toCalibration
        }
        import scala.collection.mutable._
        val resultMap = Map.empty[String, ListBuffer[(DateTime, Calibration)]]
        for (item <- calibrationList.filter { c => c.success } if item.monitorType != MonitorType.NO2) {
          val lb = resultMap.getOrElseUpdate(item.monitorType, ListBuffer.empty[(DateTime, Calibration)])
          lb.append((item.endTime, item))
        }

        resultMap.map(kv => kv._1 -> kv._2.toList).toMap
      }
  }

  override def insert(cal: Calibration) = {
    import ModelHelper._
    val f = collection.insertOne(toDocument(cal)).toFuture()
    f onFailure ({
      case ex: Exception =>
        logException(ex)
    })
  }

  def toDocument(cal: Calibration) = {
    import org.mongodb.scala.bson._
    Document("monitorType" -> cal.monitorType, "startTime" -> (cal.startTime: BsonDateTime),
      "endTime" -> (cal.endTime: BsonDateTime), "zero_val" -> cal.zero_val,
      "span_std" -> cal.span_std, "span_val" -> cal.span_val)
  }

  private def init() {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(collectionName)) {
        val f = mongodb.database.createCollection(collectionName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            val cf = collection.createIndex(ascending("monitorType", "startTime", "endTime")).toFuture()
            cf.onFailure(errorHandler)
        })
      }
    }
  }
}