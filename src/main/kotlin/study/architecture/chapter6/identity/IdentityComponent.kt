package study.architecture.chapter6.identity

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter6.messagestore.Message
import study.architecture.chapter6.messagestore.MessageStore
import study.architecture.chapter6.subscription.Subscription

/**
 * Identity Component: identity:command 카테고리를 구독하여
 * Register command를 처리하고, Registered 이벤트를 발행한다.
 */
@Component("ch6IdentityComponent")
class IdentityComponent(
    @Qualifier("ch6MessageStore") private val messageStore: MessageStore,
    private val txHandlers: IdentityTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "identity:command",
        handlers = mapOf(
            "Register" to { command -> txHandlers.handleRegister(command) }
        ),
        subscriberId = "components:identity"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component
    class IdentityTransactionalHandlers(
        @Qualifier("ch6MessageStore") private val messageStore: MessageStore
    ) {
        @Transactional
        fun handleRegister(command: Message) {
            val userId = extractJsonField(command.data, "userId")
            val email = extractJsonField(command.data, "email")
            val passwordHash = extractJsonField(command.data, "passwordHash")

            val identityStream = "identity-$userId"

            // 멱등성: 이미 Registered 이벤트가 있으면 무시
            val existing = messageStore.read(identityStream)
            val alreadyRegistered = existing.any { it.type == "Registered" }
            if (alreadyRegistered) return

            val eventData = """{"userId":"$userId","email":"$email","passwordHash":"$passwordHash"}"""
            messageStore.write(
                streamName = identityStream,
                type = "Registered",
                data = eventData
            )
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
