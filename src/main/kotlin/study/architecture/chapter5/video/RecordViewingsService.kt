package study.architecture.chapter5.video

import org.springframework.stereotype.Service
import study.architecture.chapter5.messagestore.MessageStore
import study.architecture.chapter5.page.PageRepository

@Service("ch5RecordViewingsService")
class RecordViewingsService(
    private val messageStore: MessageStore,
    private val pageRepository: PageRepository
) {

    fun recordViewing(videoId: Long) {
        val streamName = "viewing-$videoId"
        val data = """{"videoId":$videoId}"""
        messageStore.write(streamName, "VideoViewed", data)
    }

    fun loadHomePage(): Map<String, Any> {
        val page = pageRepository.findById("home").orElse(null)
        return mapOf(
            "videosWatched" to (page?.videosWatched ?: 0L),
            "lastViewProcessed" to (page?.lastViewProcessed ?: 0L)
        )
    }
}
