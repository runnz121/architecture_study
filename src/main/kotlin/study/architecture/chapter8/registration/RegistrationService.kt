package study.architecture.chapter8.registration

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import study.architecture.chapter8.identity.UserCredential
import study.architecture.chapter8.identity.UserCredentialRepository
import study.architecture.chapter8.messagestore.MessageStore
import java.util.UUID

@Service("ch8RegistrationService")
class RegistrationService(
    @Qualifier("ch8MessageStore") private val messageStore: MessageStore,
    @Qualifier("ch8UserCredentialRepository") private val userCredentialRepository: UserCredentialRepository
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
}
