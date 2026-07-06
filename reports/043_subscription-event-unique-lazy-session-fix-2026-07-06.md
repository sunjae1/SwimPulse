# 구독 기간 수정, registration_events unique, Lazy no Session 문제 분석

작성일: 2026-07-06

## 목적

마이페이지에서 구독 기간을 수정할 때 다음 문제가 함께 발생했다.

1. `registration_events`의 unique 제약 때문에 같은 기간을 다른 사용자가 쓰지 못하는 것처럼 보임
2. 구독 기간 수정 후 `Could not initialize proxy [com.swimpulse.pool.Pool#...] - no Session` 발생
3. 과거 공지 기간을 이번 달로 보정해 수동 이벤트를 만들 때 `notice_url`이 사라짐
4. 이미 존재하는 `registration_events` row를 재사용하면서 오래된 잘못된 데이터가 계속 따라옴

이 문서는 문제가 된 데이터 흐름, 해결 방식, 사용한 기술, 선택 이유, 대안과 개선점을 정리한다.

## 관련 테이블 구조

### registration_events

`registration_events`는 모집 기간 자체를 나타낸다.

핵심 unique 제약:

```sql
unique (pool_id, title, registration_starts_at, registration_ends_at)
```

의미:

```text
같은 수영장 + 같은 제목 + 같은 시작 시각 + 같은 종료 시각
-> 같은 모집 이벤트로 본다.
```

즉 이 테이블은 사용자 개인별 구독 테이블이 아니라, 여러 사용자가 공유할 수 있는 이벤트 마스터 테이블이다.

### subscriptions

`subscriptions`는 사용자가 어떤 이벤트를 구독했는지 나타낸다.

핵심 unique 제약:

```text
uk_subscription_user_event = (user_id, event_id)
```

의미:

```text
같은 사용자는 같은 이벤트를 중복 구독할 수 없다.
하지만 다른 사용자는 같은 event_id를 함께 구독할 수 있다.
```

따라서 “한 사용자가 기간을 선점해서 다른 사용자가 같은 기간을 못 쓴다”가 맞는 구조는 아니다. 정상 구조에서는 여러 사용자가 같은 `registration_events.id`를 공유하고, 각자 `subscriptions` row를 가진다.

## 문제 1. 기간 수정이 공유 이벤트를 직접 바꾸면 안 됨

### 문제가 되는 흐름

처음에는 구독 기간 수정이 이렇게 생각될 수 있다.

```text
subscription id=4774
-> event_id=3487
-> registration_events 3487의 starts_at/ends_at 수정
```

하지만 이 방식은 위험하다.

`registration_events`는 여러 사용자가 공유할 수 있는 row이기 때문이다. 한 사용자가 마이페이지에서 기간을 수정했는데 해당 `registration_events` row를 직접 수정하면, 같은 이벤트를 구독 중인 다른 사용자들의 구독 기간도 같이 바뀐다.

### 해결 흐름

현재는 기간 수정 시 기존 event row를 수정하지 않는다.

```text
사용자 A의 subscription id=4774
-> 현재 event_id=3487
-> 새 title/starts_at/ends_at 기준으로 registration_event get-or-create
-> 새 event_id=3488 생성 또는 기존 row 재사용
-> subscription id=4774의 event_id만 3488로 변경
```

코드 기준:

```text
SubscriptionService.updatePeriod(...)
-> insertService.findUpdateSource(...)
-> eventResolver.getOrCreate(...)
-> insertService.reassignEvent(...)
```

관련 코드:

```text
backend/src/main/java/com/swimpulse/subscription/SubscriptionService.java
backend/src/main/java/com/swimpulse/subscription/SubscriptionInsertService.java
backend/src/main/java/com/swimpulse/event/RegistrationEventResolver.java
```

### 왜 이 방식이 맞는가

구독 기간 수정은 “이 사용자의 구독 기준을 바꾸는 일”이지, “공식 모집 이벤트 자체를 수정하는 일”이 아니다.

