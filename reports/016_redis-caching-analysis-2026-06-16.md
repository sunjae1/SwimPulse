# Redis 캐싱 적용 지점 분석 보고서

## 요약

현재 프로젝트에서 Redis는 이미 사용 중이지만, 용도는 캐시보다 큐와 락에 가깝다.

- 알림 발송 큐: `swimpulse:notifications`
- 이벤트 스케줄러 중복 실행 방지 락
- 같은 pool 공지 스캔 동시 실행 방지 락
- 공지 스캔 동시 요청 결과 공유: 기본 60초

코드 기준으로 `@Cacheable`, `CacheManager`, Redis key-value 기반 일반 조회 캐시는 아직 없다. 따라서 Redis 캐싱으로 성능 개선을 노릴 수 있는 지점은 꽤 명확하다.

우선순위는 다음 순서가 적합하다.

| 우선순위 | 캐시 대상 | 기대 효과 | 위험도 |
| --- | --- | --- | --- |
| 1 | Naver Geocoding / Reverse Geocoding | 외부 API 호출 감소, 위치 검색 체감 개선 | 낮음 |
| 2 | Naver Local Search 결과 | 검색/홈페이지 보강 API 비용 감소 | 중간 |
| 3 | 공지 스캔 응답 또는 상세 후보 결과 | 공지 확인 반복 요청 지연 감소 | 중간 |
| 4 | OCR / OpenAI 추출 결과 | 이미지 공지 재분석 비용 감소 | 낮음~중간 |
| 5 | 근처 수영장 조회 결과 | 반복 위치 조회 DB 비용 감소 | 낮음 |
| 6 | 수영장 목록 | 단순 반복 DB 조회 감소 | 낮음 |
| 7 | My Page 응답 | 새로고침 반복 비용 감소 | 중간~높음 |

가장 큰 성능 이득은 DB 캐시보다 외부 API와 공지 크롤링 캐시에서 나온다.

---

## 현재 Redis 사용 현황

### 1. 알림 큐

`NotificationQueuePublisher`가 알림 ID를 Redis List에 `rightPush`한다.

```text
swimpulse:notifications
```

`NotificationWorker`는 같은 List에서 `leftPop`으로 꺼내 FCM 발송을 처리한다.

이건 캐시가 아니라 queue다.

### 2. 분산 락

`RedisLockService`는 `SET NX + TTL` 방식으로 락을 잡는다.

현재 락 사용처:

- 이벤트 스케줄러
- 공지 스캔

락은 캐시가 아니라 동시성 제어 장치다.

### 3. 공지 스캔 결과 공유

`NoticeCrawlerService`는 같은 pool에 대해 이미 스캔 중이면 Redis 락을 보고 기다린 뒤, 선행 스캔이 저장한 짧은 TTL 결과를 읽는다.

```text
swimpulse:notice-scan:result:{poolId}
```

기본 TTL은 코드 기본값 기준 60초다. 이건 “동시 요청 공유”에 가깝고, 일반적인 장기 캐시는 아니다.

---

## 캐싱 후보 1. Naver Geocoding

### 현재 흐름

`LocationService.search(...)`는 네이버 Local Search 후보를 받은 뒤, 기존 DB 시설과 exact match가 안 되면 후보 주소를 지오코딩한다.

관련 코드:

```text
backend/src/main/java/com/swimpulse/location/LocationService.java
backend/src/main/java/com/swimpulse/pool/NaverMapsGeocodingClient.java
```

또 `LocationService.reverseGeocode(...)`는 현재 위치 좌표를 주소로 표시할 때 Naver Reverse Geocoding을 호출한다.

### 캐시 키

주소 → 좌표:

```text
swimpulse:cache:naver:geocode:v1:{normalizedAddress}
```

좌표 → 주소:

```text
swimpulse:cache:naver:reverse-geocode:v1:{latBucket}:{lonBucket}
```

