package study.architecture.chapter10.videopublishing

import study.architecture.chapter10.messagestore.Message
import study.architecture.chapter10.messagestore.Projection

/**
 * videoPublishing-<videoId> 스트림의 projection.
 * publishingAttempted 가 성공/실패 모두에서 true 로 설정되어 멱등성을 보장한다.
 */
data class Video(
    val id: String? = null,
    val ownerId: String? = null,
    val publishingAttempted: Boolean = false,
    val sourceUri: String? = null,
    val transcodedUri: String? = null
)

class VideoPublishingProjection : Projection<Video> {
    override fun init() = Video()

    override fun apply(entity: Video, event: Message): Video = when (event.type) {
        "VideoPublished" -> entity.copy(
            id = extractJsonField(event.data, "videoId"),
            publishingAttempted = true,
            ownerId = extractJsonField(event.data, "ownerId"),
            sourceUri = extractJsonField(event.data, "sourceUri"),
            transcodedUri = extractJsonField(event.data, "transcodedUri")
        )
        "VideoPublishingFailed" -> entity.copy(
            id = extractJsonField(event.data, "videoId"),
            publishingAttempted = true,
            ownerId = extractJsonField(event.data, "ownerId"),
            sourceUri = extractJsonField(event.data, "sourceUri")
        )
        else -> entity
    }

    private fun extractJsonField(json: String, field: String): String {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }
}
