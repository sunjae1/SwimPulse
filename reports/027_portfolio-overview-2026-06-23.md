# SwimPulse Portfolio Overview

작성일: 2026-06-23

## 프로젝트 개요

SwimPulse는 동네 공공 수영장과 체육센터의 수강 신청 기간을 놓치지 않도록, 시설 검색부터 공지 확인, 모집 기간 추출, 구독, 브라우저 푸시 알림까지 제공하는 웹 서비스입니다.

공공기관 공지는 시설마다 HTML 구조가 다르고, 모집 기간이 이미지 공지로만 올라오는 경우도 많습니다. SwimPulse는 이런 비정형 공지를 자동으로 수집·분석하고, 사용자가 원하는 모집 기간을 구독하면 접수 시작 전과 시작 시점에 알림을 보냅니다.

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 4, Spring Data JPA, MySQL |
| Frontend | Next.js 16, React 19, Tailwind CSS |
| Auth | Google OAuth2, JWT HttpOnly Cookie |
| Queue / Cache | Redis List, Redis TTL Cache, Single-flight Lock |
| Notification | Firebase Cloud Messaging, Web Push, Service Worker |
| Crawling / OCR | Jsoup, Tesseract OCR |
| Observability | Prometheus, Grafana, Spring Actuator, Micrometer |
| Load Test | k6 |
| Infra | Docker Compose, ngrok |

## 시스템 아키텍처

```text
Browser / Next.js
  -> Spring Boot API
    -> MySQL
    -> Redis Cache
    -> Redis Queue
      -> Notification Worker
        -> FCM / Mock FCM
```

공지 확인 흐름:

```text
수영장 선택
-> 공식 홈페이지/공지 경로 탐색
-> 상세 공지 HTML 파싱
-> 모집 기간 추출
-> 이미지 공지는 OCR 백그라운드 큐잉
-> pool_notices / notice_registration_periods 저장
```

알림 흐름:

```text
사용자 구독
-> registration_event 생성 또는 재사용
-> EventScheduler가 due event 감지
-> notification row 생성
-> DB commit 이후 Redis queue publish
-> NotificationWorker가 FCM 발송
```

## 주요 기능

| 기능 | 설명 |
|---|---|
| 위치 기반 시설 검색 | 현재 위치 또는 검색 위치 기준 수영장/체육센터 후보 조회 |
| 주변 시설 후보 추가 | 네이버 Local Search 기반 후보를 보여주고, DB에 없는 시설 추가 가능 |
| 공지 자동 스캔 | 시설 홈페이지에서 공지 게시판과 상세 공지를 탐색 |
| 모집 기간 추출 | HTML 텍스트와 OCR 결과에서 접수 기간 파싱 |
| 공지 기간 정규화 | `notice_registration_periods` 테이블로 여러 모집 기간을 구조화 |
| 구독/해지 | 사용자가 특정 모집 기간을 구독하고 마이페이지에서 관리 |
| 푸시 알림 | 접수 시작 전/시작 시점에 브라우저 푸시 발송 |
| 대표 이미지 보강 | 공식 홈페이지 `og:image`, favicon, 기본 이미지 순서로 이미지 보강 |
| 모니터링 | API latency, Redis queue length, delivery lag 등 관측 |
| 부하 테스트 | k6로 위치 검색, 공지 스캔, 구독, 알림 fan-out 성능 검증 |

## 주요 문제 상황과 해결

### 1. 위치 검색 외부 API 병목

문제:

- 위치 검색과 주변 후보 조회에서 Naver Local Search, Geocoding, Reverse Geocoding이 반복 호출됨
- VUS10 부하에서 p95가 1초 이상으로 증가하고, 외부 API rate limit성 실패 발생

해결:

- Redis TTL Cache 적용
- 같은 key 동시 요청을 하나로 합치는 single-flight lock 적용
- 전체 응답 캐시 대신 Naver/Geocode 결과만 캐시하여 DB 최신 상태 유지
- 이름/주소 정규화 컬럼과 batch match로 DB 중복 판정 최적화

성과:

| 시나리오 | Before p95 | After p95 | 실패율 |
|---|---:|---:|---:|
| 위치 검색 | 1452.77ms | 9.84ms | 14.91% -> 0% |
| 수영장 후보 검색 | 1360.60ms | 48.37ms | 17.14% -> 0% |
| 전체 탐색 흐름 | 1336.33ms | 25.62ms | 5.24% -> 0% |

### 2. OCR로 인한 공지 확인 지연

문제:

- 이미지 공지를 처리하기 위해 OCR을 도입했지만, 초기에는 사용자 요청 안에서 OCR을 직접 실행
- 일부 요청이 16초 이상 걸려 사용자 경험이 나빠짐

해결:

- 사용자 요청에서는 HTML 텍스트 파싱만 즉시 수행
- OCR은 Redis queue에 넣고 백그라운드 worker가 처리
- OCR 결과는 DB에 보강 저장
- 프론트는 OCR 처리 중 상태를 polling하고, 지연 시 안내 메시지 표시

성과:

| 지표 | Before | After |
|---|---:|---:|
| 평균 응답 | 349.66ms | 117.50ms |
| p99 | 13.37s | 406.07ms |
| 최대 응답 | 16.65s | 859.34ms |
| 실패 응답 | 2건 | 0건 |

