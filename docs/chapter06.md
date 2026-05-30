# Chapter 6: Registering Users

## 챕터 요약

이 챕터에서는 사용자 등록이라는 비즈니스 프로세스를 domain message로 분해하는 방법을 다룬다. CRUD 방식의 `UserCreated` 대신 도메인 의도를 반영한 `Register` command와 `Registered` event를 정의하고, eventually consistent한 환경에서 데이터 유효성 검증을 어떻게 처리할지에 대한 전략을 학습한다. Application 계층에서 command를 작성하여 아직 구현되지 않은 Component의 contract만으로도 개발을 진행할 수 있음을 보여준다.

---

## Discovering Domain Messages

메시지 기반 시스템에 새로운 기능을 추가할 때, 첫 번째 단계는 해당 기능을 모델링하는 **domain message를 발견**하는 것이다.

- Event는 시스템의 상태를 정의하는 **명시적 통신 계약(explicit communication contract)** 이다
- 이 계약의 변경은 해당 계약을 사용하는 모든 Component에 전파되므로 처음부터 올바르게 정의하는 것이 중요하다
- 핵심 질문들:
  - 어떤 event가 상태를 저장하는가?
  - 어떤 command와 event가 그 event를 발생시키는가?
  - 어떤 entity가 이 메시지들로부터 나타나는가?

---

## Starting with the Business Process

코드를 작성하기 전에 비즈니스 프로세스를 이해해야 한다. 답해야 할 질문들:

- 시스템이 무엇을 하는가?
- 어떤 domain behavior를 포착하려 하는가?
- 사용자가 어떤 action을 취할 수 있는가?
- 종이나 스프레드시트로 구현한다면 어떤 단계들이 나타나는가?

등록 프로세스의 경우: 사용자가 등록을 시도하면 성공/실패 여부를 알려주고, 성공 시 기록하며 환영 이메일을 보낸다.

---

## Translating Business Processes into Events and Commands

### CRUD vs Domain Naming

| CRUD 방식 | Domain 방식 |
|-----------|------------|
| `UserCreated` | `Registered` |
| CREATE 쿼리 실행 | 사용자의 의도(intent)를 포착 |
| 개발자 관점 | 비즈니스/사용자 관점 |

- **Domain event는 CRUD action의 별칭이 아니라 사용자의 의도를 포착**하는 것이다
- `Registered`라는 이름을 사용하면 일반 사용자의 가입과 관리자의 계정 생성을 구분할 수 있다

### Identity Entity의 메시지 구조

| 유형 | 이름 | 설명 |
|------|------|------|
| Command | `Register` | 등록 요청 |
| Event | `Registered` | 등록 성공 |
| Event | `RegistrationRejected` | 등록 거부 |
| Command | `CloseAccount` | 계정 폐쇄 요청 |
| Event | `AccountClosed` | 계정 폐쇄 완료 |

---

## Fleshing Out the Identity Messages

메시지의 이름을 정했으면 어떤 데이터를 포함할지 결정해야 한다. Event가 곧 시스템의 상태이므로 email과 password hash를 포함해야 한다.

### Register Command 예시

```json
{
  "id": "928a73ca-2925-42c9-974a-467cd96e0a44",
  "type": "Register",
  "data": {
    "userId": "46aa6e66-adf9-40d0-bfe0-ae8ed5b70892",
    "email": "user@example.com",
    "passwordHash": "$2b$10$IrxFcWAxwRQGcNbK5Zr03.aLvgFGSUSdeUGw86ONXoz3Nm.PUlycS"
  }
}
```

### Registered Event 예시

```json
{
  "id": "10e23852-2725-4789-a4d2-4e0630b3a55d",
  "type": "Registered",
  "data": {
    "userId": "46aa6e66-adf9-40d0-bfe0-ae8ed5b70892",
    "email": "user@example.com",
    "passwordHash": "$2b$10$IrxFcWAxwRQGcNbK5Zr03.aLvgFGSUSdeUGw86ONXoz3Nm.PUlycS"
  }
}
```

### RegistrationRejected Event 예시

```json
{
  "id": "ea0835d6-a073-4a25-aca9-db75c4c153f4",
  "type": "RegistrationRejected",
  "data": {
    "userId": "46aa6e66-adf9-40d0-bfe0-ae8ed5b70892",
    "email": "not an email",
    "passwordHash": "$2b$10$IrxFcWAxwRQGcNbK5Zr03.aLvgFGSUSdeUGw86ONXoz3Nm.PUlycS",
    "reason": "email was not valid"
  }
}
```

### Stream 구조

- **Command stream**: `identity:command-{userId}` (예: `identity:command-2b9df609-276f-488a-bc88-3566c5f17dc6`)
- **Event stream**: `identity-{userId}` (예: `identity-46aa6e66-adf9-40d0-bfe0-ae8ed5b70892`)
- `identity`와 `identity:command`는 **별도의 category**이다

---

## Adding Registration to Our System

등록을 위해 필요한 것은 웹 인터페이스에서 등록 데이터를 수집하고 `Register` command를 발행하는 **register-users application**이다. Aggregation은 이 단계에서 필요하지 않다.

### Application이 처리하는 3가지 요청

1. 등록 폼 표시 (GET)
2. 등록 완료 확인 페이지 표시 (GET)
3. 등록 폼 제출 수신 (POST)

---

## Turning Registration Requests into Commands

등록 요청을 처리하는 handler는 세 가지 결과를 다룬다:

