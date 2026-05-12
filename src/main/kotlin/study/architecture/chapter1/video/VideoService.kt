package study.architecture.chapter1.video

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class VideoService(private val videoRepository: VideoRepository) {

    fun getGlobalViewCount(): Long = videoRepository.sumAllViewCounts()

    @Transactional
    fun recordViewing(videoId: Long) {
        val video = videoRepository.findById(videoId)
            .orElseThrow { NoSuchElementException("Video not found: $videoId") }
        video.viewCount += 1
        videoRepository.save(video)
    }
}