그래서 변경 대상은 다음이 되어야 한다.

```text
registration_events row 자체 수정 X
subscriptions.event_id 재연결 O
```

이 방식은 다음 장점이 있다.

| 장점 | 설명 |
|---|---|
| 사용자 간 영향 차단 | 한 사용자의 수동 보정이 다른 사용자에게 전파되지 않는다. |
| 이벤트 재사용 가능 | 같은 수동 기간을 여러 사용자가 선택하면 같은 event row를 공유할 수 있다. |
| unique 제약 유지 가능 | 같은 이벤트 중복 생성을 DB가 막아준다. |
| 알림 스케줄 기준 명확 | notification scheduler는 event 기준으로 due 여부를 계산할 수 있다. |

## 문제 2. unique 제약 충돌과 get-or-create

### 문제 상황

동시에 여러 사용자가 같은 수영장, 같은 제목, 같은 기간을 구독하면 둘 다 새 `registration_events`를 만들려고 할 수 있다.

```text
요청 A: find existing 없음 -> insert 시도
요청 B: find existing 없음 -> insert 시도
```

DB unique 제약 때문에 둘 중 하나만 성공하고, 나머지는 `DataIntegrityViolationException`이 발생한다.

이건 DB 입장에서는 정상이다. 문제는 애플리케이션이 이 충돌을 “서버 오류”로 끝내면 안 된다는 점이다.

### 해결 방식

`RegistrationEventResolver`는 insert 중복 충돌을 잡고 기존 row를 다시 조회한다.

```text
1. 기존 event 조회
2. 없으면 REQUIRES_NEW insert
3. unique 충돌 발생
4. 같은 key로 다시 조회
5. 기존 row 재사용
```

코드 기준:

```text
RegistrationEventResolver.insertOrReuse(...)
RegistrationEventInsertService.findExistingAndRememberNoticeUrl(...)
```

핵심 기술:

| 기술 | 사용 이유 |
|---|---|
| DB Unique Constraint | 동시성 상황에서도 최종 중복 생성을 DB가 확실히 방지한다. |
| get-or-create 패턴 | 이미 있으면 재사용하고, 없으면 만든다. |
| DataIntegrityViolationException 처리 | 동시 insert 충돌을 정상 경쟁 상황으로 보고 재조회한다. |
| REQUIRES_NEW | insert/조회 단위를 짧은 독립 트랜잭션으로 분리한다. |

### 왜 unique 제약을 제거하지 않았나

unique 제약을 없애면 당장은 충돌이 사라진다. 하지만 같은 이벤트 row가 여러 개 생긴다.

그러면 다음 문제가 생긴다.

```text
event_id=3488: 동대문구민체육센터 7월 재등록
event_id=3489: 동대문구민체육센터 7월 재등록
event_id=3490: 동대문구민체육센터 7월 재등록
```

겉으로는 같은 모집 기간인데 DB row가 나뉘면 구독 수 집계, 알림 fan-out, dedupe, 관리자 화면 분석이 모두 어려워진다.

따라서 unique 제약은 유지하는 것이 맞다.

## 문제 3. 같은 사용자가 이미 target event를 구독 중인 경우

### 문제 상황

사용자가 구독 A를 수정해서 이미 자신이 구독 중인 event B로 옮기려 하면 `subscriptions`의 `(user_id, event_id)` unique 제약과 충돌한다.

예:

```text
subscription id=10 -> event_id=100
subscription id=11 -> event_id=200

사용자가 subscription id=10을 event_id=200과 같은 기간으로 수정
-> 같은 user_id + event_id=200 구독이 이미 있음
```

### 해결 방식

`reassignEvent(...)`에서 먼저 같은 사용자, 같은 target event 구독이 있는지 확인한다.

```text
subscriptionRepository.findByUser_IdAndEvent_Id(userId, eventId)
    .filter(existing -> !existing.getId().equals(subscription.getId()))
    .ifPresent(existing -> throw BadRequestException)
```

