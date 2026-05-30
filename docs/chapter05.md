# Chapter 5: Subscribing to the Message Store

## 챕터 요약

Message Store에 기록된 메시지를 지속적으로 읽어오는 **Subscription(구독)** 메커니즘을 구현하는 챕터이다. Polling 기반의 구독 시스템을 통해 컴포넌트 간 자율적(autonomous)이고 분리된(decoupled) 통신을 가능하게 하며, read position 관리, 배치 처리, 오케스트레이션의 세 가지 범주로 나뉜다. 이를 통해 Message Store는 단순한 데이터베이스를 넘어 **데이터 전송(data transport)** 역할까지 수행하게 된다.

---

## Subscription 코드의 세 가지 범주

| 범주 | 설명 |
|------|------|
| **Managing the Current Read Position** | 마지막으로 처리한 메시지의 위치를 저장/복구 |
| **Fetching and Processing Batches of Messages** | 메시지를 배치 단위로 가져와 handler에 전달 |
| **Orchestrating the Subscription** | polling 루프를 통해 전체 구독 흐름을 조율 |

---

## Subscription 생성 구조

외부 함수가 Message Store의 `read`, `readLastMessage`, `write` 의존성을 받고, 내부 함수가 구독 설정 파라미터를 받아 subscription 객체를 반환하는 이중 함수 구조이다.

```js
function configureCreateSubscription ({ read, readLastMessage, write }) {
  return ({
    streamName,
    handlers,
    messagesPerTick = 100,
    subscriberId,
    positionUpdateInterval = 100,
    tickIntervalMs = 100
  }) => {
    const subscriberStreamName = `subscriberPosition-${subscriberId}`
    let currentPosition = 0
    let messagesSinceLastPositionWrite = 0
    let keepGoing = true
    return { loadPosition, start, stop, tick, writePosition }
  }
}
```

### Subscription 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `streamName` | - | 구독할 category stream 이름 |
| `handlers` | - | 메시지 타입별 핸들러 함수 객체. **반드시 idempotent해야 함** |
| `messagesPerTick` | 100 | 한 polling 루프에서 처리할 최대 메시지 수 |
| `subscriberId` | - | 전역적으로 고유한 구독자 식별자 |
| `positionUpdateInterval` | 100 | 이 수만큼 메시지 처리 후 position 기록 |
| `tickIntervalMs` | 100 | 새 메시지 없을 때 다음 polling까지 대기 시간(ms) |

> **핵심 원칙**: Handler는 반드시 **idempotent(멱등)**해야 한다. 네트워크 장애 등으로 같은 메시지를 여러 번 처리할 수 있기 때문이다.

---

## Managing the Current Read Position

### loadPosition - 저장된 읽기 위치 복구

```js
function loadPosition () {
  return readLastMessage(subscriberStreamName)
    .then(message => {
      currentPosition = message ? message.data.position : 0
    })
}
```

`subscriberPosition-{subscriberId}` stream의 마지막 메시지에서 position을 읽는다. 메시지가 없으면 0부터 시작한다.

### updateReadPosition - 읽기 위치 갱신

```js
function updateReadPosition (position) {
  currentPosition = position
  messagesSinceLastPositionWrite += 1
  if (messagesSinceLastPositionWrite === positionUpdateInterval) {
    messagesSinceLastPositionWrite = 0
    return writePosition(position)
  }
  return Bluebird.resolve(true)
}
```

### writePosition - 읽기 위치 저장

```js
function writePosition (position) {
  const positionEvent = {
    id: uuid(),
    type: 'Read',
    data: { position }
  }
  return write(subscriberStreamName, positionEvent)
}
```

`Read` 타입 이벤트로 position을 Message Store에 기록한다. Read position 저장은 **성능 최적화**이며 정확성(correctness)과는 무관하다.

---

## Fetching and Processing Batches of Messages

### getNextBatchOfMessages - 다음 배치 가져오기

```js
function getNextBatchOfMessages () {
  return read(streamName, currentPosition + 1, messagesPerTick)
}
```

Eventide의 `get_category_messages` 함수가 `global_position >= $2` 조건을 사용하므로 `currentPosition + 1`을 전달한다.

### processBatch - 배치 처리

```js
function processBatch (messages) {
  return Bluebird.each(messages, message =>
    handleMessage(message)
      .then(() => updateReadPosition(message.globalPosition))
      .catch(err => { logError(message, err); throw err })
  )
  .then(() => messages.length)
}
```

`Bluebird.each`로 메시지를 **순차적으로** 하나씩 처리한다. 처리된 메시지 수를 반환하여 polling 주기를 결정한다.

