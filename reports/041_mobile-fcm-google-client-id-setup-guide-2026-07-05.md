# 041 Mobile FCM / Google Client ID 설정 가이드

작성일: 2026-07-05

## 목적

SwimPulse React Native Android 앱에서 Google 로그인과 FCM 푸시를 설정할 때 필요한 콘솔 작업, 코드 위치, client id 역할을 정리한다.

## 현재 앱 정보

현재 Android 앱 설정:

| 항목 | 값 |
|---|---|
| Android package name | `com.swimpulsemobile` |
| Debug SHA-1 | `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25` |
| Web client id | `829584550774-17n75ueed6o958h3078dljisuoi1g33r.apps.googleusercontent.com` |
| Android API URL | `http://10.0.2.2:8080` |

SHA-1 확인 명령:

```powershell
cd mobile/android
.\gradlew.bat signingReport
```

## React Native 코드에서 web client id 위치

현재 Google 로그인 설정 위치:

```text
mobile/src/auth/googleAuth.ts
```

현재 코드:

```ts
const GOOGLE_WEB_CLIENT_ID =
  '829584550774-17n75ueed6o958h3078dljisuoi1g33r.apps.googleusercontent.com';

GoogleSignin.configure({
  webClientId: GOOGLE_WEB_CLIENT_ID,
  offlineAccess: false,
});
```

여기에는 Android OAuth client id가 아니라 **Web client id**를 넣는다.

## 왜 Android app client id를 코드에 넣지 않나?

Google 로그인에는 여러 client id가 나온다.

| client id 종류 | 역할 | 앱 코드에 직접 넣는가 |
|---|---|---|
| Web client id | 백엔드가 검증할 `idToken`의 audience | 현재 구조에서는 넣음 |
| Android client id | package name + SHA-1로 Android 앱 신원 확인 | 보통 직접 넣지 않음 |
| iOS client id | iOS bundle id / URL scheme 기반 iOS 앱 신원 확인 | iOS 설정에서 사용 |

현재 SwimPulse는 모바일 앱이 Google idToken을 받아 백엔드로 보내고, 백엔드가 그 idToken을 검증한 뒤 SwimPulse JWT를 발급한다.

```text
Android app
  -> Google Sign-In
  -> idToken 획득
  -> POST /api/auth/mobile/google
  -> backend가 idToken 검증
  -> SwimPulse JWT 발급
```

백엔드는 idToken의 `aud` 값을 확인한다.

```text
aud = 이 토큰이 어느 OAuth client를 대상으로 발급됐는가
```

현재 백엔드는 `application.properties`의 Google web client id와 `aud`가 일치하는지 검증한다.

그래서 앱도 `GoogleSignin.configure({ webClientId })`에 Web client id를 넣어야 한다.

공식 Firebase Android Google 로그인 문서에서도 서버 인증을 위해 넘기는 client id는 Web application type client id라고 설명한다.

## Android client id는 내부적으로 어떻게 쓰이나?

Android OAuth client는 앱 코드에 직접 넣는 값이라기보다 Google Cloud/Firebase에 등록되는 앱 신원 정보다.

```text
Android OAuth client
  - package name: com.swimpulsemobile
  - SHA-1: 5E:8F:16:06:...
```

Google Play Services는 로그인 시 현재 실행 중인 앱의 package name과 서명 SHA-1을 확인한다.

```text
현재 앱 package name + SHA-1
  -> Google Cloud/Firebase에 등록된 Android OAuth client와 매칭
  -> 매칭 성공 시 로그인 진행
  -> 매칭 실패 시 DEVELOPER_ERROR
```

따라서 현재 구조는 다음처럼 작동한다고 보면 된다.

```text
1. 앱 코드에서 webClientId로 idToken 발급 요청
2. Google Play Services가 현재 Android 앱의 package/SHA-1 확인
3. 같은 Google/Firebase 프로젝트에 등록된 Android OAuth client와 매칭
4. 매칭되면 Google이 webClientId audience의 idToken 발급
5. 백엔드가 해당 idToken을 검증
```

즉 “web client id만 있으면 Android client id가 필요 없다”가 아니다.

```text
web client id
  -> 서버가 검증할 idToken 대상

Android client 등록
  -> 이 Android 앱이 로그인 요청을 해도 되는 앱인지 확인
```

둘 다 필요하다.

## app client id로 OAuth 할 수도 있나?

일부 흐름에서는 Android client id가 idToken audience가 되도록 설계할 수도 있다. 하지만 서버가 idToken을 받아 검증하는 일반적인 구조에서는 Web client id를 server client id로 사용하는 것이 표준적이다.

