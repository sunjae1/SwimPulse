# Notification Load Test Results

작성일: 2026-06-23

## 목적

구독 부하 테스트 이후 남아 있던 알림 경로를 따로 측정했다.

측정 대상은 두 가지다.

| 구분 | 확인한 흐름 |
|---|---|
| 테스트 알림 | `/api/notifications/test` → notification row 생성 → Redis queue publish |
| scheduler due event | due `registration_event` → scheduler tick → 구독자별 notification 생성 → Redis queue → worker 발송 |

실제 FCM 외부 제한과 네트워크 변수를 제거하기 위해 `SWIMPULSE_FIREBASE_MOCK=true` 상태에서 실행했다.

## 이번에 보강한 측정 지표

기존 k6 스크립트에 측정 지표를 추가했다.

| 스크립트 | 추가 지표 | 이유 |
|---|---|---|
| `notification-test-load.js` | `notification_test_list_duration` | 알림 목록 조회 latency를 알림 생성 요청과 분리 |
| `scheduler-notification-load.js` | `scheduler_notification_total_delivery_duration` | scheduler tick 시작부터 모든 알림이 SENT/FAILED 될 때까지의 전체 시간 |
| `scheduler-notification-load.js` | `scheduler_notification_queued_count`, `scheduler_notification_sending_count` | Redis queue와 worker 처리 상태를 더 명확히 보기 위해 추가 |

## 실행 환경

baseline worker 설정:

```properties
SWIMPULSE_NOTIFICATION_WORKER_BATCH_SIZE=20
SWIMPULSE_NOTIFICATION_WORKER_DELAY_MS=1000
SWIMPULSE_FIREBASE_MOCK=true
SWIMPULSE_LOADTEST_ENABLED=true
```

after worker 설정:

```properties
SWIMPULSE_NOTIFICATION_WORKER_BATCH_SIZE=100
SWIMPULSE_NOTIFICATION_WORKER_DELAY_MS=250
SWIMPULSE_FIREBASE_MOCK=true
SWIMPULSE_LOADTEST_ENABLED=true
```

after 설정은 `backend/.env`에 로컬 부하 테스트용으로 반영했다. 이 파일은 git 추적 대상이 아니다.

## 테스트 1. 테스트 알림 queue 처리

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=notification-test-baseline-20260623 `
  -e LOADTEST_TOKEN_COUNT=10 `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e REGISTER_DEVICE=true `
  -e SETUP_SUBSCRIPTION=true `
  -e LIST_AFTER_QUEUE=false `
  k6 run /scripts/notification-test-load.js `
  --summary-export /results/notification-test-baseline-20260623-summary.json `
  --out json=/results/notification-test-baseline-20260623-raw.json
```

after는 같은 명령에서 `RUN_LABEL`과 결과 파일명을 `after`로 바꿔 실행했다.

결과:

| 구분 | iterations | 실패율 | valid response | queued | HTTP p95 |
|---|---:|---:|---:|---:|---:|
| baseline | 1120 | 0% | 100% | 1120 | 51.61ms |
| after | 1120 | 0% | 100% | 1120 | 51.47ms |

해석:

`/api/notifications/test`는 안정적이다. worker batch/delay를 공격적으로 조정해도 notification row 생성과 Redis queue publish latency는 거의 변하지 않았다.

즉, 알림 생성 API 자체는 현재 병목이 아니다.

## 테스트 2. 알림 목록 조회 포함

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=notification-list-baseline-20260623 `
  -e LOADTEST_TOKEN_COUNT=10 `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e REGISTER_DEVICE=true `
  -e SETUP_SUBSCRIPTION=true `
  -e LIST_AFTER_QUEUE=true `
  k6 run /scripts/notification-test-load.js `
  --summary-export /results/notification-list-baseline-20260623-summary.json `
  --out json=/results/notification-list-baseline-20260623-raw.json
```

결과:

| 구분 | iterations | 실패율 | queued | 목록 평균 건수 | 목록 최대 건수 | 목록 조회 p95 | HTTP p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| baseline | 1091 | 0% | 1091 | 234.49 | 446 | 39.51ms | 43.79ms |
| after | 1050 | 0% | 1050 | 453.60 | 663 | 60.31ms | 53.75ms |

해석:

after에서 목록 조회 p95가 증가한 것은 worker 튜닝 때문이라기보다, 앞선 테스트로 사용자별 알림 row가 더 많이 쌓였기 때문이다.

그래도 목록 최대 663건 기준 p95 60.31ms로 즉시 병목은 아니다. 다만 현재 `/api/notifications`는 pagination이 없으므로 운영에서 사용자별 알림이 수천 건 이상 쌓이면 개선 대상이 된다.

## 테스트 3. Scheduler due event 500명 fan-out

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=scheduler-notification-baseline-500-20260623 `
  -e USER_COUNT=500 `
  -e VUS=1 `
  -e ITERATIONS=1 `
  -e POOL_ID=1 `
  -e TITLE="k6 scheduler due notification baseline 20260623" `
  -e START_OFFSET_SECONDS=-5 `
  -e EVENT_DURATION_MINUTES=60 `
  -e REGISTER_DEVICES=true `
  -e WAIT_FOR_DELIVERY=true `
  -e POLL_TIMEOUT_SECONDS=90 `
  k6 run /scripts/scheduler-notification-load.js `
  --summary-export /results/scheduler-notification-baseline-500-20260623-summary.json `
  --out json=/results/scheduler-notification-baseline-500-20260623-raw.json
```

결과:

