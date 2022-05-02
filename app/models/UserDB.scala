package models

import com.google.inject.ImplementedBy
case class User(_id: String, password: String, name: String, isAdmin: Boolean, group: Option[String], monitorTypeOfInterest: Seq[String])

@ImplementedBy(classOf[mongodb.UserOp])
trait UserDB {

  def newUser(user: User)

  def deleteUser(email: String)

  def updateUser(user: User): Unit

  def getUserByEmail(email: String): Option[User]

  def getAllUsers(): Seq[User]

  def getAdminUsers(): Seq[User]
}
