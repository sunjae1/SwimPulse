# 공지 경로 검증 및 재사용 방식 전환 보고서

## 1. 변경 요약

수영장 공지 크롤링의 시작점을 매 요청마다 홈페이지에서 다시 찾는 방식에서, 검증된 공지 경로를 `pool_notice_sources`에 저장하고 우선 재사용하는 방식으로 전환했다.

이번 변경의 핵심은 다음과 같다.

- `source_url`에서 `;jsessionid`, fragment(`#...`) 제거
- 정규화된 URL 중복 제거
- `(pool_id, source_url)` 유니크 제약 추가
- 공지 경로 상태를 `CANDIDATE`, `VERIFIED`, `INACTIVE`, `FAILED`로 세분화
- 연속 접근 실패 횟수와 마지막 오류·성공 시각 저장
- 사용자 공지 스캔에서 `VERIFIED` 경로 우선 사용
- 전체 홈페이지 재탐색을 pool별 24시간에 한 번으로 제한
- `FAILED` URL 자체의 재검증은 기본 7일 후 허용
- 기존 공지 결과가 있으면 최신 확인 실패 시 이전 결과 반환
- 기존 결과도 없으면 `현재 공지 경로를 찾지 못했습니다.` 반환
- 기존 source를 한 번에 최대 20개 pool씩 재검증하는 관리 API 추가

관련 구현:

```text
backend/src/main/resources/db/migration/V5__normalize_and_verify_pool_notice_sources.sql
backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java
backend/src/main/java/com/swimpulse/notice/PoolNoticeSource.java
backend/src/main/java/com/swimpulse/notice/NoticeSourceController.java
frontend/src/components/DashboardClient.tsx
```

---

## 2. 변경 전 방식

### 2.1 처리 흐름

기존 공지 스캔은 대략 다음 순서로 동작했다.

```text
사용자 공지 확인 요청
→ pools.homepage_url 접근
→ 시설명 메뉴 탐색
→ 공지·수강·접수 관련 링크 탐색
→ 후보 페이지 각각 접근
→ 상세 공지 링크 또는 inline 기간 탐색
→ 상세 공지 분석
→ pool_notices에 저장 또는 기존 URL 결과 재사용
```

`pool_notices`에 이미 동일한 상세 URL이 있으면 저장된 분석 결과를 재사용했지만, 그 상세 URL에 도달하기 위한 상위 공지 경로 탐색은 매번 다시 수행했다.

### 2.2 `pool_notice_sources`의 기존 의미

기존 `pool_notice_sources`는 실질적으로 다음 정보를 담았다.

```text
크롤러가 공지 관련 페이지일 가능성이 있다고 판단하여 방문한 URL
```

상태는 두 가지뿐이었다.

| 상태 | 기존 의미 |
| --- | --- |
| `ACTIVE` | 페이지 요청과 분석 과정에서 예외가 발생하지 않음 |
| `FAILED` | 페이지 요청 또는 분석 중 예외 발생 |

`ACTIVE`는 실제 공지 경로가 검증됐다는 의미가 아니었다. 공지를 찾지 못한 일반 메뉴나 프로그램 안내 페이지도 요청만 성공하면 `ACTIVE`가 될 수 있었다.

### 2.3 기존 방식의 장점

- 구현 흐름이 단순했다.
- 매 사용자 요청에서 홈페이지를 다시 탐색하므로 사이트 메뉴가 바뀌어도 즉시 새 경로를 발견할 가능성이 있었다.
- 별도의 배치 검증이나 source 상태 관리가 없어 운영 정책이 적었다.
- 저장된 상위 경로가 잘못돼도 홈페이지부터 다시 시작하므로 특정 source 데이터에 강하게 의존하지 않았다.

### 2.4 기존 방식의 단점

