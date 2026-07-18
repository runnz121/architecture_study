package study.architecture.chapter11.videopublishing

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter11.messagestore.Message
import study.architecture.chapter11.messagestore.MessageStore
import study.architecture.chapter11.subscription.Subscription

/**
 * Chapter 10~11: video-publishing Component
 *
 * Chapter 10: PublishVideo -> transcode -> VideoPublished / VideoPublishingFailed
 * Chapter 11: NameVideo -> validation -> VideoNamed / VideoNameRejected
 *
 * 데이터의 소유권을 Component가 가지므로 validation도 Component 내부에서 비동기로 수행한다.
 * Application layer는 모든 입력을 통과시킨다.
 */
@Component("ch11VideoPublishingComponent")
class VideoPublishingComponent(
    @Qualifier("ch11MessageStore") private val messageStore: MessageStore,
    private val txHandlers: VideoPublishingTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "videoPublishing:command",
        handlers = mapOf(
            "PublishVideo" to { command -> txHandlers.handlePublishVideo(command) },
            "NameVideo" to { command -> txHandlers.handleNameVideo(command) }
        ),
        subscriberId = "components:video-publishing:ch11"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch11VideoPublishingTransactionalHandlers")
    class VideoPublishingTransactionalHandlers(
        @Qualifier("ch11MessageStore") private val messageStore: MessageStore,
        @Qualifier("ch11Transcoder") private val transcoder: Transcoder
    ) {
        // ---- Chapter 10: PublishVideo ----
        @Transactional
        fun handlePublishVideo(command: Message) {
            val videoId = extractJsonField(command.data, "videoId")
            val ownerId = extractJsonField(command.data, "ownerId")
            val sourceUri = extractJsonField(command.data, "sourceUri")
            val streamName = "videoPublishing-$videoId"

            val video = messageStore.fetch(streamName, VideoPublishingProjection())
            if (video.publishingAttempted) return // AlreadyPublished -> no-op

            val transcodedUri = try {
                transcoder.transcode(sourceUri)
            } catch (e: TranscodeError) {
                writeVideoPublishingFailedEvent(command, e.message ?: "unknown")
                return
            }

            val data = """{"ownerId":"$ownerId",""" +
                """"sourceUri":"$sourceUri",""" +
                """"transcodedUri":"$transcodedUri",""" +
                """"videoId":"$videoId"}"""
            messageStore.write(streamName, "VideoPublished", data)
        }

        // ---- Chapter 11: NameVideo ----
        /**
         * 처리 pipeline:
         *   loadVideo -> ensureCommandHasNotBeenProcessed -> ensureNameIsValid -> writeVideoNamedEvent
         *   이미 처리됨 -> no-op / validation 실패 -> VideoNameRejected
         */
        @Transactional
        fun handleNameVideo(command: Message) {
            val videoId = extractJsonField(command.data, "videoId")
            val name = extractJsonField(command.data, "name")
            val streamName = "videoPublishing-$videoId"

            // loadVideo
            val video = messageStore.fetch(streamName, VideoPublishingProjection())

            // ensureCommandHasNotBeenProcessed (멱등성)
            // sequence = 마지막 naming 이벤트의 globalPosition. command보다 뒤면 이미 처리된 것.
            if (video.sequence > command.globalPosition) return // CommandAlreadyProcessed -> no-op

            // ensureNameIsValid — 빈 이름 거부 (validate.js presence: allowEmpty false 에 대응)
            if (name.isBlank()) {
                writeVideoNameRejectedEvent(command, name, "Name can't be blank")
                return
            }

            // writeVideoNamedEvent — traceId 전파(metadata)로 polling interstitial과 연결
            messageStore.write(
                streamName = streamName,
                type = "VideoNamed",
                data = """{"name":"$name"}""",
                metadata = command.metadata
            )
        }

        private fun writeVideoNameRejectedEvent(command: Message, name: String, reason: String) {
            val videoId = extractJsonField(command.data, "videoId")
            val safeReason = reason.replace("\"", "'")
            messageStore.write(
                streamName = "videoPublishing-$videoId",
                type = "VideoNameRejected",
                data = """{"name":"$name","reason":"$safeReason"}""",
                metadata = command.metadata
            )
        }

        private fun writeVideoPublishingFailedEvent(command: Message, reason: String) {
            val videoId = extractJsonField(command.data, "videoId")
            val ownerId = extractJsonField(command.data, "ownerId")
            val sourceUri = extractJsonField(command.data, "sourceUri")
            val safeReason = reason.replace("\"", "'")
            val data = """{"reason":"$safeReason",""" +
                """"ownerId":"$ownerId",""" +
                """"sourceUri":"$sourceUri",""" +
                """"videoId":"$videoId"}"""
            messageStore.write(
                streamName = "videoPublishing-$videoId",
                type = "VideoPublishingFailed",
                data = data
            )
        }

        private fun extractJsonField(json: String, field: String): String {
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
