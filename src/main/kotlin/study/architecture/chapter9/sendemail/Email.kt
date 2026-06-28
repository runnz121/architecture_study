package study.architecture.chapter9.sendemail

import study.architecture.chapter9.messagestore.Message
import study.architecture.chapter9.messagestore.Projection

/**
 * Chapter 9 §4-2: 이메일 스트림(sendEmail-<emailId>)의 projection.
 * Sent 이벤트가 한 번이라도 있으면 isSent=true 가 되어 멱등성 체크에 쓰인다.
 */
data class Email(
    val isSent: Boolean = false
)

class EmailProjection : Projection<Email> {
    override fun init() = Email()

    override fun apply(entity: Email, event: Message): Email = when (event.type) {
        "Sent" -> entity.copy(isSent = true)
        else -> entity
    }
}
