package models

import play.api.Logger
import play.api.libs.mailer.{Email, MailerClient}
object AlertEmailSender {
  def sendAlertMail(mailerClient: MailerClient)(subject: String, emails: Seq[String], msg: String): Unit = {
    val mail = Email(
      subject = subject,
      from = LoggerConfig.config.fromEmail,
      to = emails,
      bodyHtml = Some(msg)
    )
    try {
      Thread.currentThread().setContextClassLoader(getClass.getClassLoader)
      mailerClient.send(mail)
    } catch {
      case ex: Exception =>
        Logger.error("Failed to send email", ex)
    }
  }
}
