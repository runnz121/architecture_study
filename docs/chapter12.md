# Chapter 12: Deploying Components

## 챕터 요약

이 챕터에서는 마이크로서비스 기반 시스템인 Video Tutorials를 Heroku PaaS에 배포하는 전체 과정을 다룬다. 단일 코드베이스로 배포한 후, 시스템을 front-end와 back-end로 분리하여 distributed system으로 전환하는 방법과 데이터베이스 분리 전략까지 설명한다. 핵심 메시지는 autonomous component로 올바르게 설계된 시스템은 배포 전략을 유연하게 변경할 수 있다는 것이다.

---

## Creating the Heroku "App"

- Heroku는 **PaaS(Platform-as-a-Service)** 로, AWS/GCP 등과 달리 인프라 관리 없이 코드를 쉽게 배포할 수 있다
- Dashboard에서 **"New" → "Create New App"** 선택하여 앱 생성
- 프로젝트 코드를 Git repository로 만들고, Heroku의 **"Existing Git repository"** 안내에 따라 heroku remote를 추가

**배포 기본 계획:**

1. Heroku 계정 가입
2. 새로운 "app" 생성
3. "app" 설정(환경변수, DB)
4. 코드 배포

---

## Configuring the "App"

### Database 설정

- **Resources** 탭에서 "postgres" 검색 → **Heroku Postgres** 추가
- **중요:** Postgres를 **두 번** 추가해야 한다
  - 하나는 **View Data** 용
  - 다른 하나는 **Message DB** 용

### 환경변수 설정

**Settings** 탭 → **"Reveal Config Vars"** 에서 다음 환경변수를 설정:

| KEY | VALUE |
|-----|-------|
| `APP_NAME` | Video Tutorials |
| `COOKIE_SECRET` | 랜덤 문자열 (아래 스크립트로 생성) |
| `EMAIL_DIRECTORY` | tmp/email |
| `MESSAGE_STORE_CONNECTION_STRING` | 두 번째 DB의 URL 값 복사 |
| `NODE_ENV` | production |
| `SYSTEM_SENDER_EMAIL_ADDRESS` | no-reply@example.com |

> `PORT`는 Heroku가 런타임에 자동으로 제공하므로 설정하지 않는다.

**COOKIE_SECRET 생성:**

```bash
$ npm run generate-cookie-secret
> microservices-book@1.0.0 generate-cookie-secret
> node src/bin/generate-cookie-secret
eff41191a4c1f51742496bd07ec9ca5407a94e0e98fb995f920d8690828c7aa8
```

---

## Installing Message DB

두 번째 Heroku Postgres 인스턴스에 Message DB를 설치해야 한다.

- `psql` 클라이언트가 로컬에 설치되어 있어야 함
- connection string 형식: `postgres://PGUSER:PGPASSWORD@PGHOST:PGPORT/PGDATABASE`

**설치 스크립트 실행:**

```bash
PGUSER=mqlztugqizopuz \
PGPASSWORD=fd2b16b923521a2312ee9a63a8fe7c37d869fdde860abdbf1c0e172de9a7820f \
PGHOST=ec2-174-129-253-175.compute-1.amazonaws.com \
PGPORT=5432 \
PGDATABASE=d2a0cj6ir00kc2 node script/install-message-store-in-heroku.js
```

> 출력에서 `NOTICE` 메시지가 나타나는 것은 정상이다 (기존 타입/인덱스가 없어서 skip 되는 것).

---

## Deploying the System

배포는 간단하다:

```bash
git push heroku master
```

- 배포 후 `https://<yourappname>.herokuapp.com` 으로 접속하여 확인
- 이것으로 **마이크로서비스 기반 시스템이 실제 인터넷에 배포**된 것이다

---

## Distributing the System

### 분산 시스템으로 전환해야 하는 이유

Tyler Treat의 원칙: **"분산 시스템의 첫 번째 규칙은 관찰 가능한 이유가 있기 전까지 분산하지 않는 것이다."**

**분산하면 안 되는 이유들 (잘못된 이유):**

- Kubernetes를 사용하고 싶어서
- 마이크로서비스는 분산되어야 한다고 읽어서
- Docker container에 넣을 줄 알아서

**분산해야 하는 올바른 이유:** 가용성(availability) 문제가 관찰될 때

### 분산 구조

| 항목 | 단일 배포(Monolith) | 분산 배포(Distributed) |
|------|---------------------|----------------------|
| 코드베이스 | 단일 codebase, 단일 process | front-end / back-end 분리 |
| config.js | 모든 component 포함 | 각각의 config.js에 해당 부분만 포함 |
| 장애 영향 | 하나가 crash하면 전체 down | 한쪽이 down되어도 다른 쪽은 정상 작동 |
| 배포 | 한 번에 전체 배포 | 독립적으로 배포 가능 |

### front-end의 index.js (분산 버전)

```javascript
const createExpressApp = require('./app/express')
const createConfig = require('./config')
const env = require('./env')
const config = createConfig({ env })
const app = createExpressApp({ config, env })

function start () {
  app.listen(env.port, signalAppStart)
}

function signalAppStart () {
  console.log(`${env.appName} started`)
  console.table([['Port', env.port], ['Environment', env.env]])
}

module.exports = {
  app,
  config,
  start
}
```

> `start` 함수에서 aggregator와 component 관련 코드가 제거되었다.

### Heroku 분산 배포 시 주의사항

- front-end와 back-end 각각 별도의 Heroku "app" 생성
- Postgres Add-on은 back-end에 연결 (Aggregator가 테이블을 소유하므로)
- front-end의 migration 테이블명을 분리:

```javascript
const knexClient = createKnexClient({
  connectionString: env.databaseUrl,
  migrationsTableName: 'front_end_migrations'
})
```

- back-end는 web server가 없으므로 반드시 다음 명령 실행:

```bash
heroku scale web=0 worker=1
```

> worker dyno는 sleep하지 않으므로 free tier 시간을 소모한다는 점에 주의.

### 핵심 포인트

- 분산 시 **실제 component 코드는 변경할 필요가 없었다** (app, aggregators, components 폴더 내부 수정 없음)
- 변경은 오직 **outermost layer** (config.js, index.js)에서만 발생
- 이것이 **autonomy(자율성)** 의 힘이다

---

## Deploying Databases

- 현재 프로젝트는 **단일 물리 데이터베이스**를 사용하지만, 각 View Data는 단일 Aggregator만 쓰기를 수행한다
- 코드베이스 분리와 마찬가지로, 테이블을 **별도의 데이터베이스로 분리**할 수 있다
- 가장 자연스러운 분리: **Message Store를 별도 데이터베이스(또는 서버)로** 이동
- 분리 결정은 **가용성(availability)** 요구사항에 따라 결정해야 한다
- **separation of concerns**를 올바르게 지키면, 테이블의 물리적 위치는 독립적인 관심사가 된다

---

## 자율성(Autonomy) 검증 실습

분산 배포된 시스템에서 autonomy를 직접 확인하는 방법:

1. **front-end만 실행** → 사용자 등록, 비디오 조회 버튼 클릭 → 조회수 증가하지 않고 로그인 불가
2. **front-end 중지, back-end만 실행** → back-end가 밀린 메시지들을 처리
3. **front-end 재시작** → 모든 queued operation이 완료된 것을 확인

> Autonomous component는 한쪽이 재배포되어도 전체 시스템이 unavailable 상태가 되지 않는다.
