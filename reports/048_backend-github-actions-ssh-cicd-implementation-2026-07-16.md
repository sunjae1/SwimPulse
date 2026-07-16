# 048 Backend GitHub Actions SSH CI/CD 구현 보고서

작성일: 2026-07-16

## 목적

SwimPulse 프론트엔드는 Vercel에 연결되어 GitHub push만으로 자동 배포된다. 반면 백엔드는 Lightsail Ubuntu 서버에 SSH로 접속해 직접 `git pull`과 `docker compose up -d --build`를 실행해야 했다.

이번 작업의 목표는 다음이다.

```text
GitHub push
  -> GitHub Actions
  -> backend 전체 테스트
  -> Lightsail SSH 접속
  -> git pull
  -> docker compose 재배포
  -> healthcheck
```

## 구현 내용

### 1. GitHub Actions SSH 배포 workflow 추가

추가 파일:

```text
.github/workflows/backend-deploy.yml
```

동작:

```text
workflow_dispatch 수동 실행
main branch push
  paths:
    backend/**
    docker-compose.prod.yml
    .github/workflows/backend-deploy.yml
```

CI gate:

```bash
cd backend
chmod +x ./gradlew
./gradlew test
```

배포 명령:

```bash
echo "SSH 접속 완료."
cd /home/ubuntu/SwimPulse
echo "운영 서버 코드 갱신 중..."
git pull --ff-only
echo "운영 서버 코드 갱신 완료."
echo "컨테이너 이미지 빌드 및 배포 중..."
docker compose -f docker-compose.prod.yml up -d --build --remove-orphans
echo "컨테이너 이미지 배포 완료."
echo "사용하지 않는 Docker 이미지 정리 중..."
docker image prune -f
echo "Docker 이미지 정리 완료."
echo "백엔드 컨테이너 healthcheck 대기 중..."
for attempt in $(seq 1 36); do
  if curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health; then
    echo "백엔드 healthcheck 성공."
    break
  fi
  if [ "$attempt" -eq 36 ]; then
    echo "백엔드 healthcheck 실패. 최근 로그를 출력합니다."
    docker logs --tail=200 swimpulse-backend
    exit 1
  fi
  echo "백엔드 시작 대기 중... ($attempt/36)"
  sleep 5
done
echo "컨테이너 상태 확인 중..."
docker ps --filter "name=swimpulse-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
echo "Lightsail 배포 완료."
```

배포 job은 test job이 성공해야만 실행된다.

`docker compose up -d`는 컨테이너 시작 명령까지만 보장하고 Spring Boot 준비 완료를 보장하지 않는다. 그래서 `/actuator/health`는 즉시 1회 호출하지 않고 최대 180초 동안 재시도한다.

### 2. GitHub Secrets

GitHub repository에서 다음 secrets를 등록해야 한다.

위치:

```text
GitHub Repository
-> Settings
-> Secrets and variables
-> Actions
-> New repository secret
```

| Secret | 설명 |
|---|---|
| `LIGHTSAIL_HOST` | Lightsail public IP 또는 SSH 가능한 도메인 |
| `LIGHTSAIL_USER` | 보통 `ubuntu` |
| `LIGHTSAIL_SSH_KEY` | Lightsail 서버에 접속 가능한 private key 전체 내용 |

서버에는 해당 private key와 짝이 되는 public key가 등록되어 있어야 한다.

```bash
mkdir -p ~/.ssh
nano ~/.ssh/authorized_keys
chmod 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
```

### 3. 전체 테스트 실패 수정

기존 전체 테스트 실패 원인:

```text
NoticeCrawlerServiceTests > detailNoticeCandidatesSupportFnViewOnclickLinks
NoticeCrawlerServiceTests > detailNoticeCandidatesPreferRealContentAreaOverGenericPopupContent
NoticeCrawlerServiceTests > imageOcrRetryNormalizesDuplicateRangeFragmentsAndSuppressesMonthlyFalsePositive
```

수정 내용:

- `fn_view(6593)`처럼 `href="#none"`과 `onclick`으로 상세 페이지를 여는 게시판 링크도 detail 후보로 인정되도록 월 키워드 판정을 현재/다음 달 한정에서 일반 월 표기로 확장했다.
- popup/slide 영역보다 실제 board/content 영역을 우선하는 기존 scope 선택은 유지했다.
- OCR 텍스트에서 `매월 27일 접수`처럼 모집 일정 설명문에 가까운 monthly single-day false positive가 실제 명시 모집 기간과 함께 잡힐 때 제거되도록 정규화 조건을 보강했다.
- 월별 단일일이 실제 기간인 경우를 과하게 제거하지 않도록, 같은 계열 label의 명시 기간이 있을 때만 suppress하도록 제한했다.

## 운영 사용 순서

### 1. 서버 준비 확인

Lightsail 서버에는 이미 다음이 있어야 한다.

```text
/home/ubuntu/SwimPulse
backend/.env.prod
deploy/secrets/firebase-adminsdk.json
Docker
Docker Compose plugin
Caddy
```

서버에서 수동으로 한 번 확인:

```bash
cd /home/ubuntu/SwimPulse
git status
docker compose -f docker-compose.prod.yml ps
curl -fsS http://127.0.0.1:8080/actuator/health
```

### 2. GitHub Secrets 등록

`LIGHTSAIL_HOST`, `LIGHTSAIL_USER`, `LIGHTSAIL_SSH_KEY`를 등록한다.

주의:

- `LIGHTSAIL_SSH_KEY`는 public key가 아니라 private key다.
- key 전체를 줄바꿈 포함해서 넣는다.
- 서버의 `authorized_keys`에는 대응되는 public key가 있어야 한다.

### 3. 첫 실행은 수동 실행 권장

GitHub Actions에서:

```text
Actions
-> Backend Deploy
-> Run workflow
```

처음에는 수동 실행으로 SSH 접속, `git pull`, Docker build, healthcheck까지 확인한다.

### 4. 이후 main push 자동 배포

다음 파일들이 바뀌어 `main`에 push되면 자동으로 실행된다.

```text
backend/**
docker-compose.prod.yml
.github/workflows/backend-deploy.yml
```

프론트만 바뀐 push는 Vercel이 처리하고, backend deploy workflow는 실행되지 않는다.

## 검증 결과

통과:

```text
backend ./gradlew.bat test --tests com.swimpulse.notice.NoticeCrawlerServiceTests
backend ./gradlew.bat test
frontend npm run lint
frontend npm run build
mobile npm run lint
mobile npx tsc --noEmit
```

이전에는 전체 백엔드 테스트가 3개 실패로 막혔지만, 이제 `./gradlew test`가 통과하므로 GitHub Actions CD gate로 사용할 수 있다.

## 남은 운영 확인

로컬에서는 workflow 파일 작성과 전체 테스트 통과까지 확인했다. 실제 GitHub Actions 실행은 GitHub secrets 등록 후 GitHub 원격에서 확인해야 한다.

운영 첫 실행 후 확인할 것:

```bash
docker ps
docker logs --tail=200 swimpulse-backend
curl -fsS http://127.0.0.1:8080/actuator/health
```

외부에서는 Caddy 뒤 API 도메인으로 확인한다.

```text
https://api.sunjae.link/actuator/health
```

## 판단

이번 단계는 GHCR image 배포가 아니라 SSH 기반 배포다.

이유:

1. 현재 Lightsail 서버에 repo와 compose 구조가 이미 준비되어 있다.
2. 기존 수동 배포 명령을 가장 적은 변경으로 자동화할 수 있다.
3. 전체 테스트가 통과해야 배포되므로, 수동 배포보다 안정성이 높다.

다음 개선 단계는 Lightsail 서버에서 build하지 않고 GitHub Actions에서 Docker image를 만들고 GHCR에 push한 뒤, 서버는 image만 pull하는 구조다.