좌표는 너무 정밀하게 잡으면 캐시가 거의 맞지 않는다. 예를 들어 소수점 4자리 정도로 bucket 처리하면 약 10m 단위가 된다.

### TTL 제안

| 결과 | TTL |
| --- | ---: |
| geocode 성공 | 30일~90일 |
| geocode 결과 없음 | 1일 |
| reverse geocode 성공 | 7일~30일 |
| reverse geocode 결과 없음 | 1일 |

주소와 좌표는 자주 바뀌지 않으므로 긴 TTL이 가능하다.

### 기대 효과

- `/api/locations/search`에서 같은 후보 주소가 반복 등장할 때 외부 호출 감소
- `/api/locations/reverse-geocode`에서 같은 현재 위치 주변 반복 요청 감소
- 네이버 API quota와 응답 지연 감소

### 주의점

- 잘못된 주소 결과가 오래 남지 않도록 miss는 짧게 둔다.
- 캐시 payload에는 최소한 `latitude`, `longitude`, `cachedAt`, `source` 정도만 둔다.

### 우선순위

가장 먼저 적용할 만하다. 결과가 비교적 안정적이고 무효화가 거의 필요 없다.

---

## 캐싱 후보 2. Naver Local Search

### 현재 흐름

Naver Local Search는 두 군데에서 중요하다.

1. 사용자가 장소를 검색하는 `/api/locations/search`
2. 홈페이지 보강/재검증 API에서 pool 이름으로 검색

관련 코드:

```text
backend/src/main/java/com/swimpulse/location/NaverLocalSearchClient.java
backend/src/main/java/com/swimpulse/location/LocationService.java
backend/src/main/java/com/swimpulse/pool/PoolService.java
```

현재 Local Search 요청은 `sort=random`이다. 즉 같은 query라도 후보 순서와 결과가 흔들릴 수 있다.

### 캐시 키

```text
swimpulse:cache:naver:local-search:v1:{normalizedQuery}:display:{display}
```

홈페이지 보강용은 별도 key namespace를 두는 편이 낫다.

```text
swimpulse:cache:naver:homepage-search:v1:{normalizedPoolName}
```

### TTL 제안

| 사용처 | TTL |
| --- | ---: |
| 사용자 위치 검색 | 5분~30분 |
| 홈페이지 enrich/reverify | 1일~7일 |
| 검색 결과 없음 | 10분~1시간 |

### 기대 효과

기존 성능 보고서에서 `/api/locations/search` 전체 지연은 네이버 Local Search와 후보 geocoding의 영향이 크다고 봤다. DB batch matching은 빨라졌지만 실제 검색 API는 외부 호출 변동이 크다.

따라서 같은 검색어가 반복되는 경우 Local Search 결과 캐시는 효과가 있다.

예:

```text
서울 수영장
성동구 수영장
오정레포츠센터수영장
```

### 주의점

- `sort=random` 결과를 캐시하면 랜덤성이 사라진다.
- 사용자 검색에서 너무 긴 TTL을 두면 네이버 지도 장소 변경 반영이 늦어진다.
- 후보가 DB에 이미 추가됐는지 여부는 캐시된 Naver 결과만으로 결정하면 안 된다. DB 매칭은 캐시 hit 후에도 다시 수행해야 한다.

### 추천 방식

처음에는 “Naver raw candidate list”만 캐시한다.

그 뒤 기존 로직처럼:

```text
캐시된 Naver 후보
→ 후보 정규화
→ DB batch matching
→ 필요한 후보만 geocoding cache 조회
→ 응답 생성
```

이렇게 해야 DB 최신 상태를 반영할 수 있다.

---

## 캐싱 후보 3. 위치 검색 전체 응답

### 현재 흐름

`GET /api/locations/search`는 다음 단계로 구성된다.

```text
Naver Local Search
→ 후보 정규화
→ DB exact match batch lookup
→ 미매칭 후보 geocoding
→ 좌표 기반 근접 중복 매칭
→ 사용자 기준 거리 계산과 정렬
```

