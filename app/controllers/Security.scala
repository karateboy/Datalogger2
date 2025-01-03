package controllers

import models.{Group, LoggerConfig}
import play.api.mvc.Security._
import play.api.mvc._

class AuthenticatedRequest[A](val userinfo: String, request: Request[A]) extends WrappedRequest[A](request)

case class UserInfo(id: String, name: String, group: String, isAdmin: Boolean)

object Security {
  val idKey = "ID"
  val nameKey = "Name"
  val adminKey = "Admin"
  val groupKey = "Group"


  def getUserinfo(request: RequestHeader): Option[UserInfo] = {
    val defaultUser = UserInfo("sales@wecc.com.tw", "Aragorn", Group.PLATFORM_ADMIN, isAdmin = true)
    if(LoggerConfig.config.bypassLogin)
      return Some(defaultUser)

    val userInfo =
      for {
        id <- request.session.get(idKey)
        admin <- request.session.get(adminKey)
        name <- request.session.get(nameKey)
        group <- request.session.get(groupKey)
      } yield
        UserInfo(id, name, group, admin.toBoolean)

    userInfo
  }

  private def onUnauthorized(request: RequestHeader): Results.Status = Results.Unauthorized


  //def invokeBlock[A](request: Request[A], block: (AuthenticatedRequest[A]) => Future[Result]) = {
  //  AuthenticatedBuilder(getUserinfo _, onUnauthorized)
  //})

  //def isAuthenticated(f: => String => Request[AnyContent] => Result) = {
  //  Authenticated(getUserinfo, onUnauthorized) { user =>
  //    Action(request => f(user)(request))
  //  }
  // }

  def setUserinfo[A](request: Request[A], userInfo: UserInfo): Session = {
    request.session +
      (idKey -> userInfo.id) +
      (adminKey -> userInfo.isAdmin.toString) +
      (nameKey -> userInfo.name) +
      (groupKey -> userInfo.group)
  }

  def getUserInfo[A]()(implicit request: Request[A]): Option[UserInfo] = getUserinfo(request)

  def Authenticated = new AuthenticatedBuilder(getUserinfo, onUnauthorized)
}