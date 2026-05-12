package study.architecture.chapter1.config

import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import study.architecture.chapter1.video.Video
import study.architecture.chapter1.video.VideoRepository

@Component
class Ch1DataInitializer(private val videoRepository: VideoRepository) : CommandLineRunner {
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
