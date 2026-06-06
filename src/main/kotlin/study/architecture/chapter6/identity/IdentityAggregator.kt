package study.architecture.chapter6.identity

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter6.messagestore.Message
import study.architecture.chapter6.messagestore.MessageStore
import study.architecture.chapter6.subscription.Subscription
import java.util.UUID

/**
 * Identity Aggregator: identity 카테고리를 구독하여
 * Registered 이벤트를 user_credentials 테이블에 반영한다.
 */
@Component("ch6IdentityAggregator")
class IdentityAggregator(
    @Qualifier("ch6MessageStore") private val messageStore: MessageStore,
    private val txHandlers: AggregatorTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "identity",
        handlers = mapOf(
            "Registered" to { event -> txHandlers.onRegistered(event) }
        ),
        subscriberId = "aggregators:identity"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component
    class AggregatorTransactionalHandlers(
        private val userCredentialRepository: UserCredentialRepository
    ) {
        @Transactional
        fun onRegistered(event: Message) {
            val userId = UUID.fromString(extractJsonField(event.data, "userId"))
            val email = extractJsonField(event.data, "email")
            val passwordHash = extractJsonField(event.data, "passwordHash")

            // 멱등성: 이미 존재하면 무시
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
