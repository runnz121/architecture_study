package study.architecture.chapter3.messagestore

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch3MessageRepository")
interface MessageRepository : JpaRepository<Message, UUID> {

    fun countByType(type: String): Long

    @Query("SELECT MAX(m.position) FROM Ch3Message m WHERE m.streamName = :streamName")
    fun findMaxPositionByStreamName(streamName: String): Long?
}
