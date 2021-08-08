package models

case class ModbusConfig(slaveID: Option[Int], monitorTypes: Option[List[String]])
case class ModelConfig(model: String, monitorTypeIDs: List[String])
case class ModbusModelConfig(model: String, mtAddrMap: Map[String, Int])
case class InputReg(addr: Int, desc: String, unit: String)
case class HoldingReg(addr: Int, desc: String, unit: String)
case class DiscreteInputReg(addr: Int, desc: String)
case class CoilReg(addr: Int, desc: String)
case class ModelReg(inputRegs: List[InputReg], holdingRegs: List[HoldingReg],
                    modeRegs: List[DiscreteInputReg], warnRegs: List[DiscreteInputReg], coilRegs: List[CoilReg])
case class ModelRegValue(inputRegs: List[(InstrumentStatusType, Float)], holdingRegs: List[(InstrumentStatusType, Float)],
                         modeRegs: List[(InstrumentStatusType, Boolean)], warnRegs: List[(InstrumentStatusType, Boolean)])