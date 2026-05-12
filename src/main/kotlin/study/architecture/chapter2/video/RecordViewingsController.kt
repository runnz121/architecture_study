package study.architecture.chapter2.video

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chapter2")
class RecordViewingsController(private val recordViewingsService: RecordViewingsService) {

    @GetMapping("/home")
    fun home(): Map<String, Any> = mapOf(
        "videosWatched" to recordViewingsService.getGlobalViewCount()
    )

    @PostMapping("/record-viewing/{videoId}")
    fun recordViewing(@PathVariable videoId: Long): Map<String, String> {
        recordViewingsService.recordViewing(videoId)
        return mapOf("status" to "ok")
    }
}
