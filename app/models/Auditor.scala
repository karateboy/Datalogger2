package models

import akka.actor.Actor
import models.mongodb.RecordOp
import play.api.Logger

import javax.inject.Inject

object Auditor {
  case object Audit

  def checkSpikeRule(rules:Seq[SpikeRule], recordOp:RecordDB): Unit ={

  }

  def checkConstantRule(rules:Seq[ConstantRule], recordOp:RecordDB)={

  }

  def checkVariationRule(rules:Seq[VariationRule], recordOp: RecordDB)={

  }
}
import scala.concurrent.ExecutionContext.Implicits.global

class Auditor @Inject()(recordOp:RecordOp,
                        spikeRuleOp: SpikeRuleDB,
                        constantRuleOp: ConstantRuleDB,
                        variationRuleOp: VariationRuleDB) extends Actor {
  import Auditor._
  override def receive: Receive = {
    case Audit =>
      try{
        for(rules<-spikeRuleOp.getRules())
          checkSpikeRule(rules, recordOp)

        for(rules <- constantRuleOp.getRules())
          checkConstantRule(rules, recordOp)

        for(rules<-variationRuleOp.getRules())
          checkVariationRule(rules, recordOp)
      }catch {
        case ex:Throwable=>
          Logger.error("Failed to audit", ex)
      }
  }
}
