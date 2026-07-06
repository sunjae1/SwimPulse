# EC2, Lightsail, 결제, 모바일 확장 검토 보고서

작성일: 2026-06-25  
대상 프로젝트: SwimPulse  
목적: AWS 배포 선택지, 결제 기능 추가 방향, iOS/Android 앱 확장 방향을 한 번에 판단하기 위한 정리

## 1. EC2와 Lightsail 차이

### 한 줄 요약

| 구분 | EC2 | Lightsail |
|---|---|---|
| 성격 | AWS의 기본 가상 서버 | 쉽게 쓰는 VPS 패키지 |
| 자유도 | 높음 | 낮음 |
| 비용 예측 | 세부 항목별 과금이라 처음엔 어렵다 | 월 정액에 가깝다 |
| 확장성 | VPC, ALB, Auto Scaling, RDS, ECS 등과 자연스럽게 연결 | 작은 서비스에는 편하지만 복잡한 구조로 갈수록 한계 |
| 운영 난이도 | 높음 | 낮음 |
| 포트폴리오 어필 | 인프라 설계 역량을 보여주기 좋음 | 빠른 배포 경험을 보여주기 좋음 |

### EC2란?

EC2는 AWS에서 가장 기본이 되는 가상 서버다. 직접 인스턴스 타입, 디스크, 네트워크, 보안 그룹, 로드밸런서, 오토스케일링 등을 조합한다.

```text
EC2 = 직접 조립하는 서버
```

장점:

1. 원하는 구조로 확장하기 좋다.
2. RDS, ElastiCache, ALB, ECS, CloudWatch와 자연스럽게 연결된다.
3. 포트폴리오에서 VPC, 보안 그룹, 배포 자동화, 관측 구조를 설명하기 좋다.

단점:

1. 처음 설정할 것이 많다.
2. 인스턴스 비용 외에 public IPv4, EBS, 데이터 전송, 로그 비용이 따로 붙는다.
3. 서버 패치, Docker, 배포, HTTPS 설정을 직접 관리해야 한다.

### Lightsail이란?

Lightsail은 AWS가 EC2, 디스크, 네트워크 전송량을 묶어서 쉽게 제공하는 VPS 서비스다. “AWS판 간단 호스팅 서버”에 가깝다.

```text
Lightsail = 월 정액 느낌의 간편 VPS
```

장점:

1. 월 비용이 이해하기 쉽다.
2. 서버, 디스크, 전송량이 번들에 포함된다.
3. 초보자가 배포하기 쉽다.
4. 작은 프로젝트나 포트폴리오 데모를 빠르게 올리기 좋다.

단점:

1. EC2/VPC 기반의 정교한 아키텍처로 확장하기엔 제약이 있다.
2. ALB, ECS, ElastiCache 같은 구조로 발전시키려면 결국 일반 AWS 구성으로 넘어가는 편이 자연스럽다.
3. 고급 네트워크/보안/오토스케일링 경험을 보여주기엔 EC2/ECS보다 약하다.

## 2. 비용 비교

### Lightsail 비용

AWS 공식 Lightsail Linux/Unix public IPv4 번들 기준으로 주요 플랜은 다음이다.

| 플랜 | vCPU | 메모리 | SSD | 전송량 | 월 비용 |
|---|---:|---:|---:|---:|---:|
| Small | 2 | 2GB | 60GB | 3TB | $12 |
| Medium | 2 | 4GB | 80GB | 4TB | $24 |
| Large | 2 | 8GB | 160GB | 5TB | $44 |

SwimPulse는 Java/Spring Boot, Next.js, Redis, OCR까지 같이 돌릴 수 있어서 최소 2GB보다는 4GB 플랜이 현실적이다.

```text
추천 Lightsail 시작점:
Medium 4GB = 약 $24/month
```

단, MySQL까지 같은 Lightsail 인스턴스 안에 넣으면 저렴하지만 운영 안정성은 떨어진다. Lightsail managed database를 쓰면 별도 비용이 붙고, AWS 문서 기준 managed database는 $15/month부터 시작한다.

예상:

| 구조 | 월 비용 |
|---|---:|
| Lightsail 4GB 한 대에 app + MySQL + Redis | 약 $24 |
| Lightsail 4GB + Lightsail managed DB 최소 플랜 | 약 $39 |
| Lightsail 8GB + managed DB | 약 $59 |

### EC2 비용

서울 리전 온디맨드 기준으로 이전 AWS Price List 확인값은 다음이었다.

