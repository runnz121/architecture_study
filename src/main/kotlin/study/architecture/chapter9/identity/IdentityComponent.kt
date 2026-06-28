package study.architecture.chapter9.identity

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter9.messagestore.Message
import study.architecture.chapter9.messagestore.MessageStore
import study.architecture.chapter9.subscription.Subscription

@Component("ch9IdentityComponent")
class IdentityComponent(
    @Qualifier("ch9MessageStore") private val messageStore: MessageStore,
    private val txHandlers: IdentityTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "identity:command",
        handlers = mapOf(
            "Register" to { command -> txHandlers.handleRegister(command) }
        ),
        subscriberId = "components:identity:ch9"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch9IdentityTransactionalHandlers")
    class IdentityTransactionalHandlers(
        @Qualifier("ch9MessageStore") private val messageStore: MessageStore
    ) {
        @Transactional
        fun handleRegister(command: Message) {
            val userId = extractJsonField(command.data, "userId")
            val email = extractJsonField(command.data, "email")
            val passwordHash = extractJsonField(command.data, "passwordHash")

            val identityStream = "identity-$userId"

            // fetch + projection 기반 멱등성 체크
            val identity = messageStore.fetch(identityStream, IdentityProjection())
            if (identity.isRegistered) return

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