### handleMessage - 메시지 핸들링

```js
function handleMessage (message) {
  const handler = handlers[message.type] || handlers.$any
  return handler ? handler(message) : Promise.resolve(true)
}
```

| 케이스 | 동작 |
|--------|------|
| `handlers[message.type]` 존재 | 해당 handler 호출 |
| `handlers.$any` 존재 | 모든 타입에 대한 범용 handler 호출 |
| handler 없음 | `Promise.resolve(true)` 반환 (아무 작업 안 함) |

---

## Orchestrating the Subscription

### start / stop

```js
function start () {
  console.log(`Started ${subscriberId}`)
  return poll()
}

function stop () {
  console.log(`Stopped ${subscriberId}`)
  keepGoing = false
}
```

### poll - Polling 루프

```js
async function poll () {
  await loadPosition()
  while (keepGoing) {
    const messagesProcessed = await tick()
    if (messagesProcessed === 0) {
      await Bluebird.delay(tickIntervalMs)
    }
  }
}
```

- `async/await`를 사용한 무한 루프 방식
- 처리된 메시지가 있으면 즉시 다음 tick 실행
- 처리된 메시지가 없으면 `tickIntervalMs`만큼 대기 후 재시도
- Node.js의 비동기 특성 덕분에 실행이 블로킹되지 않음

### tick - 한 사이클 실행

```js
function tick () {
  return getNextBatchOfMessages()
    .then(processBatch)
    .catch(err => {
      console.error('Error processing batch', err)
      stop()
    })
}
```

에러 발생 시 자동 복구를 시도하지 않고 **stop()을 호출**하여 사람의 개입을 요구한다.

---

## Reading the Last Message in a Stream

```sql
SELECT * FROM get_last_stream_message($1)
```

```js
const getLastMessageSql = 'SELECT * FROM get_last_stream_message($1)'

function readLastMessage (streamName) {
  return db.query(getLastMessageSql, [ streamName ])
    .then(res => deserializeMessage(res.rows[0]))
}
```

> **주의**: 이 함수는 category stream에서는 동작하지 않으며, entity stream 전용이다.

### deserializeMessage - 메시지 역직렬화

```js
function deserializeMessage (rawMessage) {
  if (!rawMessage) { return null }
  return {
    id: rawMessage.id,
    streamName: rawMessage.stream_name,
    type: rawMessage.type,
    position: parseInt(rawMessage.position, 10),
    globalPosition: parseInt(rawMessage.global_position, 10),
    data: rawMessage.data ? JSON.parse(rawMessage.data) : {},
    metadata: rawMessage.metadata ? JSON.parse(rawMessage.metadata) : {},
    time: rawMessage.time
  }
}
```

PostgreSQL의 `snake_case`를 JavaScript의 `camelCase`로 변환하고, `position`/`global_position`을 문자열에서 숫자로 파싱한다.

---

## Reading a Stream's Messages

Stream 유형에 따라 다른 SQL 함수를 사용한다.

| Stream 유형 | SQL 함수 | 판별 기준 |
|-------------|----------|-----------|
| Entity stream | `get_stream_messages($1, $2, $3)` | streamName에 `-` 포함 |
| Category stream | `get_category_messages($1, $2, $3)` | streamName에 `-` 미포함 |

```js
function read (streamName, fromPosition = 0, maxMessages = 1000) {
  let query = null
  let values = []
  if (streamName.includes('-')) {
    query = getStreamMessagesSql
    values = [streamName, fromPosition, maxMessages]
  } else {
    query = getCategoryMessagesSql
    values = [streamName, fromPosition, maxMessages]
  }
  return db.query(query, values)
    .then(res => res.rows.map(deserializeMessage))
}
```

---

## Adding the Read Functions to the Message Store's Interface

```js
const createRead = require('./read')
const configureCreateSubscription = require('./subscribe')

function createMessageStore ({ db }) {
  const read = createRead({ db })
  const createSubscription = configureCreateSubscription({
    read: read.read,
    readLastMessage: read.readLastMessage,
    write: write
  })
  return {
    createSubscription,
    read: read.read,
    readLastMessage: read.readLastMessage,
  }
}
```

`read`를 먼저 인스턴스화한 후, 그 함수들을 `configureCreateSubscription`의 의존성으로 전달한다. 최종적으로 `createSubscription`, `read`, `readLastMessage`를 Message Store의 public interface로 노출한다.

---

## 전체 데이터 흐름 요약

1. 사용자가 비디오 조회를 트리거한다
2. Application이 이를 받아 이벤트를 기록한다
3. Aggregator가 해당 이벤트를 구독하여 View Data를 구축한다
4. Application이 View Data를 사용하여 홈 페이지를 업데이트한다

