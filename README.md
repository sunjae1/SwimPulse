# SwimPulse

동네 공공 수영장의 선착순 접수 타이밍을 놓치지 않도록 이벤트 기반 알림을 제공하는 프로젝트입니다.

## Stack

- Backend: Java 21, Spring Boot 4, Spring Data JPA, MySQL, Redis, Firebase Cloud Messaging
- Frontend: Next.js 16, React 19, Tailwind CSS, FCM Web Push, Service Worker
- Architecture: registration event -> notification DB -> Redis queue -> worker -> FCM

## Local Run

백엔드와 Redis는 Docker Compose로 실행합니다. MySQL은 로컬 PC에 설치된 MySQL을 사용하고, 프론트는 Next.js dev server로 따로 실행합니다.

처음 실행 전 `backend/.env.example`을 기준으로 `backend/.env`를 만들고 Google OAuth/FCM 값을 채웁니다. 이 파일은 로컬 비밀값이므로 Git에 올리지 않습니다.

로컬 MySQL에는 아래 DB와 계정이 준비되어 있어야 합니다.

```sql
CREATE DATABASE swimpulse
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER 'swimpulse'@'localhost' IDENTIFIED BY 'swimpulse';

GRANT ALL PRIVILEGES ON swimpulse.* TO 'swimpulse'@'localhost';

FLUSH PRIVILEGES;
```

```powershell
docker compose up -d
```

```powershell
cd frontend
npm run dev
```

프론트 dev server, Docker 서비스, ngrok을 한 번에 띄우려면 루트에서 아래 스크립트를 실행합니다.

```powershell
.\scripts\dev.ps1
```

백엔드 이미지를 다시 빌드하지 않아도 되는 경우:

```powershell
.\scripts\dev.ps1 -SkipBuild
```

- Backend API: http://localhost:8080
- Frontend: http://localhost:3000
- MySQL: localhost:3306
- Redis: localhost:6379 -> container 6379

Google OAuth를 사용하려면 백엔드 실행 환경에 아래 값을 넣습니다.

```env
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
SWIMPULSE_JWT_SECRET=change-this-to-a-random-secret-at-least-32-characters
SWIMPULSE_OAUTH2_REDIRECT_URI=http://localhost:3000/login/oauth2/code/google
SWIMPULSE_AUTH_COOKIE_SECURE=false
SWIMPULSE_AUTH_SUCCESS_REDIRECT_URI=http://localhost:3000?login=success
NAVER_MAPS_CLIENT_ID=
NAVER_MAPS_CLIENT_SECRET=
NAVER_SEARCH_CLIENT_ID=
NAVER_SEARCH_CLIENT_SECRET=
OPENAI_API_KEY=
SWIMPULSE_OPENAI_MODEL=gpt-5.4-mini
SWIMPULSE_NOTICE_OCR_ENABLED=true
SWIMPULSE_NOTICE_OCR_COMMAND=tesseract
SWIMPULSE_NOTICE_OCR_LANGUAGES=kor+eng
SWIMPULSE_NOTICE_OCR_MAX_IMAGES=3
SWIMPULSE_NOTICE_OCR_TIMEOUT_MS=15000
```

Docker Compose에서는 이 값들이 `env_file: ./backend/.env`로 백엔드 컨테이너에 주입됩니다. 백엔드 컨테이너 내부에서 로컬 PC의 MySQL에 붙을 때는 `localhost`가 아니라 `host.docker.internal`을 사용합니다. Redis는 compose 서비스명이 `redis`이므로 그대로 `redis`를 사용합니다.

```env
SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/swimpulse?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
SPRING_DATASOURCE_USERNAME=swimpulse
SPRING_DATASOURCE_PASSWORD=swimpulse
SPRING_DATA_REDIS_HOST=redis
```

IntelliJ나 `gradlew bootRun`처럼 백엔드를 Docker 밖에서 직접 실행할 때는 기본값으로 `localhost:3306` MySQL과 `localhost:6379` Redis를 사용합니다.

Google Cloud Console의 OAuth Client에는 로컬 개발용 redirect URI로 아래 값을 등록합니다.

```text
http://localhost:3000/login/oauth2/code/google
```

## ngrok Static Domain

무료 ngrok static domain 1개는 프론트에 연결합니다. 브라우저는 같은 origin의 `/api/*`를 호출하고, Next.js rewrite가 Spring Boot `localhost:8080`으로 프록시합니다.

```powershell
cd frontend
npm run dev
```

```powershell
ngrok http --domain=unnamable-preset-contact.ngrok-free.dev 3000
```

접속 주소:

```text
https://unnamable-preset-contact.ngrok-free.dev
```

