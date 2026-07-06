# 모바일 앱 개발 첫 시작 가이드: React Native, FCM, 인증, 빌드, 테스트

작성일: 2026-06-25  
보고서 경로: `reports/036_mobile-app-first-guide-react-native-fcm-auth-test-2026-06-25.md`  
대상: SwimPulse를 iOS/Android 앱으로 확장하려는 첫 모바일 개발자

## 목적

이 문서는 “모바일 앱 개발을 처음 해보는 백엔드/웹 개발자” 기준으로 정리한다.

질문은 크게 여섯 가지다.

1. 왜 React Native + TypeScript를 추천하는가?
2. VS Code에서 코드 짜는데 왜 Android Studio/Xcode 이야기가 나오는가?
3. Kotlin, Swift, React Native, Flutter는 왜 다 있고 무엇이 다른가?
4. Service Worker가 없는 모바일 앱은 FCM 푸시를 어떻게 받는가?
5. 웹 JWT 쿠키와 모바일 secure storage/token 방식은 뭐가 다른가?
6. 앱을 만들고 어떻게 테스트하고 설치해보는가?

## 1. 앱 개발도 프론트 개발 아닌가?

맞다. 큰 의미에서는 모바일 앱도 프론트 개발이다.

사용자 화면을 만들고, 버튼을 누르면 API를 호출하고, 상태를 관리하고, 알림을 보여준다. 이 점은 웹 프론트와 같다.

하지만 실행 환경이 다르다.

| 구분 | 웹 프론트 | 모바일 앱 |
|---|---|---|
| 실행 환경 | 브라우저 | Android OS / iOS |
| 화면 구성 | HTML DOM | Native View |
| 스타일 | CSS | Android/iOS native style 또는 framework style |
| 저장소 | cookie, localStorage, IndexedDB | Keychain, Keystore, SecureStore 등 |
| 푸시 수신 | Service Worker 기반 Web Push | OS push service 기반 |
| 빌드 산출물 | JS/CSS/HTML bundle | `.apk`, `.aab`, `.ipa` |
| 배포 | 웹 서버/CDN | Play Store/App Store 또는 직접 설치 |

그래서 앱 개발은 “프론트 개발”이 맞지만, 브라우저가 아니라 OS 위에서 돈다는 점 때문에 도구와 개념이 더 많다.

## 2. 왜 언어와 프레임워크가 이렇게 많나?

모바일은 원래 Android와 iOS가 서로 다른 플랫폼이다.

```text
Android:
  - Kotlin / Java
  - Android Studio
  - Gradle
  - APK / AAB

iOS:
  - Swift / Objective-C
  - Xcode
  - IPA
  - App Store
```

여기에 “한 번 개발해서 Android/iOS 둘 다 만들자”는 필요가 생기면서 크로스 플랫폼 프레임워크가 나왔다.

```text
React Native:
  - JavaScript / TypeScript
  - React 문법
  - Android/iOS native view를 JS로 조작

Flutter:
  - Dart
  - 자체 렌더링 엔진
  - Android/iOS 화면을 거의 같은 방식으로 그림
```

즉, 언어가 파편화된 이유는 Android와 iOS가 원래 다른 플랫폼이고, 그 위에 크로스 플랫폼 선택지가 추가됐기 때문이다.

## 3. Kotlin, Swift, React Native, Flutter 차이

### Kotlin / Android Native

Android 공식 앱 개발 방식이다.

```text
언어: Kotlin
IDE: Android Studio
대상: Android
```

장점:

1. Android 기능을 가장 정확하게 쓸 수 있다.
2. FCM, 권한, 백그라운드, 알림 채널 같은 Android 개념을 정석으로 배운다.
3. Android 개발자 관점에서 가장 표준적이다.

단점:

1. iOS 앱은 따로 만들어야 한다.
2. React/TypeScript 경험을 거의 재사용하지 못한다.

### Swift / iOS Native

iOS 공식 앱 개발 방식이다.

```text
언어: Swift
IDE: Xcode
대상: iOS
```

장점:

1. iOS 기능을 가장 정확하게 쓸 수 있다.
2. Apple 생태계에 가장 잘 맞는다.

단점:

1. iOS만 만든다.
2. Xcode와 macOS가 필요하다.

### React Native

React 문법으로 Android/iOS 앱을 만드는 크로스 플랫폼 프레임워크다.

```text
언어: JavaScript / TypeScript
코딩: VS Code 가능
빌드: Android Studio/Gradle, Xcode 필요
대상: Android + iOS
```