### 캐시 키

좌표가 없는 경우:

```text
swimpulse:cache:locations:search-response:v1:{query}:display:{display}
```

좌표가 있는 경우:

```text
swimpulse:cache:locations:search-response:v1:{query}:display:{display}:origin:{latBucket}:{lonBucket}
```

### TTL 제안

```text
30초~3분
```

### 기대 효과

같은 사용자가 같은 검색어를 반복하거나 프론트에서 refetch가 일어나는 경우 전체 응답을 바로 반환할 수 있다.

### 주의점

이 응답에는 `alreadyExists`, `matchedPoolId`, `distanceMeters` 같은 현재 DB 상태와 사용자 위치 기반 값이 섞인다.

따라서 TTL을 길게 두면 다음이 늦게 반영된다.

- 새 시설 추가
- 기존 시설 좌표 수정
- normalized matching 규칙 변경

### 추천 여부

1차 캐시는 Local Search와 Geocoding에 두고, 전체 응답 캐시는 2차로 고려하는 편이 좋다.

이유:

- 외부 API 캐시는 무효화가 쉽다.
- 전체 응답 캐시는 DB 상태가 섞여 무효화 범위가 넓다.

---

## 캐싱 후보 4. 근처 수영장 조회

### 현재 흐름

`GET /api/pools/nearby`는 좌표와 limit을 받아 거리순 수영장을 조회한다.

관련 코드:

```text
backend/src/main/java/com/swimpulse/pool/PoolService.java
backend/src/main/java/com/swimpulse/pool/PoolNearbyQueryRepository.java
```

현재 SQL은 `ST_Distance_Sphere`로 거리 계산 후 정렬한다.

### 캐시 키

```text
swimpulse:cache:pools:nearby:v1:{latBucket}:{lonBucket}:limit:{limit}
```

좌표 bucket은 3~4자리 중 선택한다.

| 소수점 | 대략 거리 | 특징 |
| ---: | ---: | --- |
| 3자리 | 약 100m | 캐시 hit 높음, 정확도 낮음 |
| 4자리 | 약 10m | 캐시 hit 낮음, 정확도 높음 |

수영장 가까운 순 목록은 100m 정도 흔들려도 사용자가 크게 이상하게 느끼지 않을 가능성이 높다. 그래서 3자리 bucket이 더 실용적이다.

### TTL 제안

```text
1분~10분
```

### 기대 효과

현재 `nearby`는 외부 API 없이 DB 거리 계산만 한다. 기존 k6 결과에서 p95가 수십 ms 수준이었으므로, 최우선 병목은 아니다.

그래도 모바일에서 현재 위치 기준 목록이 자주 새로고침되면 캐시 효과가 있다.

### 무효화

다음 이벤트가 있을 때 prefix evict가 필요하다.

- 새 pool 추가
- pool 좌표 변경
- geocode 상태 변경

초기에는 TTL만으로 충분하고, 명시적 evict는 나중에 붙여도 된다.

### 우선순위

중간. 구현은 쉽지만 큰 성능 이득은 외부 API 캐시보다 작다.

---

## 캐싱 후보 5. 수영장 전체 목록

### 현재 흐름

`GET /api/pools`는 `findAllByOrderByNameAsc()`로 전체 pool 목록을 반환한다.

관련 코드:

```text
backend/src/main/java/com/swimpulse/pool/PoolService.java
backend/src/main/java/com/swimpulse/pool/PoolRepository.java
```

### 캐시 키

```text
swimpulse:cache:pools:list:v1
```

### TTL 제안

```text
5분~30분
```

### 기대 효과

DB 전체 목록 조회를 줄인다. 다만 현재 pool 데이터가 132개 수준이라 성능상 큰 병목은 아니다.

### 무효화

다음 작업 후 삭제하면 된다.

- `createFromLocationCandidate`
- geocode batch 결과로 좌표가 갱신될 때
- homepage enrich/reverify로 표시 필드가 갱신될 때

