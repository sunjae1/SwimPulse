# 구독/알림 성능 및 아키텍처 점검

작성일: 2026-06-18

## 요약

현재 구독/알림 기능은 일반적인 사용 흐름에서는 동작 가능한 구조다. 구독 생성은 `registration_events` 유니크 제약과 duplicate 재조회 방식으로 같은 논리 이벤트 중복 생성 위험을 줄였고, 알림은 `EventScheduler -> notifications row 생성 -> Redis list queue -> NotificationWorker -> FCM` 흐름으로 비동기 처리된다.

다만 위치 조회, 공지 스캔, OCR에 비해 구독/알림은 아직 k6 부하 테스트가 없다. 그래서 “기능은 동작한다”와 “동시 요청/대량 알림에서도 충분히 검증됐다”는 분리해서 봐야 한다.

RabbitMQ 전환은 지금 당장 필수는 아니다. 먼저 Redis queue의 안전성을 보강하고, 알림 전용 부하 테스트와 관측 지표를 추가하는 것이 우선이다. RabbitMQ는 알림량이 커지고, 전달 보장, dead-letter, 재시도 정책, 운영 UI가 중요해질 때 검토하는 쪽이 맞다.

## 현재 구조

| 영역 | 현재 구현 |
|---|---|
| 구독 생성 | `/api/subscriptions`에서 기간을 받아 `RegistrationEventResolver`가 event를 찾거나 생성 |
| 이벤트 중복 방지 | `registration_events`의 `(pool_id, title, registration_starts_at, registration_ends_at)` 유니크 제약 |
| 공지 기간 기반 구독 | `notice_registration_period_id`로 공지에서 추출한 기간과 event 연결 가능 |
| 구독 중복 방지 | `subscriptions`의 `(user_id, event_id)` 유니크 제약 |
| 알림 생성 | `EventScheduler`가 30초마다 due event를 확인하고 `notifications` row 생성 |
| 알림 큐 | Redis list `swimpulse:notifications`에 notification id push |
| 알림 발송 | `NotificationWorker`가 Redis list에서 id를 pop하고 FCM 발송 |
| FCM 로컬 테스트 | Firebase service account가 없으면 `MockFcmClient` 사용 |
| 스케줄러 thread | notification, OCR, event 전용 scheduler pool 분리 완료 |

## 잘 되어 있는 부분

| 항목 | 평가 |
|---|---|
| 이벤트 중복 생성 방어 | DB 유니크 제약 + duplicate key 재조회 방식이라 좋은 방향이다. |
| 구독 중복 방어 | `(user_id, event_id)` 유니크 제약이 있어 같은 event 중복 구독은 막는다. |
| 멀티 인스턴스 이벤트 스케줄러 | `EventScheduler`가 Redis lock을 잡아 중복 스케줄링 가능성을 줄인다. |
| 알림 비동기 처리 | 요청 흐름과 FCM 발송을 분리했기 때문에 사용자 요청이 FCM 속도에 직접 묶이지 않는다. |
| 워커 스레드 분리 | 알림, OCR, 이벤트 스케줄러가 서로 다른 scheduler pool에서 돈다. OCR이 알림 스케줄러 thread를 직접 막는 문제는 줄었다. |
| 로컬 부하 테스트 가능성 | `MockFcmClient`가 있어 실제 FCM 비용 없이 알림 처리량 테스트를 만들 수 있다. |

## 아직 검증이 부족한 부분

