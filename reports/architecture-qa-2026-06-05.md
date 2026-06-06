# SwimPulse 기술 점검 보고서

작성일: 2026-06-05

## 한줄 결론

- 최근 알림의 `안 읽음`은 "아직 읽음 처리되지 않음"이고, "푸시가 아직 안 갔음"과는 다른 상태입니다.
- Redis는 현재 알림 발송용 큐에만 쓰고 있고, 캐시로는 쓰지 않고 있습니다.
- 이 프로젝트가 개인별 시간 기반 구독이라고 해도 레이스 컨디션 가능성은 있습니다. 특히 `이벤트 중복 생성`, `멀티 인스턴스 중복 큐잉`, `동시 공지 스캔`은 실제 후보입니다.
- 홈페이지 후보는 현재 네이버 지역 검색 API 결과로 찾습니다.
- 네이버 지도 리뷰는 현재 코드로 접근하지 않고 있고, 제가 확인한 공식 공개 문서 범위에서도 공개 리뷰 조회 API는 보이지 않았습니다. 이 부분은 "공개 문서상 확인되지 않았다"는 의미로 이해하는 게 가장 정확합니다.
- 공지 크롤링은 지금 배치/사전 적재가 아니라, 사용자가 요청할 때 동기 실행되는 구조라 체감상 느릴 수 있습니다.

## 1. 질문별 답변

### 1) 최근 알림의 `안 읽음`은 무엇인가

- 서버 기준으로는 `readAt == null`이면 안 읽음입니다. 근거: [MyPageResponse.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/mypage/MyPageResponse.java:32), [Notification.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notification/Notification.java:102)
- 프론트도 같은 기준으로 `readAt`이 없으면 `안 읽음`, 있으면 `읽음` 배지를 보여줍니다. 근거: [MyPageClient.tsx](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/frontend/src/components/MyPageClient.tsx:723)
- 이 값은 전송 상태와 별개입니다. 즉 `SENT + 안 읽음`도 가능하고, `FAILED + 안 읽음`도 가능합니다. 상태 배지는 `QUEUED/SENT/FAILED`, 읽음 배지는 `readAt`으로 따로 표현합니다. 근거: [NotificationResponse.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notification/NotificationResponse.java), [MyPageClient.tsx](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/frontend/src/components/MyPageClient.tsx:720)
- 추가로, 읽음 처리는 별도 API로 존재하고 대시보드에서 호출됩니다. 마이페이지 리스트 자체는 현재 배지를 렌더링하는 쪽에 가깝습니다. 근거: [api.ts](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/frontend/src/lib/api.ts:319), [DashboardClient.tsx](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/frontend/src/components/DashboardClient.tsx:525)

### 2) 현재 Redis는 어디에 쓰고 있나

- 현재 확인된 Redis 사용처는 알림 큐 하나입니다.
- 알림이 생성되면 Redis List에 `notificationId`를 `rightPush`합니다. 근거: [NotificationQueuePublisher.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notification/NotificationQueuePublisher.java:25)
- 워커는 1초마다 Redis List에서 `leftPop`으로 최대 20개를 꺼내 발송합니다. 근거: [NotificationWorker.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notification/NotificationWorker.java:31), [application.properties](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/application.properties:34)
- 코드 기준으로 `@Cacheable`, `CacheManager`, Redis key-value 캐시 사용은 보이지 않았습니다. 즉 지금 프로젝트는 "Redis queue는 있음, Redis caching은 없음" 상태입니다.

### 3) Redis queue와 Redis caching의 차이

- Redis queue:
  작업을 순서대로 넣고 소비자가 꺼내 처리하는 용도입니다. 한 번 처리하면 사라지는 성격이 강합니다. 이 프로젝트에서는 "알림을 언제 보낼지"가 아니라 "보낼 알림 ID를 워커가 소비하도록 넘기는 통로"로 씁니다.
- Redis caching:
  비싼 계산이나 외부 호출 결과를 잠깐 저장해 두고 같은 요청이 오면 빠르게 재사용하는 용도입니다. TTL, 무효화, 캐시 히트율이 중요합니다.
- 이 프로젝트 예시:
  현재는 `swimpulse:notifications` 같은 큐 성격만 있습니다.
- 캐시로 바꾸면 좋아 보이는 후보:
  `근처 수영장 조회 결과`, `주소 지오코딩 결과`, `공지 스캔 결과`, `홈페이지 후보 탐색 결과`

### 4) 이 프로젝트에서 레이스 컨디션이 날 만한 경우가 있나

