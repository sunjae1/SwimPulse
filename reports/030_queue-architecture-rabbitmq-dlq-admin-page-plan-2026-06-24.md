# Redis Queue, RabbitMQ, DLQ, 관리자 페이지 판단 보고서

작성일: 2026-06-24

## 목적

SwimPulse는 현재 Redis List를 알림 발송 queue로 사용한다. 이 문서는 현재 구조가 무엇을 보장하는지, RabbitMQ로 바꾸면 무엇이 좋아지고 무엇이 복잡해지는지, DLQ가 무엇인지, 그리고 운영용 관리자 페이지를 만들 때 어떤 기능이 필요한지 판단하기 위한 자료다.

결론부터 말하면 현재 단계에서는 RabbitMQ를 바로 도입하기보다, Redis Queue + DB 상태 관리 구조를 유지하면서 관리자 페이지와 관측 지표를 먼저 보강하는 편이 좋다. RabbitMQ는 알림량이 더 커지고, 실패 메시지 재처리와 운영 추적이 Redis 기반 보강만으로 부족해질 때 도입하는 것이 자연스럽다.

## 현재 SwimPulse 알림 구조

현재 알림 흐름은 Redis만 믿는 구조가 아니라, DB의 `notifications` row를 중심으로 Redis를 작업 신호 queue로 사용하는 구조다.

```text
EventScheduler
  -> due registration_events 조회
  -> subscriptions 조회
  -> notifications row 생성(status=QUEUED)
  -> DB commit 이후 Redis queue publish
  -> NotificationWorker가 Redis에서 notificationId pop
  -> DB row를 SENDING으로 변경
  -> FCM 발송
  -> SENT 또는 FAILED 저장
```

핵심은 Redis queue 안에 알림 본문 전체를 넣지 않고 `notificationId`만 넣는다는 점이다. 실제 상태와 본문은 DB에 남아 있다.

현재 코드 기준:

| 역할 | 현재 구현 |
|---|---|
| Queue publish | `NotificationQueuePublisher.publishAfterCommit()` |
| Redis 자료구조 | Redis List, `rightPush` / `leftPop` |
| Worker | `NotificationWorker.process()` |
| Worker 실행 방식 | `@Scheduled(..., scheduler = "notificationTaskScheduler")` |
| 발송 시작 상태 | `QUEUED -> SENDING` |
| 성공 상태 | `SENT` |
| 실패 상태 | `FAILED` |
| worker 장애 보강 | 오래된 `SENDING` row를 `QUEUED`로 되돌리고 Redis에 재삽입 |
| 중복 알림 방지 | `dedupeKey` 기반 중복 확인 |
| 관측 지표 | Redis queue length, delivery result, delivery lag |

## Redis Queue를 쉽게 말하면

Redis Queue는 Redis의 List를 줄 서기 공간처럼 쓰는 방식이다.

```text
오른쪽에 넣기: RPUSH notificationId
왼쪽에서 꺼내기: LPOP notificationId
```

예를 들어 DB에 `notification_id=100` 알림 row를 만들고 Redis에 `100`을 넣는다. worker는 Redis에서 `100`을 꺼낸 뒤 DB에서 100번 알림을 찾아 FCM을 보낸다.

Redis는 빠르고 단순하다. 하지만 Redis List 자체는 “이 메시지를 누가 처리 중인지”, “몇 번 실패했는지”, “너무 많이 실패한 메시지를 어디에 보낼지” 같은 기능을 RabbitMQ처럼 기본 제공하지 않는다. 그래서 SwimPulse는 그 정보를 DB 상태로 보강하고 있다.

## 현재 Redis Queue 구조의 장점

