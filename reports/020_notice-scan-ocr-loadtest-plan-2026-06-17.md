# Notice Scan OCR Load Test Plan

작성일: 2026-06-17

이 문서는 OCR이 포함된 현재 공지 스캔 성능을 baseline으로 남기고, 이후 개선 작업 후 같은 조건으로 after를 비교하기 위한 k6 실행 방법을 정리한다.

## 목적

공지 스캔은 현재 다음 작업을 한 요청 안에서 수행할 수 있다.

- 저장된 `VERIFIED` 또는 `CANDIDATE` 공지 source 접근
- 상세 후보 URL 탐색
- 상세 페이지 HTML fetch
- rule 기반 기간 파싱
- HTML에서 기간을 못 찾으면 이미지 OCR retry
- 공지 및 구조화 기간 DB 저장

OCR은 이미지 다운로드와 Tesseract 프로세스를 포함하므로 p95가 크게 튈 수 있다. 개선 전후 비교에서는 단순 HTTP latency뿐 아니라 OCR 의심 요청, 추출 성공 수, 기간 추출 수를 같이 본다.

## 추가 스크립트

- `ops/k6/scripts/notice-scan-ocr-load.js`

주요 지표:

- `http_req_duration`
- `notice_scan_ocr_duration`
- `notice_scan_ocr_valid_response`
- `notice_scan_ocr_notice_count`
- `notice_scan_ocr_period_count`
- `notice_scan_ocr_scanned_link_count`
- `notice_scan_ocr_extracted_notices`
- `notice_scan_ocr_link_only_notices`
- `notice_scan_ocr_failed_notices`
- `notice_scan_ocr_shared_responses`
- `notice_scan_ocr_waited_responses`
- `notice_scan_ocr_latest_check_failures`
- `notice_scan_ocr_trace_ocr_mentions`

## Baseline 실행

OCR 개선 전 현재 상태를 baseline으로 저장한다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=baseline `
  -e VUS=3 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_IDS="10,13,16,22,23,28,30,32,33,36" `
  -e ACCESS_TOKEN="여기에_쿠키값만" `
  -e HTTP_P95_THRESHOLD="p(95)<20000" `
  k6 run /scripts/notice-scan-ocr-load.js `
  --summary-export /results/notice-scan-ocr-baseline-summary.json `
  --out json=/results/notice-scan-ocr-baseline-raw.json
```

특정 pool 하나가 17초 이상 걸리는지 확인하려면 단일 pool로 먼저 돌린다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=baseline-single `
  -e VUS=1 `
  -e DURATION=30s `
  -e SLEEP_SECONDS=1 `
  -e POOL_IDS="여기에_pool_id" `
  -e ACCESS_TOKEN="여기에_쿠키값만" `
  -e HTTP_P95_THRESHOLD="p(95)<25000" `
  k6 run /scripts/notice-scan-ocr-load.js `
  --summary-export /results/notice-scan-ocr-baseline-single-summary.json `
  --out json=/results/notice-scan-ocr-baseline-single-raw.json
```

## After 실행

OCR 캐싱, 백그라운드 OCR, OCR 조건 강화 등 개선 후 같은 조건으로 실행한다.

```powershell
docker compose --profile loadtest run --rm `
  -e RUN_LABEL=after `
  -e VUS=3 `
  -e DURATION=1m `
  -e SLEEP_SECONDS=0.5 `
  -e POOL_IDS="10,13,16,22,23,28,30,32,33,36" `
  -e ACCESS_TOKEN="여기에_쿠키값만" `
  -e HTTP_P95_THRESHOLD="p(95)<20000" `
  k6 run /scripts/notice-scan-ocr-load.js `
  --summary-export /results/notice-scan-ocr-after-summary.json `
  --out json=/results/notice-scan-ocr-after-raw.json
```

## 비교 기준

우선순위는 다음 순서로 본다.

1. `http_req_duration p95`
2. `http_req_duration p99`
3. `notice_scan_ocr_valid_response`
4. `notice_scan_ocr_trace_ocr_mentions`
5. `notice_scan_ocr_period_count`
6. `notice_scan_ocr_extracted_notices`
7. `notice_scan_ocr_failed_notices`

개선 후 기대값:

- p95 감소
- p99 감소
- valid response 유지 또는 증가
- 기간 추출 수 유지
- failed notice 수 증가 없음
- OCR mention 수 감소 또는 OCR 요청이 백그라운드로 이동

## 주의

- 같은 `poolId`에 동시 요청이 몰리면 notice scan lock 때문에 shared/waited response가 증가할 수 있다.
- OCR 개선 효과를 보려면 raw 파일에서 `pool_id` 태그별 latency도 함께 확인한다.
- raw 결과 파일은 Git에 올리지 않는다.
