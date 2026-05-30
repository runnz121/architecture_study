package study.architecture.chapter4.messagestore

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Chapter 4: Chapter 3의 message store에 "라이브 메시지 흐름" 연결 추가.
 *
 * write 후 Spring ApplicationEvent로 발행하여 Aggregator에게 전달한다.
 * (Chapter 5에서는 polling 기반 subscription으로 교체된다.)
 */
@Component("ch4MessageStore")
class MessageStore(
    private val messageRepository: MessageRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

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

        val saved = messageRepository.save(
            Message(
                streamName = streamName,
                type = type,
                position = nextPosition,
                globalPosition = nextGlobal,
                data = data,
                metadata = metadata
            )
        )

        eventPublisher.publishEvent(
            MessageWrittenEvent(
                streamName = saved.streamName,
                type = saved.type,
                position = saved.position,
                globalPosition = saved.globalPosition,
                data = saved.data,
                metadata = saved.metadata
            )
        )
    }
}