| 장점 | 설명 |
|---|---|
| 단순함 | 이미 Redis를 캐시와 lock에 사용 중이라 추가 인프라가 없다. |
| 빠름 | notificationId만 push/pop 하므로 queue 작업 비용이 작다. |
| DB 정합성 유지 | 알림 row가 commit된 뒤 Redis에 넣기 때문에 worker가 없는 row를 처리할 가능성이 줄어든다. |
| 장애 복구 가능 | worker가 pop 후 죽어도 DB row가 `SENDING`으로 남고, stale requeue가 다시 `QUEUED`로 되돌린다. |
| 중복 방지 가능 | `dedupeKey`로 같은 사용자, 같은 이벤트, 같은 알림 타입 중복 생성을 막는다. |
| 운영 비용 낮음 | RabbitMQ broker, exchange, queue, binding, DLQ 운영을 아직 하지 않아도 된다. |

## 현재 Redis Queue 구조의 한계

| 한계 | 의미 |
|---|---|
| pop 이후 즉시 메시지는 Redis에서 사라짐 | worker가 죽으면 Redis 안에는 메시지가 없고 DB의 `SENDING` 상태를 보고 복구해야 한다. |
| 기본 ack/nack 없음 | RabbitMQ처럼 “성공했으니 ack”, “실패했으니 nack/requeue”를 broker가 직접 관리하지 않는다. |
| DLQ가 기본 기능이 아님 | 너무 많이 실패한 메시지를 별도 queue로 자동 이동시키는 기능은 직접 만들어야 한다. |
| 운영 UI가 약함 | Redis만으로는 어떤 메시지가 왜 실패했는지 보기 어렵고 DB/admin 페이지가 필요하다. |
| 복잡한 routing에 약함 | 알림 종류별 queue, 우선순위, 지연 재시도, dead-letter routing이 많아지면 코드가 복잡해진다. |

현재 구조는 이 한계를 DB 상태 관리로 상당 부분 줄이고 있다. 다만 운영자가 실패 알림을 보고 재처리할 수 있는 관리자 페이지가 아직 부족하다.

## RabbitMQ를 쉽게 말하면

RabbitMQ는 메시지를 전문적으로 관리하는 broker다.

Redis Queue가 “빠른 줄 서기 리스트”에 가깝다면, RabbitMQ는 “택배 물류센터”에 가깝다.

```text
Producer
  -> Exchange
  -> Queue
  -> Consumer
  -> ack/nack
```

주요 개념은 다음과 같다.

| 개념 | 쉬운 설명 |
|---|---|
| Producer | 메시지를 보내는 쪽. SwimPulse에서는 알림 생성 로직이다. |
| Exchange | 메시지를 어느 queue로 보낼지 결정하는 라우터다. |
| Queue | 메시지가 쌓이는 줄이다. 예: `notification.send.queue` |
| Consumer | queue에서 메시지를 받아 처리하는 worker다. |
| Ack | “처리 성공했으니 이 메시지를 지워도 된다”는 확인이다. |
| Nack | “처리 실패했다”는 신호다. 재큐잉하거나 버릴 수 있다. |
| Prefetch | consumer 하나가 한 번에 가져갈 수 있는 메시지 수 제한이다. |
| Durable queue | RabbitMQ 재시작 후에도 queue 정의가 남도록 하는 설정이다. |
| Persistent message | broker 재시작 후에도 메시지가 남도록 디스크에 기록하는 설정이다. |

## DLQ란?

DLQ는 Dead Letter Queue의 줄임말이다. 직역하면 “죽은 편지함” 정도인데, 실제 의미는 “정상 처리에 실패한 메시지를 따로 모아두는 queue”다.

예를 들어 FCM 알림을 3번 보내봤는데 계속 실패한다고 하자.

```text
notification.send.queue
  -> worker 처리
  -> 실패
  -> retry queue
  -> 다시 실패
  -> retry queue
  -> 또 실패
  -> DLQ로 이동
```

DLQ에 들어간 메시지는 자동으로 계속 재시도하지 않는다. 운영자가 관리자 페이지나 RabbitMQ 관리 UI에서 확인한다.

DLQ에 넣는 이유:

| 이유 | 설명 |
|---|---|
| 무한 재시도 방지 | 계속 실패하는 메시지가 queue를 막지 않게 한다. |
| 원인 분석 | 어떤 사용자, 어떤 이벤트, 어떤 에러인지 나중에 볼 수 있다. |
| 수동 재처리 | 설정 오류나 일시 장애가 해결된 뒤 다시 보낼 수 있다. |
| 정상 메시지 보호 | 실패 메시지가 정상 메시지 처리를 방해하지 않게 한다. |

