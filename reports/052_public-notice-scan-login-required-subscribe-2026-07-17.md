# 052 공지 확인 Public 전환 및 구독 로그인 가드

작성일: 2026-07-17

## 목적

기존에는 비로그인 사용자가 수영장 목록에서 `공지 확인`을 누를 수 없었다.

하지만 SwimPulse의 핵심 가치는 공공기관 공지를 자동으로 확인하고 모집 기간을 추출해 보여주는 것이다. 첫 방문자가 이 기능을 보기 전에 로그인으로 막히면 서비스 가치를 확인하기 어렵다.

이번 변경은 다음 UX로 전환한다.

```text
공지 확인
-> 비로그인도 가능
-> 모집 기간 추출 결과까지 표시

이 기간 구독
-> 로그인 필요
-> "로그인이 필요한 작업입니다." 안내
-> Google 로그인 버튼 제공
```

## 변경 내용

### Backend

수정 파일:

```text
backend/src/main/java/com/swimpulse/config/SecurityConfig.java
```

변경:

```java
requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
requestMatchers(HttpMethod.POST, "/api/pools/*/notices/scan").permitAll()
```

결과:

- `/api/pools/{poolId}/notices/scan`은 비로그인 사용자도 호출할 수 있다.
- 브라우저가 cross-origin `POST` 전에 보내는 CORS preflight `OPTIONS`도 인증 없이 통과한다.
- `/api/subscriptions/**`, `/api/notifications/**`, `/api/me` 등 개인 데이터 API는 계속 인증이 필요하다.

주의:

프론트 fetch는 `Content-Type: application/json`과 credentials를 포함하므로, 운영 Vercel 도메인에서 `api.sunjae.link`로 POST할 때 브라우저가 먼저 `OPTIONS` preflight를 보낼 수 있다.

따라서 POST 경로만 public으로 열어도 preflight가 401이면 프론트에서는 `로그인이 필요합니다.`로 보일 수 있다. 이 때문에 `OPTIONS /**`도 명시적으로 허용했다.

### Web

수정 파일:

```text
frontend/src/components/DashboardClient.tsx
```

변경:

- `공지 확인` 버튼에서 로그인 여부 disabled 조건을 제거했다.
- `알림 구독` 버튼도 공지 확인 결과를 볼 수 있도록 로그인 없이 열 수 있게 했다.
- 실제 `이 기간 구독` 버튼을 눌렀을 때 로그인하지 않은 상태면 로그인 필요 모달을 띄운다.
- 로그인 필요 모달에는 다음 메시지를 표시한다.

```text
로그인이 필요한 작업입니다.
모집 기간 알림을 받으려면 Google 로그인이 필요합니다.
```

모달 액션:

```text
닫기
Google 로그인
```

### Mobile

수정 파일:

```text
mobile/App.tsx
```

변경:

- `공지 확인` 실행 전 로그인 체크를 제거했다.
- 공지 확인 결과 모달은 비로그인 사용자도 볼 수 있다.
- 모집 기간 `구독`을 누를 때 로그인하지 않은 상태면 native `Alert`로 안내한다.

안내:

```text
로그인이 필요한 작업입니다.
모집 기간 알림을 받으려면 Google 로그인이 필요합니다.
```

액션:

```text
닫기
Google 로그인
```

## 판단

### 왜 공지 확인은 public인가

공지 확인은 서비스 탐색 단계다.

```text
사용자 검색
-> 수영장 선택
-> 공지 확인
-> 모집 기간 추출 결과 확인
```

이 흐름까지는 로그인 없이 가능해야 사용자가 SwimPulse의 가치를 확인할 수 있다.

### 왜 구독은 로그인 필수인가

구독은 사용자별 상태를 만든다.

```text
subscriptions row 생성
registration_event 연결
notifications 생성 대상이 됨
FCM device token과 사용자 계정 연결
```

따라서 구독부터는 로그인된 사용자 기준으로 처리하는 것이 맞다.

## 검증 결과

실행:

```text
backend ./gradlew.bat test
frontend npm run lint
frontend npm run build
mobile npm run lint
mobile npx tsc --noEmit
robocopy 원본 mobile -> C:\sp\mobile
C:\sp\mobile\android .\gradlew.bat assembleRelease
```

결과:

```text
모두 통과
```

생성 APK:

```text
C:\sp\mobile\android\app\build\outputs\apk\release\app-release.apk
```

## 후속 고려

공지 확인을 public으로 열면 OCR/크롤링 요청이 비로그인 사용자에게도 열리므로, 트래픽이 늘면 다음 보강을 고려한다.

```text
비로그인 공지 확인 rate limit
IP 기준 scan 요청 제한
poolId 기준 single-flight 유지
scan result cache TTL 활용
관리자 페이지에서 scan 실패/지연 지표 확인
```

현재는 Redis single-flight lock, scan result cache, OCR queue가 이미 있어 초기 서비스 탐색 UX를 우선하는 것이 더 적합하다.