| 항목 | 단가 |
|---|---:|
| EC2 `t4g.small` Linux | $0.0208/hour |
| EC2 `t4g.medium` Linux | $0.0416/hour |
| Public IPv4 | $0.005/hour |
| EBS gp3 | $0.0912/GB-month |
| RDS MySQL `db.t4g.micro` | $0.025/hour |
| RDS gp3 | $0.131/GB-month |

SwimPulse를 EC2 + RDS로 올리면 대략 다음이다.

| 항목 | 계산 | 월 비용 |
|---|---:|---:|
| EC2 `t4g.medium` | $0.0416 * 730h | $30.37 |
| Public IPv4 | $0.005 * 730h | $3.65 |
| EBS gp3 30GB | $0.0912 * 30GB | $2.74 |
| RDS `db.t4g.micro` | $0.025 * 730h | $18.25 |
| RDS gp3 20GB | $0.131 * 20GB | $2.62 |
| Route 53 | 1 hosted zone | $0.50 |
| CloudWatch Logs | 2GB ingest 가정 | 약 $1.52 |
| 합계 |  | **약 $59.65/month** |

### 둘 중 뭘 추천하나?

처음 실제 배포만 목적이면 Lightsail도 괜찮다.

```text
최소 비용 데모:
Lightsail 4GB 한 대
```

하지만 포트폴리오와 운영 설계까지 생각하면 EC2 또는 ECS 쪽이 더 좋다.

```text
포트폴리오 추천:
EC2 + RDS
또는
ECS Fargate + RDS + ElastiCache
```

내 추천은 다음이다.

1. 빠르게 공개 URL을 만들고 싶으면 Lightsail 4GB.
2. 백엔드 포트폴리오에서 인프라 설계를 보여주고 싶으면 EC2 + RDS.
3. 완성형 구조를 문서와 면접에서 보여주고 싶으면 ECS + RDS + ElastiCache.

## 3. SwimPulse에서 결제 기능을 넣는다면

먼저 “무엇을 결제하게 할 것인가”를 나눠야 한다.

### 결제 모델 후보

| 모델 | 설명 | 추천도 |
|---|---|---:|
| 프리미엄 구독 | 알림 개수 제한 해제, 빠른 OCR, 관심 지역 알림, 광고 제거 | 높음 |
| 시설 관리자 플랜 | 수영장/시설 담당자가 공지 관리, 노출 강화, 분석 대시보드 사용 | 중간 |
| 실제 수강료 결제 | 사용자가 SwimPulse에서 수영장 수강료를 결제 | 낮음 |
| 후원/기부 | 서비스 유지 후원 | 중간 |

현재 SwimPulse에는 “수영장 접수 알림”이라는 핵심 가치가 있으므로, 가장 자연스러운 유료화는 사용자 프리미엄 구독이다.

예시:

```text
무료:
  - 구독 5개
  - 기본 알림
  - 수동 공지 확인

프리미엄:
  - 구독 무제한
  - 관심 지역 자동 감지
  - OCR 완료 알림
  - 모집 시작 전 다중 리마인더
  - 우선 공지 스캔
```

### 웹 결제 구조

한국 서비스라면 Toss Payments 또는 PortOne 같은 PG를 붙이는 흐름이 현실적이다.

웹 결제 흐름:

```text
1. 사용자가 요금제 선택
2. frontend가 backend에 주문 생성 요청
3. backend가 payments row 생성: PENDING
4. frontend가 PG 결제 위젯 실행
5. 결제 인증 성공 후 successUrl로 돌아옴
6. frontend가 paymentKey/orderId/amount를 backend에 전달
7. backend가 PG 결제 승인 API 호출
8. 승인 성공 시 payments=PAID
9. user_entitlements 또는 subscriptions_plan 활성화
10. PG webhook으로 최종 상태 보정
```

Toss Payments 기준으로는 결제 승인 API가 있고, 결제 인증 후 일정 시간 안에 서버가 승인 API를 호출해야 한다. PortOne도 webhook을 통해 결제 상태를 서버에 동기화하는 구조를 제공한다.

### 필요한 DB 테이블

```text
plans
- id
- code
- name
- price
- billing_cycle
- max_subscriptions
- features_json

orders
- id
- user_id
- plan_id
- order_id
- amount
- status
- created_at

payments
- id
- order_id
- provider
- provider_payment_key
- amount
- status
- approved_at
- failure_reason
- raw_response_json

user_entitlements
- id
- user_id
- plan_id
- starts_at
- ends_at
- status

payment_webhook_events
- id
- provider
- event_id
- event_type
- payload_json
- processed_at
```

중요한 포인트:

