package study.architecture.payment.pg

import org.springframework.stereotype.Component // 빈 등록 어노테이션.

/**
 * pgType 문자열을 [PgClient] 구현체로 매핑.
 * 신규 PG 사가 흐름 변경 없이 [PgClient] 구현체 추가만으로 확장된다.
 */
@Component
class PgClientRouter(
    // 핵심 트릭: Map<String, PgClient> 타입의 생성자 파라미터에 대해
    //   Spring 이 "PgClient 를 구현한 모든 빈" 을 자동 수집해서 주입한다.
    //   key = 빈 이름. 즉:
    //     @Component("kcp")     class KcpClient     : PgClient  → "kcp"
    //     @Component("nicepay") class NicePayClient : PgClient  → "nicepay"
    //   → clients = { "kcp" → KcpClient bean, "nicepay" → NicePayClient bean }
    private val clients: Map<String, PgClient>
) {

    // pgType 문자열을 받아 해당 PG 어댑터를 반환.
    fun route(pgType: String): PgClient =
        // clients[pgType] 가 null 이면(=등록 안 된 PG) Elvis 연산자(?:)로 즉시 예외.
        // 사가 catch 가 이 예외를 받아 보상 분기를 결정한다.
        clients[pgType] ?: throw IllegalArgumentException("Unknown PG: $pgType")
}
