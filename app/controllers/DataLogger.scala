package controllers

import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DataLogger @Inject()(alarmRuleDb: AlarmRuleDb) extends Controller {


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
}