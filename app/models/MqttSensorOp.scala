package models
import models.ModelHelper.errorHandler
import models.mongodb.{GroupOp, MongoDB}
import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.libs.json.Json

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success





