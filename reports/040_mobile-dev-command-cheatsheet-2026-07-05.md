# 040 모바일 개발 명령어 치트시트

작성일: 2026-07-05

## 목적

React Native 모바일 개발을 처음 할 때 자주 쓰는 명령어와 개념을 SwimPulse 프로젝트 기준으로 정리한다.

SwimPulse 모바일 앱 위치:

```text
mobile/
```

Android emulator에서 백엔드는 다음 주소로 접근한다.

```text
PC localhost:8080
-> Android emulator에서는 http://10.0.2.2:8080
```

현재 모바일 API base URL:

```text
mobile/src/api/client.ts
API_BASE_URL = http://10.0.2.2:8080
```

## 기본 실행 순서

터미널은 보통 3개를 쓰면 편하다.

### 1. 백엔드 실행

프로젝트 루트에서 실행한다.

```powershell
docker compose up -d --build
```

백엔드 상태 확인:

```powershell
curl http://localhost:8080/actuator/health
```

정상 예시:

```json
{"status":"UP"}
```

### 2. Metro 서버 실행

Metro는 React Native JavaScript bundle을 만들어주는 개발 서버다.

```powershell
cd mobile
npm start
```

이 터미널은 끄지 말고 유지한다.

### 3. Android 앱 설치/실행

다른 터미널에서 실행한다.

```powershell
cd mobile
npm run android
```

이 명령은 Android debug APK를 빌드하고 emulator에 설치한 뒤 앱을 실행한다.

## Metro 서버란?

React Native는 웹처럼 브라우저가 HTML/CSS/JS를 직접 받는 구조가 아니다.

```text
Android native app
  -> React Native runtime
  -> JavaScript bundle 실행
```

개발 모드에서는 Metro가 JS bundle을 만든다.

```text
index.js
  -> App.tsx
  -> src/*
  -> Metro bundle
  -> Android app에서 실행
```

`index.js`는 앱의 JavaScript 시작점이다.

## 자주 쓰는 명령어

### emulator 연결 확인

```powershell
adb devices
```

정상 예시:

```text
List of devices attached
emulator-5554   device
```

`adb`가 인식되지 않으면 Android SDK platform-tools 경로를 PATH에 추가해야 한다.

보통 경로:

```text
C:\Users\kimsunjae\AppData\Local\Android\Sdk\platform-tools
```

### 앱 reload

Metro 터미널 또는 emulator focus 상태에서:

```text
R 두 번
```

또는 Dev Menu:

```text
Ctrl + M
```

그 뒤 `Reload` 선택.

### Metro cache reset

웹의 Ctrl+F5처럼 JS bundle cache가 의심될 때 사용한다.

```powershell
cd mobile
npm start -- --reset-cache
```

또는:

```powershell
cd mobile
npx react-native start --reset-cache
```

### 앱 데이터 초기화

로그인 토큰, 앱 저장 데이터 등을 지우고 싶을 때:

```powershell
adb shell pm clear com.swimpulsemobile
```

앱은 유지되고 데이터만 삭제된다.

### 앱 완전 삭제

```powershell
adb uninstall com.swimpulsemobile
```

다시 설치:

```powershell
cd mobile
npm run android
```

### 현재 설치된 앱 실행

앱이 이미 설치되어 있고 다시 실행만 하고 싶을 때:

```powershell
adb shell monkey -p com.swimpulsemobile 1
```

## 빌드 명령어

### Debug APK 빌드

개발용 APK를 만든다.

```powershell
cd mobile/android
.\gradlew.bat assembleDebug
```

결과물:

```text
mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

### Debug APK 설치

```powershell
adb install -r mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

`-r`은 기존 앱 위에 재설치한다는 뜻이다.

### Release AAB 빌드

Google Play 업로드용 AAB를 만든다.

```powershell
cd mobile/android
.\gradlew.bat bundleRelease
```

결과물:

```text
mobile/android/app/build/outputs/bundle/release/app-release.aab
```

주의: Play Store에 올리려면 release signing 설정이 필요하다. 현재 debug keystore는 개발용이다.

## APK와 AAB 차이

| 구분 | 설명 |
|---|---|
| APK | Android 기기에 직접 설치 가능한 파일 |
| AAB | Google Play에 업로드하는 앱 번들 |
| IPA | iOS 앱 배포 파일 |

개발 중에는 보통 APK를 쓴다.

```text
npm run android
-> debug APK 빌드
-> emulator에 설치
```

Google Play 배포는 AAB를 쓴다.

```text
app-release.aab
-> Google Play Console 업로드
-> Google Play가 기기별 APK 생성
```

AAB는 사용자가 직접 설치하는 파일이 아니라 Google Play가 APK를 만들기 위한 원본 묶음이다.

