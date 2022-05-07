package models.sql

import models.{EmailTarget, EmailTargetDB}
import org.mongodb.scala.BulkWriteResult
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class EmailTargetOp @Inject()()extends EmailTargetDB{
  override def upsert(et: EmailTarget): Future[UpdateResult] = ???

  override def get(_id: String): Future[EmailTarget] = ???

  override def upsertMany(etList: Seq[EmailTarget]): Future[BulkWriteResult] = ???

  override def getList(): Future[Seq[EmailTarget]] = ???

  override def delete(_id: String): Future[DeleteResult] = ???

  override def deleteAll(): Future[DeleteResult] = ???
}
