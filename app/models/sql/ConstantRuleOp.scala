package models.sql

import models.{ConstantRule, ConstantRuleDB, ConstantRuleID}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class ConstantRuleOp @Inject()()extends ConstantRuleDB{
  override def getRules(): Future[Seq[ConstantRule]] = ???

  override def upsert(rule: ConstantRule): Future[UpdateResult] = ???

  override def delete(_id: ConstantRuleID): Future[DeleteResult] = ???
}
