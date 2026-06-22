# Scheduler Due Event Notification Load Test Results

작성일: 2026-06-21

## 목적

`registration_events` 중 알림 시각이 도래한 이벤트에 대해 스케줄러가 다수 구독자의 알림을 한 번에 생성하고 Redis queue에 넣은 뒤, worker가 밀리지 않고 처리하는지 확인했다.

이 테스트는 실제 FCM 발송 비용과 외부 제한을 피하기 위해 `SWIMPULSE_FIREBASE_MOCK=true` 상태에서 수행했다.

## 구현 내용

테스트 전용 내부 API를 추가했다.

| API | 역할 |
| --- | --- |
| `POST /internal/loadtest/scheduler-notifications/seed` | 테스트 이벤트, 테스트 사용자, 구독, mock device 생성 |
| `POST /internal/loadtest/scheduler-notifications/tick?eventId=...` | `EventScheduler.tick()` 실행 |
| `GET /internal/loadtest/scheduler-notifications/status?eventId=...` | 생성된 알림 수, 발송 상태, Redis queue 길이 조회 |

k6 스크립트는 `ops/k6/scripts/scheduler-notification-load.js`에 추가했다.

측정 메트릭:

| Metric | 의미 |
| --- | --- |
| `scheduler_notification_tick_duration` | scheduler tick API 응답 시간 |
| `scheduler_notification_count` | 이벤트에 대해 생성된 알림 수 |
| `scheduler_notification_sent_count` | mock FCM worker가 SENT 처리한 알림 수 |
| `scheduler_notification_failed_count` | FAILED 처리된 알림 수 |
| `scheduler_notification_redis_queue_length` | Redis 알림 queue 길이 |
| `scheduler_notification_valid_response` | 테스트 성공 여부 |

## 실행 명령

### 100명 구독자

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=scheduler-due-notification `
  -e USER_COUNT=100 `
  -e VUS=1 `
  -e ITERATIONS=1 `
  -e POOL_ID=1 `
  -e TITLE="k6 scheduler due notification" `
  -e START_OFFSET_SECONDS=-5 `
  -e EVENT_DURATION_MINUTES=60 `
  -e REGISTER_DEVICES=true `
  -e WAIT_FOR_DELIVERY=true `
  -e POLL_TIMEOUT_SECONDS=30 `
  k6 run /scripts/scheduler-notification-load.js `
  --summary-export /results/scheduler-notification-summary.json `
  --out json=/results/scheduler-notification-raw.json
```

### 500명 구독자

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=scheduler-due-notification-500 `
  -e USER_COUNT=500 `
  -e VUS=1 `
  -e ITERATIONS=1 `
  -e POOL_ID=1 `
  -e TITLE="k6 scheduler due notification 500" `
  -e START_OFFSET_SECONDS=-5 `
  -e EVENT_DURATION_MINUTES=60 `
  -e REGISTER_DEVICES=true `
  -e WAIT_FOR_DELIVERY=true `
  -e POLL_TIMEOUT_SECONDS=45 `
  k6 run /scripts/scheduler-notification-load.js `
  --summary-export /results/scheduler-notification-500-summary.json `
  --out json=/results/scheduler-notification-500-raw.json
```

## 결과 요약

| 구독자 수 | HTTP 실패율 | Valid response | 생성 알림 | SENT | FAILED | Scheduler tick | 전체 완료 시간 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 0% | 100% | 100 | 100 | 0 | 569ms | 약 7.7초 |
| 500 | 0% | 100% | 500 | 500 | 0 | 1.58초 | 약 34.3초 |

## 해석

스케줄러 자체는 정상 동작했다.

100명, 500명 모두 알림 생성 수와 SENT 수가 일치했고 실패 알림은 0개였다. HTTP 실패도 없었다.

핵심은 두 시간이 다르다는 점이다.

| 구분 | 의미 |
| --- | --- |
| Scheduler tick 시간 | DB에서 대상 구독자를 읽고 알림 row를 만들고 Redis queue에 넣는 시간 |
| 전체 완료 시간 | Redis queue에 쌓인 알림을 worker가 mock FCM으로 처리 완료할 때까지의 시간 |

500명 테스트에서 scheduler tick은 1.58초로 끝났지만, 전체 완료는 약 34초가 걸렸다. 즉, 병목은 “알림 생성/큐잉”보다 “worker가 queue를 비우는 속도” 쪽에 더 가깝다.

## Redis Queue

