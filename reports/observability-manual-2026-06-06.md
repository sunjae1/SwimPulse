# SwimPulse 동시성 설명 + 관측 스택 매뉴얼

작성일: 2026-06-06

성능 측정 방법만 빠르게 보고 싶다면 [performance-measurement-manual-2026-06-06.md](./performance-measurement-manual-2026-06-06.md)를 보면 됩니다.

![관측 흐름](./assets/observability-flow.svg)

## 1. 먼저 헷갈리는 개념 정리

### `registration_events`와 `subscriptions`는 역할이 다릅니다

- `registration_events`
  수영장의 "모집 기간 이벤트 그 자체"를 저장하는 테이블입니다.
- `subscriptions`
  어떤 사용자가 어떤 이벤트를 구독하는지를 저장하는 연결 테이블입니다.
- `notifications`
  특정 사용자에게 실제로 생성된 알림입니다.

### 왜 "같은 이벤트가 중복 생성"될 수 있다고 한 건가

사용자가 다르더라도 `event` row는 사용자별로 따로 만들 필요가 없습니다.

정상적인 모습은 이렇습니다.

```text
registration_events
- event_id=101, pool_id=7, title="6월 신규회원", starts_at=..., ends_at=...

subscriptions
- user_id=1 -> event_id=101
- user_id=2 -> event_id=101
```

문제가 생기면 이렇게 됩니다.

```text
registration_events
- event_id=101, pool_id=7, title="6월 신규회원", starts_at=..., ends_at=...
- event_id=102, pool_id=7, title="6월 신규회원", starts_at=..., ends_at=...

subscriptions
- user_id=1 -> event_id=101
- user_id=2 -> event_id=102
```

즉 "유저가 다르니까 event도 따로 만든다"가 아니라, 원래는 같은 `event`를 여러 유저가 공유해야 하는데, 동시 요청 때문에 같은 이벤트가 2개 생기는 게 문제입니다.

### DB 쪽에서 막는다는 건 뭘 의미하나

보통 2단계로 막습니다.

- 1단계:
  애플리케이션이 "기존 이벤트가 있으면 재사용"하도록 처리합니다.
- 2단계:
  DB가 마지막 안전장치로 동일 이벤트 중복 insert를 거부하게 합니다.

이번에 넣은 것은 1단계 보강입니다.

- [SubscriptionService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/subscription/SubscriptionService.java)
- [EventService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/EventService.java)
- [PoolRepository.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/pool/PoolRepository.java)

핵심은 `pool` row를 `PESSIMISTIC_WRITE`로 잠그고, 같은 수영장에 대한 이벤트 생성 구간을 직렬화한 것입니다.

추가로 더 단단하게 하려면 나중에 아래 유니크 인덱스를 추천합니다.

```sql
create unique index uk_registration_event_identity
on registration_events (pool_id, title, registration_starts_at, registration_ends_at);
```

단, 이 인덱스를 넣기 전에는 기존 중복 데이터가 없는지 먼저 확인해야 합니다.

### `service level 처리`는 에러 핸들링이랑 같은 말인가

완전히 같지는 않습니다.

- 에러 핸들링:
  예외가 났을 때 메시지와 상태코드를 어떻게 줄지
- 서비스 레벨 처리:
  서비스 메서드 안에서 락을 잡고, 기존 데이터를 찾고, 중복이면 재사용하고, 필요하면 예외를 바꾸는 전체 비즈니스 로직

즉 `service level 처리`는 "애플리케이션 단에서 중복 생성 가능성을 줄이는 로직" 전체를 뜻합니다.

### `pod`는 뭐냐

- 쿠버네티스에서 애플리케이션이 실행되는 기본 단위입니다.
- 실무적으로는 "백엔드 서버 인스턴스 하나" 정도로 이해해도 됩니다.

예를 들면 아래는 같은 뜻으로 봐도 됩니다.

- 백엔드 컨테이너 2개
- 백엔드 인스턴스 2대
- 백엔드 pod 2개

### `Redis 분산 락`은 뭐냐

- 여러 백엔드 인스턴스가 동시에 같은 작업을 하지 못하게 잠깐 자물쇠를 거는 방식입니다.
- Redis에 `lock:event-scheduler` 같은 key를 먼저 선점한 인스턴스만 작업합니다.
- 지금 구현은 [RedisLockService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/common/RedisLockService.java)에서 `SET NX + TTL` 형태로 동작합니다.

이번에 적용한 곳은 2개입니다.

- 이벤트 스케줄러 락:
  [EventScheduler.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/EventScheduler.java)
- pool별 공지 스캔 락:
  [NoticeCrawlerService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java)

### 동시 공지 스캔이 왜 문제인가

같은 수영장에 대해 두 사용자가 동시에 `scan`을 누르면 아래 문제가 생길 수 있습니다.

