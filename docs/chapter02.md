# Chapter 2: Writing Messages

## 챕터 요약

Chapter 1에서 만든 MVC CRUD 방식의 한계를 분석하고, **모놀리스의 본질은 데이터 모델**임을 밝힌다.
그 해결책으로 **비동기 메시지(Command/Event)** 기반 아키텍처를 도입하며, 실제로 첫 번째 메시지(`VideoViewed`)를 작성한다.

---

## 1. 모놀리스의 정체를 밝히다 (Unmasking the Monolith)

### 모놀리스는 배포 전략이 아니라 데이터 모델이다

> "A monolith is a data model and not a deployment or code organization strategy."

- 언어, 프레임워크, Docker, 서버 수와 **무관**
- MVC CRUD 프레임워크는 **쓰기에 최적화** → 빠른 초기 결과를 주지만 높은 결합도(coupling) 발생
- 결합도 = 변경의 적 → 서브시스템 A를 바꾸면 B, C, D가 깨질 수 있음

### videos 테이블의 문제: 물을 압축하려는 것과 같다

```
videos 테이블의 6개 컬럼:
id | owner_id | name | description | view_count | transcoding_status
```

이 테이블에는 최소 **3가지 서로 다른 관심사**가 결합되어 있다:

| 컬럼 | 관심사 | 용도 |
|---|---|---|
| `owner_id` | **인가(Authorization)** | 권한 확인 |
| `name`, `description` | **비디오 플레이어** | 사용자에게 표시 |
| `transcoding_status` | **트랜스코딩** | 백그라운드 영상 변환 |
| `view_count` | **시청 통계** | 조회수 집계 |

하나의 테이블에 모든 관심사를 합치면 → **어떤 변경이든 다른 관심사를 깨뜨릴 수 있음**
이것이 **Canonical Data Model** (하나의 엔터티 표현으로 모든 것을 지배)의 함정이다.

---

## 2. "마이크로서비스 추출"의 함정

### 블로고스피어의 조언: "Just Extract Microservices™"

**Before** (모놀리스):
```
┌──────────────────────────────────┐
│  users ──function calls──► videos │
│              database             │
└──────────────────────────────────┘
```

**After** (소위 "마이크로서비스"):
```
┌──────────┐  HTTP Requests  ┌──────────┐
│  users   │◄───────────────►│  videos  │
│    DB    │                 │    DB    │
└──────────┘                 └──────────┘
```

### 결과: 더 나빠졌다

- 서버 2개, DB 2개로 운영 비용 증가
- function call이 HTTP call로 바뀜 (더 느리고 불안정)
- **데이터 모델은 변하지 않음** → 여전히 모놀리스
- `users` 서비스가 다운되면 `videos`도 다운 → **분산 모놀리스(Distributed Monolith)**
- JOIN으로 되던 것을 앱 코드에서 재구현해야 함

> "Simply putting a database table behind an HTTP interface does not produce a service, micro or otherwise."

---

## 3. 진짜 서비스의 정의: 자율성(Autonomy)

### Component의 핵심 특성

1. **질문에 응답하지 않는다** (질문에 응답하는 건 "데이터베이스")
2. **다른 것에 질문하지 않는다** (질문하면 의존성 발생 → 자율성 상실)
3. **비동기 메시지로 통신한다** → 이것이 서비스 기반 아키텍처를 가능하게 하는 핵심

> "If you have to connect to something else to get data to make a decision, then you are not autonomous."

---

## 4. 메시지: Commands와 Events

### 두 가지 메시지 유형

| 구분 | Command (명령) | Event (이벤트) |
|---|---|---|
| 의미 | 무언가를 해달라는 **요청** | 이미 발생한 **사실** |
| 시제 | **명령형** (imperative) | **과거형** (past tense) |
| 예시 | `PublishVideo`, `RecordVideoViewing` | `VideoPublished`, `VideoViewed` |
| 특징 | 거부될 수 있음 | 이미 일어난 일, 받아들여야 함 |

### 메시지 구조 (JSON)

