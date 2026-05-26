package study.architecture.payment.saga

// Spring 빈 등록용 어노테이션 — @Component 가 붙어야 컴포넌트 스캔으로 잡힌다.
import org.springframework.stereotype.Component
// 트랜잭션 전파(propagation) 옵션 enum — REQUIRES_NEW 를 쓰기 위해 import.
import org.springframework.transaction.annotation.Propagation
// 메서드 단위 트랜잭션을 열어주는 어노테이션.
import org.springframework.transaction.annotation.Transactional
// 결제 도메인 엔티티(JPA Entity).
import study.architecture.payment.domain.Payment
// 결제 요청 DTO(주문ID/금액/PG타입 등 사가 시작 시 들어오는 입력).
import study.architecture.payment.domain.PaymentCommand
// 9-상태 enum — 사가 진행/보상/실패 단계를 표현.
import study.architecture.payment.domain.PaymentStatus
// Spring Data JPA Repository — payments 테이블 CRUD.
import study.architecture.payment.repository.PaymentRepository

/**
 * 각 상태 전이를 독립 트랜잭션(REQUIRES_NEW)으로 영속화한다.
 *
 * 오케스트레이터가 같은 클래스 안에서 호출하면 Spring AOP 셀프 인보케이션 한계로
 * 프록시를 거치지 않아 새 트랜잭션이 열리지 않는다 — 별도 빈으로 분리해
 * "전이 단위마다 커밋"이라는 사가 핵심 불변식을 보장한다.
 */
@Component // ← 이 클래스를 스프링 컨테이너에 빈으로 등록(다른 곳에서 주입 가능).
class PaymentStateWriter(
    // 생성자 주입 — paymentRepository 는 스프링이 PaymentRepository 구현체(프록시)를 꽂아준다.
    // val + private 라 외부에서 접근 불가, 불변.
    private val paymentRepository: PaymentRepository
) {

    // REQUIRES_NEW: 호출자에 기존 트랜잭션이 있어도 "무조건 새 트랜잭션을 시작"한다.
    //   → persistInitial 안에서 일어난 INSERT 는 메서드가 끝나는 순간 즉시 커밋된다.
    //   → 오케스트레이터의 try-catch 가 예외를 받아도 이 INSERT 는 롤백되지 않는다
    //     (= "사가가 시작됐다"는 사실이 DB 에 영구히 남는다 → 보상/회복 가능).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persistInitial(command: PaymentCommand): Payment =
        // = 표기는 Kotlin 의 single-expression function — 값 그대로 반환.
        // repository.save() 는 신규 엔티티면 INSERT, 기존 엔티티면 merge(UPDATE).
        paymentRepository.save(
            // 새 Payment 엔티티를 만든다. status 는 생성자 기본값(INITIATED)이 적용됨.
            // 따라서 첫 row 의 상태는 항상 INITIATED 로 INSERT 된다.
            Payment(
                orderId = command.orderId, // 주문번호 — Payment 의 PK.
                amount = command.amount,   // 결제 금액.
                pgType = command.pgType    // 사용할 PG 식별자("kcp", "nicepay" 등) — Router 가 이걸로 라우팅.
            )
        )

    // 동일하게 REQUIRES_NEW — 매 상태 전이가 "독립 커밋" 이라는 사가 불변식을 보장한다.
    //   → PG_REQUESTED → PG_APPROVED → INTERNAL_RECORDED → COMPLETED 사이의
    //     각 단계가 별개의 DB 트랜잭션으로 끊겨 커밋된다.
    //   → 어디서 끊기든 "어디까지 진행됐다"가 DB 에 남고, 그 상태로부터 보상 분기가 갈린다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persistTransition(payment: Payment, next: PaymentStatus): Payment {
        // 상태 머신 검증 + 상태 변경.
        //   → Payment.transitionTo() 안에서 allowedTransitions 맵을 보고
        //     "현재 상태 → next" 가 허용된 경로가 아니면 IllegalArgumentException 을 던진다.
        //   → 잘못된 상태 점프를 방지하는 도메인 가드.
        payment.transitionTo(next)
        // 변경된 엔티티를 저장 → JPA merge → UPDATE 쿼리 발행.
        // 이미 PK(orderId) 가 있는 엔티티이므로 INSERT 가 아닌 UPDATE 로 동작.
        return paymentRepository.save(payment)
    }
}
