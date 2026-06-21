# 구독/알림 k6 부하 테스트 계획

작성일: 2026-06-18

## 목적

구독/알림은 공지 스캔처럼 한 요청의 latency만 보면 부족하다. 구독은 동시 요청에서 중복 생성이 막히는지 봐야 하고, 알림은 API 응답보다 Redis queue와 worker가 밀리지 않는지 봐야 한다.

이번 문서는 다음 두 흐름을 측정한다.

| 스크립트 | 목적 |
|---|---|
| `subscription-load.js` | `/api/subscriptions` 구독 생성, 중복 구독 재사용, optional unsubscribe 성능 측정 |
| `notification-test-load.js` | `/api/notifications/test`로 notification row 생성, Redis queue publish, worker 처리 흐름 측정 |

## 사전 조건

1. 백엔드와 Redis가 떠 있어야 한다.
2. 인증이 필요한 API라 `ACCESS_TOKEN`, `COOKIE_HEADER`, 또는 loadtest 전용 `LOADTEST_TOKEN_COUNT`가 필요하다.
3. 알림 테스트는 사용자가 최소 1개 구독을 가지고 있어야 한다.
4. 실제 FCM 성능이 아니라 서버 내부 queue 처리량을 보고 싶으면 Firebase service account 없이 `MockFcmClient` 상태에서 실행하는 편이 좋다.
5. 실제 FCM으로 실행하면 외부 FCM 제한, 브라우저 토큰 상태, 네트워크 지연이 섞인다.

부하 테스트에서는 실제 FCM 발송을 피하기 위해 백엔드를 다음 값으로 실행하는 것을 권장한다.

```env
SWIMPULSE_FIREBASE_MOCK=true
```

이 값이 `true`이면 `SWIMPULSE_FIREBASE_SERVICE_ACCOUNT_PATH`가 설정되어 있어도 `MockFcmClient`를 사용한다.

## loadtest JWT 자동 발급

여러 사용자 구독 테스트를 위해 구글 로그인을 여러 번 할 필요는 없다. 백엔드를 `SWIMPULSE_LOADTEST_ENABLED=true`로 실행하면 다음 내부 API가 활성화된다.

```text
POST /internal/loadtest/auth/tokens?count=10
```

이 API는 `loadtest-user-1@swimpulse.local`부터 `loadtest-user-{count}@swimpulse.local`까지 고정 이메일 사용자를 만들거나 재사용하고, 각 사용자 JWT를 반환한다.

`subscription-load.js`는 `LOADTEST_TOKEN_COUNT`가 있으면 k6 `setup()` 단계에서 이 API를 호출해 토큰을 자동으로 받아온다. 테스트 user는 DB에 남고, 다음 실행에서는 같은 user를 재사용한다. 자동 rollback은 없다.

`subscription-load.js`는 기본적으로 `setup()` 단계에서 `registrationStartsAt`/`registrationEndsAt`를 한 번만 정해 같은 이벤트를 반복 요청한다. 매 요청마다 다른 이벤트를 만들고 싶을 때만 `UNIQUE_EVENTS=true`를 사용한다.

## 테스트 1. 같은 사용자 같은 이벤트 구독

같은 사용자가 같은 기간을 반복 구독한다. 정상이라면 첫 요청은 생성되고 이후 요청은 기존 구독을 재사용해야 한다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=same-user `
  -e VUS=5 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_ID=1 `
  -e TITLE="k6 same event subscription" `
  -e LOADTEST_TOKEN_COUNT=1 `
  k6 run /scripts/subscription-load.js `
  --summary-export /results/subscription-same-user-summary.json `
  --out json=/results/subscription-same-user-raw.json
```

중점 지표:

| 지표 | 의미 |
|---|---|
| `subscription_valid_response` | 200 응답과 event 포함 여부 |
| `http_req_failed` | 4xx/5xx 비율 |
| `http_req_duration p95` | 구독 API p95 latency |
| DB `subscriptions` count | 같은 사용자/이벤트가 중복 생성되지 않는지 확인 |

## 테스트 2. 구독 생성 후 바로 해제

반복 테스트로 DB에 구독 row가 계속 쌓이는 것을 피하고 싶을 때 사용한다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=subscribe-unsubscribe `
  -e VUS=5 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_ID=1 `
  -e TITLE="k6 subscribe unsubscribe" `
  -e UNSUBSCRIBE_AFTER=true `
  -e LOADTEST_TOKEN_COUNT=1 `
  k6 run /scripts/subscription-load.js `
  --summary-export /results/subscription-unsubscribe-summary.json `
  --out json=/results/subscription-unsubscribe-raw.json
```

주의:

`UNSUBSCRIBE_AFTER=true`는 구독 row는 정리하지만 `registration_events`는 남는다. 이벤트까지 완전히 지우는 테스트는 별도 SQL cleanup을 사용한다.

## 테스트 3. 여러 사용자로 같은 이벤트 구독

여러 사용자가 같은 event를 구독하는 상황은 `LOADTEST_TOKEN_COUNT`로 테스트 user JWT를 자동 발급받아 실행한다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=multi-user `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_ID=1 `
  -e TITLE="k6 multi user subscription" `
  -e LOADTEST_TOKEN_COUNT=10 `
  k6 run /scripts/subscription-load.js `
  --summary-export /results/subscription-multi-user-summary.json `
  --out json=/results/subscription-multi-user-raw.json
```