이 경우 500이 아니라 사용자 입력 충돌에 가까우므로 `BadRequestException`으로 처리한다.

## 문제 4. Lazy proxy no Session

### 에러

```text
Could not initialize proxy [com.swimpulse.pool.Pool#132] - no Session
```

### 원인

`RegistrationEventInsertService.insert(...)`는 별도 트랜잭션(`REQUIRES_NEW`)에서 event를 생성한다.

기존 방식에서는 `Pool`을 `entityManager.getReference(Pool.class, poolId)`로 연결할 수 있었다.

```text
REQUIRES_NEW transaction
-> Pool lazy proxy 참조
-> RegistrationEvent 저장
-> transaction 종료
-> event 객체 반환
-> 바깥에서 EventResponse.from(event)
-> event.pool 접근
-> pool proxy 초기화 필요
-> 하지만 영속성 컨텍스트 종료
-> no Session
```

즉 event row는 정상 생성됐지만, 응답 DTO를 만들 때 `event.pool` lazy proxy를 건드리면서 터졌다.

### 해결 방식

수동 event insert에서는 `Pool`을 proxy가 아니라 실제 entity로 로드했다.

```java
Pool pool = entityManager.find(Pool.class, poolId);
```

코드:

```text
backend/src/main/java/com/swimpulse/event/RegistrationEventInsertService.java
```

테스트:

```text
backend/src/test/java/com/swimpulse/event/RegistrationEventInsertServiceTests.java
insertUsesLoadedPoolInsteadOfDetachedLazyProxy()
```

### 왜 이 해결이 적절했나

이 문제의 핵심은 “응답 DTO 생성 시 필요한 pool 정보가 detached lazy proxy 상태였다”는 것이다.

해결 선택지는 여러 개가 있었다.

| 해결책 | 장점 | 단점 |
|---|---|---|
| `entityManager.find`로 Pool 실제 로드 | 단순하고 현재 응답 구조에 바로 맞음 | insert 시 pool 1회 조회 필요 |
| DTO 생성을 항상 새 read transaction에서 다시 조회 | lazy 문제를 더 일반적으로 방지 | 코드 흐름이 더 길어지고 조회 API 추가 필요 |
| fetch join repository 추가 | 응답에 필요한 graph를 명확히 로드 | 전용 쿼리 증가 |
| OSIV 활성화 | view layer에서도 lazy loading 가능 | 트랜잭션 경계가 흐려지고 운영 DB connection 점유 위험 |
| EventResponse가 pool 접근을 안 하도록 변경 | lazy 접근 회피 | 응답에 필요한 poolName/poolId를 잃거나 별도 전달 필요 |

현재 선택한 `entityManager.find`는 다음 이유로 합리적이었다.

1. 문제 지점이 event insert 직후 응답 DTO 생성으로 명확했다.
2. Pool은 event 생성에 반드시 필요한 aggregate root에 가깝다.
3. 추가 조회 1회 비용은 작고, no Session 500을 없애는 효과가 크다.
4. OSIV처럼 트랜잭션 경계를 흐리는 해결책을 쓰지 않아도 된다.

## 문제 5. notice_url이 사라지는 문제

### 문제 상황

공지에서 추출한 기간이 지난 달 기간이면 프론트에서 “이번 달 같은 날짜로 구독할까요?” 흐름을 제공한다.

이때 원래 공지의 상세 URL은 `pool_notices.url` 또는 `notice_registration_period`를 통해 알 수 있다.

하지만 사용자가 기간을 이번 달로 보정하면 더 이상 기존 `notice_registration_period_id`와 정확히 같은 기간이 아니다.

```text
원문 공지: 2026-06-20 ~ 2026-06-24
사용자 보정: 2026-07-20 ~ 2026-07-24
```

이 경우 기존 notice period에 직접 연결하면 안 된다. 그래서 수동 `registration_events`가 생성된다.

