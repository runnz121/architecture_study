package study.architecture.chapter9.identity

import study.architecture.chapter9.messagestore.Message
import study.architecture.chapter9.messagestore.Projection

data class Identity(
    val id: String? = null,
    val email: String? = null,
    val isRegistered: Boolean = false,
    val registrationEmailSent: Boolean = false
)

class IdentityProjection : Projection<Identity> {
    override fun init() = Identity()

    override fun apply(entity: Identity, event: Message): Identity = when (event.type) {
        "Registered" -> entity.copy(
            id = extractJsonField(event.data, "userId"),
            email = extractJsonField(event.data, "email"),
            isRegistered = true
        )
        "RegistrationEmailSent" -> entity.copy(
            registrationEmailSent = true
        )
        else -> entity
    }

    private fun extractJsonField(json: String, field: String): String {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }
}