이 테스트는 사용자 수만큼 구독이 생기는 것이 정상이다. 같은 사용자 중복이 아니라, 같은 event에 여러 사용자가 붙는 구조를 보는 것이다.

## 테스트 4. 테스트 알림 queue 처리

`/api/notifications/test`를 반복 호출한다. 각 요청은 notification row를 만들고 Redis queue에 push한다. worker가 켜져 있으면 곧바로 FCM mock 또는 실제 FCM 발송을 수행한다.

`LOADTEST_TOKEN_COUNT`를 사용하면 k6 `setup()`에서 테스트 user를 만들고, 각 user가 테스트 알림을 보낼 수 있도록 `POOL_ID/TITLE` 기준 구독도 자동 생성한다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=notification-test `
  -e VUS=3 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_ID=1 `
  -e TITLE="k6 notification test subscription" `
  -e REGISTER_DEVICE=true `
  -e LOADTEST_TOKEN_COUNT=3 `
  k6 run /scripts/notification-test-load.js `
  --summary-export /results/notification-test-summary.json `
  --out json=/results/notification-test-raw.json
```

중점 지표:

| 지표 | 의미 |
|---|---|
| `notification_test_valid_response` | `/api/notifications/test`가 정상적으로 QUEUED row를 반환한 비율 |
| `notification_test_queued` | 테스트 중 생성된 알림 수 |
| `http_req_duration p95` | 테스트 알림 API p95 latency |
| Prometheus `swimpulse_notification_queue_length` | Redis 알림 queue가 쌓이는지 여부 |
| Prometheus `swimpulse_notification_delivery_lag_seconds` | notification 생성부터 FCM 성공까지 지연 |
| Prometheus `swimpulse_notification_delivery_total` | FCM 성공/실패 수 |
| Prometheus `swimpulse_notification_queue_requeued_total` | retry/stale recovery 발생 수 |

## 테스트 5. 알림 목록 조회 포함

테스트 알림 생성 후 `/api/notifications` 목록 조회까지 포함한다. 마이페이지/알림 목록이 커졌을 때 영향을 볼 수 있다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=notification-test-with-list `
  -e VUS=3 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_ID=1 `
  -e TITLE="k6 notification test subscription" `
  -e REGISTER_DEVICE=true `
  -e LIST_AFTER_QUEUE=true `
  -e LOADTEST_TOKEN_COUNT=3 `
  k6 run /scripts/notification-test-load.js `
  --summary-export /results/notification-test-with-list-summary.json `
  --out json=/results/notification-test-with-list-raw.json
```

주의:

현재 알림 목록은 pagination이 없다. 오래 돌리면 사용자 알림 row가 계속 늘어나서 목록 조회가 점점 불리해질 수 있다.

## 결과 해석 기준

| 기준 | 판단 |
|---|---|
| `http_req_failed` 0% 근처 | API 레벨 실패 없음 |
| `subscription_valid_response` 95% 이상 | 구독 API 기본 정상 |
| `notification_test_valid_response` 95% 이상 | 테스트 알림 queue 생성 정상 |
| queue length가 계속 증가 | worker 처리량이 생성량보다 부족 |
| delivery lag가 계속 증가 | 알림이 사용자에게 늦게 도착하고 있음 |
| stale requeue 증가 | worker 장애, timeout, FCM 지연 등으로 `SENDING` 상태가 오래 남음 |

## Cleanup SQL 예시

테스트 알림 row를 정리하고 싶으면 다음 순서로 지운다.

```sql
DELETE FROM notifications
WHERE title = 'SwimPulse 테스트 푸시';
```

구독 테스트 데이터를 정리하려면 title 기준 event를 먼저 확인하고, 관련 구독/알림을 지운 뒤 event를 지운다.

```sql
SELECT id, title
FROM registration_events
WHERE title LIKE 'k6%';
```

```sql
DELETE n
FROM notifications n
JOIN registration_events e ON e.id = n.event_id
WHERE e.title LIKE 'k6%';

DELETE s
FROM subscriptions s
JOIN registration_events e ON e.id = s.event_id
WHERE e.title LIKE 'k6%';

DELETE FROM registration_events
WHERE title LIKE 'k6%';
```

## 다음 개선 후보

| 항목 | 이유 |
|---|---|
| 알림 목록 pagination | 알림 row가 쌓이면 `/api/notifications`와 마이페이지 응답이 커진다 |
| subscription duplicate 예외 idempotent 처리 | 같은 사용자 동시 구독에서 unique 충돌이 500으로 새지 않게 보강 가능 |
| worker throughput 전용 seed 테스트 | 실제 due event와 다수 구독자를 넣고 scheduler가 만드는 알림량을 측정 |
| Grafana 패널 추가 | queue length, delivery lag, delivery result를 한 화면에서 보기 위해 필요 |