- 공지 확인 때마다 홈페이지와 여러 후보 URL을 반복 호출했다.
- 외부 사이트 응답 지연이 사용자 요청 시간에 그대로 포함됐다.
- 요청에 성공한 일반 페이지와 실제 공지 경로를 구분하지 못했다.
- 한 번 실패하면 즉시 `FAILED`가 되어 일시적인 네트워크 장애와 지속적인 장애를 구분하지 못했다.
- 마지막 성공 시각과 연속 실패 횟수가 없어 운영 분석이 어려웠다.
- URL을 그대로 저장해 세션 ID와 fragment가 중복 row를 만들었다.

예:

```text
/notice;jsessionid=ABC123?bbsId=NOTICE
/notice;jsessionid=XYZ789?bbsId=NOTICE
/notice?bbsId=NOTICE#none
```

위 URL은 크롤러 관점에서 같은 서버 요청일 수 있지만 서로 다른 source로 저장됐다.

- 같은 pool을 여러 사용자가 반복 조회할수록 불필요한 외부 호출과 source row가 누적됐다.
- 경로 확인 실패 시 사용자에게 이전 결과를 보여주지 못하거나 실패 원인을 명확히 구분하지 못했다.

---

## 3. 변경 후 방식

### 3.1 처리 흐름

현재 사용자 공지 스캔은 다음 순서로 동작한다.

```text
사용자 공지 확인 요청
→ 해당 pool의 VERIFIED source 조회
→ 있으면 VERIFIED source부터 직접 접근
→ 없으면 CANDIDATE source 검증
→ 사용할 경로가 없고 마지막 전체 탐색 후 24시간이 지났으면 홈페이지 재탐색
→ 공지 상세 후보 분석
→ 최신 경로 확인에 실패하면 기존 pool_notices 조회
→ 기존 결과 반환 또는 "현재 공지 경로를 찾지 못했습니다." 반환
```

평소에는 이미 확인한 경로로 바로 들어가고, 경로가 사라지거나 검증된 source가 없을 때만 홈페이지 전체를 다시 탐색한다.

### 3.2 source 상태

| 상태 | 의미 | 일반 사용자 스캔 |
| --- | --- | --- |
| `CANDIDATE` | 발견됐지만 공지 진입점인지 아직 검증되지 않음 | `VERIFIED`가 없을 때 검사 |
| `VERIFIED` | 공지 게시판 구조 또는 등록 기간을 확인함 | 가장 먼저 사용 |
| `INACTIVE` | 접근은 성공했지만 공지·등록 정보와 관련 없는 페이지 | 직접 우선 사용하지 않음 |
| `FAILED` | 연속 접근 실패가 기본 3회에 도달함 | 일반 스캔의 우선 대상에서 제외 |

### 3.3 검증 기준

source 페이지에 접근한 뒤 다음 중 하나를 확인하면 `VERIFIED`로 처리한다.

- 모집 기간이 포함된 inline 페이지
- 공지 상세 후보 링크
- `공지사항`, `알림마당`, `회원모집`, `수강신청`, `접수안내` 등 강한 키워드
- 게시판 형태의 HTML 구조

접근은 성공했지만 위 조건을 만족하지 않으면 `INACTIVE`로 처리한다.

접근 자체가 실패하면 다음 값을 기록한다.

```text
failure_count
last_error
last_scanned_at
```

기본 3회 연속 실패하면 `FAILED`가 된다. 이후 성공하면 실패 기록은 초기화되고 `VERIFIED`로 복구된다.

### 3.4 URL 정규화

저장 전에 URL을 canonical 형태로 바꾼다.

```text
https://example.com/notice;jsessionid=ABC123?bbsId=NOTICE#none
↓
https://example.com/notice?bbsId=NOTICE
```

현재 정규화 대상:

- 앞뒤 공백 제거
- `;jsessionid=...` 제거
- fragment 제거
- scheme과 host 소문자 변환
- 기본 포트 `80`, `443` 제거
- URI path 정규화

정규화된 URL에는 다음 유니크 제약이 적용된다.

```sql
UNIQUE (pool_id, source_url)
```