- 결론부터 말하면 "있습니다". 지금은 개인 구독 중심이라 체감 빈도는 낮을 수 있지만, 구조상 후보는 분명합니다.

- 경우 1: 같은 기간 이벤트의 중복 생성
  `SubscriptionService`는 `(poolId, title, startsAt, endsAt)`로 기존 이벤트를 먼저 찾고, 없으면 새 `RegistrationEvent`를 만듭니다. 그런데 `registration_events` 테이블에는 이 조합에 대한 유니크 제약이 없습니다. 동시에 같은 요청이 2개 들어오면 논리적으로 같은 기간 이벤트가 2개 생길 수 있습니다. 근거: [SubscriptionService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/subscription/SubscriptionService.java), [RegistrationEvent.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/RegistrationEvent.java:19), [V1__baseline.sql](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/db/migration/V1__baseline.sql:158)

- 경우 2: 구독은 유니크지만, 이벤트가 나뉘면 중복 알림 세트가 생길 수 있음
  `subscriptions`는 `(user_id, event_id)` 유니크 제약이 있어서 같은 이벤트에 대한 중복 구독은 막습니다. 하지만 위의 경우처럼 같은 논리 이벤트가 서로 다른 `event_id`로 2개 생기면, 사용자 입장에서는 같은 기간을 다른 이벤트로 각각 구독하게 될 여지가 있습니다. 근거: [Subscription.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/subscription/Subscription.java:18), [V1__baseline.sql](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/db/migration/V1__baseline.sql:211)

- 경우 3: 멀티 인스턴스에서 스케줄러 중복 큐잉
  이벤트 스케줄러는 30초마다 돌면서 `reminderQueued`, `startQueued` 플래그를 보고 알림을 생성합니다. 이 플래그는 일반 boolean 필드이고, 분산 락이나 낙관적 락 버전 필드는 보이지 않습니다. 앱 인스턴스가 2대 이상이면 같은 이벤트에 대해 동시에 큐잉할 가능성을 배제하기 어렵습니다. 근거: [EventScheduler.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/EventScheduler.java:18), [EventService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/EventService.java), [RegistrationEvent.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/RegistrationEvent.java)

- 경우 4: 동시 공지 스캔 시 중복 저장/중복 작업
  공지 스캔은 먼저 URL로 기존 공지를 찾지만, `pool_notices.url`에 유니크 제약은 없습니다. 같은 수영장에 대해 동시에 `scan`이 들어오면 같은 URL 공지를 각각 새로 저장할 가능성이 있습니다. 최소한 중복 네트워크 작업은 발생합니다. 근거: [NoticeCrawlerService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java:155), [PoolNoticeRepository.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/PoolNoticeRepository.java:8), [V1__baseline.sql](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/db/migration/V1__baseline.sql:95)

- 경우 5: Redis 큐 소비 중 예외가 나면 유실 리스크
  이건 엄밀히는 레이스 컨디션보다는 신뢰성 이슈에 가깝지만 중요합니다. 워커는 Redis에서 `leftPop`으로 먼저 꺼낸 뒤 처리하고, 처리 중 예외가 나면 catch에서 로그만 남기고 끝납니다. 즉 pop 이후 예외면 큐 아이템은 사라졌는데 재큐잉이 안 될 수 있습니다. 근거: [NotificationWorker.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notification/NotificationWorker.java:34)

### 5) 홈페이지를 네이버 검색 API로 찾는가

- 네, 현재는 네이버 지역 검색 API 결과를 이용합니다.
- 코드상 엔드포인트는 `https://openapi.naver.com/v1/search/local.json`입니다. 근거: [NaverLocalSearchClient.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/location/NaverLocalSearchClient.java:18)
- `PoolService`가 수영장 이름으로 후보를 검색하고, 이름/주소/카테고리 기준으로 가장 그럴듯한 후보를 골라 홈페이지를 반영합니다. 근거: [PoolService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/pool/PoolService.java:167)
- 한 가지 관찰 포인트는, 현재 검색 정렬이 `sort=random`입니다. 그래서 반복 실행 시 후보 순서가 흔들릴 수 있고, 자동 보정 로직의 재현성도 낮아질 수 있습니다. 근거: [NaverLocalSearchClient.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/location/NaverLocalSearchClient.java:40)

### 6) 네이버 지도 리뷰 같은 것은 검색 자체가 불가능한가

