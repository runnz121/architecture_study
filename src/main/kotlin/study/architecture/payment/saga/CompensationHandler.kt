package study.architecture.payment.saga

import org.slf4j.LoggerFactory                                  // 로깅.
import org.springframework.stereotype.Component                 // 빈 등록.
import study.architecture.payment.domain.Payment                // 보상 대상 엔티티.
import study.architecture.payment.domain.PaymentStatus          // 상태 enum.
import study.architecture.payment.internalpay.InternalPaymentClient // 내부 기록 취소용.
import study.architecture.payment.internalpay.NotificationService   // 운영자 알림.
import study.architecture.payment.pg.PgClientRouter             // PG 취소 호출용.
import study.architecture.payment.repository.PaymentRepository  // 상태 영속화.

/**
 * 상태 기반 보상 처리기.
 *
 * 보상 전략은 catch 블록의 예외가 아니라 영속화된 현재 상태로부터 파생된다.
 *  - PG_APPROVED        → PG 취소만
 *  - INTERNAL_RECORDED  → 내부 기록 + PG 취소
 *  - COMPENSATING_*     → 재시도(회복 스케줄러 진입점)
 */
@Component
class CompensationHandler(
    private val paymentRepository: PaymentRepository,        // 상태 전이 후 저장.
    private val pgClientRouter: PgClientRouter,              // PG 취소 호출.
    private val internalPaymentClient: InternalPaymentClient, // 내부 기록 취소.
    private val notificationService: NotificationService     // 최종 실패 시 운영자 호출.
) {
    private val logger = LoggerFactory.getLogger(CompensationHandler::class.java)

    // 보상 진입점 — 현재 상태로 분기한다.
    //   ※ "어떤 예외가 났는가" 가 아니라 "어디까지 진행됐는가" 로 결정.
    fun compensate(payment: Payment, cause: Throwable) {
        // Kotlin 의 when — 여러 값을 콤마로 묶어 같은 분기로 보낼 수 있다.
        when (payment.status) {
            // [A] PG 호출 자체가 안 됐거나 호출 중 실패 → 외부에 남긴 자취 없음 → 그냥 FAILED 마킹.
            PaymentStatus.INITIATED,
            PaymentStatus.PG_REQUESTED -> markFailed(payment, cause)

            // [B] PG 승인은 됐는데 내부 기록 전 or 중에 실패 → PG 거래만 취소하면 됨.
            //   COMPENSATING_PG 가 들어오는 경우: 회복 스케줄러가 정체된 보상을 재시도하는 경로.
            PaymentStatus.PG_APPROVED,
            PaymentStatus.COMPENSATING_PG -> cancelPgWithRetry(payment, cause)

            // [C] 내부 기록까지 됐다 → 내부 기록 취소 + PG 취소 양쪽 모두 필요.
            PaymentStatus.INTERNAL_RECORDED,
            PaymentStatus.COMPENSATING_FULL -> cancelFullWithRetry(payment, cause)

            // [D] COMPLETED / CANCELLED / FAILED_NEEDS_REVIEW 등은 보상 불필요(이미 종착).
            else -> {
                logger.debug("No compensation needed for status={}", payment.status)
            }
        }
    }

    // 회복 스케줄러가 호출하는 재시도 진입점 — 그냥 compensate 를 다시 부르는 래퍼.
    fun retry(payment: Payment) {
        compensate(payment, RuntimeException("Recovery retry: ${payment.status}"))
    }

    // ─── PG 만 취소 보상(재시도 포함) ───
    private fun cancelPgWithRetry(payment: Payment, cause: Throwable) {
        // 첫 진입이면 COMPENSATING_PG 로 전이 + 저장(=보상 시작 흔적을 DB 에 남김).
        // 이미 COMPENSATING_PG 면(회복 스케줄러 경로) 재진입이라 전이 생략.
        if (payment.status != PaymentStatus.COMPENSATING_PG) {
            payment.transitionTo(PaymentStatus.COMPENSATING_PG)
            paymentRepository.save(payment)
        }

        // MAX_RETRY 번까지 재시도 — repeat(n) 은 Kotlin stdlib 의 0..n-1 루프.
        //   attempt: 0, 1, 2 (= 3회 시도).
        repeat(MAX_RETRY) { attempt ->
            try {
                // PG 취소 호출(!! 는 pgTransactionId 가 null 이 아님을 단언 — 이 상태에선 무조건 존재).
                pgClientRouter.route(payment.pgType)
                    .cancel(payment.pgTransactionId!!)
                // 성공 시 CANCELLED 로 전이 + 저장 후 함수 종료.
                payment.transitionTo(PaymentStatus.CANCELLED)
                paymentRepository.save(payment)
                return
            } catch (e: Exception) {
                // 실패 시 재시도 카운트 ++, 백오프 후 다음 시도.
                payment.retryCount++
                paymentRepository.save(payment)
                logger.warn(
                    "PG cancel retry failed orderId={} attempt={} cause={}",
                    payment.orderId, attempt + 1, e.message
                )
                Thread.sleep(backoffMs(attempt)) // 지수 백오프(아래 정의).
            }
        }

        // 3회 모두 실패 → 자동 보상 포기, 운영자 알림 발송 후 FAILED 종착.
        markFailed(payment, cause)
        notificationService.notifyOperator(payment, "PG 취소 보상 실패")
    }

    // ─── 내부 기록 + PG 양쪽 모두 취소 보상(재시도 포함) ───
    private fun cancelFullWithRetry(payment: Payment, cause: Throwable) {
        if (payment.status != PaymentStatus.COMPENSATING_FULL) {
            payment.transitionTo(PaymentStatus.COMPENSATING_FULL)
            paymentRepository.save(payment)
        }

        repeat(MAX_RETRY) { attempt ->
            try {
                // 순서 — 내부 기록 먼저 지우고 → PG 취소.
                //   왜 이 순서? "최종 단일 진실(internal)이 사라지지 않은 상태에서 PG 만 취소되는" 갭을 최소화.
                internalPaymentClient.cancelPayment(payment.orderId)
                pgClientRouter.route(payment.pgType)
                    .cancel(payment.pgTransactionId!!)
                payment.transitionTo(PaymentStatus.CANCELLED)
                paymentRepository.save(payment)
                return
            } catch (e: Exception) {
                payment.retryCount++
                paymentRepository.save(payment)
                logger.warn(
                    "Full compensation retry failed orderId={} attempt={} cause={}",
                    payment.orderId, attempt + 1, e.message
                )
                Thread.sleep(backoffMs(attempt))
            }
        }

        markFailed(payment, cause)
        notificationService.notifyOperator(payment, "내부+PG 취소 보상 실패")
    }

    // 자동 보상 포기 종착 — 운영자가 직접 확인 필요.
    private fun markFailed(payment: Payment, cause: Throwable) {
        // cause 를 마지막 인자로 넘기면 SLF4J 가 스택트레이스를 함께 찍어줌.
        logger.error("Marking FAILED_NEEDS_REVIEW orderId={}", payment.orderId, cause)
        payment.transitionTo(PaymentStatus.FAILED_NEEDS_REVIEW)
        paymentRepository.save(payment)
    }

    // 지수 백오프 — 100ms, 200ms, 400ms, ... 최대 2초로 캡.
    //   1 shl attempt: 비트 시프트(1, 2, 4, 8 ...) — 2의 거듭제곱.
    //   coerceAtMost: 상한선 클램프.
    private fun backoffMs(attempt: Int): Long =
        (100L * (1 shl attempt)).coerceAtMost(2_000L)

    companion object {
        // 재시도 횟수 — 상수로 클래스 한 군데에 모아둠.
        private const val MAX_RETRY = 3
    }
}