따라서 서로 다른 pool은 같은 URL을 가질 수 있지만, 한 pool 안에서는 동일 source가 중복 저장되지 않는다.

### 3.5 재탐색 간격

두 간격은 역할이 다르다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| 전체 홈페이지 재탐색 | 24시간 | 사용할 공지 경로가 없을 때 홈페이지 메뉴부터 다시 탐색할 최소 간격 |
| `FAILED` URL 재검증 | 7일 | 반복 실패한 동일 URL을 다시 직접 요청할 최소 간격 |

설정값:

```properties
SWIMPULSE_NOTICE_SOURCE_DISCOVERY_INTERVAL_MS=86400000
SWIMPULSE_NOTICE_FAILED_SOURCE_RETRY_INTERVAL_MS=604800000
SWIMPULSE_NOTICE_SOURCE_FAILURE_THRESHOLD=3
```

`FAILED` source만 존재하더라도 사용자 요청마다 같은 홈페이지를 반복 탐색하지 않는다. 마지막 전체 탐색 후 24시간이 지나야 다시 탐색한다.

---

## 4. 이전과 이후 비교

| 항목 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 스캔 시작점 | 매번 `homepage_url` | `VERIFIED` source 우선 |
| 홈페이지 전체 탐색 | 사용자 요청마다 가능 | 사용할 경로가 없을 때 24시간 간격 |
| source 의미 | 방문한 후보 URL | 검증 상태가 있는 재사용 가능 경로 |
| 상태 | `ACTIVE`, `FAILED` | `CANDIDATE`, `VERIFIED`, `INACTIVE`, `FAILED` |
| 성공 판정 | 예외 없이 요청 완료 | 공지 구조·상세 후보·기간 확인 |
| 실패 판정 | 한 번 실패하면 `FAILED` | 연속 3회 실패하면 `FAILED` |
| 실패 원인 기록 | 없음 | `last_error`, `failure_count` |
| URL 중복 | 세션 ID와 fragment로 발생 가능 | 정규화와 유니크 제약으로 차단 |
| 이전 결과 fallback | 명확한 정책 없음 | 최신 확인 실패 시 기존 공지 반환 |
| 사용자 메시지 | 후보 없음 중심 | 최신 확인 실패와 경로 없음 구분 |
| 기존 데이터 정리 | 수동 | Flyway V5에서 자동 |
| 대량 검증 | 없음 | 최대 20개 pool 배치 API |

---

## 5. 왜 변경했는가

### 5.1 성능

공지 스캔은 DB 조회보다 외부 홈페이지 요청 시간이 훨씬 크다. 기존 방식은 공지 상세 URL을 이미 알고 있어도 상위 메뉴 탐색부터 반복할 수 있었다.

검증된 source를 재사용하면 다음 요청들을 줄일 수 있다.

```text
홈페이지 요청
시설 메뉴 요청
관련 없는 메뉴 후보 요청
fallback 시설 페이지 요청
```

사이트별 응답 시간과 후보 수에 따라 절감 폭은 다르지만, 반복 스캔의 외부 요청 수를 줄이는 것이 주요 목적이다.

### 5.2 데이터 품질

기존 `ACTIVE`는 “공지 경로”와 “요청 가능한 일반 페이지”를 구분하지 못했다. 변경 후에는 source를 실제 용도에 따라 분류할 수 있다.

```text
공지를 찾을 수 있는 경로 → VERIFIED
관련 없는 페이지 → INACTIVE
일시적으로 실패한 후보 → CANDIDATE + failure_count
반복 실패한 경로 → FAILED
```

### 5.3 장애 해석성

이제 다음을 DB와 로그에서 확인할 수 있다.

- 마지막 확인 시각
- 마지막 성공 시각
- 연속 실패 횟수
- 마지막 오류 메시지
- 현재 source 상태
- 마지막 전체 홈페이지 탐색 시각

단순히 `FAILED`만 보던 것보다 왜 사용되지 않는 경로인지 분석하기 쉬워졌다.

