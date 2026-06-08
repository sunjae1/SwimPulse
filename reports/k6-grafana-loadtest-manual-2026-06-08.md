# SwimPulse k6 + Grafana 부하 테스트 매뉴얼

작성일: 2026-06-08

이 문서는 SwimPulse 프로젝트에서 `Grafana + Prometheus + k6`로 부하 테스트를 수행하고, 결과를 파일로 남기고, 개선 전후를 비교하는 방법을 설명합니다.

관련 문서:

- [performance-measurement-manual-2026-06-06.md](./performance-measurement-manual-2026-06-06.md)
- [monitoring-thresholds-2026-06-07.md](./monitoring-thresholds-2026-06-07.md)
- [observability-manual-2026-06-06.md](./observability-manual-2026-06-06.md)

## 1. 먼저 짚고 갈 것

### `p95`는 요청이 100개 이상 있어야만 나오나

아닙니다.

- `p95`는 표본이 1개만 있어도 수학적으로는 계산될 수 있습니다.
- 다만 요청 수가 너무 적으면 값이 들쭉날쭉해서 신뢰도가 낮습니다.

쉽게 말하면:

- 요청 5개로 나온 `p95`
  참고는 가능하지만 의미가 약함
- 요청 수십~수백 개 이상으로 나온 `p95`
  비교 지표로 쓰기 훨씬 좋음

즉 "100건 이상이어야 반환된다"는 규칙은 아니고, "표본이 많을수록 의미가 좋아진다"가 맞습니다.

## 2. 이번에 추가한 구성

### Docker Compose 서비스

`docker-compose.yml`에 `k6` 서비스를 추가했습니다.

- 이미지: `grafana/k6:0.49.0`
- 기본 `BASE_URL`: `http://backend:8080`
- 결과 저장 폴더: `./ops/k6/results`

즉 k6는 별도 설치 없이 Docker로 바로 실행할 수 있습니다.

### 추가된 스크립트

- [nearby-load.js](../ops/k6/scripts/nearby-load.js)
- [location-search-load.js](../ops/k6/scripts/location-search-load.js)
- [notice-scan-load.js](../ops/k6/scripts/notice-scan-load.js)

### 결과 저장 위치

- 호스트 경로: `ops/k6/results/`
- Git에는 `.gitkeep`만 남기고, 실제 결과 파일은 `.gitignore`로 제외했습니다.

즉 결과는 로컬 디스크에 남고, 저장소는 지저분해지지 않습니다.

## 3. 기본 실행 순서

### 1) 관측 스택 올리기

프로젝트 루트에서:

```powershell
docker compose up -d --build
```

### 2) Grafana 열기

- URL: `http://localhost:3001`
- ID: `admin`
- Password: `swimpulse-admin`

### 3) 대시보드 열기

`SwimPulse / SwimPulse Overview`

### 4) k6 실행

다른 터미널에서 아래 명령을 실행합니다.

## 4. 공개 API 부하 테스트 명령

### 근처 수영장 조회

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=10 `
  -e DURATION=1m `
  -e LATITUDE=37.5665 `
  -e LONGITUDE=126.9780 `
  -e LIMIT=20 `
  k6 run /scripts/nearby-load.js `
  --summary-export /results/nearby-summary.json `
  --out json=/results/nearby-raw.json
```

### 위치 검색

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=10 `
  -e DURATION=1m `
  -e QUERY="서울 수영장" `
  -e DISPLAY=10 `
  k6 run /scripts/location-search-load.js `
  --summary-export /results/location-search-summary.json `
  --out json=/results/location-search-raw.json
```

## 5. 인증이 필요한 공지 스캔 테스트

`/api/pools/{poolId}/notices/scan`은 인증이 필요합니다.

현재 프로젝트는 `swimpulse_access_token` 쿠키로 인증하므로, 브라우저 로그인 후 해당 쿠키 값을 가져와서 넣으면 됩니다.

### ACCESS_TOKEN만 넣는 방식

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=1 `
  -e DURATION=30s `
  -e POOL_ID=1 `
  -e ACCESS_TOKEN="여기에_쿠키값만" `
  k6 run /scripts/notice-scan-load.js `
  --summary-export /results/notice-scan-summary.json `
  --out json=/results/notice-scan-raw.json
```

### Cookie 헤더 전체를 넣는 방식

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=1 `
  -e DURATION=30s `
  -e POOL_ID=1 `
  -e COOKIE_HEADER="swimpulse_access_token=여기에_쿠키값" `
  k6 run /scripts/notice-scan-load.js `
  --summary-export /results/notice-scan-summary.json `
  --out json=/results/notice-scan-raw.json
