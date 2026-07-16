# 046 Dashboard 사용자 에러 문구와 관리자 기능 노출 정리

작성일: 2026-07-16

## 목적

운영 배포 후 사용자가 테스트 알림을 누르면 Spring 기본 에러 응답 JSON이 그대로 화면에 표시되는 문제가 있었다.

예시:

```json
{"timestamp":"...","status":400,"error":"Bad Request","message":"Subscribe to a registration period before sending a test notification.","path":"/api/notifications/test"}
```

이 형식은 개발자에게는 디버깅 정보가 되지만, 일반 사용자에게는 상태 코드, API path, 영어 메시지가 그대로 노출되어 사용성이 좋지 않다.

또한 대시보드의 `접수 이벤트`, `관리자` 네비게이션은 일반 사용자에게 필요 없는 운영 기능이므로 role 기준으로 숨기도록 정리했다. `수동 이벤트 등록`은 사용자가 직접 알림 테스트용 이벤트를 만들 수 있어야 하므로 로그인 사용자에게는 계속 노출한다.

## 변경 내용

### 1. API 에러 메시지 정제

대상:

- `frontend/src/lib/api.ts`
- `mobile/src/api/client.ts`

변경:

- API 실패 시 응답 body를 그대로 `Error.message`로 던지지 않는다.
- Spring 기본 에러 JSON이면 `message` 필드만 추출한다.
- 알려진 영어/개발자용 메시지는 사용자용 한글 문구로 치환한다.
- `401`, `403`, `404`, `5xx`는 공통 사용자 문구로 치환한다.

대표 문구:

```text
테스트 알림을 보내기 전에 원하는 수영장 공지를 확인하고 모집 기간을 구독해주세요.
```

이제 프론트 toast/notice에는 `timestamp`, `status`, `error`, `path` 같은 개발자 정보가 노출되지 않는다.

### 2. 테스트 알림 서버 메시지 한글화

대상:

- `backend/src/main/java/com/swimpulse/notification/NotificationService.java`

변경 전:

```text
Subscribe to a registration period before sending a test notification.
```

변경 후:

```text
테스트 알림을 보내기 전에 원하는 수영장 공지를 확인하고 모집 기간을 구독해주세요.
```

프론트에서 메시지를 정제하더라도, 백엔드 비즈니스 예외 자체도 사용자 친화적인 문구를 반환하도록 맞췄다.

### 3. 관리자 네비게이션 role 기반 노출

대상:

- `frontend/src/components/AppNavigation.tsx`
- `frontend/src/components/DashboardClient.tsx`
- `frontend/src/components/MyPageClient.tsx`
- `frontend/src/components/AdminDashboardClient.tsx`

변경:

- `AppNavigation`이 `userRole` 또는 `showAdmin`을 받도록 수정했다.
- `role=ADMIN`일 때만 `관리자` 메뉴를 보여준다.
- 관리자 페이지 안에서는 `showAdmin`을 넘겨 관리자 메뉴를 유지한다.
- `role=USER` 또는 비로그인 상태에서는 `관리자` 버튼이 숨겨진다.

### 4. 접수 이벤트 관리자 노출, 수동 이벤트 로그인 사용자 노출

대상:

- `frontend/src/components/DashboardClient.tsx`

변경:

- `접수 이벤트` 목록은 `user.role === "ADMIN"`일 때만 렌더링한다.
- `수동 이벤트 등록` 폼은 로그인 사용자가 직접 이벤트를 만들 수 있도록 계속 렌더링한다.
- 비로그인 상태에서는 등록 버튼이 비활성화된다.

일반 사용자 대시보드에서는 전체 접수 이벤트 목록은 숨기되, 필요한 경우 직접 접수 기간을 등록할 수 있는 흐름은 유지했다.

### 5. 수동 이벤트 생성 API는 로그인 사용자 허용

대상:

- `backend/src/main/java/com/swimpulse/config/SecurityConfig.java`

변경:

`POST /api/events`를 별도 `ADMIN` rule로 묶지 않고, 기존 `.requestMatchers("/api/**").authenticated()` rule을 타게 유지한다.

즉, 비로그인 사용자는 호출할 수 없고, 로그인 사용자는 수동 이벤트를 등록할 수 있다.

## 검증 결과

통과:

```text
frontend npm run lint
frontend npm run build
mobile npm run lint
mobile npx tsc --noEmit
backend ./gradlew.bat test --tests com.swimpulse.SwimPulseApplicationTests --tests com.swimpulse.notification.RegisterDeviceTokenRequestTests
```

전체 백엔드 테스트:

```text
./gradlew.bat test
```

결과:

- 70개 중 67개 통과
- `NoticeCrawlerServiceTests` 3개 실패

실패 테스트:

```text
detailNoticeCandidatesSupportFnViewOnclickLinks
imageOcrRetryNormalizesDuplicateRangeFragmentsAndSuppressesMonthlyFalsePositive
detailNoticeCandidatesPreferRealContentAreaOverGenericPopupContent
```

이번 변경 파일은 인증/알림/프론트 렌더링 영역이고, 실패한 테스트는 공지 크롤러 파싱 기대값 관련 테스트다. 변경 인접 테스트와 프론트/모바일 검증은 통과했다.

## 운영 반영 시 확인할 것

1. Vercel 프론트 재배포 후 일반 사용자 계정에서 `관리자` 메뉴가 보이지 않는지 확인한다.
2. 일반 사용자 대시보드에서 `접수 이벤트` 섹션은 사라지고, `수동 이벤트 등록` 섹션은 보이는지 확인한다.
3. 테스트 알림을 구독 없이 눌렀을 때 한글 안내 문구만 표시되는지 확인한다.
4. 백엔드 컨테이너 재배포 후 비로그인 사용자는 `POST /api/events` 호출 시 `401`, 로그인 사용자는 정상 등록되는지 확인한다.
