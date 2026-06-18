# 공지 OCR 백그라운드 보강 성능 비교

작성일: 2026-06-17

## 요약

공지 스캔 요청 경로에서 이미지 OCR을 제거하고, OCR이 필요한 공지는 Redis 큐에 넣어 백그라운드 워커가 보강하도록 변경했다.

결과적으로 사용자 요청의 긴 꼬리 지연이 크게 줄었다. 기존에는 OCR이 요청 스레드 안에서 실행되어 일부 요청이 16~17초까지 늘어졌지만, 개선 후 같은 k6 시나리오에서 최대 응답 시간은 859ms였다.

## 변경 내용

| 항목 | 이전 | 이후 |
|---|---:|---:|
| 사용자 요청 중 OCR | HTML 파싱 실패 시 즉시 실행 | 실행하지 않음 |
| OCR 실행 위치 | HTTP 요청 스레드 | `NoticeOcrWorker` 스케줄러 |
| OCR 대상 전달 | 즉시 메서드 호출 | Redis list queue |
| OCR 결과 재사용 | 없음 | 이미지 URL 기준 Redis 캐시 |
| 단계별 관측 | HTTP/k6 중심 | 백엔드 phase 로그 + Micrometer metric |

## 구현 지점

- `NoticeCrawlerService`
  - 요청 경로에서는 rule 기반 파싱까지만 수행한다.
  - 이미지 후보가 있고 기간을 못 찾으면 `NoticeOcrQueuePublisher`로 OCR 보강 작업을 큐잉한다.
  - 백그라운드 OCR 성공 시 기존 `pool_notices`와 `notice_registration_periods`를 갱신한다.
  - `source_fetch`, `detail_fetch`, `detail_rule_parse`, `background_ocr_enrich`, `ocr_extract` phase metric을 남긴다.

- `NoticeOcrQueuePublisher`
  - 트랜잭션 commit 이후 Redis queue에 `noticeId`를 넣는다.

- `NoticeOcrWorker`
  - 주기적으로 Redis queue에서 `noticeId`를 가져와 OCR 보강을 수행한다.

- `TesseractNoticeImageOcrService`
  - 이미지 URL 기준 OCR 결과를 Redis에 캐시한다.
  - `download`, `process` phase metric을 남긴다.

## k6 비교 조건

같은 스크립트와 같은 pool set을 사용했다.

- 스크립트: `ops/k6/scripts/notice-scan-ocr-load.js`
- pool ids: `10,13,16,22,23,28,30,32,33,36`
- VUs: `3`
- duration: `1m`
- sleep: `0.5s`

주의: 개선 후 측정은 기존 DB를 파괴적으로 초기화하지 않고 진행했다. 따라서 완전한 cold crawl 비교라기보다는, 같은 서비스 상태에서 “요청 경로 OCR 제거 후 사용자 체감 지연이 어떻게 변했는지”를 본 결과다.

## 결과

| 측정 | 요청 수 | 실패율 | valid | 평균 | 중앙값 | p95 | p99 | 최대 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline multi | 213 | 0.93% | 99.06% | 349.66ms | 129.28ms | 301.63ms | 13.37s | 16.65s |
| baseline single pool 23 | 12 | 0% | 100% | 1.56s | 146.47ms | 7.80s | 15.25s | 17.11s |
| after multi | 292 | 0% | 100% | 117.50ms | 107.82ms | 275.29ms | 406.07ms | 859.34ms |

## 개선 폭

baseline multi 대비:

| 지표 | 개선 |
|---|---:|
| 평균 응답 시간 | 349.66ms → 117.50ms, 약 66.4% 감소 |
| p95 | 301.63ms → 275.29ms, 약 8.7% 감소 |
| p99 | 13.37s → 406.07ms, 약 97.0% 감소 |
| 최대 응답 시간 | 16.65s → 859.34ms, 약 94.8% 감소 |
| 처리량 | 3.52 req/s → 4.84 req/s, 약 37.6% 증가 |
| 실패 응답 | 2건 → 0건 |

