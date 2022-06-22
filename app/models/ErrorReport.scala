package models

import org.joda.time.DateTime
import org.mongodb.scala.model.{ReplaceOptions, Updates}
import org.mongodb.scala.result.UpdateResult
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.mailer.{Email, MailerClient}

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class EffectiveRate(_id: String, rate: Double)
case class ErrorAction(sensorID:String, errorType:String, action:String)
case class ErrorReport(_id: Date, noErrorCode: Seq[String], powerError: Seq[String],
                       constant: Seq[String], ineffective: Seq[EffectiveRate], disconnect:Seq[String],
                       inspections: Seq[ErrorAction], actions:Seq[ErrorAction], constantRecordTime: Option[Long],
                       disconnectRecordTime: Option[Long],
                       dailyChecked: Boolean = false)
case class SensorErrorReport(errorType:String, monitors:Seq[Monitor])

object ErrorReport {
  implicit val writeAction = Json.writes[ErrorAction]
  implicit val readAction = Json.reads[ErrorAction]
  implicit val writeRates = Json.writes[EffectiveRate]
  implicit val readRates = Json.reads[EffectiveRate]
  implicit val reads = Json.reads[ErrorReport]
  implicit val writes = Json.writes[ErrorReport]
}

import models.ModelHelper.{errorHandler, waitReadyResult}
import org.mongodb.scala.model.Filters

import javax.inject._

case class EmailTarget(email:String, group:String, monitorIDs:Seq[String])
@Singleton
class ErrorReportOp @Inject()(mongoDB: MongoDB, mailerClient: MailerClient, monitorOp: MonitorOp) {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  val colName = "errorReports"

  val codecRegistry = fromRegistries(fromProviders(classOf[ErrorReport], classOf[EffectiveRate], classOf[ErrorAction]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[ErrorReport](colName).withCodecRegistry(codecRegistry)

  def init(): Unit = {
    val colNames = waitReadyResult(mongoDB.database.listCollectionNames().toFuture())
    if (!colNames.contains(colName)) {
      val f = mongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
    }
  }

  def upgrade(): Unit ={
    val filter = Filters.not(Filters.exists("inspections"))
    val update = Updates.combine(Updates.set("inspections", Seq.empty[ErrorAction]),
      Updates.set("actions", Seq.empty[ErrorAction]))
    val f = collection.updateMany(filter, update).toFuture()
    f onFailure(errorHandler())
  }

  init
  upgrade()

  def upsert(report: ErrorReport) = {
    val f = collection.replaceOne(Filters.equal("_id", report._id), report, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def addNoErrorCodeSensor = initBefore(addNoErrorCodeSensor1) _
  private def addNoErrorCodeSensor1(date: Date, sensorID: String): Future[UpdateResult] = {
    val updates = Updates.addToSet("noErrorCode", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def addErrorInspection = initBefore(addErrorInspection1) _
  private def addErrorInspection1(date: Date, inspection:ErrorAction): Future[UpdateResult] = {
    val updates = Updates.push("inspections", inspection)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def addErrorAction = initBefore(addErrorAction1) _
  private def addErrorAction1(date: Date, action:ErrorAction): Future[UpdateResult] = {
    val filter = Filters.equal("_id", date)
    val updates = Updates.push("actions", action)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def removeNoErrorCodeSensor = initBefore(removeNoErrorCodeSensor1) _

  def removeNoErrorCodeSensor1(date: Date, sensorID: String) = {
    val updates = Updates.pull("noErrorCode", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def addPowerErrorSensor = initBefore(addPowerErrorSensor1) _

  def addPowerErrorSensor1(date: Date, sensorID: String) = {
    val updates = Updates.addToSet("powerError", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def removePowerErrorSensor = initBefore(removePowerErrorSensor1) _

  def removePowerErrorSensor1(date: Date, sensorID: String) = {
    val updates = Updates.pull("powerError", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def initBefore[T](f: (Date, T) => Future[UpdateResult])(date: Date, sensorID: T): Unit = {

    insertEmptyIfNotExist(date).andThen({
      case _ =>
        f(date, sensorID)
    })
  }

  def insertEmptyIfNotExist(date: Date) = {
    val emptyDoc = ErrorReport(date, Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq.empty[EffectiveRate],
      Seq.empty[String], Seq.empty[ErrorAction], Seq.empty[ErrorAction], None, None)
    collection.insertOne(emptyDoc).toFuture()
  }

  def addConstantSensor = initBefore(addConstantSensor1) _

  def addConstantSensor1(date: Date, sensorID: String) = {
    val updates = Updates.addToSet("constant", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def addDisconnectedSensor = initBefore(addDisconnectedSensor1) _
  def addDisconnectedSensor1(date: Date, sensorID: String) = {
    val updates = Updates.addToSet("disconnect", sensorID)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }
  def addLessThan90Sensor = initBefore(addLessThan90Sensor1) _

  def addLessThan90Sensor1(date: Date, effectRateList: Seq[EffectiveRate]) = {
    val updates = Updates.combine(
      Updates.addEachToSet("ineffective", effectRateList: _*),
      Updates.set("dailyChecked", true))
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def setConstantRecordTime(date: Date, constantRecordTime:Long) = {
    val updates = Updates.set("constantRecordTime", constantRecordTime)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def setDisconnectRecordTime(date: Date, disconnectRecordTime:Long) = {
    val updates = Updates.set("disconnectRecordTime", disconnectRecordTime)
    val f = collection.updateOne(Filters.equal("_id", date), updates).toFuture()
    f.onFailure(errorHandler())
    f
  }


  def sendEmail(emailTargetList: Seq[EmailTarget]) = {
    val today = DateTime.now.withMillisOfDay(0)
    val f = get(today.toDate)
    f onFailure errorHandler()
    for (reports <- f) yield {
      val subReportList =
        if (reports.isEmpty) {
          Logger.info("Emtpy report!")
          Seq.empty[SensorErrorReport]
        } else {
          val report = reports(0)
          def getSensorErrorReport(title:String, monitorIDs:Seq[String])={
            val monitors = monitorIDs.map(monitorOp.map.get).flatten
            SensorErrorReport(title, monitors = monitors)
          }
          Seq(getSensorErrorReport("充電異常", report.powerError),
            getSensorErrorReport("定值", report.constant),
            getSensorErrorReport("斷線", report.disconnect))
        }
      for (emailTarget <- emailTargetList) {
        Logger.info(s"send report to ${emailTarget.toString}")
        val htmlBody = views.html.errorReport(today.toString("yyyy/MM/dd"), emailTarget.group, subReportList).body
        val mail = Email(
          subject = s"${today.toString("yyyy/MM/dd")}異常設備",
          from = "AirIot <airiot@wecc.com.tw>",
          to = Seq(emailTarget.email),
          bodyHtml = Some(htmlBody)
        )
        try {
          Thread.currentThread().setContextClassLoader(getClass().getClassLoader())
          mailerClient.send(mail)
        } catch {
          case ex: Exception =>
            Logger.error("Failed to send email", ex)
        }
      }
    }
  }

  def get(_id: Date) = {
    val f = collection.find(Filters.equal("_id", _id)).toFuture()
    f.onFailure(errorHandler())
    f
  }

  def get(start:Date, end:Date)= {
    val filter = Filters.and(Filters.gte("_id", start), Filters.lte("_id", end))
    val f = collection.find(filter).toFuture()
    f.onFailure(errorHandler())
    f
  }
}

