package controllers

import models.Group
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


  def getUserinfo(request: RequestHeader): Option[UserInfo] = {
    val userInfo =
      for {
        id <- request.session.get(idKey)
        admin <- request.session.get(adminKey)
        name <- request.session.get(nameKey)
        group <- request.session.get(groupKey)
      } yield
        UserInfo(id, name, group, admin.toBoolean)

    Some(userInfo.getOrElse(UserInfo("sales@wecc.com.tw", "Aragorn", Group.PLATFORM_ADMIN, isAdmin = true)))
  }

  //def invokeBlock[A](request: Request[A], block: (AuthenticatedRequest[A]) => Future[Result]) = {
  //  AuthenticatedBuilder(getUserinfo _, onUnauthorized)
  //})

  //def isAuthenticated(f: => String => Request[AnyContent] => Result) = {
  //  Authenticated(getUserinfo, onUnauthorized) { user =>
  //    Action(request => f(user)(request))
  //  }
  // }

  def setUserinfo[A](request: Request[A], userInfo: UserInfo): (String, String) = {
      request.session +
      idKey -> userInfo.id +
      adminKey -> userInfo.isAdmin.toString +
      nameKey -> userInfo.name +
      groupKey -> userInfo.group
  }

  def getUserInfo[A]()(implicit request: Request[A]): Option[UserInfo] = {
    getUserinfo(request)
  }

  def Authenticated: AuthenticatedBuilder[UserInfo]
  = new AuthenticatedBuilder(userinfo = getUserinfo, defaultParser = parse.defaultBodyParser)
}