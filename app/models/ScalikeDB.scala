package models

import play.api._

import javax.inject._

@Singleton
class ScalikeDB @Inject()() {
  import scalikejdbc.config._

  DBs.setup()

}