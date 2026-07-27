# 063 SwimPulse Notion용 간소화 시퀀스 다이어그램

작성일: 2026-07-22

## 목적

기존 상세 시퀀스 다이어그램은 구현 검토 문서로는 유용하지만, Notion 포트폴리오에서는 participant와 메시지가 너무 많아 글자가 작아지는 문제가 있다.

이 문서는 다음 원칙으로 포트폴리오용 다이어그램을 다시 구성한다.

- 다이어그램 하나당 participant를 최대 7개로 제한한다.
- Controller, Security Filter, Service, Repository 같은 내부 계층을 각각 분리하지 않는다.
- Caddy, Spring Security, Keychain 같은 세부 구현은 메시지나 핵심 설명에 표시한다.
- 정상 흐름과 핵심 예외만 남기고 세부 fallback은 보고서 링크로 분리한다.
- `autonumber`를 제거해 작은 화면의 시각적 밀도를 낮춘다.

---

## 1. 웹 로그인 및 인증 API 요청

```mermaid
sequenceDiagram
    actor User as 사용자
    participant Web as Web
    participant API as Spring Boot
    participant Google as Google OAuth
    participant DB as MySQL

    User->>Web: Google 로그인 선택
    Web->>API: OAuth 로그인 요청
    API->>Google: 사용자 인증 요청
    Google-->>API: 인증 결과 전달
    API->>DB: 사용자 조회 또는 생성
    DB-->>API: 사용자 정보
    API-->>Web: JWT Cookie 발급 후 Redirect

    User->>Web: 인증 화면 또는 기능 요청
    Web->>API: JWT Cookie와 API 요청
    API->>DB: 사용자 데이터 조회
    DB-->>API: 조회 결과
    API-->>Web: JSON 응답

    Note over Web,API: Web은 HttpOnly Secure SameSite None Cookie 사용
```

### 핵심 설명

- Vercel에서 제공된 Web 화면이 HTTPS API를 호출한다.
- Spring Security가 공개 API와 인증 API를 구분하고 JWT Cookie를 검증한다.
- 브라우저 JavaScript가 JWT 값을 직접 읽지 못하도록 `HttpOnly` Cookie를 사용한다.
- Caddy는 앞단에서 HTTPS 종료와 Spring Boot reverse proxy를 담당한다.

---

## 2. 모바일 로그인과 FCM 기기 등록

```mermaid
sequenceDiagram
    actor User as 사용자
    participant App as Android App
    participant Google as Google Sign In
    participant API as Spring Boot
    participant DB as MySQL
    participant FCM as Firebase FCM

    User->>App: Google 로그인 선택
    App->>Google: Google ID Token 요청
    Google-->>App: ID Token 발급
    App->>API: ID Token 전달
    API->>Google: 서명과 발급 정보 검증
    API->>DB: 사용자 조회 또는 생성
    DB-->>API: 사용자 정보
    API-->>App: SwimPulse JWT 발급
    App->>App: JWT를 Secure Storage에 저장

    App->>FCM: Android Device Token 요청
    FCM-->>App: FCM Token 발급
    App->>API: Bearer JWT와 FCM Token 등록
    API->>DB: ANDROID 기기 저장
    API-->>App: 기기 등록 완료
```

### 핵심 설명

- Android OAuth Client는 package name과 SHA-1로 설치 앱을 식별한다.
- 백엔드는 Google ID Token을 검증한 뒤 자체 SwimPulse JWT를 발급한다.
- 모바일 API는 Cookie 대신 `Authorization: Bearer <JWT>`를 사용한다.
- JWT는 React Native Keychain을 통해 Android Keystore 영역에 저장한다.
- FCM Token은 로그인 JWT와 다른 값이며 설치된 기기를 식별하기 위해 `user_devices`에 저장한다.

---

## 3. 공지 확인과 백그라운드 OCR

```mermaid
sequenceDiagram
    actor User as 사용자
    participant Client as Web 또는 Android
    participant API as Spring Boot
    participant Site as 공공기관 사이트
    participant DB as MySQL
    participant Redis as Redis Lock과 Queue
    participant Worker as OCR Worker

    User->>Client: 공지 확인
    Client->>API: 수영장 공지 스캔 요청
    API->>Redis: 동일 수영장 single flight 확인
    API->>DB: 저장된 공지 경로 조회
    API->>Site: 공지 목록과 상세 페이지 탐색
    Site-->>API: HTML과 이미지 정보
    API->>DB: 공지와 HTML 기간 저장
    API-->>Client: 먼저 확인된 모집 기간 응답

    opt 이미지 공지 존재
        API->>DB: OCR PENDING 저장
        API->>Redis: DB commit 후 OCR 작업 등록
        Worker->>Redis: OCR 작업 가져오기
        Worker->>Site: 공지 이미지 다운로드
        Worker->>Worker: Tesseract OCR 실행
        Worker->>DB: OCR 기간과 처리 상태 저장
        Client->>API: 처리 결과 재조회
        API->>DB: 보강된 기간 조회
        API-->>Client: OCR 반영 결과 응답
    end
```

