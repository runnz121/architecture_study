# Chapter 11: Building Async-Aware User Interfaces

## 챕터 요약

비동기 마이크로서비스 아키텍처에서 사용자 요청의 결과가 즉시 제공되지 않을 때 UI를 어떻게 구성할지 다룬다. 비디오 이름 변경 기능을 구현하면서 polling interstitial 패턴을 적용하고, Component 내부에서 validation을 수행하는 방법과 결과를 View Data로 aggregating하는 과정을 살펴본다.

---

## Defining Video Metadata Messages

비디오 이름 변경 기능을 위해 세 가지 message를 정의한다.

| Message | Type | 역할 |
|---------|------|------|
| `VideoNamed` | Event | 비디오 이름이 성공적으로 변경됨을 기록 |
| `VideoNameRejected` | Event | 이름 변경이 거부된 이유를 기록 |
| `NameVideo` | Command | 비디오 이름 변경을 요청 |

```json
{
  "type": "VideoNamed",
  "data": {
    "name": "Prod Bugs Hate This Guy: 42 Things You Didn't Know About JS"
  }
}
```

```json
{
  "type": "VideoNameRejected",
  "data": {
    "name": "",
    "reason": "ValidationError { \"name\": [ \"Can't be blank\" ] }"
  }
}
```

```json
{
  "type": "NameVideo",
  "data": {
    "videoId": "f94ce176-4a31-47e3-9593-c4ed4ee6ac84",
    "name": "Prod Bugs Hate This Guy: 42 Things You Didn't Know About JS"
  }
}
```

- **Component가 데이터의 소유권을 가지므로** validation도 Component 내부에서 수행한다.
- Application layer는 모든 입력을 통과시키고, Component가 비동기적으로 검증한다.

---

## Responding to Users When the Response Isn't Immediately Available

비동기 처리 결과를 사용자에게 보여주기 위해 **polling interstitial 패턴**을 사용한다.

### 전체 흐름

1. 사용자가 비디오 이름을 제출하면 `NameVideo` command를 message store에 기록
2. `traceId` 기반으로 `/creators-portal/video-operations/:traceId`로 redirect
3. 해당 페이지에서 `video_operations` View Data를 polling하여 결과 확인

```javascript
// HTTP handler - command 발행 후 redirect
function handleNameVideo (req, res, next) {
  const videoId = req.params.id
  const name = req.body.name
  actions
    .nameVideo(req.context, videoId, name)
    .then(() =>
      res.redirect(
        `/creators-portal/video-operations/${req.context.traceId}`
      )
    )
    .catch(next)
}
```

```javascript
// Action - NameVideo command 생성 및 기록
function nameVideo (context, videoId, name) {
  const nameVideoCommand = {
    id: uuid(),
    type: 'NameVideo',
    metadata: {
      traceId: context.traceId,
      userId: context.userId
    },
    data: { name, videoId }
  }
  const streamName = `videoPublishing:command-${videoId}`
  return messageStore.write(streamName, nameVideoCommand)
}
```

### video_operations View Data 스키마

```javascript
exports.up = function up (knex) {
  return knex.schema.createTable('video_operations', table => {
    table.string('trace_id').primary()
    table.string('video_id').notNullable()
    table.bool('succeeded').notNullable()
    table.string('failure_reason')
  })
}
```

| Column | 역할 |
|--------|------|
| `trace_id` | command의 traceId와 연결 (Primary Key) |
| `video_id` | 대상 비디오 ID |
| `succeeded` | 성공 여부 |
| `failure_reason` | 실패 시 사유 (성공 시 null) |

### Polling Interstitial 처리 로직

```javascript
function handleShowVideoOperation (req, res, next) {
  return queries.videoOperationByTraceId(req.params.traceId)
    .then(operation => {
      if (!operation || !operation.succeeded) {
        return res.render(
          'creators-portal/templates/video-operation',
          { operation }
        )
      }
      return res.redirect(
        `/creators-portal/videos/${operation.videoId}`
      )
    })
}
```

| 상태 | 동작 |
|------|------|
| operation이 없음 (pending) | "Operation pending" 표시 후 1초마다 자동 새로고침 |
| operation 실패 | 실패 사유 표시 + 비디오 페이지 링크 제공 |
| operation 성공 | 비디오 페이지로 redirect |

---

## Adding Validation to a Component

Component 내부에서 `NameVideo` command를 처리하는 pipeline을 구성한다.

### 처리 Pipeline

```javascript
NameVideo: command => {
  const context = { command, messageStore }
  return Bluebird.resolve(context)
    .then(loadVideo)
    .then(ensureCommandHasNotBeenProcessed)
    .then(ensureNameIsValid)
    .then(writeVideoNamedEvent)
    .catch(CommandAlreadyProcessedError, () => {})
    .catch(
      ValidationError,
      err => writeVideoNameRejectedEvent(context, err.message)
    )
}
```