**Command 예시:**
```json
{
  "id": "875b04d0-081b-453e-925c-a25d25213a18",
  "type": "PublishVideo",
  "metadata": {
    "traceId": "ddecf8e8-de5d-4989-9cf3-549c303ac939",
    "userId": "bb6a04b0-cb74-4981-b73d-24b844ca334f"
  },
  "data": {
    "ownerId": "bb6a04b0-cb74-4981-b73d-24b844ca334f",
    "sourceUri": "https://sourceurl.com/",
    "videoId": "9bfb5f98-36f4-44a2-8251-ab06e0d6d919"
  }
}
```

**Event 예시:**
```json
{
  "id": "23d2076f-41bd-4cdb-875e-2b0812a27524",
  "type": "VideoPublished",
  "metadata": {
    "traceId": "ddecf8e8-de5d-4989-9cf3-549c303ac939",
    "userId": "bb6a04b0-cb74-4981-b73d-24b844ca334f"
  },
  "data": {
    "ownerId": "bb6a04b0-cb74-4981-b73d-24b844ca334f",
    "sourceUri": "https://sourceurl.com/",
    "videoId": "9bfb5f98-36f4-44a2-8251-ab06e0d6d919"
  }
}
```

### 메시지의 4가지 필드

| 필드 | 설명 |
|---|---|
| `id` | UUID로 생성된 고유 식별자 |
| `type` | 메시지 유형 (비즈니스 도메인 용어 사용, CRUD 용어 금지) |
| `metadata` | 인프라 정보 (`traceId`, `userId` 등) |
| `data` | 메시지 페이로드 (함수 호출의 파라미터와 유사) |

### 메시지 네이밍 규칙

- 비즈니스 팀과 협업하여 **도메인 전문가의 언어**로 작명
- "create", "update", "delete" 절대 사용 금지 → CRUD 어휘 퇴출
- 예: `TransferFunds`, `AccountOpened`, `FundsDeposited`

---

## 5. 이벤트 소싱 (Event Sourcing)

### 상태를 이벤트로 저장한다

**CRUD 방식**: 현재 상태만 저장, 어떻게 그 상태에 도달했는지 모름
```
UPDATE videos SET view_count = view_count + 1
```

**Event Sourcing**: 상태 변이 자체를 이벤트로 저장, 현재 상태는 이벤트로부터 도출
```
INSERT { type: "VideoViewed", videoId, timestamp }
```

- 메시지를 일회성 알림으로 버리지 않고 **영구 저장**
- 현재 상태뿐 아니라 **과거 임의 시점의 상태**도 재구성 가능

---

## 6. 스트림 (Streams)

### 스트림이란?

메시지를 논리적으로 그룹화하는 단위. 보통 시스템의 **엔터티나 프로세스**를 나타낸다.
스트림 내에서 메시지는 **작성 순서대로** 저장된다.

### 스트림 종류

| 스트림 유형 | 형식 | 예시 | 설명 |
|---|---|---|---|
| **Entity Stream** | `{category}-{uuid}` | `identity-81cb4647-...` | 특정 엔터티의 이벤트만 포함, **단일 작성자** |
| **Category Stream** | `{category}` | `identity` | 해당 카테고리의 모든 엔터티 이벤트 포함 |
| **Command Stream** | `{category}:command-{uuid}` | `identity:command-81cb4647-...` | 특정 엔터티에 대한 명령 포함 |

### 카테고리 도출 규칙

- 스트림 이름에서 **첫 번째 대시(`-`) 왼쪽** 부분이 카테고리
- `identity-81cb4647-...` → 카테고리: `identity`
- `identity:command-81cb4647-...` → 카테고리: `identity:command` (entity stream과 **다른** 카테고리)

### 핵심 규칙

- 스트림은 **삭제되지 않음** (append-only)
- Entity stream은 **단일 작성자**만 허용
- **스트림 경계 = 컴포넌트 경계** → 하나의 카테고리에는 하나의 컴포넌트만 쓰기 권한 보유

---

## 7. 컴포넌트 경계 (Component Boundaries)

> "Stream boundaries are Component boundaries."

