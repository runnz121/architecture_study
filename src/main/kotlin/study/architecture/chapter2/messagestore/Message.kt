package study.architecture.chapter2.messagestore

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "messages")
class Message(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val streamName: String = "",

    @Column(nullable = false)
    val type: String = "",

    @Column(nullable = false)
    val position: Long = 0,

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    val globalPosition: Long = 0,

    @Column(columnDefinition = "TEXT")
    val data: String = "{}",

    @Column(columnDefinition = "TEXT")
    val metadata: String = "{}",

    @Column(nullable = false)
    val time: LocalDateTime = LocalDateTime.now()
)
