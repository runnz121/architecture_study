package study.architecture.chapter5.aggregator

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter5.messagestore.Message
import study.architecture.chapter5.messagestore.MessageStore
import study.architecture.chapter5.page.Page
import study.architecture.chapter5.page.PageRepository
import study.architecture.chapter5.subscription.Subscription

/**
 * Chapter 5: Polling 기반 Subscription으로 라이브 메시지 흐름에 연결된 Aggregator.
 *
 * build({ db, messageStore }) → queries + handlers + createSubscription
 * start() → init() → subscription.start()
 */
@Component("ch5HomePageAggregator")
class HomePageAggregator(
    private val pageRepository: PageRepository,
    private val messageStore: MessageStore,
    private val txAggregator: TransactionalHandlers
) {

    private val subscription: Subscription = Subscription(
        messageStore = messageStore,
        streamName = "viewing",
        handlers = mapOf(
            "VideoViewed" to { event -> txAggregator.onVideoViewed(event) }
        ),
        subscriberId = "aggregators:home-page"
    )

    @PostConstruct
    fun start() {
        init()
        subscription.start()
    }

    @PreDestroy
    fun shutdown() {
        subscription.stop()
    }

    @Transactional
    fun init() {
        if (!pageRepository.existsById("home")) {
            pageRepository.save(Page(pageName = "home"))
        }
    }

    /**
     * @Transactional이 self-invocation에서는 동작하지 않으므로
     * 핸들러 본체를 별도 빈으로 분리한다.
     */
    @Component
    class TransactionalHandlers(private val pageRepository: PageRepository) {
        @Transactional
        fun onVideoViewed(event: Message) {
            pageRepository.incrementVideosWatched(event.globalPosition)
        }
    }
}
