package study.architecture.payment

import org.springframework.context.annotation.Configuration       // 설정 클래스 표시(빈 정의 가능).
import org.springframework.context.annotation.EnableAspectJAutoProxy // AspectJ 어드바이스 활성화.
import org.springframework.scheduling.annotation.EnableScheduling // @Scheduled 메서드 동작 활성화.

// @Configuration: Spring 설정 클래스. 빈 정의 메서드(@Bean)를 둘 수도 있음.
// @EnableScheduling: 이게 켜져 있어야 SagaRecoveryScheduler 의 @Scheduled 가 실제로 실행된다.
//   (꺼져 있으면 어노테이션이 무시되고 1분마다 깨어나지 않음)
// @EnableAspectJAutoProxy: 어노테이션 기반 AOP(@Aspect/@Around) 어드바이스를 활성화.
//   (꺼져 있으면 EventLockAspect 가 메서드를 가로채지 않아 락이 전혀 안 걸린다)
//   ※ Spring Boot 의 자동 설정에 의해 보통 켜져 있지만, 여기서 명시적으로 한 번 더 켜서 의도를 드러냄.
@Configuration
@EnableScheduling
@EnableAspectJAutoProxy
class PaymentConfig
