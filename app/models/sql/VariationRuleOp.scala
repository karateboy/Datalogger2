package models.sql

import com.mongodb.client.result.{DeleteResult, UpdateResult}
import models.{VariationRule, VariationRuleDB, VariationRuleID}
import scalikejdbc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class VariationRuleOp @Inject()(sqlServer: SqlServer) extends VariationRuleDB {
  val tabName = "variationRule"
  private def init()(implicit session: DBSession = AutoSession): Unit = {
    if (!sqlServer.getTables().contains(tabName)) {
      sql"""
        CREATE TABLE [dbo].[variationRule](
	          [monitor] [nvarchar](50) NOT NULL,
	          [monitorType] [nvarchar](50) NOT NULL,
	          [enable] [bit] NOT NULL,
	          [abs] [float] NOT NULL,
        CONSTRAINT [PK_variationRule] PRIMARY KEY CLUSTERED
        (
	        [monitor] ASC,
	        [monitorType] ASC
        )WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON) ON [PRIMARY]
        ) ON [PRIMARY]
           """.execute().apply()
    }
  }
  init()
  def mapper(rs:WrappedResultSet)= {
    VariationRule(VariationRuleID(rs.string("monitor"), rs.string("monitorType")),
      rs.boolean("enable"),
      rs.double("abs"))
  }
  override def getRules(): Future[Seq[VariationRule]] = Future{
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
          Select *
          FROM [dbo].[variationRule]
         """.map(mapper).list().apply()
  }

  override def upsert(rule: VariationRule): Future[UpdateResult] = Future {
    implicit val session: DBSession = AutoSession
    val ret =
      sql"""
        UPDATE [dbo].[variationRule]
          SET [enable] = ${rule.enable}
              ,[abs] = ${rule.abs}
          WHERE [monitor] = ${rule._id.monitor} and [monitorType] = ${rule._id.monitorType}
          IF(@@ROWCOUNT = 0)
            BEGIN
              INSERT INTO [dbo].[variationRule]
            ([monitor]
              ,[monitorType]
              ,[enable]
              ,[abs])
            VALUES
              (${rule._id.monitor}
              ,${rule._id.monitorType}
              ,${rule.enable}
              ,${rule.abs})
            END
        """.update().apply()
    UpdateResult.acknowledged(ret, ret, null)
  }

  override def delete(_id: VariationRuleID): Future[DeleteResult] = Future{
    implicit val session: DBSession = AutoSession
    val ret = sql"""
          DELETE FROM [dbo].[variationRule]
          WHERE [monitor] = ${_id.monitor} and [monitorType] = ${_id.monitorType}
         """.update().apply()
    DeleteResult.acknowledged(ret)
  }
}
