package study.architecture.video

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface VideoRepository : JpaRepository<Video, Long> {
    @Query("SELECT COALESCE(SUM(v.viewCount), 0) FROM Video v")
    fun sumAllViewCounts(): Long
}
