# 구독/알림 부하 테스트 결과 및 수정 내역

작성일: 2026-06-20

## 목적

구독과 알림은 단일 API 응답 속도만 보면 부족하다. 같은 이벤트를 여러 번 누르는 동시성, 여러 사용자가 같은 이벤트에 붙는 상황, 알림 row 생성 후 Redis queue와 worker가 밀리지 않는지까지 확인해야 한다.

이번 테스트는 `reports/023_subscription-notification-k6-loadtest-plan-2026-06-18.md`의 5개 시나리오를 기준으로 실행했다.

## 결과 요약

| 테스트 | 목적 | 결과 파일 | 요청/반복 | 실패율 | p95 latency | 핵심 결과 |
|---|---|---|---:|---:|---:|---|
| 1. 같은 사용자 같은 이벤트 구독 | 같은 user/event 중복 구독 재사용 | `subscription-same-user-summary.json` | 540 iterations | 0.00% | 73.6ms | 중복 구독 없이 기존 구독 재사용 |
| 2. 구독 생성 후 바로 해제 | subscribe/delete 반복 안정성 | `subscription-unsubscribe-summary.json` | 535 iterations | 0.00% | 72.6ms | 동시 delete 404/500 없이 idempotent 처리 |
| 3. 여러 사용자 같은 이벤트 구독 | event 1개를 여러 사용자가 재사용 | `subscription-multi-user-summary.json` | 1100 iterations | 0.00% | 67.2ms | 커넥션 고갈 없이 같은 event 재사용 |
| 4. 테스트 알림 queue 처리 | notification row 생성 + Redis queue push | `notification-test-summary.json` | 339 iterations | 0.00% | 52.1ms | QUEUED 생성 정상, device token 3개 등록 |
| 5. 알림 목록 조회 포함 | 알림 생성 후 목록 조회 영향 확인 | `notification-test-with-list-summary.json` | 330 iterations | 0.00% | 48.7ms | 알림 목록 평균 168.5개, 최대 223개까지 조회 |

## 테스트 1: 같은 사용자 같은 이벤트 구독

결과:

| 지표 | 값 |
|---|---:|
| iterations | 540 |
| `subscription_valid_response` | 100% |
| `http_req_failed` | 0% |
| `http_req_duration p95` | 73.6ms |
| `subscription_duration p95` | 73.4ms |

의미:

같은 사용자가 같은 이벤트를 반복 구독해도 `subscriptions(user_id, event_id)` unique 기준으로 중복 row가 생기지 않고, 기존 구독이 재사용된다.

## 테스트 2: 구독 생성 후 바로 해제

결과:

| 지표 | 값 |
|---|---:|
| iterations | 535 |
| subscribe + unsubscribe HTTP requests | 1071 |
| `subscription_valid_response` | 100% |
| `subscription_unsubscribed` | 535 |
| `http_req_failed` | 0% |
| `http_req_duration p95` | 72.6ms |
| `subscription_duration p95` | 79.7ms |

의미:

부하 테스트 중 같은 user/event에 대해 unsubscribe가 여러 번 겹쳐도 실패로 새지 않는다. 이미 삭제된 구독은 no-op으로 처리한다.

## 테스트 3: 여러 사용자 같은 이벤트 구독

결과:

| 지표 | 값 |
|---|---:|
| iterations | 1100 |
| `subscription_valid_response` | 100% |
| `http_req_failed` | 0% |
| `http_req_duration p95` | 67.2ms |
| `subscription_duration p95` | 66.1ms |

의미:

10명의 테스트 사용자가 같은 event 하나를 구독하는 상황에서, `registration_events` 중복 생성 경합과 DB connection pool 고갈이 해결되었다.

## 테스트 4: 테스트 알림 queue 처리

결과:

| 지표 | 값 |
|---|---:|
| iterations | 339 |
| `notification_test_valid_response` | 100% |
| `notification_test_queued` | 339 |
| device registrations | 3 |
| `http_req_failed` | 0% |
| `http_req_duration p95` | 52.1ms |
| `notification_test_duration p95` | 52.2ms |

의미:

`/api/notifications/test`가 notification row를 만들고 Redis queue에 push하는 흐름은 안정적이다. 부하 테스트는 `SWIMPULSE_FIREBASE_MOCK=true` 상태를 권장한다.

## 테스트 5: 알림 목록 조회 포함

결과:

| 지표 | 값 |
|---|---:|
| iterations | 330 |
| HTTP requests | 667 |
| `notification_test_valid_response` | 100% |
| `notification_test_queued` | 330 |
| `notification_test_list_count avg` | 168.5 |
| `notification_test_list_count max` | 223 |
| `http_req_failed` | 0% |
| `http_req_duration p95` | 48.7ms |
| `notification_test_duration p95` | 50.1ms |

의미:

테스트5는 알림 row를 새로 만들고 바로 `/api/notifications` 목록 조회까지 수행한다. 이번 실행에서는 목록이 최대 223개까지 커졌지만 API 실패는 없었다.

주의:

