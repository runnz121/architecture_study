package study.architecture.chapter11.creatorsportal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * polling interstitial이 조회하는 View Data.
 * command의 traceId를 Primary Key로 사용하여, 사용자가 traceId로 작업 결과를 추적한다.
 */
@Entity(name = "Ch11VideoOperation")
@Table(name = "ch11_video_operations")
class VideoOperation(
    @Id
    val traceId: String = "",

    @Column(nullable = false)
    val videoId: String = "",

    @Column(nullable = false)
    val succeeded: Boolean = false,

    @Column
    val failureReason: String? = null
)