### 3. 구독/알림 동시성 문제

문제:

- 같은 이벤트를 여러 사용자가 동시에 구독할 때 `registration_events` 중복 생성 가능
- 같은 사용자/이벤트 중복 구독 가능
- Redis queue publish가 DB commit보다 먼저 일어나면 worker가 DB row를 못 볼 수 있음
- worker가 Redis에서 pop한 뒤 장애가 나면 알림 유실 가능

해결:

- `registration_events`와 `subscriptions`에 unique 제약 추가
- duplicate insert 충돌 시 기존 row 재조회 후 재사용
- 큰 outer transaction 제거, insert/read 책임을 짧은 트랜잭션으로 분리
- notification row commit 이후 Redis queue publish
- `QUEUED -> SENDING -> SENT/FAILED` 상태 추가
- stale `SENDING` 알림 재큐잉 처리

개선 전에는 안정적인 p95 비교가 어려웠다. 부하 테스트가 정상 완료되기 전에 중복 insert, 동시 delete, lazy proxy, connection pool 고갈로 4xx/5xx가 먼저 발생했기 때문이다.

| 문제 | Before | After |
|---|---|---|
| 같은 논리 이벤트 동시 생성 | unique 충돌이 500으로 노출될 수 있음 | duplicate key 발생 시 기존 event 재조회 후 재사용 |
| 같은 사용자 구독 후 동시 해제 | 이미 삭제된 row 재삭제 시 404/실패 가능 | delete 대상이 없어도 no-op 처리 |
| 응답 DTO 변환 | 트랜잭션 밖 lazy proxy 접근으로 `Could not initialize proxy` 발생 | 응답 DTO를 짧은 조회 트랜잭션 안에서 생성 |
| 트랜잭션 경계 | 큰 outer transaction 안에서 `REQUIRES_NEW`가 중첩되어 connection pool 고갈 | outer transaction 제거, insert/read 책임 분리 |

수정 후 안정화 결과:

| 테스트 | 반복 | 실패율 | p95 |
|---|---:|---:|---:|
| 같은 사용자 같은 이벤트 구독 | 540 | 0% | 73.6ms |
| 구독 생성 후 바로 해제 | 535 | 0% | 72.6ms |
| 여러 사용자 같은 이벤트 구독 | 1100 | 0% | 67.2ms |
| 테스트 알림 queue 처리 | 1120 | 0% | 51.6ms |

### 4. 알림 worker 처리량 병목

문제:

- scheduler가 500명에게 알림을 생성하는 것은 빠르지만, Redis queue를 worker가 비우는 시간이 길었음
- 기존 설정은 1초마다 최대 20개 처리

해결:

- worker batch/delay 설정 튜닝
- `20개 / 1000ms`에서 `100개 / 250ms`로 변경

성과:

| 구분 | Before | After |
|---|---:|---:|
| 500명 알림 생성 | 500건 | 500건 |
| 실패 알림 | 0건 | 0건 |
| 전체 delivery 완료 | 32.85s | 10.10s |
| 개선율 | - | 약 69.3% 감소 |

## 데이터 모델 핵심

```text
pools
  1 : N pool_notice_sources
  1 : N pool_notices

pool_notices
  1 : N notice_registration_periods

notice_registration_periods
  1 : 0..1 registration_events

registration_events
  1 : N subscriptions
  1 : N notifications

app_users
  1 : N subscriptions
  1 : N notifications
  1 : N user_devices
```

## 성능/운영 관점에서 고민한 점

- 외부 API 결과는 Redis에 캐시하되, DB 중복 판정은 매 요청 최신 DB 기준으로 수행
- 전체 응답 캐시는 DB 상태 stale 위험 때문에 보류
- OCR은 정확도보다 먼저 사용자 응답 지연 제거를 우선
- Redis queue는 현재 규모에서 충분하지만, DLQ/ack/retry 운영이 중요해지면 RabbitMQ 전환 검토
- Mock FCM 기반 부하 테스트와 실제 FCM smoke test를 분리
- API latency뿐 아니라 queue length, delivery lag를 함께 관측

## 프로젝트 성과 요약

- 위치 검색 p95 `1452.77ms -> 9.84ms`
- 위치 검색 실패율 `14.91% -> 0%`
- OCR 포함 공지 확인 p99 `13.37s -> 406.07ms`
- 알림 500명 fan-out delivery time `32.85s -> 10.10s`
- 구독/알림 동시성 테스트 5종 실패율 `0%`
- Redis cache, queue, single-flight lock, afterCommit publish, stale recovery 등 실서비스형 병목/정합성 문제 해결

## 향후 개선 계획

| 우선순위 | 개선 |
|---:|---|
| 1 | 알림 목록 pagination 추가 |
| 2 | scheduler notification 생성 bulk insert/batch dedupe 조회 |
| 3 | Grafana에 delivery lag / queue length 패널 고정 |
| 4 | OCR 파싱 정확도 개선 및 관리자 보정 UI |
| 5 | 대표 이미지 출처/검증 상태 관리 |
| 6 | RabbitMQ 전환 기준 수립 |
