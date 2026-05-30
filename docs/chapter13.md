# Chapter 13: Debugging Components

## 챕터 요약

이벤트 기반 아키텍처에서 문제를 감지하고 디버깅하는 방법을 다룬다. Admin Portal을 구축하여 Message Store의 데이터를 다양한 방식으로 집계하고 시각화함으로써, 사용자 등록 이메일이 발송되지 않는 문제의 원인을 추적한다. traceId를 활용한 분산 추적(distributed tracing)과 `$any`, `$all` 같은 특수 스트림 구독 메커니즘을 소개한다.

---

## Priming the Database with Example Data

- `code/debugging-components` 폴더에서 작업을 진행한다
- `docker-compose rm -sf` 후 `docker-compose up`으로 데이터베이스를 완전히 재구축한다
- `npm run populate` 명령으로 15개의 Register command를 Message Store에 주입한다
- 서버를 시작하면 Component들이 해당 command를 처리하여 event를 생성한다

---

## Introducing the Admin Portal

Admin Portal은 세 가지 주요 섹션으로 구성된다:

| 섹션 | 내용 | 설명 |
|------|------|------|
| **Views** | User 등 | 데이터 집계(aggregation) 결과를 보여주는 뷰 |
| **Messages** | 전체 메시지 목록, 스트림 목록 | 메시지를 다양한 방식으로 조회 |
| **System Health** | Component Read Positions | Subscriber들의 읽기 위치를 집계하여 표시 |

- `/admin/subscriber-positions`에서 Component Read Positions를 확인할 수 있다
- Read event를 집계하는 Aggregator를 사용하지만, 100개 메시지마다 기록하므로 15개 메시지로는 데이터가 표시되지 않는다

---

## Creating Users

사용자 관련 데이터를 여러 스트림에서 모아 하나의 aggregate view로 조합한다. `identity`와 `authentication` 두 개의 category stream에서 데이터를 수집한다.

### Migration 파일

```javascript
exports.up = function up (knex) {
  return knex.schema.createTable('admin_users', table => {
    table.string('id').primary()
    table.string('email')
    table.boolean('registration_email_sent').defaultTo(false)
    table.integer('last_identity_event_global_position').defaultTo(0)
    table.integer('login_count').defaultTo(0)
    table.integer('last_authentication_event_global_position').defaultTo(0)
    table.index('email')
  })
}
exports.down = knex => knex.schema.dropTable('admin_users')
```

### Multi-Subscription Aggregator

두 개의 별도 subscription을 사용하며, 각각 고유한 `subscriberId`를 가진다.

```javascript
function build ({ db, messageStore }) {
  const queries = createQueries({ db })

  // identity category subscription
  const identityHandlers = createIdentityHandlers({ queries })
  const identitySubscription = messageStore.createSubscription({
    streamName: 'identity',
    handlers: identityHandlers,
    subscriberId: 'e482ed56-311c-486c-9bb8-8c2e2ca6f6f4'
  })

  // authentication category subscription
  const authenticationHandlers = createAuthenticationHandlers({ queries })
  const authenticationSubscription = messageStore.createSubscription({
    streamName: 'authentication',
    handlers: authenticationHandlers,
    subscriberId: '18b4c5d6-1f30-4a67-9b61-76a42884a9bb'
  })

  function start () {
    identitySubscription.start()
    authenticationSubscription.start()
  }
  return { authenticationHandlers, identityHandlers, queries, start }
}
```

### Idempotent Query 패턴

| 쿼리 함수 | 역할 | Idempotence 전략 |
|-----------|------|-----------------|
| `ensureUser` | 사용자 행 존재 보장 | `ON CONFLICT DO NOTHING` |
| `setEmail` | 이메일 주소 설정 | `WHERE last_identity_event_global_position < :globalPosition` |
| `markRegistrationEmailSent` | 이메일 발송 여부 표시 | `WHERE last_identity_event_global_position < :globalPosition` |

```javascript
function ensureUser (id) {
  const rawQuery = `
    INSERT INTO admin_users (id)
    VALUES (:id)
    ON CONFLICT DO NOTHING
  `
  return db.then(client => client.raw(rawQuery, { id }))
}
```

---

## Wiring the Users View into the Admin Portal

### Application 구조

```javascript
function createAdminApplication ({ db, messageStoreDb }) {
  const queries = createQueries({ db, messageStoreDb })
  const handlers = createHandlers({ queries })
  const router = express.Router()

  router.route('/users').get(handlers.handleUsersIndex)
  router.route('/users/:id').get(handlers.handleShowUser)

  return { handlers, queries, router }
}
```

### 개별 사용자 뷰 - Promise.all 패턴

세 가지 데이터 소스에서 동시에 조회한다:

```javascript
function handleShowUser (req, res) {
  const userPromise = queries.user(req.params.id)
  const loginEventsPromise = queries.userLoginEvents(req.params.id)
  const viewingEventsPromise = queries.userViewingEvents(req.params.id)

  return Promise.all([
    userPromise, loginEventsPromise, viewingEventsPromise
  ]).then(values => {
    const user = values[0]
    const loginEvents = values[1]
    const viewingEvents = values[2]
    return res.render('admin/templates/user', {
      user, loginEvents, viewingEvents
    })
  })
}
```

### Message Store 직접 쿼리

| 쿼리 | stream 접근 방식 | 설명 |
|------|-----------------|------|
| `userLoginEvents` | `authentication-${userId}` | stream_name으로 직접 조회 |
| `userViewingEvents` | `category(stream_name) = 'viewing' AND data->>'userId' = $1` | category 함수와 JSON 필드 조회 조합 |

---

## Inspecting the Results So Far

