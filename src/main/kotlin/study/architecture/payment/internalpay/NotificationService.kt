package study.architecture.payment.internalpay

import org.slf4j.LoggerFactory                    // SLF4J — Spring Boot 표준 로깅 인터페이스.
import org.springframework.stereotype.Component   // 빈 등록.
import study.architecture.payment.domain.Payment  // 알림 대상 결제 엔티티.

@Component
class NotificationService {

    // companion object 안에 두지 않고 인스턴스 필드로 쓰는 게 일반적 Kotlin 스타일.
    //   클래스::class.java 로 Java Class 참조 → SLF4J 가 클래스명 기반 로거를 만든다.
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    // 자동 보상까지 모두 실패했을 때 호출되는 운영자 알림.
    // 학습용으로 ERROR 로그만 — 운영에선 PagerDuty/Slack/이메일/SMS 등을 트리거.
    fun notifyOperator(payment: Payment, reason: String) {
        // {} 플레이스홀더 → SLF4J 가 인자를 안전하게 채워줌(문자열 concat 보다 효율적).
        logger.error(
            "[OPERATOR ALERT] orderId={} status={} retry={} reason={}",
            payment.orderId, payment.status, payment.retryCount, reason
        )
    }
}
