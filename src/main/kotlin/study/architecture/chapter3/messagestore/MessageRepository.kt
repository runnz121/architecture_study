package study.architecture.chapter3.messagestore

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface MessageRepository : JpaRepository<Message, UUID> {

    fun countByType(type: String): Long

    @Query("SELECT MAX(m.position) FROM Message m WHERE m.streamName = :streamName")
    fun findMaxPositionByStreamName(streamName: String): Long?
}
