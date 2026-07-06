# 039 Mobile Metro / Google Login Troubleshooting

작성일: 2026-07-05

## 목적

Android Studio emulator에서 SwimPulse React Native 앱을 실행할 때 필요한 Metro 서버의 역할을 정리하고, Google 로그인 실패 원인과 해결 절차를 기록한다.

## Metro 서버가 필요한 이유

React Native 앱은 웹 프론트처럼 브라우저가 HTML/CSS/JS를 직접 받아 실행하는 구조가 아니다.

Android 앱 기준으로는 다음 두 부분으로 나뉜다.

```text
Android native app
  -> React Native runtime
  -> JavaScript bundle 실행
```

개발 모드에서는 이 JavaScript bundle을 Metro 서버가 실시간으로 만들어서 앱에 전달한다.

```text
index.js
  -> App.tsx
  -> src/*
  -> Metro가 JS bundle 생성
  -> Android app이 bundle 로딩
```

즉 `index.js`는 React Native 앱의 JavaScript 시작점이다. 현재 `mobile/index.js`는 `App.tsx`를 등록한다.

```text
index.js
  -> AppRegistry.registerComponent(...)
  -> App.tsx 실행
```

개발 중에는 다음처럼 실행한다.

```powershell
cd mobile
npm start
```

이 명령이 Metro 서버를 켠다. 앱을 처음 설치하거나 native 설정이 바뀐 경우에는 별도 터미널에서 Android 빌드를 실행한다.

```powershell
cd mobile
npm run android
```

한 번 앱이 설치된 뒤에는 대부분 Metro를 켜둔 상태에서 `App.tsx`나 `src/*`를 수정하면 Fast Refresh로 화면이 갱신된다.

## 현재 모바일 구현 범위

현재 `mobile/`은 React Native CLI + TypeScript 기반이다.

구현된 주요 범위:

| 영역 | 구현 상태 |
|---|---|
| 앱 구조 | React Navigation native stack, Home / MyPage / Settings |
| API 클라이언트 | `mobile/src/api/client.ts`, Android emulator 기준 `http://10.0.2.2:8080` |
| 인증 저장 | `react-native-keychain`에 access token 저장 |
| Google 로그인 | `@react-native-google-signin/google-signin`으로 idToken 획득 후 `/api/auth/mobile/google` 호출 |
| 홈 | 수영장 목록, 이벤트 목록, 내 구독 상태 조회 |
| 위치 검색 | 위치명 검색, 현재 위치 기반 주변 수영장 조회 |
| 시설 추가 요청 | 위치 후보를 사용자 요청으로 생성 |
| 공지 확인 | pool notice scan 호출, OCR 진행 상태 반영 |
| 구독 | 기간 선택, 과거 기간 보정, 구독 생성/해제 |
| 마이페이지 | 내 구독, 알림 목록, 읽음 처리, 구독 기간 수정 |
| 푸시 설정 | FCM token 등록/해제, 테스트 알림 요청 |

백엔드 쪽 모바일 인증도 이미 구현되어 있다.

| 백엔드 구성 | 상태 |
|---|---|
| `POST /api/auth/mobile/google` | 구현됨 |
| Google idToken 검증 | Google JWK + issuer/audience/email_verified 검증 |
| 모바일 JWT 응답 | `MobileLoginResponse(accessToken, user)` |
| Bearer JWT 인증 | `JwtAuthenticationFilter`가 `Authorization: Bearer` 지원 |
| 기존 웹 인증 | HttpOnly cookie JWT 흐름 유지 |

## Google 로그인 실패 현상

에뮬레이터에서 Google 로그인 시 다음 에러가 발생했다.

```text
Google 로그인에 실패했습니다.
Error: DEVELOPER_ERROR
```

이 에러는 백엔드 `/api/auth/mobile/google`까지 도달하기 전에 Android Google Sign-In SDK 단계에서 발생한다.

즉 현재 문제는 Spring Boot OAuth endpoint 문제가 아니라 Android 앱의 Google OAuth 설정 문제다.

