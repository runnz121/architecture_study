# Chapter 4: Projecting Data into Useful Shapes

## 챕터 요약

이 챕터는 Message Store에 저장된 append-only 이벤트 로그를 사용자에게 보여줄 수 있는 유용한 형태(View Data)로 변환하는 **Aggregator** 패턴을 다룬다. 이 과정에서 CQRS(Command-Query Responsibility Segregation) 개념이 도입되며, 쓰기에 최적화된 Message Store와 읽기에 최적화된 View Data를 분리하는 방법을 배운다. 특히 메시지 기반 아키텍처에서 가장 중요한 개념인 **멱등성(Idempotence)**을 다루며, 동일한 메시지를 여러 번 처리해도 부작용이 없는 핸들러 작성법을 설명한다.

---

## Handling Events

Aggregator의 존재는 단 두 단계로 정의된다:

1. **이벤트를 수신**한다.
2. 이벤트를 처리하여 **View Data를 업데이트**한다.

| 구분 | Message Store | View Data |
|------|--------------|-----------|
| 최적화 대상 | 쓰기(Write) | 읽기(Read) |
| 데이터 형태 | Append-only 이벤트 로그 | 화면 렌더링에 최적화된 테이블 |
| 정규화 | 이벤트 스트림 | 3NF 아님, 단일 쿼리로 화면 데이터 조회 가능 |

---

## (Re)Introducing the RDBMS

읽기에 최적화된 View Data 테이블을 PostgreSQL에 생성한다. 3NF(Third Normal Form)가 아닌, **화면 하나당 단일 row 조회**가 가능한 구조를 설계한다.

```javascript
// migrations/20180303013723_create-pages.js
exports.up = knex =>
  knex.schema.createTable('pages', table => {
    table.string('page_name').primary()
    table.jsonb('page_data').defaultsTo('{}')
  })
exports.down = knex => knex.schema.dropTable('pages')
```

| Column | Type | 설명 |
|--------|------|------|
| `page_name` | string (PK) | 페이지 식별자 (예: `'home'`) |
| `page_data` | jsonb | 페이지 렌더링에 필요한 모든 데이터 |

`page_data` 예시: `{ "videosWatched": 42, "lastViewProcessed": 24 }`

- `videosWatched`: 전체 비디오 시청 횟수
- `lastViewProcessed`: 멱등성 처리를 위한 마지막 처리 메시지의 globalPosition

---

## Writing Your First Aggregator

Aggregator의 기본 구조는 세 가지 요소로 구성된다:

```javascript
// src/aggregators/home-page.js
function createHandlers ({ queries }) {
  return { }
}

function createQueries ({ db }) {
  return { }
}

function build ({ db, messageStore }) {
  const queries = createQueries({ db })
  const handlers = createHandlers({ queries })
  return { queries, handlers }
}

module.exports = build
```

| 구성 요소 | 역할 |
|-----------|------|
| `createHandlers` | 메시지 타입별 핸들러 함수 정의 |
| `createQueries` | DB 쿼리 함수 정의 |
| `build` (top-level) | 의존성(`db`, `messageStore`)을 주입받아 조립 |

---

## Handling Asynchronous Messages

메시지 핸들러는 **JavaScript 객체**로 정의하며, 키는 메시지 타입, 값은 처리 함수이다.

```javascript
function createHandlers ({ queries }) {
  return {
    VideoViewed: event => queries.incrementVideosWatched(event.globalPosition)
  }
}
```

`VideoViewed` 이벤트를 수신하면 전역 시청 카운트를 1 증가시키는 쿼리를 호출한다.

### incrementVideosWatched 쿼리

```sql
UPDATE
  pages
SET
  page_data = jsonb_set(
    jsonb_set(
      page_data,
      '{videosWatched}',
      ((page_data ->> 'videosWatched')::int + 1)::text::jsonb
    ),
    '{lastViewProcessed}',
    :globalPosition::text::jsonb
  )
WHERE
  page_name = 'home' AND
  (page_data->>'lastViewProcessed')::int < :globalPosition
```

**jsonb_set 중첩 호출 구조:**

1. **내부 호출**: `videosWatched` 값을 +1 증가
2. **외부 호출**: 내부 호출 결과에 `lastViewProcessed`를 현재 `globalPosition`으로 설정

**PostgreSQL 캐스팅 체인**: `:globalPosition::text::jsonb`

| 부분 | 의미 |
|------|------|
| `:globalPosition` | knex의 SQL injection 방지용 바인딩 |
| `::text` | PostgreSQL text 타입으로 캐스팅 |
| `::jsonb` | PostgreSQL jsonb 타입으로 캐스팅 |

---

## Getting Idempotent with It

> **멱등성(Idempotence)은 마이크로서비스에서 산소와 같다.**

| 개념 | 설명 |
|------|------|
| 멱등성 정의 | "same power" — 함수를 0번 호출한 것과 1번 이상 호출한 것, 두 가지 상태만 존재 |
| 필요한 이유 | 메시징 시스템은 실패할 수 있고, exactly-once delivery는 물리적으로 불가능 |
| 구현 방식 | 동일한 메시지를 여러 번 처리해도 추가 부작용 없도록 보장 |

이 핸들러의 멱등성은 **WHERE 절**에서 보장된다:

