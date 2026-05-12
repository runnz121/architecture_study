package study.architecture.chapter1.video

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "videos")
class Video(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val ownerId: String = "",
    val name: String = "",
    val description: String = "",
    val transcodingStatus: String = "",
    var viewCount: Int = 0
)
