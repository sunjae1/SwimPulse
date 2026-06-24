# registration_events.notice_url 역정규화 설계 기록

작성일: 2026-06-24

## 배경

SwimPulse의 구독은 공지에서 추출한 모집 기간을 기반으로 생성된다.

기존에는 원문 URL을 `registration_events`에 직접 저장하지 않고, 아래 FK 경로를 통해 조회했다.

```text
registration_events.notice_registration_period_id
→ notice_registration_periods.id
→ notice_registration_periods.notice_id
→ pool_notices.id
→ pool_notices.url
```

이 구조는 정규화 관점에서는 자연스럽다.

- 원문 URL은 `pool_notices.url` 한 곳에만 저장된다.
- 이벤트는 공지 기간 FK만 들고 있다.
- 중복 저장이 없다.

## 문제

마이페이지에서 사용자가 구독 기간을 직접 수정하면, 해당 구독은 더 이상 원래 공지 기간과 1:1로 일치하지 않는다.

예:

```text
기존 공지 기간:
2026-06-17 00:00 ~ 2026-06-22 23:59

사용자 수정 기간:
2026-06-20 09:00 ~ 2026-06-24 18:00
```

이 경우 시스템은 사용자만을 위한 새 `registration_events` row를 만들고, 해당 구독을 새 이벤트로 재연결한다.

```text
subscriptions.event_id
→ 새 registration_events.id
```

하지만 새 이벤트는 공지 원본의 기간 행이 아니므로:

```text
registration_events.notice_registration_period_id = NULL
```

이렇게 되면 기존 FK 경로가 끊긴다.

결과적으로 마이페이지 구독 카드에서 `원문 보기` 버튼이 사라지는 문제가 발생했다.

## 선택한 해결책

`registration_events`에 `notice_url` 컬럼을 추가했다.

```sql
ALTER TABLE registration_events
    ADD COLUMN notice_url VARCHAR(1000) NULL;
```

기존 공지 기반 이벤트는 migration에서 `pool_notices.url`을 백필한다.

```sql
UPDATE registration_events event
JOIN notice_registration_periods period
  ON period.id = event.notice_registration_period_id
JOIN pool_notices notice
  ON notice.id = period.notice_id
SET event.notice_url = notice.url
WHERE event.notice_url IS NULL;
```

기간 수정으로 사용자 지정 이벤트를 만들 때는 기존 이벤트의 원문 URL을 새 이벤트에 복사한다.

```text
기존 registration_event.notice_url
또는 기존 FK 경로의 pool_notices.url
→ 새 registration_events.notice_url
```

## 왜 역정규화를 선택했는가

이 변경은 의도적으로 중복 저장을 허용한 역정규화다.

정규화 구조에서는 원문 URL을 `pool_notices.url`에만 두는 것이 맞다. 하지만 사용자 지정 이벤트는 공지 기간 FK를 잃기 때문에 정규화 경로만으로는 원문 URL을 찾을 수 없다.

여기서 `notice_url`은 "현재 최신 공지 URL"이 아니라, "이 이벤트가 만들어질 당시 어떤 공지에서 파생됐는지"를 나타내는 스냅샷이다.

따라서 `registration_events.notice_url`은 중복 데이터라기보다 이벤트의 출처 기록에 가깝다.

## 변경 후 조회 규칙

`EventResponse.noticeUrl`은 다음 순서로 결정한다.

```text
1. notice_registration_period_id가 있으면
   notice_registration_periods → pool_notices.url 사용

2. notice_registration_period_id가 없으면
   registration_events.notice_url 사용
```

알림 응답과 FCM payload도 같은 원칙을 따른다.

## 장점

- 사용자가 기간을 수정해도 원문 보기 버튼이 유지된다.
- 공지 기간 FK가 끊긴 사용자 지정 이벤트도 원본 공지 출처를 추적할 수 있다.
- 알림 목록, 마이페이지 구독, 브라우저 푸시 모달에서 같은 원문 URL을 사용할 수 있다.
- 과거 이벤트가 어떤 공지를 기준으로 만들어졌는지 보존된다.

## 단점

- `pool_notices.url`과 `registration_events.notice_url`에 같은 값이 중복 저장될 수 있다.
- `pool_notices.url`이 나중에 바뀌어도 기존 `registration_events.notice_url`은 자동으로 바뀌지 않는다.
- 컬럼 동기화 책임이 생긴다.

## 단점이 허용 가능한 이유

공지 원문 URL은 이벤트 생성 시점의 출처 정보로 보는 것이 더 안전하다.

나중에 `pool_notices.url`이 정규화되거나 갱신되더라도, 이미 구독과 알림에 사용된 이벤트는 당시 사용자가 본 공지 원문을 가리키는 편이 자연스럽다.

즉, 이 컬럼은 최신 데이터 동기화 대상이 아니라 이벤트 스냅샷이다.

## 결론

`registration_events.notice_url`은 정규화 원칙을 일부 포기한 역정규화 컬럼이다.

하지만 사용자 지정 구독 기간이라는 도메인 요구사항 때문에 FK 기반 조회만으로는 원문 URL을 안정적으로 보존할 수 없었다.

따라서 이 경우에는 원문 URL을 이벤트 스냅샷으로 저장하는 역정규화가 더 적합하다.