React Native는 HTML을 앱에 띄우는 것이 아니다. React 컴포넌트를 쓰지만 결과물은 Android/iOS native view로 바뀐다.

예:

```tsx
import { Text, View } from "react-native";

export function PoolCard() {
  return (
    <View>
      <Text>오정레포츠센터</Text>
    </View>
  );
}
```

웹의 `<div>`, `<p>`가 아니라 React Native의 `<View>`, `<Text>`를 쓴다.

장점:

1. 지금 Next.js/React 경험과 가장 가깝다.
2. TypeScript를 계속 쓸 수 있다.
3. Android/iOS를 한 코드베이스로 만들 수 있다.
4. SwimPulse처럼 검색, 구독, 알림, 마이페이지 중심 앱에 잘 맞는다.

단점:

1. 네이티브 설정에서 막힐 수 있다.
2. Android/iOS 빌드 도구를 완전히 피할 수는 없다.
3. 성능이나 네이티브 기능이 깊어지면 플랫폼별 코드도 필요하다.

### Flutter

Dart 언어로 Android/iOS 앱을 만드는 크로스 플랫폼 프레임워크다.

```text
언어: Dart
IDE: VS Code 또는 Android Studio
대상: Android + iOS
```

장점:

1. UI 일관성이 좋다.
2. 앱다운 화면을 빠르게 만들 수 있다.
3. Firebase 연동도 잘 된다.

단점:

1. Dart를 새로 배워야 한다.
2. 기존 React/TypeScript 코드를 거의 재사용하지 못한다.

## 4. 왜 React Native + TypeScript를 추천했나?

SwimPulse에는 React Native + TypeScript가 가장 현실적이라고 봤다.

이유:

1. 현재 프론트가 Next.js + React + TypeScript다.
2. 타입 정의, API 응답 모델, 상태 관리 사고방식을 이어가기 쉽다.
3. 앱 기능이 복잡한 네이티브 그래픽보다 검색/구독/알림/마이페이지 중심이다.
4. Android/iOS를 따로 만드는 것보다 빠르게 MVP를 만들 수 있다.
5. FCM도 React Native Firebase로 연동 가능하다.

단, “모바일 자체를 깊게 배우는 것”이 목표라면 Android는 Kotlin, iOS는 SwiftUI를 따로 해보는 것이 더 정석이다.

내 추천은 이렇다.

```text
빠른 SwimPulse 모바일 MVP:
  React Native + TypeScript

모바일 원리 학습:
  Android Kotlin 기초도 같이 보기

iOS 출시:
  Mac/Xcode 환경이 생겼을 때 진행
```

## 5. VS Code로 코딩하는데 왜 Android Studio가 필요한가?

VS Code는 편집기다. 코드를 작성하는 도구다.

Android Studio는 Android SDK, emulator, Gradle 설정, 디버깅 도구를 포함한 Android 개발 환경이다.

React Native라도 Android 앱을 최종적으로 만들려면 Android 빌드 도구가 필요하다.

```text
VS Code:
  - TypeScript 코드 작성
  - 컴포넌트 수정
  - API 코드 작성

Android Studio:
  - Android SDK 설치
  - Android emulator 실행
  - Gradle 빌드 환경 관리
  - native Android 설정 확인
```

질문처럼 명령어로도 빌드할 수 있다.

```bash
cd mobile/android
./gradlew assembleDebug
```

Windows라면:

```powershell
cd mobile\android
.\gradlew assembleDebug
```

그런데 이 명령어가 돌아가려면 Android SDK, JDK, Gradle 설정, 환경변수 등이 제대로 있어야 한다. Android Studio가 그 환경을 가장 쉽게 설치하고 관리해준다.

즉:

```text
코드는 VS Code에서 작성 가능
빌드는 명령어로 가능
하지만 Android SDK/에뮬레이터 관리는 Android Studio가 필요
```

## 6. iOS 배포에는 왜 Mac이 필요한가?

iOS 앱은 Apple의 Xcode로 빌드하고 서명해야 한다. Xcode는 macOS에서만 동작한다.

그래서 iOS 앱을 실제로 빌드하고 App Store에 올리려면 보통 Mac이 필요하다.

```text
iOS 앱 빌드/배포:
  macOS
  Xcode
  Apple Developer Account
  signing certificate
  provisioning profile
```

그럼 꼭 MacBook을 사야 하냐?

선택지는 있다.

