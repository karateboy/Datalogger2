package controllers

import models.{Ability, GroupDB, User, UserDB}
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._

case class Credential(user: String, password: String)
import models.Group

import javax.inject._

case class UserData(user: User, group: Group)

/**
 * @author user
 */
class Login @Inject()(userOp: UserDB,
                      groupOp: GroupDB,
                      configuration: Configuration,
                      security: Security,
                      cc: ControllerComponents) extends AbstractController(cc) {
  implicit val credentialReads: Reads[Credential] = Json.reads[Credential]
  val bypassLogin: Boolean = configuration.getOptional[Boolean]("logger.bypassLogin").getOrElse(false)

  implicit val writes: OWrites[User] = Json.writes[User]
  implicit val w3: OWrites[Ability] = Json.writes[Ability]
  implicit val w1: OWrites[Group] = Json.writes[Group]
  implicit val w2: OWrites[UserData] = Json.writes[UserData]

  def authenticate: Action[JsValue] = Action(parse.json) {
    implicit request =>
      if (bypassLogin) {
        val user = userOp.defaultUser
        val userGroup = {
          user.group.getOrElse({
            if (user.isAdmin)
              Group.PLATFORM_ADMIN
            else
              Group.PLATFORM_USER
          })
        }
        val group = groupOp.getGroupByID(userGroup).get
        val userInfo = UserInfo(user._id, user.name, userGroup, group.admin)
        Ok(Json.obj("ok" -> true, "userData" -> UserData(user, group))).withSession(security.setUserinfo(request, userInfo): _*)
      } else {
        val credential = request.body.validate[Credential]
        credential.fold(
          error => {
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error)))
          },
          crd => {
            val userOpt = userOp.getUserByEmail(crd.user)
            if (userOpt.isEmpty || userOpt.get.password != crd.password) {
              Results.Unauthorized(Json.obj("ok" -> false, "msg" -> "密碼或帳戶錯誤"))
            } else {
              val user = userOpt.get
              val userGroup = {
                user.group.getOrElse({
                  if (user.isAdmin)
                    Group.PLATFORM_ADMIN
                  else
                    Group.PLATFORM_USER
                })
              }
              val group = groupOp.getGroupByID(userGroup).get
              val userInfo = UserInfo(user._id, user.name, userGroup, group.admin)
              Ok(Json.obj("ok" -> true, "userData" -> UserData(user, group))).withSession(security.setUserinfo(request, userInfo): _*)
            }
          })
      }
  }

  def isLogin: Action[AnyContent] = security.Authenticated {
    Ok(Json.obj("ok" -> true))
  }

  def getUserInfo: Action[AnyContent] = security.Authenticated {
    implicit request =>
      val userInfo = security.getUserinfo(request).get
      val userOpt = userOp.getUserByEmail(userInfo.id)
      if (userOpt.isEmpty) {
        Results.Unauthorized(Json.obj("ok" -> false, "msg" -> "使用者不存在"))
      } else {
        val user = userOpt.get
        val userGroup = {
          user.group.getOrElse({
            if (user.isAdmin)
              Group.PLATFORM_ADMIN
            else
              Group.PLATFORM_USER
          })
        }
        val group = groupOp.getGroupByID(userGroup).get
        Ok(Json.obj("ok" -> true, "userData" -> UserData(user, group)))
      }
  }

  def logout: Action[AnyContent] = Action {
    Ok("").withNewSession
  }
}