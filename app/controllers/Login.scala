package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Logger
import models.{User, UserOp}
case class Credential(account: String, password: String)
import javax.inject._

/**
 * @author user
 */
class Login @Inject()
(userOp: UserOp)
  extends Controller {
  implicit val credentialReads = Json.reads[Credential]    
        
  def authenticate = Action(BodyParsers.parse.json){
    implicit request =>
      val credentail = request.body.validate[Credential]      
      credentail.fold(
          error=>{
            BadRequest(Json.obj("ok"->false, "msg"->JsError.toJson(error)))
          }, 
          crd=>{
            val user = userOp.getUserByEmail(crd.account)
            if(user.password != crd.password)
              Ok(Json.obj("ok"->false, "msg"->"密碼或帳戶錯誤"))
            else {
              import Security._
              val userInfo = UserInfo(user.email, user.name, user.isAdmin)
              Ok(Json.obj("ok"->true)).withSession(Security.setUserinfo(request, userInfo))              
            }              
          })
  }

  def logout = Action{
    Ok("").withNewSession
  }
}