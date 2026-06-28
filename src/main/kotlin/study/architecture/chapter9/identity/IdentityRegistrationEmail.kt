package study.architecture.chapter9.identity

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter9.messagestore.Message
import study.architecture.chapter9.messagestore.MessageStore
import study.architecture.chapter9.messagestore.streamNameToId
import study.architecture.chapter9.subscription.Subscription

/**
 * Chapter 9 §6~7: 등록 프로세스에 이메일 추가 (Orchestration)
 *
 * identity Component가 send-email Component를 orchestration한다:
 *  1. 자신의 Registered 이벤트를 관찰 -> sendEmail:command 스트림에 Send command 작성
 *  2. send-email이 발생시킨 Sent 이벤트를 관찰(originStreamName=identity 로 필터)
 *     -> 자신의 identity 스트림에 RegistrationEmailSent 이벤트 기록
 */
@Component("ch9IdentityRegistrationEmail")
class IdentityRegistrationEmail(
    @Qualifier("ch9MessageStore") private val messageStore: MessageStore,
    private val txHandlers: RegistrationEmailTransactionalHandlers
) {
    // (1) identity의 Registered 이벤트 -> Send command
    private val registeredSubscription = Subscription(
        messageStore = messageStore,
        streamName = "identity",
        handlers = mapOf(
            "Registered" to { event -> txHandlers.handleRegistered(event) }
        ),
        subscriberId = "components:identity:registrationEmail:ch9"
    )

    // (2) send-email의 Sent 이벤트 -> RegistrationEmailSent (identity가 보낸 것만 필터)
    private val sentSubscription = Subscription(
        messageStore = messageStore,
        streamName = "sendEmail",
        handlers = mapOf(
            "Sent" to { event -> txHandlers.handleSent(event) }
        ),
        originStreamName = "identity",
        subscriberId = "components:identity:sendEmailEvents:ch9"
    )

    @PostConstruct
    fun start() {
        registeredSubscription.start()
        sentSubscription.start()
    }

    @PreDestroy
    fun stop() {
        registeredSubscription.stop()
        sentSubscription.stop()
    }

    @Component("ch9RegistrationEmailTransactionalHandlers")
    class RegistrationEmailTransactionalHandlers(
        @Qualifier("ch9MessageStore") private val messageStore: MessageStore
    ) {
        /**
         * loadIdentity -> ensureRegistrationEmailNotSent -> renderRegistrationEmail -> writeSendCommand
         * 멱등성: registrationEmailSent 이면 no-op
         */
        @Transactional
        fun handleRegistered(event: Message) {
            val identityId = extractJsonField(event.data, "userId")
            val identityStream = "identity-$identityId"

            val identity = messageStore.fetch(identityStream, IdentityProjection())
            if (identity.registrationEmailSent) return
            val emailAddress = identity.email ?: return

            val rendered = RegistrationEmail.render(emailAddress)
            // 결정적 UUID v5 — 같은 이메일은 항상 같은 emailId
            val emailId = RegistrationEmail.emailId(emailAddress)

            val data = """{"emailId":"$emailId",""" +
                """"to":"$emailAddress",""" +
                """"subject":"${rendered.subject}",""" +
                """"text":"${rendered.text}",""" +
                """"html":"${rendered.html}"}"""

            // originStreamName 으로 어떤 Component가 이 전송을 요청했는지 전파
            val metadata = """{"originStreamName":"$identityStream",""" +
                """"traceId":"${extractJsonField(event.metadata, "traceId")}",""" +
                """"userId":"${extractJsonField(event.metadata, "userId")}"}"""

            messageStore.write(
                streamName = "sendEmail:command-$emailId",
                type = "Send",
                data = data,
                metadata = metadata
            )
        }

        /**
         * Sent 이벤트의 originStreamName(identity-<id>)에서 identityId를 복원하고
         * 해당 identity 스트림에 RegistrationEmailSent를 기록한다.
         * 멱등성: 이미 기록됐으면 no-op
         */
        @Transactional
        fun handleSent(event: Message) {
            val originStreamName = extractJsonField(event.metadata, "originStreamName")
            val identityId = streamNameToId(originStreamName)
            if (identityId.isEmpty()) return

            val identityStream = "identity-$identityId"
            val identity = messageStore.fetch(identityStream, IdentityProjection())
            if (identity.registrationEmailSent) return

            messageStore.write(
                streamName = identityStream,
                type = "RegistrationEmailSent",
                data = """{"userId":"$identityId"}"""
            )
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
