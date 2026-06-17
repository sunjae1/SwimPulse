# Location Candidate Flow Redis Cache Performance

Date: 2026-06-17

## Summary

This change separates the two location flows and applies first-layer Redis caching to external lookup results.

- `/api/locations/search` now returns only location search candidates.
- `matchedPoolId` and `alreadyExists` were removed from `LocationSearchCandidate`.
- `/api/pools/location-candidates` now owns nearby pool candidate discovery and DB duplicate detection.
- DB duplicate detection uses exact matching only: normalized name, road address, and lot address.
- Coordinate fallback duplicate matching was removed from pool creation.
- Redis caches Naver Local Search, geocode, and reverse geocode results.
- Frontend now shows additional facility candidates behind a collapsible button: `{선택 위치} 기준 수영장 후보 보기`.

The important result: repeated location discovery requests no longer expand into repeated Naver calls, and VUS10 rate-limit failures disappeared in the warm-cache runs.

## API Responsibility After Change

| API | Responsibility | DB duplicate check | External calls |
|---|---|---:|---|
| `/api/locations/search` | Search candidate locations such as `부천시청` | No | Naver Local Search, cached 5m |
| `/api/locations/geocode` | Convert selected address to coordinates | No | Naver Geocoding, cached 30d success / 1d miss |
| `/api/pools/nearby` | Show pools already in our DB near selected coordinates | DB query only | None |
| `/api/pools/location-candidates` | Search additional nearby facility candidates | Exact name/address only | Reverse geocode, Local Search, candidate geocode, all cached |
| `/api/pools/from-location-candidate` | Create a pool from selected candidate | Exact name/address only | Geocode only if request has no coordinates |

## Redis Keys And TTL

| Cache | Namespace | TTL |
|---|---|---:|
| Location search | `swimpulse:cache:location-search:v1:*` | 5 minutes |
| Pool location candidates local search | `swimpulse:cache:pool-location-candidates:v1:*` | 1 hour |
| Geocode success | `swimpulse:cache:geocode:v1:*` | 30 days |
| Geocode miss | `swimpulse:cache:geocode:v1:*` | 1 day |
| Reverse geocode success | `swimpulse:cache:reverse-geocode:v1:*` | 7 days |
| Reverse geocode miss | `swimpulse:cache:reverse-geocode:v1:*` | 1 day |

Redis policy was changed to `volatile-lru`, so if a memory limit is set later, TTL-based cache keys are eviction candidates before queue or lock keys without TTL.

Current local Redis memory after tests:

| Metric | Value |
|---|---:|
| `used_memory_human` | 1.09M |
| `maxmemory_human` | 0B |
| `maxmemory_policy` | `volatile-lru` |

## Cache Hit Ratio

Metric source: `/actuator/prometheus`, metric `swimpulse_cache_access_total`.

| Cache | Hit | Miss | Approx hit ratio |
|---|---:|---:|---:|
| `location-search` | 4,494 | 14 | 99.69% |
| `pool-location-candidates` | 4,149 | 3 | 99.93% |
| `geocode` | 22,079 | 21 | 99.90% |
| `reverse-geocode` | 4,138 | 14 | 99.66% |

These are cumulative since the backend restart used for this test run.

## k6 Results

Baseline files were already present under `ops/k6/results`. After files were generated after rebuilding the backend container with Redis cache enabled.

### VUS2 Stable Load

| Scenario | Baseline p95 | After cold p95 | After warm p95 | Failure before | Failure after warm |
|---|---:|---:|---:|---:|---:|
| Location search | 476.51ms | 33.33ms | 21.62ms | 0.00% | 0.00% |
| Pool location candidates | 481.14ms | 48.80ms | 49.67ms | 0.00% | 0.00% |
| Full discovery flow | 579.18ms | 27.33ms | 24.11ms | 0.00% | 0.00% |

### VUS10 Burst Load

| Scenario | Baseline p95 | After warm p95 | Baseline failure | After warm failure | Baseline RPS | After warm RPS |
|---|---:|---:|---:|---:|---:|---:|
| Location search | 1452.77ms | 9.84ms | 14.91% | 0.00% | 11.56 | 48.73 |
| Pool location candidates | 1360.60ms | 48.37ms | 17.14% | 0.00% | 11.99 | 42.86 |
| Full discovery flow | 1336.33ms | 25.62ms | 5.24% | 0.00% | 21.52 | 73.62 |

### p95 Chart

```text
VUS10 p95 latency

Location search          before 1452.77ms | ########################################
                         after     9.84ms | #

Pool location candidates before 1360.60ms | #####################################
                         after    48.37ms | ##

Full discovery flow      before 1336.33ms | ####################################
                         after    25.62ms | #
```

## Interpretation

The biggest win is not raw Redis speed. The bigger win is preventing one user request from becoming repeated Naver calls.

Before:

1. Repeat `/api/locations/search`.
2. Each request calls Naver Local Search.
3. Under VUS10, Naver starts returning rate-limit style failures.
4. p95 rises above 1.3s and failure rate appears.

After:

1. First request fills Redis.
2. Later requests reuse Local Search, geocode, and reverse-geocode results.
3. DB matching still runs, so `alreadyExists/matchedPoolId` stays current.
4. VUS10 warm failure rate becomes 0%.

## Important Tradeoff

The cache is a first-layer external API cache, not a full response cache.

That means:

- Naver result reuse is fast.
- DB duplicate status is still recalculated every time.
- Newly added pools can be reflected immediately.
- Cache invalidation stays simple because short TTLs do most of the work.

The tradeoff is that cold-cache concurrent requests can still race to fill the same key. In this run, cold-cache p95 was already low, but if production sees thundering herd behavior, add a short Redis single-flight lock per cache key.

## Result Files

| Scenario | File |
|---|---|
| Location search baseline VUS2 | `ops/k6/results/cache-location-search-baseline_vus2-summary.json` |
| Location search after cold VUS2 | `ops/k6/results/cache-location-search-after-cold_vus2-summary.json` |
| Location search after warm VUS2 | `ops/k6/results/cache-location-search-after-warm_vus2-summary.json` |
| Location search baseline VUS10 | `ops/k6/results/cache-location-search-baseline_vus10-summary.json` |
| Location search after warm VUS10 | `ops/k6/results/cache-location-search-after-warm_vus10-summary.json` |
| Pool candidates baseline VUS2 | `ops/k6/results/cache-pool-location-candidates-baseline_vus2-summary.json` |
| Pool candidates after cold VUS2 | `ops/k6/results/cache-pool-location-candidates-after-cold_vus2-summary.json` |
| Pool candidates after warm VUS2 | `ops/k6/results/cache-pool-location-candidates-after-warm_vus2-summary.json` |
| Pool candidates baseline VUS10 | `ops/k6/results/cache-pool-location-candidates-baseline_vus10-summary.json` |
| Pool candidates after warm VUS10 | `ops/k6/results/cache-pool-location-candidates-after-warm_vus10-summary.json` |
| Full flow baseline VUS2 | `ops/k6/results/location-discovery-flow-baseline_vus2-summary.json` |
| Full flow after cold VUS2 | `ops/k6/results/location-discovery-flow-after-cold_vus2-summary.json` |
| Full flow after warm VUS2 | `ops/k6/results/location-discovery-flow-after-warm_vus2-summary.json` |
| Full flow baseline VUS10 | `ops/k6/results/location-discovery-flow-baseline_vus10-summary.json` |
| Full flow after warm VUS10 | `ops/k6/results/location-discovery-flow-after-warm_vus10-summary.json` |

