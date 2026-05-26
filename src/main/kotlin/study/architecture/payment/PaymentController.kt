package study.architecture.payment

import org.springframework.web.bind.annotation.GetMapping      // GET 요청 매핑.
import org.springframework.web.bind.annotation.PathVariable    // URL 경로 변수 바인딩.
import org.springframework.web.bind.annotation.PostMapping     // POST 요청 매핑.
import org.springframework.web.bind.annotation.RequestBody     // 요청 바디 JSON 바인딩.
import org.springframework.web.bind.annotation.RequestMapping  // 컨트롤러 공통 prefix.
import org.springframework.web.bind.annotation.RestController  // JSON 응답 컨트롤러.
import study.architecture.payment.domain.Payment               // 엔티티 응답 타입.
import study.architecture.payment.domain.PaymentCommand        // 요청 바디 DTO.
import study.architecture.payment.domain.PaymentResult         // 사가 응답 DTO.
import study.architecture.payment.outbox.PaymentEventService   // Outbox 흐름 진입점.
import study.architecture.payment.repository.PaymentRepository // 단건 조회용.
import study.architecture.payment.saga.PaymentSagaOrchestrator // Saga 흐름 진입점.

// @RestController: @Controller + @ResponseBody — 메서드 반환값을 자동으로 JSON 직렬화.
// @RequestMapping("/payments"): 이 컨트롤러의 모든 엔드포인트 공통 prefix.
@RestController
@RequestMapping("/payments")
class PaymentController(
    // 3개 협력자 생성자 주입.
    private val orchestrator: PaymentSagaOrchestrator, // 사가 본체.
    private val eventService: PaymentEventService,     // Outbox 본체.
    private val paymentRepository: PaymentRepository   // 조회용.
) {

    // POST /payments/saga — 하나투어식 사가 흐름.
    //   @RequestBody → 요청 본문의 JSON 을 PaymentCommand 로 역직렬화.
    @PostMapping("/saga")
    fun saga(@RequestBody command: PaymentCommand): PaymentResult =
        orchestrator.processPayment(command)

    // POST /payments/outbox — 중고나라식 Transactional Outbox 흐름(사가 없이 한 트랜잭션 INSERT).
    @PostMapping("/outbox")
    fun outbox(@RequestBody command: PaymentCommand): Payment =
        eventService.completePayment(command)

    // GET /payments/{orderId} — 단건 조회(검증/디버깅용).
    //   @PathVariable: URL 경로의 {orderId} 부분을 함수 인자에 바인딩.
    //   findById 는 Optional 을 반환 → Kotlin 친화적으로 orElse(null) 로 nullable 변환.
    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: String): Payment? =
        paymentRepository.findById(orderId).orElse(null)
}
