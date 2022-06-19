package models
import play.api.libs.json._
import models.ModelHelper._
import models.mongodb.MongoDB

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import org.mongodb.scala.model._
import org.mongodb.scala.bson._
import org.mongodb.scala.result.UpdateResult
import play.api.Logger

import java.time.Instant
import java.util.Date
import javax.inject._
import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.Future

case class LogoImage(filename:String, image:Array[Byte])
