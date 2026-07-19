# 049 모바일 실폰 설치, 운영 API 연결, FCM 검증 순서

작성일: 2026-07-16

## 목적

SwimPulse 모바일 앱을 Android 실폰에 설치하고, 로컬 백엔드가 아니라 운영 백엔드 `https://api.sunjae.link`를 바라보게 한 뒤 실제 FCM 푸시까지 검증하는 절차를 정리한다.

## 현재 코드 기준

모바일 API 주소는 다음처럼 분리했다.

| 빌드/실행 방식 | API 주소 |
|---|---|
| `npm run android` 개발 실행 | `http://10.0.2.2:8080` |
| `:app:installDebug` debug 앱 | `http://10.0.2.2:8080` |
| `assembleRelease` release APK | `https://api.sunjae.link` |

위치는 다음이다.

```text
mobile/src/api/client.ts
```

즉, 실폰에 서비스처럼 설치해서 테스트하려면 `release APK`를 만들면 된다.

## 전체 흐름

```text
1. 운영 백엔드 HTTPS 확인
2. Firebase Android 설정 확인
3. Google OAuth Android client 확인
4. 모바일 release APK 빌드
5. 실폰에 APK 설치
6. 앱에서 API 상태 확인
7. Google 로그인
8. FCM 기기 등록
9. 웹 또는 앱에서 테스트 알림 발송
10. 실폰 OS 알림 수신 확인
```

## 1. 운영 백엔드 확인

브라우저나 PowerShell에서 확인한다.

```powershell
curl.exe -i https://api.sunjae.link/actuator/health
```

정상이면 대략 다음처럼 나온다.

```json
{"status":"UP"}
```

이게 안 되면 모바일 앱도 운영 API에 붙을 수 없다.

## 2. Firebase Android 설정 확인

Android 앱에는 Firebase 설정 파일이 필요하다.

```text
mobile/android/app/google-services.json
```

Firebase Console에서 Android 앱이 아래 package name으로 등록되어 있어야 한다.

```text
com.swimpulsemobile
```

확인 위치:

```text
Firebase Console
-> Project settings
-> General
-> Your apps
-> Android app
```

`google-services.json`을 바꿨다면 다시 빌드해야 한다. 앱 실행 중에 파일만 바꿔서는 반영되지 않는다.

## 3. Google 로그인 설정 확인

앱 코드는 Google ID token 발급을 위해 web client id를 사용한다.

```text
mobile/src/auth/googleAuth.ts
```

다만 Android 앱 자체도 Google Cloud/Firebase에 등록되어 있어야 한다.

필요한 값:

| 항목 | 값 |
|---|---|
| package name | `com.swimpulsemobile` |
| SHA-1 | 현재 APK에 서명한 keystore의 SHA-1 |
| web client id | 앱 코드에서 사용하는 Google web client id |

현재 `release`도 임시로 debug keystore로 서명하고 있다. 그래서 개인 실폰 테스트는 가능하지만, Play Store 배포 전에는 반드시 별도 release keystore를 만들어야 한다.

release keystore를 새로 만들면 해야 할 일:

```text
1. release keystore SHA-1 확인
2. Firebase/Google Cloud Android OAuth client에 SHA-1 추가
3. google-services.json 다시 다운로드
4. mobile/android/app/google-services.json 교체
5. APK/AAB 재빌드
```

## 4. Release APK 빌드

Windows의 React Native Android release 빌드는 경로가 길면 CMake/Ninja/Metro가 실패할 수 있다. 현재 원본 repo 경로가 길기 때문에, 실폰 설치용 APK는 `mobile` 폴더만 짧은 빌드 전용 경로로 복사해서 만든다.

```text
원본 코드: C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile
빌드 경로: C:\sp\mobile
```

### 4-1. mobile 폴더를 짧은 경로로 복사

PowerShell에서 실행한다.

