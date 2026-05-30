# Chapter 15: Continuing the Journey

## 챕터 요약

이 장은 서비스 기반 아키텍처에서 다루지 못한 중요한 주제들을 개괄적으로 소개하는 마지막 챕터이다. Concurrency 처리, Snapshotting, Message Contract 변경, 다중 프로그래밍 언어 사용, 그리고 Monolith의 활용이라는 다섯 가지 핵심 주제를 다루며, 독자가 학습을 이어나갈 수 있는 출발점을 제공한다.

---

## Handling Concurrency

### 문제: 동시 실행 시 중복 처리

Autonomous Component는 stateful하기 때문에 단순히 인스턴스를 늘리는 것으로 scaling할 수 없다. 두 인스턴스가 동일한 메시지를 동시에 처리하면, 둘 다 idempotence check를 통과하고 중복 event를 기록하는 문제가 발생한다.

> 이것은 idempotence 문제가 아니라 **concurrency 문제**이다. 단일 subscriber가 같은 메시지를 두 번 처리해도 중복이 발생하지 않지만, 두 인스턴스가 동시에 처리하면 중복이 발생한다.

### 해결: expectedVersion (Optimistic Concurrency Control)

`messageStore.write()` 함수의 세 번째 파라미터인 `expectedVersion`을 사용하여 동시 쓰기를 방지한다.

```javascript
// expectedVersion을 -1로 설정하면 스트림에 메시지가 없을 것을 기대
.write(identityStream, userRegisteredEvent, -1)
```

| 상황 | 결과 |
|------|------|
| 첫 번째 인스턴스가 먼저 write | 성공적으로 event 기록 |
| 두 번째 인스턴스가 이후 write | `VersionConflictError` 발생 |
| 두 번째 인스턴스 재시작 후 | idempotence check에 의해 중복 방지 |

### Scaling: Consumer Group 패턴

여러 인스턴스가 서로 다른 작업을 처리하도록 stream을 분배한다. Stream name의 hash 값을 인스턴스 수로 나눈 나머지(modulo)로 담당 인스턴스를 결정한다.

```javascript
const crypto = require('crypto')
const consumerIdentifier = 1
const numberOfConsumers = 3

const hash = crypto.createHash('sha256')
hash.update('streamCategory-20c27c90-ca76-4dc9-b3b8-afea34137103')
const number = hash.digest().readUInt32BE()

const owningConsumerIdentifier = number % numberOfConsumers
const owningConsumer = owningConsumerIdentifier === consumerIdentifier
```

---

## Snapshotting

### 개념

Snapshotting은 전체 event stream을 매번 다시 projection하는 대신, 중간 결과를 저장하여 새로운 event만 처리하는 **성능 최적화 기법**이다.

### 동작 방식

| 단계 | 설명 |
|------|------|
| 1. 초기 projection | 모든 event를 `$init`부터 reduce하여 entity 생성 |
| 2. Snapshot 저장 | projection 결과를 저장 |
| 3. 이후 projection | 저장된 snapshot에서 시작하여 새로운 event만 reduce |

```
// Snapshot 없이: 모든 event를 처음부터 처리
VideoPublished → VideoNamed("Work") → VideoNamed("Rework") → VideoNamed("Snapshot!")
$init ──────────────────────────────────────────────────────────► 최종 결과

// Snapshot 사용: 저장된 지점부터 새 event만 처리
[Snapshot: {isPublished: true, name: 'Rework'}] → VideoNamed("Snapshot!")
저장된 결과 ──────────────────────────────────────► 최종 결과
```

### 핵심 포인트

- Immutable data이기 때문에 snapshot이 가능하다 (event가 변경되면 매번 전체를 다시 처리해야 함)
- **Stream을 snapshot하는 것이 아니라 entity를 snapshot**한다 (하나의 stream에 여러 snapshot 가능)
- 컴퓨터는 빠르므로, event 수가 매우 많아지기 전까지는 큰 차이를 만들지 않는다
- Snapshotting은 `fetch` 함수 뒤에 숨겨져 호출하는 코드가 세부사항을 알 필요가 없어야 한다

---

## Changing the Message Contract

### 원칙: 하지 마라

Message contract는 **immutable**하게 설계되어야 한다. 변경 가능하지 않은 것들은 더 많은 사고, 고려, 설계가 필요하다.

| 권장 사항 | 설명 |
|-----------|------|
| 충분한 설계 시간 확보 | 코드 작성 전에 최소 15분 이상 설계에 투자 |
| Contract 변경이 필요한 경우 | Greg Young의 [Versioning in an Event Sourced System](https://leanpub.com/esversioning) 참고 |
| 설계는 Agile을 포기하는 것이 아님 | 충분히 생각한 후 코딩하는 것이 소프트웨어 개발자의 본업 |

---

## Using Different Programming Languages

PostgreSQL과 통신할 수 있는 언어라면 어떤 언어든 이 시스템에서 사용할 수 있다. 다만 각 언어별로 Message Store 코드를 재구현해야 한다.

| 고려사항 | 설명 |
|----------|------|
| 운영 비용 증가 | 여러 언어 사용 시 운영 복잡도 상승 |
| 교육 오버헤드 | 팀원 교육 비용 발생 |
| Ruby 사용 시 | [Eventide Project](https://eventide-project.org) 라이브러리 직접 활용 가능 |

---

## Making Use of Monoliths

### Monolith는 Special-Purpose Tool이다

저자는 monolithic architecture를 비판했지만, 가치가 없는 것은 아니다. MVC ORM web framework는 **프로토타이핑에 가장 빠른 도구**이다. 하지만 special-purpose tool은 특별한 상황에서만 사용해야 한다.

### 서비스 기반 아키텍처 지식의 활용

서비스 기반 아키텍처를 알고 있으면, monolith를 만들 때도 장기적으로 전환이 쉬운 구조로 설계할 수 있다.

| 기존 Monolith 방식 | 개선된 방식 |
|---------------------|-------------|
| 단일 `users` 테이블에 모든 관심사 집중 | 관심사별로 테이블을 분리하고, 필요 시 join |
| 전환 시 대규모 리팩토링 필요 | 관심사가 이미 분리되어 pub/sub 아키텍처로 쉽게 전환 |

> "특별한 상황(special-purpose)"임을 기억하라.
