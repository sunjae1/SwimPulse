# 관리자 대시보드 2단계 구현 정리

작성일: 2026-06-24

## 구현 목적

관리자 페이지 1단계는 읽기 전용 대시보드였다.
이번 단계에서는 운영 중 바로 확인해야 하는 지표는 polling으로 갱신하고, 관리자가 안전하게 실행할 수 있는 최소 운영 액션을 추가했다.

추가 목표는 다음과 같다.

- queue/worker 상태는 짧은 주기로 갱신한다.
- 공지 source, OCR, 인기 수영장, 시설 추가 요청은 긴 주기로 갱신한다.
- 실패 알림과 오래 멈춘 `SENDING` 알림을 관리자 화면에서 재큐잉할 수 있게 한다.
- 지역별 인기 수영장을 볼 수 있게 한다.
- 사용자의 “이 시설 추가” 요청은 바로 `pools`에 넣지 않고 관리자 승인 흐름으로 바꾼다.
- 알림이 실제로 얼마나 처리됐는지 볼 수 있는 발송 통계를 추가한다.

## Polling 정책

관리자 화면은 데이터를 두 그룹으로 나누어 갱신한다.

| 영역 | 주기 | 이유 |
|---|---:|---|
| queue/worker/알림 상태 | 5초 | 짧게 변하는 운영 상태라서 빠른 갱신이 필요하다. |
| 공지 source/OCR/랭킹/시설 추가 요청 | 60초 | 외부 크롤링, 집계성 데이터라 매초 갱신할 필요가 작다. |

구현 위치:

- `frontend/src/components/AdminDashboardClient.tsx`
  - `OPERATIONS_POLL_MS = 5000`
  - `SERVICE_POLL_MS = 60000`
- `backend/src/main/java/com/swimpulse/admin/AdminDashboardController.java`
  - `GET /api/admin/dashboard/operations`
  - `GET /api/admin/dashboard/service`

## Redis queue가 0으로 보이는 이유

관리자 화면에서 polling을 넣어도 테스트 푸시를 손으로 20개 정도 넣으면 Redis queue length는 계속 `0`으로 보일 수 있다.

현재 worker 설정이 예를 들어 다음과 같다면:

```text
SWIMPULSE_NOTIFICATION_WORKER_BATCH_SIZE=100
SWIMPULSE_NOTIFICATION_WORKER_DELAY_MS=250
```

worker는 한 번에 최대 100개를 가져가고, 한 번 처리 후 250ms 쉬었다가 다시 돈다.
즉 20개 정도의 테스트 푸시는 worker가 다음 polling 시점 전에 이미 가져가 버릴 가능성이 크다.

따라서 queue length는 “지금 쌓여 있는 대기열 스냅샷”이다.
queue length가 0이라는 뜻은 “알림 작업이 없었다”가 아니라 “현재 밀린 작업이 없다”에 가깝다.

짧은 순간의 작업량까지 보려면 queue length만으로는 부족하고 다음 지표가 필요하다.

- 생성된 알림 수
- `QUEUED`, `SENDING`, `SENT`, `FAILED` 상태별 개수
- delivery lag
- worker 처리량
- 실패율

이번 구현에서는 상태별 알림 통계와 성공률/실패율을 관리자 화면에 추가했다.

## 안전한 운영 액션

관리자 화면에서 상태를 바꾸는 액션은 확인 모달을 거치도록 했다.

현재 bulk action에 해당하는 것은 `SENDING` 상태로 오래 멈춘 알림을 한 번에 재큐잉하는 작업이다.

```text
오래 멈춘 SENDING 알림 N개
-> 관리자가 확인
-> 최대 50개만 QUEUED로 되돌림
-> Redis queue에 다시 publish
```

한 번에 재큐잉 가능한 개수를 제한하는 이유는 다음과 같다.

- 실수로 수천 건을 한 번에 다시 밀어 넣으면 Redis queue, worker, FCM, DB에 순간 부하가 생긴다.
- 실제 장애 복구에서는 “조금씩 되살려서 상태를 본다”가 안전하다.
- 재큐잉 후 다시 실패하는 데이터가 섞여 있을 수 있으므로 작은 단위로 복구하는 편이 원인 파악이 쉽다.

현재 제한값은 API에서 1~50개로 clamp한다.

구현 위치:

- `backend/src/main/java/com/swimpulse/notification/NotificationService.java`
  - `requeueFailed(...)`
  - `requeueStaleSending(..., limit)`
- `backend/src/main/java/com/swimpulse/admin/AdminDashboardController.java`
  - `POST /api/admin/notifications/{notificationId}/requeue`
  - `POST /api/admin/notifications/requeue-stale?limit=50`
- `frontend/src/components/AdminDashboardClient.tsx`
  - 확인 모달
  - 개별 실패 알림 재큐잉
  - 오래 멈춘 `SENDING` bulk 재큐잉

## 알림 발송 통계

queue length보다 사용자 관리 관점에서 더 중요한 것은 실제 알림 처리 결과다.

이번 관리자 화면에는 다음 통계를 추가했다.