| 단계 | 함수 | 역할 |
|------|------|------|
| 1 | `loadVideo` | 비디오의 publishing history 로드 |
| 2 | `ensureCommandHasNotBeenProcessed` | 멱등성 보장 - 이미 처리된 command인지 확인 |
| 3 | `ensureNameIsValid` | 이름 유효성 검사 |
| 4 | `writeVideoNamedEvent` | 성공 시 `VideoNamed` event 기록 |

### 멱등성 보장 (Idempotence)

```javascript
function ensureCommandHasNotBeenProcessed (context) {
  const command = context.command
  const video = context.video
  if (video.sequence > command.globalPosition) {
    throw new CommandAlreadyProcessedError()
  }
  return context
}
```

- `video.sequence`는 마지막으로 적용된 `VideoNamed` 또는 `VideoNameRejected` event의 `globalPosition`
- sequence가 command의 globalPosition보다 크면 이미 처리된 것으로 판단

### Projection 업데이트

```javascript
const videoPublishingProjection = {
  $init () {
    return {
      id: null,
      publishingAttempted: false,
      sourceUri: null,
      transcodedUri: null,
      sequence: 0,
      name: ''
    }
  },
  VideoNamed (video, videoNamed) {
    video.sequence = videoNamed.globalPosition
    video.name = videoNamed.data.name
    return video
  },
  VideoNameRejected (video, videoNameRejected) {
    video.sequence = videoNameRejected.globalPosition
    return video
  },
}
```

### Validation 로직

```javascript
const constraints = {
  name: {
    presence: { allowEmpty: false }
  }
}

function ensureNameIsValid (context) {
  const command = context.command
  const validateMe = { name: command.data.name }
  const validationErrors = validate(validateMe, constraints)
  if (validationErrors) {
    throw new ValidationError(validationErrors, constraints, context.video)
  }
  return context
}
```

- `validate.js` 라이브러리를 사용하여 빈 이름을 거부
- 실패 시 `VideoNameRejected` event를, 성공 시 `VideoNamed` event를 기록

---

## Aggregating Naming Results

`video_operations` View Data를 채우기 위한 Aggregator를 구현한다.

### Aggregator 구조

```javascript
function build ({ db, messageStore }) {
  const queries = createQueries({ db })
  const handlers = createHandlers({ queries })
  const subscription = messageStore.createSubscription({
    streamName: 'videoPublishing',
    handlers,
    subscriberId: componentId
  })
  function start () { subscription.start() }
  return { handlers, queries, start }
}
```

### Event Handlers

| Event | wasSuccessful | failureReason |
|-------|---------------|---------------|
| `VideoNamed` | `true` | `null` |
| `VideoNameRejected` | `false` | `event.data.reason` |

### Idempotent Upsert Query

```sql
INSERT INTO video_operations (trace_id, video_id, succeeded, failure_reason)
VALUES (:traceId, :videoId, :wasSuccessful, :failureReason)
ON CONFLICT (trace_id) DO NOTHING
```

- PostgreSQL의 `ON CONFLICT` 기능으로 멱등성을 보장한다.
- 동일한 `trace_id`로 재처리되더라도 기존 데이터를 덮어쓰지 않는다.

---

## Applying Naming Events to the Creators Portal View Data

`video_operations`와는 별도로, Creators Portal 대시보드와 개별 비디오 화면에 새 이름을 반영하기 위해 `creators-videos` Aggregator에 handler를 추가한다.

```javascript
// creators-videos Aggregator의 VideoNamed handler
VideoNamed: event =>
  queries.updateVideoName(
    streamToEntityId(event.streamName),
    event.position,
    event.data.name
  ),
```

```javascript
function updateVideoName (id, position, name) {
  return db.then(client =>
    client('creators_portal_videos')
      .update({ name, position })
      .where({ id })
      .where('position', '<', position)
  )
}
```

- **같은 event로부터 서로 다른 View Data를 구축**하는 사례이다.
- `position` 비교를 통해 멱등성을 보장한다 (이미 더 최신 event가 적용된 경우 무시).

---

## Justifying Our UI Decision

### Polling Interstitial 패턴의 장단점

| 항목 | 설명 |
|------|------|
| **장점** | 구현이 단순하고 서버 렌더링 환경에서 바로 적용 가능 |
| **단점** | 사용자 경험이 매끄럽지 않음 - 별도 페이지에서 polling이 눈에 보임 |
| **대안** | Rich client (예: React)에서 optimistic update + 백그라운드 polling |

### 핵심 교훈

- 비동기 아키텍처는 반드시 UI 패턴에 영향을 미친다. 기존 동기식 UI 관행을 그대로 사용하면 좋지 않은 경험이 된다.
- MVP 단계에서는 polling interstitial로 충분하며, 이후 rich client로 개선할 수 있다.
- Message 이름은 구체적이어야 한다: `VideoUpdated`가 아닌 `VideoNamed`를 선택한 이유는 비디오가 단순한 DB row가 아니라 **정의된 operation 집합을 가진 풍부한 entity**이기 때문이다.
