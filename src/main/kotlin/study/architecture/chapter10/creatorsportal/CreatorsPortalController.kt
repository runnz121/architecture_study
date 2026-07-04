package study.architecture.chapter10.creatorsportal

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController("ch10CreatorsPortalController")
@RequestMapping("/api/chapter10")
class CreatorsPortalController(
    @Qualifier("ch10CreatorsPortalService") private val creatorsPortalService: CreatorsPortalService
) {

    data class PublishVideoRequest(val ownerId: String, val sourceUri: String)

    /** 비디오 업로드 -> PublishVideo command 작성 (background transcoding 시작) */
    @PostMapping("/publish-video")
    fun publishVideo(@RequestBody request: PublishVideoRequest): Map<String, String> {
        val videoId = creatorsPortalService.publishVideo(request.ownerId, request.sourceUri)
        return mapOf("videoId" to videoId, "status" to "accepted")
    }

    /** Creators Portal View Data 전체 조회 */
    @GetMapping("/videos")
    fun videos(): List<Map<String, Any?>> =
        creatorsPortalService.getVideos().map(::toView)

    /** 단일 비디오 상태 조회 (transcoding 완료/실패 확인) */
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
        "reason" to video.reason
    )
}
