package study.architecture.chapter9.identity

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class RenderedEmail(
    val subject: String,
    val text: String,
    val html: String
)

/**
 * Chapter 9 §7: 등록 환영 이메일 렌더링 + 결정적 emailId 생성
 *
 * 본문에는 큰따옴표(")를 쓰지 않는다 — 프로젝트 전반이 문자열 템플릿으로 JSON을
 * 구성하므로 본문에 "가 들어가면 JSON이 깨진다.
 */
object RegistrationEmail {

    // 책의 uuidv5Namespace
    private val NAMESPACE = UUID.fromString("0c46e0b7-dfaf-443a-b150-053b67905cc2")

    fun render(email: String): RenderedEmail {
        val subject = "Welcome to Video Tutorials"
        val text = "Welcome to Video Tutorials. We are glad you are here. " +
            "Sign in at any time to start watching."
        val html = "<h1>Welcome to Video Tutorials</h1>" +
            "<p>We are glad you are here, $email.</p>" +
            "<p>Sign in at any time to start watching.</p>"
        return RenderedEmail(subject, text, html)
    }

    /**
     * UUID v5: 알려진 데이터(이메일 주소)를 해싱하여 결정적 UUID를 만든다.
     * 같은 이메일 주소는 항상 같은 emailId를 생성하므로 멱등성에 활용된다.
     * (Java 기본 nameUUIDFromBytes는 MD5 기반 v3이므로, 책과 동일하게 SHA-1 기반 v5를 직접 구현)
     */
    fun emailId(email: String): UUID = uuidV5(email, NAMESPACE)

    private fun uuidV5(name: String, namespace: UUID): UUID {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(uuidToBytes(namespace))
        md.update(name.toByteArray(Charsets.UTF_8))
        val hash = md.digest()

        val bytes = hash.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte() // version 5
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
        return bytesToUuid(bytes)
    }

    private fun uuidToBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    private fun bytesToUuid(bytes: ByteArray): UUID {
        val bb = ByteBuffer.wrap(bytes)
        val msb = bb.long
        val lsb = bb.long
        return UUID(msb, lsb)
    }
}
