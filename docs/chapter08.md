# Chapter 8: Authenticating Users

## 챕터 요약

이 챕터에서는 등록된 사용자를 인증하는 과정을 다룬다. identity Component가 생성한 Registered 이벤트를 Aggregator로 View Data에 저장하고, 이를 활용하여 로그인/로그아웃 기능을 구현한다. 인증은 본질적으로 View Data에 대한 query이므로 별도의 "auth service"가 필요하지 않으며, Component가 아닌 Application에서 처리된다.

---

## Aggregating Registered Events

identity Component에서 발행한 `Registered` 이벤트를 구독하여 `user_credentials` 테이블에 인증용 View Data를 생성하는 Aggregator를 작성한다.

### Aggregator 구조

```javascript
// src/aggregators/user-credentials.js
function build ({ db, messageStore }) {
  const queries = createQueries({ db })
  const handlers = createHandlers({ queries })
  const subscription = messageStore.createSubscription({
    streamName: 'identity',
    handlers,
    subscriberId: 'aggregators:user-credentials'
  })

  function start () {
    subscription.start()
  }

  return { handlers, queries, start }
}
```

- `identity` category stream을 구독하여 Registered 이벤트가 있는 곳에서 데이터를 가져온다.

### Handler와 Query

```javascript
// Handler
function createHandlers ({ queries }) {
  return {
    Registered: event =>
      queries.createUserCredential(
        event.data.userId,
        event.data.email,
        event.data.passwordHash
      )
  }
}
```

```sql
-- Idempotent INSERT query
INSERT INTO
  user_credentials (id, email, password_hash)
VALUES
  (:id, :email, :passwordHash)
ON CONFLICT DO NOTHING
```

`ON CONFLICT DO NOTHING` 절을 사용하여 동일한 Registered 이벤트를 중복 처리해도 **멱등성(idempotency)**이 보장된다. `id` 컬럼이 primary key이므로 같은 이벤트를 두 번 처리하면 conflict가 발생하고, 아무 작업도 수행하지 않는다.

---

## Discovering the Authentication Events and Commands

### 인증의 본질

| 항목 | 설명 |
|------|------|
| 인증의 성격 | View Data에 대한 **query** (동기적 처리) |
| Component 필요 여부 | 불필요 — autonomous service는 query에 응답하지 않음 |
| Command 필요 여부 | 불필요 — 인증은 동기적이므로 Command 패턴이 필요 없음 |
| "Auth Service"라는 용어 | 부적절 — 인증은 query이지 service가 아님 |

### 인증 관련 Domain Events

도메인 이벤트를 기록하면 로그인 빈도, 실패한 시도 등을 추적할 수 있다.

| Event | Stream | 용도 |
|-------|--------|------|
| `UserLoggedIn` | `authentication-{userId}` | 성공적인 로그인 기록 |
| `UserLoginFailed` | `authentication-{userId}` | 실패한 로그인 기록 (잘못된 비밀번호 등) |

```json
// UserLoggedIn 예시
{
  "id": "40f969ec-d6ea-466e-beb5-d37543db162e",
  "type": "UserLoggedIn",
  "data": {
    "userId": "e90647af-8103-4fe9-ae1f-4766103cca54"
  }
}
```

```json
// UserLoginFailed 예시
{
  "id": "a314d64f-6e4f-4a99-bfd4-5cf5afc52846",
  "type": "UserLoginFailed",
  "data": {
    "userId": "e90647af-8103-4fe9-ae1f-4766103cca54",
    "reason": "Incorrect password"
  }
}
```

> 존재하지 않는 이메일로 로그인 시도한 경우에는 이벤트를 기록하지 않는다. 이는 도메인 정보가 아닌 시스템 건강 정보이므로 서버 로그에서 처리한다.

---

## Letting Users in the Door

authenticate Application은 세 가지 기능을 수행한다:

1. 로그인 폼 표시 (`GET /log-in`)
2. 로그아웃 처리 (`GET /log-out`)
3. 로그인 요청 처리 (`POST /log-in`)

### Application 구조

```javascript
// src/app/authenticate/index.js
function build ({ db, messageStore }) {
  const queries = createQueries({ db })
  const actions = createActions({ messageStore, queries })
  const handlers = createHandlers({ actions })
  const router = express.Router()

  router.route('/log-in')
    .get(handlers.handleShowLoginForm)
    .post(bodyParser.urlencoded({ extended: false }), handlers.handleAuthenticate)

  router.route('/log-out').get(handlers.handleLogOut)

  return { actions, handlers, queries, router }
}
```

