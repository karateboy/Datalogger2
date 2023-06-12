package controllers

import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import java.io.File
import java.util.Date
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DataLogger @Inject()(alarmRuleDb: AlarmRuleDb, recordDB: RecordDB, excelUtility: ExcelUtility) extends Controller {


  def getAlarmRules: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      for (ret <- alarmRuleDb.getRulesAsync) yield {
        implicit val w1: OWrites[AlarmRule] = Json.writes[AlarmRule]
        Ok(Json.toJson(ret))
      }
  }

  def upsertAlarmRule: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val r1 = Json.reads[AlarmRule]
      val result = request.body.validate[AlarmRule]
      result.fold(err => {
        Logger.error(JsError(err).toString)
        Future.successful(BadRequest(Json.obj("ok" -> false, "msg" -> JsError(err).toString())))
      },
        rule => {
          for (_ <- alarmRuleDb.upsertAsync(rule)) yield
            Ok(Json.obj("ok" -> true))
        })
  }

  def deleteAlarmRule(id: String): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      alarmRuleDb.deleteAsync(id).map { _ =>
        Ok(Json.obj("ok" -> true))
      }
  }

  def getUpsertTemplate: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      Assets.at("/public", "upsertTemplate.xlsx")(request)
  }

  private def upsertMinData(file: File): Boolean = {

    true
  }

  def upsertData: Action[MultipartFormData[play.api.libs.Files.TemporaryFile]] = Security.Authenticated.async(parse.multipartFormData) {
    implicit request =>
      val file = request.body.file("data").get
      val tmpFile = file.ref.file

      val dataMaps = excelUtility.getUpsertMinData(tmpFile)
      if (dataMaps.isEmpty) {
        Future.successful(BadRequest(Json.obj("ok" -> false, "msg" -> "No data")))
      } else {
        val recordLists = dataMaps flatMap { dataMap =>
          if(dataMap.contains("時間") && dataMap("時間").asInstanceOf[Date] != null) {
            val time = dataMap("時間").asInstanceOf[Date]
            val id = RecordListID(time, Monitor.activeId)
            val mtData = (dataMap - "時間") map {
              case (k, v) =>
                val value = v.asInstanceOf[Double]
                MtRecord(k, Option(value), MonitorStatus.NormalStat)
            }
            Some(RecordList(mtData.toList, id))
          } else
             None
        }
        for (_ <- recordDB.upsertManyRecords(recordDB.MinCollection)(recordLists)) yield {
          Ok(Json.obj("ok" -> true))
        }
      }
  }
}