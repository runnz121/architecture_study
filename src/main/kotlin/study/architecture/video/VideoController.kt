package study.architecture.video

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class VideoController(private val videoService: VideoService) {

    @GetMapping("/home")
    fun home(): Map<String, Any> = mapOf(
        "videosWatched" to videoService.getGlobalViewCount()
    )

    @PostMapping("/record-viewing/{videoId}")
    fun recordViewing(@PathVariable videoId: Long): Map<String, String> {
        videoService.recordViewing(videoId)
        return mapOf("status" to "ok")
    }
}
