# 관리자 페이지 3단계 운영 편의 기능 구현 정리

작성일: 2026-06-24

## 목적

관리자 페이지 1~2단계에서 읽기 전용 대시보드와 안전한 운영 액션을 추가했다.
이번 3단계에서는 운영 액션의 결과를 화면 메시지로만 보여주는 수준을 넘어, 누가 어떤 작업을 했는지 DB에 남기고 관리자 화면에서 확인할 수 있게 했다.

핵심 목표:

- 관리자 액션 성공/실패를 `admin_action_logs`에 저장한다.
- 실패한 액션도 로그가 남도록 별도 트랜잭션으로 기록한다.
- 관리자 대시보드에서 최근 작업 로그를 볼 수 있게 한다.
- 운영 polling 영역에 작업 로그를 포함해 5초마다 갱신한다.

## 구현 요약

### 새 테이블

`admin_action_logs` 테이블을 추가했다.

```text
admin_action_logs
- id
- admin_user_id
- action_type
- target_type
- target_id
- result_status: SUCCESS / FAILED
- message
- created_at
```

마이그레이션:

- `backend/src/main/resources/db/migration/V16__add_admin_action_logs.sql`

인덱스:

| 인덱스 | 목적 |
|---|---|
| `(created_at DESC, id DESC)` | 최근 로그 조회 |
| `(result_status, created_at DESC, id DESC)` | 성공/실패 필터 |
| `(action_type, created_at DESC, id DESC)` | 작업 종류 필터 |

## 로그 저장 방식

로그 저장은 `AdminActionLogService`에서 처리한다.

```text
관리자 액션 실행
-> 성공하면 SUCCESS 로그 저장
-> 실패하면 FAILED 로그 저장
-> 예외는 다시 throw
```

중요한 점은 로그 저장을 `REQUIRES_NEW` 트랜잭션으로 분리했다는 것이다.

이유:

```text
액션 본 트랜잭션이 실패해서 rollback 되더라도
“실패했다”는 사실은 audit log에 남아야 한다.
```

구현 위치:

- `backend/src/main/java/com/swimpulse/admin/AdminActionLog.java`
- `backend/src/main/java/com/swimpulse/admin/AdminActionLogRepository.java`
- `backend/src/main/java/com/swimpulse/admin/AdminActionLogService.java`
- `backend/src/main/java/com/swimpulse/admin/AdminActionLogResponse.java`

## 로그 대상 액션

현재 로그를 남기는 관리자 액션:

| 액션 | action_type | target_type |
|---|---|---|
| 실패 알림 단건 재큐잉 | `REQUEUE_FAILED_NOTIFICATION` | `NOTIFICATION` |
| 오래 멈춘 SENDING 재큐잉 | `REQUEUE_STALE_NOTIFICATIONS` | `NOTIFICATION` |
| 시설 추가 요청 승인 | `APPROVE_POOL_ADD_REQUEST` | `POOL_ADD_REQUEST` |
| 시설 추가 요청 반려 | `REJECT_POOL_ADD_REQUEST` | `POOL_ADD_REQUEST` |
| 시설 후처리 전체 실행 | `POSTPROCESS_POOL_ADD_REQUEST` | `POOL_ADD_REQUEST` |
| 홈페이지/공지 후보 재검증 | `POSTPROCESS_POOL_ADD_REQUEST_HOMEPAGE` | `POOL_ADD_REQUEST` |
| 대표 이미지 보강 | `POSTPROCESS_POOL_ADD_REQUEST_IMAGE` | `POOL_ADD_REQUEST` |
| 상세 공지 스캔 | `POSTPROCESS_POOL_ADD_REQUEST_NOTICES` | `POOL_ADD_REQUEST` |

컨트롤러에서는 공통 `audited(...)` 래퍼로 성공/실패 기록을 처리한다.

구현 위치:

- `backend/src/main/java/com/swimpulse/admin/AdminDashboardController.java`

## 관리자 API

추가 API:

