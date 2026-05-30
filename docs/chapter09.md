# Chapter 9: Adding an Email Component

## 챕터 요약

이 챕터에서는 이메일 전송을 담당하는 `send-email` Component를 구축하고, 기존 `identity` Component가 이를 활용하여 사용자 등록 시 환영 이메일을 보내도록 orchestration하는 과정을 다룬다.
Idempotence 처리, originStreamName을 통한 메시지 추적, 그리고 orchestration과 choreography의 차이를 학습한다.

---

## 1. Email Component의 메시지 발견 (Discovering the Email Component Messages)

`send-email` Component는 이메일 전송만을 담당하는 범용 Component이다. 세 가지 메시지 타입을 정의한다:

| 메시지 타입 | 역할 | 카테고리 |
|---|---|---|
| `Send` (command) | 이메일 전송을 요청 | `sendEmail:command` |
| `Sent` (event) | 이메일 전송 성공을 기록 | `sendEmail` |
| `Failed` (event) | 이메일 전송 실패를 기록 | `sendEmail` |

### Send command 예시

```json
{
  "id": "636401d3-6585-4887-8576-ec8003e6b380",
  "type": "Send",
  "data": {
    "emailId": "e0c6e804-ae9e-4c9c-bd55-b0c049a03993",
    "to": "lucky-recipient@example.com",
    "subject": "Rare investment opportunity",
    "text": "12 million US pounds stirling",
    "html": "<blink>12 million US pounds stirling</blink>"
  }
}
```

### Sent event 예시

```json
{
  "id": "c5f672bd-cf5f-4e6b-91ad-60a17cd6bbab",
  "type": "Sent",
  "data": {
    "emailId": "e0c6e804-ae9e-4c9c-bd55-b0c049a03993",
    "to": "lucky-recipient@example.com",
    "subject": "Rare investment opportunity",
    "text": "12 million US pounds stirling",
    "html": "<blink>12 million US pounds stirling</blink>"
  }
}
```

> **핵심**: `emailId`는 **command를 보내는 쪽(클라이언트)**이 제공해야 한다. Idempotence가 작동하려면 클라이언트가 ID를 제어해야 한다.

---

## 2. Idempotence 다루기 (Addressing Idempotence)

SMTP는 전송 성공 여부를 확실히 보장하지 못한다. 이메일이 전송된 후 이벤트 기록 전에 시스템이 크래시되면, 재시작 시 이메일이 **중복 전송**될 수 있다.

| 실패 케이스 | 결과 |
|---|---|
| 이메일을 보내지 않음 | 사용자가 중요한 알림을 못 받음 |
| 이메일을 중복 전송 | 사용자가 같은 이메일을 여러 번 받음 |

**비즈니스 팀과의 협의 결과**: 등록 이메일은 안 보내는 것보다 **중복 전송이 낫다**고 결정.

> 신용카드 결제 같은 경우는 반대이다 -- 중복 과금보다 미과금이 낫다. 이런 경우에는 **과금 시도 전에 먼저 이벤트를 기록**하는 전략을 사용한다.

---

## 3. Component 추가하기 (Adding the Component)

### 3-1. 최상위 함수 (`send-email/index.js`)

```javascript
const createSend = require('./send')

function build ({ messageStore, systemSenderEmailAddress, transport }) {
  const justSendIt = createSend({ transport })
  const handlers = createHandlers({
    messageStore,
    justSendIt,
    systemSenderEmailAddress
  })

  const subscription = messageStore.createSubscription({
    streamName: 'sendEmail:command',
    handlers,
    subscriberId: 'components:send-email'
  })

  function start () {
    subscription.start()
  }

  return { handlers, start }
}
```

### Dependencies

| 의존성 | 용도 |
|---|---|
| `messageStore` | Message Store에 대한 참조 |
| `systemSenderEmailAddress` | 시스템 발신 이메일 주소 ("from" 주소) |
| `transport` | nodemailer의 전송 메커니즘을 캡슐화하는 객체 |

### 3-2. 전송 함수 (`send-email/send.js`)

