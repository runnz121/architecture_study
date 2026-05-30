# Chapter 14: Testing in a Microservices Architecture

## 챕터 요약

마이크로서비스 아키텍처에서의 테스팅은 복잡할 필요가 없다. Autonomous component는 메시지에 응답하는 단 하나의 실행 모드만 가지므로, 프로덕션과 테스트 환경의 차이가 없어 테스트가 단순해진다. 모든 테스트를 자동화하기보다는 diminishing returns와 opportunity cost를 고려하여 프로덕션 모니터링을 적극 활용하는 것이 더 효과적일 수 있다.

---

## Revisiting the Fundamentals

서비스 기반 아키텍처의 핵심 특성은 **autonomy(자율성)**이다.

- Autonomous component는 모든 통신을 **Message Store**를 통해 처리한다
- Command와 event를 수신하고, 새로운 event를 작성하여 응답한다
- **단 하나의 실행 모드**만 존재한다

> Autonomous component는 프로덕션 환경에서 실행되는지, 테스트 harness에서 실행되는지 구분할 수 없다. 메시지가 라이브 시스템에서 온 것인지, 테스트에서 온 것인지 알 방법이 없기 때문이다.

| 특성 | 설명 |
|------|------|
| 통신 방식 | Message Store를 통한 비동기 메시지 |
| 실행 모드 | 메시지 응답 (단일 모드) |
| 테스트 vs 프로덕션 | 구분 불가 — 동일하게 동작 |
| 자동화 필요성 | 모든 테스트가 자동화될 필요는 없음 |

---

## Writing Tests for Autonomous Components

모든 테스트는 기본적으로 **3단계 패턴**을 따른다:

1. **Setup** — 테스트 데이터 구성
2. **Exercise** — 테스트 대상 시스템 실행
3. **Assert** — 결과에 대한 assertion

### Home Page Aggregator 테스트 예시

```javascript
const test = require('blue-tape')
const uuid = require('uuid/v4')
const { config, reset } = require('../test-helper')

test('It aggregates a VideoViewed event', t => {
  const userId = uuid()
  const videoId = uuid()
  // ❶ Setup: 메시지 구성
  const videoViewedEvent = {
    id: uuid(),
    type: 'VideoViewed',
    metadata: { traceId: uuid(), userId: uuid() },
    data: { userId, videoId },
    globalPosition: 1
  }

  return (
    reset()  // ❷ DB 초기화
      .then(() => config.homePageAggregator.init())
      .then(() =>
        config.homePageAggregator.handlers.VideoViewed(videoViewedEvent)
      )
      // 멱등성 검증을 위해 두 번째 호출
      .then(() =>
        config.homePageAggregator.handlers.VideoViewed(videoViewedEvent)
      )
      .then(() =>
        config.db.then(client =>
          client('pages')
            .where({ page_name: 'home' })
            .then(homePageData => {
              t.ok(homePageData, 'Got the home page data')
              // ❸ Assert: 두 번 처리해도 count는 1
              t.equal(
                homePageData[0].page_data.videosWatched,
                1,
                'Even though we see the event twice, there is still only 1'
              )
            })
        )
      )
  )
})
```

| 단계 | 설명 |
|------|------|
| Setup | `VideoViewed` event 메시지 구성 |
| Exercise | DB reset 후 handler를 두 번 호출 (idempotence 검증) |
| Assert | `videosWatched`가 1인지 확인 — 멱등성 보장 |

### Video Publishing 실패 테스트 예시

```javascript
test('Writes a VideoPublishingFailed event when publishing fails', t => {
  // ❶ 실패하는 fetch 함수로 substitute 생성
  function lousyFetch () {
    throw new Error('No can haz fetch')
  }
  const lousyMessageStore = {
    ...config.messageStore,
    fetch: lousyFetch
  }
  // ❷ substitute를 주입하여 Component 생성
  const videoPublishingComponent = createVideoPublishingComponent({
    messageStore: lousyMessageStore
  })

  // ❸ PublishVideo command 구성
  const command = {
    id: uuid(),
    type: 'PublishVideo',
    metadata: { traceId, userId },
    data: { ownerId, sourceUri, videoId }
  }

  // ❹ Handler 실행 후 ❺ 결과 assertion
  return videoPublishingComponent.handlers.PublishVideo(command)
    .then(() =>
      config.messageStore.read(`videoPublishing-${videoId}`)
        .then(messages => {
          t.equal(messages.length, 1, '1 event written')
          t.equal(messages[0].type, 'VideoPublishingFailed', 'It failed')
          t.equal(messages[0].data.reason, 'No can haz fetch')
        })
    )
})
```

