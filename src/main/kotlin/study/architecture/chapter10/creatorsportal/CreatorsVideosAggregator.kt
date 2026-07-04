package study.architecture.chapter10.creatorsportal

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter10.messagestore.Message
import study.architecture.chapter10.messagestore.MessageStore
import study.architecture.chapter10.subscription.Subscription
import java.util.UUID

/**
 * Chapter 10: "Aggregating Is Also for Other Teams"
 *
 * Creators Portal이 조회하는 View Data(ch10_creator_videos)를 채우는 Aggregator.
 * videoPublishing 카테고리의 VideoPublished / VideoPublishingFailed 이벤트를 관찰한다.
 * Component(작업 수행)와 별개의 조각이며, message contract로만 소통한다.
 */
@Component("ch10CreatorsVideosAggregator")
class CreatorsVideosAggregator(
    @Qualifier("ch10MessageStore") private val messageStore: MessageStore,
    private val txHandlers: CreatorsVideosTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "videoPublishing",
        handlers = mapOf(
            "VideoPublished" to { event -> txHandlers.onVideoPublished(event) },
            "VideoPublishingFailed" to { event -> txHandlers.onVideoPublishingFailed(event) }
        ),
        subscriberId = "aggregators:creators-videos:ch10"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch10CreatorsVideosTransactionalHandlers")
    class CreatorsVideosTransactionalHandlers(
        @Qualifier("ch10CreatorVideoRepository") private val repository: CreatorVideoRepository
    ) {
        @Transactional
        fun onVideoPublished(event: Message) {
            val videoId = UUID.fromString(extractJsonField(event.data, "videoId"))
            if (alreadyApplied(videoId, event.globalPosition)) return

            repository.save(
                CreatorVideo(
                    videoId = videoId,
                    ownerId = UUID.fromString(extractJsonField(event.data, "ownerId")),
                    sourceUri = extractJsonField(event.data, "sourceUri"),
                    transcodedUri = extractJsonField(event.data, "transcodedUri"),
                    state = "published",
                    reason = null,
                    lastVideoPublishingPosition = event.globalPosition
                )
            )
        }

        @Transactional
        fun onVideoPublishingFailed(event: Message) {
            val videoId = UUID.fromString(extractJsonField(event.data, "videoId"))
            if (alreadyApplied(videoId, event.globalPosition)) return

            repository.save(
                CreatorVideo(
                    videoId = videoId,
                    ownerId = UUID.fromString(extractJsonField(event.data, "ownerId")),
                    sourceUri = extractJsonField(event.data, "sourceUri"),
                    transcodedUri = null,
                    state = "failed",
                    reason = extractJsonField(event.data, "reason"),
                    lastVideoPublishingPosition = event.globalPosition
                )
            )
        }

        /** 멱등성: 저장된 position 이상인 이벤트는 이미 반영된 것으로 보고 건너뛴다. */
        private fun alreadyApplied(videoId: UUID, globalPosition: Long): Boolean {
            val existing = repository.findById(videoId).orElse(null) ?: return false
            return existing.lastVideoPublishingPosition >= globalPosition
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