현재 SwimPulse는 RabbitMQ DLQ 대신 DB의 `FAILED` 상태와 `failureReason`이 DLQ와 비슷한 역할을 한다. 즉, 실패 알림을 DB에 남겨두고 나중에 재처리할 수 있는 기반은 있다. 다만 RabbitMQ처럼 broker 차원의 DLQ queue는 아직 없다.

## Redis Queue와 RabbitMQ 비교

| 항목 | 현재 Redis Queue | RabbitMQ |
|---|---|---|
| 도입 난이도 | 낮음 | 중간~높음 |
| 운영 인프라 | Redis만 필요 | RabbitMQ broker 추가 필요 |
| 메시지 처리 확인 | 직접 DB 상태로 관리 | ack/nack 기본 제공 |
| 실패 메시지 분리 | 직접 구현 필요 | DLQ 패턴이 표준적 |
| 재시도 정책 | 애플리케이션 코드 중심 | retry queue, TTL, dead-letter exchange로 구성 가능 |
| 관측성 | Redis queue length + DB 지표 필요 | 관리 UI와 queue별 지표 제공 |
| 메시지 routing | 단순 queue에 적합 | exchange/routing key로 유연 |
| 장애 복구 | DB `SENDING` stale requeue로 보강 | ack 전 장애 시 broker가 재전달 가능 |
| 성능 | 매우 빠름 | 충분히 빠르지만 목적은 단순 속도보다 안정적 메시징 |
| 현재 프로젝트 적합도 | 현재 규모에 적합 | 운영 복잡도가 아직 더 큼 |

RabbitMQ가 무조건 “성능이 더 좋은 선택”은 아니다. RabbitMQ의 장점은 단순 속도보다 메시지 신뢰성, ack/nack, routing, DLQ, 운영 도구에 있다.

## 지금 RabbitMQ로 바로 가지 않아도 되는 이유

현재 SwimPulse는 이미 다음 보강을 해두었다.

1. 알림 row를 먼저 DB에 저장한다.
2. DB commit 이후 Redis에 publish한다.
3. worker가 발송 전에 `SENDING`으로 바꾼다.
4. pop 이후 worker가 죽어도 오래된 `SENDING`을 다시 `QUEUED`로 되돌린다.
5. 중복 알림은 `dedupeKey`로 막는다.
6. delivery lag와 queue length를 측정한다.

즉, Redis List만 단독으로 믿는 구조가 아니다. DB 상태를 source of truth로 두기 때문에, 현재 트래픽과 기능 범위에서는 RabbitMQ 없이도 운영 가능한 수준의 안정성을 확보했다.

## RabbitMQ 도입을 고려할 시점

다음 조건이 쌓이면 RabbitMQ 도입을 진지하게 검토하는 것이 좋다.

| 조건 | 이유 |
|---|---|
| 알림 종류가 많아짐 | 접수 시작, 사전 알림, OCR 완료, 관리자 공지 등 routing이 복잡해진다. |
| retry 정책이 다양해짐 | 즉시 재시도, 1분 뒤, 10분 뒤, 하루 뒤 같은 정책이 필요해진다. |
| 실패 알림 운영이 많아짐 | DLQ와 재처리 UI가 필요해진다. |
| worker 여러 대 운영 | broker가 consumer 분산, ack, prefetch를 관리해주는 이점이 커진다. |
| Redis가 캐시와 queue를 같이 감당하기 어려움 | 캐시 eviction과 queue 안정성을 분리하고 싶어진다. |
| 운영자가 queue 단위로 상태를 보고 싶음 | RabbitMQ Management UI가 도움이 된다. |

## RabbitMQ로 바꿀 때의 기본 설계

