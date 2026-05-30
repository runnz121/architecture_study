# Chapter 10: Performing Background Jobs with Microservices

## 챕터 요약

마이크로서비스 아키텍처에서 장시간 실행되는 프로세스(background job)를 처리하는 방법을 다룬다. 기존의 별도 queue 시스템(Kafka, RabbitMQ 등) 없이도 Message Store와 비동기 메시지 기반 구조만으로 background job을 자연스럽게 수행할 수 있음을 보여준다. 이메일 전송과 비디오 트랜스코딩이라는 두 가지 use case를 통해 이를 실증하며, idempotence 전략에서 중복 작업을 수용하는 trade-off도 논의한다.

---

## Accidental Complexity

기존 모놀리스에서는 background job을 위해 별도의 인프라(DelayedJob, node-resque, Sidekiq, Redis 등)를 구축해야 했다. 이것이 바로 **accidental complexity**(우발적 복잡성)이다.

Greg Young의 질문이 핵심을 찌른다:

> "What is a queue but a degenerate form of a stream?"

| 전통적 Background Job | 메시지 기반 마이크로서비스 |
|---|---|
| 별도의 queue 인프라 필요 (Redis, RabbitMQ 등) | Message Store만으로 충분 |
| 일회성 worker 설정 필요 | 기존 subscription 메커니즘 그대로 사용 |
| 시스템마다 다른 패턴 | 모든 비동기 작업이 동일한 패턴 |
| 추가 학습 비용 발생 | 이미 알고 있는 primitive로 해결 |

Stream은 이미 비동기 작업의 근본 building block이므로, queue가 제공하는 것을 stream이 제공하지 못하는 것은 없다.

---

## Use Case #1: Sending Emails

Chapter 9에서 이미 이메일 전송을 request/response cycle 밖으로 분리했다. 이메일 전송은 이미 Message Store가 제공하는 구조만으로 완성된 background job의 예시다. 별도로 추가할 것이 없다.

---

## Use Case #2: Transcoding Videos

Content creator가 비디오를 업로드하면, 사용자의 디바이스와 인터넷 환경에 맞는 포맷으로 **transcode**해야 한다. 이 작업은 수초에서 수분이 걸리므로 HTTP connection을 열어두고 기다릴 수 없다.

### Message Contract

**PublishVideo** command:

```json
{
  "id": "f72ee7ab-066f-403c-b4b6-5f233fd34c81",
  "type": "PublishVideo",
  "data": {
    "ownerId": "bb6a04b0-cb74-4981-b73d-24b844ca334f",
    "sourceUri": "https://sourceurl.com/",
    "videoId": "9bfb5f98-36f4-44a2-8251-ab06e0d6d919"
  }
}
```

**VideoPublished** event (성공 시):

```json
{
  "id": "d260b63a-8195-4488-b5e4-8884ac792c61",
  "type": "VideoPublished",
  "data": {
    "ownerId": "bb6a04b0-cb74-4981-b73d-24b844ca334f",
    "sourceUri": "https://sourceurl.com/",
    "transcodedUri": "https://someswankyurl.com/",
    "videoId": "9bfb5f98-36f4-44a2-8251-ab06e0d6d919"
  }
}
```

**VideoPublishingFailed** event (실패 시):

```json
{
  "id": "3ed7c799-7a74-4e98-9759-013ef031ac10",
  "type": "VideoPublishingFailed",
  "data": {
    "reason": "Invalid format",
    "ownerId": "bb6a04b0-cb74-4981-b73d-24b844ca334f",
    "sourceUri": "https://sourceurl.com/",
    "videoId": "9bfb5f98-36f4-44a2-8251-ab06e0d6d919"
  }
}
```

| 메시지 | 유형 | 역할 |
|---|---|---|
| `PublishVideo` | Command (명령) | 비디오 publish 요청 |
| `VideoPublished` | Event (이벤트) | publish 성공 기록 |
| `VideoPublishingFailed` | Event (이벤트) | publish 실패 기록 (reason 포함) |

향후 다중 포맷 transcoding 요구사항에 대비하여, `transcodedUri`를 배열로 만드는 대신 **각 포맷마다 별도 event를 기록**하는 구조를 선택했다.

---

## Describing the Creators Portal

Creators Portal은 Message Store에 command를 기록하는 **Application**이다. 사용자가 비디오를 업로드하면 `PublishVideo` command를 작성한다.