Android client id를 앱 코드에 억지로 넣는 방식은 권장하지 않는다.

이유:

1. React Native Google Sign-In의 `webClientId`는 서버 인증용 idToken 발급 대상이다.
2. 백엔드는 현재 Web client id를 audience로 검증하고 있다.
3. Android client id를 넣으면 백엔드 audience 검증 기준도 바꿔야 한다.
4. Android/iOS/Web을 함께 운영할 때 server audience를 Web client id로 통일하는 편이 관리가 쉽다.

정리:

```text
현재 구조에서는 Android client id를 코드에 넣지 않는다.
Android client id는 Google/Firebase 콘솔에 등록한다.
앱 코드에는 Web client id를 넣는다.
백엔드는 Web client id audience를 검증한다.
```

## FCM Android 설정 순서

FCM은 Firebase Console에서 Android app을 추가하고 `google-services.json`을 앱에 넣어야 한다.

공식 문서 기준 핵심:

- Firebase Android setup 문서: `google-services.json`을 다운로드해 app-level root directory에 넣는다.
- Google Services Gradle plugin은 `google-services.json`을 Android resource로 처리한다.
- FCM Android guide를 따라 client app을 설정한 뒤 server에서는 Admin SDK 또는 FCM API로 메시지를 보낸다.

## 1. Firebase Console 접속

접속:

```text
https://console.firebase.google.com/
```

선택:

```text
기존 SwimPulse Google Cloud 프로젝트와 연결된 Firebase 프로젝트
```

없다면 새 Firebase 프로젝트를 만들되, 가능하면 현재 Google OAuth web client가 있는 Google Cloud 프로젝트와 같은 프로젝트를 쓰는 편이 관리가 쉽다.

## 2. Android 앱 추가

Firebase Console:

```text
Project settings
-> General
-> Your apps
-> Add app
-> Android 선택
```

입력:

```text
Android package name:
com.swimpulsemobile

App nickname:
SwimPulse Mobile

Debug signing certificate SHA-1:
5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

SHA-1은 선택처럼 보일 수 있지만 Google 로그인까지 같이 쓸 거면 반드시 넣는 편이 좋다.

## 3. google-services.json 다운로드

Firebase Android 앱 추가 과정에서 다음 파일을 다운로드한다.

```text
google-services.json
```

파일 위치:

```text
mobile/android/app/google-services.json
```

주의:

```text
mobile/android/google-services.json 이 아님
mobile/android/app/google-services.json 이 맞음
```

`google-services.json`은 앱 식별 설정 파일이다. 서버 관리자 권한을 가진 secret은 아니다.

반대로 백엔드의 Firebase Admin SDK service account JSON은 secret이다.

```text
모바일 앱:
mobile/android/app/google-services.json

백엔드 서버:
firebase-adminsdk.json
```

## 4. Google Services Gradle plugin 추가

현재 `mobile/android/build.gradle`은 `buildscript dependencies` 방식이다.

project-level:

```text
mobile/android/build.gradle
```

`dependencies`에 추가:

```gradle
dependencies {
    classpath("com.android.tools.build:gradle")
    classpath("com.facebook.react:react-native-gradle-plugin")
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin")
    classpath("com.google.gms:google-services:4.5.0")
}
```

app-level:

```text
mobile/android/app/build.gradle
```

파일 상단의 기존 plugin 선언 아래에 추가:

```gradle
apply plugin: "com.android.application"
apply plugin: "org.jetbrains.kotlin.android"
apply plugin: "com.facebook.react"
apply plugin: "com.google.gms.google-services"
```

주의: `google-services.json`이 없는 상태에서 plugin만 먼저 적용하면 빌드가 실패할 수 있다. 파일을 먼저 받은 뒤 Gradle 설정을 추가하는 편이 안전하다.

현재 프로젝트에는 다음 설정을 적용했다.

```text
mobile/android/build.gradle
  -> classpath("com.google.gms:google-services:4.5.0")

mobile/android/app/build.gradle
  -> apply plugin: "com.google.gms.google-services"