### 5.4 사용자 경험

외부 공공기관 사이트가 잠시 실패했다고 기존 결과까지 숨기면 사용자 입장에서는 정보가 갑자기 사라진다.

변경 후 응답 정책:

```text
최신 경로 확인 실패 + 기존 공지 있음
→ 기존 공지 반환
→ "최신 공지 경로 확인에 실패해 이전에 저장된 공지 결과를 표시합니다."

최신 경로 확인 실패 + 기존 공지 없음
→ "현재 공지 경로를 찾지 못했습니다."
```

프론트 모달에도 `latestCheckFailed`를 기준으로 별도 경고가 표시된다.

---

## 6. 변경 후 방식의 장점

### 6.1 반복 요청 성능 개선

이미 검증된 공지 진입점으로 바로 접근하므로 홈페이지 메뉴 탐색 비용을 줄일 수 있다.

### 6.2 외부 사이트 부하 감소

같은 사용자가 반복 조회하거나 여러 사용자가 같은 pool을 조회해도 불필요한 전체 탐색 빈도가 줄어든다.

### 6.3 중복 데이터 방지

애플리케이션 정규화와 DB 유니크 제약을 함께 사용하므로 세션 ID가 달라질 때마다 source가 늘어나는 문제가 방지된다.

### 6.4 일시 장애와 지속 장애 구분

한 번 실패했다고 바로 경로를 폐기하지 않는다. 연속 실패 횟수를 통해 일시적인 타임아웃과 장기 장애를 구분할 수 있다.

### 6.5 통제된 self-healing

검증 경로가 모두 사라지면 홈페이지부터 새 경로를 다시 찾는다. 다만 24시간 간격을 둬 반복적인 고비용 탐색을 막는다.

### 6.6 운영 작업 자동화

132개 안팎의 pool을 수동으로 수정하지 않고 배치 API로 기존 source를 점진적으로 분류할 수 있다.

---

## 7. 변경 후 방식의 단점과 비용

### 7.1 상태 관리 복잡도 증가

단순한 `ACTIVE/FAILED`보다 상태 전이와 시간 정책을 이해해야 한다.

```text
CANDIDATE → VERIFIED
CANDIDATE → INACTIVE
CANDIDATE → 반복 실패 → FAILED
FAILED → 재검증 성공 → VERIFIED
INACTIVE → 전체 재탐색에서 다시 발견 → 재검증
```

### 7.2 최신 경로 발견이 늦을 수 있음

사이트가 공지 경로를 바꾼 직후라도 마지막 전체 탐색 후 24시간 이내라면 즉시 다시 찾지 않는다.

이는 성능과 최신성 사이의 의도적인 trade-off다. 운영 중 경로 변경 빈도가 높으면 24시간 설정을 줄일 수 있다.

### 7.3 휴리스틱 오판 가능성

HTML 구조와 키워드로 검증하므로 다음 문제가 가능하다.

- 공지 게시판인데 예상 키워드나 구조가 없어 `INACTIVE`가 되는 false negative
- 일반 콘텐츠 페이지가 `board`, `notice` 구조를 가져 `VERIFIED`가 되는 false positive

따라서 source 상태는 절대적인 사실이 아니라 현재 규칙에 따른 판정이다.

### 7.4 이전 공지는 stale 데이터일 수 있음

최신 확인 실패 시 기존 결과를 반환하면 서비스 가용성은 좋아지지만, 해당 공지가 최신이라는 보장은 없다.

프론트 경고를 숨기면 안 되며, 향후에는 `lastSuccessfulScanAt`을 응답에 포함해 데이터 시점을 보여주는 것이 좋다.

### 7.5 배치 API 운영 필요

Flyway는 기존 source를 전부 `CANDIDATE`로 바꾸지만 외부 페이지 검증까지 실행하지 않는다. 따라서 초기에는 배치 API를 반복 실행해야 한다.

### 7.6 외부 요청을 포함한 긴 트랜잭션