- 같은 홈페이지/공지 목록/상세 페이지를 중복 fetch
- 같은 공지를 중복 저장 시도
- OpenAI fallback이 켜져 있으면 비용도 중복 발생
- 응답 시간도 더 느려짐

그래서 지금은 같은 `poolId`에 대한 스캔이 이미 진행 중이면 두 번째 요청은 바로 막습니다.

### `NotificationWorker`는 30초마다 아니었나

아닙니다. 두 스케줄러가 서로 다릅니다.

- 이벤트 스케줄러:
  30초마다 실행
  `registration_events` 상태 갱신 + 알림 생성/큐잉
- 알림 워커:
  1초마다 실행
  Redis queue에서 알림 ID를 꺼내 FCM 발송

현재 설정값은 아래입니다.

- [application.properties](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/application.properties:42)
- [application.properties](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/application.properties:44)

정리하면:

```text
30초 주기 -> "이제 어떤 알림을 만들까?"
1초 주기 -> "큐에 들어간 알림을 실제로 보내자"
```

### 공지 선행 수집, 백그라운드 적재형, 배치는 같은 말인가

거의 같은 방향입니다.

- 사용자가 눌렀을 때 즉석에서 풀스캔하는 대신
- 스케줄러나 배치가 미리 수집해서 DB에 저장해두고
- 사용자는 저장된 결과를 빠르게 보는 구조

즉 "배치성 선행 수집"이라고 이해하면 됩니다.

### bounding box는 왜 나오나

위경도 근처 검색에서 모든 row에 거리 계산을 걸면 비쌉니다.

그래서 먼저:

- 위도/경도 기준으로 "대략 이 사각형 안에 있는 후보"만 고르고
- 그 후보들에만 정확한 거리 계산을 합니다

이 사각형이 bounding box입니다.

## 2. 이번에 실제로 구현한 것

- 마이페이지에서 알림 `읽음 처리` 버튼 추가
  [MyPageClient.tsx](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/frontend/src/components/MyPageClient.tsx)
- 이미 읽은 알림은 `readAt`을 다시 덮어쓰지 않도록 처리
  [NotificationService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notification/NotificationService.java)
- 같은 수영장 이벤트 생성 경쟁을 `pool` row 락으로 직렬화
- 멀티 인스턴스 이벤트 스케줄러 중복 실행 방지용 Redis 분산 락
- 같은 pool 공지 스캔 동시 실행 방지용 Redis 분산 락
- `Spring Actuator + Prometheus + Grafana` 실행용 `docker-compose`와 설정 파일

## 3. 관측 스택에서 각 도구가 하는 일

- Spring Actuator:
  애플리케이션 내부 상태와 메트릭을 노출합니다.
- Micrometer:
  메트릭 표준 계층입니다. Spring Boot 메트릭을 Prometheus 형식으로 연결해 줍니다.
- Prometheus:
  주기적으로 메트릭을 긁어와 저장합니다.
- Grafana:
  Prometheus 데이터를 보기 좋은 대시보드로 보여줍니다.

중요한 포인트:

- `Actuator/Micrometer/Grafana`만 있으면 성능 "관측"은 됩니다.
- 하지만 실제 부하를 주는 "성능 테스트" 도구는 아닙니다.

그래서 추천 조합은:

```text
관측: Actuator + Micrometer + Prometheus + Grafana
부하 생성: k6
```

즉 지금 구성은 "몸에 센서를 붙이는 것"이고, `k6`는 "달리기 테스트를 시키는 것"입니다.

## 4. 이번에 추가된 운영 파일

- [docker-compose.yml](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/docker-compose.yml)
- [backend/build.gradle](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/build.gradle)
- [backend/src/main/resources/application.properties](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/application.properties)
- [ops/prometheus/prometheus.yml](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/ops/prometheus/prometheus.yml)
- [ops/grafana/provisioning/datasources/datasource.yml](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/ops/grafana/provisioning/datasources/datasource.yml)
- [ops/grafana/provisioning/dashboards/dashboards.yml](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/ops/grafana/provisioning/dashboards/dashboards.yml)
- [ops/grafana/dashboards/swimpulse-overview.json](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/ops/grafana/dashboards/swimpulse-overview.json)

## 5. 실행 방법

### 1) 스택 올리기

루트에서 실행합니다.

```powershell
docker compose up -d --build
```

### 2) 접속 주소

- Backend API: `http://localhost:8080`
- Backend health: `http://localhost:8080/actuator/health`
- Backend metrics list: `http://localhost:8080/actuator/metrics`
- Backend Prometheus scrape endpoint: `http://localhost:8080/actuator/prometheus`
- Prometheus UI: `http://localhost:9090`
- Grafana UI: `http://localhost:3001`

### 3) Grafana 로그인

- ID: `admin`
- Password: `swimpulse-admin`

로그인 후 바로 `SwimPulse / SwimPulse Overview` 대시보드가 자동으로 잡혀야 정상입니다.

