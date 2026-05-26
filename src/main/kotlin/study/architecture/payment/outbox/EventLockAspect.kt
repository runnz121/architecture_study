package study.architecture.payment.outbox

import org.aspectj.lang.ProceedingJoinPoint                          // 가로채진 메서드 호출 정보 + proceed() 핸들.
import org.aspectj.lang.annotation.Around                            // Around 어드바이스 정의용.
import org.aspectj.lang.annotation.Aspect                            // 이 클래스가 Aspect 임을 표시.
import org.aspectj.lang.reflect.MethodSignature                      // 메서드 시그니처(파라미터명/타입) 접근용.
import org.springframework.expression.spel.standard.SpelExpressionParser // SpEL 파서(스프링 표현식 언어).
import org.springframework.expression.spel.support.StandardEvaluationContext // SpEL 평가 컨텍스트.
import org.springframework.stereotype.Component                      // 빈 등록.
import java.util.concurrent.ConcurrentHashMap                        // 락 저장소(스레드 안전).
import java.util.concurrent.locks.ReentrantLock                      // 자바 표준 락.

/**
 * @EventLock 처리 AOP.
 *
 * 학습용 in-memory ReentrantLock 으로 구현 — 운영에서는 Redisson 의 RLock 으로 교체.
 * 의도는 동일 eventId 의 중복 처리를 막아 outbox 멱등성을 보장하는 것.
 */
// @Aspect: AspectJ 가 이 클래스를 어드바이스 모음으로 인식.
// @Component: 빈으로 등록(@EnableAspectJAutoProxy 가 켜져 있어야 어드바이스가 실제로 동작).
@Aspect
@Component
class EventLockAspect {

    // 키별 락 풀(in-memory). 같은 key 면 동일 ReentrantLock 인스턴스를 공유.
    //   운영에선 이 자체가 Redis(Redisson) 같은 외부 분산 락 저장소로 빠짐.
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // SpEL 파서는 stateless 라 인스턴스 1개를 공유해서 재사용해도 안전.
    private val parser = SpelExpressionParser()

    // @Around("@annotation(eventLock)"):
    //   "@EventLock 이 붙은 메서드 호출을 통째로 감싸겠다(전/후 모두 가로챔)".
    //   eventLock 파라미터에는 실제 붙은 어노테이션 인스턴스가 들어옴(이걸로 key 표현식을 읽음).
    @Around("@annotation(eventLock)")
    fun around(joinPoint: ProceedingJoinPoint, eventLock: EventLock): Any? {
        // SpEL 로 락 키 추출 후 "event:" prefix 를 붙여 네임스페이스화.
        val key = "event:" + parseKey(joinPoint, eventLock.key)

        // computeIfAbsent: key 가 없으면 새 ReentrantLock 을 만들어 등록하고 반환, 있으면 기존 것 반환.
        //   원자적 연산이라 동시성 안전.
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }

        // tryLock(): 즉시 시도, 못 잡으면 false(블로킹 없음).
        //   다른 호출이 같은 key 로 이미 처리 중이면 즉시 예외 던져서 중복 차단.
        //   → 우리 통합 테스트의 "같은 eventId 동시 호출 → 두 번째 500" 시나리오가 여기서 만들어짐.
        if (!lock.tryLock()) {
            throw EventLockException("Failed to acquire lock: $key")
        }

        // try/finally — 본 메서드 결과를 그대로 돌려주고, 끝나면 무조건 락 해제.
        try {
            // proceed() — 진짜 비즈니스 메서드(PaymentEventService.completePayment 등) 실행.
            return joinPoint.proceed()
        } finally {
            // ReentrantLock 은 잡은 스레드만 해제 가능 → 그 조건 확인 후 안전하게 unlock.
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }

    // SpEL 표현식("#command.eventId" 같은)을 실제 값으로 평가하는 유틸.
    private fun parseKey(joinPoint: ProceedingJoinPoint, expression: String): String {
        // 시그니처를 MethodSignature 로 캐스팅 — 메서드 파라미터 이름/타입 정보를 얻기 위함.
        val signature = joinPoint.signature as MethodSignature

        // SpEL 평가 컨텍스트 — 여기에 변수들을 등록하면 #변수명 으로 표현식에서 참조 가능.
        val context = StandardEvaluationContext()

        // 파라미터 이름 ↔ 실제 인자값 매핑을 컨텍스트에 모두 등록.
        //   ※ Kotlin 컴파일 시 -java-parameters 가 켜져야 parameterNames 가 살아 있다.
        //     Spring Boot 기본 설정에서는 자동으로 켜진다.
        signature.parameterNames.forEachIndexed { i, name ->
            context.setVariable(name, joinPoint.args[i])
        }

        // 표현식 평가 후 String 캐스팅.
        //   ?: error(...) → null 이면 즉시 IllegalStateException 던지는 Kotlin 스타일 가드.
        return parser.parseExpression(expression).getValue(context, String::class.java)
            ?: error("EventLock key resolved to null for expression: $expression")
    }
}

// 락 획득 실패 전용 예외. 호출 측은 이걸 잡아 재시도/거부 응답 등 처리 가능.
class EventLockException(message: String) : RuntimeException(message)
