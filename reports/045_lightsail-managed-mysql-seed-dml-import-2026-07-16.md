# 045 Lightsail Managed MySQL DML Seed Import 절차

작성일: 2026-07-16

## 목적

로컬 MySQL의 운영 seed 데이터를 Lightsail Managed MySQL로 옮긴다.

전제:

```text
1. Lightsail Managed MySQL에 swimpulse database가 있다.
2. 운영 backend가 한 번 실행되어 Flyway migration이 끝났다.
3. 원격 DB에 flyway_schema_history와 모든 테이블이 생성되어 있다.
4. 스키마는 dump/import 하지 않는다.
5. 데이터만 dump/import 한다.
```

## 가져갈 데이터 범위

처음 운영 배포에서는 사용자성 데이터는 가져가지 않는 편이 안전하다.

가져갈 추천 테이블:

| 순서 | 테이블 | 이유 |
|---:|---|---|
| 1 | `pools` | 수영장 기본 데이터 |
| 2 | `pool_notice_sources` | 수영장 공지 source |
| 3 | `pool_notices` | 수집된 공지 |
| 4 | `notice_registration_periods` | 공지에서 추출한 모집 기간 |
| 5 | `registration_events` | 구독 가능한 이벤트 |

가져가지 않는 추천 테이블:

| 테이블 | 이유 |
|---|---|
| `flyway_schema_history` | 운영 DB에서 Flyway가 직접 만든 migration 이력이 맞다. |
| `app_users` | 로컬 테스트 사용자라 운영과 섞이면 혼란스럽다. |
| `social_accounts` | 로컬 OAuth 계정 연결 정보다. |
| `user_devices` | 로컬/브라우저/모바일 FCM token은 운영에서 재등록해야 한다. |
| `subscriptions` | 로컬 구독은 운영 사용자와 맞지 않는다. |
| `notifications` | 로컬 알림 이력은 운영에 필요 없다. |
| `admin_action_logs` | 로컬 관리자 작업 로그는 운영 로그가 아니다. |
| `pool_add_requests` | 로컬 테스트 요청이면 제외하는 편이 좋다. |

## 1. 원격 DB 상태 확인

원격 DB에 접속한다.

```powershell
mysql -h <LIGHTSAIL_MYSQL_ENDPOINT> -P 3306 -u swimpulse -p swimpulse
```

Flyway 적용 상태를 확인한다.

```sql
SHOW TABLES;

SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

`V1`부터 최신 migration까지 `success = 1`이면 정상이다.

## 2. 로컬 DB에서 데이터만 dump

PowerShell에서 실행한다.

```powershell
mysqldump -h localhost -P 3306 -u swimpulse -p `
  --single-transaction `
  --quick `
  --no-create-info `
  --skip-triggers `
  --skip-lock-tables `
  --skip-add-locks `
  --skip-disable-keys `
  --no-tablespaces `
  --set-gtid-purged=OFF `
  --default-character-set=utf8mb4 `
  --complete-insert `
  swimpulse `
  pools pool_notice_sources pool_notices notice_registration_periods registration_events `
  > swimpulse_seed_data.sql
