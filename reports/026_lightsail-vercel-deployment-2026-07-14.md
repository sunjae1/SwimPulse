# Lightsail + Managed MySQL + Vercel 배포 매뉴얼

작성일: 2026-07-14

## 목표 구조

이번 배포 구조는 아래처럼 나눈다.

| 영역 | 위치 | 실행 방식 |
|---|---|---|
| Backend Spring Boot | AWS Lightsail Ubuntu | Docker Compose |
| Redis | AWS Lightsail Ubuntu | Docker Compose |
| MySQL | AWS Lightsail Managed Database | 외부 managed MySQL |
| HTTPS Reverse Proxy | AWS Lightsail Ubuntu | Caddy |
| Frontend Next.js | Vercel | GitHub 연동 배포 |
| Prometheus/Grafana | 로컬 PC | Docker Compose |
| k6 | 로컬 PC | Docker Compose 또는 로컬 k6 |

운영 서버에는 Prometheus/Grafana/k6를 올리지 않는다. 2GB RAM 인스턴스라 backend와 Redis에만 집중한다.

## 1. Lightsail Ubuntu 서버 준비

### 1.1 서버 접속

```bash
ssh ubuntu@YOUR_LIGHTSAIL_PUBLIC_IP
```

### 1.2 패키지 업데이트

```bash
sudo apt update
sudo apt upgrade -y
sudo apt install -y git curl ca-certificates gnupg unzip htop
```

### 1.3 swap 추가

2GB RAM에서는 Docker build, OCR, JVM이 같이 움직이면 메모리가 빠듯할 수 있다. 2GB swap을 추가한다.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

## 2. Docker 설치

```bash
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker ubuntu
```

권한 반영을 위해 SSH를 다시 접속한다.

```bash
exit
ssh ubuntu@YOUR_LIGHTSAIL_PUBLIC_IP
docker version
docker compose version
```

## 3. Caddy 설치

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

Caddy 상태 확인:

```bash
sudo systemctl status caddy
```

## 4. DNS 준비

도메인을 가지고 있다면 다음처럼 설정한다.

| 레코드 | 값 |
|---|---|
| `A api.your-domain.com` | Lightsail public static IP |

Vercel 프론트는 나중에 `your-project.vercel.app` 또는 커스텀 도메인을 붙인다.

## 5. Managed MySQL 준비

AWS Lightsail Managed Database에서 MySQL을 만든 뒤 아래 값을 확보한다.

```text
endpoint
port
username
password
database name
```

DB 보안 설정에서는 Lightsail backend 서버가 MySQL에 접속할 수 있어야 한다.

DB 접속 확인은 서버에서 임시 MySQL client 컨테이너로 할 수 있다.

```bash
docker run --rm -it mysql:8 mysql \
  -h YOUR_MANAGED_MYSQL_ENDPOINT \
  -P 3306 \
  -u YOUR_DB_USER \
  -p
```

DB가 없다면 접속 후 생성한다.

```sql
CREATE DATABASE IF NOT EXISTS swimpulse
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

## 6. 코드 받기

```bash
cd ~
git clone https://github.com/YOUR_GITHUB_ID/YOUR_REPOSITORY.git SwimPulse
cd SwimPulse
```

이미 받았다면:

```bash
cd ~/SwimPulse
git pull
```

## 7. prod env 설정

예시 파일을 실제 env로 복사한다.

```bash
cp backend/.env.prod.example backend/.env.prod
nano backend/.env.prod
```

반드시 바꿀 값:

```env
SPRING_DATASOURCE_URL=jdbc:mysql://YOUR_MANAGED_MYSQL_ENDPOINT:3306/swimpulse?useSSL=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...

SWIMPULSE_CORS_ALLOWED_ORIGIN_PATTERNS=https://YOUR_VERCEL_PROJECT.vercel.app,http://localhost:3000

GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
SWIMPULSE_OAUTH2_REDIRECT_URI=https://api.your-domain.com/login/oauth2/code/google
SWIMPULSE_AUTH_SUCCESS_REDIRECT_URI=https://YOUR_VERCEL_PROJECT.vercel.app?login=success
SWIMPULSE_AUTH_FAILURE_REDIRECT_URI=https://YOUR_VERCEL_PROJECT.vercel.app?login=error

SWIMPULSE_JWT_SECRET=긴_랜덤_문자열
SWIMPULSE_AUTH_COOKIE_SECURE=true
SWIMPULSE_AUTH_COOKIE_SAME_SITE=None

