package study.architecture.payment.repository

import org.springframework.data.jpa.repository.JpaRepository  // 기본 CRUD 메서드 자동 제공.
import org.springframework.data.jpa.repository.Query          // 커스텀 JPQL 쿼리 정의.
import org.springframework.data.repository.query.Param        // 쿼리 파라미터 바인딩.
import study.architecture.payment.domain.Payment              // 엔티티 타입.
import study.architecture.payment.domain.PaymentStatus        // 상태 enum.
import java.time.LocalDateTime                                // 시각 파라미터.

// JpaRepository<엔티티 타입, PK 타입>
//   → save / findById / findAll / delete 등이 자동 제공된다(별도 구현 불필요).
//   → Spring 이 런타임에 프록시 구현체를 만들어 빈으로 등록.
interface PaymentRepository : JpaRepository<Payment, String> {

    // 커스텀 조회: "지정 상태들 중 하나이면서 updatedAt 이 일정 시각 이전" 인 결제 목록.
    //   → SagaRecoveryScheduler 가 "정체된 사가" 를 골라낼 때 사용.
    // JPQL 은 Java 식 엔티티 이름/필드명을 그대로 쓴다(SQL 의 테이블/컬럼명 아님).
    @Query(
        """
        SELECT p FROM Payment p
        WHERE p.status IN :statuses AND p.updatedAt < :beforeUpdatedAt
        """
    )
    fun findStuckPayments(
        // @Param("statuses"): JPQL 의 :statuses 자리에 이 파라미터를 바인딩.
        @Param("statuses") statuses: List<PaymentStatus>,
        @Param("beforeUpdatedAt") beforeUpdatedAt: LocalDateTime
    ): List<Payment>
}