RabbitMQ를 도입하더라도 DB notification row는 유지하는 편이 좋다. 이유는 사용자 알림 목록, 읽음 처리, 실패 사유, dedupe, 관리자 조회의 기준이 DB이기 때문이다.

권장 흐름:

```text
1. notifications row 생성(status=QUEUED)
2. DB commit 이후 RabbitMQ publish(notificationId)
3. RabbitMQ consumer가 message 수신
4. DB row를 SENDING으로 변경
5. FCM 발송
6. 성공하면 DB SENT 저장 후 ack
7. 실패하면 DB FAILED 또는 QUEUED 저장 후 nack/retry
8. 최대 실패 횟수 초과 시 DLQ 이동
```

중요한 점은 RabbitMQ 메시지에도 알림 본문 전체를 넣기보다 `notificationId`를 넣는 편이 낫다는 것이다. 알림의 최종 상태와 사용자 목록은 DB에서 관리해야 마이페이지와 관리자 페이지가 일관된다.

## 관리자 페이지가 필요한 이유

현재 구조에서도 장애가 났을 때 DB에는 상태가 남는다. 하지만 운영자가 볼 화면이 없으면 SQL이나 로그를 직접 봐야 한다.

관리자 페이지는 두 성격으로 나누는 것이 좋다.

```text
1. 운영 관리자 페이지
   queue, worker, 실패 알림, 공지 스캔, OCR 상태를 보는 화면

2. 서비스 관리자 페이지
   사용자/구독/수영장/시설 추가 요청/인기 시설/알림 통계를 보는 화면
```

앞선 queue/RabbitMQ 관점의 관리자 페이지는 1번 운영 관리자에 가깝다. 실제 서비스 운영에는 2번 서비스 관리자 기능도 같이 필요하다.

운영 관리자 페이지의 목적은 다음이다.

| 목적 | 설명 |
|---|---|
| 현재 queue가 밀리는지 확인 | Redis queue length, `QUEUED`, `SENDING` 개수 확인 |
| 알림 실패 원인 확인 | `FAILED`, failure reason, attempts 확인 |
| stale 작업 복구 | 오래된 `SENDING`을 재큐잉 |
| 특정 알림 재발송 | 실패 알림을 수동 requeue |
| 공지 스캔 상태 확인 | pool별 notice source 상태와 실패 횟수 확인 |
| OCR 작업 확인 | pending/processing/failed 상태 확인 |
| 운영 실수 방지 | bulk action 전에 확인, dry-run, audit log |

서비스 관리자 페이지의 목적은 다음이다.

| 목적 | 설명 |
|---|---|
| 인기 수영장 파악 | 구독이 많은 수영장, 최근 구독 증가 시설, 지역별 인기 시설을 본다. |
| 사용자 문의 대응 | 특정 사용자의 구독, 알림 수신 상태, 실패 사유를 확인한다. |
| 시설 추가 요청 처리 | 사용자가 누른 “이 시설 추가” 요청을 검토하고 pool DB에 반영한다. |
| 수영장 데이터 품질 관리 | 홈페이지, 이미지, 주소, 좌표, 공지 경로 상태를 확인하고 보정한다. |
| 알림 품질 관리 | 몇 건이 생성됐고, 몇 건이 발송 성공/실패했는지 본다. |
| 공지 파싱 품질 관리 | 어떤 pool에서 공지/기간/OCR 실패가 자주 나는지 본다. |

## 관리자 페이지 MVP 범위

처음부터 모든 운영 기능을 넣기보다 읽기 전용 대시보드부터 시작하는 것이 안전하다.

### 1단계: 읽기 전용 대시보드

필요한 정보:

| 화면 | 표시할 데이터 |
|---|---|
| 알림 queue 요약 | Redis queue length, `QUEUED`, `SENDING`, `FAILED`, `SENT` |
| delivery lag | 평균, p95, p99 |
| worker 상태 | 마지막 처리 시각, 최근 처리 수, stale requeue 수 |
| 실패 알림 목록 | notificationId, userId, eventId, type, attempts, failureReason |
| 공지 스캔 상태 | poolId, verified source 수, failed source 수, last discovery/check |
| OCR 상태 | pending/processing/failed/completed 수 |