```

이 설정이 없으면 `google-services.json` 파일이 있어도 Firebase 기본 앱 리소스가 생성되지 않아 다음 에러가 날 수 있다.

```text
No Firebase App '[DEFAULT]' has been created - call firebase.initializeApp()
```

## 5. 앱 재빌드

native 설정과 Firebase 설정 파일이 바뀌었으므로 앱을 다시 빌드한다.

```powershell
cd mobile
npm run android
```

문제가 있으면 clean 후 재빌드:

```powershell
cd mobile/android
.\gradlew.bat clean
cd ..
npm run android
```

Windows에서 React Native 0.86 New Architecture 빌드 중 `Filename longer than 260 characters`가 발생하면 프로젝트 경로가 너무 깊은 것이다. 임시로 짧은 드라이브를 만들어 빌드할 수 있다.

```powershell
subst S: "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse"
cd S:\mobile\android
.\gradlew.bat :app:assembleDebug -PreactNativeArchitectures=x86_64
```

에뮬레이터가 `x86_64` system image라면 위처럼 `x86_64`만 빌드해도 된다. 실제 Android 휴대폰에 직접 설치할 때는 대부분 `arm64-v8a`가 필요하므로 전체 ABI 또는 해당 ABI로 다시 빌드해야 한다.

## 6. FCM token 등록 흐름 확인

현재 모바일 코드는 FCM token을 받아 백엔드에 등록하는 흐름이 있다.

관련 파일:

```text
mobile/src/notifications/push.ts
mobile/src/api/client.ts
```

백엔드 호출:

```text
POST /api/notifications/device-tokens
```

요청 형태:

```json
{
  "deviceId": "android-...",
  "fcmToken": "...",
  "platform": "ANDROID"
}
```

전체 흐름:

```text
앱 로그인
  -> Keychain에 SwimPulse JWT 저장
  -> Firebase Messaging에서 FCM token 발급
  -> backend에 device token 등록
  -> 알림 발생
  -> NotificationWorker가 Firebase Admin SDK로 FCM 전송
  -> Android 앱 push 수신
```

## 7. Firebase Console에서 Cloud Messaging 확인

Firebase Console:

```text
Project settings
-> Cloud Messaging
```

확인할 것:

| 항목 | 설명 |
|---|---|
| Android app 등록 여부 | `com.swimpulsemobile` 앱이 있어야 함 |
| Cloud Messaging API | 보통 Firebase 프로젝트에서 사용 가능해야 함 |
| Sender ID / Project ID | `google-services.json`에 포함됨 |

백엔드 FCM 전송은 이미 서버의 Firebase Admin SDK 설정을 사용한다.

서버 설정:

```text
SWIMPULSE_FIREBASE_SERVICE_ACCOUNT_PATH=/run/secrets/firebase-adminsdk.json
```

이 서버용 service account JSON은 앱에 넣으면 안 된다.

## 8. 테스트 순서

1. 백엔드 실행

```powershell
docker compose up -d --build
```

2. Metro 실행

```powershell
cd mobile
npm start
```

3. Android 앱 실행

```powershell
cd mobile
npm run android
```

4. Google 로그인
5. 설정 화면에서 푸시 token 등록
6. 테스트 알림 요청
7. Android emulator에서 push 수신 확인

## 자주 나는 오류

### Google 로그인 `DEVELOPER_ERROR`

원인:

```text
Android package name + SHA-1이 Google/Firebase에 등록되지 않았거나 불일치
```

해결:

```text
Google Cloud/Firebase에 Android OAuth client 추가
package name=com.swimpulsemobile
SHA-1=5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

### FCM token 발급 실패

가능 원인:

1. `google-services.json`이 없음
2. 파일 위치가 `mobile/android/app/`가 아님
3. Google Services Gradle plugin 미적용
4. 앱을 재빌드하지 않음
5. emulator에 Google Play Services가 없는 system image 사용

현재 emulator는 Google Play Store image를 쓰는 편이 좋다.

### 앱 코드 수정 후 반영이 안 됨

```powershell
cd mobile
npm start -- --reset-cache
```

또는 앱 데이터 초기화:

```powershell
adb shell pm clear com.swimpulsemobile
```

## 요약

```text
Google 로그인:
  앱 코드에는 Web client id 사용
  Android client id는 package/SHA-1로 Google/Firebase에 등록

FCM:
  Firebase Console에 Android app 추가
  google-services.json 다운로드
  mobile/android/app/google-services.json 배치
  Google Services Gradle plugin 추가
  앱 재빌드

백엔드:
  mobile idToken 검증 후 SwimPulse JWT 발급
  FCM Admin SDK로 등록된 Android token에 push 전송
```

## 참고 문서

- Firebase Android setup: https://firebase.google.com/docs/android/setup
- Firebase Cloud Messaging: https://firebase.google.com/docs/cloud-messaging
- Firebase Cloud Messaging Android get started: https://firebase.google.com/docs/cloud-messaging/android/get-started
- Google Services Gradle Plugin: https://developers.google.com/android/guides/google-services-plugin
- React Native Google Sign-In setup: https://react-native-google-signin.github.io/docs/setting-up/get-config-file
- Firebase Google Sign-In on Android: https://firebase.google.com/docs/auth/android/google-signin