| 방법 | 설명 |
|---|---|
| MacBook/Mac mini 구매 | 가장 안정적 |
| 빌드용 Mac 대여 서비스 | 필요할 때만 사용 |
| GitHub Actions macOS runner | CI 빌드 가능하지만 설정 필요 |
| Expo EAS Build | 클라우드 빌드 사용 가능 |
| iOS는 나중에 하고 Android부터 | 처음에는 가장 현실적 |

처음 모바일을 배우는 입장이라면 Android부터 시작해도 된다. Windows PC만 있어도 Android 개발과 실제 기기 테스트는 가능하다.

## 7. 모바일에는 Service Worker가 없는데 FCM 푸시는 어떻게 받나?

웹 푸시는 브라우저가 Service Worker를 통해 백그라운드 알림을 받는다.

```text
Web:
  Browser
  -> Service Worker
  -> Web Push/FCM
  -> 알림 표시
```

모바일 앱은 Service Worker를 쓰지 않는다. 대신 OS의 푸시 시스템을 쓴다.

```text
Android:
  FCM
  -> Android OS
  -> 앱의 Firebase Messaging handler
  -> 알림 표시

iOS:
  FCM
  -> APNs
  -> iOS
  -> 앱의 notification handler
  -> 알림 표시
```

서버 입장에서는 여전히 FCM token으로 메시지를 보낸다.

```text
Spring Boot
  -> Firebase Admin SDK
  -> token_aaa
  -> token_bbb
```

달라지는 것은 클라이언트 쪽이다.

| 구분 | 웹 | 모바일 |
|---|---|---|
| 토큰 발급 | Firebase Web SDK + Service Worker | Firebase Native SDK |
| 백그라운드 수신 | Service Worker | Android/iOS OS push handler |
| 알림 표시 | 브라우저 Notification API | OS notification |
| 서버 발송 | FCM Admin SDK | FCM Admin SDK |

즉, 서버 FCM 발송 구조는 크게 유지되고, 앱에서 토큰 발급/등록/수신 처리를 새로 만든다.

## 8. 웹 JWT 쿠키와 모바일 secure storage는 뭐가 다른가?

현재 웹은 이런 방식이다.

```text
Google OAuth 로그인
  -> backend가 JWT 발급
  -> browser cookie에 저장
  -> 이후 API 요청 때 cookie 자동 전송
```

브라우저는 쿠키를 자동으로 붙여준다.

```text
GET /api/my-page
Cookie: access_token=...
```

모바일 앱은 브라우저가 아니기 때문에 쿠키 자동 관리가 기본 흐름이 아니다. 그래서 보통 토큰을 앱의 안전한 저장소에 저장하고, API 호출 때 직접 header에 넣는다.

```text
Google mobile login
  -> backend가 JWT 발급
  -> mobile secure storage에 저장
  -> API 요청 때 Authorization header에 넣음
```

```text
GET /api/my-page
Authorization: Bearer eyJ...
```

### secure storage란?

모바일 OS가 제공하는 안전한 저장소다.

| 플랫폼 | 안전 저장소 |
|---|---|
| iOS | Keychain |
| Android | Keystore 기반 encrypted storage |
| React Native/Expo | SecureStore 같은 라이브러리 |

웹의 localStorage처럼 그냥 문자열 파일에 저장하는 것이 아니라, OS가 보호하는 저장소에 넣는 개념이다.

React Native 예시:

```ts
import * as SecureStore from "expo-secure-store";

await SecureStore.setItemAsync("accessToken", token);
const token = await SecureStore.getItemAsync("accessToken");
```

로그아웃할 때:

```ts
await SecureStore.deleteItemAsync("accessToken");
```

### “JWT를 쓰는데 뭐가 다르냐”

JWT 자체는 같다.

다른 것은 저장 위치와 전송 방식이다.

| 구분 | 웹 | 모바일 |
|---|---|---|
| 토큰 종류 | JWT | JWT |
| 저장 위치 | HTTP-only cookie 권장 | Secure storage |
| API 전송 | 브라우저가 cookie 자동 전송 | 앱이 Authorization header로 직접 전송 |
| CSRF 이슈 | cookie 기반이라 고려 필요 | header token이라 상대적으로 다름 |
| XSS 이슈 | HTTP-only cookie면 JS 접근 차단 | 앱 코드/기기 보안 고려 |

## 9. 모바일 로그인은 어떻게 바꾸면 좋나?

웹 OAuth redirect를 그대로 앱에서 쓰려면 deep link, cookie, redirect 처리가 복잡해질 수 있다.