### 우선순위

낮음~중간. 쉽고 안정적이지만 체감 개선은 제한적이다.

---

## 캐싱 후보 6. 공지 스캔 응답

### 현재 흐름

`POST /api/pools/{poolId}/notices/scan`은 다음을 수행한다.

```text
Redis lock 획득
→ 저장된 VERIFIED source 우선 접근
→ 필요 시 CANDIDATE 검증
→ 필요 시 홈페이지 전체 재탐색
→ 공지 상세 후보 수집
→ 기존 pool_notices 재사용 또는 상세 페이지 재분석
→ 응답 생성
```

현재도 같은 pool에 대한 동시 요청은 Redis 락과 60초 결과 공유로 어느 정도 완화된다.

하지만 “동시 요청”이 아니라 “몇 분 안에 반복 클릭”은 여전히 source fetch가 다시 발생할 수 있다.

### 캐시 키

```text
swimpulse:cache:notice-scan-response:v1:pool:{poolId}:parser:{CURRENT_PARSER_VERSION}
```

source 상태나 notice 변경을 반영하려면 version token을 추가할 수 있다.

```text
swimpulse:cache:notice-scan-response:v1:pool:{poolId}:parser:{version}:source:{sourceVersion}
```

처음에는 단순 TTL만 추천한다.

### TTL 제안

```text
3분~10분
```

### 기대 효과

- 같은 사용자가 모달을 닫고 다시 누르는 경우 즉시 응답
- 여러 사용자가 짧은 시간에 같은 pool을 확인할 때 외부 fetch 감소
- OCR/OpenAI fallback이 발생하는 사이트에서 재분석 비용 감소

### 주의점

공지 스캔은 `POST`지만 실제 의미는 조회에 가깝다. 캐시 가능하다. 다만 내부에서 source 검증과 notice 저장 side effect가 있으므로 캐시 hit 시 side effect가 생략된다.

이건 장점이기도 하고 단점이기도 하다.

- 장점: 반복 외부 요청 감소
- 단점: 짧은 TTL 동안 새 공지 발견이 늦어질 수 있음

### 추천 방식

캐시 hit 조건:

```text
pool에 VERIFIED source가 있고
최근 scan response가 있고
TTL이 남아 있으면 반환
```

캐시 miss 조건:

```text
latestCheckFailed=true 였던 응답은 짧게 캐시하거나 캐시하지 않음
parser version 변경 시 miss
사용자가 강제 새로고침 옵션을 누르면 miss
```

---

## 캐싱 후보 7. 공지 상세 HTML / 상세 후보

### 현재 흐름

공지 스캔은 source URL을 fetch하고, 상세 후보 링크를 찾고, 상세 페이지를 다시 fetch한다.

관련 코드:

```text
backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java
```

### 선택지 A. HTML 원문 캐시

```text
swimpulse:cache:http:html:v1:{normalizedUrl}
```

TTL:

```text
5분~30분
```

장점:

- 구현이 직관적
- 같은 URL 반복 fetch를 줄임

단점:

- HTML이 클 수 있음
- Redis memory 사용량 증가
- 사이트별 캐시 정책을 고려하지 않음

### 선택지 B. 상세 후보 캐시

```text
swimpulse:cache:notice-source:candidates:v1:{sourceUrl}:parser:{version}
```

payload:

```json
[
  {
    "url": "...",
    "title": "...",
    "source": "anchor link"
  }
]
```

TTL:

```text
10분~1시간
```

장점:

- HTML보다 작음
- 크롤러의 중간 결과를 바로 재사용 가능

단점:

- 파서 규칙이 바뀌면 버전 관리 필요

### 추천

HTML 원문 캐시보다 상세 후보 캐시가 더 안전하다.

HTML 캐시는 메모리 비용이 크고, 실제로 필요한 것은 “이 source에서 어떤 상세 후보가 나왔는가”이기 때문이다.

---

## 캐싱 후보 8. OCR 결과

