package study.architecture.chapter8.authentication

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController("ch8AuthenticationController")
@RequestMapping("/api/chapter8")
class AuthenticationController(
    @Qualifier("ch8AuthenticationService") private val authenticationService: AuthenticationService
) {

    data class LoginRequest(val email: String, val password: String)

    @PostMapping("/log-in")
    fun logIn(@RequestBody request: LoginRequest): ResponseEntity<Map<String, Any>> {
        return try {
            val result = authenticationService.authenticate(request.email, request.password)
            ResponseEntity.ok(result)
        } catch (e: AuthenticationError) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "Authentication failed"))
        }
    }
}
