# 044 Lightsail Docker 운영 배포 준비 보고서

작성일: 2026-07-13

## 목적

SwimPulse 백엔드를 AWS Lightsail instance + Lightsail Managed MySQL + Docker Compose 구조로 배포하기 위한 운영 파일과 절차를 정리한다.

이번 배포의 기준은 다음이다.

```text
Frontend: Vercel 또는 별도 정적/Next.js 배포
Backend: Lightsail Ubuntu instance
DB: Lightsail Managed MySQL 8.0.46
Redis: Lightsail instance 내부 Docker Redis
HTTPS: Caddy reverse proxy
Push: Firebase Cloud Messaging
```

## MySQL 버전 선택

첫 배포는 `MySQL 8.0.46`을 선택한다.

이유:

| 선택지 | 판단 |
|---|---|
| MySQL 8.0.46 | 현재 개발/검증선과 같은 8.0 계열이라 첫 운영 배포에 안전하다. |
| MySQL 8.4.10 LTS | 장기 지원 버전이지만 8.0에서 8.4로 넘어가는 line 변경이므로 staging 검증 후 적용하는 편이 좋다. |

결론:

```text
첫 운영 배포: 8.0.46
추후 개선: staging에서 Flyway/JPA/날짜/문자셋/unique 제약 검증 후 8.4 LTS 검토
```

## 추가/수정한 파일

| 파일 | 내용 |
|---|---|
| `docker-compose.prod.yml` | 운영용 backend + redis compose 파일 |
| `backend/.env.prod.example` | 운영 env 템플릿. 실제 `backend/.env.prod`는 서버에서 작성 |
| `deploy/Caddyfile.example` | Caddy HTTPS reverse proxy 예시 |
| `.gitignore` | `backend/.env.prod`를 git 추적 제외 |
| `backend/.dockerignore` | `.env.*`, git/문서 파일을 Docker build context에서 제외 |
| `backend/src/main/resources/application.properties` | CORS 허용 origin을 env로 설정 가능하게 변경 |
| `backend/src/main/java/com/swimpulse/config/WebConfig.java` | CORS origin hardcoding 제거, 설정값 기반으로 변경 |
| `backend/src/test/resources/application.properties` | 테스트용 CORS 설정 추가 |

## 운영 Docker 구조

운영 compose는 `backend`와 `redis`만 실행한다.

```text
backend
  -> 127.0.0.1:8080 에만 바인딩
  -> 외부 직접 접근 차단
  -> Caddy가 HTTPS 요청을 받아 reverse proxy

redis
  -> Docker network 내부에서만 접근
  -> 외부 6379 포트 미노출
```

기존 개발용 `docker-compose.yml`은 Prometheus/Grafana/k6까지 포함하지만, 2GB Lightsail에서는 메모리가 빡빡할 수 있으므로 운영 첫 단계에서는 제외했다.

## 운영 env 작성 방식

repo에는 실제 비밀값을 넣지 않는다.

서버에서 다음처럼 만든다.

```bash
cp backend/.env.prod.example backend/.env.prod
nano backend/.env.prod
```

주요 값:

```env
SPRING_DATASOURCE_URL=jdbc:mysql://<LIGHTSAIL_MYSQL_ENDPOINT>:3306/swimpulse?useSSL=true&serverTimezone=UTC
SPRING_DATASOURCE_USERNAME=<DB_USERNAME>
SPRING_DATASOURCE_PASSWORD=<DB_PASSWORD>

GOOGLE_CLIENT_ID=<GOOGLE_WEB_CLIENT_ID>
GOOGLE_CLIENT_SECRET=<GOOGLE_CLIENT_SECRET>
SWIMPULSE_OAUTH2_REDIRECT_URI=https://api.your-domain.com/login/oauth2/code/google
SWIMPULSE_AUTH_SUCCESS_REDIRECT_URI=https://app.your-domain.com?login=success
SWIMPULSE_AUTH_FAILURE_REDIRECT_URI=https://app.your-domain.com?login=failure
SWIMPULSE_CORS_ALLOWED_ORIGIN_PATTERNS=https://app.your-domain.com

SWIMPULSE_JWT_SECRET=<CHANGE_TO_LONG_RANDOM_SECRET>
SWIMPULSE_AUTH_COOKIE_SECURE=true

SWIMPULSE_FIREBASE_SERVICE_ACCOUNT_PATH=/run/secrets/firebase-adminsdk.json
SWIMPULSE_FIREBASE_MOCK=false

SWIMPULSE_LOADTEST_ENABLED=false
SWIMPULSE_SQL_LOG_LEVEL=INFO
SWIMPULSE_SQL_BIND_LOG_LEVEL=OFF
```

