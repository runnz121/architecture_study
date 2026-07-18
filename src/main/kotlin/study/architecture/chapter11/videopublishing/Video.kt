package study.architecture.chapter11.videopublishing

import study.architecture.chapter11.messagestore.Message
import study.architecture.chapter11.messagestore.Projection

/**
 * videoPublishing-<videoId> 스트림의 projection.
 *
 * Chapter 11 추가:
 *  - sequence : 마지막으로 적용된 VideoNamed/VideoNameRejected 이벤트의 globalPosition.
 *               NameVideo command 멱등성 판단에 사용 (sequence > command.globalPosition 이면 이미 처리됨)
 *  - name     : 현재 비디오 이름
 */
data class Video(
    val id: String? = null,
    val ownerId: String? = null,
    val publishingAttempted: Boolean = false,
    val sourceUri: String? = null,
    val transcodedUri: String? = null,
    val sequence: Long = 0,
    val name: String = ""
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
        "VideoNamed" -> entity.copy(
            sequence = event.globalPosition,
            name = extractJsonField(event.data, "name")
        )
        "VideoNameRejected" -> entity.copy(
            sequence = event.globalPosition
        )
        else -> entity
    }

    private fun extractJsonField(json: String, field: String): String {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }
}
