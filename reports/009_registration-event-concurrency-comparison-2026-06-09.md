# registration_events 동시성 제어 비교

## 변경 요약

이번 변경에서는 같은 모집 기간 이벤트를 만들 때 사용하던 `pools` row의 `PESSIMISTIC_WRITE` 의존도를 줄이고, `registration_events` 테이블 자체에 유니크 제약을 둔 뒤 `duplicate key -> 재조회` 방식으로 전환했습니다.

주의할 점은, 코드상 기존 락 대상은 `pool_notices`가 아니라 `pools` row였습니다. 즉 "공지 row를 잠근다"가 아니라, 같은 `pool_id`에서 이벤트 생성 구간을 한 줄로 세우기 위해 수영장 row를 잠그던 구조였습니다.

## 왜 바꿨는가

`subscriptions`는 결국 `user_id + event_id`를 저장합니다. 그래서 핵심은 "같은 논리 이벤트를 나타내는 `registration_events` row가 한 개만 존재해야 한다"는 점입니다.

기존 방식은 이 문제를 우회적으로 풀었습니다.

- 먼저 `pools` row에 `PESSIMISTIC_WRITE`를 걸고
- 그 안에서 같은 이벤트가 있는지 조회하고
- 없으면 새 `registration_events` row를 생성했습니다

이 방식도 동작은 하지만, 중복 방지의 기준이 실제 테이블(`registration_events`)이 아니라 바깥 row(`pools`)에 걸려 있다는 점이 아쉬웠습니다.

새 방식은 중복 기준을 DB 스키마에 직접 선언합니다.

- `registration_events(pool_id, title, registration_starts_at, registration_ends_at)` 유니크 제약 추가
- 먼저 기존 row를 조회
- 없으면 insert 시도
- 동시에 다른 요청이 먼저 insert 했으면 `duplicate key` 발생
- 그 경우 같은 조건으로 다시 조회해서 기존 row를 재사용

## 비교 표

| 항목 | `pools` `PESSIMISTIC_WRITE` 방식 | `registration_events` 유니크 + duplicate key 재조회 |
| --- | --- | --- |
| 중복 방지 기준 | `pools` row 락에 간접 의존 | `registration_events` 자체가 직접 보장 |
| DB 설계 의도 표현력 | 코드 봐야 이해 가능 | 스키마만 봐도 "이 조합은 하나만 가능"이 드러남 |
| 동시성 처리 방식 | 먼저 온 트랜잭션이 pool row를 잠금 | DB가 유니크 제약으로 승자 1개만 허용 |
| 잠금 범위 | 같은 pool의 이벤트 생성 요청이 넓게 직렬화됨 | 정확히 같은 이벤트 키 충돌만 경합 |
| 멀티 인스턴스 대응 | 같은 DB를 보면 동작 | 같은 DB를 보면 동작 |
| 장애 시 해석 | 왜 막혔는지 로그/락 상태 추적 필요 | duplicate key면 "이미 누가 만들었다"가 명확 |
| 성능 특성 | 락 대기 시간이 길어질 수 있음 | 보통은 무락 조회 후 필요 시 insert, 충돌 시에만 재시도 |
| 운영 리스크 | 락 순서/대기시간 고민 필요 | 기존 중복 데이터 정리 후 제약 추가 필요 |

## 각 방식의 장단점

### `pools` `PESSIMISTIC_WRITE` 방식의 장점

- 구현이 직관적입니다. 먼저 잠그고, 안에서 조회 후 생성하면 된다는 흐름이라 애플리케이션 코드만 보면 이해하기 쉽습니다.
- DB 유니크 충돌 예외를 따로 처리하지 않아도 됩니다. 먼저 들어온 트랜잭션이 끝날 때까지 뒤 요청이 기다리므로 코드 흐름이 단순합니다.
- 같은 pool에 대한 이벤트 생성 요청을 강하게 직렬화하므로, 특정 구간에서는 예측 가능한 동작을 얻기 쉽습니다.

### `pools` `PESSIMISTIC_WRITE` 방식의 단점

- 실제로 보호하고 싶은 대상은 `registration_events`인데, 락은 바깥의 `pools` row에 걸립니다. 즉 모델 중심이 아니라 우회적인 제어입니다.
- 같은 `pool_id` 안의 서로 다른 이벤트 생성까지 넓게 직렬화될 수 있어서 경합 범위가 큽니다.
- 왜 막혔는지 확인하려면 코드와 DB 락 상태를 같이 봐야 해서 운영 해석성이 떨어집니다.
- 스키마만 봐서는 "이 이벤트는 중복되면 안 된다"는 규칙이 드러나지 않습니다.

### `registration_events` 유니크 제약 방식의 장점

