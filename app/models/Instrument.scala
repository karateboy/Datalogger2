package models

import models.Protocol.ProtocolParam
case class InstrumentInfo(_id: String, instType: String, state: String,
                          protocol: String, protocolParam: String, monitorTypes: String,
                          calibrationTime: Option[String], inst: Instrument)

case class InstrumentStatusType(key: String, addr: Int, desc: String, unit: String, prec: Option[Int] = None)

case class Instrument(_id: String, instType: String,
                      protocol: ProtocolParam, param: String, active: Boolean,
                      state: String,
                      statusType: Option[List[InstrumentStatusType]]) {

  def replaceParam(newParam: String): Instrument = {
    Instrument(_id, instType, protocol, newParam, active, state, statusType)
  }
}

