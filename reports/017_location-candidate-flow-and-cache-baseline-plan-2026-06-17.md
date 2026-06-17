# 위치 기준 수영장 후보 검색 및 Redis 1차 캐시 구현 계획

작성일: 2026-06-17

## 목적

현재 위치 검색 흐름을 다음 의도에 맞게 정리한다.

```text
브라우저 위치가 틀릴 때
→ 사용자가 검색창으로 기준 위치를 직접 선택
→ 그 기준 위치 주변의 DB 수영장을 보여줌
→ DB에 없는 주변 수영장 후보를 별도 섹션에서 확인
→ 사용자가 "이 시설 추가"로 건의
```

이번 문서는 구현 전에 선택한 설계 방향과, Redis 캐시 도입 전후 비교를 위한 k6 baseline 명령어를 고정해두는 문서다.

---

## 구현 결과

2026-06-17 구현 완료.

- [x] `/api/locations/search`에서 `matchedPoolId` / `alreadyExists` 제거
- [x] `LocationSearchCandidate` 응답 영향 정리
- [x] `/api/pools/location-candidates` 신규 API 추가
- [x] 네이버 기반 주변 수영장 후보 검색 구현
- [x] `location-candidates`에서 이름/도로명주소/지번주소 exact match로 DB 중복 판정
- [x] `/api/pools/from-location-candidate`의 좌표 fallback 중복 판정 제거
- [x] 프론트에 `{선택 위치} 기준 수영장 후보 보기` 접기/펼치기 섹션 추가
- [x] Redis 1차 캐시 추가
- [x] k6 baseline/after 비교
- [x] 결과 보고서 작성

성능 비교 결과는 [018_location-candidate-cache-performance-2026-06-17.md](018_location-candidate-cache-performance-2026-06-17.md)에 정리했다.

---

## 선택한 구현 방향

### 1. `/api/locations/search`는 기준 위치 검색 전용

선택:

```text
A. matchedPoolId / alreadyExists 완전 제거
```

의미:

```text
/api/locations/search는 "내 기준 위치 후보"만 반환한다.
우리 DB 수영장과의 관계는 판단하지 않는다.
```

이 API에서는 다음 후보가 모두 정상 후보가 될 수 있다.

```text
부천시청
부천시청역
아파트
지하철역
식당
카페
```

이유:

- 사용자의 목적은 수영장 검색이 아니라 기준 위치 특정이다.
- 좌표 80m fallback으로 `matchedPoolId`를 붙이면, 수영장 근처 식당이나 아파트가 DB 수영장으로 오인될 수 있다.
- DB 수영장 여부는 `/api/pools/nearby`, `/api/pools/location-candidates`, `/api/pools/from-location-candidate`에서 처리한다.

---

### 2. `/api/pools/location-candidates` 신규 API 추가

선택한 API 이름:

```http
GET /api/pools/location-candidates
```

목적:

```text
선택한 기준 위치 주변의 "수영장 후보"를 외부 Local Search로 찾는다.
```

예상 요청:

```http
GET /api/pools/location-candidates?latitude=37.5034&longitude=126.7660&radius=5000
```

선택적으로 query를 받을 수 있다.

```http
GET /api/pools/location-candidates?latitude=37.5034&longitude=126.7660&radius=5000&query=수영장
```

예상 응답 요소:

```text
title
category
address
roadAddress
link
latitude
longitude
distanceMeters
alreadyExists
matchedPoolId
homepageUrl
```

`alreadyExists`와 `matchedPoolId`는 이 API에서는 유지한다. 목적이 "주변 수영장 후보 중 DB에 없는 시설 찾기"이기 때문이다.

---

### 3. 주변 수영장 후보 검색 provider

선택:

```text
A. 네이버 유지
```

구현 방향:

```text
기준 좌표
→ reverse geocode로 행정구역 또는 주소 얻기
→ "{행정구역} 수영장" 형태로 Naver Local Search
→ 후보 주소 geocoding
→ 기준 좌표와 거리 계산
→ radius 밖 후보 제거
→ 가까운 순 정렬
```

장점:

- 기존 `NaverLocalSearchClient`, `NaverMapsGeocodingClient`를 재사용할 수 있다.
- 새 API 키가 필요 없다.
- 현재 프로젝트의 네이버 기반 흐름과 잘 맞는다.

단점:

- 네이버 Local Search는 진짜 `좌표 + radius` POI 검색 API가 아니다.
- 검색어 품질과 행정구역 문자열에 따라 후보가 누락될 수 있다.

추후 대안:

```text
Kakao Local API
```

카카오는 `query`, `x`, `y`, `radius`, `sort=distance` 방식이 가능하므로, 진짜 주변 POI 검색에는 더 적합하다. 단, 이번 선택에서는 네이버를 먼저 유지한다.

