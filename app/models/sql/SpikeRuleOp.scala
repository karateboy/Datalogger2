package models.sql

import models.{SpikeRule, SpikeRuleDB, SpikeRuleID}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class SpikeRuleOp @Inject()() extends SpikeRuleDB{
  override def getRules(): Future[Seq[SpikeRule]] = ???

  override def upsert(rule: SpikeRule): Future[UpdateResult] = ???

  override def delete(_id: SpikeRuleID): Future[DeleteResult] = ???
}
