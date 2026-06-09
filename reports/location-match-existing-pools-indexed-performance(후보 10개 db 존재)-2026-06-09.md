# Existing Pool Candidate Matching Performance With Normalized Indexes

## Goal

`pools`에 이미 존재하는 후보 10개를 배치 정규화 매칭할 때, 정규화 컬럼 인덱스 추가 전후를 같은 조건으로 다시 비교했다.

- baseline: `normalized_*` 컬럼만 있고 인덱스는 없음
- indexed: `normalized_*` 3개 컬럼에 개별 BTREE 인덱스 추가

비교 대상은 `batch` 전략이다.

## Applied Change

Migration:

```text
backend/src/main/resources/db/migration/V3__add_pool_normalized_search_indexes.sql
```

Indexes:

```sql
CREATE INDEX idx_pools_normalized_name
    ON pools (normalized_name);

CREATE INDEX idx_pools_normalized_road_name_address
    ON pools (normalized_road_name_address);

CREATE INDEX idx_pools_normalized_lot_number_address
    ON pools (normalized_lot_number_address);
```

## Conditions

```text
Database pools: 132
Candidates per request: 10
Matched candidates per request: 10
VUs: 10
Duration: 30s per strategy
Sleep: 0.1s per iteration
Execution: legacy 30s, 5s pause, batch 30s
Load test endpoint: /internal/loadtest/location-match
```

Script:

```text
ops/k6/scripts/location-match-existing-load.js
```

Result files:

```text
ops/k6/results/location-match-existing-noindex-summary.json
ops/k6/results/location-match-existing-indexed-summary.json
```

위 JSON 파일은 로컬 생성 산출물이라 `.gitignore`로 제외된다.

## Result

| Metric | No Index | Indexed | Change |
|---|---:|---:|---:|
| Batch completed requests | 2,659 | 2,670 | +0.41% |
| Batch throughput | 88.63 RPS | 89.00 RPS | +0.41% |
| Batch HTTP average | 12.02 ms | 11.65 ms | 3.09% faster |
| Batch HTTP median | 9.81 ms | 11.28 ms | 15.01% slower |
| Batch HTTP p95 | 18.23 ms | 16.03 ms | 12.08% faster |
| Batch HTTP p99 | 68.73 ms | 21.10 ms | 69.29% faster |
| Batch server average | 6.86 ms | 7.12 ms | 3.69% slower |
| Batch server p95 | 11.98 ms | 10.68 ms | 10.82% faster |
| Batch server p99 | 30.12 ms | 14.73 ms | 51.11% faster |
| Match failures | 0 | 0 | unchanged |

All 10,242 k6 checks passed on the indexed run. All requests returned 10 candidates and 10 matches.

## Interpretation

정규화 컬럼 인덱스를 넣어도 현재 `pools` 데이터가 132행뿐이라 평균 성능은 크게 달라지지 않았다.

- 처리량 증가는 거의 없었다.
- 평균값과 중앙값은 run-to-run noise 범위에 가깝다.
- 대신 tail latency는 좋아졌다.
  `batch` HTTP p95는 `18.23 ms -> 16.03 ms`, p99는 `68.73 ms -> 21.10 ms`로 줄었다.
  서버 내부 시간도 p95, p99 기준으로 개선됐다.

즉 현재 데이터 크기에서는 "확실한 대폭 개선"보다는 "최악 구간 완화" 정도로 보는 편이 맞다.

## Query Plan Note

인덱스 적용 후 실제 유사 쿼리에 `EXPLAIN FORMAT=TREE`를 실행했을 때, MySQL 8 옵티마이저는 여전히 `pools` 전체 스캔을 선택했다.

```text
-> Table scan on pools  (cost=14.5 rows=132)
```

이건 인덱스가 잘못 만들어졌다는 뜻이 아니라, 현재 테이블이 너무 작아서 풀스캔 비용이 더 싸다고 판단한 것이다.

그래서 이번 측정 결과가 "인덱스 추가 = 즉시 큰 폭 개선"으로 나오지 않은 것이 자연스럽다.

## Conclusion

정규화 컬럼 인덱스는 넣어둘 가치가 있다. 다만 현재 132행 규모에서는 체감 차이가 크지 않다.

- 지금 단계: 배치 정규화 + `HashMap` 매칭 자체가 이미 가장 큰 개선이다.
- 데이터가 수백~수천 행으로 늘어나면 인덱스 효과가 더 분명해질 가능성이 높다.
- 더 큰 개선이 필요하면 다음 우선순위는 인덱스보다도 후보 수 축소, 외부 API 호출 수 감소, 캐시 전략 강화 쪽이다.
