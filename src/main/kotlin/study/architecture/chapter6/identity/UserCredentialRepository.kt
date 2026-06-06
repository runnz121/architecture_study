package study.architecture.chapter6.identity

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch6UserCredentialRepository")
interface UserCredentialRepository : JpaRepository<UserCredential, UUID> {
    fun findByEmail(email: String): UserCredential?
}
