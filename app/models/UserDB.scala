package models

case class User(_id: String, password: String, name: String,
                isAdmin: Boolean,
                group: Option[String],
                monitorTypeOfInterest: Seq[String],
                windField: Option[Boolean])

trait UserDB {
  val defaultUser = User("sales@wecc.com.tw",
    "abc123",
    "Aragorn",
    isAdmin = true,
    Some(Group.PLATFORM_ADMIN),
    Seq.empty[String],
    windField = Some(true))

  def newUser(user: User): Unit

  def deleteUser(email: String): Unit

  def updateUser(user: User): Unit

  def getUserByEmail(email: String): Option[User]

  def getAllUsers(): Seq[User]

  def getAdminUsers(): Seq[User]
}
