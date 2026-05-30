package study.architecture.chapter5.messagestore

import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch5MessageRepository")
interface MessageRepository : JpaRepository<Message, UUID> {

    @Query("SELECT MAX(m.position) FROM Ch5Message m WHERE m.streamName = :streamName")
    fun findMaxPositionByStreamName(@Param("streamName") streamName: String): Long?

    @Query("SELECT MAX(m.globalPosition) FROM Ch5Message m")
    fun findMaxGlobalPosition(): Long?

    /**
     * get_stream_messages($1, $2, $3) 등가: 정확한 streamName 매칭.
     */
    @Query(
        """
        SELECT m FROM Ch5Message m
        WHERE m.streamName = :streamName
          AND m.globalPosition >= :fromPosition
        ORDER BY m.globalPosition ASC
        """
    )
    fun findByStream(
        @Param("streamName") streamName: String,
        @Param("fromPosition") fromPosition: Long,
        pageable: PageRequest
    ): List<Message>

    /**
     * get_category_messages($1, $2, $3) 등가: streamName이 "{category}-..."로 시작.
     */
    @Query(
        """
        SELECT m FROM Ch5Message m
        WHERE m.streamName LIKE CONCAT(:category, '-%')
          AND m.globalPosition >= :fromPosition
        ORDER BY m.globalPosition ASC
        """
    )
    fun findByCategory(
        @Param("category") category: String,
        @Param("fromPosition") fromPosition: Long,
        pageable: PageRequest
    ): List<Message>

    /**
     * get_last_stream_message($1) 등가.
     */
    @Query(
        """
        SELECT m FROM Ch5Message m
        WHERE m.streamName = :streamName
        ORDER BY m.position DESC
        LIMIT 1
        """
    )
    fun findLastInStream(@Param("streamName") streamName: String): Message?
}
