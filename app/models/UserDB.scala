package models

case class User(_id: String, password: String, name: String, isAdmin: Boolean, group: Option[String], monitorTypeOfInterest: Seq[String])

trait UserDB {
  val defaultUser = User("sales@wecc.com.tw", "abc123", "Aragorn", true, Some(Group.PLATFORM_ADMIN), Seq.empty[String])

  def newUser(user: User)

  def deleteUser(email: String)

  def updateUser(user: User): Unit

  def getUserByEmail(email: String): Option[User]

  def getAllUsers(): Seq[User]

  def getAdminUsers(): Seq[User]
}
