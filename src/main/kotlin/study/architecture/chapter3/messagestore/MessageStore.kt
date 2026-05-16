package study.architecture.chapter3.messagestore

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Chapter 3: write_message UDF 역할 + 낙관적 동시성 제어
 *
 * expectedVersion이 주어지면 스트림의 현재 버전(max position)과 비교하여
 * 일치하지 않으면 VersionConflictError를 발생시킨다.
 */
@Component("ch3MessageStore")
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
            throw VersionConflictError(streamName, currentVersion, expectedVersion)
        }

        val nextPosition = (currentVersion ?: -1) + 1

        val message = Message(
            streamName = streamName,
            type = type,
            position = nextPosition,
            data = data,
            metadata = metadata
        )
        messageRepository.save(message)
    }
}