| 항목 | 현재 상태 | 리스크 |
|---|---|---|
| 구독 API 부하 테스트 | 전용 k6 스크립트 없음 | 동시 구독 시 latency, duplicate 처리, DB lock/unique 충돌 빈도를 모름 |
| 알림 생성 부하 테스트 | 전용 k6/시드 테스트 없음 | 구독자 수가 많을 때 notifications insert + Redis queue push 속도를 모름 |
| 알림 발송 처리량 테스트 | 전용 테스트 없음 | `worker-batch-size`, FCM 지연, Redis pop 속도 기준 처리량을 모름 |
| 알림 지연 관측 | queue length, delivery lag metric 없음 | 알림이 밀리는지 Grafana에서 바로 보기 어렵다 |
| 실패/재시도 관측 | 성공/실패/retry counter 부족 | 장애가 나도 어느 단계에서 막혔는지 바로 구분하기 어렵다 |

## 주요 리스크

| 우선순위 | 리스크 | 설명 | 개선 방향 |
|---:|---|---|---|
| 1 | 알림 Redis publish가 DB commit 전에 실행됨 | `NotificationService.createAndQueueForEvent`는 notification 저장 트랜잭션 안에서 바로 Redis에 push한다. 트랜잭션 commit 전에 worker가 pop하면 DB row가 아직 안 보일 수 있다. | OCR 큐처럼 `publishAfterCommit` 방식으로 변경 |
| 2 | Redis list pop 이후 worker 장애 시 유실 가능 | `leftPop`은 꺼내는 순간 queue에서 제거된다. worker가 pop 후 crash하면 notification row는 남지만 queue item은 사라질 수 있다. | stale `QUEUED` notification 재큐잉 job 또는 Redis reliable queue 패턴 도입 |
| 3 | 알림 중복 row 방어 부족 | event의 `reminderQueued`, `startQueued`가 일반 경로를 막지만, race나 재시도 상황에서 `(user_id, event_id, type)` 중복 알림 row를 DB가 직접 막지는 않는다. | `notifications(user_id, event_id, type)` 유니크 제약 검토 |
| 4 | 구독 동시 요청 duplicate save 예외 가능 | 같은 사용자가 같은 event를 거의 동시에 구독하면 find 후 둘 다 save로 들어가고 한쪽은 유니크 충돌이 날 수 있다. 현재는 이 예외를 idempotent 응답으로 바꾸지 않는다. | duplicate key catch 후 기존 subscription 재조회 |
| 5 | 스케줄러 조회가 전체 이벤트 기반 | `refreshStatuses()`는 `registration_events` 전체를 읽고, 알림 큐잉도 `UPCOMING`, `OPEN` 전체를 읽는다. 이벤트가 많아지면 느려진다. | due 대상만 조회하는 쿼리와 복합 인덱스 추가 |
| 6 | 알림 목록 조회 pagination 없음 | `/api/notifications`, 마이페이지 알림이 사용자 전체 알림을 모두 내려준다. | limit/page 또는 cursor pagination 추가 |
| 7 | FCM invalid token 정리 부족 | FCM 실패 원인에 따라 device token을 disable하는 처리가 없다. | invalid/registration-token-not-registered 계열은 device disable |

## 인덱스 개선 후보

현재 `subscriptions`에는 `user_id`, `pool_id`, `event_id` 인덱스와 `(user_id, event_id)` 유니크가 있다. `registration_events`에는 유니크 제약이 있어 event 생성/조회 방어는 좋다. 다만 스케줄러와 알림 조회에 맞춘 복합 인덱스는 더 필요하다.

| 테이블 | 후보 인덱스 | 이유 |
|---|---|---|
| `registration_events` | `(status, registration_starts_at)` | `findByStatusInOrderByRegistrationStartsAtAsc`와 due event 조회 최적화 |
| `notifications` | `(user_id, created_at)` | 사용자 알림 목록 최신순 조회 최적화 |
| `notifications` | `(status, created_at)` | stale `QUEUED` 재큐잉 job 추가 시 필요 |
| `notifications` | unique `(user_id, event_id, type)` | 같은 이벤트/타입 알림 중복 생성 방어 |
| `user_devices` | `(user_id, enabled)` | 발송 대상 device 조회와 active device count 최적화 |
| `subscriptions` | `(user_id, created_at)` | 내 구독 목록 최신순 조회 최적화 |

