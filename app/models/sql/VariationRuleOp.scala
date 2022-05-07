package models.sql

import models.{VariationRule, VariationRuleDB, VariationRuleID}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class VariationRuleOp @Inject()() extends VariationRuleDB {
  override def getRules(): Future[Seq[VariationRule]] = ???

  override def upsert(rule: VariationRule): Future[UpdateResult] = ???

  override def delete(_id: VariationRuleID): Future[DeleteResult] = ???
}
