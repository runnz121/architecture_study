package study.architecture.chapter11.creatorsportal

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController("ch11CreatorsPortalController")
@RequestMapping("/api/chapter11")
class CreatorsPortalController(
    @Qualifier("ch11CreatorsPortalService") private val creatorsPortalService: CreatorsPortalService
) {

    data class PublishVideoRequest(val ownerId: String, val sourceUri: String)
    data class NameVideoRequest(val name: String)

    /** 비디오 업로드 -> PublishVideo command (background transcoding 시작) */
    @PostMapping("/publish-video")
    fun publishVideo(@RequestBody request: PublishVideoRequest): Map<String, String> {
        val videoId = creatorsPortalService.publishVideo(request.ownerId, request.sourceUri)
        return mapOf("videoId" to videoId, "status" to "accepted")
    }

    /**
     * 비디오 이름 변경 -> NameVideo command 작성 후 traceId 반환.
     * 클라이언트는 /video-operations/{traceId}를 polling하여 결과를 확인한다.
     */
    @PostMapping("/videos/{videoId}/name")
    fun nameVideo(
        @PathVariable videoId: String,
        @RequestBody request: NameVideoRequest
    ): Map<String, String> {
        val traceId = creatorsPortalService.nameVideo(videoId, request.name)
        return mapOf(
            "traceId" to traceId,
            "pollUrl" to "/api/chapter11/video-operations/$traceId"
        )
    }

    /**
     * Polling interstitial. 작업 결과를 traceId로 조회한다.
     *   pending   : 아직 처리 안 됨 -> 클라이언트가 계속 polling
     *   failed    : 실패 사유 표시
     *   succeeded : 비디오 페이지로 이동 (redirectTo)
     */
    @GetMapping("/video-operations/{traceId}")
    fun videoOperation(@PathVariable traceId: String): Map<String, Any?> {
        val operation = creatorsPortalService.getVideoOperation(traceId)
        return when {
            operation == null -> mapOf("status" to "pending")
            !operation.succeeded -> mapOf(
                "status" to "failed",
                "videoId" to operation.videoId,
                "reason" to operation.failureReason
            )
            else -> mapOf(
                "status" to "succeeded",
                "videoId" to operation.videoId,
                "redirectTo" to "/api/chapter11/videos/${operation.videoId}"
            )
        }
    }

    /** Creators Portal View Data 전체 조회 */
    @GetMapping("/videos")
    fun videos(): List<Map<String, Any?>> =
        creatorsPortalService.getVideos().map(::toView)

    /** 단일 비디오 상태 조회 (transcoding 완료/실패, 현재 이름 확인) */
    @GetMapping("/videos/{videoId}")
    fun video(@PathVariable videoId: String): ResponseEntity<Map<String, Any?>> {
        val video = creatorsPortalService.getVideo(videoId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "video not found or not yet processed"))
        return ResponseEntity.ok(toView(video))
    }

    private fun toView(video: CreatorVideo): Map<String, Any?> = mapOf(
        "videoId" to video.videoId,
        "ownerId" to video.ownerId,
        "sourceUri" to video.sourceUri,
        "transcodedUri" to video.transcodedUri,
        "state" to video.state,
        "reason" to video.reason,
        "name" to video.name
    )
}