## 배포 순서

### 1. Lightsail Managed MySQL 생성

1. MySQL `8.0.46` 선택
2. database name: `swimpulse`
3. username/password 생성
4. endpoint 확인
5. Lightsail instance와 같은 region 권장

### 2. Lightsail instance 생성

권장:

```text
Ubuntu
2GB RAM / 2 vCPU / 60GB SSD 가능
안정 운영은 4GB 권장
```

방화벽:

| 포트 | 용도 |
|---|---|
| 22 | SSH |
| 80 | Caddy HTTP-01 인증서 발급 |
| 443 | HTTPS |

열지 않는 포트:

```text
8080 backend
3306 MySQL
6379 Redis
```

### 3. 서버 기본 패키지 설치

```bash
sudo apt update
sudo apt install -y git curl ca-certificates
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
sudo apt install -y docker-compose-plugin caddy
```

그 다음 SSH 재접속한다.

### 4. 2GB instance면 swap 추가

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

재부팅 후 유지하려면 `/etc/fstab`에 추가한다.

```bash
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 5. 코드 clone

```bash
git clone <repo-url> SwimPulse
cd SwimPulse
```

전체 repo가 clone되어도 괜찮다. 실제 Docker build context는 `./backend`이고, 운영 compose는 backend/redis만 실행한다.

### 6. 운영 env 작성

```bash
cp backend/.env.prod.example backend/.env.prod
nano backend/.env.prod
```

반드시 실제 값으로 바꿀 것:

```text
LIGHTSAIL_MYSQL_ENDPOINT
DB username/password
Google OAuth client/secret
JWT secret
Naver API keys
도메인
```

### 7. Firebase service account 업로드

`docker-compose.prod.yml`은 아래 파일을 mount한다.

```text
deploy/secrets/firebase-adminsdk.json
  -> /run/secrets/firebase-adminsdk.json
```

서버에 파일을 업로드한다.

#### Windows PowerShell에서 `scp`로 업로드

Lightsail SSH key가 Windows에 있고, 서버에 repo를 이미 clone해둔 상태라면 PowerShell에서 다음처럼 보낸다.

```powershell
scp -i "C:\Users\kimsunjae\Downloads\LightsailDefaultKey-ap-northeast-2.pem" `
  "C:\Users\kimsunjae\Downloads\firebase-adminsdk.json" `
  ubuntu@<LIGHTSAIL_PUBLIC_IP>:/home/ubuntu/SwimPulse/deploy/secrets/firebase-adminsdk.json
```

도메인 DNS가 이미 연결되어 있으면 IP 대신 도메인도 가능하다.

```powershell
scp -i "C:\Users\kimsunjae\Downloads\LightsailDefaultKey-ap-northeast-2.pem" `
  "C:\Users\kimsunjae\Downloads\firebase-adminsdk.json" `
  ubuntu@api.your-domain.com:/home/ubuntu/SwimPulse/deploy/secrets/firebase-adminsdk.json
```

서버에 `SwimPulse/backend` 폴더가 아직 없다면 먼저 만든다.

```powershell
ssh -i "C:\Users\kimsunjae\Downloads\LightsailDefaultKey-ap-northeast-2.pem" ubuntu@<LIGHTSAIL_PUBLIC_IP> `
  "mkdir -p /home/ubuntu/SwimPulse/deploy/secrets"
```

업로드 후 서버에서 확인한다.

```bash
ls -l ~/SwimPulse/deploy/secrets/firebase-adminsdk.json
chmod 600 ~/SwimPulse/deploy/secrets/firebase-adminsdk.json
```

Windows OpenSSH에서 private key 권한 경고가 나면 PowerShell에서 key 권한을 제한한다.

```powershell
icacls "C:\Users\kimsunjae\Downloads\LightsailDefaultKey-ap-northeast-2.pem" /inheritance:r
icacls "C:\Users\kimsunjae\Downloads\LightsailDefaultKey-ap-northeast-2.pem" /grant:r "$($env:USERNAME):R"
```

#### 서버에서 직접 붙여넣기

파일을 직접 만들 수도 있다.

```bash
nano deploy/secrets/firebase-adminsdk.json
```

또는 `scp`로 올린다.

```bash
scp firebase-adminsdk.json ubuntu@<server-ip>:~/SwimPulse/deploy/secrets/firebase-adminsdk.json
```