- `identity` 카테고리 → `Identity` 컴포넌트만 쓰기 가능
- `identity:command` 카테고리 → 같은 `Identity` 컴포넌트만 명령 처리 가능
- 모놀리스에서는 아무나 `users` 테이블에 컬럼 추가/행 수정 가능 → **이 경계가 없기 때문에** "Extract Microservices™"가 불가능

---

## 8. Video Views 기록: 설계 결정

### Event vs Command 선택 과정

**질문**: 비디오 시청을 기록할 때 Application이 Event를 직접 쓸까, Command를 써서 Component가 처리하게 할까?

**분석**:
1. `VideoViewed` 이벤트를 `viewing-{videoId}` 스트림에 기록
2. 나중에 가짜 조회 감지 알고리즘이 필요하면 → Component로 이동
3. 현재는 Application에서 직접 Event 작성으로 충분

**리팩터링 비용이 낮은 이유**:
- Application을 Event 대신 Command 쓰도록 변경 (간단)
- Component 작성 (어차피 해야 할 일)

**CRUD 방식이었다면**: 별도 API, 백그라운드 작업, 공유 DB 테이블 등 **설계적으로 복잡한 문제**가 발생

---

## 9. 첫 번째 메시지 작성 (코드)

### Before (CRUD - Chapter 1)

```javascript
function createActions({ db }) {
  function recordViewing(traceId, videoId) {
    // UPDATE videos SET view_count = view_count + 1
  }
  return { recordViewing }
}
```

### After (Event Sourcing - Chapter 2)

```javascript
function createActions({ messageStore }) {
  function recordViewing(traceId, videoId, userId) {
    // ❶ 이벤트 구성
    const viewedEvent = {
      id: uuid(),
      type: 'VideoViewed',
      metadata: { traceId, userId },
      data: { userId, videoId }
    }
    // ❷ 스트림 이름 결정
    const streamName = `viewing-${videoId}`
    // ❸ Message Store에 기록
    return messageStore.write(streamName, viewedEvent)
  }
  return { recordViewing }
}
```

### 핵심 변경점

| 항목 | Before (CRUD) | After (Event) |
|---|---|---|
| 의존성 | `db` (직접 DB 접근) | `messageStore` (메시지 저장소) |
| 동작 | `UPDATE` (행 갱신) | `write` (이벤트 추가) |
| 데이터 보존 | 현재 카운트만 | 모든 시청 이력 |
| 결합도 | 높음 (테이블 구조에 의존) | 낮음 (메시지 계약만) |

### 설정 변경 (config.js)

```javascript
const createMessageStore = require('./message-store')

function createConfig({ env }) {
  const postgresClient = createPostgresClient({
    connectionString: env.messageStoreConnectionString
  })
  const messageStore = createMessageStore({ db: postgresClient })
  const recordViewingsApp = createRecordViewingsApp({ messageStore })
  // ...
}
```

- Message Store는 별도의 PostgreSQL 연결 사용 (`MESSAGE_STORE_CONNECTION_STRING`)
- 기존 `db` 대신 `messageStore`를 주입

---

## 10. 이 챕터에서 아직 안 한 것

- **Message Store 구현**: 다음 챕터(Chapter 3)에서 구현
- **홈페이지 조회수 표시**: Aggregator + View Data가 필요 (이후 챕터)
- **Component**: Chapter 6부터 시작

---

## 핵심 정리

```
┌─────────────────────────────────────────────────────────────────┐
│  모놀리스의 본질 = 데이터 모델 (배포/코드 구조가 아님)              │
│  분산 모놀리스 = DB 테이블을 HTTP 뒤에 숨긴 것 (여전히 모놀리스)    │
│  진짜 서비스 = 자율적 컴포넌트 (비동기 메시지로만 통신)              │
│  메시지 = Command(요청, 명령형) + Event(사실, 과거형)              │
│  스트림 = 메시지의 논리적 그룹 (엔터티/프로세스 단위)               │
│  스트림 경계 = 컴포넌트 경계 (소유권 명확)                         │
│  이벤트 소싱 = 상태 변이를 이벤트로 저장 → 과거 재구성 가능          │
└─────────────────────────────────────────────────────────────────┘
```
