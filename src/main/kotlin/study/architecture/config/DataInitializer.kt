package study.architecture.config

import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import study.architecture.video.Video
import study.architecture.video.VideoRepository

@Component
class DataInitializer(private val videoRepository: VideoRepository) : CommandLineRunner {
    override fun run(vararg args: String) {
        if (videoRepository.count() == 0L) {
            videoRepository.saveAll(
                listOf(
                    Video(name = "Intro to Microservices", description = "Learn the basics"),
                    Video(name = "Event Sourcing 101", description = "Understanding events"),
                    Video(name = "CQRS Patterns", description = "Command Query Separation")
                )
            )
        }
    }
}