하지만 JSON은 줄바꿈과 따옴표가 중요하므로, 가능하면 `scp` 업로드 방식을 권장한다.

### 8. Caddy 설정

DNS에서 `api.your-domain.com`의 A record를 Lightsail static IP로 연결한다.

그 다음:

```bash
sudo nano /etc/caddy/Caddyfile
```

예시:

```caddy
api.your-domain.com {
	reverse_proxy 127.0.0.1:8080
}
```

적용:

```bash
sudo systemctl reload caddy
```

### 9. Google OAuth 설정

Google Cloud Console에서 Web OAuth client에 redirect URI를 추가한다.

```text
https://api.your-domain.com/login/oauth2/code/google
```

프론트가 Vercel이면 authorized JavaScript origins에도 프론트 도메인을 추가한다.

```text
https://app.your-domain.com
```

### 10. Docker 실행

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

로그 확인:

```bash
docker compose -f docker-compose.prod.yml logs -f backend
```

상태 확인:

```bash
curl https://api.your-domain.com/actuator/health
```

### 11. 프론트/Vercel env

Vercel에는 다음 값을 넣는다.

```env
NEXT_PUBLIC_API_BASE_URL=https://api.your-domain.com
BACKEND_INTERNAL_API_BASE_URL=https://api.your-domain.com
```

### 12. 모바일 APK 운영 API 주소

현재 모바일 API 주소는 `mobile/src/api/client.ts`에 개발용으로 고정되어 있다.

```ts
export const API_BASE_URL = 'http://10.0.2.2:8080';
```

운영 APK를 만들 때는 다음처럼 바꿔야 한다.

```ts
export const API_BASE_URL = 'https://api.your-domain.com';
```

장기적으로는 `react-native-config` 같은 환경 분리 도구를 붙이는 것이 좋다.

## CI/CD를 붙일 때

처음 1회는 서버에 repo clone이 필요하다.

그 다음 GitHub Actions에서 SSH로 접속해 다음 명령을 실행하는 방식이 가장 단순하다.

```bash
cd ~/SwimPulse
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

GHCR image 배포는 나중에 서버 빌드가 부담될 때 전환한다.

## 검증 결과

### Compose config

`docker compose -f docker-compose.prod.yml config` 확인 완료.

결과:

```text
정상
```

### 백엔드 context test

실행:

```bash
./gradlew.bat test --tests com.swimpulse.SwimPulseApplicationTests
```

결과:

```text
BUILD SUCCESSFUL
```

### 전체 백엔드 테스트

실행:

```bash
./gradlew.bat test
```

결과:

```text
70 tests completed, 3 failed
```

남은 실패:

| 테스트 | 상태 |
|---|---|
| `NoticeCrawlerServiceTests.detailNoticeCandidatesSupportFnViewOnclickLinks` | 실패 |
| `NoticeCrawlerServiceTests.detailNoticeCandidatesPreferRealContentAreaOverGenericPopupContent` | 실패 |
| `NoticeCrawlerServiceTests.imageOcrRetryNormalizesDuplicateRangeFragmentsAndSuppressesMonthlyFalsePositive` | 실패 |

이 3개는 공지 파싱/OCR 기대값 관련 실패이며, 이번 운영 배포 설정 변경으로 생긴 CORS/context 문제는 해결됐다.

## 운영 전 체크리스트

| 항목 | 확인 |
|---|---|
| MySQL 8.0.46 생성 | 필요 |
| `backend/.env.prod` 실제 값 입력 | 필요 |
| `deploy/secrets/firebase-adminsdk.json` 업로드 | 필요 |
| DNS `api` A record 설정 | 필요 |
| Caddy HTTPS 발급 확인 | 필요 |
| Google OAuth redirect URI 변경 | 필요 |
| Vercel API URL 변경 | 필요 |
| 모바일 운영 API URL 변경 | APK 배포 전 필요 |
| Lightsail 방화벽 80/443/22만 허용 | 필요 |
| Redis 6379 외부 미노출 | 완료 구조 |
| backend 8080 외부 미노출 | 완료 구조 |

## 최종 판단

현재 구조에서는 Lightsail instance 한 대에 `backend + redis`를 Docker Compose로 띄우고, DB는 Lightsail Managed MySQL로 분리하는 방식이 가장 현실적이다.

2GB instance에서도 시작은 가능하지만, Docker build와 OCR 때문에 메모리가 빡빡할 수 있다. 초기에는 swap을 추가하고 Prometheus/Grafana는 제외한 지금의 `docker-compose.prod.yml`로 시작하는 것이 좋다.