- 현재 코드로는 불가능합니다. 리뷰를 가져오는 클라이언트나 파서가 없습니다.
- 제가 확인한 공식 공개 문서 범위에서는 네이버 검색 API가 제공하는 것은 웹/뉴스/블로그/쇼핑/지역 검색 같은 범주이고, 공개 리뷰 조회 API는 확인되지 않았습니다. 이 문장은 "제가 확인한 공식 공개 문서상 보이지 않는다"는 뜻으로 이해해 주세요.
- 현재 코드에서 사용하는 네이버 지역 검색 응답 필드는 `title`, `link`, `category`, `description`, `telephone`, `address`, `roadAddress`, `mapx`, `mapy` 수준입니다. 리뷰 필드는 없습니다. 근거: [NaverLocalSearchClient.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/location/NaverLocalSearchClient.java:95)

- 코드에서 접근하려면 선택지는 2개입니다.
- 선택지 1:
  네이버가 공식적으로 제공하는 리뷰/플레이스 제휴 API가 있다면 그걸 붙이는 방식입니다. 가장 안전합니다.
- 선택지 2:
  네이버 플레이스 웹 페이지를 크롤링하는 방식입니다. 기술적으로는 가능할 수 있지만, HTML 구조 변경에 취약하고, 이용약관/robots/rate limit 검토가 먼저 필요합니다. 운영 리스크가 큽니다.
- 개인적으로는 공식 API/제휴가 없으면 "실시간 사용자 요청에서 직접 크롤링"은 추천하지 않습니다. 하더라도 배치 수집 후 내부 저장소에서 서빙하는 쪽이 낫습니다.

## 2. 성능 테스트는 이전/이후를 어떻게 비교해야 하나

- 지금 저장소에는 `k6`, `JMeter`, `Gatling`, `Locust` 같은 성능 테스트 스크립트가 없고, `Micrometer/Prometheus/@Timed` 같은 계측도 보이지 않았습니다.
- 그래서 "이전/이후 비교"를 하려면 먼저 기준선부터 만들어야 합니다.

### 추천 측정 방식

- 1단계:
  대상 API를 정합니다. 우선순위는 `/api/pools/nearby`, `/api/locations/search`, `/api/pools/{poolId}/notices/scan`
- 2단계:
  같은 데이터, 같은 인프라, 같은 외부 키 상태에서 30~100회 정도 반복 호출합니다.
- 3단계:
  평균만 보지 말고 `p50`, `p95`, `p99`, 에러율, 외부 호출 횟수를 같이 봅니다.
- 4단계:
  개선 후 완전히 같은 시나리오를 다시 돌립니다.

### 같이 남겨야 하는 데이터

- 전체 응답시간
- DB 쿼리 수와 느린 쿼리 로그
- 외부 API 호출 시간
- 공지 크롤링이면 페이지 fetch 횟수, OpenAI fallback 횟수

### 실무적으로 더 좋은 방법

- 엔드포인트 전체 시간만 재지 말고, 아래처럼 구간별 타이머를 같이 남기는 게 좋습니다.
- `nearby SQL 시간`
- `pool 상세 재조회 시간`
- `Naver local search 시간`
- `Naver geocode 시간`
- `notice homepage fetch 시간`
- `notice list fetch 시간`
- `notice detail fetch 시간`
- `OpenAI extraction 시간`

## 3. 위치 기반 조회와 공지 크롤링이 느릴 수 있는 이유, 그리고 개선 방안

### A. 근처 수영장 조회(`/api/pools/nearby`)

- 현재 SQL은 모든 후보를 `ST_Distance_Sphere`로 계산해서 거리순 정렬한 뒤 limit 합니다. 사전 bounding box 필터가 없습니다. 근거: [PoolNearbyQueryRepository.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/pool/PoolNearbyQueryRepository.java:20)
- 그리고 근처 ID 목록을 얻은 뒤, 다시 `findAllById`로 두 번째 조회를 합니다. 근거: [PoolService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/pool/PoolService.java:67)

- 개선안:
- 하나의 SQL에서 필요한 컬럼까지 같이 projection 해서 2차 조회를 없애기
- 위도/경도 범위 bounding box를 먼저 걸고 그 다음 거리 계산하기
- 공간 인덱스나 별도 `POINT` 컬럼/geo index 검토하기
- 잦은 동일 좌표 요청이 많다면 좌표를 라운딩한 캐시 키로 짧은 TTL 캐시 두기

### B. 위치 검색(`/api/locations/search`)

- 현재는 검색할 때마다 `poolRepository.findAll()`로 전체 수영장 목록을 메모리로 읽고, 네이버 검색 결과 각 후보마다 지오코딩을 다시 시도합니다. 근거: [LocationService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/location/LocationService.java:40), [LocationService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/location/LocationService.java:118)
- 데이터가 커질수록 `전체 pool 로딩 + 후보별 외부 geocode` 조합이 병목이 됩니다.