문제는 이 수동 event에도 원문 URL은 계속 필요하다는 점이다. 그래야 마이페이지, 푸시 모달, 알림 목록에서 “원문 보기”가 유지된다.

### 해결 방식

`CreateSubscriptionRequest`에 `noticeUrl`을 포함하고, 수동 event 생성 시 `registration_events.notice_url`에 저장한다.

```text
CreateSubscriptionRequest.noticeUrl
-> SubscriptionService.resolveEvent(...)
-> RegistrationEventResolver.getOrCreate(..., noticeUrl)
-> RegistrationEventInsertService.insert(..., noticeUrl)
-> registration_events.notice_url 저장
```

이미 같은 event row가 존재하지만 `notice_url`이 비어 있으면 보강한다.

```text
RegistrationEvent.rememberNoticeUrl(...)
RegistrationEventInsertService.findExistingAndRememberNoticeUrl(...)
```

### 왜 역정규화가 맞았나

`notice_url`은 현재 최신 공지 URL이라기보다 “이 event가 만들어질 당시 사용자가 본 원문 URL 스냅샷”이다.

따라서 `registration_events.notice_url`에 중복 저장하는 것은 의도적인 역정규화다.

장점:

| 장점 | 설명 |
|---|---|
| 수동 event도 원문 유지 | notice period와 직접 연결되지 않아도 원문 보기 가능 |
| 알림 payload 단순화 | notification 생성 시 event에서 바로 URL을 읽을 수 있음 |
| 과거 알림의 의미 보존 | 나중에 공지 source가 바뀌어도 당시 원문 URL을 유지 |

## 최종 데이터 흐름

### 공지 기간 그대로 구독

```text
notice_registration_period_id 있음
-> period ACTIVE 검증
-> pool 일치 검증
-> starts_at/ends_at 일치 검증
-> eventResolver.getOrCreateForNoticePeriod(...)
-> registration_events.notice_registration_period_id 연결
-> subscriptions(user_id, event_id, pool_id) insert
```

### 지난 달 기간을 이번 달로 보정해 구독

```text
notice_registration_period_id 없음
notice_url 있음
-> pool_id/title/starts_at/ends_at 기준 get-or-create
-> registration_events.notice_url 저장
-> subscriptions(user_id, event_id, pool_id) insert
```

### 마이페이지 기간 수정

```text
subscriptionId로 현재 source 조회
-> poolId, 기존 noticeUrl 확보
-> 새 title/starts_at/ends_at 기준 event get-or-create
-> 기존 subscription row의 event_id만 새 event_id로 변경
-> 응답 DTO 생성
```

## 왜 이 방식이 최선에 가까웠나

현재 요구사항은 다음을 동시에 만족해야 했다.

1. 같은 공지/같은 기간은 여러 사용자가 공유해야 한다.
2. 한 사용자의 기간 수정이 다른 사용자에게 영향을 주면 안 된다.
3. 같은 사용자가 같은 이벤트를 중복 구독하면 안 된다.
4. 원문 URL은 수동 보정 후에도 유지돼야 한다.
5. 동시 요청에서 event 중복 insert가 나면 안 된다.
6. lazy loading 때문에 응답 생성이 500으로 실패하면 안 된다.

현재 구조는 이 요구사항을 다음처럼 나눠 해결한다.

| 요구사항 | 해결 |
|---|---|
| 이벤트 공유 | `registration_events` unique 유지 |
| 개인 구독 수정 | `subscriptions.event_id` 재연결 |
| 같은 사용자 중복 방지 | `uk_subscription_user_event` 유지 |
| 동시 event insert 방지 | unique + insert 충돌 후 재조회 |
| 원문 URL 보존 | `registration_events.notice_url` 역정규화 |
| lazy no Session 방지 | 필요한 Pool entity를 실제 로드 |

즉 DB 제약을 없애서 우회한 것이 아니라, DB 제약은 데이터 모델의 마지막 방어선으로 유지하고 애플리케이션 흐름을 그 제약에 맞게 정리했다.