### 현재 흐름

OCR은 이미지 기반 공지에서 기간을 뽑기 위해 사용된다. 보고서 014 기준으로 OCR은 다운로드, 이미지 처리, 줄/블록 파싱까지 포함한다.

관련 코드:

```text
backend/src/main/java/com/swimpulse/notice/TesseractNoticeImageOcrService.java
backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java
```

### 캐시 키

이미지 URL 기반:

```text
swimpulse:cache:notice-ocr:v1:{normalizedImageUrl}
```

가능하면 이미지 응답의 `ETag`, `Last-Modified`, `Content-Length`를 같이 저장하면 더 좋다.

### TTL 제안

```text
7일~30일
```

이미지 공지는 URL이 바뀌지 않는 한 내용도 보통 바뀌지 않는다.

### 기대 효과

- Tesseract 실행 비용 감소
- 이미지 다운로드 비용 감소
- OCR 품질이 낮아도 같은 실패를 반복하지 않음

### 주의점

- 이미지 URL이 같지만 내용이 바뀌는 사이트가 있을 수 있다.
- 실패 결과도 짧은 TTL로 negative cache를 둔다.

| 결과 | TTL |
| --- | ---: |
| OCR 성공 | 7일~30일 |
| OCR 빈 결과 | 1일 |
| 이미지 다운로드 실패 | 10분~1시간 |

### 우선순위

공지 이미지 OCR이 실제로 자주 발생한다면 높다. OCR은 DB 조회와 비교할 수 없을 만큼 비싼 작업이다.

---

## 캐싱 후보 9. OpenAI fallback 결과

### 현재 흐름

규칙 기반 파싱으로 기간을 못 찾으면 OpenAI fallback이 가능하다.

관련 코드:

```text
backend/src/main/java/com/swimpulse/notice/OpenAiNoticeExtractionClient.java
backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java
```

### 캐시 키

```text
swimpulse:cache:notice-openai:v1:parser:{version}:content:{sha256}
```

URL만 키로 쓰는 것보다 본문 hash를 넣는 편이 안전하다.

### TTL 제안

```text
30일
```

### 기대 효과

- 비용 절감
- OpenAI 호출 지연 감소
- 같은 본문에 대한 결과 일관성 확보

### 주의점

- 프롬프트나 파서 해석 기준이 바뀌면 cache version을 올려야 한다.
- confidence가 낮은 결과는 캐시하지 않거나 짧게 둔다.

### 우선순위

OpenAI fallback이 자주 발생하면 높다. 현재는 로컬 OCR과 규칙 파싱 개선이 많이 들어갔기 때문에 실제 호출 빈도를 먼저 확인하는 것이 좋다.

---

## 캐싱 후보 10. My Page 응답

### 현재 흐름

`MyPageService.findMyPage(...)`는 다음을 한 번에 모은다.

```text
사용자 정보
구독 목록
알림 목록
활성 device 수
```

관련 코드:

```text
backend/src/main/java/com/swimpulse/mypage/MyPageService.java
backend/src/main/java/com/swimpulse/subscription/SubscriptionService.java
backend/src/main/java/com/swimpulse/notification/NotificationService.java
```

### 캐시 키

```text
swimpulse:cache:mypage:v1:user:{userId}
```

### TTL 제안

```text
5초~30초
```

### 기대 효과

마이페이지를 연속 새로고침하거나 페이지 이동 후 다시 돌아오는 경우 DB 조회를 줄인다.

### 무효화 필요 이벤트

- 구독 생성
- 구독 해제
- 구독 기간 수정
- 알림 생성
- 알림 발송 상태 변경
- 기기 등록/해제
- 사용자 정보 변경

### 추천 여부

후순위다. 사용자별이고 변경 이벤트가 많아서 무효화가 번거롭다. 프론트 SWR/React Query 같은 클라이언트 캐시가 더 단순할 수 있다.

---

## 캐싱하지 않는 편이 좋은 지점

