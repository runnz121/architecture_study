package study.architecture.chapter11.creatorsportal

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import study.architecture.chapter11.messagestore.MessageStore
import java.util.UUID

/**
 * Creators Portal (Application). Message Store에 command를 기록한다.
 * Application layer는 입력을 검증하지 않고 통과시키며, 검증은 Component가 비동기로 수행한다.
 */
@Service("ch11CreatorsPortalService")
class CreatorsPortalService(
    @Qualifier("ch11MessageStore") private val messageStore: MessageStore,
    @Qualifier("ch11CreatorVideoRepository") private val creatorVideoRepository: CreatorVideoRepository,
    @Qualifier("ch11VideoOperationRepository") private val videoOperationRepository: VideoOperationRepository
) {
    fun publishVideo(ownerId: String, sourceUri: String): String {
        require(ownerId.isNotBlank()) { "ownerId is required" }
        require(sourceUri.isNotBlank()) { "sourceUri is required" }

        val videoId = UUID.randomUUID().toString()
        val data = """{"ownerId":"$ownerId","sourceUri":"$sourceUri","videoId":"$videoId"}"""
        messageStore.write(
            streamName = "videoPublishing:command-$videoId",
            type = "PublishVideo",
            data = data
        )
        return videoId
    }

    /**
     * 비디오 이름 변경 요청. 이름 검증은 Component가 하므로 여기서는 통과시킨다.
     * 반환한 traceId로 polling interstitial이 작업 결과를 추적한다.
     */
    fun nameVideo(videoId: String, name: String): String {
        require(videoId.isNotBlank()) { "videoId is required" }

        val traceId = UUID.randomUUID().toString()
        val data = """{"videoId":"$videoId","name":"$name"}"""
        val metadata = """{"traceId":"$traceId","userId":""}"""
        messageStore.write(
            streamName = "videoPublishing:command-$videoId",
            type = "NameVideo",
            data = data,
            metadata = metadata
        )
        return traceId
    }

    fun getVideos(): List<CreatorVideo> = creatorVideoRepository.findAll()

    fun getVideo(videoId: String): CreatorVideo? =
        creatorVideoRepository.findById(UUID.fromString(videoId)).orElse(null)

    fun getVideoOperation(traceId: String): VideoOperation? =
        videoOperationRepository.findById(traceId).orElse(null)
}
