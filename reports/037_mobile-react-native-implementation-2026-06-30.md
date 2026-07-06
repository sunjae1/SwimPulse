# SwimPulse Mobile React Native 구현 보고서

작성일: 2026-06-30

## 목적

SwimPulse 사용자 기능을 모바일에서도 사용할 수 있도록 `mobile/`에 React Native CLI + TypeScript 앱을 추가했다. Android를 우선 대상으로 구현했고, iOS 폴더는 React Native 기본 구조로 생성했지만 Windows 환경에서는 빌드 검증하지 않았다.

관리자 대시보드는 모바일 v1 범위에서 제외했다.

## 구현 요약

### 모바일 앱

추가 위치: `mobile/`

구성:

```text
mobile/
  App.tsx
  src/api/client.ts
  src/api/types.ts
  src/auth/googleAuth.ts
  src/auth/tokenStore.ts
  src/notifications/push.ts
  src/utils/date.ts
  android/
  ios/
```

주요 라이브러리:

| 목적 | 라이브러리 |
|---|---|
| 네비게이션 | `@react-navigation/native`, `@react-navigation/native-stack` |
| 안전 영역/스크린 | `react-native-safe-area-context`, `react-native-screens` |
| Google 로그인 | `@react-native-google-signin/google-signin` |
| FCM | `@react-native-firebase/app`, `@react-native-firebase/messaging` |
| JWT 저장 | `react-native-keychain` |
| 위치 권한/좌표 | `react-native-geolocation-service` |

구현 화면:

| 화면 | 기능 |
|---|---|
| 홈 | 수영장 목록, 최근 모집 일정, 위치 검색, 현재 위치 주변 수영장 |
| 공지 확인 모달 | 공지 스캔 결과, 모집 기간 표시, 구독/해제, 원문 보기 |
| 마이페이지 | 진행 중 구독, 마감 구독 접힘 처리, 기간 수정, 구독 해제, 알림 목록 pagination/읽음 처리 |
| 설정 | API health 확인, Google 로그인/로그아웃, FCM 기기 등록/해제, 테스트 알림 요청 |

모바일 API 기본 URL:

```text
http://10.0.2.2:8080
```

Android emulator에서 `10.0.2.2`는 호스트 PC의 `localhost`를 의미한다.

## 백엔드 변경

### Bearer JWT 인증 추가

파일: `backend/src/main/java/com/swimpulse/auth/JwtAuthenticationFilter.java`

기존 웹은 cookie의 `swimpulse_access_token`을 읽었다. 모바일은 cookie 저장/전송보다 `Authorization: Bearer <JWT>` header 방식이 일반적이므로 필터가 Bearer header를 먼저 읽고, 없으면 기존 cookie를 읽도록 확장했다.

```text
Authorization: Bearer <SwimPulse JWT>
```

기존 웹 OAuth/cookie 흐름은 유지된다.

### 모바일 Google 로그인 API

추가 API:

```text
POST /api/auth/mobile/google
```

요청:

```json
{
  "idToken": "google-id-token"
}
```

응답:

```json
{
  "accessToken": "swimpulse-jwt",
  "user": {}
}
```

구현 파일:

| 파일 | 역할 |
|---|---|
| `MobileGoogleLoginRequest.java` | 모바일 로그인 요청 DTO |
| `MobileLoginResponse.java` | JWT + 사용자 응답 DTO |
| `MobileGoogleAuthService.java` | Google ID token 검증 후 SwimPulse JWT 발급 |
| `OAuthLoginService.java` | 웹 OAuth와 모바일 Google 로그인에서 사용자 생성/갱신 로직 공유 |
| `AuthController.java` | `/api/auth/mobile/google` endpoint 추가 |
| `SecurityConfig.java` | 모바일 로그인 API `permitAll` 처리 |

Google ID token 검증은 Google JWK를 사용하며, issuer/audience/email_verified를 확인한다.

### Device token platform 추가

기존 `/api/notifications/device-tokens`를 모바일에서도 재사용한다. 요청 DTO에 `platform` 선택 필드를 추가했다.

```json
{
  "deviceId": "android-...",
  "fcmToken": "...",
  "platform": "ANDROID"
}
```

기존 웹 요청처럼 `platform`을 보내지 않으면 `WEB`으로 처리한다.

추가/변경:

| 파일 | 내용 |
|---|---|
| `DevicePlatform.java` | `WEB`, `ANDROID`, `IOS` enum |
| `RegisterDeviceTokenRequest.java` | `platform` 필드와 기본값 처리 |
| `UserDevice.java` | platform 컬럼 매핑 |
| `NotificationService.java` | token 등록/갱신 시 platform 저장 |
| `V17__add_user_device_platform.sql` | `user_devices.platform` 컬럼 추가 |

마이페이지 알림 row는 사용자 기준 1건이고, 발송 시점에는 사용자의 active device token 전체로 FCM을 보낸다. 즉, 한 사용자가 여러 기기를 등록해도 마이페이지 안읽음은 알림 row 기준으로 1건만 증가하는 구조를 유지한다.

## FCM 흐름

```text
모바일 로그인
  -> SwimPulse JWT를 Keychain에 저장
  -> FCM 권한 요청
  -> Firebase Messaging에서 fcmToken 발급
  -> /api/notifications/device-tokens
     Authorization: Bearer <JWT>
     platform=ANDROID
  -> user_devices row 저장/갱신

알림 발송
  -> notifications row 1건 생성
  -> Redis queue에 notificationId publish
  -> worker가 notificationId pop
  -> 사용자 active device token 전체로 FCM 전송
```

주의:

현재 repo에는 실제 Android Firebase 설정 파일이 없다. 실제 FCM 토큰 발급과 앱 푸시 수신까지 보려면 다음 작업이 필요하다.

```text
mobile/android/app/google-services.json 추가
Firebase Android app 등록
Android package name: com.swimpulsemobile
Google Sign-In Android OAuth client에 SHA-1/SHA-256 등록
```

## Android 설정

추가 권한:

파일: `mobile/android/app/src/main/AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

로컬 HTTP 호출 허용:

파일: `mobile/android/app/build.gradle`

```gradle
manifestPlaceholders = [usesCleartextTraffic: "true"]
```

`mobile/android/local.properties`에는 로컬 Android SDK 경로를 넣었다. 이 파일은 gitignore 대상이다.

## 테스트 결과

### 모바일 정적/단위 테스트

```powershell
cd mobile
npx tsc --noEmit
npm run lint
npm test -- --runInBand
```

결과:

| 테스트 | 결과 |
|---|---|
| TypeScript type check | 통과 |
| ESLint | 통과 |
| Jest render test | 통과, 1 test |

Jest 설정 보강:

| 파일 | 내용 |
|---|---|
| `jest.config.js` | `@react-native/jest-preset`, ESM transform 허용 |
| `jest.setup.js` | Keychain, Google Sign-In, Firebase Messaging, Geolocation mock |

### 백엔드 변경 범위 테스트

추가 테스트:

| 테스트 | 검증 |
|---|---|
| `JwtAuthenticationFilterTests` | Bearer JWT 인증, 기존 cookie JWT 인증, 잘못된 Bearer 거부 |
| `RegisterDeviceTokenRequestTests` | platform 누락 시 WEB 기본값, ANDROID platform 처리 |

실행:

```powershell
cd backend
.\gradlew.bat test --tests "com.swimpulse.auth.JwtAuthenticationFilterTests" --tests "com.swimpulse.notification.RegisterDeviceTokenRequestTests"
```

결과: 통과

### 백엔드 전체 테스트

실행:

```powershell
cd backend
.\gradlew.bat test
```

결과:

```text
65 tests completed, 2 failed
```

실패 테스트:

| 테스트 | 실패 내용 |
|---|---|
| `NoticeCrawlerServiceTests.imageOcrRetryParsesMaedalMonthlyRangesWithTimes` | 기대 period 4건, 실제 6건 |
| `NoticeCrawlerServiceTests.imageOcrRetryNormalizesDuplicateRangeFragmentsAndSuppressesMonthlyFalsePositive` | 기대 period 4건, 실제 5건 |

이 실패는 모바일 인증/device token 변경과 무관한 OCR 기간 파싱 테스트다. 전체 컴파일은 통과했다.

### Android build/install

일반 경로에서 실행하면 Windows 경로 길이 제한 때문에 RN 새 아키텍처 CMake build가 실패했다.

실패 원인:

```text
Filename longer than 260 characters
```

해결 방식:

짧은 junction 경로로 접근해서 빌드했다.

```powershell
New-Item -ItemType Junction -Path C:\spm -Target "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile"
cd C:\spm\android
.\gradlew.bat clean assembleDebug
```

결과: Android debug build 성공

APK:

```text
mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

에뮬레이터:

```powershell
adb devices
```

결과:

```text
emulator-5554 device
```

설치:

```powershell
adb install -r mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

결과: 성공

launch intent:

```powershell
adb shell monkey -p com.swimpulsemobile 1
```

결과: 성공

## 실행 방법

백엔드/Redis/MySQL을 먼저 실행한다.

```powershell
docker compose up -d --build
```

모바일 Metro server:

```powershell
cd mobile
npm start
```

Windows 긴 경로 문제가 있으면 짧은 junction으로 실행한다.

```powershell
New-Item -ItemType Junction -Path C:\spm -Target "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile"
cd C:\spm
npm run android
```

기존 터미널에서 `adb`가 안 잡히면 직접 경로로 실행한다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

## 남은 작업

### Android

1. Firebase Android app 생성
2. `google-services.json` 추가
3. Google Sign-In Android OAuth client 설정
4. SHA-1/SHA-256 등록
5. 실제 Google 로그인 end-to-end 확인
6. 실제 FCM token 발급/등록/푸시 수신 확인

### iOS

Windows 환경에서는 빌드하지 못했다. Mac/Xcode에서 별도 검증이 필요하다.

필요 작업:

1. `pod install`
2. iOS bundle id 결정
3. GoogleService-Info.plist 추가
4. Apple Push Notification service 설정
5. Firebase iOS app 등록
6. 실제 기기 푸시 테스트

### UX 보강

1. 날짜 입력은 현재 텍스트 입력 방식이다. 이후 native date/time picker 도입 권장
2. 공지 확인 결과 화면은 v1에서 modal이다. 목록이 길어지면 별도 상세 화면으로 분리 권장
3. 앱 첫 실행 온보딩/소개 화면은 v1에서 제외했다.
4. 이미지/카드 UI는 기능 연결 우선으로 구현했다. 디자인 polishing은 후속 작업 권장

## 판단

모바일 v1의 핵심 구조는 준비됐다.

현재 상태에서 가능한 것:

```text
React Native 앱 빌드
Android emulator 설치/launch
Bearer JWT 기반 모바일 API 호출 구조
Google ID token -> SwimPulse JWT 발급 API
FCM device token 등록 API 확장
사용자 기능 화면 v1 구현
```

실제 모바일 로그인/푸시 end-to-end는 Firebase Android 설정 파일과 Google OAuth Android client 설정을 넣은 뒤 확인하면 된다.