### 1. 이벤트 스케줄러 상태 판단

`EventScheduler`는 30초마다 이벤트 상태와 알림 큐잉 여부를 판단한다.

```text
registration_starts_at
reminder_queued
start_queued
status
```

이건 시간과 발송 여부가 핵심이라 Redis 캐시를 끼우면 오히려 정합성 위험이 커진다.

### 2. 구독 생성/해제

구독은 쓰기 경로다. 캐시보다는 DB 유니크 제약과 트랜잭션이 중요하다.

캐시를 둔다면 조회 응답만 캐시하고, 생성/해제 시 즉시 evict해야 한다.

### 3. JWT 인증

현재 JWT는 쿠키에서 읽어 검증한다. 별도 DB 조회를 하지 않는다면 Redis 캐시 이득이 거의 없다.

### 4. Redis 알림 큐 자체

이미 Redis queue다. 캐시 대상이 아니다. 오히려 reliable queue 개선이 별도 과제다.

---

## 권장 구현 순서

### 1단계. Geocoding cache

가장 안전하다.

```text
address -> coordinates
lat/lon bucket -> address
```

기대 효과:

- 외부 호출 감소
- 사용자 검색 체감 개선
- 실패해도 TTL 만료로 자연 복구

### 2단계. Naver Local Search raw result cache

```text
query/display -> raw candidates
```

DB matching은 캐시 후에도 매번 수행한다.

기대 효과:

- `/api/locations/search`
- homepage enrich/reverify

둘 다 개선된다.

### 3단계. Notice scan response short cache

```text
poolId/parserVersion -> NoticeScanResponse
```

TTL은 3~10분으로 짧게 둔다.

기대 효과:

- 반복 공지 확인 클릭
- 여러 사용자 동시/근접 요청
- 외부 홈페이지 fetch 감소

### 4단계. OCR / OpenAI result cache

```text
imageUrl -> OCR text
contentHash -> OpenAI extraction result
```

공지 이미지가 많은 사이트에서 효과가 크다.

### 5단계. nearby/list cache

DB 병목이 확인되면 적용한다. 지금은 외부 API와 크롤링 쪽이 더 중요하다.

---

## Redis key 설계안

```text
swimpulse:cache:naver:geocode:v1:{normalizedAddress}
swimpulse:cache:naver:reverse-geocode:v1:{latBucket}:{lonBucket}
swimpulse:cache:naver:local-search:v1:{normalizedQuery}:display:{display}
swimpulse:cache:naver:homepage-search:v1:{normalizedPoolName}
swimpulse:cache:locations:search-response:v1:{query}:display:{display}:origin:{latBucket}:{lonBucket}
swimpulse:cache:pools:nearby:v1:{latBucket}:{lonBucket}:limit:{limit}
swimpulse:cache:pools:list:v1
swimpulse:cache:notice-scan-response:v1:pool:{poolId}:parser:{parserVersion}
swimpulse:cache:notice-source:candidates:v1:{sourceUrl}:parser:{parserVersion}
swimpulse:cache:notice-ocr:v1:{normalizedImageUrl}
swimpulse:cache:notice-openai:v1:parser:{parserVersion}:content:{sha256}
swimpulse:cache:mypage:v1:user:{userId}
```

운영에서 key 폭주를 막기 위해 각 prefix별 TTL과 maximum payload size를 정해야 한다.

---

## 캐시 무효화 정책

| 캐시 | 무효화 트리거 |
| --- | --- |
| geocode | 기본 TTL, 수동 삭제 정도면 충분 |
| reverse geocode | 기본 TTL |
| local search | 기본 TTL |
| homepage search | 홈페이지 재검증 정책 변경 또는 TTL |
| location search response | pool 생성, 좌표 변경, 짧은 TTL |
| nearby | pool 생성, 좌표 변경, 짧은 TTL |
| pool list | pool 생성/수정 |
| notice scan response | 공지 저장/재분석, source 재검증, parser version 변경 |
| notice source candidates | source 재검증, parser version 변경, TTL |
| OCR | parser/OCR 전처리 변경 시 version 변경 |
| OpenAI | prompt/model/parser 변경 시 version 변경 |
| mypage | 구독/알림/device 변경 |

