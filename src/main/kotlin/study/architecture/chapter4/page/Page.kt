package study.architecture.chapter4.page

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Chapter 4: View Data 테이블.
 *
 * page_name PK + 화면 렌더링에 필요한 컬럼들.
 * 책에서는 jsonb로 page_data를 저장하지만, 여기서는 명시적 컬럼으로 단순화한다.
 * lastViewProcessed가 멱등성 보장의 핵심 컬럼이다.
 */
@Entity(name = "Ch4Page")
@Table(name = "ch4_pages")
class Page(
    @Id
    @Column(name = "page_name")
    val pageName: String = "",

    @Column(nullable = false)
    var videosWatched: Long = 0,

    @Column(nullable = false)
    var lastViewProcessed: Long = 0
)
