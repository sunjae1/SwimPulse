# 공지 모집 기간 정규화 및 이벤트 연결 보고서

작성일: 2026-06-10

## 1. 변경 목적

하나의 공지 페이지에는 여러 모집 기간이 존재할 수 있다.

예:

```text
재등록회원: 6월 15일 ~ 20일
성동구민 우선등록회원: 6월 21일 ~ 22일
일반등록회원: 6월 23일 ~ 7월 7일
```

기존에는 이 기간들을 `pool_notices.registration_periods_json` 한 컬럼에 JSON 배열로 저장했다. 화면에 여러 기간을 표시하는 것은 가능했지만, 개별 기간을 DB 관계와 구독 대상으로 관리하기에는 한계가 있었다.

이번 변경은 공지 안의 각 기간을 독립된 행으로 저장하고, 사용자가 선택한 기간을 `registration_events`와 직접 연결하는 것이 목적이다.

---

## 2. 변경 전 구조

```text
pool_notices
├─ registration_starts_at
├─ registration_ends_at
└─ registration_periods_json
```

여러 기간은 다음처럼 한 컬럼에 저장됐다.

```json
[
  {
    "label": "재등록회원",
    "startsAt": "2026-06-14T15:00:00Z",
    "endsAt": "2026-06-20T14:59:59Z"
  },
  {
    "label": "일반등록회원",
    "startsAt": "2026-06-22T15:00:00Z",
    "endsAt": "2026-07-07T14:59:59Z"
  }
]
```

`registration_events`는 공지 기간과 FK 관계 없이 다음 값의 조합으로만 재사용했다.

```text
pool_id
title
registration_starts_at
registration_ends_at
```

### 문제점

1. JSON 배열 안의 기간은 독립적인 PK가 없다.
2. 사용자가 어떤 공지의 어떤 기간을 구독했는지 FK로 추적할 수 없다.
3. 특정 기간만 조회하거나 상태를 변경하기 어렵다.
4. 기간이 사라졌는지, 새로 추가됐는지 DB 행 단위로 비교하기 어렵다.
5. JSON 전체를 역직렬화해야 기간을 조회할 수 있다.
6. 대표 시작·종료 시각은 여러 기간 중 하나만 표현한다.

---

## 3. 변경 후 구조

```text
pool_notices 1 : N notice_registration_periods
notice_registration_periods 1 : 0..1 registration_events
```

### `notice_registration_periods`

공지에서 파싱한 기간 하나가 테이블의 한 행이 된다.

주요 컬럼:

```text
id
notice_id
label
normalized_label
starts_at
ends_at
period_text
source
status
created_at
updated_at
```

중복 방지 기준:

```text
UNIQUE (
    notice_id,
    normalized_label,
    starts_at,
    ends_at
)
```

### `registration_events`

다음 nullable unique FK가 추가됐다.

```text
notice_registration_period_id
```

nullable인 이유는 기존 수동 이벤트도 계속 지원하기 위해서다.

unique인 이유는 하나의 공지 기간에 같은 목적의 이벤트가 여러 개 생기는 것을 막기 위해서다.

실제 관계는 다음과 같다.

```text
공지 기간 하나
├─ 아직 구독되지 않음 → 이벤트 0개
└─ 구독됨          → 이벤트 1개
```

따라서 DB 모델상 `1:0..1`이고, 이벤트가 만들어진 뒤에는 `1:1`이다.

---

## 4. 신규 크롤링 저장 방식

크롤러가 반환한 `List<NoticeRegistrationPeriod>`의 각 요소를 개별 행으로 동기화한다.

```text
동일 기간 발견
→ 기존 행 재사용
→ 내용 갱신
→ ACTIVE

새 기간 발견
→ 새 행 INSERT

이전에는 있었지만 최신 분석에서 사라짐
→ 삭제하지 않고 INACTIVE
```

사라진 기간을 바로 삭제하지 않는 이유:

- 이미 이벤트나 구독과 연결됐을 수 있다.
- 크롤링 실패로 일시적으로 누락됐을 수 있다.
- 과거 분석 결과와 변경 이력을 확인할 수 있다.

`registration_periods_json`은 기존 데이터 호환과 장애 시 확인을 위해 당분간 유지한다. 다만 정상 이관된 공지는 새 테이블의 `ACTIVE` 행을 우선 조회한다.

---

## 5. 기존 JSON 데이터 이관

관리 API:

```text
POST /api/pools/notices/periods/migrate?limit=50
```

동작:

1. 아직 이관하지 않은 `pool_notices`를 제한 개수만큼 조회한다.
2. `registration_periods_json`을 `NoticeRegistrationPeriod` 목록으로 역직렬화한다.
3. 배열 요소마다 `notice_registration_periods` 행을 생성한다.
4. JSON이 없지만 대표 시작·종료 시각이 있으면 legacy 기간 한 행으로 이관한다.
5. 기존 이벤트와 pool, 제목, 시작 시각, 종료 시각이 모두 같으면 FK를 연결한다.
6. 공지에 이관 완료 시각 또는 오류를 기록한다.