초기 구현은 명시적 evict보다 짧은 TTL 중심이 안전하다.

---

## 모니터링 지표 제안

Redis 캐시를 넣을 때는 hit/miss를 꼭 봐야 한다.

공통 metric:

```text
swimpulse.cache.requests{cache="naver_geocode", result="hit|miss|error"}
swimpulse.cache.latency{cache="naver_geocode", result="hit|miss"}
swimpulse.cache.payload_bytes{cache="notice_scan_response"}
```

외부 호출 metric과 같이 봐야 하는 지표:

```text
swimpulse.location.search.step{step="naver_local_search"}
swimpulse.location.search.candidate_geocode
http.server.requests{uri="/api/locations/search"}
http.server.requests{uri="/api/pools/{poolId}/notices/scan"}
```

캐시 도입 전후 비교 기준:

- p95 latency
- p99 latency
- RPS
- Naver API 호출 수
- OCR 실행 횟수
- OpenAI fallback 호출 수
- Redis hit ratio
- Redis memory usage

---

## k6 캐시 전후 비교 시나리오

캐시 효과는 “같은 입력이 반복될 때 외부 호출이나 비싼 계산을 얼마나 줄였는가”로 봐야 한다. 그래서 일반 부하 테스트와 달리 같은 query, 같은 좌표, 같은 pool을 반복 호출하는 전용 k6 스크립트를 추가했다.

스크립트:

```text
ops/k6/scripts/cache-repeat-load.js
```

지원 target:

| TARGET | API | 캐시 검증 목적 |
| --- | --- | --- |
| `location-search` | `GET /api/locations/search` | Local Search + geocode 캐시 효과 |
| `geocode` | `GET /api/locations/geocode` | 주소 → 좌표 캐시 효과 |
| `reverse-geocode` | `GET /api/locations/reverse-geocode` | 좌표 → 주소 캐시 효과 |
| `nearby` | `GET /api/pools/nearby` | 좌표 bucket 기반 nearby 응답 캐시 효과 |
| `notice-scan` | `POST /api/pools/{poolId}/notices/scan` | 공지 스캔 응답/상세 후보 캐시 효과 |

### 실행 방식

전후 비교는 같은 명령을 두 번 돌린다.

```text
캐시 구현 전 또는 캐시 비활성화 상태
→ baseline 결과 저장

캐시 구현 후 또는 캐시 활성화 상태
→ after 결과 저장
```

캐시가 Redis에 남는 구조라면 “warm cache”를 보기 위해 같은 명령을 연속으로 두 번 실행한다.

```text
1회차: cold 또는 semi-cold
2회차: warm
```

### Location Search 반복 테스트

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=location-search `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e QUERY="서울 수영장" `
  -e DISPLAY=10 `
  -e LATITUDE=37.5665 `
  -e LONGITUDE=126.9780 `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-location-search-baseline-summary.json `
  --out json=/results/cache-location-search-baseline-raw.json
```

캐시 적용 후:

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=location-search `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e QUERY="서울 수영장" `
  -e DISPLAY=10 `
  -e LATITUDE=37.5665 `
  -e LONGITUDE=126.9780 `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-location-search-after-summary.json `
  --out json=/results/cache-location-search-after-raw.json
```

비교 지표:

- `http_req_duration` p95/p99
- `http_reqs` rate
- `cache_repeat_valid_response`
- `cache_repeat_result_count`
- backend metric의 Naver Local Search 호출 수
- backend metric의 candidate geocode 호출 수

### Reverse Geocode 반복 테스트

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=reverse-geocode `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e LATITUDE=37.5665 `
  -e LONGITUDE=126.9780 `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-reverse-geocode-baseline-summary.json `
  --out json=/results/cache-reverse-geocode-baseline-raw.json
