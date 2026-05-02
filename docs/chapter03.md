# Chapter 3: Putting Data in a Message Store

## 챕터 요약

Chapter 2에서 설계한 메시지 쓰기 인터페이스(`messageStore.write`)를 **실제 Message DB(PostgreSQL 기반)에 연결**하는 장이다.
Message Store의 구조, 메시지 저장 시 추가되는 필드, 낙관적 동시성 제어, 그리고 Kafka와의 차이점을 다룬다.

---

## 1. Message Store의 두 가지 요구사항

1. **Append-only 스트림에 불변 메시지를 영속화**
2. **낙관적 동시성 제어(Optimistic Concurrency Control) 제공**

---

## 2. 메시지에 추가되는 필드

Chapter 2에서 정의한 메시지(id, type, metadata, data)가 Message Store에 **기록될 때** 추가되는 필드들:

| 필드 | 설명 |
|---|---|
| `streamName` | 이 메시지가 속하는 스트림의 이름 |
| `position` | 스트림 내에서의 순서 (낙관적 동시성에 사용) |
| `globalPosition` | 전체 Message Store 내에서의 순서 (실시간 메시지 소비에 사용) |
| `time` | Message Store에 기록된 시각 (도메인 시간이 필요하면 `data`에 별도 저장) |

> `time`은 **인프라 시간**이다. 비즈니스 도메인의 시간(예: 결제 유효 일시)이 필요하면 반드시 `data` 필드에 넣어야 한다.

---

## 3. Message DB의 messages 테이블

```sql
CREATE TABLE IF NOT EXISTS message_store.messages (
  id              UUID NOT NULL DEFAULT gen_random_uuid(),
  stream_name     text NOT NULL,
  type            text NOT NULL,
  position        bigint NOT NULL,
  global_position bigserial NOT NULL,
  data            jsonb,
  metadata        jsonb,
  time            TIMESTAMP WITHOUT TIME ZONE DEFAULT (now() AT TIME ZONE 'utc') NOT NULL
);

ALTER TABLE message_store.messages
  ADD PRIMARY KEY (global_position) NOT DEFERRABLE INITIALLY IMMEDIATE;
```

- PostgreSQL 기반, `message_store` 스키마에 설치
- 단일 `messages` 테이블 + 인덱스 + 사용자 정의 함수(UDF)
- 테이블을 직접 쿼리하지 않고 **UDF를 통해 상호작용**

---

## 4. Message Store 코드 구조

### 4-1. 스캐폴딩 (message-store/index.js)

```javascript
const createWrite = require('./write')

function createMessageStore({ db }) {
  const write = createWrite({ db })
  return {
    write: write,
  }
}
```

- 의존성 주입 패턴 유지: `db`(PostgreSQL 클라이언트)를 받아서 `write` 함수에 전달

### 4-2. DB 연결 (postgres-client.js)

```javascript
function createDatabase({ connectionString }) {
  const client = new pg.Client({ connectionString })

  // 연결 시 search_path를 message_store 스키마로 설정
  // → Message DB의 UDF를 바로 호출할 수 있도록
  function connect() {
    client.connect()
      .then(() => client.query('SET search_path = message_store, public'))
      .then(() => client)
  }

  function query(sql, values = []) {
    return connect().then(client => client.query(sql, values))
  }

  return { query, stop: () => client.end() }
}
```

핵심: `search_path = message_store, public` 설정으로 Message DB의 함수들을 사용 가능하게 함

### 4-3. Write 함수 (message-store/write.js)

```javascript
const writeFunctionSql =
  'SELECT message_store.write_message($1, $2, $3, $4, $5, $6)'

function createWrite({ db }) {
  return (streamName, message, expectedVersion) => {
    if (!message.type) {
      throw new Error('Messages must have a type')
    }

    const values = [
      message.id,
      streamName,
      message.type,
      message.data,
      message.metadata,
      expectedVersion
    ]
    return db.query(writeFunctionSql, values)
  }
}
```

- Message DB의 `write_message` UDF를 호출
- `expectedVersion`은 낙관적 동시성 제어에 사용 (아래 참조)

---

## 5. 낙관적 동시성 제어 (Optimistic Concurrency Control)

### 왜 필요한가?

두 인스턴스가 동시에 같은 스트림에 쓰려는 경우:

```
예: 은행 계좌에 $5 입금 명령이 두 인스턴스에서 동시 처리
→ Deposited 이벤트가 2개 쓰여서 $10이 되면 안 됨
```

### 동작 방식

- 스트림의 **버전** = 스트림 내 메시지의 최대 `position` 값
- `expectedVersion` 파라미터: "이 스트림이 아직 버전 3일 때만 써줘"
- 버전이 다르면 → DB가 에러 발생 → `VersionConflictError`로 변환

### 에러 처리

```javascript
const versionConflictErrorRegex = /^Wrong.*Stream Version: (\d+)\)/

return db.query(writeFunctionSql, values)
  .catch(err => {
    const errorMatch = err.message.match(versionConflictErrorRegex)
    if (errorMatch === null) throw err  // 버전 충돌이 아니면 그대로 throw

    const actualVersion = parseInt(errorMatch[1], 10)
    throw new VersionConflictError(streamName, actualVersion, expectedVersion)
  })
```

- DB의 원시 에러를 `VersionConflictError`로 정규화
- 스트림 이름, 실제 버전, 기대 버전 정보를 포함

---

## 6. Kafka vs Message DB

| 항목 | Kafka | Message DB (Eventide) |
|---|---|---|
| 성격 | 메시지 **브로커** (버퍼) | 메시지 **스토어** |
| 토픽/스트림 | **사전 정의** 필요 | **동적 생성** 가능 |
| 낙관적 동시성 | 미지원 (Minor 우선순위 이슈) | 기본 지원 |
| 메시지 보존 | 설정으로 무기한 가능 | 기본적으로 영구 보존 |
| 운영 복잡도 | 별도 인프라 필요 | PostgreSQL만 있으면 됨 |

> "Kafka is a buffer and not a Message Store." — Scott Bellware

### 왜 Kafka가 이벤트 소싱에 부적합한가?

1. **토픽 사전 정의**: 사용자별 이벤트 스트림(`identity-{uuid}`)처럼 동적으로 생성되는 스트림 불가
2. **낙관적 동시성 미지원**: 동시 쓰기 시 데이터 무결성 보장 불가
3. **추가 운영 부담**: 이미 쓰고 있는 PostgreSQL로 충분한데 별도 인프라 도입할 이유 없음

---

## 7. 현재 진행 상황

```
Chapter 1: CRUD로 video views 기록           ✅ (한계 체험)
Chapter 2: VideoViewed 이벤트 설계 + 코드 작성  ✅ (messageStore.write 호출)
Chapter 3: Message Store 실제 구현             ✅ (PostgreSQL에 저장)
Chapter 4: 저장된 이벤트를 유용한 형태로 변환     ← 다음
```

**남은 문제**: 이벤트는 저장했는데, 홈페이지에 표시할 "전체 조회수"는 어떻게 만들까?
→ Chapter 4에서 **Projection(프로젝션)** 으로 해결
