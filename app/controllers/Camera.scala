package controllers

import models.ImageOp
import play.api.mvc.{Action, AnyContent, Controller}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
@Singleton
class Camera @Inject() (imageOp: ImageOp) extends Controller {

  def getImage(id: String): Action[AnyContent] = Security.Authenticated.async {
    import org.mongodb.scala.bson._

    val f = imageOp.getImage(new ObjectId(id))
    for (ret <- f) yield {
      Ok(ret.content).as("image/jpeg")
    }
  }

}
