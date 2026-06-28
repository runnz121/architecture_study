package study.architecture.chapter9.identity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity(name = "Ch9UserCredential")
@Table(name = "ch9_user_credentials")
class UserCredential(
    @Id
    val userId: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true)
    val email: String = "",

    @Column(nullable = false)
    val passwordHash: String = "",

    @Column(nullable = false)
    val lastIdentityPosition: Long = 0
)