모바일에는 다음 흐름이 더 자연스럽다.

```text
1. 앱에서 Google 로그인 SDK 실행
2. Google id_token 획득
3. 앱이 backend에 id_token 전달
4. backend가 Google 공개키로 id_token 검증
5. 기존 user 조회 또는 생성
6. backend가 SwimPulse JWT 발급
7. 앱이 JWT를 secure storage에 저장
8. 이후 API는 Authorization: Bearer JWT
```

백엔드 API 예시:

```text
POST /api/auth/mobile/google

{
  "idToken": "google-id-token"
}
```

응답:

```json
{
  "accessToken": "swimpulse-jwt",
  "user": {
    "id": 1,
    "email": "user@example.com"
  }
}
```

## 10. 모바일 푸시 등록 흐름

앱에서 FCM token을 받은 뒤 서버에 등록한다.

```text
1. 앱 실행
2. 알림 권한 요청
3. Firebase SDK에서 FCM token 발급
4. 로그인 완료 후 backend에 token 등록
5. backend가 user_devices에 저장
6. 알림 발생 시 해당 user의 active device token 전체로 발송
```

API 예시:

```text
POST /api/devices
Authorization: Bearer mobile-jwt

{
  "platform": "ANDROID",
  "fcmToken": "fcm-token",
  "deviceId": "device-id",
  "appVersion": "1.0.0"
}
```

한 사용자가 웹, Android, iPhone을 모두 등록했다면:

```text
notification row 1건
  -> web token 전송
  -> android token 전송
  -> ios token 전송
```

마이페이지 알림은 1건만 보여야 한다. 기기가 3개라고 알림 row가 3개가 되면 안 된다.

## 11. 모바일 코드를 어떻게 실행하고 확인하나?

처음에는 세 단계로 확인한다.

### 1단계: 개발 서버 + 에뮬레이터

React Native 앱을 실행하면 Metro dev server가 뜬다.

```bash
npm start
```

Android 실행:

```bash
npm run android
```

흐름:

```text
VS Code에서 코드 수정
  -> Metro가 변경 감지
  -> Android emulator 또는 실제 기기에 반영
```

웹의 `npm run dev`와 비슷한 개발 경험이다.

### 2단계: 실제 Android 기기 연결

Android 폰에서 개발자 옵션과 USB 디버깅을 켠다.

```powershell
adb devices
```

기기가 보이면:

```powershell
npm run android
```

앱이 실제 폰에 설치되고 실행된다.

### 3단계: APK 빌드 후 직접 설치

개발용 APK:

```powershell
cd mobile\android
.\gradlew assembleDebug
```

결과물 예시:

```text
mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

이 APK를 폰에 설치해서 테스트할 수 있다.

설치 방법:

```powershell
adb install app-debug.apk
```

또는 파일을 폰으로 옮겨 직접 설치할 수 있다. 단, Android에서 “알 수 없는 앱 설치 허용”이 필요할 수 있다.

스토어 배포용은 보통 `.apk`보다 `.aab`를 만든다.

```powershell
.\gradlew bundleRelease
```

결과:

```text
app-release.aab
```

Google Play Console에는 보통 `.aab`를 올린다.

## 12. 모바일 테스트는 어떻게 하나?

웹처럼 테스트도 여러 층이 있다.

### 수동 테스트

처음에는 이게 제일 중요하다.

체크리스트:

1. 앱 실행
2. 로그인
3. 위치 권한 요청
4. 수영장 검색
5. 공지 확인
6. 구독 생성
7. 마이페이지 확인
8. FCM token 서버 등록 확인
9. 테스트 푸시 수신 확인
10. 로그아웃 후 token 비활성화 확인

### 단위 테스트

비즈니스 로직이나 유틸 함수 테스트.

예:

```text
날짜 포맷
API 응답 변환
알림 상태 표시
```

React Native에서는 Jest를 많이 쓴다.

### 컴포넌트 테스트

화면이 특정 데이터에서 잘 렌더링되는지 확인한다.

예:

```text
구독 카드
알림 리스트
공지 기간 표시
```

### E2E 테스트

실제 앱을 켜고 버튼을 누르며 테스트한다.

도구 후보:

```text
Detox
Maestro
Appium
```

처음에는 Maestro가 비교적 접근하기 쉽다.

### 푸시 테스트

푸시는 실제 기기에서 보는 게 가장 정확하다.

테스트 흐름:

```text
1. 앱 설치
2. 로그인
3. 알림 권한 허용
4. FCM token 등록
5. backend 테스트 알림 API 호출
6. 앱 foreground/background/종료 상태에서 알림 확인
```

상태별로 다르게 봐야 한다.

| 앱 상태 | 확인할 것 |
|---|---|
| foreground | 앱 안에서 알림 UI를 직접 띄울지 |
| background | OS 알림으로 뜨는지 |
| terminated | 앱이 꺼져 있어도 수신되는지 |

## 13. 앱 개발을 시작하면 프로젝트는 어떻게 나누나?

현재 repo 안에 `mobile`을 추가하는 걸 추천한다.

```text
SwimPulse/
  backend/
  frontend/
  mobile/
  ops/
  reports/