```powershell
mkdir C:\sp -Force

robocopy "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile" "C:\sp\mobile" /MIR /XD node_modules android\.gradle android\app\.cxx android\app\build android\build .gradle build .cxx
```

`node_modules`, `.gradle`, `.cxx`, `build`는 빌드 산출물/캐시라 복사하지 않는다. 그래서 복사 직후 용량이 작아지는 것은 정상이다.

필수 파일이 복사됐는지 확인한다.

```powershell
Test-Path C:\sp\mobile\package.json
Test-Path C:\sp\mobile\android\app\google-services.json
Test-Path C:\sp\mobile\src\api\client.ts
```

셋 다 `True`가 나와야 한다.

### 4-2. 의존성 설치

처음 빌드하거나 `package.json`/`package-lock.json`이 바뀐 경우 실행한다.

```powershell
cd C:\sp\mobile
npm install
```

앱 코드만 수정한 경우에는 매번 `npm install`을 다시 할 필요는 없다.

### 4-3. Release APK 빌드

```powershell
cd C:\sp\mobile\android
.\gradlew.bat --stop
.\gradlew.bat assembleRelease
```

생성 파일:

```text
C:\sp\mobile\android\app\build\outputs\apk\release\app-release.apk
```

이 APK는 `__DEV__ === false`이므로 운영 API `https://api.sunjae.link`를 호출한다.

### 4-4. 코드 수정 후 다시 빌드할 때

원본 repo에서 모바일 코드를 수정했다면 먼저 다시 복사한다.

```powershell
robocopy "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile" "C:\sp\mobile" /MIR /XD node_modules android\.gradle android\app\.cxx android\app\build android\build .gradle build .cxx
```

의존성 변경이 없으면 바로 빌드한다.

```powershell
cd C:\sp\mobile\android
.\gradlew.bat assembleRelease
```

## 5. 실폰에 APK 설치

### 방법 A. adb 설치

#### 1. SuperDisplay ADB 충돌 확인

이 PC에는 `SuperDisplay`가 설치되어 있으며, 해당 PC 서비스가 구버전 ADB server를 별도로 실행할 수 있다. Android SDK ADB와 SuperDisplay ADB의 버전이 다르면 다음 오류가 반복된다.

```text
adb server version (40) doesn't match this client (41)
protocol fault (couldn't read status): connection reset
```

이는 APK, 에뮬레이터, 실폰의 문제가 아니라 PC에서 두 ADB server가 기본 포트 `5037`을 두고 충돌하는 문제다. SuperDisplay PC 프로그램을 최신 버전으로 업데이트하는 것이 근본 해결책이다.

업데이트 전 ADB 설치 작업을 할 때는 **관리자 PowerShell**에서 SuperDisplay 서비스를 임시로 멈춘다.

```powershell
Stop-Service -Name SuperDisplay -Force

taskkill /F /IM adb.exe

Start-Sleep -Seconds 2

$androidAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $androidAdb start-server

& $androidAdb devices
```

이후 연결할 기기가 모두 아래처럼 `device` 상태인지 확인한다.

```text
R3CN2018C3J     device
emulator-5554   device
```

실폰이 `offline`이면 USB를 다시 연결하고, 실폰에 표시되는 USB 디버깅 RSA 승인 창을 허용한다. 필요하면 개발자 옵션에서 `USB 디버깅 승인 취소` 후 다시 연결한다.

SuperDisplay가 필요해 다시 사용할 때는 아래처럼 서비스를 시작한다.

```powershell
Start-Service -Name SuperDisplay
```

다시 시작하면 ADB 충돌이 재발할 수 있으므로, 앱 설치/디버깅 작업 중에는 서비스를 중지한 상태로 둔다.

#### 2. USB 디버깅 설정

실폰에서 먼저 설정한다.

```text
설정
-> 휴대전화 정보
-> 빌드 번호 여러 번 터치
-> 개발자 옵션 활성화
-> USB 디버깅 ON
```