```

캐시 적용 후에는 파일명만 `after`로 바꿔 같은 명령을 실행한다.

### Nearby 반복 테스트

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=nearby `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e LATITUDE=37.5665 `
  -e LONGITUDE=126.9780 `
  -e LIMIT=20 `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-nearby-baseline-summary.json `
  --out json=/results/cache-nearby-baseline-raw.json
```

### Notice Scan 반복 테스트

공지 스캔은 인증이 필요하다.

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=notice-scan `
  -e VUS=5 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_ID=16 `
  -e ACCESS_TOKEN="여기에_쿠키값" `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-notice-scan-baseline-summary.json `
  --out json=/results/cache-notice-scan-baseline-raw.json
```

비교 지표:

- `http_req_duration` p95/p99
- `cache_repeat_notice_shared_responses`
- `cache_repeat_notice_latest_check_failures`
- backend의 notice scan log
- 외부 fetch 횟수
- OCR 실행 횟수
- OpenAI fallback 호출 수

### 결과 해석 기준

캐시가 잘 먹으면 보통 다음 패턴이 나온다.

```text
p95 / p99 감소
RPS 증가
Naver API 호출 수 감소
OCR/OpenAI 호출 수 감소
Redis hit ratio 증가
Redis memory usage 완만한 증가
```

반대로 Redis hit ratio가 낮고 latency도 그대로면 key 설계가 너무 세밀하거나 TTL이 너무 짧은 것이다.

### 주의

k6만으로는 Naver API 호출 수, OCR 실행 횟수, Redis hit ratio를 직접 알 수 없다. k6는 사용자 관점의 응답 시간과 성공률을 보여준다. 외부 호출 감소 여부는 backend 로그, Micrometer metric, Prometheus/Grafana를 같이 봐야 한다.

---

## 주의할 점

### 1. 캐시가 stale data를 만든다

캐시는 항상 오래된 데이터를 만들 수 있다. 특히 공지와 구독은 사용자 신뢰와 연결되므로 TTL을 과하게 길게 잡으면 안 된다.

### 2. Redis 장애 시 fallback 필요

Redis가 죽어도 핵심 API는 DB와 외부 API로 동작해야 한다.

권장 정책:

```text
cache get 실패 → 로그만 남기고 원래 로직 실행
cache set 실패 → 응답 실패로 만들지 않음
lock/queue 실패 → 별도 정책 필요
```

락과 큐는 캐시보다 중요도가 높으므로 Redis 장애 대응을 별도로 봐야 한다.

### 3. payload 크기 제한

공지 HTML 원문이나 OCR 원문을 Redis에 크게 넣으면 memory가 빠르게 늘 수 있다.

권장:

- HTML 원문보다 파싱 결과 캐시
- payload size logging
- 너무 큰 값은 캐시하지 않음

### 4. 사용자별 캐시는 뒤로 미룬다

사용자별 캐시는 무효화가 복잡하다. 지금 프로젝트에서는 외부 API와 공지 크롤링 캐시가 먼저다.

---

## 결론

Redis 캐싱을 넣는다면 첫 목표는 DB 조회를 줄이는 것이 아니라 외부 호출과 비싼 파싱을 줄이는 것이다.

가장 추천하는 순서는 다음이다.

1. `NaverMapsGeocodingClient`에 geocode/reverse geocode 캐시 추가
2. `NaverLocalSearchClient`에 raw candidate 캐시 추가
3. `NoticeCrawlerService`에 짧은 TTL의 scan response 캐시 추가
4. OCR/OpenAI fallback 결과 캐시 추가
5. 필요하면 nearby/list/mypage 조회 캐시 추가

현재 프로젝트의 성능 병목은 이미 여러 번 확인한 것처럼 외부 API와 공지 크롤링 쪽에 더 가깝다. 그래서 Redis 캐시도 그 지점부터 넣는 것이 가장 효율적이다.
