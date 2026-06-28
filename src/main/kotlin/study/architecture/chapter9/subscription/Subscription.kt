package study.architecture.chapter9.subscription

import study.architecture.chapter9.messagestore.Message
import study.architecture.chapter9.messagestore.MessageStore
import study.architecture.chapter9.messagestore.category
import java.util.concurrent.atomic.AtomicBoolean

class Subscription(
    private val messageStore: MessageStore,
    private val streamName: String,
    private val handlers: Map<String, (Message) -> Unit>,
    private val subscriberId: String,
    /**
     * Chapter 9 §8: 지정하면 metadata.originStreamName의 카테고리가
     * 이 값과 일치하는 메시지만 핸들러로 전달한다. (책의 filterOnOriginMatch)
     */
    private val originStreamName: String? = null,
    private val messagesPerTick: Int = 100,
    private val positionUpdateInterval: Int = 100,
    private val tickIntervalMs: Long = 100
) {
    private val subscriberStreamName = "subscriberPosition-$subscriberId"
    private var currentPosition: Long = 0
    private var messagesSinceLastPositionWrite: Int = 0
    private val keepGoing = AtomicBoolean(true)
    private var thread: Thread? = null

    fun loadPosition() {
        val last = messageStore.readLastMessage(subscriberStreamName)
        currentPosition = last?.let { extractPosition(it.data) } ?: 0
    }

    fun writePosition(position: Long) {
        messageStore.write(
            streamName = subscriberStreamName,
            type = "Read",
            data = """{"position":$position}"""
        )
    }

    private fun updateReadPosition(position: Long) {
        currentPosition = position
        messagesSinceLastPositionWrite += 1
        if (messagesSinceLastPositionWrite >= positionUpdateInterval) {
            messagesSinceLastPositionWrite = 0
            writePosition(position)
        }
    }

    private fun getNextBatchOfMessages(): List<Message> =
        messageStore.read(streamName, currentPosition + 1, messagesPerTick)

    private fun processBatch(messages: List<Message>): Int {
        for (m in messages) {
            try {
                // originStreamName 불일치 메시지는 핸들링하지 않되, position은 전진시킨다.
                // (책의 filterOnOriginMatch를 메시지 단위로 평가 — 한 배치가 통째로
                //  필터링돼도 position이 멈추지 않게 하기 위함)
                if (originMatches(m)) {
                    handleMessage(m)
                }
                updateReadPosition(m.globalPosition)
            } catch (e: Exception) {
                System.err.println("[$subscriberId] error on message ${m.id} (${m.type}): ${e.message}")
                throw e
            }
        }
        return messages.size
    }

    private fun originMatches(message: Message): Boolean {
        val expected = originStreamName ?: return true
        val originCategory = category(extractMetadataField(message.metadata, "originStreamName"))
        return expected == originCategory
    }

    private fun handleMessage(message: Message) {
        val handler = handlers[message.type] ?: handlers["\$any"] ?: return
        handler(message)
    }

    fun start() {
        if (thread != null) return
        keepGoing.set(true)
        thread = Thread({
            println("Started $subscriberId")
            poll()
        }, "subscription-$subscriberId").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        println("Stopped $subscriberId")
        keepGoing.set(false)
    }

    private fun poll() {
        loadPosition()
        while (keepGoing.get()) {
            val processed = try {
                tick()
            } catch (e: Exception) {
                System.err.println("[$subscriberId] tick failed: ${e.message}")
                stop()
                0
            }
            if (processed == 0) {
                try {
                    Thread.sleep(tickIntervalMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun tick(): Int = processBatch(getNextBatchOfMessages())

    private fun extractPosition(data: String): Long {
        val regex = Regex("\"position\"\\s*:\\s*(\\d+)")
        return regex.find(data)?.groupValues?.get(1)?.toLong() ?: 0
    }

    private fun extractMetadataField(metadata: String, field: String): String {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(metadata)?.groupValues?.get(1) ?: ""
    }
}
