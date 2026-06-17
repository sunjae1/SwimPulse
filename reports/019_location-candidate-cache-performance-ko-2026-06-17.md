# 위치 후보 검색 Redis 캐시 성능 비교

작성일: 2026-06-17

## 요약

이번 변경은 위치 검색 흐름을 두 단계로 분리하고, 외부 API 조회 결과에 Redis 1차 캐시를 적용한 작업이다.

- `/api/locations/search`는 기준 위치 후보만 반환한다.
- `LocationSearchCandidate`에서 `matchedPoolId`, `alreadyExists`를 제거했다.
- `/api/pools/location-candidates`가 주변 수영장 후보 탐색과 DB 중복 판정을 담당한다.
- DB 중복 판정은 정규화된 이름, 도로명주소, 지번주소 exact match만 사용한다.
- 수영장 추가 API에서 좌표 80m fallback 중복 판정을 제거했다.
- Redis에는 Naver Local Search, geocode, reverse geocode 결과를 캐시한다.
- 프론트에는 `{선택 위치} 기준 수영장 후보 보기` 접기/펼치기 섹션을 추가했다.

핵심 결과는 반복 요청이 더 이상 반복적인 Naver API 호출로 확장되지 않는다는 점이다. warm cache 기준 VUS10 부하에서 rate limit 실패가 사라졌다.

## 변경 후 API 책임

| API | 책임 | DB 중복 판정 | 외부 호출 |
|---|---|---:|---|
| `/api/locations/search` | `부천시청` 같은 기준 위치 후보 검색 | 없음 | Naver Local Search, 5분 캐시 |
| `/api/locations/geocode` | 선택한 주소를 좌표로 변환 | 없음 | Naver Geocoding, 성공 30일 / 실패 1일 캐시 |
| `/api/pools/nearby` | 선택 좌표 주변의 DB 수영장 조회 | DB 조회만 수행 | 없음 |
| `/api/pools/location-candidates` | DB에 없는 주변 시설 후보 검색 | 이름/주소 exact match | Reverse geocode, Local Search, 후보 geocode, 모두 캐시 |
| `/api/pools/from-location-candidate` | 선택한 후보를 pool로 생성 | 이름/주소 exact match | 요청에 좌표가 없을 때만 geocode |

## Redis Key와 TTL

| 캐시 | Namespace | TTL |
|---|---|---:|
| 기준 위치 검색 | `swimpulse:cache:location-search:v1:*` | 5분 |
| 수영장 후보 Local Search | `swimpulse:cache:pool-location-candidates:v1:*` | 1시간 |
| Geocode 성공 | `swimpulse:cache:geocode:v1:*` | 30일 |
| Geocode 실패 | `swimpulse:cache:geocode:v1:*` | 1일 |
| Reverse geocode 성공 | `swimpulse:cache:reverse-geocode:v1:*` | 7일 |
| Reverse geocode 실패 | `swimpulse:cache:reverse-geocode:v1:*` | 1일 |

Redis eviction 정책은 `volatile-lru`로 변경했다. 나중에 maxmemory를 설정하면 TTL이 있는 캐시 key가 큐나 락 key보다 먼저 정리될 수 있다.

테스트 후 로컬 Redis 메모리 상태:

| 항목 | 값 |
|---|---:|
| `used_memory_human` | 1.09M |
| `maxmemory_human` | 0B |
| `maxmemory_policy` | `volatile-lru` |

## 캐시 Hit Ratio

측정 기준: `/actuator/prometheus`의 `swimpulse_cache_access_total`.

| 캐시 | Hit | Miss | 대략적인 hit ratio |
|---|---:|---:|---:|
| `location-search` | 4,494 | 14 | 99.69% |
| `pool-location-candidates` | 4,149 | 3 | 99.93% |
| `geocode` | 22,079 | 21 | 99.90% |
| `reverse-geocode` | 4,138 | 14 | 99.66% |

위 값은 이번 테스트를 위해 백엔드를 재시작한 이후의 누적값이다.

## k6 결과

Baseline 파일은 기존에 `ops/k6/results`에 있던 결과를 사용했다. After 파일은 Redis 캐시 적용 후 백엔드 컨테이너를 다시 빌드하고 생성했다.

### VUS2 안정 부하

| 시나리오 | Baseline p95 | After cold p95 | After warm p95 | 기존 실패율 | Warm 실패율 |
|---|---:|---:|---:|---:|---:|
| 위치 검색 | 476.51ms | 33.33ms | 21.62ms | 0.00% | 0.00% |
| 수영장 후보 검색 | 481.14ms | 48.80ms | 49.67ms | 0.00% | 0.00% |
| 전체 탐색 흐름 | 579.18ms | 27.33ms | 24.11ms | 0.00% | 0.00% |

### VUS10 순간 부하

