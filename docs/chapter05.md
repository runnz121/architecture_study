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
