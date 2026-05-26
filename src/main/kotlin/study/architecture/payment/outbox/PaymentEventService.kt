package study.architecture.payment.outbox

import org.springframework.stereotype.Service                  // 빈 등록(서비스 계층).
import org.springframework.transaction.annotation.Transactional // 메서드 트랜잭션 경계.
import study.architecture.payment.domain.Payment               // 결제 엔티티.
import study.architecture.payment.domain.PaymentCommand        // 입력 DTO.
import study.architecture.payment.domain.PaymentStatus         // 상태 enum.
import study.architecture.payment.repository.PaymentRepository // payments 테이블 CRUD.
import tools.jackson.databind.ObjectMapper                     // Jackson 3 — payload 직렬화.

/**
 * 중고나라식 Transactional Outbox 패턴.
 *
 * Payment INSERT 와 OutboxEvent INSERT 가 단일 트랜잭션 안에서 함께 커밋되어
 * 결제 사실과 이벤트 발행이 원적으로 일치한다.
 *
 * 운영에서는 AWS DMS 가 outbox_events 의 binlog 를 CDC 로 캡처해 Kafka 에 발행,
 * 소비자 측은 aggregateId+eventType 으로 멱등 처리.
 */
@Service
class PaymentEventService(
    private val paymentRepository: PaymentRepository, // payments INSERT.
    private val outboxRepository: OutboxRepository,   // outbox_events INSERT.
    private val objectMapper: ObjectMapper            // 이벤트 payload JSON 직렬화기.
) {

    // @EventLock(key = "#command.eventId"):
    //   같은 eventId 로 들어오는 동시 호출을 차단(멱등성 1차 방어선).
    //   AOP 가 메서드 진입 직전에 락을 잡고, 끝나면 풀어준다.
    // @Transactional:
    //   payment.save() 와 outboxEvent.save() 를 하나의 DB 트랜잭션으로 묶어
    //   "둘 다 커밋되거나 둘 다 롤백" 의 원자성 보장.
    // 어노테이션 순서 — AOP 가 먼저 락을 잡고 그 안에서 트랜잭션이 열린다.
    @EventLock(key = "#command.eventId")
    @Transactional
    fun completePayment(command: PaymentCommand): Payment {
        // [1] 결제 엔티티 생성 — 사가를 거치지 않으므로 상태를 COMPLETED 로 직접 세팅.
        //   생성자에서 상태를 박는 건 transitionTo 검증을 우회하는 것 — 이 흐름엔 상태 머신 없음.
        val payment = Payment(
            orderId = command.orderId,
            amount = command.amount,
            pgType = command.pgType,
            status = PaymentStatus.COMPLETED
        )
        // [2] payments 테이블 INSERT — 같은 트랜잭션 안에서 진행.
        paymentRepository.save(payment)

        // [3] 이벤트 payload 를 Map 으로 만들어 JSON 직렬화.
        //   toPlainString(): BigDecimal → 지수표기 없이 깔끔한 문자열(예: "1E+4" 대신 "10000").
        val payload = mapOf(
            "orderId" to payment.orderId,
            "amount" to payment.amount.toPlainString(),
            "pgType" to payment.pgType,
            "eventId" to command.eventId
        )
        // [4] outbox_events INSERT — payments INSERT 와 같은 트랜잭션 안에서 커밋.
        //   둘이 한 트랜잭션 안에 들어가야 "결제는 됐는데 이벤트는 못 보냄" 같은 갭이 사라진다.
        outboxRepository.save(
            OutboxEvent(
                aggregateId = payment.orderId,
                eventType = "PAYMENT_COMPLETED",
                payload = objectMapper.writeValueAsString(payload)
            )
        )
        return payment
    }
}
