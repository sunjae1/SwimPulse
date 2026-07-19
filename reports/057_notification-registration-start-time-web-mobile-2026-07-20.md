# 057 알림 접수 시작 시각 표시 개선

작성일: 2026-07-20

## 문제

접수 시작 10분 전에 발송되는 `REGISTRATION_REMINDER` 알림을 열면 웹과 모바일 모달에 알림 생성 시각만 표시됐다.

예를 들어 실제 접수 시작이 `12:00`이고 리마인더가 `11:50`에 생성되면 사용자는 `도착 11:50`만 확인할 수 있었다. 알림이 언제 왔는지는 알 수 있지만, 준비해야 하는 실제 접수 시작 시각은 바로 파악하기 어려웠다.

## 변경 사항

### 백엔드 알림 계약

`NotificationResponse`에 다음 필드를 추가했다.

```json
{
  "registrationStartsAt": "2026-07-20T03:00:00Z"
}
```

FCM data payload에도 같은 값을 포함한다. 모바일 앱이 알림을 눌렀을 때 API 상세 조회가 늦거나 실패하더라도 FCM payload의 실제 시작 시각을 사용할 수 있다.

### FCM 알림 문구

리마인더와 접수 시작 알림 본문을 알림 생성 시각이 아니라 이벤트의 `registration_events.registration_starts_at` 기준으로 생성한다.

```text
변경 전: 테스트 수영장 7월 모집 접수가 곧 시작됩니다.
변경 후: 테스트 수영장 7월 모집 접수가 7월 20일 오후 12시에 시작합니다.
```

서버에서는 `Asia/Seoul` 시간대로 변환하며, 정각이면 `00분`을 생략하고 분이 있으면 `오후 12시 30분`처럼 표시한다.

### 웹

- 대시보드 푸시 상세 모달에 `접수 시작` 영역을 강조 표시한다.
- 마이페이지 알림 상세 모달에도 동일하게 표시한다.
- 마이페이지 최근 알림 목록에도 실제 접수 시작 시각을 표시한다.
- `알림 도착`은 보조 정보로 남겨 시작 시각과 발송 시각을 구분한다.

### 모바일

- API 알림 타입에 `registrationStartsAt`을 추가했다.
- FCM fallback 메시지에서도 `registrationStartsAt`을 읽는다.
- 푸시 상세 모달에 실제 접수 시작 시각을 강조한다.
- 마이페이지 최근 알림 목록에도 실제 접수 시작 시각을 표시한다.
- `알림 도착`은 별도 보조 정보로 표시한다.

## 최종 UX

```text
수영장 접수 시작이 곧 다가옵니다
테스트 수영장 7월 모집 접수가 7월 20일 오후 12시에 시작합니다.

접수 시작
07. 20. 오후 12:00

알림 도착 07. 20. 오전 11:50
```

사용자는 리마인더가 도착한 시각과 실제 접수 시작 시각을 혼동하지 않고 한 화면에서 비교할 수 있다.

## 호환성

- 배포 후 새로 생성되는 알림은 FCM 제목·본문과 data payload 모두 실제 시작 시각을 포함한다.
- 과거에 이미 DB에 생성된 알림의 저장 메시지는 바뀌지 않는다.
- 과거 알림도 API 응답은 연결된 이벤트에서 `registrationStartsAt`을 조회하므로 웹·모바일 상세 화면에서는 실제 시작 시각을 표시할 수 있다.

## 테스트

다음 검증을 모두 통과했다.

```text
backend: ./gradlew.bat test --tests com.swimpulse.notification.NotificationSourceReviewTests
backend: ./gradlew.bat test
frontend: npm run lint
frontend: npm run build
mobile: npm run lint
mobile: npx tsc --noEmit
mobile: npm test -- --runInBand
```

추가한 백엔드 테스트는 실제 시작이 `2026-07-20 12:00 KST`일 때 리마인더 본문이 `7월 20일 오후 12시에 시작합니다.`를 포함하고, API 응답의 `registrationStartsAt`이 원래 이벤트 시각과 일치하는지 검증한다.

## 운영 반영

백엔드와 프론트 변경을 함께 배포해야 한다. 모바일은 새 release APK를 빌드한 뒤 기존 앱에 `adb install -r`로 덮어써야 새 모달 UI와 FCM fallback 처리가 반영된다.