PC에 연결 후 확인한다.

```powershell
$androidAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $androidAdb devices
```

기기가 보이면 설치한다.

```powershell
& $androidAdb -s emulator-5554 install -r "C:\sp\mobile\android\app\build\outputs\apk\release\app-release.apk"

& $androidAdb -s R3CN2018C3J install -r "C:\sp\mobile\android\app\build\outputs\apk\release\app-release.apk"
```

에뮬레이터와 실폰이 동시에 연결된 경우에는 `-s <device-id>`를 반드시 넣는다. `adb install`만 실행하면 대상이 둘 이상이라 설치가 실패하거나 의도하지 않은 대상에 설치될 수 있다.

서명 충돌이 나면 기존 앱을 지우고 다시 설치한다.

```powershell
& $androidAdb -s R3CN2018C3J uninstall com.swimpulsemobile
& $androidAdb -s R3CN2018C3J install "C:\sp\mobile\android\app\build\outputs\apk\release\app-release.apk"
```

`adb install -r`은 같은 `applicationId`인 `com.swimpulsemobile` 앱을 덮어쓴다. 같은 앱이 두 개 생기지는 않는다.

### 방법 B. APK 파일을 폰으로 옮겨 설치

APK를 카카오톡, USB, 구글 드라이브 등으로 옮겨 설치할 수도 있다.

이 경우 Android에서 다음 권한을 요구할 수 있다.

```text
알 수 없는 앱 설치 허용
```

개인 테스트는 가능하지만, 실제 사용자 배포는 Google Play가 정석이다.

## 6. 앱에서 API 연결 확인

앱 실행 후 `설정` 또는 API 연결 섹션에서 다음이 보이면 운영 API를 보고 있는 것이다.

```text
API: https://api.sunjae.link
```

`API 상태 확인`을 눌러 `UP`이 나오는지 확인한다.

## 7. Google 로그인 확인

앱에서 Google 로그인을 누른다.

성공 흐름:

```text
앱 Google Sign-In
-> Google idToken 발급
-> POST https://api.sunjae.link/api/auth/mobile/google
-> 백엔드가 SwimPulse JWT 발급
-> 앱 secure storage에 accessToken 저장
-> 이후 API 요청은 Authorization: Bearer <JWT>
```

실패하면 우선 확인할 것:

| 증상 | 확인 |
|---|---|
| `DEVELOPER_ERROR` | Android OAuth client의 package name/SHA-1 불일치 |
| 백엔드 401 | Google web client id/audience 설정 확인 |
| `/api/me` 실패 | 모바일 JWT 저장 또는 Authorization header 확인 |

## 8. FCM 기기 등록

앱에서 로그인 후:

```text
푸시 알림
-> 기기 등록
```

성공하면 백엔드 `user_devices`에 대략 다음 형태로 저장된다.

```text
platform = ANDROID
active = true
fcm_token = 앱에서 발급받은 Android FCM token
```

DB 확인 예시:

```sql
select id, user_id, platform, active, left(fcm_token, 20), updated_at
from user_devices
order by updated_at desc
limit 10;
```

## 9. 테스트 알림 발송

방법은 둘 중 하나다.

### 방법 A. 웹에서 테스트 알림

웹 로그인 후 테스트 알림을 보내면 같은 사용자에게 등록된 WEB/ANDROID device token 전체로 발송된다.

```text
Vercel 웹
-> 푸시 테스트
-> 백엔드 notification 생성
-> FCM 발송
-> 실폰 Android 알림 수신
```

### 방법 B. 앱에서 테스트 알림

앱에서:

```text
푸시 알림
-> 테스트 알림
```

단, 테스트 알림은 사용자가 구독한 모집 기간이 있어야 정상 발송된다.

## 10. 알림 수신 확인

Android 앱 상태에 따라 동작이 다르다.