핵심 포인트:
- **Dependency injection**을 활용하여 실패 상황을 재현한다
- View Data가 관여하지 않으면 DB reset이 불필요하다 — UUID를 사용하므로 테스트 간 충돌이 없다

---

## Writing Tests for Message-Writing Applications

Application 테스트는 더 단순하다. 메시지를 작성하고 응답을 확인하면 된다.

### 사용자 등록 테스트 예시

```javascript
test('Issues the registration command when user submits good data', t => {
  // ❶ Setup: POST body 구성
  const userId = uuid()
  const attributes = {
    id: userId,
    email: 'finally@example.com',
    password: 'adsfasdf'
  }

  // ❷ Exercise: HTTP POST 요청
  return supertest(app)
    .post('/register')
    .type('form')
    .send(attributes)
    .expect(301)
    .then(res => {
      t.assert(res.headers.location.includes('registration-complete'))
    })
    // ❸ Assert: 올바른 command가 작성되었는지 확인
    .then(() =>
      config.messageStore
        .read(`identity:command-${userId}`)
        .then(retrievedMessages => {
          t.equal(retrievedMessages.length, 1, 'There is 1 message')
        })
    )
})
```

| 기존 방식 | 마이크로서비스 방식 |
|-----------|-------------------|
| 여러 테이블 업데이트 확인 | Message Store에 메시지 작성 확인 |
| DB 상태 직접 검증 | Stream에서 메시지 읽어 검증 |

---

## Keeping It Simple

Autonomous component 테스트가 단순한 이유:

- **복잡한 orchestration이 불필요하다** — 전체 시스템을 동시에 구성할 필요가 없다
- **Component 간 직접 통신이 없다** — mock이나 stub으로 상호작용을 흉내 낼 필요가 없다
- **테스트에서 실행하는 방식이 프로덕션과 동일하다** — 메시지를 전달하고 결과를 확인하면 된다

> Mock과 stub에 의존해야 한다면, 그것은 코드가 "더 나은 설계를 해달라"고 요청하는 것이다.

---

## Dropping Testing?

모든 테스트를 자동화하는 대신 **프로덕션 모니터링**으로 대체할 수 있다는 관점이다.

### Diminishing Returns vs Monitoring

| 접근 방식 | 장점 | 단점 |
|-----------|------|------|
| End-to-end 자동화 테스트 | 배포 전 문제 발견 | 구축·유지 비용 높음, 예측 불가한 실패는 못 잡음 |
| 프로덕션 모니터링 | 실제 환경에서 검증, 구축 비용 낮음 | 사후 발견 |
| Observer 기반 자동 모니터링 | 자동화된 실시간 감시 | 설계 필요 |

### 모니터링이 효과적인 이유

1. **프로덕션에서 예측 불가능한 방식으로 실패가 발생한다** — 사전에 모든 테스트 케이스를 작성할 수 없다
2. **Event sourcing으로 완전한 상태 이력이 존재한다** — 문제 발생 시 메시지 워크플로우를 샘플링하여 사후 검증이 가능하다
3. **Observer 패턴으로 자동 모니터링이 가능하다** — 예: `Register` command 이후 일정 시간 내 `RegistrationEmailSent`가 없으면 알림

> "Sufficiently Advanced Monitoring Is Indistinguishable from Testing." — Ed Keyes

### 핵심 교훈

- **Diminishing returns**: 테스트 자동화에 투입하는 자원이 늘어날수록 단위당 효용이 감소한다
- **Opportunity cost**: 복잡한 테스트 파이프라인에 투자하는 시간은 다른 곳에 쓸 수 있다
- 전체 시스템 복제보다 **모니터링 도구와 correlation 화면**이 더 실용적이다
