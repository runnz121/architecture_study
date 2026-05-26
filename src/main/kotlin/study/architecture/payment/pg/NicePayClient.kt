package study.architecture.payment.pg

import org.springframework.stereotype.Component         // 빈 등록.
import study.architecture.payment.domain.PaymentCommand // 결제 요청 DTO.
import java.math.BigDecimal                             // 금액 비교.
import java.util.UUID                                   // 거래ID 생성.

// @Component("nicepay"): 빈 이름 "nicepay" → Router 의 Map 에 key="nicepay" 로 들어간다.
@Component("nicepay")
class NicePayClient : PgClient {

    // KCP 와 동일한 인터페이스만 구현 — "전략 패턴" 의 또 다른 전략.
    override fun approve(command: PaymentCommand): PgApproveResult {
        if (command.amount <= BigDecimal.ZERO) throw PgException("Invalid amount")
        // 거래ID 접두사를 "NICE-" 로 줘서 어떤 PG 가 발급했는지 한눈에 구분되게.
        return PgApproveResult("NICE-${UUID.randomUUID()}")
    }

    override fun cancel(pgTransactionId: String): PgCancelResult {
        return PgCancelResult("CANCEL-$pgTransactionId")
    }
}
