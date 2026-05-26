package study.architecture.payment.outbox

import jakarta.persistence.Column          // 컬럼 제약.
import jakarta.persistence.Entity          // JPA 엔티티 매핑.
import jakarta.persistence.GeneratedValue  // PK 자동 생성 전략.
import jakarta.persistence.GenerationType  // 자동 생성 방식 enum.
import jakarta.persistence.Id              // PK 표시.
import jakarta.persistence.Table           // 테이블명 지정.
import java.time.LocalDateTime             // 생성 시각.

// outbox_events 테이블 — 이벤트 발행 사실을 결제와 같은 트랜잭션 안에서 INSERT 한다.
//   → 결제 INSERT 와 이벤트 INSERT 의 원자성 보장 ("결제는 됐는데 이벤트가 안 나갔다" 방지).
//   → 운영에선 AWS DMS 가 이 테이블 binlog 를 CDC 로 캡처해 Kafka 로 발행.
@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    // PK — DB 가 자동 증가시키도록(IDENTITY) 위임. 신규 행이면 null 로 INSERT → DB 가 값 채움.
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // 집계 ID — 보통 도메인 엔티티의 PK(여기선 Payment.orderId).
    //   Kafka 파티션 키로 쓰면 같은 주문 이벤트가 같은 파티션으로 가서 순서 보장됨.
    @Column(nullable = false)
    val aggregateId: String = "",

    // 이벤트 타입 식별자("PAYMENT_COMPLETED", "PAYMENT_REFUNDED" 등).
    //   소비자 측이 분기 처리할 때 사용. 멱등성 키도 보통 (aggregateId + eventType) 조합.
    @Column(nullable = false)
    val eventType: String = "",

    // 직렬화된 이벤트 본문(JSON 문자열). columnDefinition = "TEXT" → 길이 제한 없는 텍스트 컬럼.
    @Column(columnDefinition = "TEXT", nullable = false)
    val payload: String = "{}",

    // 발행 시각 — 디버깅/순서 추적용.
    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
