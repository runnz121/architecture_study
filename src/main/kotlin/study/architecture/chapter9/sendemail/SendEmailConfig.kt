package study.architecture.chapter9.sendemail

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chapter 9 §5: Component 실행을 위한 설정.
 * env.js의 EMAIL_DIRECTORY 대응 — application.yaml의 app.email.directory.
 */
@Configuration
class SendEmailConfig {

    @Bean("ch9EmailTransport")
    fun emailTransport(
        @Value("\${app.email.directory:./emails/ch9}") directory: String
    ): EmailTransport = PickupEmailTransport(directory)
}
