package study.architecture.chapter9.sendemail

/**
 * Chapter 9 §3-2: 전송 실패를 나타내는 타입.
 *
 * 책의 potentialError 패턴 — JS에서는 catch 핸들러에서 stack trace를 잃을 수 있어
 * 에러 발생 전에 SendError를 미리 만들어 두지만, Kotlin은 예외 생성 시점에
 * stack trace가 캡처되므로 transport 실패를 이 타입으로 감싸 던지기만 하면 된다.
 * 핸들러는 이 타입을 잡아 Failed 이벤트를 기록한다.
 */
class SendError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