```javascript
const nodemailer = require('nodemailer')
const SendError = require('./send-error')

function createSend ({ transport }) {
  const sender = nodemailer.createTransport(transport)

  return function send (email) {
    const potentialError = new SendError()

    return sender.sendMail(email)
      .catch(err => {
        potentialError.message = err.message
        throw potentialError
      })
  }
}
```

> **potentialError 패턴**: Node.js에서는 `catch()` 핸들러에서 실제 stack trace를 잃어버릴 수 있다. 에러가 발생하기 전에 `SendError`를 미리 인스턴스화하여 올바른 stack trace를 캡처한 뒤, 에러 발생 시 메시지만 복사한다.

---

## 4. 이메일 전송 (Sending the Email)

### 4-1. Send command handler - Promise chain

```javascript
function createHandlers ({ justSendIt, messageStore, systemSenderEmailAddress }) {
  return {
    Send: command => {
      const context = {
        messageStore, justSendIt, systemSenderEmailAddress,
        sendCommand: command
      }
      return Bluebird.resolve(context)
        .then(loadEmail)                  // 이메일 스트림을 projection
        .then(ensureEmailHasNotBeenSent)  // 이미 전송했는지 확인
        .then(sendEmail)                  // 실제 전송
        .then(writeSentEvent)             // Sent 이벤트 기록
        .catch(AlreadySentError, () => {})        // 이미 전송 -> no-op
        .catch(SendError, err => writeFailedEvent(context, err))  // 전송 실패 기록
    }
  }
}
```

### 4-2. 이메일 상태 로드 (`load-email.js`)

```javascript
const emailProjection = {
  $init () { return { isSent: false } },
  Sent (email) {
    email.isSent = true
    return email
  }
}

function loadEmail (context) {
  const streamName = `sendEmail-${context.sendCommand.data.emailId}`
  return context.messageStore
    .fetch(streamName, emailProjection)
    .then(email => {
      context.email = email
      return context
    })
}
```

### 4-3. Idempotence 체크 (`ensure-email-has-not-been-sent.js`)

```javascript
function ensureEmailHasNotBeenSent (context) {
  if (context.email.isSent) {
    throw new AlreadySentError()
  }
  return context
}
```

### 4-4. 실제 전송 (`send-email.js`)

```javascript
function sendEmail (context) {
  const email = {
    from: context.systemSenderEmailAddress,
    to: context.sendCommand.data.to,
    subject: context.sendCommand.data.subject,
    text: context.sendCommand.data.text,
    html: context.sendCommand.data.html
  }
  return context.justSendIt(email).then(() => context)
}
```

### 4-5. Sent 이벤트 기록 (`write-sent-event.js`)

```javascript
function writeSentEvent (context) {
  const sendCommand = context.sendCommand
  const streamName = `sendEmail-${sendCommand.data.emailId}`
  const event = {
    id: uuid(),
    type: 'Sent',
    metadata: {
      originStreamName: sendCommand.metadata.originStreamName,
      traceId: sendCommand.metadata.traceId,
      userId: sendCommand.metadata.userId
    },
    data: sendCommand.data
  }
  return context.messageStore.write(streamName, event)
}
```

> **originStreamName**: Send command를 보낸 원래 Component의 스트림 이름을 metadata에 전파한다. 이를 통해 어떤 Component가 이 이메일 전송을 요청했는지 추적할 수 있다.

### 4-6. Failed 이벤트 기록 (`write-failed-event.js`)

Failed 이벤트는 Sent와 동일한 구조이나, `reason` 속성이 추가되어 실패 원인을 기록한다.

---

## 5. Component 실행하기 (Running the Component)

### config.js에 Component 등록

```javascript
const createPickupTransport = require('nodemailer-pickup-transport')
const createSendEmailComponent = require('./components/send-email')

function createConfig ({ env }) {
  const transport = createPickupTransport({ directory: env.emailDirectory })
  const sendEmailComponent = createSendEmailComponent({
    messageStore,
    systemSenderEmailAddress: env.systemSenderEmailAddress,
    transport
  })
  const components = [
    // ...
    sendEmailComponent,
  ]
}
```

