package study.architecture.payment.saga

import org.slf4j.LoggerFactory                                  // 로깅.
import org.springframework.data.repository.findByIdOrNull       // JpaRepository 의 Kotlin 확장 — Optional 대신 null 반환.
import org.springframework.stereotype.Service                   // 빈 등록(서비스 계층 의미).
import study.architecture.payment.domain.PaymentCommand         // 입력 DTO.
import study.architecture.payment.domain.PaymentResult          // 출력 DTO.
import study.architecture.payment.domain.PaymentStatus          // 9-상태 enum.
import study.architecture.payment.internalpay.InternalPaymentClient // 내부 결제 서버 어댑터.
import study.architecture.payment.pg.PgClientRouter             // PG 라우터(전략 디스패처).
import study.architecture.payment.repository.PaymentRepository  // payments 테이블 CRUD.

/**
 * 하나투어 결제 사가 오케스트레이터.
 *
 * 메서드 본체에는 @Transactional이 없다 — 단일 트랜잭션 롤백은
 * "어디까지 진행했는가"를 지워 보상 분기를 무너뜨리기 때문이다.
 * 모든 상태 전이는 [PaymentStateWriter] 의 REQUIRES_NEW로 분리 커밋된다.
 */
@Service
class PaymentSagaOrchestrator(
    // 생성자 주입 — 5개 협력자를 모두 주입.
    private val paymentRepository: PaymentRepository,        // 최신 상태 재조회용.
    private val pgClientRouter: PgClientRouter,              // PG 어댑터 라우팅.
    private val internalPaymentClient: InternalPaymentClient, // 내부 결제 서버 호출.
    private val compensationHandler: CompensationHandler,    // catch 블록에서 보상 트리거.
    private val stateWriter: PaymentStateWriter              // REQUIRES_NEW 상태 전이 영속화.
) {
    // 인스턴스마다 로거 1개. 클래스명 기반 로그 카테고리.
    private val logger = LoggerFactory.getLogger(PaymentSagaOrchestrator::class.java)

    // 사가 본체. 메서드 자체엔 @Transactional 안 붙임 — 각 전이 단위 커밋이 핵심 불변식이라.
    fun processPayment(command: PaymentCommand): PaymentResult {
        // [1] INITIATED 로 INSERT — 즉시 별도 트랜잭션으로 커밋.
        //   var: 이후 단계에서 재할당하기 위함(persistTransition 이 갱신된 엔티티를 반환).
        var payment = stateWriter.persistInitial(command)

        try {
            // [2] PG 호출 직전 상태로 전이 후 커밋.
            //   여기서 끊겨도 DB 엔 "PG_REQUESTED" 상태가 남는다 → 보상 핸들러가 markFailed 처리 가능.
            payment = stateWriter.persistTransition(payment, PaymentStatus.PG_REQUESTED)

            // [3] PG 어댑터 라우팅 후 승인 호출 — 외부 시스템 호출(여기서 가장 자주 터짐).
            val pgResult = pgClientRouter.route(command.pgType).approve(command)
            // PG 가 발급한 거래ID 를 엔티티에 박는다 — 보상(취소) 때 다시 PG 에 전달해야 함.
            payment.pgTransactionId = pgResult.transactionId
            // [4] PG_APPROVED 로 전이 + 커밋. 이 시점 이후 실패는 "PG 만 취소" 보상으로 분기.
            payment = stateWriter.persistTransition(payment, PaymentStatus.PG_APPROVED)

            // [5] 내부 결제 서버에 사실 기록(외부 호출). 여기서 터지면 보상 핸들러가 PG 만 취소.
            internalPaymentClient.recordPayment(payment)
            // [6] INTERNAL_RECORDED 로 전이 + 커밋. 이후 실패는 "내부+PG 풀 보상" 으로 분기.
            payment = stateWriter.persistTransition(payment, PaymentStatus.INTERNAL_RECORDED)

            // [7] 최종 COMPLETED 로 전이 + 커밋 → 사가 성공 종료.
            payment = stateWriter.persistTransition(payment, PaymentStatus.COMPLETED)
            return PaymentResult.success(payment.orderId)

        } catch (e: Exception) {
            // ─── 어떤 단계에서든 예외가 터지면 여기로 진입 ───
            logger.warn("Saga failed orderId={} cause={}", payment.orderId, e.message)

            // 최신 영속 상태로 다시 읽어온다 — 인메모리 객체와 DB 가 불일치할 수 있어서.
            //   findByIdOrNull: JpaRepository 의 Kotlin 확장(Optional 대신 nullable).
            val latest = paymentRepository.findByIdOrNull(payment.orderId)
                ?: return PaymentResult.failure(
                    payment.orderId, payment.status, e.message ?: "Unknown"
                )

            // 보상 분기는 "최신 영속 상태" 를 보고 핸들러가 결정 — 이게 사가 핵심 아이디어.
            compensationHandler.compensate(latest, e)

            // 보상 후 최종 상태(CANCELLED / FAILED_NEEDS_REVIEW)를 다시 읽어서 응답에 담는다.
            val final = paymentRepository.findByIdOrNull(payment.orderId) ?: latest
            return PaymentResult.failure(
                final.orderId, final.status, e.message ?: "Unknown"
            )
        }
    }
}