---

### 4. `/api/pools/location-candidates`의 DB 중복 판정 기준

선택:

```text
이름 exact match
도로명주소 exact match
지번주소 exact match
```

제외:

```text
좌표 80m fallback 제거
```

이유:

```text
공공기관 수영장 옆에 민간 수영장이 있을 수 있다.
좌표 80m fallback을 쓰면 민간 수영장이 공공기관 수영장의 matchedPoolId를 가져갈 수 있다.
```

따라서 좌표는 다음 용도로만 사용한다.

```text
기준 위치와의 거리 계산
가까운 순 정렬
radius 필터링
지도 표시
```

DB 중복 판정은 이름/주소 exact match만 사용한다.

---

### 5. `/api/pools/from-location-candidate` 유지

선택:

```text
B. 좌표 fallback 제거
```

역할:

```text
사용자가 "이 시설 추가"를 누른 후보를 DB에 저장하거나, 기존 pool을 반환한다.
```

중복 판단:

```text
이름 exact match
도로명주소 exact match
지번주소 exact match
```

좌표 fallback을 제거하는 이유는 `/api/pools/location-candidates`와 같다.

```text
좌표는 거리 판단에는 좋지만, 시설 동일성 판단에는 오탐 가능성이 있다.
```

---

### 6. 프론트 흐름 변경

선택:

```text
B. "DB에 없는 수영장 후보 보기" 버튼으로 접기/펼치기
```

버튼 이름:

```text
"{선택 위치} 기준 수영장 후보 보기"
```

예:

```text
부천시청 기준 수영장 후보 보기
```

화면 흐름:

```text
1. 사용자가 검색창에서 기준 위치 검색
2. 기준 위치 후보 선택
3. /api/pools/nearby로 DB 수영장 가까운 순 표시
4. 사용자가 "{선택 위치} 기준 수영장 후보 보기" 클릭
5. /api/pools/location-candidates 호출
6. DB에 없는 후보에 "이 시설 추가" 버튼 표시
7. 클릭 시 /api/pools/from-location-candidate 호출
```

접기/펼치기로 둔 이유:

- 외부 Local Search 후보는 정확도가 완벽하지 않을 수 있다.
- 기본 화면은 DB에 검증된 수영장 목록에 집중하는 편이 낫다.
- 사용자가 원할 때만 외부 후보를 보게 하면 UX가 덜 복잡하다.

---

### 7. Redis 1차 캐시 적용

선택:

```text
외부 API 결과 중심 1차 캐시만 적용
/api/locations/search 전체 응답 캐시는 보류
```

캐시 대상과 TTL:

| 캐시 대상 | TTL |
| --- | ---: |
| 기준 위치 검색 | 5분 |
| 주변 수영장 후보 | 1시간 |
| geocode 성공 | 30일 |
| geocode 실패 | 1일 |
| reverse geocode 성공 | 7일 |
| reverse geocode 실패 | 1일 |

캐시 key 예시:

```text
swimpulse:cache:location-search:v1:{normalizedQuery}
swimpulse:cache:pool-location-candidates:v1:{latBucket}:{lonBucket}:radius:{radius}:provider:naver
swimpulse:cache:geocode:v1:{normalizedAddress}
swimpulse:cache:reverse-geocode:v1:{latBucket}:{lonBucket}
```

중요 원칙:

```text
외부 API 결과는 캐시한다.
우리 DB exact matching은 매번 다시 수행한다.
```

이렇게 해야 새 pool 추가가 즉시 `alreadyExists`, `matchedPoolId`에 반영된다.

---

### 8. `/api/locations/search` 전체 응답 캐시는 보류

선택:

```text
2차 캐시 안 함
```

이유:

최종 응답 전체를 Redis에 저장하면 `alreadyExists`, `matchedPoolId` 같은 DB 상태가 TTL 동안 stale해질 수 있다.

이번 방향에서는 `/api/locations/search` 자체에서 DB 매칭 정보를 제거하므로 위험은 줄지만, 그래도 최종 응답 캐시는 후순위로 둔다.

우선순위는 다음이다.

```text
1차 캐시:
Naver Local Search 결과
geocode 결과
reverse geocode 결과

2차 캐시:
/api/locations/search 최종 응답 전체
```

이번 구현에서는 1차 캐시만 한다.

---

### 9. Redis 설정

선택:

```text
Redis 하나 사용 가능
캐시 key에는 TTL 부여
큐 key에는 TTL 없음
maxmemory-policy는 volatile-lru 고려
```

의미:

```text
캐시 key는 TTL이 있으므로 메모리 부족 시 eviction 대상이 될 수 있다.
알림 queue key는 TTL이 없으므로 volatile-lru 정책에서는 삭제 대상이 아니다.
```

초기 개발/소규모 운영에서는 하나의 Redis로 시작할 수 있다.

운영 규모가 커지면 다음 분리를 검토한다.

```text
Redis for cache
Redis for queue/lock
```

---

## 구현 순서

추천 순서:

```text
1. /api/locations/search에서 matchedPoolId / alreadyExists 제거
2. LocationSearchCandidate 응답 영향 정리
3. /api/pools/location-candidates 신규 API 추가
4. 네이버 기반 주변 수영장 후보 검색 구현
5. location-candidates에서 이름/주소 exact match만으로 DB 중복 판정
6. /api/pools/from-location-candidate에서 좌표 fallback 제거
7. 프론트에 "{선택 위치} 기준 수영장 후보 보기" 접기/펼치기 섹션 추가
8. Redis 1차 캐시 추가
9. k6 baseline/after 비교
10. 보고서 업데이트
```

---

## 구현 전 k6 baseline 측정 계획

캐시 효과를 보려면 구현 전과 구현 후를 같은 조건으로 비교해야 한다.

추가된 공통 스크립트:

```text
ops/k6/scripts/cache-repeat-load.js
```

지원 target:

```text
location-search
geocode
reverse-geocode
nearby
pool-location-candidates
notice-scan
```

`pool-location-candidates` target은 스크립트에 준비되어 있지만, API가 아직 없으므로 현재 시점에는 실행하지 않는다. 이 target은 API 구현 직후, Redis 캐시 적용 전 baseline으로 사용한다.

---

## 공통 사전 준비

백엔드, Redis, k6 서비스가 사용할 Docker 네트워크를 준비한다.

```powershell
docker compose up -d --build backend redis
```

상태 확인:

```powershell
docker compose ps
```

백엔드 health 확인:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

결과 파일은 `ops/k6/results`에 저장된다. 생성된 result JSON은 Git에 올리지 않는다.

---

## 1. 기준 위치 검색 baseline

목적:

```text
/api/locations/search 현재 성능 측정
향후 기준 위치 검색 Local Search 캐시 효과 비교
```

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=location-search `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e QUERY="부천시청" `
  -e DISPLAY=10 `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-location-search-baseline-summary.json `
  --out json=/results/cache-location-search-baseline-raw.json
```

비교 대상:

```text
cache-location-search-after-summary.json
```

---

## 2. Geocode baseline

목적:

```text
주소 → 좌표 변환 캐시 효과 비교
```

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=geocode `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e ADDRESS="경기도 부천시 길주로 210" `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-geocode-baseline-summary.json `
  --out json=/results/cache-geocode-baseline-raw.json
```

비교 대상:

```text
cache-geocode-after-summary.json
```

---

## 3. Reverse Geocode baseline

목적:

```text
좌표 → 주소 변환 캐시 효과 비교
```

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=reverse-geocode `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e LATITUDE=37.5034 `
  -e LONGITUDE=126.7660 `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-reverse-geocode-baseline-summary.json `
  --out json=/results/cache-reverse-geocode-baseline-raw.json
```

비교 대상:

```text
cache-reverse-geocode-after-summary.json
```

---

## 4. DB 기반 가까운 수영장 baseline

목적:

```text
/api/pools/nearby 현재 성능 측정
좌표 bucket 기반 nearby 캐시를 나중에 붙일 경우 비교 가능
```

이번 선택의 핵심 캐시 대상은 아니지만, 프론트 흐름에서 기준 위치 선택 후 항상 호출되는 API라 baseline을 남겨둔다.

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=nearby `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e LATITUDE=37.5034 `
  -e LONGITUDE=126.7660 `
  -e LIMIT=20 `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-nearby-baseline-summary.json `
  --out json=/results/cache-nearby-baseline-raw.json
```

비교 대상:

```text
cache-nearby-after-summary.json
```

---

## 5. 주변 수영장 후보 API baseline

주의:

```text
이 명령은 /api/pools/location-candidates 구현 후,
Redis 캐시 적용 전에 실행한다.
```

목적:

```text
신규 주변 수영장 후보 API의 캐시 적용 전 성능 측정
```

명령:

```powershell
docker compose --profile loadtest run --rm `
  -e TARGET=pool-location-candidates `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.2 `
  -e LATITUDE=37.5034 `
  -e LONGITUDE=126.7660 `
  -e RADIUS=5000 `
  -e QUERY="체육센터" `
  k6 run /scripts/cache-repeat-load.js `
  --summary-export /results/cache-pool-location-candidates-baseline-summary.json `
  --out json=/results/cache-pool-location-candidates-baseline-raw.json
