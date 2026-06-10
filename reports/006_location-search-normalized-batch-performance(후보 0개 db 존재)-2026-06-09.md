# Location Search Normalized Batch Matching

## Goal

`GET /api/locations/search`가 매 요청마다 모든 `pools` 행을 읽고 Java에서 반복 정규화·중첩 비교하던 구조를 다음과 같이 변경했다.

- `pools`에 이름, 도로명주소, 지번주소 정규화 컬럼 추가
- 기존 132개 pool 데이터 Flyway backfill
- 신규 저장 및 수정 시 JPA callback으로 정규화 값 자동 갱신
- 네이버 검색 후보를 Java에서 한 번 정규화
- 후보 최대 10개의 정규화 값을 한 SQL `IN` 배치 조회로 전달
- 조회 결과를 이름·도로명주소·지번주소별 `HashMap<String, Pool>`로 구성
- 후보별 `matchedPoolId`를 HashMap 조회로 연결
- 정규화 일치가 없는 후보의 좌표 80m 중복 판정은 후보별 SQL 대신 한 번의 배치 CTE로 처리

정규화 컬럼 인덱스는 이번 변경에서 제외했다.

## Database Migration

Migration:

```text
V2__add_pool_normalized_search_columns.sql
```

Added columns:

```text
normalized_name
normalized_road_name_address
normalized_lot_number_address
```

Local MySQL validation:

```text
Successfully applied 1 migration
Current schema version: 2
Total pools: 132
Empty normalized names: 0
```

도로명주소가 원래 `NULL`인 22개 행은 `normalized_road_name_address=''`로 저장됐다.

## Test Conditions

Before and after used the same k6 settings:

```text
VUS=10
DURATION=1m
QUERY=서울 수영장
DISPLAY=10
SLEEP_SECONDS=1
```

Baseline:

```text
ops/k6/results/location-search-summary.json
```

After:

```text
ops/k6/results/location-search-normalized-batch-final-summary.json
```

The result JSON files are local generated artifacts and remain excluded by `.gitignore`.

## Result

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Requests | 442 | 438 | -0.90% |
| Throughput | 7.213 RPS | 7.143 RPS | -0.97% |
| Average | 370.75 ms | 382.35 ms | +3.13% |
| Median | 313.74 ms | 339.75 ms | +8.29% |
| p90 | 484.18 ms | 522.25 ms | +7.86% |
| p95 | 578.78 ms | 628.18 ms | +8.53% |
| p99 | 1410.21 ms | 815.41 ms | -42.18% |
| Maximum | 1449.99 ms | 1132.90 ms | -21.87% |
| HTTP failure rate | 0% | 0% | unchanged |

All response status and JSON-array checks passed.

## Interpretation

This run does not demonstrate a latency improvement for normalized matching.

During the final test:

```text
Location search requests observed: 439
Requests with exact normalized pool matches: 0
Naver candidates returned per request: 5
```

The tested query therefore did not exercise the optimized HashMap matching path. Most elapsed time still came from Naver Local Search and candidate Geocoding calls. Naver Local Search also uses `sort=random`, so candidate selection and external response time vary between runs.

The average and p95 changes are small enough to be external-call and run-to-run noise, while p99 and maximum improved substantially. A single before/after run cannot attribute either change confidently to this DB optimization.

The structural improvement is still valid:

- Removed `poolRepository.findAll()` from location search and facility-add duplicate matching.
- Existing pool strings are no longer normalized repeatedly for every candidate.
- Exact normalized matches return only relevant pool rows.
- Candidate-to-pool lookup is average `O(1)` through HashMap.
- Coordinate fallback no longer issues one distance query per candidate.

## Verification

```text
.\gradlew.bat test: passed
Docker backend rebuild: passed
Flyway V2 against MySQL: passed
Hibernate schema validation: passed
Location search smoke test: HTTP 200
k6 checks: 876/876 passed
```

## Better Follow-up Benchmark

To measure this optimization itself rather than Naver variability:

1. Use a deterministic stub response containing known existing pools.
2. Compare the old full-pool Java matching and new batch matching with the same candidate payload.
3. Repeat each scenario at least five times.
4. Add a larger generated pool dataset because 132 rows are too small for a meaningful DB matching difference.
5. Add normalized-column indexes as a separate experiment and compare again.
