package study.architecture.chapter6.messagestore

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component("ch6MessageStore")
class MessageStore(private val messageRepository: MessageRepository) {

    @Transactional
    fun write(
        streamName: String,
        type: String,
        data: String,
        metadata: String = "{}",
        expectedVersion: Long? = null
    ) {
        val currentVersion = messageRepository.findMaxPositionByStreamName(streamName)
        if (expectedVersion != null && currentVersion != expectedVersion) {
            error("Version conflict on $streamName: current=$currentVersion expected=$expectedVersion")
        }
        val nextPosition = (currentVersion ?: -1) + 1
        val nextGlobal = (messageRepository.findMaxGlobalPosition() ?: 0) + 1
        messageRepository.save(
            Message(
                streamName = streamName,
                type = type,
                position = nextPosition,
                globalPosition = nextGlobal,
                data = data,
                metadata = metadata
            )
        )
    }

    fun read(streamName: String, fromPosition: Long = 0, maxMessages: Int = 1000): List<Message> {
        val pageable = PageRequest.of(0, maxMessages)
        return if (streamName.contains('-')) {
            messageRepository.findByStream(streamName, fromPosition, pageable)
        } else {
            messageRepository.findByCategory(streamName, fromPosition, pageable)
        }
    }

    fun readLastMessage(streamName: String): Message? =
        messageRepository.findLastInStream(streamName)
}
