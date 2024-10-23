package models.mongodb

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.model._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CalibrationOp @Inject()(mongodb: MongoDB) extends CalibrationDB {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  private lazy val codecRegistry = fromRegistries(fromProviders(classOf[Calibration]), DEFAULT_CODEC_REGISTRY)
  lazy val colName = "calibration"
  lazy val collection: MongoCollection[Calibration] = mongodb.database.getCollection[Calibration](colName).withCodecRegistry(codecRegistry)


  import org.mongodb.scala._
  import org.mongodb.scala.model.Indexes._

  override def calibrationReport(start: DateTime, end: DateTime): Seq[Calibration] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(gte("startTime", start.toDate), lt("startTime", end.toDate))).sort(ascending("startTime")).toFuture()
    f onFailure errorHandler
    waitReadyResult(f)
  }

  init()

  override def calibrationReportFuture(start: DateTime, end: DateTime): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(gte("startTime", start.toDate), lt("startTime", end.toDate))).sort(ascending("startTime")).toFuture()
    f onFailure errorHandler
    f
  }

  override def calibrationReportFuture(start: DateTime): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._
    val f = collection.find(gte("startTime", start.toDate)).sort(ascending("startTime")).toFuture()
    f onFailure errorHandler
    f
  }

  override def calibrationReport(mt: String, start: DateTime, end: DateTime): Seq[Calibration] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(equal("monitorType", mt), gte("startTime", start.toDate), lt("startTime", end.toDate))).sort(ascending("startTime")).toFuture()
    f onFailure errorHandler
    waitReadyResult(f)
  }

  override def insertFuture(cal: Calibration): Unit = {
    collection.insertOne(cal).toFuture()
  }

  private def init(): Unit = {
    for (colNames <- mongodb.database.listCollectionNames().toFuture()) {
      if (!colNames.contains(colName)) {
        val f = mongodb.database.createCollection(colName).toFuture()
        f.onFailure(errorHandler)
        f.onSuccess({
          case _ =>
            val cf = collection.createIndex(ascending("monitorType", "startTime", "endTime")).toFuture()
            cf.onFailure(errorHandler)
        })
      }
    }
  }

  override def getLatestCalibration(mt: String): Future[Option[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(equal("monitorType", mt)).sort(descending("startTime")).limit(1).toFuture()
    f onFailure errorHandler
    f.map { seq =>
      seq.headOption
    }
  }
}