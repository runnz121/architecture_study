package study.architecture.payment.pg

import study.architecture.payment.domain.PaymentCommand // 결제 입력 DTO(사가/Outbox 진입 시 들어옴).

/**
 * 외부 PG 어댑터. 사가 흐름은 이 인터페이스에 의존하고
 * 실제 KCP/NicePay 구현은 [PgClientRouter]가 pgType 으로 라우팅한다.
 */
// interface: 사가 코드가 "구체 PG"를 모르게 하기 위한 경계.
//   → KcpClient/NicePayClient/(추후) TossClient 등은 모두 이 시그니처만 구현.
interface PgClient {
    // 결제 승인(approve) — 성공 시 거래ID 를 받음. 실패 시 PgException 등을 던지도록 약속.
    fun approve(command: PaymentCommand): PgApproveResult

    // 결제 취소(cancel) — 보상 단계에서 호출. 받은 거래ID 를 그대로 사용.
    fun cancel(pgTransactionId: String): PgCancelResult
}

// PG 가 발급한 거래 식별자를 담는 결과 DTO. 사가가 Payment.pgTransactionId 에 저장 후 보상 때 재사용.
data class PgApproveResult(val transactionId: String)

// 취소 영수증 ID — 운영 추적용. 실제 보상 분기에는 사용 안 함.
data class PgCancelResult(val cancelTransactionId: String)

// PG 호출 실패 전용 unchecked 예외. RuntimeException 상속이라 메서드 시그니처에 throws 안 적어도 됨.
class PgException(message: String) : RuntimeException(message)
