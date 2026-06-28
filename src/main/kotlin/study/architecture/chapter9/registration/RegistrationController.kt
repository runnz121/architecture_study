package study.architecture.chapter9.registration

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController("ch9RegistrationController")
@RequestMapping("/api/chapter9")
class RegistrationController(
    @Qualifier("ch9RegistrationService") private val registrationService: RegistrationService
) {

    data class RegisterRequest(val email: String, val password: String)

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterRequest): Map<String, String> {
        registrationService.registerUser(request.email, request.password)
        return mapOf("status" to "ok")
    }

    @GetMapping("/user-credentials")
    fun userCredentials(): List<Map<String, Any>> =
        registrationService.getUserCredentials().map {
            mapOf("userId" to it.userId, "email" to it.email)
        }

    /** 등록 + 환영 이메일 발송이 완료됐는지 projection으로 확인 */
    @GetMapping("/identities/{userId}")
    fun identity(@PathVariable userId: String): Map<String, Any?> {
        val identity = registrationService.getIdentity(userId)
        return mapOf(
            "id" to identity.id,
            "email" to identity.email,
            "isRegistered" to identity.isRegistered,
            "registrationEmailSent" to identity.registrationEmailSent
        )
    }
}