```sql
WHERE
  page_name = 'home' AND
  (page_data->>'lastViewProcessed')::int < :globalPosition
```

- `lastViewProcessed`가 현재 이벤트의 `globalPosition`보다 작을 때만 UPDATE 실행
- 이미 처리된 이벤트가 다시 들어오면 조건 불충족 → **no-op**

---

## Connecting to the Live Message Flow

Aggregator를 라이브 메시지 흐름에 연결하기 위해 `messageStore.createSubscription`을 사용한다.

```javascript
function build ({ db, messageStore }) {
  const queries = createQueries({ db })
  const handlers = createHandlers({ queries })
  const subscription = messageStore.createSubscription({
    streamName: 'viewing',
    handlers,
    subscriberId: 'aggregators:home-page'
  })

  function init () {
    return queries.ensureHomePage()
  }

  function start () {
    init().then(subscription.start)
  }

  return { queries, handlers, init, start }
}
```

**createSubscription의 3가지 인자:**

| 인자 | 설명 |
|------|------|
| `streamName` | 구독할 카테고리 스트림 이름 |
| `handlers` | 멱등한 메시지 핸들러 객체 |
| `subscriberId` | 전역 고유 식별자 (구독 진행 상태 추적용) |

### ensureHomePage — 초기 데이터 삽입

```javascript
function ensureHomePage () {
  const initialData = {
    pageData: { lastViewProcessed: 0, videosWatched: 0 }
  }
  const queryString = `
    INSERT INTO pages(page_name, page_data)
    VALUES ('home', :pageData)
    ON CONFLICT DO NOTHING
  `
  return db.then(client => client.raw(queryString, initialData))
}
```

- `ON CONFLICT DO NOTHING`: Aggregator를 몇 번 재시작하더라도 초기 row는 한 번만 삽입
- `start()` → `init()` → `ensureHomePage()` → `subscription.start()` 순서로 실행

---

## Configuring the Aggregator

### config.js 설정

```javascript
const createHomePageAggregator = require('./aggregators/home-page')

function createConfig ({ env }) {
  const homePageAggregator = createHomePageAggregator({
    db: knexClient,
    messageStore
  })
  const aggregators = [homePageAggregator]
  const components = []

  return { homePageAggregator, aggregators, components }
}
```

### src/index.js에서 시작

```javascript
function start () {
  config.aggregators.forEach(a => a.start())
  config.components.forEach(s => s.start())
  app.listen(env.port, signalAppStart)
}
```

- `aggregators` 배열을 순회하며 각 Aggregator의 `start()` 호출
- `components` 배열도 동일한 패턴 (현재는 비어 있음)

---

## Having the Home Page Application Use the New View Data

Home page Application이 Aggregator의 출력(View Data)을 사용하도록 수정한다.

```javascript
function createQueries ({ db }) {
  function loadHomePage () {
    return db.then(client =>
      client('pages')
        .where({ page_name: 'home' })
        .limit(1)
        .then(camelCaseKeys)
        .then(rows => rows[0])
    )
  }
  return { loadHomePage }
}
```

- 기존의 monolithic `videos` 테이블 대신 **특수 목적 `pages` 테이블** 조회
- JOIN 없음, 집계 연산 없음 — 단일 row 조회로 화면 렌더링 가능
- **자율성(Autonomy)의 힘**: 새로운 View Data가 추가되어도 home page Application은 변경 불필요

---

## Coming to Terms with Data Duplication

> "데이터를 중복하지 마라"의 진정한 의미: **권위 있는 진실의 원천(authoritative source of truth)을 중복하지 마라.**

| 구분 | 설명 |
|------|------|
| Source of Truth | Message Store의 이벤트 (단일 원본) |
| Aggregation (View Data) | 원본에서 파생된 다양한 표현 — 중복이 아님 |

동일한 이벤트 스트림에서 여러 View Data를 파생할 수 있다:

| View Data | 내용 |
|-----------|------|
| `global_videos_watched` | 전체 시청 횟수 (단일 값) |
| `video_watch_counts` | 비디오별 시청 횟수 |
| `pages` (home) | 홈 페이지 렌더링 데이터 |

**전통적 MVC와의 차이:**

| MVC | CQRS + Aggregator |
|-----|-------------------|
| 매 요청마다 JOIN/집계 반복 실행 | 이벤트 발생 시 한 번만 파생 데이터 갱신 |
| 읽기/쓰기가 같은 모델 | 읽기/쓰기 모델 분리 |
| derivation을 매번 재생성 | 이벤트는 immutable → 새 이벤트 전까지 derivation 불변 |

---

## 핵심 개념 정리

| 개념 | 설명 |
|------|------|
| **CQRS** | Command-Query Responsibility Segregation — 쓰기와 읽기 모델 분리 |
| **Aggregator** | 이벤트를 수신하여 View Data를 업데이트하는 자율 컴포넌트 |
| **View Data** | 읽기에 최적화된 파생 데이터 (화면별 단일 row 조회 목표) |
| **Idempotence** | 동일 메시지를 여러 번 처리해도 부작용 없음 — `lastViewProcessed`로 구현 |
| **Subscription** | 카테고리 스트림을 폴링하여 실시간 메시지 흐름에 연결 |
| **Source of Truth** | Message Store의 이벤트만이 유일한 진실의 원천 |
