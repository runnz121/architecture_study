package study.architecture.payment.saga

import org.slf4j.LoggerFactory                                  // 로깅.
import org.springframework.scheduling.annotation.Scheduled      // 주기 실행 어노테이션.
import org.springframework.stereotype.Component                 // 빈 등록.
import study.architecture.payment.domain.PaymentStatus          // 상태 enum.
import study.architecture.payment.repository.PaymentRepository  // 정체 사가 조회.
import java.time.LocalDateTime                                  // 시각 계산.

/**
 * 인라인 보상이 실패한 사가를 주기적으로 깨워 재시도/보상 실행.
 * 매 1분 fixedDelay, updatedAt 이 5분 이상 정체된 케이스만 대상.
 */
@Component
class SagaRecoveryScheduler(
    private val paymentRepository: PaymentRepository,    // 정체 사가 조회.
    private val compensationHandler: CompensationHandler // 보상/재시도 실행 위임.
) {
    private val logger = LoggerFactory.getLogger(SagaRecoveryScheduler::class.java)

    // @Scheduled(fixedDelay = ms): 이전 실행이 끝난 시점 기준으로 ms 간격 재실행.
    //   ※ @EnableScheduling 이 어딘가에 켜져 있어야 동작(PaymentConfig 에서 켰음).
    @Scheduled(fixedDelay = 60_000) // 60_000 ms = 1분.
    fun recoverStuckPayments() {
        // 5분 이전을 기준선으로 — "이 시각보다 더 오래된 updatedAt 을 가진 사가는 정체된 걸로 본다".
        val cutoff = LocalDateTime.now().minusMinutes(5)

        // 정체 후보: 진행/보상 진행 중인 상태들. 종착 상태(COMPLETED/CANCELLED/FAILED_NEEDS_REVIEW)는 제외.
        val stuck = paymentRepository.findStuckPayments(
            statuses = listOf(
                PaymentStatus.PG_APPROVED,
                PaymentStatus.INTERNAL_RECORDED,
                PaymentStatus.COMPENSATING_PG,
                PaymentStatus.COMPENSATING_FULL
            ),
            beforeUpdatedAt = cutoff
        )

        // 비어 있으면 즉시 종료(불필요한 로그 안 찍음).
        if (stuck.isEmpty()) return
        logger.info("Recovering {} stuck payments", stuck.size)

        // 각 정체 사가에 대해 적절한 보상/재시도 호출.
        stuck.forEach { payment ->
            try {
                when (payment.status) {
                    // 진행 도중 멈춘 거 → 보상 시작.
                    PaymentStatus.PG_APPROVED,
                    PaymentStatus.INTERNAL_RECORDED ->
                        compensationHandler.compensate(
                            payment,
                            RuntimeException("Recovery: stuck timeout")
                        )

                    // 이미 보상 진행 중에서 멈춘 거 → 보상 재시도.
                    PaymentStatus.COMPENSATING_PG,
                    PaymentStatus.COMPENSATING_FULL ->
                        compensationHandler.retry(payment)

                    else -> {} // 그 외엔 무시(쿼리에서 이미 거른 케이스).
                }
            } catch (e: Exception) {
                // 한 건 실패가 전체 루프를 멈추지 않게 try-catch 로 격리.
                logger.error("Recovery failed orderId=${payment.orderId}", e)
            }
        }
    }
}
