# 060 모바일 한국 시간 표시 통일

작성일: 2026-07-20

## 문제

백엔드와 MySQL은 시간 데이터를 UTC `Instant`로 저장하고 API로 전달한다. 이 방식은 정상적인 서버 저장 방식이지만, 모바일 공용 날짜 포맷 함수가 표시 시간대를 지정하지 않아 Android 기기나 에뮬레이터의 시스템 시간대가 UTC이면 마이페이지 구독 시간이 UTC 그대로 표시됐다.

예를 들어 DB/API의 다음 시각은 같은 순간을 뜻한다.

```text
UTC: 2026-07-05 15:01
KST: 2026-07-06 00:01
```

기존 모바일 화면은 기기 시간대에 의존했기 때문에 UTC 환경에서는 `07.05 오후 03:01`로 보일 수 있었다.

## 수정

`mobile/src/utils/date.ts`의 공용 `formatDateTime()`에 `timeZone: Asia/Seoul`을 명시했다.

이 함수가 사용되는 다음 화면이 모두 한국 시간으로 표시된다.

- 마이페이지 내 구독 시작/종료 시각
- 구독 생성 시각
- 최근 알림 도착 시각
- 알림 상세의 실제 접수 시작 시각
- 공지 확인 결과와 접수 이벤트 기간

기간 수정 달력과 문자열 변환에서도 같은 `SEOUL_TIME_ZONE` 상수를 사용한다.

## 저장과 표시 원칙

```text
DB/API 저장 및 전송: UTC Instant
모바일 사용자 표시: Asia/Seoul
기간 수정 입력: 한국 시간으로 선택
기간 수정 API 전송: UTC ISO-8601 Instant
```

DB 값을 한국 시간으로 바꿔 저장하지는 않는다. UTC 저장을 유지하고 화면 경계에서만 한국 시간으로 변환해야 서버, 웹, 모바일 사이에서 같은 순간을 안정적으로 공유할 수 있다.

## 테스트

`mobile/src/utils/date.test.ts`에 다음 변환을 검증하는 테스트를 추가했다.

```text
2026-07-05T15:01:00Z -> 07. 06. 오전 12:01
2026-07-06 00:01 KST -> 2026-07-05T15:01:00.000Z
UTC Instant -> KST 입력값 왕복 변환
```

실행 명령:

```powershell
cd mobile
npx tsc --noEmit
npm run lint
npm test -- --runInBand
```

실행 결과:

| 검증 | 결과 |
|---|---|
| TypeScript `npx tsc --noEmit` | 통과 |
| ESLint `npm run lint` | 통과 |
| Jest | 2 suites, 4 tests 전체 통과 |