상태 확인 API:

```text
GET /api/pools/notices/periods/migration-status
```

실제 이관 결과:

```text
전체 공지: 66
이관 완료 공지: 66
미이관 공지: 0
이관 오류: 0
활성 기간: 92
비활성 기간: 0
고아 기간: 0
기존 이벤트 FK 연결: 2
```

배치 호출 결과:

```text
1차: 공지 50개, 기간 61개, 이벤트 연결 2개
2차: 공지 16개, 기간 31개, 이벤트 연결 0개
```

---

## 6. 이벤트 FK가 2개만 연결된 이유

이관 당시 활성 기간은 92개였지만, 모든 기간에 대해 이벤트를 미리 만들지는 않는다.

기존 `registration_events` 중 다음 값이 모두 일치한 행만 기간 FK를 연결했다.

```text
pool_id
title
registration_starts_at
registration_ends_at
기존 notice_registration_period_id가 비어 있음
```

이 조건에 맞은 기존 이벤트가 2개였다.

```text
활성 기간 92개
├─ 기존 이벤트와 일치: 2개 → FK 연결
└─ 아직 이벤트 없음: 90개 → 구독 시 생성
```

기간마다 이벤트를 미리 만들지 않는 이유:

- 실제로 아무도 구독하지 않는 기간까지 이벤트가 생성된다.
- 스케줄러가 불필요한 이벤트를 계속 검사하게 된다.
- 공지 파싱 결과와 실제 알림 이벤트의 책임이 섞인다.

기간은 공지 분석 결과이고, 이벤트는 사용자가 알림을 신청했을 때 생성되는 실행 데이터로 분리했다.

---

## 7. 구독 생성 흐름

프론트는 공지 결과에서 사용자가 선택한 기간 ID를 전달한다.

```json
{
  "poolId": 16,
  "title": "신규 회원 - 7월 회원 모집",
  "registrationStartsAt": "2026-06-30T15:00:00Z",
  "registrationEndsAt": "2026-07-03T14:59:59Z",
  "noticeRegistrationPeriodId": 43
}
```

backend 검증 순서:

1. 기간 ID가 존재하는지 확인한다.
2. 기간 상태가 `ACTIVE`인지 확인한다.
3. 요청한 `poolId`와 기간의 공지 시설이 같은지 확인한다.
4. 요청 시작·종료 시각이 DB 기간과 같은지 확인한다.
5. 해당 기간에 연결된 이벤트가 있으면 재사용한다.
6. 없으면 이벤트를 생성하고 FK를 연결한다.
7. 사용자와 이벤트 사이의 구독을 생성한다.

클라이언트가 임의로 기간이나 시설을 바꿔 요청해도 DB의 공지 기간과 일치하지 않으면 거부된다.

### backend에서 다시 검증하는 이유

프론트가 공지 조회 API에서 받은 값을 그대로 다시 보내더라도 backend는 해당 값을 신뢰하지 않는다. 브라우저에 전달된 이후의 요청 값은 사용자가 개발자 도구, 별도 HTTP 클라이언트 또는 변조된 프론트 코드로 바꿀 수 있기 때문이다.

예:

```text
periodId=43은 poolId=16 소속
→ 클라이언트가 poolId=22로 변경해 요청
→ backend가 기간의 실제 poolId와 비교하고 거부

DB 기간: 7월 1일 ~ 7월 3일
→ 클라이언트가 7월 1일 ~ 7월 31일로 변경해 요청
→ backend가 DB 시각과 비교하고 거부
```

이 검증이 방어하는 문제:

- 브라우저 개발자 도구나 API 클라이언트를 통한 요청 값 변조
- 오래 열린 화면에서 전송된 만료되거나 변경 전인 기간
- 프론트 버그로 다른 시설의 기간 ID와 `poolId`가 섞이는 문제
- 제3자가 만든 비공식 클라이언트의 잘못된 요청
- 사용자가 다른 공지 기간을 자신의 요청에 임의로 조합하는 문제

HTTPS는 네트워크 구간의 중간자 공격과 도청·변조를 방어한다. 여기의 backend 검증은 HTTPS가 적용돼 있어도 클라이언트 자체를 신뢰할 수 없다는 원칙에 따른 서버 측 무결성 검증이다.

즉, 같은 값을 DB에서 불필요하게 두 번 읽는 것이 아니다.

```text
클라이언트가 주장하는 값
vs
기간 ID로 DB에서 조회한 authoritative 값
```

두 값이 같은지 확인하는 과정이다.

### 구독 후 사용자가 기간을 수정하는 경우

공지에서 처음 구독할 때는 파싱된 기간의 정확한 출처를 보존하기 위해 공지 기간 FK가 연결된 이벤트를 사용한다.

```text
notice_registration_period 43
→ registration_event 10
→ 사용자 A 구독
→ 사용자 B 구독
```

사용자 A가 마이페이지에서 알림 날짜나 제목을 수정한다고 해서 공유 이벤트의 FK나 시간을 바꾸면 안 된다. 같은 이벤트를 구독 중인 사용자 B의 알림까지 함께 변경되기 때문이다.

