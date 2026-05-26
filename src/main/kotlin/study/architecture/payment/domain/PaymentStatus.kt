package study.architecture.payment.domain

/**
 * 결제 사가의 9-상태 머신.
 *
 * 진행 5단계(INITIATED → COMPLETED), 보상 3단계(COMPENSATING_PG/FULL → CANCELLED),
 * 자동 보상이 실패해 운영자 개입이 필요한 종착 1단계(FAILED_NEEDS_REVIEW).
 *
 * "상태가 곧 보상의 트리거" — 다음 보상 동작은 현재 영속 상태로부터만 파생된다.
 */
// enum class — 정해진 값들만 가질 수 있는 타입. 컴파일 타임에 상태 누락을 잡을 수 있다.
enum class PaymentStatus {
    // ─── 진행 단계(정상 흐름) ───
    INITIATED,         // 사가 시작 직후 Payment row 가 INSERT 된 직후의 상태.
    PG_REQUESTED,      // PG 호출 직전에 박는 상태(approve 요청을 보내고 응답을 기다리는 중).
    PG_APPROVED,       // PG 가 승인 응답을 줘서 거래ID 를 받은 직후. 여기서 실패 시 PG 취소만 필요.
    INTERNAL_RECORDED, // 내부 결제 서버(Source of Truth)에 결제 사실 기록 완료. 여기서 실패 시 풀 보상.
    COMPLETED,         // 최종 성공. 사가 종료.

    // ─── 보상 진행 단계 ───
    COMPENSATING_PG,   // PG 취소만 진행 중(아직 내부 기록 전이라 PG 만 되돌리면 됨).
    COMPENSATING_FULL, // 내부 기록 + PG 취소를 함께 진행 중.
    CANCELLED,         // 보상 성공으로 깔끔히 취소된 종착 상태.

    // ─── 자동 복구 실패 ───
    FAILED_NEEDS_REVIEW // 자동 보상까지 모두 실패 → 운영자 알림 후 수동 개입이 필요한 종착 상태.
}
