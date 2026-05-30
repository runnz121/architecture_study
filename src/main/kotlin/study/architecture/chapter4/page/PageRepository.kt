package study.architecture.chapter4.page

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository("ch4PageRepository")
interface PageRepository : JpaRepository<Page, String> {

    /**
     * 책의 jsonb_set 중첩 호출을 단순 컬럼 UPDATE로 옮긴 것.
     * WHERE 절의 lastViewProcessed < :globalPosition 가 멱등성을 보장한다.
     */
    @Modifying
    @Query(
        """
        UPDATE Ch4Page p
        SET p.videosWatched = p.videosWatched + 1,
            p.lastViewProcessed = :globalPosition
        WHERE p.pageName = 'home'
          AND p.lastViewProcessed < :globalPosition
        """
    )
    fun incrementVideosWatched(@Param("globalPosition") globalPosition: Long): Int
}
