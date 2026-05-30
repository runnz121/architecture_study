package study.architecture.chapter4.messagestore

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch4MessageRepository")
interface MessageRepository : JpaRepository<Message, UUID> {

    @Query("SELECT MAX(m.position) FROM Ch4Message m WHERE m.streamName = :streamName")
    fun findMaxPositionByStreamName(streamName: String): Long?

    @Query("SELECT MAX(m.globalPosition) FROM Ch4Message m")
    fun findMaxGlobalPosition(): Long?
}