현재 summary에는 목록 조회 전용 duration metric이 없다. `http_req_duration`에는 테스트 알림 생성 요청과 목록 조회 요청이 함께 섞인다. 다음에는 `notification_test_list_duration` 별도 Trend를 추가하면 목록 조회만 분리해 볼 수 있다.

## 테스트 중 발견된 문제와 해결

### 1. registration_events 중복 insert 충돌

문제:

여러 요청이 동시에 같은 이벤트를 만들면 아래 unique key에서 충돌할 수 있었다.

```text
uk_registration_event_pool_title_period
pool_id + title + registration_starts_at + registration_ends_at
```

기존 흐름:

```text
요청 A: event 조회 -> 없음
요청 B: event 조회 -> 없음
요청 A: INSERT 성공
요청 B: INSERT 시 unique 충돌
요청 B: 예외가 500으로 노출될 수 있음
```

해결 후 흐름:

```text
요청 A: event 조회 -> 없음
요청 B: event 조회 -> 없음
요청 A: INSERT 성공
요청 B: INSERT unique 충돌
요청 B: DataIntegrityViolationException catch
요청 B: REQUIRES_NEW 새 트랜잭션으로 기존 event 재조회
요청 B: 기존 event 재사용
```

왜 `REQUIRES_NEW`가 필요한가:

unique 충돌이 난 같은 트랜잭션 안에서 바로 재조회하면 트랜잭션 상태가 이미 실패 상태이거나, 격리 수준/flush 상태 때문에 안정적인 재조회가 어렵다. 그래서 충돌 후 재조회는 짧은 새 트랜잭션으로 분리했다.

### 2. unsubscribe 동시 실행 문제

문제:

테스트2에서 같은 사용자가 같은 이벤트를 구독 후 바로 해제하는데, 여러 VU가 같은 row를 지우려고 하면 첫 요청만 성공하고 나머지는 `Subscription not found`로 실패할 수 있었다.

기존 흐름:

```text
요청 A: subscription 조회 -> 있음
요청 B: subscription 조회 -> 있음 또는 이미 삭제됨
요청 A: DELETE 성공
요청 B: 삭제 대상 없음 -> 404/실패
```

해결 후 흐름:

```text
DELETE FROM subscriptions
WHERE user_id = ?
  AND event_id = ?

deletedRows = 1 -> 삭제 성공
deletedRows = 0 -> 이미 삭제된 것으로 보고 no-op
```

결과:

테스트2에서 `http_req_failed`가 0%가 되었다. unsubscribe는 멱등 API처럼 동작한다.

### 3. lazy proxy no session 문제

문제:

부하 테스트 중 아래 오류가 발생했다.

```text
Could not initialize proxy [com.swimpulse.pool.Pool#1] - no session
```

원인:

JPA lazy entity를 트랜잭션 밖에서 응답 DTO로 변환하면서 `event.pool` 같은 지연 로딩 필드에 접근했다.

해결:

구독 insert 후 응답 생성은 별도 트랜잭션 안에서 다시 조회해 DTO로 변환한다.

```text
insert subscription
-> findExistingResponse(userId, eventId) with transaction
-> SubscriptionResponse.from(...)
```

공지 기간 기반 구독은 `NoticeRegistrationPeriod` 조회 시 `notice`와 `pool`을 fetch join으로 같이 가져온다.

```text
period
-> notice
-> pool
```

### 4. DB connection pool 고갈

문제:

테스트3에서 다음 오류가 발생했다.

```text
Could not open JPA EntityManager for transaction
HikariPool-1 - Connection is not available, request timed out after 30000ms
total=10, active=10, idle=0
```

원인:

기존 `subscribe()`가 큰 트랜잭션을 잡은 상태에서 event insert, subscription insert가 `REQUIRES_NEW`로 새 트랜잭션을 열었다. VU 10에서 요청마다 커넥션을 여러 개 요구하면서 Hikari pool 10개가 모두 점유되었다.

기존 흐름:

```text
subscribe() outer transaction 시작
-> user/event 조회
-> event INSERT REQUIRES_NEW 필요
-> subscription INSERT REQUIRES_NEW 필요
-> outer transaction connection도 점유
-> 동시 요청 10개에서 pool 고갈
```

해결 후 흐름:

```text
subscribe()는 큰 트랜잭션을 열지 않음
-> user 존재 여부 짧게 확인
-> event get-or-create
-> subscription 존재 여부 짧게 확인
-> subscription INSERT만 짧은 REQUIRES_NEW
-> 응답 DTO는 짧은 조회 트랜잭션에서 생성
```

결과:

테스트3 재실행에서 1100 iterations, 실패 0%, p95 67.2ms를 기록했다.

### 5. entity 전체 전달 대신 ID reference 저장

문제:

구독 저장 시 `AppUser`, `RegistrationEvent`, `Pool` entity를 들고 다니면 불필요한 조회와 lazy proxy 접근이 늘어난다.

해결:

구독 저장은 ID만 받아 JPA reference로 연결한다.

```java
AppUser user = entityManager.getReference(AppUser.class, userId);
RegistrationEvent event = entityManager.getReference(RegistrationEvent.class, eventId);
Pool pool = entityManager.getReference(Pool.class, poolId);
```