![대시보드 예시](./assets/grafana-dashboard-example.svg)

## 6. 초심자 기준 사용 순서

### A. 백엔드가 살아있는지 먼저 본다

브라우저에서 아래를 엽니다.

```text
http://localhost:8080/actuator/health
```

정상이라면 대략 이런 형태가 나옵니다.

```json
{
  "status": "UP"
}
```

### B. Prometheus가 메트릭을 긁고 있는지 본다

브라우저에서 아래를 엽니다.

```text
http://localhost:9090/targets
```

여기서 `swimpulse-backend` target이 `UP`이면 scrape가 성공한 것입니다.

### C. Prometheus에서 직접 질의해 본다

Prometheus UI의 `Graph` 탭에서 아래 같은 쿼리를 넣어봅니다.

```promql
http_server_requests_seconds_count
```

좀 더 실전적인 쿼리는 아래입니다.

```promql
sum(rate(http_server_requests_seconds_count[5m]))
```

근처 수영장 API의 p95는 아래처럼 볼 수 있습니다.

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{uri="/api/pools/nearby"}[5m])
  )
)
```

### D. Grafana에서 대시보드로 본다

`http://localhost:3001` 로그인 후 아래 순서로 봅니다.

1. `Dashboards`
2. `SwimPulse`
3. `SwimPulse Overview`

## 7. 대시보드에서 뭘 보면 되나

### `HTTP 요청량`

- 지금 서비스에 요청이 얼마나 들어오는지
- 부하 테스트 전후 트래픽 차이 확인

### `근처 수영장 p95 지연`

- 위치 기반 조회의 체감 지연을 보기에 좋습니다.
- 평균보다 `p95`가 중요합니다.

### `공지 스캔 p95 지연`

- 느려졌는지 가장 빨리 체감할 수 있는 패널입니다.
- 크롤링 사이트 상태나 OpenAI fallback 영향이 보일 수 있습니다.

### `핵심 API 요청량`

- `/api/pools/nearby`
- `/api/pools/{poolId}/notices/scan`
- `/api/locations/search`

세 API를 한 화면에서 비교할 수 있습니다.

### `JVM Heap`

- 메모리 사용이 계속 오른 뒤 내려오지 않으면 누수 의심
- GC 튀는 패턴 확인 가능

### `Process CPU`

- 크롤링이나 위치 조회 부하 때 CPU가 얼마나 오르는지 확인

## 8. 성능 테스트를 실제로 하려면

이 스택만으로는 "보는 것"까지만 됩니다. 실제 부하를 주려면 `k6`를 추천합니다.

추천 이유:

- 스크립트 관리가 쉽습니다.
- HTTP API 부하 테스트에 가볍고 빠릅니다.
- Prometheus/Grafana와 같이 보기 좋습니다.

추천 흐름:

```text
1. docker compose up -d
2. Grafana 열어둠
3. k6로 /api/pools/nearby, /api/pools/{poolId}/notices/scan 부하 테스트
4. Grafana에서 p95, req/s, CPU, heap을 비교
5. 코드 개선 후 같은 k6 스크립트 재실행
```

원하시면 다음 턴에서 `k6` 스크립트까지 저장소에 바로 추가할 수 있습니다.

## 9. 지금 프로젝트에서 바로 체크해볼 시나리오

### 시나리오 1: 근처 수영장 조회

1. Grafana 대시보드를 열어 둡니다.
2. 프론트에서 근처 수영장 조회를 여러 번 실행합니다.
3. `근처 수영장 p95 지연`과 `핵심 API 요청량`을 봅니다.

### 시나리오 2: 공지 스캔 조회

1. Grafana 대시보드를 열어 둡니다.
2. 프론트에서 공지 스캔을 여러 번 실행합니다.
3. `공지 스캔 p95 지연`이 튀는지 봅니다.

### 시나리오 3: 동시 스캔 보호 확인

1. 같은 수영장에 대해 거의 동시에 공지 스캔 요청을 두 번 보냅니다.
2. 하나는 실행되고, 다른 하나는 "이미 진행 중" 메시지로 빠르게 실패하는지 확인합니다.

## 10. 운영 주의사항

- 지금 Grafana 계정은 로컬 편의용 고정값입니다.
- 운영에서는 비밀번호를 바꾸는 게 좋습니다.
- `/actuator/prometheus`는 외부 공개망에 그대로 노출하지 않는 걸 추천합니다.
- 멀티 인스턴스 환경에서는 Redis가 락 저장소 역할도 하므로 안정성이 중요합니다.

## 11. 한줄 권장안

- 지금 단계 최적 조합:
  `Actuator + Micrometer + Prometheus + Grafana + k6`
- 이번 턴 구현 범위:
  `Actuator + Micrometer + Prometheus + Grafana`
- 다음 턴 추천:
  `k6 스크립트 추가 + 공지 스캔/nearby 전용 성능 시나리오 저장`