NAVER_MAPS_CLIENT_ID=...
NAVER_MAPS_CLIENT_SECRET=...
NAVER_SEARCH_CLIENT_ID=...
NAVER_SEARCH_CLIENT_SECRET=...
```

주의: `SWIMPULSE_OAUTH2_REDIRECT_URI`는 Vercel 프론트 주소가 아니라 Spring backend의 Google callback 주소여야 한다.

```text
올바름: https://api.your-domain.com/login/oauth2/code/google
틀림:   https://YOUR_VERCEL_PROJECT.vercel.app/login/oauth2/code/google
```

랜덤 JWT secret 생성 예:

```bash
openssl rand -base64 48
```

### Firebase Admin SDK 파일

브라우저 푸시 발송을 쓰려면 Firebase Admin SDK json이 필요하다.

로컬 PC에서 서버로 복사:

```powershell
scp .\backend\swimpulse-f571e-firebase-adminsdk-fbsvc-f36e419e99.json ubuntu@13.124.45.61:~/SwimPulse/deploy/secrets/firebase-adminsdk.json
```

서버에서 확인:

```bash
ls -l deploy/secrets/firebase-adminsdk.json
```

아직 푸시 발송을 테스트하지 않을 거면 `backend/.env.prod`에서 임시로 아래처럼 둘 수 있다.

```env
SWIMPULSE_FIREBASE_MOCK=true
```

단, 현재 `docker-compose.prod.yml`은 `/run/secrets/firebase-adminsdk.json` bind mount를 사용하므로 파일 자체는 준비해두는 편이 안전하다.

## 8. Caddy 설정

`api.your-domain.com`을 실제 API 도메인으로 바꾼다.

```bash
sudo tee /etc/caddy/Caddyfile >/dev/null <<'EOF'
api.your-domain.com {
	encode zstd gzip

	# Actuator는 공개하지 않고, 로컬 Prometheus는 SSH tunnel로 붙인다.
	respond /actuator/* 404

	reverse_proxy 127.0.0.1:8080
}
EOF
```

Caddy reload:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
sudo systemctl status caddy
```

## 9. Backend + Redis 배포

서버에서 실행:

```bash
cd ~/SwimPulse
docker compose -f docker-compose.prod.yml up -d --build
```

상태 확인:

```bash
docker compose -f docker-compose.prod.yml ps
docker logs -f swimpulse-backend
```

서버 내부 health 확인:

```bash
curl http://127.0.0.1:8080/actuator/health
```

외부 API 확인:

```bash
curl https://api.your-domain.com/api/pools
```

## 10. Google OAuth 설정

Google Cloud Console에서 OAuth Client의 Authorized redirect URI에 추가한다.

```text
https://api.your-domain.com/login/oauth2/code/google
```

Vercel default domain을 쓰면 Authorized JavaScript origins에는 보통 프론트 도메인을 추가한다.

```text
https://YOUR_VERCEL_PROJECT.vercel.app
```

OAuth redirect URI와 `SWIMPULSE_OAUTH2_REDIRECT_URI` 값이 정확히 같아야 한다.

## 11. Vercel 프론트 배포

### 11.1 Vercel에서 프로젝트 만들기

1. Vercel에 GitHub 계정으로 로그인한다.
2. `Add New...` → `Project`를 누른다.
3. SwimPulse GitHub repository를 import한다.
4. Root Directory를 `frontend`로 지정한다.
5. Framework Preset은 Next.js로 인식되는지 확인한다.
6. Build Command는 기본값 또는 `npm run build`를 사용한다.
7. Install Command는 기본값을 사용한다.

### 11.2 Vercel 환경변수

Vercel Project Settings → Environment Variables에서 추가한다.

| 변수 | 값 |
|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `https://api.your-domain.com` |
| `BACKEND_INTERNAL_API_BASE_URL` | `https://api.your-domain.com` |
| `NEXT_PUBLIC_FIREBASE_API_KEY` | Firebase web config |
| `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` | Firebase web config |
| `NEXT_PUBLIC_FIREBASE_PROJECT_ID` | Firebase web config |
| `NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID` | Firebase web config |
| `NEXT_PUBLIC_FIREBASE_APP_ID` | Firebase web config |
| `NEXT_PUBLIC_FIREBASE_VAPID_KEY` | Firebase Cloud Messaging web push key |

주의:

```text
NEXT_PUBLIC_* 값은 브라우저 번들에 포함된다.
비밀키를 NEXT_PUBLIC_*에 넣으면 안 된다.
```

### 11.3 첫 배포 후 backend env 수정

Vercel 배포가 끝나면 실제 프론트 URL을 확인한다.

예:

```text
https://swimpulse.vercel.app
```

서버의 `backend/.env.prod`에 반영한다.

```env
SWIMPULSE_CORS_ALLOWED_ORIGIN_PATTERNS=https://swimpulse.vercel.app,http://localhost:3000
SWIMPULSE_AUTH_SUCCESS_REDIRECT_URI=https://swimpulse.vercel.app?login=success
SWIMPULSE_AUTH_FAILURE_REDIRECT_URI=https://swimpulse.vercel.app?login=error
```

backend 재시작:

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

### 11.4 프론트 재배포

Vercel은 GitHub에 push하면 자동으로 배포한다.

수동으로 다시 배포하려면 Vercel Dashboard → Deployments → Redeploy를 누른다.

## 12. 로컬 Prometheus/Grafana로 원격 backend 관측

운영 서버의 `/actuator/*`는 Caddy에서 공개 차단한다. 대신 SSH tunnel로 로컬 PC에 연결한다.

로컬 PC PowerShell:

```powershell
ssh -N -L 18080:127.0.0.1:8080 ubuntu@YOUR_LIGHTSAIL_PUBLIC_IP
```

다른 PowerShell 창에서:

```powershell
docker compose -f docker-compose.observability.yml up -d prometheus grafana
```

접속:

```text
Prometheus: http://localhost:9090
Grafana:    http://localhost:3001
```

Prometheus target은 `host.docker.internal:18080`을 scrape한다.

확인:

```powershell
curl http://localhost:18080/actuator/health
curl http://localhost:18080/actuator/prometheus
```

## 13. 로컬 k6로 원격 backend 부하 테스트

SSH tunnel이 켜져 있는 상태에서 실행한다.

예: nearby API

```powershell
docker compose -f docker-compose.observability.yml --profile loadtest run --rm `
  -e BASE_URL=http://host.docker.internal:18080 `
  -e VUS=5 `
  -e DURATION=1m `
  -e LATITUDE=37.5665 `
  -e LONGITUDE=126.9780 `
  -e LIMIT=20 `
  k6 run /scripts/nearby-load.js `
  --summary-export /results/prod-nearby-summary.json
```

예: 공지 스캔

```powershell
docker compose -f docker-compose.observability.yml --profile loadtest run --rm `
  -e BASE_URL=http://host.docker.internal:18080 `
  -e VUS=2 `
  -e DURATION=1m `
  -e POOL_IDS=10,13,16,22,23 `
  k6 run /scripts/notice-scan-ocr-load.js `
  --summary-export /results/prod-notice-scan-summary.json
```

2GB 운영 서버에 직접 부하를 줄 때는 처음에는 VUS를 낮게 시작한다.

권장 시작점:

```text
VUS=2~5
duration=1m
```

## 14. 자주 쓰는 운영 명령어

### 재배포

```bash
cd ~/SwimPulse
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

### 로그 보기

```bash
docker logs -f swimpulse-backend
docker logs -f swimpulse-redis
```

### Redis 확인

```bash
docker exec -it swimpulse-redis redis-cli INFO memory
docker exec -it swimpulse-redis redis-cli LLEN swimpulse:notifications
```

### 컨테이너 재시작

```bash
docker compose -f docker-compose.prod.yml restart backend
```

### 디스크 확인

```bash
df -h
docker system df
```

불필요한 빌드 캐시 정리:

```bash
docker builder prune
```

## 15. 배포 체크리스트

| 항목 | 확인 |
|---|---|
| Lightsail static IP 연결 | `A api.your-domain.com` |
| Managed MySQL 접속 | 서버에서 mysql client로 접속 성공 |
| `backend/.env.prod` 작성 | secrets 포함, git에 올리지 않음 |
| Firebase Admin SDK | `deploy/secrets/firebase-adminsdk.json` 존재 |
| Caddy HTTPS | `curl https://api.your-domain.com/api/pools` 성공 |
| Google OAuth redirect URI | `https://api.your-domain.com/login/oauth2/code/google` 등록 |
| Vercel env | `NEXT_PUBLIC_API_BASE_URL`, Firebase public env 입력 |
| Backend CORS | Vercel URL 포함 |
| Cookie 설정 | cross-site면 `Secure=true`, `SameSite=None` |
| 로컬 관측 | SSH tunnel + Prometheus target UP |
| k6 | 낮은 VUS부터 실행 |

## 참고 링크

- Vercel Next.js 배포: https://vercel.com/docs/frameworks/nextjs
- Vercel Environment Variables: https://vercel.com/docs/projects/environment-variables
- Caddy reverse proxy: https://caddyserver.com/docs/quick-starts/reverse-proxy
- Docker install: https://docs.docker.com/engine/install/ubuntu/
