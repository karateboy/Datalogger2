package controllers

import play.api.Logger
import play.api.mvc.Security._
import play.api.mvc._

import javax.inject.Inject
import scala.concurrent._

case class UserInfo(id: String, name: String, group: String, isAdmin: Boolean)

class Security @Inject()(cc: ControllerComponents, implicit val ec: ExecutionContext) extends AbstractController(cc) {
  val idKey = "ID"
  val nameKey = "Name"
  val adminKey = "Admin"
  val groupKey = "Group"

  val logger: Logger = Logger(this.getClass)

  def getUserinfo(request: RequestHeader): Option[UserInfo] = {
    logger.debug(s"session = ${request.session}")
    for {
      id <- request.session.get(idKey)
      admin <- request.session.get(adminKey)
      name <- request.session.get(nameKey)
      group <- request.session.get(groupKey)
    } yield
      UserInfo(id, name, group, admin.toBoolean)
  }


  def setUserinfo[A](request: Request[A], userInfo: UserInfo) = {
    logger.debug(s"setUserinfo $userInfo")
    List(idKey -> userInfo.id,
      adminKey -> userInfo.isAdmin.toString,
      nameKey -> userInfo.name,
      groupKey -> userInfo.group)
  }

  def getUserInfo[A]()(implicit request: Request[A]): Option[UserInfo] = {
    getUserinfo(request)
  }

  def Authenticated: AuthenticatedBuilder[UserInfo]
  = new AuthenticatedBuilder(userinfo = getUserinfo, defaultParser = parse.defaultBodyParser)
}