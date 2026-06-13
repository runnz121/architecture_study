package study.architecture.chapter7.identity

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch7UserCredentialRepository")
interface UserCredentialRepository : JpaRepository<UserCredential, UUID> {
    fun findByEmail(email: String): UserCredential?
}