```javascript
function handleRegisterUser (req, res, next) {
  const attributes = {
    id: req.body.id,
    email: req.body.email,
    password: req.body.password
  }
  return actions
    .registerUser(req.context.traceId, attributes)
    .then(() => res.redirect(301, 'register/registration-complete'))
    .catch(ValidationError, err =>
      res.status(400).render(
        'register-users/templates/register',
        { userId: attributes.id, errors: err.errors }
      )
    )
    .catch(next)
}
```

| 결과 | 처리 방식 |
|------|----------|
| 성공 (Happy path) | `/register/registration-complete`로 redirect |
| 유효성 검증 실패 | `ValidationError`를 catch하여 400 상태와 함께 폼 재렌더링 |
| 알 수 없는 오류 | `next(err)` 호출하여 error-handling middleware에 위임 |

---

## Superficially Validating User Input

등록 action은 Promise chain으로 단계를 선언한다:

```javascript
function registerUser (traceId, attributes) {
  const context = { attributes, traceId, messageStore, queries }
  return Bluebird.resolve(context)
    .then(validate)
    .then(loadExistingIdentity)
    .then(ensureThereWasNoExistingIdentity)
    .then(hashPassword)
    .then(writeRegisterCommand)
}
```

### Superficial Validation (표면적 검증)

시스템 상태와 무관하게 검증 가능한 항목들을 검증한다. Daniel Whitaker가 말하는 "superficial validation"이다.

```javascript
const constraints = {
  email: {
    email: true,
    presence: true
  },
  password: {
    length: { minimum: 8 },
    presence: true
  }
}

function v (context) {
  const validationErrors = validate(context.attributes, constraints)
  if (validationErrors) {
    throw new ValidationError(validationErrors)
  }
  return context
}
```

- `validate.js` 라이브러리 사용
- 검증 실패 시 `{ email: ['Email is not a valid email'] }` 형태의 오류 반환
- **Component가 궁극적으로 비즈니스 규칙을 강제하지만**, Application 계층에서도 UX를 위해 검증한다
- password length는 Component가 hash만 보기 때문에 Application에서만 검증 가능

---

## Ensuring Uniqueness of Email Addresses

이메일 중복 검사는 두 단계로 분리된다 (I/O와 비즈니스 로직 분리):

### 1단계: View Data 조회

```javascript
function loadExistingIdentity (context) {
  return context.queries
    .byEmail(context.attributes.email)
    .then(existingIdentity => {
      context.existingIdentity = existingIdentity
      return context
    })
}
```

### 2단계: 존재 여부 확인

```javascript
function ensureThereWasNoExistingIdentity (context) {
  if (context.existingIdentity) {
    throw new ValidationError({ email: ['already taken'] })
  }
  return context
}
```

- Eric Elliot의 "Mocking Is a Code Smell" 원칙에 따라 **I/O와 branching 로직을 분리**
- `user_credentials` View Data 테이블에서 조회 (eventually consistent)

### 나머지 단계

**hashPassword**: bcrypt로 비밀번호 해싱

```javascript
function hashPassword (context) {
  return bcrypt
    .hash(context.attributes.password, SALT_ROUNDS)
    .then(passwordHash => {
      context.passwordHash = passwordHash
      return context
    })
}
```

**writeRegisterCommand**: command 작성 및 Message Store에 기록

```javascript
function writeRegisterCommand (context) {
  const userId = context.attributes.id
  const stream = `identity:command-${userId}`
  const command = {
    id: uuid(),
    type: 'Register',
    metadata: { traceId: context.traceId, userId },
    data: {
      userId,
      email: context.attributes.email,
      passwordHash: context.passwordHash
    }
  }
  return context.messageStore.write(stream, command)
}
```

---

## Validating Eventually Consistent Data

Eventually consistent한 View Data로 이메일 중복을 검증하는 결정에 대한 정당성을 다룬다.

### Race Condition 시나리오

1. User 1이 `tricksie@example.com`으로 등록 요청 -> 통과
2. User 2가 같은 이메일로 등록 요청 -> View Data에 아직 반영 안 됨 -> 통과
3. 두 개의 `Registered` event가 작성됨
4. Aggregator가 중복 이메일을 만남

### 핵심 판단 질문들

| 질문 | 이메일 중복의 경우 답변 |
|------|----------------------|
| 방지하려는 일이 얼마나 일어날 가능성이 있는가? | "eventually consistent"는 밀리초 단위 -> **가능성 매우 낮음** |
| 방지하려는 일이 실제로 일어나면 얼마나 나쁜가? | userId가 다르므로 구분 가능 -> **치명적이지 않음** |
| 누구의 제약(constraint)인가? | Application 계층의 관심사 vs Component의 근본 속성 |
| "올바름(correct)"이란 무엇인가? | 비즈니스 팀과 함께 결정해야 함 |

### 중복 발생 시 해결 방법

`user_credentials` 테이블에 `needs_to_change_email` 컬럼을 추가하여 두 번째 사용자에게 이메일 변경을 요청할 수 있다:

| id | email | password_hash | needs_to_change_email |
|----|-------|---------------|----------------------|
| uuid-1 | dup@example.com | $0meh4$h | false |
| uuid-2 | dup@example.com | $0me-other-h4$h | true |

### 만약 절대 중복이 불가해야 한다면?

그것은 identity의 **근본적 속성**이므로 **Component가 강제**해야 한다:
- Component가 Message Store의 모든 identity를 검사
- 시작 시 사용된 이메일 목록을 자체 DB 테이블에 캐싱
- 중복 발견 시 `RegistrationRejected` event 작성

### Trade-off

- Monolith에서는 이 문제가 더 단순하지만, 의식적으로 선택지를 추론할 기회를 잃는다
- 정직한 사용에서는 문제가 발생할 가능성이 낮고, 발생해도 해결 가능하다
- **비즈니스 팀과 함께 결정하는 것이 핵심**이다