### env.js에 환경변수 추가

```javascript
emailDirectory: requireFromEnv('EMAIL_DIRECTORY'),
systemSenderEmailAddress: requireFromEnv('SYSTEM_SENDER_EMAIL_ADDRESS'),
```

> `nodemailer-pickup-transport`는 이메일을 파일시스템에 `.eml` 파일로 기록한다. 개발/테스트 환경에서 실제 이메일을 보내지 않고 결과를 확인할 수 있다.

---

## 6. 등록 프로세스에 이메일 추가 (Adding Email to the Registration Process)

`identity` Component가 자신의 `Registered` 이벤트를 관찰하여 `send-email` Component에 Send command를 작성하도록 한다.

### 전체 흐름 (6단계)

| 단계 | 주체 | 동작 |
|---|---|---|
| 1 | `identity` | 자신의 `Registered` 이벤트를 관찰 |
| 2 | `identity` | `sendEmail:command` 스트림에 `Send` command 작성 |
| 3 | `send-email` | `Send` command를 관찰 |
| 4 | `send-email` | 이메일 전송 후 `sendEmail` 스트림에 `Sent` 이벤트 기록 |
| 5 | `identity` | `Sent` 이벤트를 관찰 |
| 6 | `identity` | 자신의 identity 스트림에 `RegistrationEmailSent` 이벤트 기록 |

### identity의 Registered 이벤트 핸들러

```javascript
function createIdentityEventHandlers ({ messageStore }) {
  return {
    Registered: event => {
      const context = {
        messageStore, event,
        identityId: event.data.userId
      }
      return Bluebird.resolve(context)
        .then(loadIdentity)
        .then(ensureRegistrationEmailNotSent)
        .then(renderRegistrationEmail)
        .then(writeSendCommand)
        .catch(AlreadySentRegistrationEmailError, () => {})
    }
  }
}
```

### identity projection에 registrationEmailSent 추가

```javascript
const identityProjection = {
  $init () {
    return {
      id: null, email: null,
      isRegistered: false,
      registrationEmailSent: false    // 추가
    }
  },
  // ...
  RegistrationEmailSent (identity) {  // 추가
    identity.registrationEmailSent = true
    return identity
  }
}
```

---

## 7. Registration Email 기록하기 (Recording Registration Emails)

### writeSendCommand - UUID v5를 활용한 emailId 생성

```javascript
const uuidv4 = require('uuid/v4')
const uuidv5 = require('uuid/v5')
const uuidv5Namespace = '0c46e0b7-dfaf-443a-b150-053b67905cc2'

function writeSendCommand (context) {
  const identity = context.identity
  const emailId = uuidv5(identity.email, uuidv5Namespace)  // 결정적 UUID

  const sendEmailCommand = {
    id: uuidv4(),
    type: 'Send',
    metadata: {
      originStreamName: `identity-${identity.id}`,   // 원본 스트림 설정
      traceId: event.metadata.traceId,
      userId: event.metadata.userId
    },
    data: {
      emailId, to: identity.email,
      subject: email.subject, text: email.text, html: email.html
    }
  }

  const streamName = `sendEmail:command-${emailId}`
  return context.messageStore.write(streamName, sendEmailCommand)
}
```

> **UUID v5**: 알려진 데이터(이메일 주소)를 해싱하여 **결정적(deterministic) UUID**를 생성한다. 같은 이메일 주소는 항상 같은 emailId를 생성하므로 idempotence에 활용된다.

### Sent 이벤트를 관찰하여 RegistrationEmailSent 기록

```javascript
function createSendEmailEventHandlers ({ messageStore }) {
  return {
    Sent: event => {
      const originStreamName = event.metadata.originStreamName
      const identityId = streamNameToId(originStreamName)

      return Bluebird.resolve({ messageStore, event, identityId })
        .then(loadIdentity)
        .then(ensureRegistrationEmailNotSent)
        .then(writeRegistrationEmailSentEvent)
        .catch(AlreadySentRegistrationEmailError, () => {})
    }
  }
}
```

