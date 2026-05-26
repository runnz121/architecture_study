package study.architecture.payment.domain

// JPA(Jakarta Persistence) — 엔티티 매핑용 어노테이션들.
import jakarta.persistence.Column     // 컬럼 제약(nullable, precision 등) 지정.
import jakarta.persistence.Entity     // 이 클래스를 DB 테이블에 매핑함을 선언.
import jakarta.persistence.EnumType   // enum 저장 방식(ORDINAL/STRING) 지정.
import jakarta.persistence.Enumerated // enum 컬럼 저장 방식 지시 어노테이션.
import jakarta.persistence.Id         // PK(Primary Key) 표시.
import jakarta.persistence.Table      // 테이블 이름/스키마 지정.
import java.math.BigDecimal           // 금액 — Double 쓰면 부동소수 오차 → 결제에선 BigDecimal 필수.
import java.time.LocalDateTime        // 마지막 갱신 시각.

// @Entity: JPA 가 이 클래스를 영속 엔티티로 인식.
// @Table(name = "payments"): 매핑되는 테이블명 강제(기본은 클래스명 lowercase).
@Entity
@Table(name = "payments")
class Payment(
    // @Id: PK. orderId 자체가 자연키(natural key) 역할 — 별도 surrogate ID 안 둠.
    // val + 기본값 "" : JPA 가 reflection 으로 빈 생성자를 호출할 수 있게 모두 기본값 부여.
    @Id
    val orderId: String = "",

    // precision/scale: BigDecimal 컬럼의 자리수 — 정수 17자리 + 소수 2자리(19,2).
    // nullable = false: NOT NULL 제약.
    @Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal = BigDecimal.ZERO,

    // 어떤 PG 를 사용했는지 식별자 문자열("kcp"/"nicepay"). PgClientRouter 가 이걸로 라우팅.
    @Column(nullable = false)
    val pgType: String = "kcp",

    // @Enumerated(STRING): enum 을 "PG_APPROVED" 같은 문자열로 저장(ORDINAL 인덱스 저장 시,
    //   enum 순서 바뀌면 데이터 의미가 바뀌어 위험 → STRING 이 안전한 표준).
    // var: 상태는 사가가 진행되며 변한다(transitionTo 로만 변경).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus = PaymentStatus.INITIATED,

    // PG 가 발급한 거래 식별자 — approve 응답에서 받아 저장, 취소(cancel) 때 다시 사용.
    // PG 호출 전엔 null 이라서 nullable.
    var pgTransactionId: String? = null,

    // 보상 재시도 횟수 — 운영 가시성/디버깅용.
    @Column(nullable = false)
    var retryCount: Int = 0,

    // 마지막 갱신 시각 — SagaRecoveryScheduler 가 "5분 이상 정체된 사가" 를 이걸로 판별.
    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {

    // 상태 머신의 유일한 변경 통로. status 를 직접 대입하지 말고 반드시 이 메서드로만 바꾼다.
    fun transitionTo(newStatus: PaymentStatus) {
        // require(boolean) { 메시지 }: false 면 IllegalArgumentException 던짐(Kotlin stdlib).
        // allowedTransitions[현재상태] → Set<허용 다음상태> 를 꺼내서
        //   newStatus 가 포함되어 있는지 확인. 허용 안 된 점프면 즉시 예외 → 잘못된 사가 흐름 차단.
        require(allowedTransitions[status]?.contains(newStatus) == true) {
            "Invalid transition: $status -> $newStatus"
        }
        this.status = newStatus              // 상태 갱신.
        this.updatedAt = LocalDateTime.now() // 갱신 시각도 함께 — 회복 스케줄러가 이걸 본다.
    }

    // companion object: Java 의 static 영역에 해당.
    //   → allowedTransitions 는 클래스당 한 번만 생성되고 모든 인스턴스가 공유.
    companion object {
        // 상태 머신 전이 규칙(허용된 다음 상태 집합).
        // 여기에 없는 출발 상태는 "더 이상 전이 불가" (예: COMPLETED, CANCELLED, FAILED_NEEDS_REVIEW — 종착 상태).
        private val allowedTransitions: Map<PaymentStatus, Set<PaymentStatus>> = mapOf(
            // INITIATED 에서는 → PG 호출 시작(PG_REQUESTED) 또는 즉시 실패(FAILED_NEEDS_REVIEW)
            PaymentStatus.INITIATED to setOf(
                PaymentStatus.PG_REQUESTED,
                PaymentStatus.FAILED_NEEDS_REVIEW
            ),
            // PG_REQUESTED 에서는 → PG 승인 성공(PG_APPROVED) 또는 PG 호출 자체가 터짐(FAILED_NEEDS_REVIEW)
            PaymentStatus.PG_REQUESTED to setOf(
                PaymentStatus.PG_APPROVED,
                PaymentStatus.FAILED_NEEDS_REVIEW
            ),
            // PG_APPROVED 에서는 → 내부 기록 성공(INTERNAL_RECORDED) 또는 PG-only 보상 시작(COMPENSATING_PG)
            //   ※ "PG 만 되돌리면 되는" 분기점.
            PaymentStatus.PG_APPROVED to setOf(
                PaymentStatus.INTERNAL_RECORDED,
                PaymentStatus.COMPENSATING_PG
            ),
            // INTERNAL_RECORDED 에서는 → 최종 완료(COMPLETED) 또는 풀 보상 시작(COMPENSATING_FULL)
            //   ※ "내부 기록 + PG 둘 다 되돌려야 하는" 분기점.
            PaymentStatus.INTERNAL_RECORDED to setOf(
                PaymentStatus.COMPLETED,
                PaymentStatus.COMPENSATING_FULL
            ),
            // 보상 진행 중에서는 → 보상 성공(CANCELLED) 또는 재시도까지 모두 실패(FAILED_NEEDS_REVIEW)
            PaymentStatus.COMPENSATING_PG to setOf(
                PaymentStatus.CANCELLED,
                PaymentStatus.FAILED_NEEDS_REVIEW
            ),
            PaymentStatus.COMPENSATING_FULL to setOf(
                PaymentStatus.CANCELLED,
                PaymentStatus.FAILED_NEEDS_REVIEW
            )
            // COMPLETED / CANCELLED / FAILED_NEEDS_REVIEW 는 키 자체가 없음 → 종착 상태.
        )
    }
}