### 2단계: 안전한 운영 액션

가능한 액션:

| 액션 | 설명 |
|---|---|
| 실패 알림 1건 재큐잉 | `FAILED -> QUEUED`, Redis publish |
| stale `SENDING` 재큐잉 | 오래 멈춘 작업만 선택적으로 복구 |
| 특정 pool 공지 재검증 | pool 하나만 reverify |
| OCR 실패 재시도 | 특정 notice만 OCR requeue |
| 알림 상세 보기 | 발송 대상, 이벤트, 원문 URL, 실패 사유 확인 |

주의할 점:

1. bulk action은 확인 모달을 둔다.
2. 한 번에 재큐잉 가능한 개수를 제한한다.
3. 모든 관리자 액션은 audit log를 남긴다.
4. 실제 FCM 발송을 유발하는 액션은 더 강한 확인을 둔다.
5. loadtest/mock 환경과 운영 환경을 화면에서 명확히 구분한다.

## 서비스 관리자 기능 설계

### 인기 수영장 랭킹

구독이 많은 수영장을 보여주는 랭킹은 사용자 위치를 직접 저장하지 않아도 만들 수 있다.

추천 지표:

| 지표 | 기준 |
|---|---|
| 누적 구독 수 | `subscriptions` 또는 과거 구독 로그 기준 |
| 활성 구독 수 | 아직 마감되지 않은 event에 연결된 구독 수 |
| 최근 7일 신규 구독 수 | `subscriptions.created_at` 기준 |
| 알림 발생 수 | `notifications` 생성 수 |
| 지역별 인기 | pool의 주소/좌표를 기준으로 그룹화 |

주의할 점은 “지역별 인기”를 사용자의 현재 위치로 계산할 필요가 없다는 것이다. 사용자가 어느 위치에서 검색했는지 저장하지 않아도, 구독된 수영장의 주소와 좌표를 기준으로 어느 지역 시설이 많이 구독되는지 볼 수 있다.

```text
사용자 위치 저장 없이 가능한 통계:
수영장 주소/좌표 + 구독 수
-> 부천/수원/군포 등 지역별 인기 시설
```

### 사용자 위치 데이터와 개인정보

현재 위치 검색 흐름은 브라우저에서 얻은 좌표나 검색어를 API 요청에 사용하고, 그 위치 자체를 사용자 프로필처럼 DB에 저장하는 구조가 아니라면 장기 개인정보로 쌓이지 않는다.

다만 관리자 페이지에서 “사용자들이 어디에서 많이 검색했는지”까지 보고 싶다면 별도 이벤트 로그가 필요하다.

그 경우 고려해야 할 점:

| 항목 | 권장 |
|---|---|
| 원본 좌표 저장 | 가능하면 피한다. |
| 좌표 정밀도 | 3~4자리 bucket 또는 행정동 단위로 낮춘다. |
| 사용자 ID 연결 | 꼭 필요하지 않으면 저장하지 않는다. |
| 보관 기간 | 짧게 둔다. 예: 7일~30일 |
| 고지/동의 | 개인정보 처리방침에 명확히 적는다. |
| 관리자 화면 | 개인별 위치가 아니라 집계 데이터만 보여준다. |

즉, “구독 많은 수영장 위치”는 비교적 안전하고, “사용자가 검색한 실제 현재 위치”는 개인정보 이슈가 있으므로 집계/익명화 중심으로 가는 편이 좋다.

### “이 시설 추가” 요청 관리

사용자가 “이 시설 추가”를 누르면 바로 `pools`에 넣기보다 요청 테이블에 저장하고, 관리자가 검토 후 승인하는 흐름이 좋다.

예상 테이블:

```text
pool_add_requests
- id
- requested_by_user_id
- title
- category
- address
- road_address
- latitude
- longitude
- homepage_url
- provider
- provider_place_id
- status: PENDING / APPROVED / REJECTED / MERGED
- admin_note
- created_at
- reviewed_at
- reviewed_by_admin_id
```

