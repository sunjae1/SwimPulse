# 관리자 읽기 전용 대시보드 1단계 구현 보고서

작성일: 2026-06-24

## 구현 목적

관리자 페이지의 첫 단계는 운영자가 SQL이나 Docker 로그를 직접 보지 않아도 현재 서비스 상태를 빠르게 확인하는 것이다.

이번 범위는 읽기 전용이다. 알림 재큐잉, 공지 source 재검증, 시설 추가 승인 같은 변경 액션은 아직 넣지 않았다.

## 구현 범위

### Backend

추가 API:

```text
GET /api/admin/dashboard
```

응답에 포함되는 정보:

| 구분 | 내용 |
|---|---|
| Overview | 사용자 수, 수영장 수, 구독 수, 이벤트 수, 활성 기기 수 |
| Notification | Redis queue length, 전체 알림 수, 상태별 알림 수, stale SENDING 수 |
| Notice | 공지 수, source 상태별 수, extraction 상태별 수, OCR 상태별 수 |
| Worker | 알림 worker batch size, delay, stale 기준, event scheduler 설정 |
| Ranking | 구독 수 기준 인기 수영장 상위 10개 |

추가된 주요 파일:

```text
backend/src/main/java/com/swimpulse/admin/AdminDashboardController.java
backend/src/main/java/com/swimpulse/admin/AdminDashboardService.java
backend/src/main/java/com/swimpulse/admin/AdminDashboardResponse.java
backend/src/main/java/com/swimpulse/admin/AdminMetricCount.java
backend/src/main/java/com/swimpulse/admin/AdminPoolRankingResponse.java
```

### Frontend

추가 페이지:

```text
/admin
```

추가된 주요 파일:

```text
frontend/src/app/admin/page.tsx
frontend/src/components/AdminDashboardClient.tsx
```

상단 네비게이션에도 `관리자` 링크를 추가했다.

```text
frontend/src/components/AppNavigation.tsx
```

## 권한 구조

기존에는 사용자 role이 없었다. 관리자 API 보호를 위해 `app_users.role`을 추가했다.

추가 migration:

```text
backend/src/main/resources/db/migration/V14__add_app_user_role.sql
```

내용:

```sql
ALTER TABLE app_users
    ADD COLUMN role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER';
```

Spring Security 설정:

```text
/api/admin/** -> ROLE_ADMIN 필요
나머지 /api/** -> 로그인 필요
```

JWT에도 role claim을 포함했다. 따라서 사용자의 role을 SQL로 바꾼 뒤에는 기존 로그인 쿠키에 반영되지 않는다. 반드시 로그아웃 후 다시 로그인해야 새 JWT에 `role=ADMIN`이 들어간다.

## 내가 해야 하는 SQL

관리자로 만들 이메일을 확인한다.

```sql
SELECT id, email, display_name, role
FROM app_users
ORDER BY id;
```

본인 계정에 ADMIN 권한을 부여한다.

```sql
UPDATE app_users
SET role = 'ADMIN'
WHERE email = '본인_구글_이메일';
```

반영 확인:

```sql
SELECT id, email, display_name, role
FROM app_users
WHERE email = '본인_구글_이메일';
```

그 다음 SwimPulse에서 로그아웃 후 다시 Google 로그인한다.

## 확인 방법

1. 백엔드 컨테이너 또는 로컬 백엔드를 다시 띄운다.
2. Flyway가 `V14` migration을 적용하는지 확인한다.
3. 위 SQL로 본인 계정을 `ADMIN`으로 바꾼다.
4. 브라우저에서 로그아웃 후 다시 로그인한다.
5. `/admin`으로 이동한다.
6. 읽기 전용 대시보드가 보이면 성공이다.

권한이 없으면 `/admin` 화면에서 다음 의미의 오류가 나온다.

```text
ADMIN 권한이 없습니다. app_users.role을 ADMIN으로 변경한 뒤 다시 로그인해야 합니다.
```

## 현재 화면에서 보는 것

### 알림 Queue 상태

| 항목 | 의미 |
|---|---|
| Redis queue | 아직 worker가 pop하지 않은 알림 작업 수 |
| 전체 알림 | notifications 전체 row 수 |
| 대기 알림 | status=QUEUED |
| stale SENDING | SENDING 상태가 오래 지속된 알림 수 |

### 공지 상태

| 항목 | 의미 |
|---|---|
| source 상태 | CANDIDATE / VERIFIED / INACTIVE / FAILED |
| extraction 상태 | EXTRACTED / LINK_ONLY / FAILED |
| OCR 상태 | NOT_REQUIRED / PENDING / PROCESSING / COMPLETED / NO_PERIOD / FAILED |

### 인기 수영장

현재는 `subscriptions` 기준으로 구독 수가 많은 수영장 상위 10개를 보여준다.

## 아직 하지 않은 것

이번 1단계에서는 다음 기능을 넣지 않았다.

```text
실패 알림 재큐잉
stale SENDING 수동 재큐잉
공지 source 재검증 버튼
OCR 재시도 버튼
시설 추가 요청 승인/반려
관리자 audit log
사용자 상세 조회
알림 상세 조회
```

이 기능들은 관리자 페이지 2단계에서 안전장치와 함께 추가하는 편이 좋다.

## 추천 다음 단계

1. 관리자 audit log 테이블 추가
2. 실패 알림 목록 페이지 추가
3. 단건 알림 requeue 버튼 추가
4. stale SENDING 재큐잉 버튼 추가
5. “이 시설 추가” 요청 테이블과 관리자 승인 화면 추가
6. 공지 source / OCR 실패 상세 화면 추가

