package study.architecture.chapter7.registration

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController("ch7RegistrationController")
@RequestMapping("/api/chapter7")
class RegistrationController(
    @org.springframework.beans.factory.annotation.Qualifier("ch7RegistrationService")
    private val registrationService: RegistrationService
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
}
