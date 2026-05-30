package study.architecture.chapter5.subscription

import study.architecture.chapter5.messagestore.Message
import study.architecture.chapter5.messagestore.MessageStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chapter 5: Polling 기반 Subscription.
 *
 * 세 가지 책임 범주:
 *   1. Managing the Current Read Position (loadPosition / writePosition / updateReadPosition)
 *   2. Fetching and Processing Batches    (getNextBatchOfMessages / processBatch / handleMessage)
 *   3. Orchestrating the Subscription     (start / stop / poll / tick)
 *
 * 핸들러는 반드시 idempotent 해야 한다.
 */
class Subscription(
    private val messageStore: MessageStore,
    private val streamName: String,
    private val handlers: Map<String, (Message) -> Unit>,
    private val subscriberId: String,
    private val messagesPerTick: Int = 100,
    private val positionUpdateInterval: Int = 100,
    private val tickIntervalMs: Long = 100
) {
    private val subscriberStreamName = "subscriberPosition-$subscriberId"
    private var currentPosition: Long = 0
    private var messagesSinceLastPositionWrite: Int = 0
    private val keepGoing = AtomicBoolean(true)
    private var thread: Thread? = null

    // --- 1. Read Position 관리 ---

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

    // --- 2. Batch fetch & process ---

    private fun getNextBatchOfMessages(): List<Message> =
        messageStore.read(streamName, currentPosition + 1, messagesPerTick)

    private fun processBatch(messages: List<Message>): Int {
        for (m in messages) {
            try {
                handleMessage(m)
                updateReadPosition(m.globalPosition)
            } catch (e: Exception) {
                System.err.println("[$subscriberId] error on message ${m.id} (${m.type}): ${e.message}")
                throw e
            }
        }
        return messages.size
    }

    private fun handleMessage(message: Message) {
        val handler = handlers[message.type] ?: handlers["\$any"] ?: return
        handler(message)
    }

    // --- 3. Orchestration ---

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
}
