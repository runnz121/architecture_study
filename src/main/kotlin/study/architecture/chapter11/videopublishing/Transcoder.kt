package study.architecture.chapter11.videopublishing

import org.springframework.stereotype.Component

/** 트랜스코딩 실패를 나타내는 타입. 핸들러가 잡아 VideoPublishingFailed 이벤트를 기록한다. */
class TranscodeError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 실제 작업(transcodeVideo)을 담당하는 추상화.
 * 수초~수분이 걸릴 수 있는 background 작업 — request/response cycle 밖에서 수행된다.
 */
interface Transcoder {
    fun transcode(sourceUri: String): String
}

/**
 * 트랜스코딩 시뮬레이션. 실제로는 ffmpeg 등으로 포맷 변환 후 결과 URI를 반환한다.
 */
@Component("ch11Transcoder")
class FakeTranscoder : Transcoder {
    override fun transcode(sourceUri: String): String {
        if (sourceUri.isBlank() || !sourceUri.startsWith("http")) {
            throw TranscodeError("Invalid source URI: $sourceUri")
        }
        val slug = sourceUri.trimEnd('/').substringAfterLast('/').ifBlank { "video" }
        return "https://transcoded.video-tutorials.example.com/$slug.mp4"
    }
}