- 중복 방지 규칙이 테이블에 직접 선언됩니다. 즉 "같은 pool, 제목, 기간 조합은 하나만 존재"가 DB 차원에서 보장됩니다.
- 경합 범위가 더 정확합니다. 같은 pool이라도 다른 이벤트 키면 굳이 서로 기다릴 필요가 없습니다.
- 멀티 인스턴스 환경에서도 같은 DB만 공유하면 동일하게 동작합니다.
- duplicate key가 발생하면 "이미 같은 이벤트가 먼저 생성되었다"는 의미가 명확해서 장애 해석이 쉽습니다.

### `registration_events` 유니크 제약 방식의 단점

- insert 후 duplicate key를 받아 재조회하는 흐름을 코드로 구현해야 해서 처음엔 조금 더 낯설 수 있습니다.
- 기존에 중복 데이터가 있으면 제약 추가 전에 정리 작업이 필요합니다.
- 트랜잭션 경계와 예외 처리를 잘못 설계하면 JPA flush 시점과 충돌할 수 있어 구현을 조심해야 합니다.

## 이번 구현 내용

### 1. DB에서 같은 이벤트를 한 번만 허용

추가한 제약:

`registration_events(pool_id, title, registration_starts_at, registration_ends_at)`

즉 같은 수영장, 같은 제목, 같은 모집 시작/종료 시간 조합은 한 row만 들어갈 수 있습니다.

### 2. 기존 중복 데이터도 마이그레이션에서 정리

유니크 제약을 바로 추가하면 과거 중복 row 때문에 실패할 수 있으므로, 마이그레이션에서 아래를 먼저 수행합니다.

- 중복 `registration_events`를 canonical row 하나로 매핑
- `subscriptions.event_id`, `notifications.event_id`를 canonical row로 재연결
- 충돌하는 중복 구독 row는 정리
- 마지막에 유니크 제약 추가

### 3. 서비스 레벨 동작

이제 이벤트 생성은 다음 순서로 처리됩니다.

1. 같은 키의 `registration_events` row가 이미 있으면 그대로 사용
2. 없으면 새 row insert 시도
3. 동시 요청으로 다른 트랜잭션이 먼저 성공했으면 duplicate key 발생
4. 실패한 쪽은 같은 키로 재조회해서 방금 만들어진 row를 재사용

## 이 방식이 더 맞는 이유

이 프로젝트에서 진짜 공유 단위는 `pool_notice`가 아니라 `registration_event`입니다.

- 알림 스케줄링 기준도 `registration_event`
- 구독 FK도 `registration_event`
- 알림 FK도 `registration_event`

그래서 중복 방지도 `registration_event` 레벨에서 거는 편이 모델에 더 자연스럽습니다.

## `pool_notice` 기반 설계와의 차이

`pool_notice`를 그대로 구독 대상으로 삼으면 "공지 문서"와 "알림 대상 기간 이벤트"가 섞입니다.

예를 들어 공지 하나에 기간이 여러 개 있을 수 있습니다.

- 성인반 모집
- 어린이반 모집
- 추가 접수

이 경우 `pool_notice`는 문서 1개지만, 실제 알림 단위는 여러 개입니다. 그래서 현재 구조처럼 `registration_event`를 별도 정규화해서 두는 편이 확장성과 재사용성이 좋습니다.

## 결론

이번 변경의 핵심은 "이벤트 중복 방지 규칙을 코드 락이 아니라 DB 제약으로 옮겼다"는 점입니다.

- 기존: `pools` row 락으로 간접 제어
- 변경 후: `registration_events` 유니크 제약으로 직접 제어

즉, 이제는 "같은 이벤트는 DB에 하나만 존재한다"는 규칙이 애플리케이션 관례가 아니라 스키마 차원에서 보장됩니다.

이번 프로젝트에서 최종적으로 `registration_events` 유니크 제약 방식을 선택한 이유는 다음과 같습니다.

- 실제 공유 대상이 `pool_notice`나 `pool` row가 아니라 `registration_event` 자체이기 때문입니다.
- 구독, 알림, 스케줄링 모두 `registration_event`를 기준으로 동작하므로 중복 방지 기준도 같은 레벨에 두는 편이 자연스럽습니다.
- 같은 pool 안의 모든 생성 요청을 넓게 잠그는 것보다, 정말 같은 이벤트 키 충돌만 DB가 막아주는 편이 더 정밀합니다.
- 장기적으로 코드를 읽지 않아도 스키마만 보고 핵심 제약을 이해할 수 있는 구조가 유지보수에 유리합니다.

정리하면, `PESSIMISTIC_WRITE`는 "코드 레벨에서 안전하게 순서를 세우는 방식"이고, 이번에 선택한 유니크 제약 방식은 "데이터 모델 자체가 정답을 강제하는 방식"입니다. 이 프로젝트의 구독/알림 구조에는 후자가 더 잘 맞습니다.
