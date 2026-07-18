package study.architecture.chapter11.creatorsportal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository("ch11VideoOperationRepository")
interface VideoOperationRepository : JpaRepository<VideoOperation, String>
