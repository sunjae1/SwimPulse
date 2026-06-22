# 수영장 대표 이미지 보강: og:image, favicon, 기본 상어 로고

작성일: 2026-06-22

## 요약

수영장 카드 대표 이미지가 없을 때 화면이 밋밋하거나 placeholder가 깨져 보이는 문제를 줄이기 위해 대표 이미지 보강 흐름을 3단계로 확장했다.

| 순서 | 전략 | 설명 |
|---:|---|---|
| 1 | 공식 홈페이지 대표 이미지 | `og:image`, `twitter:image`, `image_src`, 본문 이미지 후보를 검사 |
| 2 | favicon / apple-touch-icon | 대표 이미지가 없으면 공식 홈페이지의 아이콘을 후보로 사용 |
| 3 | 기본 상어 수영 로고 | favicon도 없거나 홈페이지가 없어도 `/swimpulse-pool-shark.png`를 저장 |

핵심 변화는 “못 찾으면 `image_url = null` 유지”가 아니라, 마지막에는 기본 로고 경로라도 저장한다는 점이다. 그래서 한 번 favicon/default 보강을 실행하면 다음 배치에서 같은 pool이 계속 `processedPools`로 잡히는 현상이 줄어든다.

## DB 영향

새 migration은 없다. 기존 컬럼을 그대로 사용한다.

```sql
pools.image_url
```

이미지 바이너리나 blob은 DB에 저장하지 않는다. favicon이나 기본 로고도 URL 문자열만 저장한다.

예:

```text
https://example.or.kr/favicon.ico
/swimpulse-pool-shark.png
```

## 백엔드 구현

| 파일 | 내용 |
|---|---|
| `PoolImageEnrichmentService` | 대표 이미지 → favicon → 기본 로고 순서로 `image_url` 보강 |
| `PoolImagePageClient` | 홈페이지 HTML fetch 및 이미지 URL probe 인터페이스 |
| `JsoupPoolImagePageClient` | Jsoup + Java HttpClient 구현 |
| `PoolImageEnrichmentResult` | 단건 enrich 결과 |
| `PoolImageEnrichmentResponse` | 배치 enrich 집계 |
| `PoolController` | 이미지 enrich API와 favicon/default enrich API 제공 |
| `PoolService` | `image_url`이 비어 있는 pool만 대상으로 배치 처리 |

## 이미지 후보 우선순위

대표 이미지 enrich는 아래 후보를 먼저 본다.

| 우선순위 | 후보 |
|---:|---|
| 1 | `meta[property=og:image:secure_url]` |
| 2 | `meta[property=og:image]` |
| 3 | `meta[name=twitter:image]` |
| 4 | `meta[name=twitter:image:src]` |
| 5 | `meta[itemprop=image]` |
| 6 | `link[rel=image_src]` |
| 7 | `main`, `article`, `.visual`, `.main`, `.content` 안의 `img[src]` |

대표 이미지 후보가 없거나 부적합하면 favicon 후보를 본다.

| favicon 후보 | 설명 |
|---|---|
| `link[href]` 중 `rel`에 `icon` 포함 | `icon`, `shortcut icon`, `apple-touch-icon`, `mask-icon` 등 |
| `/apple-touch-icon.png` | 관례적 기본 경로 |
| `/favicon.ico` | 관례적 기본 경로 |

favicon도 없으면 기본 로고를 저장한다.

```text
/swimpulse-pool-shark.png
```

## API

### 전체 대표 이미지 enrich

대표 이미지, favicon, 기본 로고까지 한 번에 시도한다.

```http
POST /api/pools/images/enrich?limit=100
```

브라우저 콘솔 명령:

```javascript
await fetch("/api/pools/images/enrich?limit=100", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

### favicon/default만 enrich

이미 og:image enrich를 한 번 돌렸고, 아직 `image_url`이 비어 있는 pool만 favicon/default로 채우고 싶을 때 사용한다.

```http
POST /api/pools/images/favicon-enrich?limit=100
```

브라우저 콘솔 명령:

```javascript
await fetch("/api/pools/images/favicon-enrich?limit=100", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

### 단건 favicon/default enrich

```http
POST /api/pools/{poolId}/favicon/enrich
```

브라우저 콘솔 예:

```javascript
await fetch("/api/pools/22/favicon/enrich", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

## limit 정책

이미지 enrich API는 최대 200개까지 처리할 수 있게 했다.

| API | 기본 limit | 최대 limit |
|---|---:|---:|
| `/api/pools/images/enrich` | 100 | 200 |
| `/api/pools/images/favicon-enrich` | 100 | 200 |

따라서 `limit=100`은 정상이다.

## processedPools가 0이 되는 기준

배치 대상은 `image_url`이 비어 있는 pool이다.

```text
image_url is null 또는 빈 문자열
```

이번 구현 후에는 og:image와 favicon을 모두 못 찾아도 기본 로고 `/swimpulse-pool-shark.png`를 저장한다. 그래서 정상적으로 한 바퀴 처리되면 다음 호출에서는 `processedPools`가 줄어들고, 모두 채워지면 `0`이 된다.

## 프론트 구현

`DashboardClient.tsx`의 `PoolImage` 표시 방식을 바꿨다.

| imageUrl 형태 | 표시 방식 |
|---|---|
| 일반 사진 URL | 카드 배경 이미지처럼 `cover` |
| favicon, icon, svg | 여백 있는 아이콘형 `contain` |
| imageUrl 없음 | `/swimpulse-pool-shark.png` 기본 이미지, `cover` |

기본 로고는 `frontend/public/swimpulse-pool-shark.png`에 추가했다. 수영 물결과 상어를 넣은 SwimPulse 전용 placeholder라서, 외부 이미지가 없어도 깨진 썸네일 대신 일관된 로고가 보인다.

초기 버전은 아이콘처럼 `contain`으로 표시되어 카드 프레임 비율이 달라질 때 어색했다. 이후 기본 이미지를 직사각형 비율로 다시 만들고, 상어를 중앙 safe area에 크게 배치했다. 프론트에서는 기본 이미지만 `cover`로 표시하므로 직사각형 카드에서는 전체 배경처럼 보이고, 정사각형 프레임에서는 중앙이 자연스럽게 crop된다.

## 테스트 결과

| 항목 | 결과 |
|---|---:|
| Backend test | 통과 |
| Frontend lint | 통과 |
| 자동 테스트 실패율 | 0% |

실행한 명령:

```powershell
cd backend
./gradlew test
```

```powershell
cd frontend
npm run lint
```

## 직접 실행 순서

백엔드 컨테이너 재빌드:

```powershell
docker compose up -d --build backend
```

프론트를 `npm run dev`로 실행 중이면 브라우저 새로고침만 하면 된다. 프론트 컨테이너를 쓰는 경우에는 프론트도 재빌드해야 한다.

이미 og:image enrich를 실행한 뒤라면 favicon/default만 실행:

```javascript
await fetch("/api/pools/images/favicon-enrich?limit=100", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

처음부터 전체 흐름을 다시 돌리고 싶으면:

```javascript
await fetch("/api/pools/images/enrich?limit=100", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

DB 확인:

```sql
SELECT
  COUNT(*) AS total_pools,
  SUM(image_url IS NOT NULL AND image_url <> '') AS image_pools,
  ROUND(SUM(image_url IS NOT NULL AND image_url <> '') / COUNT(*) * 100, 2) AS image_rate
FROM pools;
```

기본 로고로 채워진 pool 확인:

```sql
SELECT id, name, image_url
FROM pools
WHERE image_url = '/swimpulse-pool-shark.png'
ORDER BY id;
```

## 다음 단계

| 순위 | 작업 | 이유 |
|---:|---|---|
| 1 | 실제 DB에서 favicon/default enrich 실행 | 비어 있는 썸네일 제거 |
| 2 | 관리자 화면에서 이미지 수동 수정 기능 | favicon이나 기본 로고가 마음에 안 드는 시설 보정 |
| 3 | `image_source`, `image_checked_at` 컬럼 검토 | 이미지 출처와 갱신 이력 관리 |
| 4 | 네이버 이미지 API 검토 | 공식 홈페이지 이미지가 부족할 때의 선택지 |

## 결론

대표 이미지는 공식 홈페이지 `og:image`가 가장 좋고, 그 다음은 favicon이 현실적인 fallback이다. favicon도 없을 때는 기본 상어 수영 로고를 저장해서 UI가 깨지거나 비어 보이지 않게 했다.