처리 흐름:

```text
사용자: 이 시설 추가 요청
  -> pool_add_requests(PENDING)

관리자: 요청 상세 확인
  -> 기존 pool 중복 여부 확인
  -> 승인 또는 반려

승인:
  -> pools row 생성
  -> 홈페이지 보강 API 실행
  -> 이미지 보강 API 실행
  -> 공지 source 탐색/검증 실행
  -> 상태 APPROVED

기존 시설과 중복:
  -> 기존 pool에 병합
  -> 상태 MERGED

부적절한 요청:
  -> 상태 REJECTED
  -> admin_note 기록
```

관리자 화면에는 “승인 후 후처리” 버튼이 필요하다.

| 버튼 | 하는 일 |
|---|---|
| 홈페이지 보강 | 공식 홈페이지 후보를 찾고 저장한다. |
| 이미지 보강 | `og:image` 또는 기본 이미지를 정리한다. |
| 공지 경로 탐색 | pool_notice_sources를 탐색/검증한다. |
| 공지 확인 테스트 | 실제 공지 목록과 기간 파싱이 되는지 확인한다. |
| 기존 시설과 병합 | 중복 요청을 기존 pool로 연결한다. |

### 알림 발송 통계

사용자 관리 관점에서는 queue length보다 “사용자에게 실제로 알림이 얼마나 갔는지”가 더 중요하다.

추천 지표:

| 지표 | 설명 |
|---|---|
| 생성 알림 수 | `notifications` row 생성 수 |
| 발송 성공 수 | `status=SENT` |
| 발송 실패 수 | `status=FAILED` |
| 발송 대기 수 | `status=QUEUED` |
| 발송 중 수 | `status=SENDING` |
| 실패율 | `FAILED / 전체 발송 대상` |
| delivery lag | 생성부터 발송 완료까지 걸린 시간 |
| FCM token 없음 | 보낼 기기가 없어 실패한 건수 |
| 사용자별 최근 알림 | 문의 대응용 |
| pool/event별 알림 결과 | 특정 수영장 공지 알림 품질 확인 |

“FCM token 없음”은 현재 코드에서 `FCM token is not registered.`로 `FAILED` 처리된다. 구독 생성 시점에는 토큰이 있었더라도 이후 브라우저 권한 해제, 기기 삭제, 토큰 만료, 서버 데이터 비활성화, 테스트 데이터 등으로 발송 시점에 토큰이 없을 수 있다. 그래서 이 실패 분기는 남겨두는 것이 맞다.

### 3단계: 운영 편의 기능

추가하면 좋은 기능:

| 기능 | 이유 |
|---|---|
| 상태별 필터 | 실패/대기/발송 중을 빠르게 확인 |
| pool/user/event 검색 | 특정 사용자 문의 대응 |
| 기간 필터 | 장애 발생 시간대 추적 |
| CSV export | 운영 분석 |
| Grafana 링크 | 상세 지표로 이동 |
| 재처리 결과 toast/log | 액션 성공 여부 확인 |

## 관리자 API 설계안

예상 API:

```text
GET  /api/admin/notifications/summary
GET  /api/admin/notifications?page=0&size=50&status=FAILED
GET  /api/admin/notifications/{id}
POST /api/admin/notifications/{id}/requeue
POST /api/admin/notifications/requeue-stale

GET  /api/admin/queues/notification
GET  /api/admin/workers/notification

GET  /api/admin/notice-sources?page=0&size=50&status=FAILED
POST /api/admin/notice-sources/{id}/reverify

GET  /api/admin/notice-ocr?page=0&size=50&status=FAILED
POST /api/admin/notice-ocr/{noticeId}/requeue

GET  /api/admin/pools/rankings?metric=activeSubscriptions&period=30d
GET  /api/admin/pool-add-requests?page=0&size=50&status=PENDING
POST /api/admin/pool-add-requests/{id}/approve
POST /api/admin/pool-add-requests/{id}/reject
POST /api/admin/pool-add-requests/{id}/merge

GET  /api/admin/notifications/stats?from=2026-06-01&to=2026-06-30
GET  /api/admin/users/{userId}/subscriptions
GET  /api/admin/users/{userId}/notifications
```