```

주의:

- 공지 스캔은 같은 `poolId`에 대해 Redis 락이 걸려 있으므로 동시 요청 시 일부 요청은 `already running` 성격의 응답을 받을 수 있습니다.
- 이 API는 외부 사이트 응답 속도 영향을 많이 받습니다.

## 6. 결과 파일이 무엇을 뜻하나

### `--summary-export`

예:

- `nearby-summary.json`
- `location-search-summary.json`

이 파일은 테스트 전체 요약입니다.

보통 들어있는 값:

- 요청 수
- 실패율
- 평균 응답시간
- p90, p95, p99

비교용으로 가장 보기 쉽습니다.

### `--out json=...`

예:

- `nearby-raw.json`
- `location-search-raw.json`

이 파일은 더 자세한 원시 이벤트 로그입니다.

용도:

- 나중에 세밀하게 분석
- 특정 요청 타이밍 확인
- 스크립트로 후처리

## 7. 보통 어떻게 진행하나

처음에는 아래 흐름으로 하면 됩니다.

1. Grafana를 열어둡니다.
2. 요청이 없을 때 기준선 2~3분을 봅니다.
3. `k6` 스크립트를 실행합니다.
4. Grafana에서 `p95`, `req/s`, `CPU`, `Heap`, `Hikari pending`, `5xx`를 봅니다.
5. `summary.json` 파일을 남깁니다.
6. 코드 수정 후 같은 명령을 다시 돌립니다.
7. 결과를 전후 비교합니다.

중요:

- 놓쳤다고 끝이 아닙니다.
- Prometheus가 시계열 데이터를 저장하므로 나중에 같은 시간 구간을 다시 볼 수 있습니다.
- 다만 `summary.json`도 같이 남겨두면 전후 비교가 훨씬 편합니다.

## 8. 어떤 패널을 같이 보면 좋은가

### `/api/pools/nearby` 테스트 시

- `Nearby Pools p95 Latency`
- `Top 5 Slow APIs p95`
- `All API Request Rate`
- `Process CPU`
- `Hikari Pending Connections`

### `/api/locations/search` 테스트 시

- `Top 5 Slow APIs p95`
- `All API Request Rate`
- `HTTP 5xx Error Rate`
- `Process CPU`

### `/api/pools/{poolId}/notices/scan` 테스트 시

- `Notice Scan p95 Latency`
- `HTTP 4xx Error Rate`
- `HTTP 5xx Error Rate`
- `Process CPU`

## 9. 전후 비교를 잘하려면

항상 아래 조건을 맞추는 것이 좋습니다.

- 같은 스크립트
- 같은 `VUS`
- 같은 `DURATION`
- 같은 대상 API
- 가능한 비슷한 데이터 상태

예를 들어:

- 변경 전: `nearby-load.js`, `VUS=10`, `DURATION=1m`
- 변경 후: 똑같이 `nearby-load.js`, `VUS=10`, `DURATION=1m`

이렇게 해야 `p95`와 실패율 비교가 의미가 있습니다.

## 10. 처음엔 이렇게 시작하면 충분

추천 시작 시나리오:

### 시나리오 A. nearby

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=10 `
  -e DURATION=1m `
  k6 run /scripts/nearby-load.js `
  --summary-export /results/nearby-summary.json
```

### 시나리오 B. location search

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=10 `
  -e DURATION=1m `
  -e QUERY="서울 수영장" `
  k6 run /scripts/location-search-load.js `
  --summary-export /results/location-search-summary.json
```

### 시나리오 C. notice scan

```powershell
docker compose --profile loadtest run --rm `
  -e VUS=1 `
  -e DURATION=30s `
  -e POOL_ID=1 `
  -e ACCESS_TOKEN="여기에_쿠키값" `
  k6 run /scripts/notice-scan-load.js `
  --summary-export /results/notice-scan-summary.json
```

## 11. 해석 팁

- 요청량은 늘었는데 `p95`와 `5xx`가 안정적
  잘 버틴 것
- `p95`가 뛰고 `Hikari pending`도 뜸
  DB 병목 의심
- `p95`가 뛰고 `CPU`도 뜸
  계산/파싱/크롤링 로직 병목 의심
- `5xx`가 오름
  단순 느림이 아니라 오류 상황

## 12. 다음 확장 아이디어

- `k6` 결과 요약을 `reports/`에 자동 정리하는 스크립트 추가
- 테스트 시작/종료 시각과 Prometheus 조회를 연결해 비교 보고서 자동 생성
- 관리자 전용 운영 문서에 테스트 결과 누적 기록
