package study.architecture.chapter5.page

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository("ch5PageRepository")
interface PageRepository : JpaRepository<Page, String> {

    @Modifying
    @Query(
        """
        UPDATE Ch5Page p
        SET p.videosWatched = p.videosWatched + 1,
            p.lastViewProcessed = :globalPosition
        WHERE p.pageName = 'home'
          AND p.lastViewProcessed < :globalPosition
        """
    )
    fun incrementVideosWatched(@Param("globalPosition") globalPosition: Long): Int
}
