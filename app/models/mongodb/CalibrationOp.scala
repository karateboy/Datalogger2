package models.mongodb

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import org.mongodb.scala.model._

import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CalibrationOp @Inject()(mongodb: MongoDB) extends CalibrationDB {
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  lazy val colName = "calibration"
  lazy val codecRegistry = fromRegistries(fromProviders(classOf[Calibration]), DEFAULT_CODEC_REGISTRY)
  lazy private val collection = mongodb.database.getCollection[Calibration](colName).withCodecRegistry(codecRegistry)

  import org.mongodb.scala._
  import org.mongodb.scala.model.Indexes._

  init


  override def calibrationReportFuture(start: DateTime, end: DateTime): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    collection.find(and(gte("startTime", start.toDate()), lt("startTime", end.toDate()))).sort(ascending("startTime")).toFuture()
  }

  override def calibrationReportFuture(start: DateTime): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    collection.find(gte("startTime", start.toDate())).sort(ascending("startTime")).toFuture()
  }

  override def insertFuture(cal: Calibration) = {
    import ModelHelper._
    val f = collection.insertOne(cal).toFuture()
    f onFailure errorHandler
  }

  private def init() {
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

  override def getLatestMonitorRecordTimeAsync(monitor: String): Future[Option[DateTime]] = {
    val f = collection.find(Filters.equal("monitor", monitor)).sort(Sorts.descending("startTime")).limit(1).toFuture()
    for(ret<-f) yield
      if(ret.isEmpty)
        None
      else
        Some(new DateTime(ret(0).startTime))
  }

  override def monitorCalibrationReport(monitors: Seq[String], start: Date, end: Date): Future[Seq[Calibration]] = ???
}