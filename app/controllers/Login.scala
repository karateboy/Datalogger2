package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Logger
import models.{User, UserOp}
case class Credential(user: String, password: String)
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
            val userOpt = userOp.getUserByEmail(crd.user)
            if(userOpt.isEmpty || userOpt.get.password != crd.password) {
              Results.Unauthorized(Json.obj("ok"->false, "msg"->"密碼或帳戶錯誤"))
            } else {
              import Security._
              implicit val writes = Json.writes[User]
              val user = userOpt.get
              val userInfo = UserInfo(user._id, user.name, user.isAdmin)
              Ok(Json.obj("ok"->true, "userInfo"->user)).withSession(Security.setUserinfo(request, userInfo))
            }              
          })
  }

  def isLogin = Security.Authenticated {
    Ok(Json.obj("ok"->true))
  }

  def logout = Action{
    Ok("").withNewSession
  }
}