- 개선안:
- 풀 매칭용으로 전체 엔티티 대신 가벼운 projection만 쓰기
- 주소 geocode 결과를 캐시하기
- 필요하다면 검색 결과 리스트에서는 geocode를 생략하고, 상세 진입 시 보강하기
- 풀 매칭을 DB 인덱스 기반 이름/주소 매칭으로 일부 이전하기

### C. 공지 크롤링(`/api/pools/{poolId}/notices/scan`)

- 이 API는 요청이 오면 즉시 홈 → 공지목록 → 상세 페이지를 순차적으로 fetch 합니다. 근거: [NoticeController.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/NoticeController.java:18), [NoticeCrawlerService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java)
- fetch timeout이 8초입니다. 근거: [NoticeCrawlerService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java:1162)
- 목록 후보는 최대 6개, 목록당 상세 후보는 최대 10개라서 사이트 상태가 안 좋으면 체감 지연이 커질 수 있습니다. 근거: [NoticeCrawlerService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java:79)
- 규칙 기반 파싱으로 기간을 못 찾으면 OpenAI fallback까지 붙습니다. 근거: [NoticeCrawlerService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java:521), [OpenAiNoticeExtractionClient.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notice/OpenAiNoticeExtractionClient.java:20)

- 개선안:
- 사용자 요청 시 실시간 풀스캔 대신, 백그라운드 선행 스캔 + 저장 결과 조회 구조로 바꾸기
- 최근 스캔 결과에 TTL을 두고, 너무 짧은 간격 재요청은 캐시 응답하기
- 이미 성공했던 공지 소스 URL을 우선 사용하고, 홈페이지 전체 재탐색은 fallback으로만 두기
- 상세 페이지 fetch를 제한된 병렬 처리로 바꾸기
- OpenAI 호출은 정말 필요한 경우에만 태우고, 실패한 URL은 짧은 시간 재시도하지 않기

## 4. 지금 batch / scheduling은 어떻게 처리되고 있나

- 현재 배치성 스케줄링은 2개만 보입니다.
- 이벤트 스케줄러:
  30초마다 이벤트 상태를 갱신하고, 오픈 임박/오픈 시작 알림을 DB에 만들고 Redis 큐에 넣습니다. 근거: [EventScheduler.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/EventScheduler.java:18), [EventService.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/event/EventService.java)
- 알림 워커:
  1초마다 Redis 큐에서 최대 20개를 꺼내 FCM 전송을 시도합니다. 근거: [NotificationWorker.java](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/java/com/swimpulse/notification/NotificationWorker.java:31), [application.properties](/c:/Users/mk35p/Desktop/Galaxy_Book/Java_INTELLIJ/z_My_Project/SwimPulse/backend/src/main/resources/application.properties:37)

- 반대로, 공지 크롤링용 스케줄러는 없습니다.
- 즉 지금은 사용자가 `공지 확인`을 눌렀을 때 `POST /api/pools/{poolId}/notices/scan`이 바로 실행되는 on-demand 구조입니다. 사용자가 느리다고 느끼는 게 맞는 방향입니다.

## 5. 우선순위 추천

- 1순위:
  공지 스캔 결과를 백그라운드 적재형으로 바꾸기. 체감 성능 개선 폭이 가장 큽니다.
- 2순위:
  `registration_events`에 논리 유니크 키를 두거나, 이벤트 생성 구간을 락/업서트 기반으로 바꾸기. 운영 안정성 이득이 큽니다.
- 3순위:
  `/api/pools/nearby`를 단일 projection SQL + bounding box로 줄이기
- 4순위:
  geocode / notice scan / nearby 결과에 짧은 TTL 캐시 붙이기
- 5순위:
  성능 테스트 스크립트와 구간별 타이머를 먼저 저장소에 넣기

## 외부 참고

- NAVER Developers 검색 API 소개: https://developers.naver.com/products/service-api/search/search.md
- NAVER Developers API 공통 가이드: https://developers.naver.com/docs/common/openapiguide
- 네이버 지도 Open API 이관 공지: https://developers.naver.com/notice/article/7540

## 제 의견

- 지금 구조는 MVP로는 충분히 말이 됩니다. 다만 "실시간 크롤링"과 "이벤트/알림 큐잉의 멀티 인스턴스 안전성"은 사용자가 늘수록 먼저 티가 날 가능성이 큽니다.
- 그래서 다음 단계는 새 기능 추가보다 `공지 선행 수집`, `이벤트 중복 방지`, `측정 체계 추가` 쪽이 더 큰 효율을 줄 가능성이 높습니다.
