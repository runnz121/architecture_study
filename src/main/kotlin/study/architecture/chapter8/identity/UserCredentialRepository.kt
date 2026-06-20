package study.architecture.chapter8.identity

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch8UserCredentialRepository")
interface UserCredentialRepository : JpaRepository<UserCredential, UUID> {
    fun findByEmail(email: String): UserCredential?
}