```

React Native 기준:

```text
mobile/
  package.json
  android/
  ios/
  src/
    api/
    auth/
    notifications/
    screens/
    components/
    storage/
    types/
```

주의:

1. `frontend` 코드를 그대로 복사해서 쓰는 구조는 아니다.
2. API 타입과 디자인 방향은 참고할 수 있다.
3. 화면 컴포넌트는 React Native용으로 다시 만든다.
4. 인증은 cookie가 아니라 secure storage + Authorization header 흐름을 추가한다.

## 14. SwimPulse 모바일 MVP 기능 범위

처음부터 모든 기능을 넣지 않는 게 좋다.

1차 MVP 추천:

```text
1. Google 로그인
2. 내 주변/검색 수영장 목록
3. 공지 확인
4. 구독 생성/해제
5. 마이페이지 구독 목록
6. 알림 목록
7. FCM 푸시 수신
```

나중에 추가:

```text
1. 관리자 페이지 모바일 대응
2. 결제
3. 지도 화면 고도화
4. 오프라인 캐시
5. 지역 추천
```

## 15. 최종 정리

React Native + TypeScript를 추천한 이유는 “지금 프로젝트와 가장 이어지기 쉽기 때문”이다. 이미 React/TypeScript를 쓰고 있고, SwimPulse 앱은 네이티브 게임이나 고성능 그래픽 앱이 아니라 검색/구독/알림 중심 서비스다.

VS Code에서 개발할 수 있지만, Android 앱을 실제로 빌드하려면 Android SDK와 emulator가 필요해서 Android Studio가 필요하다. 명령어로 `gradlew` 빌드도 가능하지만, 그 명령어가 동작하려면 Android 개발 환경이 설치되어 있어야 한다.

iOS는 Apple 정책상 Xcode가 필요하고, Xcode는 macOS에서만 돌아간다. 그래서 iOS 빌드/배포에는 Mac이 필요하다. 처음에는 Windows에서 Android 앱부터 시작해도 충분하다.

모바일에는 Service Worker가 없다. 대신 Android/iOS OS의 push system과 Firebase native SDK가 FCM token을 받고 알림을 처리한다. 서버는 기존처럼 FCM token으로 메시지를 보내면 된다.

JWT 자체는 웹과 모바일 모두 쓸 수 있다. 차이는 저장과 전송 방식이다. 웹은 HTTP-only cookie가 자연스럽고, 모바일은 secure storage에 저장한 뒤 `Authorization: Bearer` header로 보내는 방식이 일반적이다.

처음 진행 순서 추천:

```text
1. mobile/ 폴더 생성
2. React Native + TypeScript 초기화
3. Android Studio 설치
4. Android emulator 또는 실제 Android 폰 연결
5. API 없는 정적 화면 먼저 구현
6. backend에 mobile Google login API 추가
7. secure storage에 JWT 저장
8. FCM token 등록 API 추가
9. 테스트 푸시 수신 확인
10. 구독/알림 기능 연결
```

## 16. 참고 자료

- React Native 공식 문서: https://reactnative.dev/docs/getting-started
- React Native Firebase Messaging: https://rnfirebase.io/messaging/usage
- Firebase Cloud Messaging: https://firebase.google.com/docs/cloud-messaging
- Firebase Cloud Messaging Android: https://firebase.google.com/docs/cloud-messaging/android/client
- Firebase Cloud Messaging iOS: https://firebase.google.com/docs/cloud-messaging/ios/client
- Android Studio: https://developer.android.com/studio
- Android Kotlin: https://developer.android.com/kotlin
- Android 앱 서명: https://developer.android.com/studio/publish/app-signing
- Apple Xcode: https://developer.apple.com/xcode/
- Apple Developer Program: https://developer.apple.com/programs/
- Expo SecureStore: https://docs.expo.dev/versions/latest/sdk/securestore/
