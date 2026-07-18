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

/**
 * Chapter 11: "Aggregating Naming Results"
 *
 * video_operations View Data를 채우는 Aggregator.
 * VideoNamed / VideoNameRejected 이벤트를 관찰하여 traceId 기준으로 작업 결과를 기록한다.
 * polling interstitial이 이 View Data를 traceId로 조회한다.
 */
@Component("ch11VideoOperationsAggregator")
class VideoOperationsAggregator(
    @Qualifier("ch11MessageStore") private val messageStore: MessageStore,
    private val txHandlers: VideoOperationsTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "videoPublishing",
        handlers = mapOf(
            "VideoNamed" to { event -> txHandlers.onVideoNamed(event) },
            "VideoNameRejected" to { event -> txHandlers.onVideoNameRejected(event) }
        ),
        subscriberId = "aggregators:video-operations:ch11"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch11VideoOperationsTransactionalHandlers")
    class VideoOperationsTransactionalHandlers(
        @Qualifier("ch11VideoOperationRepository") private val repository: VideoOperationRepository
    ) {
        @Transactional
        fun onVideoNamed(event: Message) =
            upsert(event, succeeded = true, failureReason = null)

        @Transactional
        fun onVideoNameRejected(event: Message) =
            upsert(event, succeeded = false, failureReason = extractJsonField(event.data, "reason"))

        /**
         * ON CONFLICT (trace_id) DO NOTHING 에 대응하는 멱등 upsert.
         * 동일 traceId로 재처리되더라도 기존 데이터를 덮어쓰지 않는다.
         */
        private fun upsert(event: Message, succeeded: Boolean, failureReason: String?) {
            val traceId = extractMetadataField(event.metadata, "traceId")
            if (traceId.isBlank()) return
            if (repository.existsById(traceId)) return // DO NOTHING

            repository.save(
                VideoOperation(
                    traceId = traceId,
                    videoId = streamNameToId(event.streamName),
                    succeeded = succeeded,
                    failureReason = failureReason
                )
            )
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }

        private fun extractMetadataField(metadata: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(metadata)?.groupValues?.get(1) ?: ""
        }
    }
}