### 핵심 설명

- Redis single-flight로 같은 수영장의 동시 스캔을 하나만 실행한다.
- HTML에서 찾은 기간은 사용자에게 먼저 응답한다.
- 무거운 Tesseract OCR은 요청 thread가 아닌 백그라운드 Worker에서 처리한다.
- 공지 row가 commit된 뒤 OCR 작업을 Queue에 넣어 아직 저장되지 않은 공지를 Worker가 읽는 문제를 막는다.
- 이 구조로 공지 확인 p99를 `13.37s`에서 `406.07ms`로 개선했다.

---

## 4. 기간 구독과 FCM 알림 발송

```mermaid
sequenceDiagram
    actor User as 사용자
    participant Client as Web 또는 Android
    participant Backend as API와 Scheduler
    participant DB as MySQL
    participant Queue as Redis Queue
    participant Worker as Notification Worker
    participant FCM as Firebase FCM

    User->>Client: 모집 기간 구독
    Client->>Backend: 구독 생성 요청
    Backend->>DB: Event 재사용 또는 생성 후 구독 저장
    DB-->>Backend: 구독 결과
    Backend-->>Client: 구독 완료

    Note over Backend,DB: Scheduler가 접수 전과 시작 대상 감지
    Backend->>DB: Notification을 QUEUED로 저장
    Backend->>Queue: DB commit 후 notificationId 등록
    Worker->>Queue: notificationId 가져오기
    Worker->>DB: SENDING 전환과 활성 기기 조회
    DB-->>Worker: WEB과 ANDROID Token
    Worker->>FCM: 실제 접수 시작 시각과 알림 발송
    FCM-->>Client: Web 또는 Android Push
    Worker->>DB: SENT 또는 FAILED 저장

    User->>Client: 수신 알림 선택
    Client->>Backend: 알림 상세와 읽음 처리
    Backend->>DB: 사용자 소유 확인과 readAt 저장
    Backend-->>Client: 대상 구독 정보
    Client->>Client: 마이페이지 이동과 구독 강조
```

### 핵심 설명

- 공식 모집 기간은 여러 사용자가 같은 `registration_events` row를 재사용한다.
- Notification row를 source of truth로 두고 Redis에는 commit 이후 ID만 전달한다.
- dedupe key와 Unique Constraint로 Scheduler 반복 실행과 동시 요청의 중복 생성을 방지한다.
- Worker는 사용자에게 활성화된 WEB과 ANDROID Token 전체로 fan-out한다.
- 오래 `SENDING`에 머문 알림은 stale requeue로 다시 처리한다.
- Worker batch와 delay를 조정해 500명 fan-out 완료 시간을 `32.85s`에서 `10.10s`로 개선했다.

---

## Notion 배치 권장안

### 본문에 바로 노출

1. `공지 확인과 백그라운드 OCR`
2. `기간 구독과 FCM 알림 발송`

두 다이어그램은 SwimPulse의 핵심인 비정형 데이터 처리, 비동기 Worker, 데이터 정합성, 알림 복구 구조를 가장 잘 보여준다.

### 접기 영역에 배치

1. `웹 로그인 및 인증 API 요청`
2. `모바일 로그인과 FCM 기기 등록`

인증 흐름은 중요하지만 프로젝트의 차별점보다는 지원 기능에 가깝다. Notion의 Toggle 안에 넣으면 본문 길이를 줄이면서 필요한 독자는 상세 내용을 확인할 수 있다.

### Notion 표시 설정

- 페이지를 `Full width`로 설정한다.
- Mermaid 블록을 2단 Column 안에 넣지 않는다.
- 다이어그램 하나와 핵심 설명을 한 묶음으로 배치한다.
- 상세 클래스·메서드 단위 흐름은 기존 `062` 보고서 또는 GitHub 문서 링크로 제공한다.

## 최종 판단

포트폴리오에서는 모든 구현 단계를 한 장에 담는 것보다, 핵심 설계 판단이 읽히는 수준까지만 보여주는 것이 좋다.

```text
Notion 포트폴리오: 간소화 다이어그램
GitHub 및 기술 보고서: 기존 상세 다이어그램
면접 설명: 상세 다이어그램을 근거로 예외·복구 흐름 설명
```