| 구독자 수 | Redis queue max | Redis queue p95 | 의미 |
| ---: | ---: | ---: | --- |
| 100 | 100 | 거의 100 | 한 번에 100개가 큐에 쌓인 뒤 worker가 소비 |
| 500 | 500 | 약 476 | 한 번에 500개가 큐에 쌓이고 worker가 순차 소비 |

현재 worker 설정은 기본적으로 작은 batch를 일정 간격으로 처리하는 구조다. 그래서 500개 알림은 큐잉은 빨리 끝나지만, 소비에는 시간이 걸린다.

## 발견한 병목

1. Worker 처리량

현재 구조에서는 Redis queue에 들어간 뒤 worker가 batch 단위로 처리한다. 기본 batch 크기와 polling delay가 작으면 500명 이상부터 delivery lag가 선형으로 늘어난다.

2. 알림 생성 쿼리

`NotificationService.createAndQueueForEvent()`는 구독자를 순회하면서 dedupe key 확인과 저장을 수행한다. 수백 명에서는 괜찮지만, 수천 명 이상이면 N개 구독자에 비례해서 DB 왕복과 insert 비용이 커질 수 있다.

3. 이벤트 상태 갱신

`EventScheduler.tick()` 안에서 이벤트 상태 갱신이 전체 이벤트 기준으로 넓게 돌면 이벤트 수가 많아졌을 때 비용이 커질 수 있다. 현재 규모에서는 큰 문제는 아니지만 운영 데이터가 쌓이면 개선 대상이다.

## 개선 방향

우선순위는 다음 순서가 좋다.

1. Worker batch 크기 조정

예: `swimpulse.notification.worker-batch-size`를 20에서 50 또는 100으로 늘려본다.

기대 효과:

| 항목 | 효과 |
| --- | --- |
| Queue drain time | 크게 감소 |
| Scheduler tick | 거의 변화 없음 |
| DB/FCM 부하 | 증가 가능 |

2. Worker 병렬성 확대

여러 worker가 같은 Redis queue를 소비하도록 하면 delivery lag를 줄일 수 있다.

주의점은 중복 발송 방지다. 현재 `SENDING`, `SENT`, dedupe key 방어가 있으므로 방향은 맞다. 다만 worker 수를 늘릴 때는 stale SENDING 재큐잉과 중복 처리 로그를 같이 봐야 한다.

3. 알림 생성 bulk화

구독자가 수천 명 이상인 이벤트를 생각하면 알림 row 생성은 batch insert 또는 dedupe key batch 조회로 바꾸는 편이 좋다.

현재:

```text
subscription N개 조회
→ 각 subscription마다 dedupe 확인
→ 각 notification 저장
→ 각 notification queue publish
```

개선:

```text
subscription N개 조회
→ dedupe key N개를 한 번에 조회
→ 없는 것만 batch insert
→ insert된 알림만 queue publish
```

4. EventScheduler 대상 조회 축소

전체 이벤트를 매번 갱신하지 않고, 현재 시각 기준으로 상태 변경 가능성이 있는 이벤트만 조회하는 쿼리로 줄인다.

## 현재 결론

현재 구현은 500명 단일 이벤트까지는 오류 없이 처리된다.

다만 500명 기준 전체 완료가 약 34초라서, 실제 운영에서 “알림이 거의 즉시 와야 한다”를 목표로 하면 worker 처리량 튜닝이 필요하다.

가장 먼저 볼 설정은 worker batch size와 worker delay다. 이 두 값을 조정한 뒤 같은 k6 스크립트로 500명, 1000명 테스트를 다시 돌리면 다음 병목이 더 명확해진다.

## 정리 SQL

테스트 데이터 정리가 필요하면 다음 SQL을 사용한다.

```sql
DELETE n
FROM notifications n
JOIN registration_events e ON e.id = n.event_id
WHERE e.title LIKE 'k6 scheduler due notification%';

DELETE s
FROM subscriptions s
JOIN registration_events e ON e.id = s.event_id
WHERE e.title LIKE 'k6 scheduler due notification%';

DELETE FROM registration_events
WHERE title LIKE 'k6 scheduler due notification%';

DELETE FROM user_devices
WHERE device_id LIKE 'scheduler-loadtest-device-%';

DELETE FROM app_users
WHERE email LIKE 'scheduler-loadtest-user-%@swimpulse.local';
```

테스트 사용자를 다른 부하 테스트에서도 재사용하고 있다면 `app_users` 삭제는 생략해도 된다.