## 캐싱 판단

구독/알림에는 일반적인 Redis 캐싱을 크게 넣지 않는 편이 안전하다.

| 대상 | 캐싱 추천 여부 | 이유 |
|---|---|---|
| 내 구독 목록 | 낮음 | 사용자별 최신성이 중요하고 쓰기 직후 바로 반영되어야 한다. |
| 내 알림 목록 | 낮음 | 읽음 처리, 발송 상태가 자주 바뀐다. |
| event 조회 | 일부 가능 | 공개 event 목록은 짧은 TTL 캐시가 가능하지만 현재 병목 우선순위는 낮다. |
| device registration 상태 | 낮음 | 브라우저 push 등록 직후 정확성이 중요하다. |

현재 단계에서는 캐싱보다 인덱스, pagination, queue 안정성이 더 중요하다.

## Redis queue와 RabbitMQ 비교

| 항목 | 현재 Redis list | RabbitMQ |
|---|---|---|
| 구현 복잡도 | 낮음 | 중간 이상 |
| 현재 프로젝트 적합성 | 초기 운영에는 충분 | 아직 과할 수 있음 |
| ack/requeue | 직접 구현 필요 | 기본 기능 |
| dead-letter queue | 직접 구현 필요 | 기본 지원 |
| 지연/재시도 정책 | 직접 구현 필요 | plugin/TTL/DLX로 구성 가능 |
| 운영 UI | 별도 없음 | Management UI 제공 |
| 장애 시 유실 방어 | 현재는 약함 | ack 기반으로 강함 |
| 대량 알림 확장 | 제한적이지만 가능 | 더 적합 |

결론은 “RabbitMQ가 나쁜 선택”이 아니라 “지금 당장 1순위는 아니다”에 가깝다. 지금은 Redis queue를 쓰되, commit 이후 publish, stale queued recovery, queue length metric을 먼저 넣는 것이 비용 대비 효과가 좋다.

RabbitMQ 전환을 검토할 조건은 다음과 같다.

| 조건 | 판단 |
|---|---|
| 알림을 반드시 at-least-once로 보장해야 함 | RabbitMQ 검토 |
| 알림 실패를 dead-letter로 모아 운영자가 보고 싶음 | RabbitMQ 검토 |
| 워커를 여러 대로 늘리고 처리량을 안정적으로 관리해야 함 | RabbitMQ 검토 |
| Redis를 캐시와 queue로 같이 쓰는 것이 운영상 불안해짐 | RabbitMQ 검토 |
| 현재처럼 개인별 구독 알림 규모가 작고 단순함 | Redis 유지 가능 |

## 스케줄러/워커 개선 후보

| 항목 | 현재 | 개선 |
|---|---|---|
| 알림 워커 thread | 전용 scheduler pool 1개 | 필요 시 `SWIMPULSE_NOTIFICATION_SCHEDULER_POOL_SIZE` 증가 |
| OCR 워커 thread | 전용 scheduler pool 1개 | OCR 처리량이 부족하면 별도 executor 또는 worker service 분리 |
| 이벤트 스케줄러 | 30초마다 상태 갱신 + 알림 큐잉 | due event만 조회하도록 쿼리 최적화 |
| queue pop | Redis `leftPop` | reliable queue 또는 stale queued recovery |
| queue publish | 알림은 commit 전 publish | `afterCommit` publish로 변경 |

스케줄러 thread는 분리됐지만 CPU, DB connection, Redis, 네트워크는 여전히 공유 자원이다. 따라서 OCR이 CPU를 많이 쓰면 간접 영향은 남을 수 있다.

## 부하 테스트 추가 제안