프론트 환경변수는 API base URL을 비워둡니다.

```env
NEXT_PUBLIC_API_BASE_URL=
BACKEND_INTERNAL_API_BASE_URL=http://localhost:8080
```

요청 흐름:

```text
Browser -> https://unnamable-preset-contact.ngrok-free.dev/api/pools
Next.js rewrite -> http://localhost:8080/api/pools
Spring Boot -> JSON response
```

ngrok으로 Google OAuth까지 테스트하려면 Google Cloud Console에 아래 origin과 redirect URI도 추가합니다.

```text
Authorized JavaScript origins:
https://unnamable-preset-contact.ngrok-free.dev

Authorized redirect URIs:
https://unnamable-preset-contact.ngrok-free.dev/login/oauth2/code/google
```

그리고 백엔드 실행 환경은 ngrok 주소로 맞춥니다.

```env
SWIMPULSE_OAUTH2_REDIRECT_URI=https://unnamable-preset-contact.ngrok-free.dev/login/oauth2/code/google
SWIMPULSE_AUTH_SUCCESS_REDIRECT_URI=https://unnamable-preset-contact.ngrok-free.dev?login=success
```

## MVP Features

- 수영장 목록 조회
- 현재 위치 기준 가까운 수영장 10개 조회
- Google OAuth 로그인
- JWT HttpOnly 쿠키 기반 로그인 유지
- 로그인 사용자 기준 수영장 구독/해지
- 수동 접수 이벤트 등록
- 이벤트 상태 전환: `UPCOMING -> OPEN -> CLOSED`
- 접수 시작 전 알림과 시작 시점 알림 생성
- 앱 내 알림 DB 저장
- Redis List 기반 알림 큐
- Worker 기반 FCM 전송
- FCM 서비스 계정 미설정 시 Mock FCM 전송
- 사용자별 다중 디바이스 웹 푸시 토큰 등록 UI와 Service Worker

## Key APIs

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/me` | 현재 로그인 사용자 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/pools` | 수영장 목록 |
| GET | `/api/pools/nearby?latitude=37.5665&longitude=126.9780&limit=10` | 가까운 수영장 목록 |
| GET | `/api/locations/search?query=화성남부국민체육센터&display=5` | 네이버 지역 검색 후보 |
| GET | `/api/locations/geocode?address=경기 화성시 우정읍 조암리 385` | 주소 좌표 변환 |
| POST | `/api/pools/from-location-candidate` | 검색 후보를 수영장으로 추가 |
| POST | `/api/pools/homepages/enrich?limit=50` | 네이버 지역 검색으로 홈페이지 URL 보강 |
| POST | `/api/pools/{poolId}/notices/scan` | 홈페이지 공지 후보 수집과 모집 기간 추출 |
| POST | `/api/pools/notice-sources/reverify?limit=20` | 공지 경로 후보를 최대 20개 시설씩 배치 재검증 |
| POST | `/api/pools/notices/periods/migrate?limit=50` | 기존 `registration_periods_json`을 기간 행으로 이관 |
| GET | `/api/pools/notices/periods/migration-status` | 기간 이관 및 이벤트 FK 연결 현황 |
| GET | `/api/events` | 접수 이벤트 목록 |
| POST | `/api/events` | 수동 접수 이벤트 등록 |
| GET | `/api/subscriptions` | 현재 사용자 구독 목록 |
| POST | `/api/subscriptions` | 구독 생성 |
| DELETE | `/api/subscriptions?poolId=1` | 구독 해지 |
| GET | `/api/notifications` | 현재 사용자 앱 내 알림 목록 |
| PATCH | `/api/notifications/{id}/read` | 알림 읽음 |
| POST | `/api/notifications/device-tokens` | FCM 토큰 등록 |
| POST | `/api/notifications/test` | 테스트 알림 큐잉 |

로그인 시작 경로:

```text
/oauth2/authorization/google
```

공지 경로 배치 재검증은 로그인한 브라우저 개발자 도구에서 다음처럼 호출할 수 있습니다.

