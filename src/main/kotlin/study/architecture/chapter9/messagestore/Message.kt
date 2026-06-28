package study.architecture.chapter9.messagestore

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity(name = "Ch9Message")
@Table(name = "ch9_messages")
class Message(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val streamName: String = "",

    @Column(nullable = false)
    val type: String = "",

    @Column(nullable = false)
    val position: Long = 0,

    @Column(nullable = false, updatable = false)
    val globalPosition: Long = 0,

    @Column(columnDefinition = "TEXT")
    val data: String = "{}",

    @Column(columnDefinition = "TEXT")
    val metadata: String = "{}",

    @Column(nullable = false)
    val time: LocalDateTime = LocalDateTime.now()
)