| 시나리오 | Baseline p95 | After warm p95 | 기존 실패율 | Warm 실패율 | 기존 RPS | Warm RPS |
|---|---:|---:|---:|---:|---:|---:|
| 위치 검색 | 1452.77ms | 9.84ms | 14.91% | 0.00% | 11.56 | 48.73 |
| 수영장 후보 검색 | 1360.60ms | 48.37ms | 17.14% | 0.00% | 11.99 | 42.86 |
| 전체 탐색 흐름 | 1336.33ms | 25.62ms | 5.24% | 0.00% | 21.52 | 73.62 |

### p95 차트

```text
VUS10 p95 latency

위치 검색             before 1452.77ms | ########################################
                     after     9.84ms | #

수영장 후보 검색      before 1360.60ms | #####################################
                     after    48.37ms | ##

전체 탐색 흐름        before 1336.33ms | ####################################
                     after    25.62ms | #
```

## 해석

이번 개선의 핵심은 Redis 자체의 속도보다, 하나의 사용자 요청이 반복적인 외부 API 호출로 커지는 문제를 막은 데 있다.

이전 흐름:

1. `/api/locations/search`를 반복 호출한다.
2. 매 요청마다 Naver Local Search를 호출한다.
3. VUS10에서는 Naver rate limit 성격의 실패가 발생한다.
4. p95가 1.3초 이상으로 올라가고 실패율이 생긴다.

변경 후 흐름:

1. 첫 요청이 Redis에 결과를 채운다.
2. 이후 요청은 Local Search, geocode, reverse geocode 결과를 재사용한다.
3. DB 중복 판정은 매번 다시 수행하므로 `alreadyExists`, `matchedPoolId`는 최신 DB 상태를 반영한다.
4. VUS10 warm cache 기준 실패율이 0%가 된다.

## 중요한 트레이드오프

이번 캐시는 전체 응답 캐시가 아니라 외부 API 결과에 대한 1차 캐시다.

그래서 다음 장점이 있다.

- Naver 결과는 빠르게 재사용한다.
- DB 중복 상태는 매 요청마다 다시 계산한다.
- 새로 추가된 pool을 즉시 반영할 수 있다.
- 짧은 TTL 중심이라 캐시 무효화가 단순하다.

초기 구현에서는 cold cache에서 같은 key로 동시 요청이 몰리면 여러 요청이 동시에 외부 API를 호출할 수 있었다. 이후 cache key별 Redis single-flight lock을 추가해, 첫 요청만 외부 API를 호출하고 나머지 요청은 짧게 대기한 뒤 Redis에 채워진 값을 재사용하도록 보강했다.

single-flight 기본값:

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `SWIMPULSE_CACHE_SINGLE_FLIGHT_LOCK_TTL_MS` | 3000ms | 외부 API 호출 담당 요청이 잡는 lock TTL |
| `SWIMPULSE_CACHE_SINGLE_FLIGHT_WAIT_TIMEOUT_MS` | 2000ms | lock을 못 잡은 요청이 cache 채워지기를 기다리는 최대 시간 |
| `SWIMPULSE_CACHE_SINGLE_FLIGHT_POLL_MS` | 50ms | 대기 중 Redis cache를 다시 확인하는 간격 |

## 결과 파일

| 시나리오 | 파일 |
|---|---|
| 위치 검색 baseline VUS2 | `ops/k6/results/cache-location-search-baseline_vus2-summary.json` |
| 위치 검색 after cold VUS2 | `ops/k6/results/cache-location-search-after-cold_vus2-summary.json` |
| 위치 검색 after warm VUS2 | `ops/k6/results/cache-location-search-after-warm_vus2-summary.json` |
| 위치 검색 baseline VUS10 | `ops/k6/results/cache-location-search-baseline_vus10-summary.json` |
| 위치 검색 after warm VUS10 | `ops/k6/results/cache-location-search-after-warm_vus10-summary.json` |
| 수영장 후보 baseline VUS2 | `ops/k6/results/cache-pool-location-candidates-baseline_vus2-summary.json` |
| 수영장 후보 after cold VUS2 | `ops/k6/results/cache-pool-location-candidates-after-cold_vus2-summary.json` |
| 수영장 후보 after warm VUS2 | `ops/k6/results/cache-pool-location-candidates-after-warm_vus2-summary.json` |
| 수영장 후보 baseline VUS10 | `ops/k6/results/cache-pool-location-candidates-baseline_vus10-summary.json` |
| 수영장 후보 after warm VUS10 | `ops/k6/results/cache-pool-location-candidates-after-warm_vus10-summary.json` |
| 전체 흐름 baseline VUS2 | `ops/k6/results/location-discovery-flow-baseline_vus2-summary.json` |
| 전체 흐름 after cold VUS2 | `ops/k6/results/location-discovery-flow-after-cold_vus2-summary.json` |
| 전체 흐름 after warm VUS2 | `ops/k6/results/location-discovery-flow-after-warm_vus2-summary.json` |
| 전체 흐름 baseline VUS10 | `ops/k6/results/location-discovery-flow-baseline_vus10-summary.json` |
| 전체 흐름 after warm VUS10 | `ops/k6/results/location-discovery-flow-after-warm_vus10-summary.json` |

