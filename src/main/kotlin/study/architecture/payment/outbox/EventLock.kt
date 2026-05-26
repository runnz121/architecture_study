package study.architecture.payment.outbox

/**
 * 메서드 단위 분산락 어노테이션.
 * [key] 는 SpEL 표현식으로 메서드 파라미터에서 락 키를 추출한다. 예: `#command.eventId`.
 */
// @Target — 이 어노테이션을 어디에 붙일 수 있는지 제한.
//   AnnotationTarget.FUNCTION: 함수(메서드)에만 붙일 수 있다.
@Target(AnnotationTarget.FUNCTION)

// @Retention — 어노테이션 정보가 언제까지 살아남는지.
//   RUNTIME: 런타임에 리플렉션으로 읽을 수 있어야 함. AOP 가 어노테이션을 보고 동작하려면 RUNTIME 필수.
@Retention(AnnotationRetention.RUNTIME)

// annotation class — 사용자 정의 어노테이션 정의 키워드.
//   key 파라미터 = SpEL 표현식("#command.eventId" 같은 형태).
//   AOP 가 이 표현식을 평가해서 메서드 파라미터 안에서 실제 락 키를 뽑아낸다.
annotation class EventLock(val key: String)
