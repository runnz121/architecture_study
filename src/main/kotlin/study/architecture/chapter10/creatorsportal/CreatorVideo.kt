package study.architecture.chapter10.creatorsportal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * Creators Portal이 조회하는 View Data.
 * Aggregator가 videoPublishing 이벤트를 관찰하여 채운다.
 */
@Entity(name = "Ch10CreatorVideo")
@Table(name = "ch10_creator_videos")
class CreatorVideo(
    @Id
    val videoId: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val ownerId: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val sourceUri: String = "",

    @Column
    val transcodedUri: String? = null,

    /** "published" | "failed" */
    @Column(nullable = false)
    val state: String = "",

    @Column
    val reason: String? = null,

    /** 멱등성: 이 position 이하의 이벤트는 이미 반영된 것으로 보고 무시한다. */
    @Column(nullable = false)
    val lastVideoPublishingPosition: Long = 0
)