| 지표 | 의미 |
|---|---|
| `QUEUED` | 아직 worker가 처리하지 않은 알림 |
| `SENDING` | worker가 가져가 처리 중인 알림 |
| `SENT` | 발송 처리 완료 |
| `FAILED` | 발송 실패 |
| 성공률 | `SENT / (SENT + FAILED)` |
| 실패율 | `FAILED / (SENT + FAILED)` |

구현 위치:

- `backend/src/main/java/com/swimpulse/admin/AdminDashboardResponse.java`
  - `AdminNotificationDeliveryStats`
- `backend/src/main/java/com/swimpulse/admin/AdminDashboardService.java`
  - `deliveryStats()`
- `frontend/src/components/AdminDashboardClient.tsx`
  - “알림 발송 통계” 카드

## 지역별 인기 수영장

기존 인기 수영장은 전체 기준 구독 수 순위였다.
이번에는 구독된 수영장의 `district` 기준으로 지역별 구독 분포도 같이 볼 수 있게 했다.

예:

```text
부천시  120
수원시   80
군포시   35
```

사용자 위치 데이터는 저장하지 않는다.
지역별 인기는 사용자의 실시간 위치가 아니라, 이미 구독된 수영장의 주소/지역 정보 기준 집계다.

구현 위치:

- `backend/src/main/java/com/swimpulse/subscription/DistrictSubscriptionRankingProjection.java`
- `backend/src/main/java/com/swimpulse/subscription/SubscriptionRepository.java`
  - `findDistrictSubscriptionRankings(...)`
- `backend/src/main/java/com/swimpulse/admin/AdminDistrictRankingResponse.java`
- `frontend/src/components/AdminDashboardClient.tsx`
  - “지역별 인기 수영장” 카드

## “이 시설 추가” 요청 승인 흐름

기존 흐름:

```text
사용자 “이 시설 추가”
-> 바로 pools insert 또는 기존 pool 재사용
```

변경 후 흐름:

```text
사용자 “이 시설 추가”
-> pool_add_requests PENDING 저장
-> 관리자 화면에서 승인/반려
-> 승인 시 pools 생성 또는 기존 pool 재사용
-> 승인 후 후처리 실행
   -> 홈페이지 재검증
   -> 대표 이미지 보강
   -> 공지 스캔
```

이렇게 바꾼 이유는 시설 추가가 단순 insert로 끝나지 않기 때문이다.
홈페이지, 공지 경로, 이미지, 공지 기간 파싱 같은 후처리가 필요하고, 잘못된 시설이 바로 사용자 화면에 노출되는 것도 막아야 한다.

구현 위치:

- DB
  - `backend/src/main/resources/db/migration/V15__add_pool_add_requests.sql`
- Backend
  - `backend/src/main/java/com/swimpulse/pool/PoolAddRequest.java`
  - `backend/src/main/java/com/swimpulse/pool/PoolAddRequestService.java`
  - `backend/src/main/java/com/swimpulse/pool/PoolController.java`
  - `backend/src/main/java/com/swimpulse/admin/AdminDashboardController.java`
- Frontend
  - `frontend/src/components/DashboardClient.tsx`
    - 기존 “DB에 추가”를 “추가 요청”으로 변경
  - `frontend/src/components/AdminDashboardClient.tsx`
    - 시설 추가 요청 목록
    - 승인
    - 반려
    - 승인 후 후처리

## 추가 API

| Method | Path | 용도 |
|---|---|---|
| `GET` | `/api/admin/dashboard/operations` | queue/worker/알림 상태 polling |
| `GET` | `/api/admin/dashboard/service` | 공지/OCR/랭킹/시설 요청 polling |
| `POST` | `/api/admin/notifications/{notificationId}/requeue` | 실패 알림 1건 재큐잉 |
| `POST` | `/api/admin/notifications/requeue-stale?limit=50` | 오래 멈춘 `SENDING` 알림 bulk 재큐잉 |
| `POST` | `/api/admin/pool-add-requests/{requestId}/approve` | 시설 추가 요청 승인 |
| `POST` | `/api/admin/pool-add-requests/{requestId}/reject` | 시설 추가 요청 반려 |
| `POST` | `/api/admin/pool-add-requests/{requestId}/postprocess` | 승인된 시설 후처리 |
| `POST` | `/api/pools/from-location-candidate` | 이제 바로 pool 생성이 아니라 시설 추가 요청 생성 |

## 검증 결과

실행한 검증:

```text
backend:
.\gradlew.bat test

frontend:
npm run lint
npm run build
```

결과:

| 항목 | 결과 |
|---|---|
| Backend test | 성공 |
| Frontend lint | 성공 |
| Frontend production build | 성공 |

## 남은 고려사항

- 시설 추가 요청은 승인 전 중복 요청 방지 정책을 더 촘촘히 만들 수 있다.
  - 예: 같은 title/address의 `PENDING` 요청이 있으면 재요청 제한
- 승인 후 후처리는 외부 호출이 포함되므로 관리자에게 실행 결과와 실패 원인을 더 자세히 보여줄 수 있다.
- 장기적으로 bulk action은 작업 이력 테이블을 두는 편이 좋다.
  - 누가, 언제, 몇 건을 재큐잉했는지 남기면 운영 감사가 쉬워진다.
- queue length는 순간 스냅샷이므로, worker 처리량과 delivery lag 그래프를 같이 보는 것이 좋다.
