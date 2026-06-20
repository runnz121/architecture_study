package study.architecture.chapter8.authentication

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import study.architecture.chapter8.identity.UserCredentialRepository
import study.architecture.chapter8.messagestore.MessageStore

/**
 * 인증은 View Data(user_credentials)에 대한 query다.
 * Component가 아닌 Application 레이어에서 동기적으로 처리한다.
 *
 * 흐름: loadCredential → validatePassword → writeLoggedInEvent
 * 실패 시: UserLoginFailed 이벤트 기록 후 AuthenticationError throw
 */
@Service("ch8AuthenticationService")
class AuthenticationService(
    @Qualifier("ch8MessageStore") private val messageStore: MessageStore,
    @Qualifier("ch8UserCredentialRepository") private val userCredentialRepository: UserCredentialRepository
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    fun authenticate(email: String, password: String): Map<String, Any> {
        // 1. email로 credential 조회
        val credential = userCredentialRepository.findByEmail(email)

        // 2. credential 미존재 — 이벤트 기록 없이 AuthenticationError
        //    (존재하지 않는 이메일은 도메인 정보가 아니므로 이벤트 불필요)
        if (credential == null) {
            throw AuthenticationError()
        }

        // 3. 비밀번호 검증
        if (!passwordEncoder.matches(password, credential.passwordHash)) {
            // UserLoginFailed 이벤트 기록
            val eventData = """{"userId":"${credential.userId}","reason":"Incorrect password"}"""
            messageStore.write(
                streamName = "authentication-${credential.userId}",
                type = "UserLoginFailed",
                data = eventData
            )
            throw AuthenticationError()
        }

        // 4. UserLoggedIn 이벤트 기록
        val eventData = """{"userId":"${credential.userId}"}"""
        messageStore.write(
            streamName = "authentication-${credential.userId}",
            type = "UserLoggedIn",
            data = eventData
        )

        return mapOf("userId" to credential.userId, "email" to credential.email)
    }
}
