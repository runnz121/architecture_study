package study.architecture.chapter10.creatorsportal

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import study.architecture.chapter10.messagestore.MessageStore
import java.util.UUID

/**
 * Chapter 10: "Describing the Creators Portal"
 *
 * Creators Portal은 Message Store에 command를 기록하는 Application이다.
 * 사용자가 비디오를 업로드하면 PublishVideo command를 작성한다.
 * (멱등성을 위해 videoId는 command를 보내는 쪽에서 생성/제어한다)
 */
@Service("ch10CreatorsPortalService")
class CreatorsPortalService(
    @Qualifier("ch10MessageStore") private val messageStore: MessageStore,
    @Qualifier("ch10CreatorVideoRepository") private val repository: CreatorVideoRepository
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

    fun getVideos(): List<CreatorVideo> = repository.findAll()

    fun getVideo(videoId: String): CreatorVideo? =
        repository.findById(UUID.fromString(videoId)).orElse(null)
}
