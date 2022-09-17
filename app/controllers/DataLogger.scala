package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import java.time.Instant
import java.util.Date
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DataLogger @Inject()(monitorTypeDB: MonitorTypeDB, monitorOp: MonitorDB, instrumentStatusDB: InstrumentStatusDB,
                           instrumentTypeOp: InstrumentTypeOp, monitorStatusOp: MonitorStatusDB,
                           instrumentStatusTypeDB: InstrumentStatusTypeDB,
                           recordDB: RecordDB, calibrationDB: CalibrationDB, alarmDB: AlarmDB) extends Controller {

  import RecordList._

  implicit val latestRecordTimeWrite = Json.writes[LatestRecordTime]
  implicit val CalibrationRead = Json.reads[CalibrationJSON]

  def getRecordRange(tabType: TableType.Value)(monitor: String) = Action.async {
    for (timeOpt <- recordDB.getLatestMonitorRecordTimeAsync(TableType.mapCollection(tabType))(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def getHourRecordRange: String => Action[AnyContent] = getRecordRange(TableType.hour) _

  def getMinRecordRange: String => Action[AnyContent] = getRecordRange(TableType.min) _

  def upsertDataRecord(tabType: TableType.Value)(monitor: String): Action[JsValue] = Action(BodyParsers.parse.json) {
    implicit request =>
      val result = request.body.validate[Seq[RecordList]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString().toString()))
      },
        recordLists => {
          monitorOp.ensureMonitor(monitor)
          val monitorTypes = recordLists.map(_.mtMap.keySet).foldLeft(Set.empty[String])((a,b)=>a ++ b)
          monitorTypes.foreach(mt=>{
            monitorTypeDB.ensureMonitorType(mt)
            recordDB.ensureMonitorType(mt)
          })

          val collection = TableType.mapCollection(tabType)
          recordDB.upsertManyRecords(collection)(recordLists)
          Ok(Json.obj("ok" -> true))
        })
  }

  def upsertHourRecord: String => Action[JsValue] = upsertDataRecord(TableType.hour) _

  def upsertMinRecord: String => Action[JsValue] = upsertDataRecord(TableType.min) _

  def getCalibrationRange(monitor: String) = Action.async {
    for (timeOpt <- calibrationDB.getLatestMonitorRecordTimeAsync(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def insertCalibrationRecord(monitor: String): Action[JsValue] = Action(BodyParsers.parse.json) {
    implicit request =>
      val result = request.body.validate[Seq[CalibrationJSON]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString()))
      },
        calibrations => {
          monitorOp.ensureMonitor(monitor)
          val monitorTypes = calibrations.map(_.monitorType).toSet
          monitorTypes.foreach(mt => {
            monitorTypeDB.ensureMonitorType(mt)
            recordDB.ensureMonitorType(mt)
          })
          calibrations.map(json => Calibration(monitorType = json.monitorType,
            startTime = Date.from(Instant.ofEpochMilli(json.startTime)),
            endTime = Date.from(Instant.ofEpochMilli(json.endTime)),
            zero_val = json.zero_val,
            span_std = json.span_std,
            span_val = json.span_val,
            monitor = monitor)).foreach(calibrationDB.insertFuture)

          Ok(Json.obj("ok" -> true))
        })
  }


  def getAlarmRange(monitor: String): Action[AnyContent] = Action.async {
    for (timeOpt <- alarmDB.getLatestMonitorRecordTimeAsync(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def insertAlarmRecord(monitor: String): Action[JsValue] = Action(BodyParsers.parse.json) {
    implicit request =>
      implicit val ar2JsonRead = Json.reads[Alarm2JSON]

      val result = request.body.validate[Seq[Alarm2JSON]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString().toString()))
      },
        alarm2JsonSeq => {
          monitorOp.ensureMonitor(monitor)
          val alarms = alarm2JsonSeq.map { json =>
            Alarm(Date.from(Instant.ofEpochMilli(json.time)), json.src, json.level, json.info, monitor)
          }
          alarmDB.insertAlarms(alarms)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getInstrumentStatusRange(monitor: String): Action[AnyContent] = Action.async {

    for (timeOpt <- instrumentStatusDB.getLatestMonitorRecordTimeAsync(monitor)) yield {
      val latestRecordTime = timeOpt.map {
        time =>
          LatestRecordTime(time.getMillis)
      }.getOrElse(LatestRecordTime(0))
      Ok(Json.toJson(latestRecordTime))
    }
  }

  def insertInstrumentStatusRecord(monitor: String): Action[JsValue] = Action(BodyParsers.parse.json) {
    implicit request =>
      monitorOp.ensureMonitor(monitor)
      import instrumentStatusDB._
      val result = request.body.validate[Seq[InstrumentStatusJSON]]
      result.fold(err => {
        Logger.error(JsError(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString().toString()))
      },
        isJsonList => {
          monitorOp.ensureMonitor(monitor)
          isJsonList.map(_.toInstrumentStatus(monitor)).foreach(instrumentStatusDB.log(_))
          Ok(Json.obj("ok" -> true))
        })
  }

  def getInstrumentStatusTypeIds(monitor: String): Action[AnyContent] = Action.async {
    for (instrumentStatusTypeLists <- instrumentStatusTypeDB.getAllInstrumentStatusTypeListAsync(monitor)) yield {
      val instrumentStatusTypeIds = instrumentStatusTypeLists.map { istList =>
        istList.instrumentId + istList.statusTypeSeq.mkString("")
      }.mkString("")
      Ok(Json.toJson(instrumentStatusTypeIds))
    }
  }

  def updateInstrumentStatusTypeMap(monitor: String): Action[JsValue] = Action.async(BodyParsers.parse.json) {
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