package study.architecture.payment.internalpay

import org.springframework.stereotype.Component       // 빈 등록.
import study.architecture.payment.domain.Payment      // 결제 엔티티(기록 대상).
import java.util.concurrent.ConcurrentHashMap         // 스레드 안전 HashMap.
import java.util.concurrent.atomic.AtomicBoolean      // 실패 주입 플래그(테스트용).

/**
 * 내부 결제 서버(Single Source of Truth) 어댑터.
 * 학습용으로 in-memory 저장 — 실제로는 HTTP/gRPC 호출.
 */
@Component
class InternalPaymentClient {

    // 내부 결제 서버를 흉내내는 in-memory 저장소.
    //   ConcurrentHashMap: 동시 호출에서 안전. 운영에선 이 자체가 외부 시스템(원격 호출).
    private val records = ConcurrentHashMap<String, Payment>()

    // 테스트용 실패 주입 — 보상 분기(INTERNAL_RECORDED 직전 실패) 검증에 사용.
    val simulateRecordFailure = AtomicBoolean(false)

    // 내부 결제 사실 기록(원격 호출 흉내).
    fun recordPayment(payment: Payment) {
        // 실패 시뮬레이션 — RuntimeException 으로 사가 catch 블록 트리거.
        if (simulateRecordFailure.get()) throw RuntimeException("Internal record failed")
        // orderId 키로 저장. 중복 호출은 덮어쓰기 — 멱등성은 외부 단(@EventLock 등)에서 보장.
        records[payment.orderId] = payment
    }

    // 보상 단계에서 호출 — 내부 기록을 지운다(취소 처리).
    fun cancelPayment(orderId: String) {
        records.remove(orderId)
    }

    // 테스트/디버깅용 — 특정 주문이 기록됐는지 확인.
    fun isRecorded(orderId: String) = records.containsKey(orderId)
}
