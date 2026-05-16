package study.architecture.chapter2.messagestore

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component("ch2MessageStore")
class MessageStore(private val messageRepository: MessageRepository) {

    @Transactional
    fun write(streamName: String, type: String, data: String, metadata: String = "{}") {
        val position = (messageRepository.findMaxPositionByStreamName(streamName) ?: -1) + 1

        val message = Message(
            streamName = streamName,
            type = type,
            position = position,
            data = data,
            metadata = metadata
        )
        messageRepository.save(message)
    }
}