| 앱 상태 | 동작 |
|---|---|
| 앱이 백그라운드/종료 상태 | OS 알림 센터에 푸시 표시 |
| 앱이 포그라운드 상태 | React Native foreground handler가 앱 안에서 표시 |
| 알림 클릭 | 앱이 열리고 알림 상세 모달/화면으로 연결 |

포그라운드 알림이 OS 알림 센터에 항상 남는 것은 아니다. Android에서 foreground 메시지를 OS 알림으로도 남기려면 별도 local notification 라이브러리 연동이 필요하다.

## 운영 체크리스트

| 항목 | 상태 |
|---|---|
| `https://api.sunjae.link/actuator/health` 정상 | 확인 필요 |
| `mobile/android/app/google-services.json` 존재 | 확인 필요 |
| Firebase Android package name `com.swimpulsemobile` | 확인 필요 |
| Google Android OAuth SHA-1 등록 | 확인 필요 |
| release APK 빌드 | 확인 필요 |
| 실폰 설치 | 확인 필요 |
| Google 로그인 | 확인 필요 |
| `user_devices.platform=ANDROID` 등록 | 확인 필요 |
| 웹/앱 테스트 알림 수신 | 확인 필요 |

## 지금 단계에서 권장하는 테스트 순서

가장 빠른 검증 순서는 다음이다.

```text
1. backend 운영 health 확인
2. 원본 `mobile`을 `C:\sp\mobile`로 robocopy
3. 필요 시 `C:\sp\mobile`에서 `npm install`
4. `C:\sp\mobile\android`에서 release APK 빌드
5. `adb install -r`로 실폰 설치
6. 앱에서 API 주소가 `https://api.sunjae.link`인지 확인
7. API 상태 확인
8. Google 로그인
9. 기기 등록
10. 웹에서 테스트 알림 발송
11. 실폰 알림 수신 확인
```

이 순서대로 되면 “운영 백엔드 + 실폰 Android 앱 + 실제 FCM” 흐름은 연결된 것이다.

## 부록 A. 에뮬레이터에 로컬 백엔드용 앱 설치

운영용 release APK를 설치한 뒤에도 에뮬레이터에서는 로컬 백엔드를 바라보는 개발용 앱으로 다시 설치할 수 있다.

현재 API 주소 선택 코드는 다음 기준으로 동작한다.

```text
Debug 앱: __DEV__ = true  -> http://10.0.2.2:8080
Release 앱: __DEV__ = false -> https://api.sunjae.link
```

Android 에뮬레이터에서 `10.0.2.2`는 Windows 개발 PC의 `localhost`를 뜻한다. 따라서 에뮬레이터에서 `http://10.0.2.2:8080`을 호출하면 PC에서 실행 중인 로컬 Spring Boot `localhost:8080`에 연결된다.

### A-1. 로컬 백엔드 실행

프로젝트 루트에서 개발 환경을 실행한다.

```powershell
cd C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse
.\scripts\dev.ps1
```

최소한 로컬 백엔드가 `localhost:8080`에서 실행 중이어야 한다.

```powershell
curl.exe http://localhost:8080/actuator/health
```

정상이면 다음 응답이 나온다.

```json
{"status":"UP"}
```

### A-2. 최신 모바일 코드를 짧은 경로로 동기화

React Native Android 빌드는 Windows의 긴 경로 때문에 CMake/Ninja가 실패할 수 있으므로 `C:\sp\mobile`에서 빌드한다.

```powershell
robocopy "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile" "C:\sp\mobile" /MIR /XD node_modules android\.gradle android\app\.cxx android\app\build android\build .gradle build .cxx
```

`/MIR`은 대상 폴더를 원본과 동일하게 맞추므로 대상 경로가 `C:\sp\mobile`인지 확인하고 실행한다.

### A-3. JavaScript 의존성 설치

처음 복사했거나 `package.json` 또는 `package-lock.json`이 변경됐다면 실행한다.

```powershell
cd C:\sp\mobile
npm install
```

