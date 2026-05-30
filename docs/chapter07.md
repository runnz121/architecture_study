# Chapter 7: Implementing Your First Component

## 챕터 요약

이 챕터에서는 identity Component를 구현하여 Register command를 처리하는 첫 번째 마이크로서비스를 완성한다. Message Store에 `fetch` 함수를 추가하여 stream의 현재 상태를 projection으로 조회하고, 이를 활용해 idempotent한 command handler를 구현한다. 또한 "projection"과 "replaying"이라는 용어가 가지는 두 가지 의미를 명확히 구분한다.

---

## Fetching a Stream's Current State

### View Data vs. Entity State

| 구분 | View Data (Aggregator) | Entity State (fetch) |
|------|----------------------|---------------------|
| 일관성 | Eventually Consistent | 현재 시점의 정확한 상태 |
| 용도 | 읽기 전용 화면 표시 | Command 처리 시 판단 근거 |
| 구현 방식 | 지속적 subscription | 필요 시 stream 전체 읽기 + projection |

### fetch 함수 구현

Message Store에 `fetch` 함수를 추가하여 entity stream의 현재 상태를 조회한다. stream 이름과 projection을 받아 events를 읽고 projection을 적용한다.

```javascript
function createRead ({ db }) {
  function fetch (streamName, projection) {
    return read(streamName).then(messages => project(messages, projection))
  }
  return {
    // ...
    fetch
  }
}
```

### project 함수 (순수 함수)

`project`는 events 배열을 `Array.prototype.reduce`로 순회하며 projection의 handler를 적용해 단일 entity 상태를 만들어낸다.

```javascript
function project (events, projection) {
  return events.reduce((entity, event) => {
    if (!projection[event.type]) {
      return entity
    }
    return projection[event.type](entity, event)
  }, projection.$init())
}
```

### Projection의 구조

| 키 | 역할 |
|----|------|
| `$init` | entity의 초기 상태를 반환하는 함수 |
| `EventType` | 해당 event type을 entity에 적용하는 handler 함수 |

> **주의**: Command는 상태가 아니라 상태 변경 요청이므로 projection에 포함되지 않는다.

### Identity Projection 예시

```javascript
const identityProjection = {
  $init () {
    return {
      id: null,
      email: null,
      isRegistered: false,
    }
  },
  Registered (identity, registered) {
    identity.id = registered.data.userId
    identity.email = registered.data.email
    identity.isRegistered = true
    return identity
  },
}
```

---

## Joining the "I Wrote a Microservice" Club

### Component의 최상위 구조

Component는 Aggregator와 유사한 구조를 가진다. command category stream을 subscribe하고 handler를 연결한다.

```javascript
function build ({ messageStore }) {
  const identityCommandHandlers =
    createIdentityCommandHandlers({ messageStore })
  const identityCommandSubscription = messageStore.createSubscription({
    streamName: 'identity:command',
    handlers: identityCommandHandlers,
    subscriberId: 'components:identity:command'
  })
  function start () {
    identityCommandSubscription.start()
  }
  return {
    identityCommandHandlers,
    start
  }
}
```

### Register Command Handler Pipeline

Command를 event로 변환하는 과정은 Bluebird promise pipeline으로 구성된다.

```javascript
Register: command => {
  const context = {
    messageStore: messageStore,
    command,
    identityId: command.data.userId
  }
  return Bluebird.resolve(context)
    .then(loadIdentity)
    .then(ensureNotRegistered)
    .then(writeRegisteredEvent)
    .catch(AlreadyRegisteredError, () => {})
}
```

| 단계 | 함수 | 역할 |
|------|------|------|
| 1 | `loadIdentity` | entity stream에서 현재 identity 상태를 fetch하여 context에 첨부 |
| 2 | `ensureNotRegistered` | 이미 등록된 경우 `AlreadyRegisteredError` throw (idempotence 보장) |
| 3 | `writeRegisteredEvent` | command를 Registered event로 변환하여 entity stream에 write |

### Idempotent Handler 패턴

```javascript
function ensureNotRegistered (context) {
  if (context.identity.isRegistered) {
    throw new AlreadyRegisteredError()
  }
  return context
}
```

메시지가 중복 전달될 수 있으므로, 처리 전에 반드시 현재 상태를 확인하여 이미 처리된 command는 무시한다.

### Registered Event 작성

```javascript
function writeRegisteredEvent (context) {
  const command = context.command
  const registeredEvent = {
    id: uuid(),
    type: 'Registered',
    metadata: {
      traceId: command.metadata.traceId,
      userId: command.metadata.userId
    },
    data: {
      userId: command.data.userId,
      email: command.data.email,
      passwordHash: command.data.passwordHash
    }
  }
  const identityStreamName = `identity-${command.data.userId}`
  return context.messageStore
    .write(identityStreamName, registeredEvent)
    .then(() => context)
}
```

---

## Wiring the Identity Component into the System

시스템 config에 Component를 연결하는 과정은 간단하다.

```javascript
const createIdentityComponent = require('./components/identity')

function createConfig ({ env }) {
  // ...
  const identityComponent = createIdentityComponent({ messageStore })
  const components = [
    identityComponent,
  ]
  return {
    // ...
    identityComponent,
  }
}
```

| 단계 | 코드 | 설명 |
|------|------|------|
| 1 | `require` | Component constructor를 가져온다 |
| 2 | `createIdentityComponent` | messageStore dependency를 주입하여 인스턴스화한다 |
| 3 | `components` 배열에 추가 | 시스템이 `start()` 함수를 호출하도록 등록한다 |

---

## Disambiguating "Projections" and "Replaying"

"Projection"과 "Replaying"은 각각 두 가지 의미를 가지므로 구분이 필요하다.

### Projection의 두 가지 의미

| 의미 | 맥락 | 설명 |
|------|------|------|
| Component projection | `fetch` 호출 시 | entity stream의 events를 순회하여 현재 상태를 산출. 매번 command 처리 시 반복 실행 |
| Aggregator projection | Aggregator 동작 시 | append-only log를 다른 형태(View Data)로 지속적으로 변환 |

### Replaying의 두 가지 의미

| 의미 | 맥락 | 설명 |
|------|------|------|
| Entity replay | Component 내 `fetch` | entity의 events를 매번 다시 순회. Side effect 없이 순수하게 상태만 산출하므로 안전 |
| Historical replay | 새 Component/Aggregator 추가 시 | Message Store의 첫 메시지부터 현재까지 처리. Idempotence로 side effect 중복 방지 |

> **핵심**: Projection은 절대 side effect를 발생시키지 않으며, historical replay 시에는 **idempotence**로 side effect 중복을 방지한다.

---

## 핵심 정리

- **fetch 함수**: stream 이름과 projection을 받아 entity의 현재 상태를 event sourcing으로 산출한다
- **Projection**: `$init`으로 초기 상태를 정의하고, event type별 handler로 상태를 갱신하는 순수 함수 집합이다
- **Idempotent Handler 패턴**: `loadEntity` → `ensureNotAlreadyProcessed` → `writeEvent` 순서를 따른다
- **Component 구조**: command category stream을 subscribe하고, handler에서 command를 event로 변환한다
- **fetch의 제한사항**: 기본 `read`가 최대 1,000개 메시지를 반환하므로 1,000개 이상의 메시지가 있는 stream은 지원하지 않는다 (snapshotting으로 해결 가능)