단일 pool 23 baseline 대비:

| 지표 | 개선 |
|---|---:|
| p95 | 7.80s → 275.29ms |
| p99 | 15.25s → 406.07ms |
| 최대 응답 시간 | 17.11s → 859.34ms |

간단히 보면, “대부분 빠르지만 가끔 17초 걸리는 요청”이 “항상 1초 안쪽으로 끝나는 요청”에 가까워졌다.

## 백엔드 메트릭 확인

Prometheus에서 확인된 신규 metric:

```text
swimpulse_notice_phase_duration_seconds
swimpulse_notice_ocr_phase_duration_seconds
swimpulse_notice_ocr_queued_total
swimpulse_cache_access_total{cache="notice-ocr", ...}
```

측정 중 관측된 값:

| metric | 값 |
|---|---:|
| `swimpulse_notice_ocr_queued_total{pool_id="23"}` | 5 |
| `swimpulse_cache_access_total{cache="notice-ocr", result="miss"}` | 7 |
| `swimpulse_cache_access_total{cache="notice-ocr", result="put"}` | 7 |
| OCR `download` 총 시간 | 0.39s / 7회 |
| OCR `process` 총 시간 | 16.42s / 7회 |
| OCR `process` 최대 | 3.66s |
| `background_ocr_enrich` 총 시간 | 17.11s / 5회 |

이 값은 OCR이 사라진 것이 아니라, 사용자 요청 경로 밖으로 이동했다는 것을 보여준다. 즉 OCR 비용은 여전히 존재하지만 HTTP 응답 지연을 직접 늘리지 않는다.

## 해석

이번 병목은 OCR의 평균 시간이 아니라 꼬리 지연이었다. baseline multi의 p95는 이미 300ms 근처였지만 p99와 max가 13~17초로 튀었다. 즉 평소에는 빠른데, 이미지 OCR이 걸리는 순간 요청이 길게 붙잡히는 구조였다.

개선 후에는 OCR이 백그라운드에서 실행되므로 p99가 406ms로 내려갔다. 이 변화가 사용자 체감에는 가장 크다. 사용자는 “공지 확인” 버튼을 눌렀을 때 오래 기다리지 않고, OCR 보강 결과는 이후 재조회에서 반영된다.

## 남은 개선점

1. OCR 큐 중복 제거

현재 같은 이미지/공지에 대해 짧은 시간 안에 중복 큐잉될 수 있다. `noticeId` 기준 짧은 TTL lock을 두면 같은 공지를 여러 번 OCR queue에 넣는 일을 줄일 수 있다.

2. OCR 워커 동시성 분리

현재 스케줄러 thread에서 OCR을 처리한다. OCR이 무거워질수록 별도 executor나 worker service로 분리하는 편이 좋다.

3. OCR 성공률 판단 개선

이번 측정에서 pool 23의 OCR 보강은 실행됐지만 기간 추출은 대부분 `LINK_ONLY`로 남았다. 성능 문제는 해결됐지만, OCR 텍스트에서 기간을 더 잘 뽑는 로직은 별도 개선 대상이다.

4. Grafana 패널 추가

다음 패널을 추가하면 병목 추적이 쉬워진다.

```promql
rate(swimpulse_notice_ocr_queued_total[5m])
sum by (phase) (rate(swimpulse_notice_phase_duration_seconds_sum[5m]))
sum by (phase) (rate(swimpulse_notice_ocr_phase_duration_seconds_sum[5m]))
rate(swimpulse_cache_access_total{cache="notice-ocr"}[5m])
```

## 결론

요청 경로에서 OCR을 제거한 방향은 맞다. OCR을 백그라운드 보강으로 옮기면서 p99와 최대 지연이 크게 줄었고, 실패 응답도 사라졌다.

다음 단계는 OCR 큐 중복 방지와 OCR 파싱 정확도 개선이다. 성능 관점에서는 이미 큰 불은 껐다.
