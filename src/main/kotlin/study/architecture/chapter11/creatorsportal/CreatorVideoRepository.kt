package study.architecture.chapter11.creatorsportal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository("ch11CreatorVideoRepository")
interface CreatorVideoRepository : JpaRepository<CreatorVideo, UUID> {
    fun findByOwnerId(ownerId: UUID): List<CreatorVideo>
}
