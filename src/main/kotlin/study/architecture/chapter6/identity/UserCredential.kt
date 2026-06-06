package study.architecture.chapter6.identity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity(name = "Ch6UserCredential")
@Table(name = "ch6_user_credentials")
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
