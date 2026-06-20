package study.architecture.chapter8.authentication

/**
 * 인증 실패 시 사용하는 통합 에러.
 * 이메일 미존재와 비밀번호 불일치를 구분하지 않아 보안을 강화한다.
 */
class AuthenticationError : RuntimeException("Authentication failed")
