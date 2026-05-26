package study.architecture.payment.domain

import java.math.BigDecimal // 금액 — BigDecimal 사용 이유는 Payment.kt 참고(부동소수 오차 방지).

// PaymentCommand: 외부(REST 등)에서 사가/Outbox 진입 시 들어오는 입력 DTO.
//   data class — equals/hashCode/toString/copy 를 컴파일러가 자동 생성.
//   생성자 파라미터가 곧 필드(불변, val).
data class PaymentCommand(
    val orderId: String,           // 주문 고유 ID — Payment 의 PK 로 그대로 사용.
    val amount: BigDecimal,        // 결제 금액.
    val pgType: String,            // 어떤 PG 로 갈지("kcp"/"nicepay" 등). PgClientRouter 에 전달.
    val cardNumber: String = "",   // 카드번호(데모용 — 운영에선 토큰화 필요). 기본 빈 문자열.
    val eventId: String = orderId  // 멱등키. @EventLock 가 이 값으로 분산락 키를 만든다.
                                   //   기본값을 orderId 로 둬서 명시 안 하면 주문 단위로 잠금.
)

// PaymentResult: 사가 처리 결과 응답 DTO.
data class PaymentResult(
    val orderId: String,           // 어떤 주문에 대한 결과인지.
    val success: Boolean,          // 성공/실패 플래그.
    val status: PaymentStatus,     // 최종 영속 상태(COMPLETED / FAILED_NEEDS_REVIEW / CANCELLED 등).
    val message: String? = null    // 실패 시 원인 메시지(성공이면 null).
) {
    // companion object: Java 의 static 메서드 영역.
    //   → 호출은 `PaymentResult.success(...)` / `PaymentResult.failure(...)` 형태.
    companion object {
        // 성공 결과 팩토리 — 상태는 항상 COMPLETED 로 고정.
        fun success(orderId: String) =
            PaymentResult(orderId, true, PaymentStatus.COMPLETED)

        // 실패 결과 팩토리 — 최종 상태(FAILED_NEEDS_REVIEW / CANCELLED 등)는 호출 측에서 주입.
        fun failure(orderId: String, status: PaymentStatus, message: String) =
            PaymentResult(orderId, false, status, message)
    }
}
