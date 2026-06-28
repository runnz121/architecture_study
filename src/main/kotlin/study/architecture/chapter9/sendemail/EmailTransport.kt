package study.architecture.chapter9.sendemail

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class OutgoingEmail(
    val from: String,
    val to: String,
    val subject: String,
    val text: String,
    val html: String
)

/**
 * Chapter 9 §3: nodemailer의 transport 추상화.
 * 전송 실패 시 SendError를 던진다.
 */
interface EmailTransport {
    fun send(email: OutgoingEmail)
}

/**
 * 책의 nodemailer-pickup-transport 대응 구현.
 * 실제 SMTP로 보내지 않고 이메일을 .eml 파일로 디렉터리에 기록한다 —
 * 개발/테스트 환경에서 결과를 눈으로 확인할 수 있다.
 */
class PickupEmailTransport(private val directory: String) : EmailTransport {

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    override fun send(email: OutgoingEmail) {
        try {
            val dir = File(directory)
            dir.mkdirs()
            val fileName = "${LocalDateTime.now().format(stamp)}-${UUID.randomUUID()}.eml"
            File(dir, fileName).writeText(render(email))
            println("[send-email] wrote ${File(dir, fileName).path} (to=${email.to})")
        } catch (e: Exception) {
            // transport 실패를 SendError로 변환 (potentialError 패턴)
            throw SendError(e.message ?: "email transport failed", e)
        }
    }

    private fun render(email: OutgoingEmail): String = buildString {
        appendLine("From: ${email.from}")
        appendLine("To: ${email.to}")
        appendLine("Subject: ${email.subject}")
        appendLine("Content-Type: multipart/alternative")
        appendLine()
        appendLine("--- text ---")
        appendLine(email.text)
        appendLine()
        appendLine("--- html ---")
        appendLine(email.html)
    }
}