현재 배치 메서드에는 트랜잭션이 걸려 있고 최대 20개 pool의 외부 페이지 요청을 처리한다. 느린 사이트가 섞이면 DB 트랜잭션이 길어질 수 있다.

장기적으로는 다음 구조가 더 적합하다.

```text
배치 대상 ID 조회
→ pool별 별도 트랜잭션
→ 제한된 동시성 worker 실행
→ pool 하나의 결과만 짧게 저장
```

### 7.7 관리 API 권한

현재 `/api/**` 정책에 따라 로그인 사용자라면 배치 API를 호출할 수 있다. 운영 환경에서는 관리자 권한 또는 내부 API 인증으로 제한하는 것이 적절하다.

---

## 8. Flyway V5 데이터 정리

마이그레이션 순서:

1. `pools.last_notice_discovery_at` 추가
2. source 실패·성공 이력 컬럼 추가
3. 기존 enum이 `ACTIVE`를 포함하도록 일시 확장
4. 기존 URL 정규화
5. 모든 기존 source를 `CANDIDATE`로 변경
6. 정규화 결과가 같은 row 중 오래된 한 row만 유지
7. 최종 enum을 새 상태 네 가지로 제한
8. `(pool_id, source_url)` 유니크 제약 추가
9. 상태·확인 시각 조회용 인덱스 추가

실제 로컬 MySQL 적용 결과:

```text
Flyway schema version: 5
정리 후 pool_notice_sources: 128개
status: 전부 CANDIDATE로 초기화
jsessionid 포함 URL: 0개
fragment 포함 URL: 0개
중복 (pool_id, source_url) 그룹: 0개
```

기존 source 수가 줄어든 이유는 세션 ID만 다르거나 fragment만 다른 URL이 같은 canonical URL로 합쳐졌기 때문이다.

---

## 9. 배치 API 사용법

Endpoint:

```text
POST /api/pools/notice-sources/reverify?limit=20
```

제약:

- 로그인 필요
- 기본 limit: `20`
- 최소: `1`
- 최대: `20`
- 한 번 호출할 때 선택된 pool을 순차 처리

로그인한 서비스 화면의 브라우저 개발자 도구 Console에서:

```javascript
await fetch("/api/pools/notice-sources/reverify?limit=20", {
  method: "POST",
  credentials: "include",
}).then((response) => response.json());
```

응답 예:

```json
{
  "processedPools": 1,
  "checkedSources": 1,
  "verifiedSources": 0,
  "inactiveSources": 1,
  "failedSources": 0,
  "results": [
    {
      "poolId": 1,
      "poolName": "강남스포츠문화센터 수영장",
      "checkedSources": 1,
      "verifiedSources": 0,
      "inactiveSources": 1,
      "failedSources": 0,
      "homepageDiscoveryRan": true,
      "message": "검증 가능한 공지 경로를 찾지 못했습니다."
    }
  ]
}
```

초기 데이터는 다음처럼 처리한다.

```text
limit=20으로 호출
→ 응답의 processedPools 확인
→ 다시 호출
→ processedPools가 0이 될 때까지 반복
```

단, 접근 실패한 source는 한 번 호출로 바로 `FAILED`가 되지 않는다. 기본 임계치는 연속 3회이며, `FAILED` URL 자체는 7일 후 재검증 대상이 된다.

---

## 10. 사용자 공지 스캔 응답

기존 응답에 다음 필드가 추가됐다.

```json
{
  "latestCheckFailed": true
}
```

### 최신 확인 성공

```json
{
  "message": "공지 확인이 완료되었습니다.",
  "latestCheckFailed": false
}
```

### 최신 확인 실패, 기존 결과 있음

```json
{
  "message": "최신 공지 경로 확인에 실패해 이전에 저장된 공지 결과를 표시합니다.",
  "latestCheckFailed": true,
  "notices": [
    "기존 DB 공지 결과"
  ]
}
```

### 최신 확인 실패, 기존 결과 없음

