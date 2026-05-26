package study.architecture.payment.pg

import org.springframework.stereotype.Component         // 빈 등록 어노테이션.
import study.architecture.payment.domain.PaymentCommand // 결제 요청 DTO.
import java.math.BigDecimal                             // 금액 0 비교용.
import java.util.UUID                                   // 가짜 거래ID 생성용.
import java.util.concurrent.atomic.AtomicBoolean        // 스레드 안전한 boolean 플래그.

// @Component("kcp"): 빈 이름을 "kcp" 로 지정.
//   → PgClientRouter 의 Map<String, PgClient> 에 key="kcp" 로 자동 등록된다.
@Component("kcp")
class KcpClient : PgClient {

    // 테스트/데모용 실패 주입 스위치 — 통합 테스트에서 보상 분기를 강제로 트리거하려고 둠.
    // AtomicBoolean: 멀티스레드 환경에서 안전한 boolean(가시성 + 원자성 보장).
    // 운영용 실제 구현에는 이런 플래그 자체가 없어야 함.
    val simulateApproveFailure = AtomicBoolean(false)
    val simulateCancelFailure = AtomicBoolean(false)

    // PG 결제 승인 흉내 — 실제론 HTTP 호출.
    override fun approve(command: PaymentCommand): PgApproveResult {
        // 실패 시뮬레이션이 켜져 있으면 그냥 예외.
        if (simulateApproveFailure.get()) throw PgException("KCP approve failed")
        // 금액 0 이하면 잘못된 요청 — PG 가 거절하는 상황을 흉내.
        if (command.amount <= BigDecimal.ZERO) throw PgException("Invalid amount")
        // 성공 — 거래ID 를 만들어 반환. 실제 PG 응답에서 받는 값에 해당.
        return PgApproveResult("KCP-${UUID.randomUUID()}")
    }

    // PG 결제 취소 흉내 — 보상 핸들러가 부른다.
    override fun cancel(pgTransactionId: String): PgCancelResult {
        if (simulateCancelFailure.get()) throw PgException("KCP cancel failed")
        // 취소 영수증 ID 를 만들어 반환.
        return PgCancelResult("CANCEL-$pgTransactionId")
    }
}
