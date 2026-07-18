package study.architecture.chapter11.creatorsportal

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter11.messagestore.Message
import study.architecture.chapter11.messagestore.MessageStore
import study.architecture.chapter11.messagestore.streamNameToId
import study.architecture.chapter11.subscription.Subscription
import java.util.UUID

/**
 * Creators Portal View Data(ch11_creator_videos)를 채우는 Aggregator.
 *
 * Chapter 10: VideoPublished / VideoPublishingFailed -> 행 생성
 * Chapter 11: VideoNamed -> 같은 이벤트로부터 name 갱신
 *   (video_operations 와는 별개의 View Data — "같은 이벤트, 다른 View Data")
 */
@Component("ch11CreatorsVideosAggregator")
class CreatorsVideosAggregator(
    @Qualifier("ch11MessageStore") private val messageStore: MessageStore,
    private val txHandlers: CreatorsVideosTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "videoPublishing",
        handlers = mapOf(
            "VideoPublished" to { event -> txHandlers.onVideoPublished(event) },
            "VideoPublishingFailed" to { event -> txHandlers.onVideoPublishingFailed(event) },
            "VideoNamed" to { event -> txHandlers.onVideoNamed(event) }
        ),
        subscriberId = "aggregators:creators-videos:ch11"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch11CreatorsVideosTransactionalHandlers")
    class CreatorsVideosTransactionalHandlers(
        @Qualifier("ch11CreatorVideoRepository") private val repository: CreatorVideoRepository
    ) {
        @Transactional
        fun onVideoPublished(event: Message) {
            val videoId = UUID.fromString(extractJsonField(event.data, "videoId"))
            if (alreadyApplied(videoId, event.globalPosition)) return

            val existing = repository.findById(videoId).orElse(null)
            repository.save(
                CreatorVideo(
                    videoId = videoId,
                    ownerId = UUID.fromString(extractJsonField(event.data, "ownerId")),
                    sourceUri = extractJsonField(event.data, "sourceUri"),
                    transcodedUri = extractJsonField(event.data, "transcodedUri"),
                    state = "published",
                    reason = null,
                    name = existing?.name,
                    lastVideoPublishingPosition = event.globalPosition
                )
            )
        }

        @Transactional
        fun onVideoPublishingFailed(event: Message) {
            val videoId = UUID.fromString(extractJsonField(event.data, "videoId"))
            if (alreadyApplied(videoId, event.globalPosition)) return

            val existing = repository.findById(videoId).orElse(null)
            repository.save(
                CreatorVideo(
                    videoId = videoId,
                    ownerId = UUID.fromString(extractJsonField(event.data, "ownerId")),
                    sourceUri = extractJsonField(event.data, "sourceUri"),
                    transcodedUri = null,
                    state = "failed",
                    reason = extractJsonField(event.data, "reason"),
                    name = existing?.name,
                    lastVideoPublishingPosition = event.globalPosition
                )
            )
        }

        /**
         * Chapter 11: VideoNamed -> 기존 행의 name 갱신 (position 비교로 멱등성 보장).
         * videoId는 event.streamName(videoPublishing-<videoId>)에서 추출한다.
         * 아직 행이 없으면(발행 전) 갱신하지 않는다 (책의 update-only 동작에 대응).
         */
        @Transactional
        fun onVideoNamed(event: Message) {
            val videoId = UUID.fromString(streamNameToId(event.streamName))
            val existing = repository.findById(videoId).orElse(null) ?: return
            if (existing.lastVideoPublishingPosition >= event.globalPosition) return

            repository.save(
                CreatorVideo(
                    videoId = existing.videoId,
                    ownerId = existing.ownerId,
                    sourceUri = existing.sourceUri,
                    transcodedUri = existing.transcodedUri,
                    state = existing.state,
                    reason = existing.reason,
                    name = extractJsonField(event.data, "name"),
                    lastVideoPublishingPosition = event.globalPosition
                )
            )
        }

        private fun alreadyApplied(videoId: UUID, globalPosition: Long): Boolean {
            val existing = repository.findById(videoId).orElse(null) ?: return false
            return existing.lastVideoPublishingPosition >= globalPosition
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
