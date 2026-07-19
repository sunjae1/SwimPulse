# 059 웹·모바일 구독 기간 동기화 및 모바일 상태 필터 개선

작성일: 2026-07-20

## 문제 1. 모바일에서 수정한 기간이 열린 웹 화면에 반영되지 않음

같은 계정으로 모바일에서 구독 기간을 수정하면 모바일 마이페이지에는 변경된 기간이 표시됐지만, 이미 열려 있던 웹 마이페이지에는 이전 기간이 계속 표시됐다.

## 원인 분석

백엔드와 DB 갱신은 정상 동작하고 있었다.

```text
모바일 PATCH /api/subscriptions/{subscriptionId}
-> RegistrationEventResolver가 수정 기간 이벤트 조회/생성
-> subscriptions.event_id를 새 event_id로 변경
-> 수정된 SubscriptionResponse 반환
-> 모바일 getMyPage 재조회
```

웹 API 클라이언트도 `cache: no-store`로 `/api/my-page`를 요청하므로 HTTP 캐시 문제는 아니었다.

실제 원인은 웹 `MyPageClient`가 컴포넌트 최초 진입 또는 웹 내부 수정 후 `reloadKey`가 변경될 때만 데이터를 조회한 점이다. 모바일처럼 다른 클라이언트에서 DB를 바꿔도 이미 열린 웹 탭의 React state에는 이를 알릴 경로가 없었다.

따라서 DB는 새 기간을 가리키지만 웹 화면은 최초 조회 결과를 메모리에 계속 들고 있었다.

## 웹 동기화 개선

웹 마이페이지에 다음 재검증 정책을 추가했다.

1. 브라우저 창이 다시 포커스를 받으면 즉시 `/api/my-page` 재조회
2. 숨겨졌던 탭이 다시 보이면 즉시 재조회
3. 탭을 계속 열어둔 경우 30초마다 백그라운드 재조회
4. 동기화 결과로 현재 열려 있는 구독 상세 모달도 같은 구독 ID의 최신 데이터로 교체
5. 숨겨진 탭에서는 불필요한 주기 요청 생략
6. 포커스와 visibility 이벤트가 동시에 발생하면 100ms debounce로 중복 요청 방지

이 방식은 WebSocket이나 SSE를 추가하지 않고도 마이페이지처럼 변경 빈도가 낮은 화면을 충분히 최신 상태로 유지한다.

## 동기화 시점

```text
모바일에서 기간 수정
-> DB subscriptions.event_id 변경
-> 웹 탭으로 돌아옴
-> focus/visibilitychange
-> GET /api/my-page
-> 최신 event와 기간으로 React state 교체
```

웹을 계속 화면에 띄워둔 경우에는 최대 약 30초 안에 반영된다.

## 문제 2. 모바일 구독 상태 구분 부족

모바일은 구독을 `진행 중인 구독`과 접혀 있는 `마감된 구독`으로만 나눴다. 웹의 `전체`, `예정`, `시작`, `종료` 필터와 UX가 달랐고, 특정 상태를 빠르게 비교하기 어려웠다.

## 모바일 필터 개선

모바일 마이페이지를 단일 `내 구독` 영역으로 통합하고 다음 필터를 추가했다.

| 버튼 | 조건 |
|---|---|
| 전체 | 모든 구독 |
| 예정 | `event.status = UPCOMING` |
| 시작 | `event.status = OPEN` |
| 종료 | `event.status = CLOSED` |

각 버튼에는 현재 상태별 구독 건수를 함께 표시한다.

```text
[전체 28] [예정 12] [시작 3] [종료 13]
```

추가 동작:

- 기본 선택은 `전체`다.
- 홈페이지 출처 검토가 필요한 구독은 선택 상태 안에서도 목록 상단에 둔다.
- 그 외 구독은 접수 시작 시각순으로 정렬한다.
- 종료된 구독은 상세 확인과 구독 해제는 가능하지만 기간 수정 버튼은 표시하지 않는다.
- 선택한 상태에 결과가 없으면 `예정 상태의 구독이 없습니다.`처럼 구체적으로 안내한다.

## 백엔드 검증

`SubscriptionServiceTests`를 캐시 없이 다시 실행해 다음 기존 보장을 확인했다.

- 기간 수정 시 새 이벤트를 조회하거나 생성한다.
- 현재 사용자의 해당 subscription만 새 event로 재연결한다.
- 다른 사용자의 subscription은 변경하지 않는다.
- 같은 사용자가 이미 구독한 target event로 중복 연결되는 것을 차단한다.

즉 이번 문제는 서버 저장 실패가 아니라 열린 웹 클라이언트의 재조회 부재였다.

## 테스트 결과

다음 검증을 모두 통과했다.

```text
backend: ./gradlew.bat test
backend: ./gradlew.bat test --tests com.swimpulse.subscription.SubscriptionServiceTests --rerun-tasks
frontend: npm run lint
frontend: npm run build
mobile: npx tsc --noEmit
mobile: npm run lint
mobile: npm test -- --runInBand
```

## 운영 확인 순서

1. 같은 Google 계정으로 웹과 모바일 로그인
2. 웹 마이페이지를 열어 기존 기간 확인
3. 모바일에서 같은 subscription의 기간 수정
4. 웹 탭으로 돌아와 즉시 변경된 기간이 표시되는지 확인
5. 웹을 계속 표시한 상태라면 30초 안에 변경되는지 확인
6. 모바일 `전체·예정·시작·종료` 필터 건수와 목록 확인

웹 수정은 Vercel 배포 후 반영된다. 모바일 필터와 네이티브 날짜 선택기는 새 APK를 빌드하고 기존 앱에 덮어써야 반영된다.
