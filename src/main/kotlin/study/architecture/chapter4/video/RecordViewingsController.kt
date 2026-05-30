package study.architecture.chapter4.video

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController("ch4RecordViewingsController")
@RequestMapping("/api/chapter4")
class RecordViewingsController(private val recordViewingsService: RecordViewingsService) {

    @GetMapping("/home")
    fun home(): Map<String, Any> = recordViewingsService.loadHomePage()

    @PostMapping("/record-viewing/{videoId}")
    fun recordViewing(@PathVariable videoId: Long): Map<String, String> {
        recordViewingsService.recordViewing(videoId)
        return mapOf("status" to "ok")
    }
}