`App.tsx` 같은 JavaScript/TypeScript 코드만 변경됐다면 매번 `npm install`을 다시 할 필요는 없다.

### A-4. 에뮬레이터 확인

Android Studio에서 에뮬레이터를 켠 뒤 확인한다.

```powershell
adb devices
```

예시:

```text
List of devices attached
emulator-5554   device
```

실폰과 에뮬레이터가 동시에 연결되어 있다면 이후 명령에 `-s emulator-5554`를 지정해야 엉뚱한 기기에 설치되지 않는다.

### A-5. Debug 앱 빌드 및 에뮬레이터 설치

```powershell
cd C:\sp\mobile\android
.\gradlew.bat :app:installDebug
```

연결된 기기가 여러 대라서 설치 대상 오류가 나면 APK를 만든 뒤 에뮬레이터를 명시한다.

```powershell
cd C:\sp\mobile\android
.\gradlew.bat assembleDebug

adb -s emulator-5554 install -r "C:\sp\mobile\android\app\build\outputs\apk\debug\app-debug.apk"
```

같은 application id와 서명을 사용하는 기존 앱이 있으면 `-r`이 앱을 덮어쓴다. 앱이 두 개 생기는 명령이 아니다.

### A-6. Metro 서버 실행

Debug 앱은 JavaScript bundle을 Metro 개발 서버에서 받는다. 원본 코드를 계속 수정하고 바로 확인하려면 별도 PowerShell에서 원본 프로젝트 기준으로 Metro를 실행한다.

```powershell
cd C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile
npm start
```

에뮬레이터와 Metro 연결이 불안하면 다음 포트 연결도 설정한다.

```powershell
adb -s emulator-5554 reverse tcp:8081 tcp:8081
```

이 상태에서는 `App.tsx`, 스타일, 일반 TypeScript 로직을 수정하면 Fast Refresh로 빠르게 반영된다.

### A-7. 네이티브 의존성 변경 시 주의

다음 파일이나 Android 네이티브 구성이 바뀌면 Metro 재시작이나 앱 새로고침만으로는 반영되지 않는다.

```text
package.json에 React Native native module 추가/삭제
android/ 아래 Gradle 또는 Manifest 변경
google-services.json 변경
앱 아이콘/네이티브 리소스 변경
```

예를 들어 `@react-native-community/datetimepicker`를 추가한 뒤 기존 APK를 그대로 실행하면 JavaScript에는 `RNCDatePicker` 호출이 있지만 설치된 앱의 native binary에는 모듈이 없어 다음 오류가 발생한다.

```text
TurboModuleRegistry.getEnforcing(...): 'RNCDatePicker' could not be found
```

이 경우 A-2부터 다시 진행하고 `:app:installDebug`로 Debug 앱을 재빌드·재설치해야 한다.

### A-8. 로컬 API 연결 확인

앱의 설정 화면에서 다음 주소가 보이면 로컬 Debug 앱이 맞다.

```text
API: http://10.0.2.2:8080
```

`https://api.sunjae.link`가 보이면 에뮬레이터에 아직 release 앱이 설치된 것이다. A-5의 Debug 설치를 다시 실행한다.

### 로컬 개발 반복 흐름

| 변경 내용 | 필요한 작업 |
|---|---|
| `App.tsx`, 스타일, TypeScript 로직 | Metro Fast Refresh 또는 앱 Reload |
| `package.json`의 JavaScript 전용 패키지 | `npm install`, Metro 재시작 |
| DatePicker, Firebase 등 native module | `npm install`, Debug 앱 재빌드·재설치 |
| Android Gradle/Manifest/Firebase 설정 | Debug 앱 재빌드·재설치 |
| 운영 환경 최종 확인 | `assembleRelease`, release APK 설치 |

정리하면 평소 화면 개발은 Metro와 Fast Refresh로 확인하고, 네이티브 의존성이 바뀐 경우에만 APK를 다시 설치하면 된다.