현재 수정 흐름은 다음과 같다.

```text
사용자 A가 마이페이지에서 기간 수정
→ 수정한 날짜의 수동 registration_event를 생성하거나 재사용
→ 새 이벤트의 notice_registration_period_id는 NULL
→ 사용자 A의 subscription.event_id만 새 이벤트로 변경

기존 공지 기간 이벤트
→ FK와 원본 날짜를 그대로 보존
→ 사용자 B 구독에는 영향 없음
```

따라서 사용자의 자유로운 수정 기능을 위해 공지 기간 FK를 끊을 필요는 없다. 원본 공지 기반 이벤트는 그대로 두고, 수정한 사용자만 별도의 사용자 지정 이벤트로 이동시키는 방식이 안전하다.

현재 API 구분:

```text
공지 결과에서 최초 구독
POST /api/subscriptions
noticeRegistrationPeriodId 포함
→ 공지 기간 FK가 연결된 이벤트 사용

마이페이지에서 구독 기간 수정
PATCH /api/subscriptions/{subscriptionId}
noticeRegistrationPeriodId 없음
→ FK 없는 사용자 지정 이벤트 사용
```

수정 후 기존 이벤트에 구독자가 한 명도 남지 않을 수 있다. 이는 FK를 깨야 하는 문제는 아니며, 필요해지면 구독자가 없고 알림 이력도 없는 이벤트를 별도 정리하는 정책으로 처리할 수 있다.

---

## 8. 이전·이후 비교

| 항목 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 여러 기간 저장 | JSON 배열 한 컬럼 | 기간마다 한 행 |
| 기간 식별자 | 없음 | `notice_registration_periods.id` |
| 공지와 기간 관계 | JSON 내부 구조 | 명시적 `1:N` FK |
| 기간과 이벤트 관계 | 값 비교로 추정 | nullable unique FK |
| 중복 방지 | 애플리케이션 파싱 의존 | DB unique 제약 |
| 기간 삭제 감지 | JSON 전체 비교 필요 | 행을 `INACTIVE` 처리 |
| 기간별 구독 | 날짜와 제목 전달 | 기간 ID 전달 후 서버 검증 |
| 조회 | 매번 JSON 역직렬화 | 활성 기간 행 조회 |
| 추적성 | 어떤 기간의 이벤트인지 불명확 | 이벤트에서 원본 기간 추적 가능 |
| 수동 이벤트 | 지원 | FK를 null로 두고 계속 지원 |

---

## 9. 변경 후 구조가 더 나은 이유

### 데이터 의미가 명확하다

공지, 공지 안의 모집 기간, 알림 이벤트가 각각 별도 책임을 갖는다.

```text
pool_notice
→ 원문 공지

notice_registration_period
→ 원문에서 파싱한 개별 모집 기간

registration_event
→ 알림 스케줄러가 처리할 실행 이벤트
```

### 무결성을 DB가 보장한다

동일 공지의 동일 기간 중복과 한 기간에 여러 이벤트가 연결되는 문제를 unique 제약으로 방지한다.

### 구독 검증이 안전하다

프론트가 보낸 날짜만 신뢰하지 않고, 선택한 기간 ID를 기준으로 DB 값을 다시 검증한다.

### 변경과 이력을 관리할 수 있다

공지에서 기간이 사라져도 행을 삭제하지 않고 `INACTIVE`로 남기므로 기존 이벤트와 구독의 참조가 깨지지 않는다.

### 향후 기능 확장이 쉽다

다음 기능을 기간 행 기준으로 구현할 수 있다.

- 특정 공지 기간의 구독자 수 조회
- 기간 변경 감지
- 기간별 알림 발송 이력
- 기간별 파싱 신뢰도와 검수 상태
- 지난 기간 보관 및 검색
- 관리자 기간 수정

---

## 10. 검증 결과

```text
backend: .\gradlew.bat test 통과
frontend: npm run build 통과
Flyway: V7 적용 완료
Hibernate ddl-auto=validate 통과
Docker backend 재빌드 완료
공지 스캔 응답에서 기간 ID 반환 확인
DB 고아 기간 0건 확인
```

`npm run lint`는 이번 변경과 무관한 기존 `DashboardClient.tsx` effect 내부 동기 `setState` 규칙 오류 2건이 남아 있다.

---

## 11. 결론

변경 전 구조는 크롤링 결과를 빠르게 저장하고 화면에 표시하기에는 단순했다. 하지만 여러 기간을 독립적인 구독 대상으로 다루기 시작하면서 JSON 한 컬럼만으로는 관계, 무결성, 상태, 추적성을 표현하기 어려웠다.

변경 후에는 공지 기간이 실제 도메인 엔티티가 됐다. 기간은 파싱 결과로 보존하고, 사용자가 선택한 기간에 대해서만 이벤트를 만들어 연결한다. 이 구조는 불필요한 이벤트 생성을 피하면서도 구독과 알림이 어떤 원문 기간에서 만들어졌는지 정확하게 추적할 수 있다.
