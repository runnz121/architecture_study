package study.architecture.chapter4.video

import org.springframework.stereotype.Service
import study.architecture.chapter4.messagestore.MessageStore
import study.architecture.chapter4.page.PageRepository

@Service("ch4RecordViewingsService")
class RecordViewingsService(
    private val messageStore: MessageStore,
    private val pageRepository: PageRepository
) {

    fun recordViewing(videoId: Long) {
        val streamName = "viewing-$videoId"
        val data = """{"videoId":$videoId}"""
        messageStore.write(streamName, "VideoViewed", data)
    }

    /**
     * 기존 monolithic 집계 쿼리 대신 View Data 단일 row 조회.
     */
    fun loadHomePage(): Map<String, Any> {
        val page = pageRepository.findById("home").orElse(null)
        return mapOf(
            "videosWatched" to (page?.videosWatched ?: 0L),
            "lastViewProcessed" to (page?.lastViewProcessed ?: 0L)
        )
    }
}