| 구분 | 구독자 | 실패율 | valid response | 생성 알림 | SENT | FAILED | Redis queue max | Redis queue p95 | scheduler tick | 전체 delivery 완료 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 500 | 0% | 100% | 500 | 500 | 0 | 500 | 476.70 | 1.16s | 32.85s |
| after | 500 | 0% | 100% | 500 | 500 | 0 | 483 | 456.60 | 1.95s | 10.10s |

개선률:

```text
전체 delivery 완료 시간
32.85s -> 10.10s
약 69.3% 감소
약 3.25배 빨라짐
```

## 병목 분석

baseline에서 scheduler tick은 1.16초였지만 전체 delivery 완료는 32.85초였다.

이는 병목이 다음 흐름 중 뒤쪽에 있다는 뜻이다.

```text
due event 감지
→ notification row 생성
→ Redis queue publish
→ worker가 Redis queue 소비
→ MockFcmClient 발송 처리
```

알림 생성/큐잉은 1~2초 안에 끝난다. 오래 걸린 부분은 Redis queue에 쌓인 500개를 worker가 소비하는 시간이다.

기존 worker는 기본적으로 1초마다 최대 20개만 처리한다.

```text
20개 / 1초
500개 처리 이론상 최소 약 25초 이상
```

실제 baseline 32.85초는 이 구조와 잘 맞는다.

## 적용한 개선

Java 코드 구조는 이미 설정값으로 조절 가능했다. 따라서 이번에는 코드 변경보다 worker 설정 튜닝을 먼저 적용했다.

```properties
SWIMPULSE_NOTIFICATION_WORKER_BATCH_SIZE=100
SWIMPULSE_NOTIFICATION_WORKER_DELAY_MS=250
```

효과:

| 항목 | 결과 |
|---|---|
| Queue drain time | 크게 감소 |
| 실패 알림 | 0건 유지 |
| 중복 발송 징후 | 없음 |
| 테스트 알림 API p95 | 거의 변화 없음 |
| scheduler tick | 1.16s → 1.95s로 증가 |

scheduler tick이 늘어난 것은 worker가 더 자주 DB를 사용하면서 scheduler tick과 순간적으로 DB 작업이 겹친 영향으로 보인다. 그래도 전체 사용자 경험 기준으로는 “알림이 실제로 처리 완료되는 시간”이 32.85초에서 10.10초로 줄어든 것이 더 큰 개선이다.

## 결론

현재 알림 생성 API와 notification list 조회는 VUS 10 기준 안정적이다.

가장 큰 병목은 scheduler가 만든 알림을 Redis queue에서 worker가 소비하는 속도였다. worker batch/delay 튜닝만으로 500명 fan-out의 전체 delivery 완료 시간이 약 69.3% 줄었다.

현재 권장 설정:

```properties
SWIMPULSE_NOTIFICATION_WORKER_BATCH_SIZE=100
SWIMPULSE_NOTIFICATION_WORKER_DELAY_MS=250
```

다만 실제 FCM에서는 외부 API 제한과 네트워크 지연이 섞이므로, 운영 적용 전에는 real FCM smoke test를 소량으로 따로 해야 한다.

## 다음 개선 후보

| 우선순위 | 개선 | 이유 |
|---:|---|---|
| 1 | `/api/notifications` pagination | 사용자별 알림 row가 수천 건 이상 쌓일 때 목록 조회 보호 |
| 2 | scheduler notification 생성 bulk화 | 수천 명 이상 fan-out에서 dedupe 조회와 insert 비용 감소 |
| 3 | worker 병렬성 확대 | 1000명 이상 알림 delivery lag 감소 |
| 4 | Grafana에 queue length/delivery lag 패널 고정 | API latency보다 실제 알림 지연을 운영에서 보기 위해 필요 |
| 5 | 실제 FCM 소량 smoke test | mock에서는 외부 FCM 제한을 알 수 없기 때문 |

## 테스트 결과 파일

```text
ops/k6/results/notification-test-baseline-20260623-summary.json
ops/k6/results/notification-test-after-20260623-summary.json
ops/k6/results/notification-list-baseline-20260623-summary.json
ops/k6/results/notification-list-after-20260623-summary.json
ops/k6/results/scheduler-notification-baseline-500-20260623-summary.json
ops/k6/results/scheduler-notification-after-500-20260623-summary.json
```

raw JSON은 같은 prefix의 `*-raw.json`으로 저장했다.

## 정리 SQL

반복 실행 후 테스트 데이터를 정리하려면 다음 SQL을 사용한다.

```sql
DELETE n
FROM notifications n
JOIN registration_events e ON e.id = n.event_id
WHERE e.title LIKE 'k6 scheduler due notification%'
   OR e.title LIKE 'k6 notification test subscription%';

DELETE s
FROM subscriptions s
JOIN registration_events e ON e.id = s.event_id
WHERE e.title LIKE 'k6 scheduler due notification%'
   OR e.title LIKE 'k6 notification test subscription%';

DELETE FROM registration_events
WHERE title LIKE 'k6 scheduler due notification%'
   OR title LIKE 'k6 notification test subscription%';

DELETE FROM user_devices
WHERE device_id LIKE 'scheduler-loadtest-device-%'
   OR device_id LIKE 'k6-notification-device-%';

DELETE FROM app_users
WHERE email LIKE 'scheduler-loadtest-user-%@swimpulse.local'
   OR email LIKE 'loadtest-user-%@swimpulse.local';
```

다른 부하 테스트에서 같은 테스트 사용자를 재사용할 계획이면 `app_users` 삭제는 생략한다.