```

중요한 옵션:

| 옵션 | 의미 |
|---|---|
| `--no-create-info` | `CREATE TABLE` 없이 `INSERT`만 dump |
| `--single-transaction` | InnoDB 기준 일관된 snapshot dump |
| `--quick` | 큰 결과를 메모리에 모두 올리지 않고 stream 처리 |
| `--skip-triggers` | trigger dump 제외 |
| `--skip-lock-tables` | dump 중 테이블 lock 시도 방지 |
| `--skip-add-locks` | dump 파일 안에 `LOCK TABLES` / `UNLOCK TABLES` 출력 방지 |
| `--skip-disable-keys` | dump 파일 안에 `ALTER TABLE ... DISABLE/ENABLE KEYS` 출력 방지 |
| `--no-tablespaces` | tablespace metadata dump 방지. `PROCESS` 권한 에러 회피 |
| `--set-gtid-purged=OFF` | GTID 관련 문구 제외 |
| `--complete-insert` | 컬럼명을 포함한 INSERT 생성 |

`mysqldump`는 데이터만 dump해도 기본 옵션 때문에 아래 같은 보조문을 넣을 수 있다.

```sql
LOCK TABLES `pool_notice_sources` WRITE;
/*!40000 ALTER TABLE `pool_notice_sources` DISABLE KEYS */;
/*!40000 ALTER TABLE `pool_notice_sources` ENABLE KEYS */;
UNLOCK TABLES;
```

이 문장은 테이블 schema를 바꾸려는 migration용 DDL이라기보다 import 속도와 일관성을 위한 dump 보조문이다. 원격 Managed MySQL에 seed 데이터를 넣을 때는 권한 문제와 혼선을 줄이기 위해 위 옵션들로 제거하는 편이 좋다.

dump 파일이 생겼는지 확인한다.

```powershell
Get-Item .\swimpulse_seed_data.sql
```

`flyway_schema_history`가 들어가지 않았는지 확인한다.

```powershell
Select-String -Path .\swimpulse_seed_data.sql -Pattern "flyway_schema_history"
```

아무 결과가 없어야 한다.

## 3. 원격 DB에 import

PowerShell에서 바로 원격 DB로 넣는다.

```powershell
mysql -h <LIGHTSAIL_MYSQL_ENDPOINT> -P 3306 -u swimpulse -p `
  --default-character-set=utf8mb4 `
  swimpulse < .\swimpulse_seed_data.sql
```

비밀번호는 명령어에 직접 쓰지 말고 prompt에 입력한다.

## 4. import 결과 확인

원격 DB에서 count를 확인한다.

```sql
SELECT COUNT(*) AS pools FROM pools;
SELECT COUNT(*) AS pool_notice_sources FROM pool_notice_sources;
SELECT COUNT(*) AS pool_notices FROM pool_notices;
SELECT COUNT(*) AS notice_registration_periods FROM notice_registration_periods;
SELECT COUNT(*) AS registration_events FROM registration_events;
```

대표 데이터도 확인한다.

```sql
SELECT id, title, address
FROM pools
ORDER BY id
LIMIT 10;

SELECT id, pool_id, title, registration_starts_at, registration_ends_at, notice_url
FROM registration_events
ORDER BY id DESC
LIMIT 10;
```

## 5. import 재시도 시 주의

처음 import가 중간에 실패하면 같은 primary key 때문에 재시도에서 duplicate key가 날 수 있다.

서비스 공개 전에 원격 seed 데이터를 비우고 다시 넣을 때만 아래처럼 정리한다.

```sql
SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM notifications;
DELETE FROM subscriptions;
DELETE FROM registration_events;
DELETE FROM notice_registration_periods;
DELETE FROM pool_notices;
DELETE FROM pool_notice_sources;
DELETE FROM pools;

SET FOREIGN_KEY_CHECKS = 1;
```

주의:

```text
운영 사용자가 이미 생긴 뒤에는 위 삭제 SQL을 함부로 실행하면 안 된다.
subscriptions/notifications까지 날아갈 수 있다.
```

## 6. 서버에서 backend 재확인

import 후 backend 로그를 본다.

```bash
docker logs --tail=200 -f swimpulse-backend
```

health check:

```bash
curl -i https://api.sunjae.link/actuator/health
```

프론트에서 수영장 목록/공지 확인/구독 흐름을 확인한다.

## 전체 요약

```text
Flyway: 운영 DB 스키마 생성
DML dump: 필요한 seed 테이블만 데이터 import
제외: flyway_schema_history, users, devices, subscriptions, notifications
검증: count 확인, backend 로그 확인, 프론트 동작 확인
```
