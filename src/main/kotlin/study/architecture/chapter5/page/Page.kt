package study.architecture.chapter5.page

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity(name = "Ch5Page")
@Table(name = "ch5_pages")
class Page(
    @Id
    @Column(name = "page_name")
    val pageName: String = "",

    @Column(nullable = false)
    var videosWatched: Long = 0,

    @Column(nullable = false)
    var lastViewProcessed: Long = 0
)