```

비교 대상:

```text
cache-pool-location-candidates-after-summary.json
```

---

## 6. 공지 스캔은 이번 구현의 직접 대상 아님

`notice-scan` target은 스크립트에 남겨둔다.

다만 이번 선택 사항은 위치 검색, 주변 수영장 후보, Redis 1차 캐시가 중심이므로 공지 스캔 baseline은 필수는 아니다.

필요하면 별도 실행:

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

---

## 캐시 구현 후 after 측정 규칙

캐시 적용 후에는 같은 명령을 사용하고 파일명만 `after`로 바꾼다.

예:

```text
baseline:
cache-location-search-baseline-summary.json

after:
cache-location-search-after-summary.json
```

캐시 warm 상태를 보려면 같은 after 명령을 연속 두 번 실행한다.

```text
1회차: cold 또는 semi-cold
2회차: warm
```

최종 비교는 다음을 본다.

```text
p95 latency
p99 latency
RPS
Naver API 호출 수
geocode 호출 수
reverse geocode 호출 수
Redis hit ratio
Redis memory usage
HTTP failure rate
응답 검증 check 성공률
```

k6는 사용자 관점의 응답 시간과 성공률을 보여준다. Naver 호출 수, Redis hit ratio, Redis memory usage는 백엔드 Micrometer metric, Redis INFO, Grafana/Prometheus 또는 로그를 함께 봐야 한다.

---

## 7. 전체 위치 탐색 사용자 흐름 baseline

API 단위 측정과 별도로, 실제 사용자가 화면에서 수행하는 흐름을 한 iteration으로 묶어 측정한다.

흐름:

```text
1. /api/locations/search
2. 선택 후보 주소로 /api/locations/geocode
3. /api/pools/nearby
4. /api/pools/location-candidates
5. 선택 사항: /api/pools/from-location-candidate
```

기본값은 DB를 변경하지 않기 위해 5번 시설 추가를 실행하지 않는다.

캐시 전 정상 latency baseline:

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=2 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=2 `
  -e STEP_SLEEP_SECONDS=0.1 `
  -e LOCATION_QUERY="부천시청" `
  -e LOCATION_DISPLAY=5 `
  -e NEARBY_LIMIT=10 `
  -e CANDIDATE_QUERY="체육센터" `
  -e CANDIDATE_DISPLAY=10 `
  -e RADIUS=5000 `
  k6 run /scripts/location-discovery-flow-load.js `
  --summary-export /results/location-discovery-flow-baseline_vus2-summary.json `
  --out json=/results/location-discovery-flow-baseline_vus2-raw.json
```

캐시 전 부하 baseline:

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=10 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e STEP_SLEEP_SECONDS=0 `
  -e LOCATION_QUERY="부천시청" `
  -e LOCATION_DISPLAY=5 `
  -e NEARBY_LIMIT=10 `
  -e CANDIDATE_QUERY="체육센터" `
  -e CANDIDATE_DISPLAY=10 `
  -e RADIUS=5000 `
  -e FAILURE_LOG_LIMIT_PER_VU=3 `
  k6 run /scripts/location-discovery-flow-load.js `
  --summary-export /results/location-discovery-flow-baseline_vus10-summary.json `
  --out json=/results/location-discovery-flow-baseline_vus10-raw.json
```

시설 추가까지 포함하려면 인증 쿠키와 함께 명시적으로 켠다. 이 명령은 DB 데이터를 변경하므로 반복 테스트용 기본값으로 쓰지 않는다.

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=1 `
  -e DURATION=30s `
  -e SLEEP_SECONDS=3 `
  -e CREATE_CANDIDATE=true `
  -e ACCESS_TOKEN="여기에_쿠키값만" `
  k6 run /scripts/location-discovery-flow-load.js `
  --summary-export /results/location-discovery-flow-create-baseline-summary.json `
  --out json=/results/location-discovery-flow-create-baseline-raw.json
```

비교 대상:

```text
location-discovery-flow-after-cold-summary.json
location-discovery-flow-after-warm-summary.json
```

---

## 결론

이번 선택의 핵심은 다음이다.

```text
/api/locations/search
→ 기준 위치 검색 전용

/api/pools/location-candidates
→ 기준 위치 주변 수영장 후보 검색

DB 중복 판정
→ 이름/주소 exact match only

Redis 캐시
→ 외부 API/geocode 결과 중심 1차 캐시
→ 최종 응답 2차 캐시는 보류
```

이 방향은 기준 위치 검색과 수영장 후보 검색의 책임을 분리하고, 좌표 기반 오탐을 줄이며, 외부 API 호출 비용만 캐시로 줄이는 구조다.
