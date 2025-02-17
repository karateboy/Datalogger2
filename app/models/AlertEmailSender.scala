package models

import play.api.Logger
import play.api.libs.mailer.{Email, MailerClient}
object AlertEmailSender {
  val logger: Logger = Logger(this.getClass)
  def sendAlertMail(mailerClient: MailerClient)(subject: String, emails: Seq[String], msg: String): Unit = {
    val mail = Email(
      subject = subject,
      from = LoggerConfig.config.fromEmail,
      to = emails,
      bodyHtml = Some(msg)
    )
    try {
      mailerClient.send(mail)
    } catch {
      case ex: Exception =>
        logger.error("Failed to send email", ex)
    }
  }
}