핵심 포인트: message contract가 확정되면, **다른 팀이 UI와 Aggregator를 병렬로 개발**할 수 있다. Component 개발과 UI 개발은 서로를 기다릴 필요가 없다.

---

## Aggregating Is Also for Other Teams

다른 팀은 Creators Portal이 사용하는 View Data를 채우는 **Aggregator**도 빌드한다. Message contract를 통해 소통하므로 내부 구현에 의존하지 않으며, **개별 조각의 개발 순서는 중요하지 않다.**

---

## Building the Video Publishing Component

### Component 구조

```javascript
function build ({ messageStore }) {
  const handlers = createHandlers({ messageStore })
  const subscription = messageStore.createSubscription({
    streamName: 'videoPublishing:command',
    handlers: handlers,
    subscriberId: 'video-publishing'
  })
  function start () {
    subscription.start()
  }
  return { handlers, start }
}
```

`videoPublishing:command` category stream을 구독하여 command를 처리한다.

### Handler의 기본 흐름

모든 Component handler는 동일한 4단계 패턴을 따른다:

| 단계 | 설명 | 함수 |
|---|---|---|
| 1 | Entity의 현재 상태 load & project | `loadVideo` |
| 2 | Idempotent 처리 보장 | `ensurePublishingNotAttempted` |
| 3 | 실제 작업 수행 | `transcodeVideo` |
| 4 | 결과 event 기록 | `writeVideoPublishedEvent` |

### Handler Pipeline

```javascript
return (
  Bluebird.resolve(context)
    .then(loadVideo)
    .then(ensurePublishingNotAttempted)
    .then(transcodeVideo)
    .then(writeVideoPublishedEvent)
    .catch(AlreadyPublishedError, () => {})
    .catch(err => writeVideoPublishingFailedEvent(err, context))
)
```

### Projection

```javascript
const videoPublishingProjection = {
  $init () {
    return {
      id: null,
      publishingAttempted: false,
      sourceUri: null,
      transcodedUri: null,
    }
  },
  VideoPublished (video, videoPublished) {
    video.id = videoPublished.data.videoId
    video.publishingAttempted = true
    video.ownerId = videoPublished.data.ownerId
    video.sourceUri = videoPublished.data.sourceUri
    video.transcodedUri = videoPublished.data.transcodedUri
    return video
  },
  VideoPublishingFailed (video, videoPublishingFailed) {
    video.id = videoPublishingFailed.data.videoId
    video.publishingAttempted = true
    video.ownerId = videoPublishingFailed.data.ownerId
    video.sourceUri = videoPublishingFailed.data.sourceUri
    return video
  }
}
```

`publishingAttempted` 필드가 성공/실패 모두에서 `true`로 설정되어 idempotence를 보장한다.

### Idempotence 체크

```javascript
function ensurePublishingNotAttempted (context) {
  const { video } = context
  if (video.publishingAttempted) {
    throw new AlreadyPublishedError()
  }
  return context
}
```

### Error Handling

- `AlreadyPublishedError`: 이미 처리된 경우 조용히 무시 (`catch(AlreadyPublishedError, () => {})`)
- 기타 에러: `VideoPublishingFailed` event를 기록하여 디버깅 가능한 상태를 남김

---

## Accepting Potential Duplication

Idempotence 전략에서 중요한 trade-off가 존재한다:

> **"작업을 0번 하는 것이 더 나쁜가, 아니면 2번 이상 하는 것이 더 나쁜가?"**

비디오 transcoding의 경우:

| 고려사항 | 판단 |
|---|---|
| Transcoding 비용 | 저렴 |
| Transcoding 안 하면? | 시청자에게 비디오가 전달되지 않음 (치명적) |
| 중복 transcoding 하면? | 동일 destination에 덮어쓰기, orphaned file 없음 |
| 결론 | **중복 작업을 수용** |

시나리오: Component가 transcoding을 완료한 후 `VideoPublished` event를 기록하기 전에 재시작(배포 또는 crash)되면, 다시 시작할 때 `PublishVideo` command를 보고 동일 작업을 반복한다. 비즈니스 팀과 협의 후, 이 중복은 수용 가능하다고 결정했다.

이 선택은 **비즈니스 맥락에 따라 달라지는 결정**이며, 반드시 비즈니스 팀과 함께 논의해야 한다.