- Admin Portal에서 모든 사용자의 "registration email sent"가 "no"로 표시된다
- 두 가지 가능성이 존재한다:
  1. Aggregator가 제대로 집계하지 못하고 있다
  2. Message Store에 `RegistrationEmailSent` event 자체가 없다

---

## Thinking Through the Expected Flow

등록 시 예상되는 메시지 흐름:

1. `register-users` app이 **Register** command를 작성
2. `identity` component가 **Registered** event를 작성
3. `identity`가 자체 Registered event를 처리하여 **Send** command를 작성
4. `send-email`이 이메일을 발송하고 **Sent** event를 작성
5. `identity`가 Sent event를 처리하여 **RegistrationEmailSent** event를 작성

이 5개의 메시지를 연결하는 핵심 데이터가 바로 **traceId**이다. 이것이 distributed tracing의 기반이 된다.

---

## Correlators

traceId를 기반으로 관련 메시지를 모아 보는 뷰를 구축한다.

```javascript
function correlatedMessages (traceId) {
  return messageStoreDb.query(
    `SELECT * FROM messages WHERE metadata->>'traceId' = $1`,
    [traceId]
  )
  .then(res => res.rows)
  .then(camelCaseKeys)
}
```

> **주의:** 이 쿼리는 인덱스를 사용하지 않는다. 소규모 데이터에서는 문제없지만, 수백만 건의 메시지에서는 별도의 Aggregator나 ElasticSearch 같은 도구를 사용해야 한다.

### 디버깅 결과

- 특정 traceId로 조회하면 5개가 아닌 **3개의 메시지**만 존재한다
- `Sent` event와 `RegistrationEmailSent` event가 누락되어 있다
- `send-email` component가 실행되지 않고 있음을 의미한다

---

## Starting from the Beginning

원인을 추적한 결과, `src/config.js`에서 문제를 발견한다:

```javascript
const components = [
  // ...
  identityComponent,
  // sendEmailComponent, // <-- 누군가 주석 처리함!
  videoPublishingComponent
]
```

**`sendEmailComponent`가 주석 처리되어 있어서** components 배열에 포함되지 않았고, `start()` 함수가 호출되지 않았다. 주석을 해제하면 `send-email`이 밀린 `UserRegistered` event들을 처리하기 시작한다.

---

## Viewing Messages by Stream

모든 스트림과 각 스트림의 메시지 수를 보여주는 뷰를 구축한다.

### Migration

```javascript
exports.up = function up (knex) {
  return knex.schema.createTable('admin_streams', table => {
    table.string('stream_name').primary()
    table.integer('message_count').defaultsTo(0)
    table.string('last_message_id')
    table.integer('last_message_global_position').defaultsTo(0)
  })
}
```

### Upsert Query

```javascript
function upsertStream (streamName, id, globalPosition) {
  const rawQuery = `
    INSERT INTO admin_streams (
      stream_name, message_count, last_message_id, last_message_global_position
    )
    VALUES (:streamName, 1, :id, :globalPosition)
    ON CONFLICT (stream_name) DO UPDATE
    SET
      message_count = admin_streams.message_count + 1,
      last_message_id = :id,
      last_message_global_position = :globalPosition
    WHERE
      admin_streams.last_message_global_position < :globalPosition
  `
  return db.then(client => client.raw(rawQuery, { streamName, id, globalPosition }))
}
```

### Upsert의 세 가지 케이스

| 케이스 | 경로 | 결과 |
|--------|------|------|
| 스트림 최초 처리 | INSERT | message_count = 1로 새 행 삽입 |
| 같은 스트림의 새 메시지 | ON CONFLICT → UPDATE | count 증가, 위치 갱신 |
| 메시지 재처리 | ON CONFLICT → WHERE 조건 불충족 | 아무 변경 없음 (idempotent) |

---

## Augmenting the Message Store for $any and $all

### $all - 모든 메시지 구독

`$all`은 Message Store의 모든 메시지를 읽기 위한 특수 stream name이다.

```javascript
const getAllMessagesSql = `
  SELECT id::varchar, stream_name::varchar, type::varchar,
    position::bigint, global_position::bigint,
    data::varchar, metadata::varchar, time::timestamp
  FROM messages
  WHERE global_position > $1
  LIMIT $2`

function read (streamName, fromPosition = 0, maxMessages = 1000) {
  let query = null
  let values = []

  if (streamName === '$all') {
    query = getAllMessagesSql
    values = [fromPosition, maxMessages]
  } else if (streamName.includes('-')) {
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

### $any - 모든 메시지 타입 핸들링

`$any`는 handler에서 사용하는 특수 키로, 메시지 타입에 관계없이 모든 메시지를 처리한다.

```javascript
function createHandlers ({ queries }) {
  return {
    $any: event => queries.upsertStream(
      event.streamName, event.id, event.globalPosition
    )
  }
}
```

| 특수 키워드 | 적용 위치 | 역할 |
|------------|----------|------|
| `$all` | Subscription의 `streamName` | Message Store의 모든 메시지를 읽음 |
| `$any` | Handler의 키 | 메시지 타입에 관계없이 모든 메시지를 처리 |

---

## 핵심 교훈

1. **이벤트 기반 아키텍처는 자체적으로 풍부한 디버깅 정보를 제공한다** - 모든 상태 변경이 이벤트로 기록되어 있기 때문이다
2. **traceId를 통한 distributed tracing**으로 여러 component에 걸친 메시지 흐름을 추적할 수 있다
3. **Aggregator 패턴**을 활용하여 Message Store의 원시 데이터를 유용한 디버깅 뷰로 변환할 수 있다
4. **Idempotent query 설계**는 모든 Aggregator에서 필수적이며, `global_position` 비교를 통해 달성한다
5. **`$all`과 `$any`**는 시스템 전체를 관찰하는 관리 도구 구축에 유용한 특수 메커니즘이다