```json
{
  "message": "현재 공지 경로를 찾지 못했습니다.",
  "latestCheckFailed": true,
  "notices": []
}
```

프론트에서는 `latestCheckFailed=true`일 때 주황색 경고 영역으로 메시지를 표시한다.

---

## 11. 검증 결과

### 자동 테스트

```text
backend: .\gradlew.bat test
결과: BUILD SUCCESSFUL
```

추가 테스트:

- URL에서 `jsessionid`와 fragment 제거
- 3회 실패 전까지 `CANDIDATE` 유지
- 3회째 `FAILED` 전환
- 검증 성공 시 실패 횟수와 오류 초기화

### 프론트 빌드

```text
frontend: npm run build
결과: 성공
```

`npm run lint`는 기존 `DashboardClient.tsx`의 `setState` effect 규칙 오류 2건으로 실패했다. 이번 source 변경에서 새로 발생한 lint 오류는 아니다.

### 실제 DB 및 API 확인

```text
docker compose up -d --build backend
Flyway V5 적용 성공
백엔드 정상 시작
```

배치 API `limit=1` 실제 호출:

```text
HTTP 200
poolId=1
checkedSources=1
INACTIVE=1
```

이후 pool 1 공지 스캔 실제 호출:

```text
24시간 이내 전체 재탐색 생략
기존 공지 1개 반환
latestCheckFailed=true
```

검증된 응답 메시지:

```text
최신 공지 경로 확인에 실패해 이전에 저장된 공지 결과를 표시합니다.
```

---

## 12. 아직 수행하지 않은 작업

- 128개 `CANDIDATE` 전체 배치 재검증
- 변경 전후 동일 pool 집합에 대한 k6 성능 비교
- source 판정 false positive·false negative 수동 샘플링
- 배치 API 관리자 권한 제한
- pool별 짧은 트랜잭션 또는 worker 분리
- `lastSuccessfulScanAt` 사용자 응답 노출
- `VERIFIED` source 우선순위 필드 도입

따라서 이번 결과는 구조 전환과 동작 검증까지 완료된 상태이며, 전체 pool의 source 품질과 성능 개선 폭은 배치 완료 후 별도로 측정해야 한다.

---

## 13. 상세 공지 파서 버전 관리

기존 상세 공지 재사용 기준은 저장된 구조화 기간 개수였다.

```text
기간 0~1개 → 구버전 결과로 추정하고 상세 페이지 재분석
기간 2개 이상 → DB 결과 재사용
```

이 기준은 실제 기간이 하나뿐인 정상 공지도 매 요청마다 다시 분석하는 문제가 있었다. 또한 잘못된 기간이 두 개 저장돼 있으면 최신 파서 결과가 아니어도 재사용할 수 있었다.

V6부터 `pool_notices`에 다음 메타데이터를 저장한다.

```text
parser_version
last_analyzed_at
```

현재 파서 버전:

```text
CURRENT_PARSER_VERSION = 1
```

재사용 정책:

```text
DB parser_version < 현재 버전
→ 상세 페이지를 다시 요청하고 최신 파서로 갱신

DB parser_version = 현재 버전 + 정상 분석 결과
→ 기간이 한 개여도 DB 결과 재사용

DB parser_version = 현재 버전 + FAILED
→ 상세 페이지 재분석 허용
```

기존 데이터는 Flyway V6에서 `parser_version=0`, `last_analyzed_at=NULL`로 시작한다. 따라서 같은 상세 URL이 공지 목록에서 다시 발견될 때 한 번만 현재 파서로 보강된다.

새 분석 또는 재분석이 끝나면 다음처럼 기록된다.

```text
parser_version = 1
last_analyzed_at = 실제 분석 완료 시각
```

향후 파싱 규칙이 크게 변경되면 `CURRENT_PARSER_VERSION`을 `2`, `3`처럼 올리면 기존 결과만 선택적으로 다시 분석할 수 있다.

관련 migration:

