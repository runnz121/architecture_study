package study.architecture.chapter4.aggregator

import jakarta.annotation.PostConstruct
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import study.architecture.chapter4.messagestore.MessageWrittenEvent
import study.architecture.chapter4.page.Page
import study.architecture.chapter4.page.PageRepository

/**
 * Chapter 4: Home Page Aggregator.
 *
 * - createHandlers: 메시지 타입별 핸들러 정의 (VideoViewed)
 * - createQueries: View Data 갱신 쿼리 (incrementVideosWatched, ensureHomePage)
 * - build/start: init() -> ensureHomePage 후 라이브 메시지 흐름에 연결
 *
 * 라이브 연결은 Spring ApplicationEvent 구독으로 구현했다.
 * (Chapter 5에서 polling 기반 subscription으로 대체된다.)
 */
@Component("ch4HomePageAggregator")
class HomePageAggregator(private val pageRepository: PageRepository) {

    @PostConstruct
    @Transactional
    fun init() {
        // ensureHomePage: ON CONFLICT DO NOTHING 과 동일 효과
        if (!pageRepository.existsById("home")) {
            pageRepository.save(Page(pageName = "home"))
        }
    }

    @EventListener
    @Transactional
    fun handle(event: MessageWrittenEvent) {
        // 카테고리 스트림 "viewing" 구독: streamName이 "viewing-..." 인 메시지만 처리
        if (!event.streamName.startsWith("viewing-")) return
        val handler = handlers[event.type] ?: return
        handler(event)
    }

    private val handlers: Map<String, (MessageWrittenEvent) -> Unit> = mapOf(
        "VideoViewed" to { event ->
            // WHERE lastViewProcessed < globalPosition 으로 멱등 보장
            pageRepository.incrementVideosWatched(event.globalPosition)
        }
    )
}