## 대안과 개선점

### 1. 개인 구독 기간을 별도 테이블/컬럼으로 분리

현재는 사용자의 수동 기간도 `registration_events`에 들어간다.

대안:

```text
subscriptions.custom_starts_at
subscriptions.custom_ends_at
subscriptions.custom_title
```

장점:

```text
사용자 개인 수정이 registration_events를 더럽히지 않는다.
```

단점:

```text
알림 scheduler가 event 기준과 subscription custom 기준을 모두 계산해야 한다.
fan-out 구조가 복잡해진다.
```

현재는 알림 스케줄링을 event 중심으로 유지하는 편이 단순하므로 보류가 적절하다.

### 2. registration_events에 source_type 추가

현재 수동 event와 공지 기반 event는 `notice_registration_period_id` 유무로 구분한다.

개선안:

```text
source_type: NOTICE_PERIOD / MANUAL_SHIFTED / USER_CUSTOM
source_notice_url
```

장점:

```text
관리자 화면과 분석에서 수동 보정 이벤트를 더 명확히 구분할 수 있다.
```

### 3. DTO 전용 fetch query 도입

현재는 `SubscriptionResponse.from(subscription)`이 entity graph를 직접 따라간다.

개선안:

```text
SubscriptionRepository.findResponseProjection(...)
```

또는 fetch join:

```text
subscription
join fetch subscription.pool
join fetch subscription.event
join fetch event.pool
```

장점:

```text
lazy no Session 계열 문제를 응답 조회 계층에서 더 체계적으로 막을 수 있다.
```

### 4. 기존 notice_url 누락 row 보정 SQL

이미 생성된 `registration_events.notice_url is null` row는 새 로직만으로 자동 보정되지 않을 수 있다.

운영 보정이 필요하면 다음 기준으로 backfill을 고려할 수 있다.

```text
registration_events.notice_registration_period_id is not null
-> notice_registration_periods.pool_notice_id
-> pool_notices.url
-> registration_events.notice_url 업데이트
```

단, 수동 event는 어떤 원문에서 파생됐는지 알 수 없는 row도 있으므로 무조건 보정하면 안 된다.

## 테스트로 보강한 내용

관련 테스트:

```text
SubscriptionServiceTests.updatePeriodCreatesNewEventAndReassignsOnlyCurrentSubscription
SubscriptionServiceTests.subscribeCreatesCustomEventWhenPastNoticePeriodIsShiftedToCurrentMonth
SubscriptionServiceTests.updatePeriodRejectsDuplicateSubscriptionForSameTargetEvent
RegistrationEventInsertServiceTests.insertUsesLoadedPoolInsteadOfDetachedLazyProxy
```

검증한 것:

| 테스트 | 검증 |
|---|---|
| 기간 수정 | 기존 event 수정이 아니라 새 event로 subscription만 재연결 |
| 지난 달 기간 보정 | 수동 event에도 notice_url 유지 |
| 중복 target event | 같은 사용자의 중복 구독은 BadRequest |
| lazy proxy | insert 시 Pool proxy 대신 실제 entity 사용 |

## 요약

이번 문제는 단일 버그가 아니라 `registration_events`를 공유 이벤트로 볼 것인지, 사용자 개인 구독 상태로 볼 것인지가 섞이면서 발생한 모델링/트랜잭션 문제였다.

최종 정리는 다음과 같다.

```text
registration_events = 공유 가능한 모집 이벤트
subscriptions = 사용자 개인 구독
기간 수정 = event row 수정이 아니라 subscription.event_id 재연결
동시 생성 = unique 충돌 후 재조회
원문 URL = event 생성 시점 스냅샷으로 notice_url 역정규화
lazy no Session = REQUIRES_NEW 밖으로 나갈 entity graph를 proxy로 두지 않음
```

이 구조가 현재 SwimPulse의 알림 scheduler, dedupe, 마이페이지, 모바일 푸시 흐름과 가장 잘 맞는다.