## 원인

Android Google Sign-In은 앱의 package name과 서명 인증서 SHA-1이 Google Cloud 또는 Firebase에 등록된 Android OAuth client와 일치해야 한다.

현재 앱 설정:

| 항목 | 값 |
|---|---|
| Android package name | `com.swimpulsemobile` |
| Debug keystore SHA-1 | `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25` |
| Web client id | `829584550774-17n75ueed6o958h3078dljisuoi1g33r.apps.googleusercontent.com` |

SHA-1은 다음 명령으로 확인했다.

```powershell
cd mobile/android
.\gradlew.bat signingReport
```

확인된 debug signing report:

```text
Variant: debug
Store: mobile/android/app/debug.keystore
Alias: androiddebugkey
SHA1: 5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

## 해결 방법

Google Cloud Console 또는 Firebase Console에서 Android OAuth client를 추가해야 한다.

### Google Cloud Console 기준

1. Google Cloud Console 접속
2. 현재 웹 OAuth client가 있는 같은 프로젝트 선택
3. `APIs & Services` -> `Credentials`
4. `Create Credentials` -> `OAuth client ID`
5. Application type: `Android`
6. Package name:

```text
com.swimpulsemobile
```

7. SHA-1:

```text
5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

8. 저장
9. 몇 분 기다린 뒤 앱에서 다시 로그인

주의: Android OAuth client id 자체를 앱 코드에 넣는 것이 아니다. 앱 코드는 idToken 발급을 위해 web client id를 계속 사용한다.

### Firebase Console 기준

Firebase를 통해 관리한다면:

1. Firebase Console 접속
2. 프로젝트 선택
3. Authentication에서 Google sign-in provider 활성화
4. Project Settings -> Android app 추가
5. Android package name:

```text
com.swimpulsemobile
```

6. SHA-1 fingerprint 추가

```text
5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

7. `google-services.json` 다운로드
8. 파일 위치:

```text
mobile/android/app/google-services.json
```

현재 repo에는 `google-services.json`이 없다. FCM까지 완전히 검증하려면 이 파일이 필요하다.

## 이번 코드 보강

`mobile/src/auth/googleAuth.ts`에서 `DEVELOPER_ERROR`를 별도로 해석하도록 수정했다.

이전에는 native error가 그대로 표시되어 원인을 알기 어려웠다.

```text
Error: DEVELOPER_ERROR
```

수정 후에는 다음 정보를 앱에서 안내한다.

```text
Google Android OAuth 설정이 앱 서명 정보와 맞지 않습니다.
Google Cloud 또는 Firebase에 Android OAuth client를 추가하고
package name=com.swimpulsemobile,
SHA-1=5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
를 등록해 주세요.
```

## 테스트 결과

실행한 검증:

```powershell
cd mobile
npm test -- --runInBand
npm run lint -- --quiet
```

결과:

| 검증 | 결과 |
|---|---|
| Jest | PASS, `renders correctly` |
| ESLint | PASS |
| Android signing report | PASS, debug SHA-1 확인 |

Google 로그인 실제 성공은 Google Cloud/Firebase Console에 Android OAuth client를 추가한 뒤 재검증해야 한다.

## 다음 확인 순서

1. Google Cloud 또는 Firebase에 Android OAuth client 추가
2. 앱 재실행

```powershell
cd mobile
npm run android
```

3. 로그인 재시도
4. 로그인 성공 후 `/api/me` 호출 확인
5. FCM까지 확인하려면 `google-services.json` 추가 후 다시 빌드

```powershell
cd mobile
npm run android
```

## 정리

이번 로그인 실패는 코드에서 idToken을 서버로 보내는 단계의 문제가 아니라, Android Google Sign-In SDK가 앱을 Google OAuth client와 매칭하지 못해 발생한 설정 문제다.

개발용 emulator/debug APK 기준으로는 `com.swimpulsemobile`과 debug SHA-1을 Android OAuth client에 등록하면 해결된다.
