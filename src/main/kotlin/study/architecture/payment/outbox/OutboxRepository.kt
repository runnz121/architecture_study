package study.architecture.payment.outbox

import org.springframework.data.jpa.repository.JpaRepository // 기본 CRUD 자동 제공.

// JpaRepository<OutboxEvent, Long>
//   → Spring 이 런타임에 프록시 구현체를 만들어 빈으로 등록.
//   → save / findById / delete / findAll 등을 별도 구현 없이 사용 가능.
//   → 학습용에선 save 만 쓰지만, 실제 운영(폴링 발행 방식)이라면
//     "발행 안 된 이벤트 조회 / 발행 후 삭제" 메서드를 여기 추가.
interface OutboxRepository : JpaRepository<OutboxEvent, Long>