1. 클라이언트 결제 성공만 믿으면 안 된다.
2. 결제 금액은 반드시 서버가 검증한다.
3. webhook은 중복으로 올 수 있으므로 idempotent하게 처리한다.
4. `order_id`, `payment_key`, `event_id`에 unique 제약을 둔다.
5. 환불/취소/구독 만료까지 상태 전이를 설계한다.

### 앱 결제 주의점

모바일 앱에서 “디지털 프리미엄 기능”을 판매하면 iOS/Android 스토어 정책이 중요하다.

일반적으로:

| 판매 대상 | 결제 방식 |
|---|---|
| 앱 안에서 쓰는 디지털 기능/구독 | Apple In-App Purchase, Google Play Billing 고려 필요 |
| 오프라인 시설 이용권, 실제 물리 서비스 | 외부 PG 가능성이 높음 |
| 웹에서만 결제하고 앱에서 로그인 후 이용 | 스토어 정책 검토 필요 |

SwimPulse 프리미엄이 “앱 안에서 쓰는 알림 기능”이면 앱 출시 시 Apple/Google 인앱결제를 고려해야 한다. 웹에서는 Toss/PortOne을 쓰고, 앱에서는 StoreKit/Google Play Billing으로 결제한 뒤 서버에서 영수증/구매 토큰을 검증하는 구조가 안전하다.

## 4. iOS/Android로 확장할 때 FCM은 그대로 쓸 수 있나?

결론부터 말하면, 현재 백엔드의 FCM 발송 구조는 그대로 확장 가능하다.

현재 구조:

```text
Spring Boot
  -> Firebase Admin SDK
  -> FCM token
  -> Web Push
```

모바일 확장 후:

```text
Spring Boot
  -> Firebase Admin SDK
  -> Web FCM token
  -> Android FCM token
  -> iOS FCM token
```

즉, 서버는 “토큰에게 메시지를 보낸다”는 점에서는 같다. 다만 앱에서 토큰을 발급받고 서버에 등록하는 방식이 달라진다.

### 추가로 필요한 것

| 플랫폼 | 필요한 것 |
|---|---|
| Android | Firebase Android app 등록, `google-services.json`, FCM SDK |
| iOS | Firebase Apple app 등록, `GoogleService-Info.plist`, Apple Developer Account, APNs key/certificate |
| Backend | `user_devices`에 platform, appVersion, deviceId, lastSeenAt 추가 권장 |

iOS는 FCM이 내부적으로 APNs와 연동된다. 그래서 Firebase Console에 APNs 인증 정보를 등록해야 한다. iOS 푸시 테스트는 시뮬레이터보다 실제 기기로 보는 것이 안전하다.

### DB 변경 추천

현재 `user_devices`가 있다면 다음 정보를 추가하는 편이 좋다.

```text
user_devices
- id
- user_id
- fcm_token
- platform: WEB / ANDROID / IOS
- device_id
- app_version
- user_agent
- active
- last_seen_at
- created_at
- updated_at
```

이렇게 하면 한 계정이 여러 기기에서 로그인해도 모든 기기에 보낼 수 있다.

```text
notification 1건
  -> user_id의 active device token 조회
  -> web token 전송
  -> android token 전송
  -> ios token 전송
  -> 마이페이지 알림 row는 1건만 유지
```

마이페이지 알림은 사용자 이벤트 기준이고, 기기 토큰 수만큼 row가 늘어나면 안 된다.

## 5. 모바일 개발은 뭘로 해야 하나?

선택지는 크게 세 가지다.

### 선택지 A: Android/iOS 네이티브

| 플랫폼 | 언어 | IDE |
|---|---|---|
| Android | Kotlin | Android Studio |
| iOS | Swift / SwiftUI | Xcode |

장점:

1. 각 플랫폼 기능을 가장 정확하게 쓸 수 있다.
2. 푸시, 권한, 백그라운드 동작, 앱스토어 배포를 정석으로 배운다.
3. 모바일 개발 경험을 제대로 쌓기 좋다.

단점:

1. Android와 iOS를 따로 만들어야 한다.
2. Mac이 없으면 iOS 개발/빌드/배포가 어렵다.
3. 학습량이 가장 많다.

추천 상황:

```text
모바일을 제대로 배워보고 싶다.
각 플랫폼 차이를 알고 싶다.
장기적으로 앱 개발자 역량도 갖추고 싶다.
```

### 선택지 B: React Native

언어는 JavaScript/TypeScript이고, React 사고방식을 그대로 가져갈 수 있다.

장점:

1. 현재 Next.js/React 경험을 재사용하기 좋다.
2. iOS/Android를 한 코드베이스로 만들 수 있다.
3. FCM도 React Native Firebase로 연결 가능하다.

단점:

1. 네이티브 설정에서 막힐 수 있다.
2. 푸시, 권한, 빌드 설정은 결국 Android/iOS 지식이 필요하다.
3. iOS 배포에는 여전히 Mac/Xcode가 필요하다.

추천 상황:

```text
현재 프로젝트와 이어서 빠르게 모바일 MVP를 만들고 싶다.
React/TypeScript를 계속 쓰고 싶다.
```

### 선택지 C: Flutter

언어는 Dart이고, UI를 한 코드베이스로 만든다.

장점:

1. UI 일관성이 좋다.
2. iOS/Android 동시 개발이 편하다.
3. Firebase/FCM 지원이 좋다.

단점:

1. Dart와 Flutter 프레임워크를 새로 배워야 한다.
2. 기존 React 코드 재사용은 어렵다.

추천 상황:

```text
크로스플랫폼 앱을 새로 배울 의향이 있다.
UI를 앱답게 빠르게 만들고 싶다.
```

## 6. SwimPulse에는 어떤 모바일 방식이 맞나?

내 추천은 React Native다.

이유:

1. 이미 프론트가 Next.js/React다.
2. 타입스크립트 모델과 API 클라이언트 일부 사고방식을 재사용할 수 있다.
3. 앱의 핵심은 복잡한 3D/네이티브 기능보다 검색, 구독, 알림, 마이페이지다.
4. FCM 연동도 React Native Firebase로 가능하다.

다만 “모바일을 진짜 기초부터 공부하고 싶다”가 목적이면 Android는 Kotlin, iOS는 SwiftUI를 각각 해보는 것도 좋다.

실용 추천:

```text
1차 모바일 MVP:
React Native + TypeScript

학습 병행:
Android Studio에서 Kotlin 기본 구조 익히기
iOS는 Mac이 있을 때 Xcode/SwiftUI로 별도 실습
```

## 7. 프로젝트 구조는 어떻게 나누나?

현재 repo 안에 `mobile` 폴더를 추가하는 monorepo 방식이 좋다.

```text
SwimPulse/
  backend/
  frontend/
  mobile/
  ops/
  reports/
```

React Native라면:

```text
mobile/
  package.json
  app.json
  src/
    api/
    screens/
    components/
    notifications/
    auth/
```

공유할 수 있는 것:

| 공유 가능 | 방식 |
|---|---|
| API endpoint 타입 | `frontend/src/lib/types.ts`를 참고해 mobile용 타입 작성 |
| 인증 흐름 | JWT cookie 대신 mobile secure storage/token 방식으로 조정 필요 |
| 알림 데이터 모델 | backend notification API 그대로 사용 |
| 디자인 톤 | 색상/컴포넌트 가이드만 재사용 |

그대로 공유하기 어려운 것:

| 항목 | 이유 |
|---|---|
| Next.js 컴포넌트 | React Native는 DOM이 아니라 native view를 사용 |
| CSS/Tailwind class | React Native 스타일 시스템이 다름 |
| browser service worker | 모바일 앱에는 service worker가 없음 |
| cookie 기반 auth | 앱에서는 secure storage/token header 방식이 일반적 |

## 8. VS Code만 써도 되나?

React Native는 VS Code로 코딩할 수 있다. 하지만 빌드와 디버깅에는 플랫폼 도구가 필요하다.

| 작업 | 필요 도구 |
|---|---|
| React Native 코드 작성 | VS Code 가능 |
| Android emulator/build | Android Studio 필요 |
| iOS simulator/build | Xcode 필요, macOS 필요 |
| Android 실제 배포 | Google Play Console |
| iOS 실제 배포 | Apple Developer Program, App Store Connect |

즉:

```text
VS Code = 코드 작성
Android Studio = Android 빌드/에뮬레이터
Xcode = iOS 빌드/시뮬레이터/배포
```

Windows PC만 있으면 Android 개발은 가능하다. iOS는 결국 Mac이 필요하다.

## 9. 모바일 API 인증 구조

현재 웹은 OAuth 후 쿠키 기반으로 동작한다.

모바일은 다음 중 하나가 필요하다.

### 방식 A: Google OAuth mobile login + backend JWT 발급

```text
Mobile app
  -> Google login
  -> Google id_token 획득
  -> backend /api/auth/mobile/google
  -> backend가 id_token 검증
  -> access token 발급
  -> mobile secure storage 저장
  -> API 호출 시 Authorization: Bearer
```