```javascript
await fetch("/api/pools/notice-sources/reverify?limit=20", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

`pool_notice_sources.status` 의미:

- `CANDIDATE`: 발견됐지만 공지 경로인지 아직 검증되지 않음
- `VERIFIED`: 공지 게시판 구조 또는 등록 기간을 확인해 일반 스캔에서 우선 사용
- `INACTIVE`: 접근은 성공했지만 공지·등록 정보와 관련 없는 페이지
- `FAILED`: 연속 접근 실패가 기본 3회에 도달한 페이지

전체 경로 탐색은 pool별로 기본 24시간에 한 번만 수행하고, `FAILED` 경로 자체의 재검증은 기본 7일 후 허용됩니다.

상세 공지 분석 결과에는 `parser_version`과 `last_analyzed_at`이 저장됩니다. 현재 파서 버전으로 분석된 정상 결과는 모집 기간이 한 개뿐이어도 DB 결과를 재사용하며, 파서 버전을 올린 경우에만 기존 상세 페이지를 다시 분석합니다.

이미지 기반 상세 공지 처리:

- 상세 본문 HTML에서 모집 기간을 못 찾으면 `img[src]` 이미지를 대상으로 로컬 OCR를 한 번 더 시도합니다.
- OCR는 backend Docker 이미지 안의 `tesseract`를 사용합니다.
- 이번 단계에서는 OpenAI fallback을 사용하지 않습니다. `OPENAI_API_KEY`가 있어도 공지 추출 경로에서는 호출하지 않습니다.
- OCR 대상은 `img[src]`만이며 PDF, canvas, CSS background image는 범위 밖입니다.
- OCR 설정을 바꿨거나 Dockerfile이 바뀌었으면 backend 이미지를 재빌드해야 합니다.

모집 기간 저장 관계:

```text
pool_notices 1 : N notice_registration_periods
notice_registration_periods 1 : 0..1 registration_events
```

신규 크롤링은 기간 배열의 각 요소를 `notice_registration_periods` 한 행으로 저장합니다. 같은 기간은 재사용하고, 새 기간은 추가하며, 최신 분석에서 사라진 기간은 삭제하지 않고 `INACTIVE`로 변경합니다. `registration_periods_json`은 기존 데이터 호환을 위해 유지하지만 조회 API는 활성 기간 행을 우선 사용합니다.

기존 JSON 이관:

```javascript
await fetch("/api/pools/notices/periods/migrate?limit=50", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

이관 상태 확인:

```javascript
await fetch("/api/pools/notices/periods/migration-status", {
  credentials: "include",
}).then((response) => response.json());
```

구독 생성 요청:

```json
{
  "poolId": 16,
  "title": "신규 회원 - 7월 회원 모집",
  "registrationStartsAt": "2026-06-30T15:00:00Z",
  "registrationEndsAt": "2026-07-03T14:59:59Z",
  "noticeRegistrationPeriodId": 43
}
```

`noticeRegistrationPeriodId`가 있으면 backend는 활성 기간 행과 pool/시각을 다시 검증한 뒤 `registration_events.notice_registration_period_id`에 연결합니다. 수동 이벤트는 이 FK 없이 생성할 수 있습니다.

디바이스 토큰 등록 요청:

```json
{
  "deviceId": "browser-install-uuid",
  "fcmToken": "fcm-token"
}
```

## Manual Event Example

```json
{
  "poolId": 1,
  "title": "5월 신규회원 새벽반 접수",
  "registrationStartsAt": "2026-05-03T06:00:00Z",
  "registrationEndsAt": "2026-05-03T09:00:00Z"
}
```

## FCM Setup

로컬에서 `SWIMPULSE_FIREBASE_SERVICE_ACCOUNT_PATH`를 비워두면 Mock 전송기가 동작합니다. 이 경우 백엔드 로그에는 전송 성공처럼 남지만 실제 브라우저 웹 푸시는 오지 않습니다.

실제 FCM 웹 푸시를 받으려면 백엔드와 프론트 양쪽 설정이 모두 필요합니다.

1. Firebase Console에서 Web App을 만들고 Web Push certificate의 VAPID key를 발급합니다.
2. `frontend/.env.local`에 `NEXT_PUBLIC_FIREBASE_*` 값을 입력합니다.
3. Firebase Admin SDK 서비스 계정 JSON을 내려받습니다.
4. 백엔드 실행 환경에 `SWIMPULSE_FIREBASE_SERVICE_ACCOUNT_PATH`를 설정합니다.
5. 백엔드와 프론트 dev server를 모두 재시작합니다.

실제 FCM을 붙일 때는 Firebase Admin SDK 서비스 계정 JSON 경로를 설정합니다.

```powershell
$env:SWIMPULSE_FIREBASE_SERVICE_ACCOUNT_PATH="C:\secrets\firebase-service-account.json"
```

프론트 웹 푸시는 `frontend/.env.local`에 `NEXT_PUBLIC_FIREBASE_*` 값을 채우면 실제 FCM Web Push 토큰을 발급받습니다. 값을 채운 뒤 `npm run dev`를 재시작해야 Next.js가 환경변수를 다시 읽습니다.

앱 오른쪽 상단의 종 버튼은 웹 푸시 토큰을 등록합니다. 앱 내 알림 패널의 전송 아이콘은 테스트 알림을 Redis Queue에 넣습니다.
