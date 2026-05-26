package study.architecture.chapter2.messagestore

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch2MessageRepository")
interface MessageRepository : JpaRepository<Message, UUID> {

    fun countByType(type: String): Long

    @Query("SELECT MAX(m.position) FROM Ch2Message m WHERE m.streamName = :streamName")
    fun findMaxPositionByStreamName(streamName: String): Long?
}