장점:

1. 앱에서 쿠키보다 다루기 쉽다.
2. Android/iOS 모두 자연스럽다.
3. 현재 JWT 구조와 잘 맞는다.

### 방식 B: WebView 로그인 재사용

앱 안에 웹 로그인 화면을 띄워 기존 OAuth 흐름을 재사용한다.

장점:

1. 초기 구현이 빠르다.

단점:

1. 앱 UX가 덜 자연스럽다.
2. 쿠키/redirect/deep link 처리가 복잡해질 수 있다.

추천은 방식 A다.

## 10. 모바일 푸시 등록 흐름

```text
1. 앱 첫 실행
2. 알림 권한 요청
3. FCM token 발급
4. 로그인 후 backend에 token 등록
5. backend는 user_devices에 저장
6. 알림 발생 시 user_id의 active device token 전체 조회
7. FCM 발송
8. 실패 token은 비활성화
```

API 예시:

```text
POST /api/devices
Authorization: Bearer mobile-jwt

{
  "platform": "ANDROID",
  "fcmToken": "...",
  "deviceId": "...",
  "appVersion": "1.0.0"
}
```

로그아웃 시:

```text
DELETE /api/devices/{deviceId}
```

또는:

```text
POST /api/devices/deactivate
```

## 11. 개발 순서 추천

### 결제

```text
1. 유료화 모델 결정
2. plans/orders/payments/user_entitlements 테이블 설계
3. 웹 결제 Toss/PortOne 중 하나 선택
4. 테스트 결제 연동
5. webhook 검증
6. premium 권한 체크를 backend에 추가
7. 관리자 페이지에 결제/환불/권한 상태 추가
8. 모바일 출시 시 App Store/Play Billing 정책 반영
```

### 모바일

```text
1. mobile/ 폴더 생성
2. React Native 초기 프로젝트 생성
3. 로그인 없는 화면부터 구현: 주변 수영장/공지 보기
4. Google mobile login 추가
5. backend mobile JWT API 추가
6. FCM token 등록 API 추가
7. 구독/마이페이지/알림 목록 구현
8. Android 실제 기기 푸시 테스트
9. iOS APNs 설정 후 실제 기기 테스트
10. 배포 준비
```

## 12. 최종 추천

### 서버 배포

처음 공개 배포는 다음을 추천한다.

```text
EC2 t4g.medium + RDS MySQL + EC2 Redis
```

이유:

1. 비용이 Lightsail보다 약간 높지만 RDS를 분리할 수 있다.
2. 백엔드 포트폴리오에서 AWS 운영 경험을 설명하기 좋다.
3. 이후 ECS/ElastiCache로 확장하기 쉽다.

정말 비용이 최우선이면:

```text
Lightsail 4GB 한 대
```

### 결제

처음에는 웹 결제만 붙이는 게 좋다.

```text
Toss Payments 또는 PortOne
웹 프리미엄 구독
서버 결제 승인 + webhook 검증
```

앱 출시 후에는 디지털 프리미엄 구독이면 Apple/Google 인앱결제를 별도로 고려해야 한다.

### 모바일

가장 빠른 길:

```text
React Native + TypeScript
mobile/ 폴더 추가
Firebase FCM 그대로 사용
backend는 mobile JWT + device token 등록 API 추가
```

모바일을 정석으로 배우고 싶다면:

```text
Android: Kotlin + Android Studio
iOS: SwiftUI + Xcode
```

## 13. 참고 자료

- Amazon Lightsail pricing: https://aws.amazon.com/lightsail/pricing/
- Lightsail instance bundles: https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html
- Lightsail managed database billing: https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html
- Amazon EC2 On-Demand pricing: https://aws.amazon.com/ec2/pricing/on-demand/
- Firebase Cloud Messaging overview: https://firebase.google.com/docs/cloud-messaging
- Firebase Cloud Messaging Apple setup: https://firebase.google.com/docs/cloud-messaging/ios/get-started
- React Native Firebase Messaging: https://rnfirebase.io/messaging/usage
- Android Kotlin docs: https://developer.android.com/kotlin
- Apple Xcode: https://developer.apple.com/xcode/
- Toss Payments Core API: https://docs.tosspayments.com/reference
- Toss Payments payment widget guide: https://docs.tosspayments.com/guides/payment-widget/integration
- PortOne webhook docs: https://developers.portone.io/opi/ko/integration/webhook/readme-v1
- Apple App Review Guidelines: https://developer.apple.com/app-store/review/guidelines/
- Google Play Billing: https://developer.android.com/google/play/billing
