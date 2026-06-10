# Existing Pool Candidate Matching Performance

## Goal

네이버 검색 후보 10개가 모두 `pools`에 존재하는 조건에서 아래 두 방식을 동일하게 비교한다.

- `legacy`: 전체 pool 조회 후 후보 10개와 Java 중첩 비교, DB 원본 문자열 반복 정규화
- `batch`: 후보만 정규화하고 정규화 컬럼 `IN` 배치 조회 후 `HashMap` 매칭

네이버 Local Search와 Geocoding 응답 시간은 이 측정에서 제외했다.

## Test Support

운영 API에는 영향을 주지 않도록 다음 설정이 `true`일 때만 측정용 엔드포인트가 생성된다.

```text
SWIMPULSE_LOADTEST_ENABLED=true
```

Endpoint:

```text
GET /internal/loadtest/location-match?strategy=legacy
GET /internal/loadtest/location-match?strategy=batch
```

기본값은 `false`다.

## Conditions

```text
Database pools: 132
Candidates per request: 10
Matched candidates per request: 10
Normalized-column indexes: none
VUs: 10
Duration: 30s per strategy
Sleep: 0.1s per iteration
Execution: legacy 30s, 5s pause, batch 30s
```

Script:

```text
ops/k6/scripts/location-match-existing-load.js
```

Result:

```text
ops/k6/results/location-match-existing-final-summary.json
```

The result JSON remains a local generated artifact excluded by `.gitignore`.

## Result

| Metric | Legacy | Batch + HashMap | Improvement |
|---|---:|---:|---:|
| Completed requests | 2,213 | 2,497 | +12.83% |
| Throughput | 73.77 RPS | 83.23 RPS | +12.83% |
| HTTP average | 34.46 ms | 19.10 ms | 44.57% faster |
| HTTP median | 36.04 ms | 18.81 ms | 47.81% faster |
| HTTP p95 | 53.41 ms | 36.37 ms | 31.90% faster |
| HTTP p99 | 56.09 ms | 38.91 ms | 30.63% faster |
| Server average | 27.77 ms | 11.54 ms | 58.44% faster |
| Server p95 | 42.99 ms | 23.72 ms | 44.82% faster |
| Match failures | 0 | 0 | unchanged |

All 9,424 k6 checks passed. All requests returned 10 candidates and 10 matches. No iterations were interrupted.

## Interpretation

DB에 이미 있는 후보를 검색하는 경우 정규화 배치 조회와 `HashMap` 매칭이 명확하게 빠르다.

- 전체 132개 엔티티를 매 요청마다 읽지 않는다.
- DB 문자열 132개를 후보마다 반복 정규화하지 않는다.
- 조회된 pool을 map으로 만든 뒤 후보별 평균 `O(1)` 조회를 수행한다.
- 0.1초 sleep이 처리량 차이를 일부 가리므로, 서버 내부 시간 감소가 최적화 효과를 더 직접적으로 보여준다.

이 결과는 후보 매칭 단계만 측정한 것이다. 실제 `/api/locations/search` 전체 응답 시간에는 네이버 Local Search와, 미매칭 후보에 대한 Geocoding 시간이 추가된다.

현재 데이터가 132행이라 인덱스 없이도 개선됐다. pool 데이터가 커지면 정규화 컬럼 인덱스를 별도 실험하는 것이 다음 단계다.
