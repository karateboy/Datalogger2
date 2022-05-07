package models.sql

import models.{User, UserDB}

import javax.inject.{Inject, Singleton}
@Singleton
class UserOp @Inject()() extends UserDB{
  override def newUser(user: User): Unit = ???

  override def deleteUser(email: String): Unit = ???

  override def updateUser(user: User): Unit = ???

  override def getUserByEmail(email: String): Option[User] = ???

  override def getAllUsers(): Seq[User] = ???

  override def getAdminUsers(): Seq[User] = ???
}