권한은 일반 로그인 사용자와 분리해야 한다.

```text
ROLE_USER
ROLE_ADMIN
```

관리자 API는 반드시 `ROLE_ADMIN`만 허용해야 한다. 운영 액션은 audit log도 남기는 편이 좋다.

## 관리자 페이지 구현 순서

권장 순서:

1. `users` 또는 별도 권한 테이블에 admin role 추가
2. Spring Security에서 `/api/admin/**` 보호
3. 알림 summary API 구현
4. 알림 목록 API 구현
5. 관리자 프론트 `/admin` 페이지 추가
6. Redis queue length, DB status count 표시
7. 실패 알림 상세 보기 추가
8. 단건 requeue 구현
9. stale requeue 버튼 구현
10. audit log 테이블과 기록 추가
11. notice source / OCR 상태 화면 추가
12. 인기 수영장 랭킹 화면 추가
13. 시설 추가 요청 관리 화면 추가
14. 알림 발송 통계 화면 추가
15. Grafana 링크 또는 주요 지표 embed

처음에는 읽기 전용으로 배포하고, 운영자가 필요한 정보를 잘 찾는지 확인한 뒤 requeue 같은 변경 액션을 추가하는 편이 안전하다.

## 현재 구조에서 우선 보강할 것

RabbitMQ 도입 전 우선순위는 다음이 좋다.

| 우선순위 | 작업 | 이유 |
|---|---|---|
| 1 | 관리자 알림 summary/list | 실패와 queue 밀림을 눈으로 확인해야 한다. |
| 2 | 단건 requeue | 실패 알림 수동 복구가 가능해진다. |
| 3 | stale `SENDING` 화면화 | worker 장애 복구 상태를 확인할 수 있다. |
| 4 | audit log | 관리자 액션 추적이 가능해진다. |
| 5 | notice/OCR 운영 화면 | 공지 스캔 문제도 SQL 없이 확인 가능해진다. |
| 6 | 시설 추가 요청 관리 | 사용자 건의를 실제 pool 데이터로 전환할 수 있다. |
| 7 | 인기 수영장/알림 통계 | 서비스 운영 판단에 필요한 제품 지표를 볼 수 있다. |
| 8 | RabbitMQ PoC | 실제 운영 불편이 남을 때 비교한다. |

## 판단 요약

현재 SwimPulse에는 Redis Queue가 맞다.

이유:

1. 이미 Redis를 사용 중이다.
2. 알림 메시지 본문이 아니라 DB `notificationId`만 queue에 넣는다.
3. DB 상태가 source of truth다.
4. commit 이후 publish, `SENDING`, stale requeue로 유실 위험을 줄였다.
5. 현재 병목은 RabbitMQ 부재보다 관리자 관측/운영 기능 부족에 가깝다.

RabbitMQ는 다음 단계의 선택지다.

RabbitMQ가 필요한 상황:

1. 알림/작업 종류가 많아져 routing이 복잡해진다.
2. 실패 재시도 정책이 다양해진다.
3. DLQ 기반 운영이 필요해진다.
4. worker를 여러 대로 확장하고 ack/nack 기반 안정성을 broker에 맡기고 싶다.
5. Redis를 캐시와 queue로 같이 쓰는 것이 부담스러워진다.

## 추천 결정

단기 결정:

```text
Redis Queue 유지
DB notification 상태 유지
관리자 페이지 MVP 구현
실패/지연/queue length 관측 강화
```

중기 결정:

```text
관리자 페이지에서 실패 알림 재처리가 잦아지거나
queue routing/retry 정책이 복잡해지면 RabbitMQ PoC 진행
```

장기 결정:

```text
RabbitMQ 도입 시에도 DB notification row는 유지
RabbitMQ는 delivery 작업 전달과 retry/DLQ 담당
DB는 사용자 알림 목록, 상태, dedupe, audit의 기준으로 유지
```
