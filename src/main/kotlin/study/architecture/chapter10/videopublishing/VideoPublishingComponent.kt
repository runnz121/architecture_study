package study.architecture.chapter10.videopublishing

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter10.messagestore.Message
import study.architecture.chapter10.messagestore.MessageStore
import study.architecture.chapter10.subscription.Subscription

/**
 * Chapter 10 Use Case #2: video-publishing Component (background job)
 *
 * 비디오 트랜스코딩은 수초~수분이 걸리는 장시간 작업이라 HTTP connection을 붙잡을 수 없다.
 * 별도 queue 인프라(Redis/RabbitMQ) 없이 Message Store의 subscription 메커니즘만으로
 * background job을 수행한다.
 *
 *   PublishVideo (command) -> transcode -> VideoPublished / VideoPublishingFailed (event)
 */
@Component("ch10VideoPublishingComponent")
class VideoPublishingComponent(
    @Qualifier("ch10MessageStore") private val messageStore: MessageStore,
    private val txHandlers: VideoPublishingTransactionalHandlers
) {
    private val subscription = Subscription(
        messageStore = messageStore,
        streamName = "videoPublishing:command",
        handlers = mapOf(
            "PublishVideo" to { command -> txHandlers.handlePublishVideo(command) }
        ),
        subscriberId = "components:video-publishing:ch10"
    )

    @PostConstruct
    fun start() = subscription.start()

    @PreDestroy
    fun stop() = subscription.stop()

    @Component("ch10VideoPublishingTransactionalHandlers")
    class VideoPublishingTransactionalHandlers(
        @Qualifier("ch10MessageStore") private val messageStore: MessageStore,
        @Qualifier("ch10Transcoder") private val transcoder: Transcoder
    ) {
        /**
         * 모든 Component handler의 4단계 패턴:
         *   1. loadVideo                    — 현재 상태 project
         *   2. ensurePublishingNotAttempted — 멱등성 (이미 시도했으면 no-op)
         *   3. transcodeVideo               — 실제 작업
         *   4. writeVideoPublishedEvent     — 결과 이벤트 기록
         *   실패 시 VideoPublishingFailed 기록
         */
        @Transactional
        fun handlePublishVideo(command: Message) {
            val videoId = extractJsonField(command.data, "videoId")
            val ownerId = extractJsonField(command.data, "ownerId")
            val sourceUri = extractJsonField(command.data, "sourceUri")
            val streamName = "videoPublishing-$videoId"

            // 1~2. loadVideo + ensurePublishingNotAttempted (멱등성)
            val video = messageStore.fetch(streamName, VideoPublishingProjection())
            if (video.publishingAttempted) return // AlreadyPublished -> no-op

            // 3. transcodeVideo — 실제 background 작업.
            //    트랜잭션으로 보호되지 않으므로, 완료 후 이벤트 기록 전에 재시작되면
            //    같은 command를 다시 보고 중복 트랜스코딩할 수 있다.
            //    (동일 destination에 덮어쓰기 -> orphaned file 없음 -> 비즈니스가 허용한 중복)
            val transcodedUri = try {
                transcoder.transcode(sourceUri)
            } catch (e: TranscodeError) {
                writeVideoPublishingFailedEvent(command, e.message ?: "unknown")
                return
            }

            // 4. writeVideoPublishedEvent
            val data = """{"ownerId":"$ownerId",""" +
                """"sourceUri":"$sourceUri",""" +
                """"transcodedUri":"$transcodedUri",""" +
                """"videoId":"$videoId"}"""
            messageStore.write(
                streamName = streamName,
                type = "VideoPublished",
                data = data
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
            val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
    }
}