## Google 로그인 관련 명령어

### debug SHA-1 확인

Google Android OAuth client 등록에 필요하다.

```powershell
cd mobile/android
.\gradlew.bat signingReport
```

현재 debug 값:

```text
Package name: com.swimpulsemobile
SHA-1: 5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

Google 로그인에서 `DEVELOPER_ERROR`가 나면 보통 이 값이 Google Cloud/Firebase에 등록되지 않은 것이다.

Google Cloud 또는 Firebase에 Android OAuth client를 만들 때:

```text
Package name: com.swimpulsemobile
SHA-1: 5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

## FCM 푸시 관련 체크

FCM을 Android에서 제대로 쓰려면 Firebase Android app 설정이 필요하다.

필요 파일:

```text
mobile/android/app/google-services.json
```

현재 이 파일이 없으면 FCM token 발급이나 푸시 수신은 정상 동작하지 않을 수 있다.

파일을 추가한 뒤에는 native 설정이 바뀐 것이므로 앱을 다시 빌드한다.

```powershell
cd mobile
npm run android
```

## 로그 확인

### Android logcat 전체 보기

```powershell
adb logcat
```

너무 많이 나오므로 보통 필터를 건다.

### React Native 로그 위주 확인

```powershell
adb logcat *:S ReactNative:V ReactNativeJS:V
```

### 특정 문자열 검색

PowerShell에서:

```powershell
adb logcat | Select-String "Google"
```

또는:

```powershell
adb logcat | Select-String "SwimPulse"
```

## 테스트 명령어

### Jest

```powershell
cd mobile
npm test -- --runInBand
```

### ESLint

```powershell
cd mobile
npm run lint
```

### TypeScript 확인

현재 `package.json`에 typecheck script가 없으면 직접 실행한다.

```powershell
cd mobile
npx tsc --noEmit
```

## 자주 겪는 문제와 해결

### 1. `adb` 명령어가 안 됨

원인:

```text
Android SDK platform-tools가 PATH에 없음
```

해결:

```text
C:\Users\kimsunjae\AppData\Local\Android\Sdk\platform-tools
```

를 사용자 PATH에 추가한다. 새 터미널을 열어야 반영된다.

### 2. 앱이 예전 JS를 물고 있는 느낌

해결 순서:

```text
1. R 두 번으로 reload
2. npm start -- --reset-cache
3. adb shell pm clear com.swimpulsemobile
4. adb uninstall com.swimpulsemobile 후 npm run android
```

### 3. 백엔드 API가 연결 안 됨

PC에서:

```powershell
curl http://localhost:8080/actuator/health
```

앱에서는:

```text
http://10.0.2.2:8080
```

를 사용해야 한다.

에뮬레이터에서 `localhost`는 PC가 아니라 emulator 자기 자신이다.

### 4. Google 로그인 `DEVELOPER_ERROR`

원인:

```text
Android package name / SHA-1이 Google OAuth client와 불일치
```

해결:

```text
Google Cloud 또는 Firebase에 Android OAuth client 추가
package name: com.swimpulsemobile
SHA-1: 5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25
```

### 5. native dependency 추가 후 이상함

예:

```text
react-native-firebase
google-signin
keychain
geolocation
```

이런 native dependency나 Android 설정이 바뀌면 JS reload만으로는 부족하다.

다시 빌드한다.

```powershell
cd mobile
npm run android
```

그래도 안 되면:

```powershell
cd mobile/android
.\gradlew.bat clean
cd ..
npm run android
```

## 추천 개발 루틴

일반적인 하루 개발 시작:

```powershell
# terminal 1
docker compose up -d --build

# terminal 2
cd mobile
npm start

# terminal 3
cd mobile
npm run android
```

코드 수정 중:

```text
저장
-> Fast Refresh 확인
-> 이상하면 R 두 번
```

캐시가 이상할 때:

```powershell
cd mobile
npm start -- --reset-cache
```

로그인이 꼬였을 때:

```powershell
adb shell pm clear com.swimpulsemobile
```

완전 초기화:

```powershell
adb uninstall com.swimpulsemobile
cd mobile
npm run android
```

## 짧은 결론

처음에는 다음만 기억하면 된다.

```text
Metro 켜기:
cd mobile
npm start

앱 설치/실행:
cd mobile
npm run android

앱 새로고침:
R 두 번

강한 캐시 리셋:
npm start -- --reset-cache

앱 데이터 삭제:
adb shell pm clear com.swimpulsemobile

APK 생성:
cd mobile/android
.\gradlew.bat assembleDebug

Play Store용 AAB 생성:
cd mobile/android
.\gradlew.bat bundleRelease
```
