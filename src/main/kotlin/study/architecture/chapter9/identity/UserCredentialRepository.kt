package study.architecture.chapter9.identity

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch9UserCredentialRepository")
interface UserCredentialRepository : JpaRepository<UserCredential, UUID> {
    fun findByEmail(email: String): UserCredential?
}