Subscription 설정 시 **`originStreamName` 파라미터**를 사용하여 identity가 발생시킨 이메일만 필터링한다:

```javascript
const sendEmailEventSubscription = messageStore.createSubscription({
  streamName: 'sendEmail',
  handlers: sendEmailEventHandlers,
  originStreamName: 'identity',              // identity 카테고리만 필터
  subscriberId: 'components:identity:sendEmailEvents'
})
```

---

## 8. Message Store의 Origin Stream 인식 (Making the Message Store Aware of Origin Streams)

### filterOnOriginMatch 함수

```javascript
function filterOnOriginMatch (messages) {
  if (!originStreamName) {
    return messages          // originStreamName 미지정 시 모든 메시지 통과
  }

  return messages.filter(message => {
    const originCategory =
      message.metadata && category(message.metadata.originStreamName)
    return originStreamName === originCategory
  })
}
```

### category 함수 (`message-store/category.js`)

```javascript
function category (streamName) {
  if (streamName == null) return ''
  return streamName.split('-')[0]
}
```

> 스트림 이름 `identity-88513bc7-...`에서 `-` 앞부분인 `identity`가 **카테고리**이다. subscription의 `originStreamName`과 메시지 metadata의 originStreamName 카테고리를 비교하여 필터링한다.

---

## 9. Idempotence 재검토 (Revisiting Idempotence)

외부 시스템(이메일 provider)과 상호작용할 때의 idempotence 전략은 **비즈니스 요구사항에 따라** 달라진다:

| 시나리오 | 선호 전략 | 이유 |
|---|---|---|
| 등록 이메일 | 중복 전송 허용 (at-least-once) | 안 보내는 것보다 중복이 낫다 |
| 신용카드 결제 | 미과금 허용 (at-most-once) | 중복 과금은 치명적, 관리 도구로 수동 처리 |

핵심 원칙:
- 외부 시스템과의 상태 일치를 **보장할 수 없다**
- 각 상호작용 단계를 **이벤트로 기록**하면 디버깅이 용이하다
- 어떤 실패 모드가 더 나은지 반드시 **비즈니스 팀과 협의**하고, 그 결정을 **문서화**해야 한다

---

## 10. Orchestration vs. Choreography

| 구분 | Orchestration | Choreography |
|---|---|---|
| 정의 | 한 Component가 명시적으로 다른 Component에 command를 작성 | 각 Component가 이벤트를 관찰하고 독립적으로 행동 |
| 이 챕터의 예 | `identity`가 `send-email`에 `Send` command를 명시적으로 작성 | `send-email`이 `Registered` 이벤트를 직접 관찰하여 이메일 전송 |
| 장점 | 프로세스가 **한 곳에서 명시적으로** 파악 가능 | Component 간 결합도가 낮음 |
| 단점 | 발신 Component가 수신 Component의 존재를 알아야 함 | 전체 프로세스를 파악하려면 **여러 Component를 살펴야** 함 |
| 관련 패턴 | Process Manager, Saga | 이벤트 기반 반응형 |

> 이 책에서는 프로세스의 명시성을 위해 **orchestration을 선호**한다. 등록 프로세스의 일부로 이메일이 나가야 한다는 것을 한 곳에서 확인할 수 있기 때문이다.

---

## 핵심 정리

1. **send-email Component**: 이메일 전송만 담당하는 범용 Component (Send command -> Sent/Failed event)
2. **originStreamName**: 어떤 Component가 이메일 전송을 요청했는지 추적하는 metadata 메커니즘
3. **UUID v5**: 결정적 UUID 생성으로 동일한 이메일에 대해 항상 같은 emailId를 보장
4. **Idempotence 전략**: 비즈니스 팀과 협의하여 결정하고 문서화
5. **Orchestration**: Component 간 협력 시 명시적 command 작성을 통해 프로세스를 추적 가능하게 유지
