package controllers

import models._
import play.api.Logger
import play.api.libs.json.{JsError, JsValue, Json}
import play.api.mvc._

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
class RuleController @Inject()(spikeRuleOp: SpikeRuleDB, 
                               constantRuleOp: ConstantRuleDB,
                               variationRuleOp: VariationRuleDB,
                               security: Security,
                               cc: ControllerComponents) extends AbstractController(cc) {
  val logger: Logger = Logger(this.getClass)
  def getSpikeRules(): Action[AnyContent] = security.Authenticated.async {
    for(ret <- spikeRuleOp.getRules()) yield
      Ok(Json.toJson(ret))
  }

  def upsertSpikeRule(): Action[JsValue] = security.Authenticated.async(parse.json){
    implicit request =>
      val ret = request.body.validate[SpikeRule]
      ret.fold(
        error => {
          logger.error(JsError.toJson(error).toString())
          Future{
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        rule => {
          for(_<-spikeRuleOp.upsert(rule)) yield
            Ok(Json.obj("ok" -> true))
        })
  }

  def deleteSpikeRule(monitor:String, monitorType:String): Action[AnyContent] = security.Authenticated.async{
    val id = SpikeRuleID(monitor, monitorType)
    for(_<-spikeRuleOp.delete(id)) yield
      Ok(Json.obj("ok" -> true))
  }

  def getConstantRules(): Action[AnyContent] = security.Authenticated.async {
    for(ret <- constantRuleOp.getRules()) yield
      Ok(Json.toJson(ret))
  }

  def upsertConstantRule(): Action[JsValue] = security.Authenticated.async(parse.json){
    implicit request =>
      val ret = request.body.validate[ConstantRule]
      ret.fold(
        error => {
          logger.error(JsError.toJson(error).toString())
          Future{
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        rule => {
          for(_<-constantRuleOp.upsert(rule)) yield
            Ok(Json.obj("ok" -> true))
        })
  }

  def deleteConstantRule(monitor:String, monitorType:String): Action[AnyContent] = security.Authenticated.async{
    val id = ConstantRuleID(monitor, monitorType)
    for(_<-constantRuleOp.delete(id)) yield
      Ok(Json.obj("ok" -> true))
  }

  def getVariationRules(): Action[AnyContent] = security.Authenticated.async {
    for(ret <- variationRuleOp.getRules()) yield
      Ok(Json.toJson(ret))
  }

  def upsertVariationRule(): Action[JsValue] = security.Authenticated.async(parse.json){
    implicit request =>
      val ret = request.body.validate[VariationRule]
      ret.fold(
        error => {
          logger.error(JsError.toJson(error).toString())
          Future{
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        rule => {
          for(_<-variationRuleOp.upsert(rule)) yield
            Ok(Json.obj("ok" -> true))
        })
  }

  def deleteVariationRule(monitor:String, monitorType:String): Action[AnyContent] = security.Authenticated.async{
    val id = VariationRuleID(monitor, monitorType)
    for(_<-variationRuleOp.delete(id)) yield
      Ok(Json.obj("ok" -> true))
  }
}
