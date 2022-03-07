package models

import akka.actor.Actor
import models.Auditor.Audit
import play.api.Logger

import javax.inject.Inject

object Auditor {
  case object Audit

  def checkSpikeRule(rules:Seq[SpikeRule], recordOp:RecordOp): Unit ={

  }

  def checkConstantRule(rules:Seq[ConstantRule], recordOp:RecordOp)={

  }

  def checkVariationRule(rules:Seq[VariationRule], recordOp: RecordOp)={

  }
}
import scala.concurrent.ExecutionContext.Implicits.global

class Auditor @Inject()(recordOp:RecordOp,
                        spikeRuleOp: SpikeRuleOp,
                        constantRuleOp: ConstantRuleOp,
                        variationRuleOp: VariationRuleOp) extends Actor {
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