```text
backend/src/main/resources/db/migration/V6__add_pool_notice_parser_metadata.sql
```

실제 pool 16 공지로 확인한 결과:

```text
첫 번째 공지 확인:
parser_version 0 → 1
상세 페이지 재분석 1회
저장된 구조화 기간 1개

두 번째 공지 확인:
상세 페이지 재분석 0회
기존 DB 공지 재사용 1회
```

DB 확인값:

```text
pool_id=16
extraction_status=EXTRACTED
parser_version=1
period_count=1
last_analyzed_at 기록됨
```

따라서 최신 파서 버전으로 정상 분석된 공지는 기간이 한 개뿐이어도 반복 HTTP 요청 없이 저장 결과를 재사용한다.

---

## 14. 결론

기존 방식은 단순하고 항상 홈페이지부터 최신 경로를 다시 찾는 장점이 있었지만, 반복 외부 호출과 불명확한 source 상태, URL 중복, 실패 이력 부족이라는 비용이 컸다.

변경 후 방식은 검증된 공지 경로를 재사용해 반복 비용을 줄이고, source 상태와 실패 원인을 운영 데이터로 남기며, 외부 사이트 장애 시 기존 공지 결과를 사용자에게 제공한다.

대신 다음 trade-off를 받아들인다.

```text
즉시 전체 재탐색하는 최신성
↓
24시간 간격의 통제된 재탐색과 더 빠른 일반 요청
```

현재 규모에서는 이 방식이 더 적합하다. 다만 pool 수와 사용자 요청이 늘어나면 배치 외부 호출을 트랜잭션 밖 worker로 분리하고, 관리자 권한과 source 우선순위를 추가하는 것이 다음 단계다.

---

## 15. 모집 기간 정규화와 이벤트 연결

기존에는 상세 공지 한 행의 `registration_periods_json`에 여러 모집 기간을 배열로 저장했다. 화면 표시는 가능했지만 각 기간을 독립적으로 식별하거나 이벤트 FK로 연결하기 어려웠다.

V7부터 관계는 다음과 같다.

```text
pool_notices 1 : N notice_registration_periods
notice_registration_periods 1 : 0..1 registration_events
```

`registration_events.notice_registration_period_id`는 nullable unique FK다. 따라서 수동 이벤트는 FK 없이 유지할 수 있고, 공지에서 선택한 기간은 최대 하나의 이벤트와 연결된다.

신규 크롤링 동기화 정책:

```text
동일 notice + 정규화 label + starts_at + ends_at
→ 기존 기간 행 재사용 및 ACTIVE

새 기간
→ INSERT

이전 분석에는 있었지만 최신 분석에서 사라진 기간
→ 삭제하지 않고 INACTIVE
```

조회는 `notice_registration_periods`의 `ACTIVE` 행을 우선 사용하며, 아직 이관되지 않은 데이터만 기존 JSON 또는 대표 기간 필드로 fallback한다.

기존 JSON 이관 API:

```text
POST /api/pools/notices/periods/migrate?limit=50
GET  /api/pools/notices/periods/migration-status
```

실제 로컬 DB 이관 결과:

```text
pool_notices: 66
migrated_notices: 66
active_periods: 92
inactive_periods: 0
migration_errors: 0
orphan_periods: 0
linked_events: 2
```

두 번의 배치 호출 결과:

```text
1차: 공지 50개, 기간 61개, 기존 이벤트 연결 2개
2차: 공지 16개, 기간 31개, 기존 이벤트 연결 0개
```

공지 스캔 응답에서도 기간 ID가 반환되는 것을 확인했다.

```text
pool_id=16
notice_id=42
notice_registration_period_id=43
```

프론트 구독 요청은 이 ID를 `noticeRegistrationPeriodId`로 전달한다. backend는 활성 상태, pool 소유 관계, 시작·종료 시각을 재검증한 뒤 이벤트를 생성하거나 기존 이벤트를 재사용하고 FK를 연결한다.
