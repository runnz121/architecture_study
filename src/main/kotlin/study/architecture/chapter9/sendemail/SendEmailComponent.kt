package study.architecture.chapter9.sendemail

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter9.messagestore.Message
import study.architecture.chapter9.messagestore.MessageStore
import study.architecture.chapter9.subscription.Subscription

/**
 * Chapter 9 §3~4: send-email Component
 *
 * 이메일 전송만 담당하는 범용 Component.
 *   Send (command) -> 이메일 전송 -> Sent (event) / Failed (event)
 *
 * sendEmail:command 스트림을 구독하여 Send command를 처리한다.
 */
@Component("ch9SendEmailComponent")
class SendEmailComponent(
    @Qualifier("ch9MessageStore") private val messageStore: MessageStore,
    private val txHandlers: SendEmailTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "sendEmail:command",
        handlers = mapOf(
            "Send" to { command -> txHandlers.handleSend(command) }
        ),
        subscriberId = "components:send-email:ch9"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch9SendEmailTransactionalHandlers")
    class SendEmailTransactionalHandlers(
        @Qualifier("ch9MessageStore") private val messageStore: MessageStore,
        @Qualifier("ch9EmailTransport") private val transport: EmailTransport,
        @Value("\${app.email.sender:no-reply@video-tutorials.example.com}")
        private val systemSenderEmailAddress: String
    ) {
        /**
         * 책의 Promise chain:
         *   loadEmail -> ensureEmailHasNotBeenSent -> sendEmail -> writeSentEvent
         *   .catch(AlreadySent -> no-op)
         *   .catch(SendError -> writeFailedEvent)
         */
        @Transactional
        fun handleSend(command: Message) {
            val emailId = extractJsonField(command.data, "emailId")
            val streamName = "sendEmail-$emailId"

            // loadEmail + ensureEmailHasNotBeenSent (멱등성)
            val email = messageStore.fetch(streamName, EmailProjection())
            if (email.isSent) return // AlreadySent -> no-op

            // sendEmail — 외부 효과. 트랜잭션으로 보호되지 않으므로 실패 시 Sent를 남기지 않는다.
            // (전송 후 Sent 기록 전에 크래시되면 중복 전송 가능 — 비즈니스가 허용한 at-least-once)
            try {
                transport.send(
                    OutgoingEmail(
                        from = systemSenderEmailAddress,
                        to = extractJsonField(command.data, "to"),
                        subject = extractJsonField(command.data, "subject"),
                        text = extractJsonField(command.data, "text"),
                        html = extractJsonField(command.data, "html")
                    )
                )
            } catch (e: SendError) {
                writeFailedEvent(command, e)
                return
            }

            writeSentEvent(command)
        }

        private fun writeSentEvent(command: Message) {
            val emailId = extractJsonField(command.data, "emailId")
            messageStore.write(
                streamName = "sendEmail-$emailId",
                type = "Sent",
                data = command.data,
                // originStreamName/traceId/userId 를 그대로 전파
                metadata = command.metadata
            )
        }

        private fun writeFailedEvent(command: Message, error: SendError) {
            val emailId = extractJsonField(command.data, "emailId")
            val to = extractJsonField(command.data, "to")
            val subject = extractJsonField(command.data, "subject")
            val reason = (error.message ?: "unknown").replace("\"", "'")
            val data = """{"emailId":"$emailId","to":"$to","subject":"$subject","reason":"$reason"}"""
            messageStore.write(
                streamName = "sendEmail-$emailId",
                type = "Failed",
                data = data,
                metadata = command.metadata
            )
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
