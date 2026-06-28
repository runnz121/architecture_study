package study.architecture.chapter9.registration

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import study.architecture.chapter9.identity.Identity
import study.architecture.chapter9.identity.IdentityProjection
import study.architecture.chapter9.identity.UserCredential
import study.architecture.chapter9.identity.UserCredentialRepository
import study.architecture.chapter9.messagestore.MessageStore
import java.util.UUID

@Service("ch9RegistrationService")
class RegistrationService(
    @Qualifier("ch9MessageStore") private val messageStore: MessageStore,
    @Qualifier("ch9UserCredentialRepository") private val userCredentialRepository: UserCredentialRepository
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    fun registerUser(email: String, password: String) {
        require(email.contains("@")) { "Invalid email format" }
        require(password.length >= 8) { "Password must be at least 8 characters" }

        val existing = userCredentialRepository.findByEmail(email)
        require(existing == null) { "Email already registered" }

        val passwordHash = passwordEncoder.encode(password)

        val userId = UUID.randomUUID()
        val data = """{"userId":"$userId","email":"$email","passwordHash":"$passwordHash"}"""
        messageStore.write(
            streamName = "identity:command-$userId",
            type = "Register",
            data = data
        )
    }

    fun getUserCredentials(): List<UserCredential> =
        userCredentialRepository.findAll()

    /** identity 스트림을 projection 하여 등록/이메일 발송 상태를 조회한다. */
    fun getIdentity(userId: String): Identity =
        messageStore.fetch("identity-$userId", IdentityProjection())
}