```text
GET /api/admin/action-logs
```

지원 파라미터:

```text
actionType
resultStatus
limit
```

예:

```text
GET /api/admin/action-logs?resultStatus=FAILED&limit=20
GET /api/admin/action-logs?actionType=REQUEUE_FAILED_NOTIFICATION&limit=10
```

또한 기존 대시보드 응답에도 최근 작업 로그가 포함된다.

```text
GET /api/admin/dashboard
GET /api/admin/dashboard/operations
```

응답 필드:

```text
recentActionLogs
```

## 프론트 구현

관리자 페이지 운영 섹션에 `관리자 작업 로그` 패널을 추가했다.

표시 항목:

| 컬럼 | 설명 |
|---|---|
| 결과 | 성공/실패 |
| 작업 | 사람이 읽기 쉬운 액션 이름 |
| 대상 | target type + target id |
| 관리자 | admin email |
| 시각 | createdAt |
| 메시지 | 성공/실패 메시지 |

로그 패널에는 최소 필터도 추가했다.

| 필터 | 설명 |
|---|---|
| 결과 | 전체 / 성공 / 실패 |
| Action type | `REQUEUE_FAILED_NOTIFICATION` 같은 action type 직접 입력 |

구현 위치:

- `frontend/src/components/AdminDashboardClient.tsx`
  - `AdminActionLogTable`
  - `AdminActionLogFilters`
  - 운영 섹션에 작업 로그 패널 추가
- `frontend/src/lib/types.ts`
  - `AdminActionLog`
  - `AdminActionResultStatus`
- `frontend/src/lib/api.ts`
  - `getAdminActionLogs(...)`

## Toast/Notice와 Audit Log 차이

기존 화면 메시지:

```text
알림 #123을 재큐잉했습니다.
시설 추가 요청을 반려했습니다.
후처리를 완료했습니다.
```

이것은 사용자가 지금 누른 액션 결과를 즉시 알려주는 UI 메시지다.
현재 코드에서는 `notice` 상태값으로 구현되어 있다.

```text
toast/notice = 지금 화면에서 보여주는 일회성 안내
audit log = DB에 남는 운영 기록
```

이번 구현으로 `toast/notice`와 `audit log`가 모두 갖춰졌다.

## 운영상 의미

이제 SQL 없이도 관리자 화면에서 다음을 확인할 수 있다.

- 누가 시설 추가 요청을 승인했는지
- 누가 반려했는지
- 어떤 알림을 재큐잉했는지
- 어떤 후처리 단계가 실행됐는지
- 어떤 관리자 액션이 실패했는지

장애 대응 시에는 `FAILED` 로그를 보면 어떤 작업이 실패했는지 빠르게 볼 수 있다.

## 아직 남은 3단계 후보

이번 구현은 3단계 중 `audit log` 중심이다.
아래 기능은 다음 단계 후보로 남겨둔다.

| 기능 | 상태 | 이유 |
|---|---|---|
| 상태별 필터 UI | 일부 구현 | 관리자 작업 로그의 성공/실패 필터 구현 |
| action type 필터 UI | 일부 구현 | 관리자 작업 로그에서 action type 직접 입력 필터 구현 |
| pool/user/event 검색 | 미구현 | 별도 검색 UX와 API 필요 |
| 기간 필터 | 미구현 | createdAt 범위 검색 API 필요 |
| CSV export | 미구현 | 운영 분석용으로 추후 추가 가능 |
| Grafana 링크 | 미구현 | 대시보드 URL 확정 후 연결 가능 |

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

## 판단

이번 단계로 관리자 페이지는 단순 조회 화면에서 운영 이력을 남기는 화면으로 발전했다.

특히 `REQUIRES_NEW` 기반 audit log는 운영 액션 실패까지 남길 수 있으므로, RabbitMQ나 더 복잡한 운영 도구를 도입하기 전 현재 Redis Queue + DB 상태 관리 구조의 관측성을 한 단계 보강한다.