### 로그아웃

```javascript
function handleLogOut (req, res) {
  req.session = null
  res.redirect('/')
}
```

`cookie-session` 미들웨어를 사용하여 세션 쿠키를 관리하며, 로그아웃 시 세션을 `null`로 설정한다.

### 로그인 Action — Promise Chain

```javascript
function authenticate (traceId, email, password) {
  const context = { traceId, email, messageStore, password, queries }

  return Bluebird.resolve(context)
    .then(loadUserCredential)       // 1. email로 credential 조회
    .then(ensureUserCredentialFound) // 2. credential 존재 확인
    .then(validatePassword)         // 3. bcrypt로 비밀번호 검증
    .then(writeLoggedInEvent)       // 4. UserLoggedIn 이벤트 기록
    .catch(NotFoundError, () => handleCredentialNotFound(context))
    .catch(CredentialsMismatchError, () => handleCredentialsMismatch(context))
}
```

### 각 단계별 처리

| 단계 | 함수 | 역할 |
|------|------|------|
| 1 | `loadUserCredential` | email로 `user_credentials` 테이블 조회, `context.userCredential`에 저장 |
| 2 | `ensureUserCredentialFound` | credential이 없으면 `NotFoundError` throw |
| 3 | `validatePassword` | `bcrypt.compare`로 비밀번호 비교, 불일치 시 `CredentialMismatchError` throw |
| 4 | `writeLoggedInEvent` | `authentication-{userId}` stream에 `UserLoggedIn` 이벤트 기록 |

### 에러 처리 전략

| 에러 | 처리 함수 | 동작 |
|------|-----------|------|
| `NotFoundError` | `handleCredentialNotFound` | `AuthenticationError`로 변환하여 throw |
| `CredentialsMismatchError` | `handleCredentialsMismatch` | `UserLoginFailed` 이벤트 기록 후 `AuthenticationError` throw |

두 경우 모두 동일한 `AuthenticationError`로 변환하는 이유: 사용자에게 이메일이 틀렸는지 비밀번호가 틀렸는지 구분하여 알려주면, 공격자가 시스템에 존재하는 이메일을 탐색할 수 있기 때문이다.

```javascript
// handleCredentialsMismatch — UserLoginFailed 이벤트 기록
const event = {
  id: uuid(),
  type: 'UserLoginFailed',
  metadata: { traceId: context.traceId, userId: null },
  data: {
    userId: context.userCredential.id,
    reason: 'Incorrect password'
  }
}
const streamName = `authentication-${context.userCredential.id}`
return context.messageStore.write(streamName, event).then(() => {
  throw new AuthenticationError()
})
```

### 시스템 연결

```javascript
// src/app/express/mount-routes.js
app.use('/auth', config.authenticateApp.router)
```

---

## Using Third-Party Authentication

실제 운영 시스템에서는 Facebook, Google, Auth0 등 third-party identity management 사용이 권장된다. Third-party 인증 도입 시 고려할 사항:

| 고려 사항 | 설명 |
|-----------|------|
| 동작 방식 | 사용자 브라우저가 provider로 redirect 후, 특수 정보를 URL에 포함하여 서버로 돌아옴 |
| 이벤트 구분 | email/password 등록과 third-party 등록을 다른 이벤트로 구분할 것인지 결정 필요 |
| 로그인 이벤트 | third-party 로그인과 password 로그인을 다른 이벤트로 구분할 것인지 결정 필요 |

---

## 핵심 정리

| 개념 | 설명 |
|------|------|
| 인증은 query | Component가 아닌 Application에서 View Data를 조회하여 처리 |
| Aggregator 역할 | Registered 이벤트를 `user_credentials` 테이블로 변환 |
| 멱등성 보장 | `ON CONFLICT DO NOTHING`으로 중복 처리 방지 |
| 에러 정규화 | 구체적 에러를 generic `AuthenticationError`로 변환하여 보안 강화 |
| 이벤트 기록 | 성공/실패 모두 `authentication-{userId}` stream에 기록하여 향후 계정 잠금 등에 활용 가능 |
| cookie-session | Express 미들웨어로 세션 쿠키 관리, 서명(signing)으로 진위 확인 |