이 방식은 `subscriptions` row에 필요한 FK 값만 저장하면 되는 상황에 맞다.

```text
subscriptions.user_id = userId
subscriptions.event_id = eventId
subscriptions.pool_id = poolId
```

장점:

| 항목 | 효과 |
|---|---|
| 불필요한 SELECT 감소 | FK 연결만 필요한 경우 entity 전체 조회를 피함 |
| lazy proxy 오류 감소 | 응답 변환과 저장 책임을 분리 |
| 트랜잭션 단축 | 저장 트랜잭션이 작아짐 |

### 6. k6 스크립트가 같은 이벤트를 만들지 못한 문제

문제:

`subscription-load.js`가 요청마다 `Date.now()`로 `registrationStartsAt`/`registrationEndsAt`를 만들었다. 제목은 같아도 시간이 조금씩 달라져서 `registration_events` row가 계속 늘었다.

문제 흐름:

```text
title = "k6 same event subscription"
startsAt = 2026-06-22T04:00:00.100Z
startsAt = 2026-06-22T04:00:00.650Z
startsAt = 2026-06-22T04:00:01.020Z

=> DB 입장에서는 모두 다른 event
```

해결:

k6 `setup()`에서 시간을 한 번만 만들고 모든 VU/iteration이 같은 값을 재사용한다.

```text
setup()
-> registrationStartsAt 1회 생성
-> registrationEndsAt 1회 생성
-> 모든 요청이 같은 시간 사용
```

요청마다 다른 이벤트를 의도적으로 만들고 싶으면 `UNIQUE_EVENTS=true`를 사용한다.

### 7. k6 런타임 JS 문법 차이

문제:

Node.js에서는 통과한 object spread 문법이 k6 런타임에서 실패했다.

```js
{ tokens: accessTokens, ...timing }
```

해결:

k6 호환성을 위해 명시적 객체 필드 대입으로 변경했다.

```js
{
  tokens: accessTokens,
  registrationStartsAt: timing.registrationStartsAt,
  registrationEndsAt: timing.registrationEndsAt,
}
```

둘 다 JavaScript지만 실행 엔진과 지원 문법 범위가 다를 수 있다. k6 스크립트는 Node.js에서 문법 검사만 통과했다고 끝이 아니고, 실제 k6 런타임으로도 확인해야 한다.

### 8. 실제 FCM 부하 테스트 위험

문제:

알림 부하 테스트를 실제 FCM으로 보내면 FCM 제한, 브라우저 토큰 상태, 네트워크 지연이 결과에 섞인다.

해결:

`SWIMPULSE_FIREBASE_MOCK=true`를 추가해 부하 테스트에서는 mock FCM client를 강제로 사용하도록 했다.

```text
API -> notification row -> Redis queue -> worker -> MockFcmClient
```

이렇게 하면 서버 내부 queue 처리량과 DB/Redis 흐름만 안정적으로 볼 수 있다.

## 알림 queue 보강 내역

부하 테스트 전후로 알림 구조도 운영 안정성 기준으로 보강했다.

| 항목 | 내용 |
|---|---|
| publish after commit | DB commit 전에 Redis queue에 넣지 않도록 변경 |
| `SENDING` 상태 추가 | worker가 pop한 뒤 처리 중인 row를 구분 |
| stale requeue | worker 장애로 `SENDING`에 오래 남은 row를 다시 `QUEUED` 처리 |
| dedupe key | 같은 알림 row 중복 생성을 방지 |
| delivery lag metric | notification 생성부터 발송 완료까지 걸린 시간 관측 |
| queue length gauge | Redis queue가 밀리는지 Grafana에서 확인 가능 |

## 현재 해석

이번 결과 기준으로 구독 API와 테스트 알림 queue API는 VU 3~10 수준에서 안정적이다. 특히 테스트 중 실제로 터진 중복 insert, 동시 delete, lazy proxy, DB connection pool 고갈이 모두 코드 수정으로 해결되었다.

다만 알림 목록 조회는 아직 pagination이 없다. 이번 테스트에서는 최대 223개 목록까지는 p95 50ms 안팎으로 무난했지만, 실제 운영에서 사용자별 알림이 수천 개로 늘면 응답 크기와 DB 조회 비용이 커질 수 있다.

## 다음 개선 후보

| 우선순위 | 개선 | 이유 |
|---:|---|---|
| 1 | `/api/notifications` pagination 추가 | 마이페이지/알림 목록 row 증가에 대비 |
| 2 | `notification_test_list_duration` k6 metric 추가 | 목록 조회 latency만 분리 측정 |
| 3 | due event seed 기반 scheduler 부하 테스트 | 실제 scheduler가 다수 구독자에게 알림을 만드는 흐름 측정 |
| 4 | Grafana dashboard에 queue length/delivery lag 패널 고정 | 알림 worker 병목을 눈으로 확인 |
| 5 | 테스트 데이터 cleanup 명령 문서화 강화 | repeated k6 실행 후 DB 오염 방지 |

