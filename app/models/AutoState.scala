package models

import play.api.Configuration

case class AutoStateConfig(instID:String, state:String, period: String, time:String)
object AutoState {
  def getConfig(configuration: Configuration): Option[Seq[AutoStateConfig]] = {
    for (autoStateConfigList <- configuration.getOptional[Seq[Configuration]]("autoState")) yield {
      try{
        autoStateConfigList.map {
          config =>
            val instID = config.get[String]("instID")
            val state = config.get[String]("state")
            val period = config.get[String]("period")
            val time = config.get[String]("time")
            AutoStateConfig(instID, state, period, time)
        }
      }catch {
        case _:Throwable=>
          Seq.empty[AutoStateConfig]
      }
    }
  }
}