> Message Store는 이제 **데이터베이스**이자 **데이터 전송(data transport)** 역할을 수행하며, pub/sub 기반의 decoupled architecture를 가능하게 한다. Polling은 화려하지 않지만 신뢰성 있고 운영 부담이 적은 방식이다.

---

## 멱등성(Idempotency) 보장 메커니즘 상세

### 핵심 원리: View Data 테이블에 마지막 처리 position을 같이 저장

Aggregator가 이벤트를 처리할 때, 비즈니스 데이터와 함께 **해당 이벤트의 globalPosition을 View Data 테이블에 기록**한다. 이를 통해 중복 처리를 방지한다.

```
pages 테이블 (View Data):
┌───────────┬─────────────────┬──────────────────────┐
│ page_name │ videos_watched  │ last_processed_pos   │
├───────────┼─────────────────┼──────────────────────┤
│ home      │ 3               │ 5                    │
└───────────┴─────────────────┴──────────────────────┘
```

### 핸들러 코드

```javascript
VideoViewed: (event) => {
  db.query(
    `UPDATE pages
     SET videos_watched = videos_watched + 1,
         last_processed_pos = $1
     WHERE page_name = 'home'
       AND last_processed_pos < $1`,
    [event.globalPosition]    // 메시지가 갖고 온 globalPosition을 그대로 사용
  )
}
```

- `event.globalPosition`: messages 테이블에서 폴링으로 가져온 이벤트에 이미 포함된 값
- SELECT 없이 **WHERE 절이 중복 판단을 겸함**
- 비즈니스 로직(+1)과 처리 기록(last_processed_pos 갱신)이 **하나의 SQL문**으로 원자적 실행

### 두 가지 position의 역할 구분

```
messages 테이블의 Read 이벤트        pages 테이블의 last_processed_pos
(subscriberPosition-* 스트림)       (View Data)
──────────────────────────         ──────────────────────────────
100개마다 저장 (부정확)               매 처리마다 갱신 (정확)
용도: 재시작 시 폴링 시작 위치         용도: 중복 처리 방지 (멱등성)
없어도 동작함 (0부터 다시 읽으면 됨)    없으면 멱등 보장 불가
성능 최적화                          정확성 보장
```

### 크래시 복구 시나리오

```
[정상 처리]
  global 0 처리 → pages: videos_watched=1, last_processed_pos=0
  global 1 처리 → pages: videos_watched=2, last_processed_pos=1
  global 2 처리 → pages: videos_watched=3, last_processed_pos=2
  Read 이벤트 저장 → messages: {"position": 2}
  global 4 처리 → pages: videos_watched=4, last_processed_pos=4

[크래시 발생! 💥] — Read 이벤트 아직 안 씀

[재시작]
  loadPosition() → messages의 Read 이벤트 → position: 2
  currentPosition = 2 → global_position >= 3부터 폴링

[중복 처리 발생]
  global 4 또 들어옴:
    WHERE last_processed_pos < 4
    → pages의 last_processed_pos는 이미 4
    → 4 < 4 → 거짓 → 무시됨 ✅

  global 5 처리 (정상):
    WHERE last_processed_pos < 5
    → 4 < 5 → 참 → 실행됨 ✅
    → pages: videos_watched=5, last_processed_pos=5
```

### 멱등 패턴 정리

| 상황 | 멱등 전략 | 예시 |
|------|----------|------|
| 숫자 증가 (조회수) | `WHERE last_processed_pos < $1` | 이미 처리한 position이면 무시 |
| 외부 호출 (이메일) | 처리 전 "이미 했나?" 이벤트 확인 | Sent 이벤트 존재 여부 체크 |
| 데이터 삽입 | `ON CONFLICT DO NOTHING` | 중복 키면 무시 |
| 상태 변경 | 현재 상태 확인 후 처리 | 이미 그 상태면 스킵 |

### 설계 철학: 트랜잭션이 필요 없는 구조

핸들러를 **SQL 1개로 설계**하면 DB가 자체적으로 원자성을 보장하므로 별도 트랜잭션이 불필요하다.

```
Aggregator 하나 = 관심사 하나 = 테이블 하나

home-page Aggregator   → pages 테이블만 업데이트
video-stats Aggregator → video_stats 테이블만 업데이트
```

> **이 아키텍처의 보장**: at-least-once (최소 1번 처리). exactly-once는 보장하지 않는다. 대신 핸들러를 멱등하게 구현하여 중복 처리해도 결과가 동일하게 만든다.
