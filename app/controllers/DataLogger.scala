package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import java.util.Date
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DataLogger @Inject()(monitorTypeOp: MonitorTypeDB, monitorOp: MonitorDB, instrumentStatusDB: InstrumentStatusDB,
                           instrumentTypeOp: InstrumentTypeOp, monitorStatusOp: MonitorStatusDB,
                           instrumentStatusTypeDB: InstrumentStatusTypeDB,
                           recordDB: RecordDB, calibrationDB: CalibrationDB, alarmDB: AlarmDB) extends Controller {

  import RecordList._

  implicit val latestRecordTimeWrite = Json.writes[LatestRecordTime]
  implicit val CalibrationRead = Json.reads[CalibrationJSON]

  def getRecordRange(tabType: TableType.Value)(monitor: String) = Action.async {
    for (timeOpt <- recordDB.getLatestMonitorRecordTimeAsync(TableType.map(tabType))(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def getHourRecordRange = getRecordRange(TableType.hour) _

  def getMinRecordRange = getRecordRange(TableType.min) _

  def upsertDataRecord(tabType: TableType.Value)(monitor: String): Action[JsValue] = Action(BodyParsers.parse.json) {
    implicit request =>
      val result = request.body.validate[Seq[RecordList]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString().toString()))
      },
        recordLists => {
          val collection = TableType.map(tabType)
          recordDB.upsertManyRecords(collection)(recordLists)
          Ok(Json.obj("ok" -> true))
        })
  }

  def upsertHourRecord = upsertDataRecord(TableType.hour) _

  def upsertMinRecord = upsertDataRecord(TableType.min) _

  def getCalibrationRange(monitor: String) = Action.async {
    for (timeOpt <- calibrationDB.getLatestMonitorRecordTimeAsync(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def toCalibrationItem(json: CalibrationJSON, monitor: String): Calibration =
    Calibration(monitorType = json.monitorType,
      startTime = new DateTime(json.startTime),
      endTime = new DateTime(json.endTime),
      zero_val = json.zero_val,
      span_std = json.span_std,
      span_val = json.span_val,
      monitor = Some(monitor))

  def insertCalibrationRecord(monitor: String) = Action(BodyParsers.parse.json) {
    implicit request =>
      val result = request.body.validate[Seq[CalibrationJSON]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString().toString()))
      },
        calibrations => {
          calibrations.map(json => Calibration(monitorType = json.monitorType,
            startTime = new DateTime(json.startTime),
            endTime = new DateTime(json.endTime),
            zero_val = json.zero_val,
            span_std = json.span_std,
            span_val = json.span_val,
            monitor = Some(monitor))).foreach(calibrationDB.insertFuture(_))

          Ok(Json.obj("ok" -> true))
        })
  }


  def getAlarmRange(monitor: String) = Action.async {
    for (timeOpt <- alarmDB.getLatestMonitorRecordTimeAsync(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def insertAlarmRecord(monitor: String) = Action(BodyParsers.parse.json) {
    implicit request =>
      implicit val ar2JsonRead = Json.reads[Alarm2JSON]

      val result = request.body.validate[Seq[Alarm2JSON]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString().toString()))
      },
        alarm2JsonSeq => {
          val alarms = alarm2JsonSeq.map { json =>
            Alarm(new DateTime(json.time), json.src, json.level, json.info, Some(monitor))
          }
          alarmDB.insertAlarms(alarms)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getInstrumentStatusRange(monitor: String) = Action.async {

    for (timeOpt <- instrumentStatusDB.getLatestMonitorRecordTimeAsync(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def insertInstrumentStatusRecord(monitor: String) = Action(BodyParsers.parse.json) {
    implicit request =>
      import instrumentStatusDB._
      val result = request.body.validate[Seq[InstrumentStatusJSON]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString().toString()))
      },
        isJsonList => {
          isJsonList.map(_.toInstrumentStatus(monitor)).foreach(instrumentStatusDB.log(_))
          Ok(Json.obj("ok" -> true))
        })
  }

  def getInstrumentStatusTypeIds(monitor: String) = Action.async {
    for (instrumentStatusTypeLists <- instrumentStatusTypeDB.getAllInstrumentStatusTypeListAsync(monitor)) yield {
      val instrumentStatusTypeIds = instrumentStatusTypeLists.map { istList =>
        istList.instrumentId + istList.statusTypeSeq.mkString("")
      }.mkString("")
      Ok(Json.toJson(instrumentStatusTypeIds))
    }
  }

  def updateInstrumentStatusTypeMap(monitor: String) = Action.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val r1 = Json.reads[InstrumentStatusType]
      implicit val read = Json.reads[InstrumentStatusTypeMap]
      val result = request.body.validate[Seq[InstrumentStatusTypeMap]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        Future.successful(BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString())))
      },
        istLists => {
          for (_ <- instrumentStatusTypeDB.upsertInstrumentStatusTypeMapAsync(monitor, istLists)) yield
            Ok(Json.obj("ok" -> true))
        })
  }
}