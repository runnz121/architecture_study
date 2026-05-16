package study.architecture.chapter3.video

import org.springframework.stereotype.Service
import study.architecture.chapter3.messagestore.MessageRepository
import study.architecture.chapter3.messagestore.MessageStore

@Service("ch3RecordViewingsService")
class RecordViewingsService(
    private val messageStore: MessageStore,
    private val messageRepository: MessageRepository
) {

    fun recordViewing(videoId: Long) {
        val streamName = "viewing-$videoId"
        val data = """{"videoId":$videoId}"""
        messageStore.write(streamName, "VideoViewed", data)
    }

    fun getGlobalViewCount(): Long = messageRepository.countByType("VideoViewed")
}
