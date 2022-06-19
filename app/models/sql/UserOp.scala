package models.sql

import models.{User, UserDB}
import scalikejdbc._

import javax.inject.{Inject, Singleton}

@Singleton
class UserOp @Inject()(sqlServer: SqlServer) extends UserDB{
  val tabName = "user"
  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
        CREATE TABLE [dbo].[user](
	        [id] [nvarchar](50) NOT NULL,
	        [password] [nvarchar](50) NOT NULL,
	        [name] [nvarchar](50) NOT NULL,
	        [isAdmin] [bit] NOT NULL,
	        [group] [nvarchar](50) NULL,
	        [monitorTypeOfInterest] [nvarchar](1024) NOT NULL,
        CONSTRAINT [PK_user] PRIMARY KEY CLUSTERED
        (
	        [id] ASC
        )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON, OPTIMIZE_FOR_SEQUENTIAL_KEY = OFF) ON [PRIMARY]
      ) ON [PRIMARY]
           """.execute().apply()
      newUser(defaultUser)
    }
  }
  init()

  def mapper(rs:WrappedResultSet): User = {
    User(_id = rs.string("id"),
      password = rs.string("password"),
      name = rs.string("name"),
      isAdmin = rs.boolean("isAdmin"),
      group = rs.stringOpt("group"),
      monitorTypeOfInterest = rs.string("monitorTypeOfInterest").split(",").filter(_.nonEmpty)
    )
  }
  override def newUser(user: User): Unit = {
    implicit val session: DBSession = AutoSession
    sql"""
        INSERT INTO [dbo].[user]
           ([id]
           ,[password]
           ,[name]
           ,[isAdmin]
           ,[group]
           ,[monitorTypeOfInterest])
     VALUES
           (${user._id}
           ,${user.password}
           ,${user.name}
           ,${user.isAdmin}
           ,${user.group}
           ,${user.monitorTypeOfInterest.mkString(",")})
         """.update().apply()
  }

  override def deleteUser(email: String): Unit = {
    implicit val session: DBSession = AutoSession
    sql"""
         DELETE FROM [dbo].[user]
        WHERE [id] = ${email}
         """.update().apply()
  }

  override def updateUser(user: User): Unit = {
    implicit val session: DBSession = AutoSession
    if(user.password.nonEmpty)
    sql"""
         UPDATE [dbo].[user]
          SET [password] = ${user.password}
              ,[name] = ${user.name}
              ,[isAdmin] = ${user.isAdmin}
              ,[group] = ${user.group}
              ,[monitorTypeOfInterest] = ${user.monitorTypeOfInterest.mkString(",")}
          WHERE [id] = ${user._id}
         """.update().apply()
    else
      sql"""
         UPDATE [dbo].[user]
          SET [name] = ${user.name}
              ,[isAdmin] = ${user.isAdmin}
              ,[group] = ${user.group}
              ,[monitorTypeOfInterest] = ${user.monitorTypeOfInterest.mkString(",")}
          WHERE [id] = ${user._id}
         """.update().apply()
  }

  override def getUserByEmail(email: String): Option[User] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         SELECT *
         From [dbo].[user]
         WHERE [id] = ${email}
         """.map(mapper).first().apply()
  }

  override def getAllUsers(): Seq[User] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         SELECT *
         From [dbo].[user]
         """.map(mapper).list().apply()
  }

  override def getAdminUsers(): Seq[User] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
         SELECT *
         From [dbo].[user]
         Where isAdmin = ${true}
         """.map(mapper).list().apply()
  }
}
