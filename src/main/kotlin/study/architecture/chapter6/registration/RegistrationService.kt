package study.architecture.chapter6.registration

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import study.architecture.chapter6.identity.UserCredential
import study.architecture.chapter6.identity.UserCredentialRepository
import study.architecture.chapter6.messagestore.MessageStore
import java.util.UUID

@Service("ch6RegistrationService")
class RegistrationService(
    @Qualifier("ch6MessageStore") private val messageStore: MessageStore,
    private val userCredentialRepository: UserCredentialRepository
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    fun registerUser(email: String, password: String) {
        // 1. Superficial validation
        require(email.contains("@")) { "Invalid email format" }
        require(password.length >= 8) { "Password must be at least 8 characters" }

        // 2. 중복 체크 (eventually consistent)
        val existing = userCredentialRepository.findByEmail(email)
        require(existing == null) { "Email already registered" }

        // 3. Password hashing
        val passwordHash = passwordEncoder.encode(password)

        // 4. Register command 발행
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
}