| 스크립트 | 목적 | 핵심 지표 |
|---|---|---|
| `subscription-create-load.js` | 같은 event에 여러 사용자가 동시에 구독 | p95, duplicate rate, 4xx/5xx, subscription count |
| `subscription-same-user-idempotency-load.js` | 같은 사용자가 같은 event를 연속/동시 구독 | 200 응답 유지 여부, duplicate key 500 발생 여부 |
| `notification-list-load.js` | 마이페이지/알림 목록 조회 | p95, DB query latency, response size |
| `notification-worker-throughput-load.js` | Mock FCM으로 queue 처리량 측정 | queue drain rate, sent/sec, failed/sec |
| `event-scheduler-due-load.js` | 많은 due event와 구독자를 넣고 알림 생성 속도 측정 | notifications insert/sec, Redis push/sec, scheduler duration |

부하 테스트는 실제 FCM이 아니라 `MockFcmClient` 상태에서 돌리는 것이 좋다. 실제 FCM으로 부하를 주면 외부 서비스 제한, 비용, 실제 브라우저 토큰 영향이 섞인다.

## Grafana/Prometheus 추가 관측 후보

| metric | 의미 |
|---|---|
| `swimpulse_notification_queued_total` | 알림 queue push 수 |
| `swimpulse_notification_delivery_total{result}` | FCM 발송 성공/실패/retry 수 |
| `swimpulse_notification_worker_duration_seconds` | worker batch 처리 시간 |
| `swimpulse_notification_queue_size` | Redis `swimpulse:notifications` list 길이 |
| `swimpulse_notification_delivery_lag_seconds` | notification created_at부터 sent_at까지 걸린 시간 |
| `swimpulse_event_scheduler_duration_seconds` | 이벤트 스케줄러 tick 소요 시간 |
| `swimpulse_subscription_created_total` | 구독 생성 수 |
| `swimpulse_subscription_duplicate_total` | 중복 구독 재사용/충돌 수 |

알림은 “API latency”보다 “queue length”와 “delivery lag”가 더 중요하다. API가 빨라도 queue가 밀리면 사용자는 늦게 알림을 받는다.

## 우선순위 제안

| 순위 | 작업 | 이유 |
|---:|---|---|
| 1 | 알림 queue publish를 transaction afterCommit으로 변경 | DB commit 전 queue 소비 위험 제거 |
| 2 | notification delivery metric과 Redis queue length metric 추가 | 알림이 밀리는지 Grafana에서 바로 확인 |
| 3 | 구독/알림 k6 스크립트 추가 | 현재 미검증 영역을 수치화 |
| 4 | `notifications(user_id, created_at)`, `registration_events(status, registration_starts_at)` 인덱스 추가 | 조회와 스케줄러 성능 안정화 |
| 5 | duplicate notification 유니크 제약 검토 | 중복 발송 방어 |
| 6 | stale `QUEUED` notification recovery job 추가 | Redis pop 이후 장애 대비 |
| 7 | 알림 목록 pagination 추가 | 장기 사용 시 응답 크기 증가 방지 |
| 8 | RabbitMQ 검토 | 위 보강 후에도 queue 신뢰성/운영성이 부족할 때 전환 |

## 결론

현재 구독/알림 구조는 초기 서비스 규모에서는 무난하다. 구독 중복 생성의 핵심 문제는 DB 유니크 제약으로 잘 잡아가고 있고, 알림도 Redis queue + worker로 비동기화되어 있다.

하지만 알림은 아직 부하 테스트와 운영 관측이 부족하다. 특히 queue publish 시점, pop 이후 장애, 중복 알림 row, delivery lag 관측은 운영 전에 보강하는 편이 좋다.

RabbitMQ는 지금 당장 도입하기보다, Redis queue를 조금 더 안전하게 다듬고 k6/Prometheus로 실제 병목을 확인한 뒤 결정하는 것이 좋다. 지금 단계의 다음 한 수는 RabbitMQ 전환이 아니라 “알림 queue 안정성 + 알림 부하 테스트 + 알림 관측 지표”다.
