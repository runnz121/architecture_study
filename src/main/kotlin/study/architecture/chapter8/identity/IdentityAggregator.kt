package study.architecture.chapter8.identity

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter8.messagestore.Message
import study.architecture.chapter8.messagestore.MessageStore
import study.architecture.chapter8.subscription.Subscription
import java.util.UUID

@Component("ch8IdentityAggregator")
class IdentityAggregator(
    @Qualifier("ch8MessageStore") private val messageStore: MessageStore,
    private val txHandlers: AggregatorTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "identity",
        handlers = mapOf(
            "Registered" to { event -> txHandlers.onRegistered(event) }
        ),
        subscriberId = "aggregators:identity:ch8"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch8AggregatorTransactionalHandlers")
    class AggregatorTransactionalHandlers(
        @Qualifier("ch8UserCredentialRepository") private val userCredentialRepository: UserCredentialRepository
    ) {
        @Transactional
        fun onRegistered(event: Message) {
            val userId = UUID.fromString(extractJsonField(event.data, "userId"))
            val email = extractJsonField(event.data, "email")
            val passwordHash = extractJsonField(event.data, "passwordHash")

            if (userCredentialRepository.existsById(userId)) return

            userCredentialRepository.save(
                UserCredential(
                    userId = userId,
                    email = email,
                    passwordHash = passwordHash,
                    lastIdentityPosition = event.globalPosition
                )
            )
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
