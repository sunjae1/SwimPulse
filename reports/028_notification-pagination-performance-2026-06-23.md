# 알림 목록 Pagination 적용 및 부하 테스트 결과

## 목적

기존 `/api/notifications`는 로그인 사용자의 알림 row 전체를 배열로 반환했다. 테스트 데이터가 600건 이상 쌓여도 즉시 장애는 없었지만, 운영에서 사용자별 알림이 수천 건 이상 누적되면 응답 크기와 DB 조회 비용이 계속 증가하는 구조였다.

이번 변경의 목적은 다음과 같다.

- 알림 목록 조회를 페이지 단위로 제한한다.
- 전체 알림 수와 안 읽은 알림 수는 유지한다.
- 마이페이지와 대시보드가 전체 row를 받지 않아도 동일한 요약 정보를 표시하게 한다.
- k6로 pagination 전후 목록 조회 성능을 비교한다.

## 구현 요약

### 1. API 응답 구조 변경

기존:

```json
[
  { "id": 1, "title": "..." },
  { "id": 2, "title": "..." }
]
```

변경:

```json
{
  "content": [
    { "id": 1, "title": "..." },
    { "id": 2, "title": "..." }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 718,
  "totalPages": 36,
  "first": true,
  "last": false,
  "unreadElements": 718
}
```

코드 흐름:

```java
@GetMapping
public NotificationPageResponse findNotifications(
    @AuthenticationPrincipal AuthenticatedUser user,
    @RequestParam(required = false) Integer page,
    @RequestParam(required = false) Integer size
) {
    return notificationService.findByUser(user.id(), page, size);
}
```

```java
Page<Notification> notifications =
    notificationRepository.findByUser_IdOrderByCreatedAtDesc(
        userId,
        PageRequest.of(page, size)
    );
```

### 2. DB 인덱스 추가

최신 알림 목록 조회와 unread count를 보호하기 위해 인덱스를 추가했다.

```sql
CREATE INDEX idx_notifications_user_created_at
    ON notifications (user_id, created_at DESC, id DESC);

CREATE INDEX idx_notifications_user_read_at
    ON notifications (user_id, read_at);
```

### 3. 프론트 변경

대시보드는 첫 페이지 20개만 렌더링한다. 대신 배지와 요약에는 `totalElements`, `unreadElements`를 사용한다.

```ts
export async function getNotificationPage(page = 0, size = 20): Promise<NotificationPage> {
  return request<NotificationPage>(`/api/notifications?page=${page}&size=${size}`);
}
```

마이페이지도 최근 알림 20개만 내려받고, 전체 알림 수와 안 읽은 알림 수는 DB count 기반 metrics로 표시한다.

## k6 변경

`notification-test-load.js`는 이제 알림 목록 응답이 배열인지 검사하지 않고 page 응답인지 검사한다.

추가 메트릭:

| 메트릭 | 의미 |
|---|---|
| `notification_test_list_count` | 실제 응답 content 개수 |
| `notification_test_list_total_count` | DB 기준 전체 알림 수 |
| `notification_test_list_unread_count` | DB 기준 안 읽은 알림 수 |
| `notification_test_list_duration` | `/api/notifications?page=0&size=20` 조회 시간 |

## After 실행 명령

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=notification-list-pagination-after `
  -e VUS=3 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_ID=1 `
  -e TITLE="k6 notification test subscription" `
  -e REGISTER_DEVICE=true `
  -e LIST_AFTER_QUEUE=true `
  -e LIST_PAGE=0 `
  -e LIST_PAGE_SIZE=20 `
  -e LOADTEST_TOKEN_COUNT=3 `
  k6 run /scripts/notification-test-load.js `
  --summary-export /results/notification-list-pagination-after-20260623-summary.json `
  --out json=/results/notification-list-pagination-after-20260623-raw.json
```

## 성능 비교

비교 기준:

- Before: `ops/k6/results/notification-list-after-20260623-summary.json`
- After: `ops/k6/results/notification-list-pagination-after-20260623-summary.json`

| 구분 | 실패율 | 목록 응답 평균 건수 | 전체 알림 평균 건수 | 목록 조회 avg | 목록 조회 p95 | 목록 조회 p99 | HTTP p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Before, 전체 배열 | 0% | 453.60 | - | 41.48ms | 60.31ms | 85.39ms | 53.75ms |
| After, page size 20 | 0% | 20.00 | 718.00 | 16.51ms | 22.91ms | 30.39ms | 51.12ms |

개선율:

| 지표 | 변화 |
|---|---:|
| 목록 조회 avg | 약 60.2% 감소 |
| 목록 조회 p95 | 약 62.0% 감소 |
| 목록 조회 p99 | 약 64.4% 감소 |

## 해석

pagination 전에는 알림 row가 쌓일수록 응답 배열 자체가 커졌다. 테스트에서도 목록 최대 663건 수준에서 p95가 60.31ms까지 올랐다.

pagination 후에는 전체 알림 수가 평균 718건인 상태에서도 실제 응답 content는 20건으로 고정됐다. 그 결과 목록 조회 p95가 22.91ms로 내려갔다.

즉, 현재 데이터 규모에서도 유의미한 차이가 있고, 운영에서 사용자별 알림이 수천 건 이상 누적될수록 효과가 더 커질 가능성이 높다.

## 남은 과제

- 프론트에서 다음 페이지 보기 UI가 필요하면 `page + 1` 요청 버튼을 추가한다.
- 현재 마이페이지는 최근 알림 20개 중 8개만 보여준다. 전체 목록 화면을 따로 만들면 pagination UI를 붙이는 편이 좋다.
- 알림 삭제/보관 기능을 넣으면 장기적으로 DB row 증가도 제어할 수 있다.
