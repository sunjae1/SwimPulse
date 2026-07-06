# 042 Mobile FCM Gradle Fix Implementation

작성일: 2026-07-06

## 목적

`mobile/android/app/google-services.json`을 추가했는데 Android emulator에서 FCM push token 등록 시 다음 오류가 발생했다.

```text
푸시 등록 실패: Error: No Firebase App '[DEFAULT]' has been created - call firebase.initializeApp()
```

이 문서는 원인과 수정 순서를 기록한다.

## 결론

`google-services.json` 파일만 넣는 것으로는 부족했다.

Android 빌드가 `google-services.json`을 읽어 Firebase 기본 앱 리소스로 변환하려면 Google Services Gradle plugin이 필요하다.

필요한 구성:

```text
1. mobile/android/app/google-services.json 배치
2. project-level Gradle에 google-services classpath 추가
3. app-level Gradle에 com.google.gms.google-services plugin 적용
4. native 앱 재빌드/재설치
```

## 왜 에러가 났나?

React Native Firebase는 Android native Firebase SDK를 사용한다.

Android native Firebase SDK는 앱 시작 시 Firebase 기본 앱 설정을 찾는다.

```text
FirebaseApp [DEFAULT]
```

이 기본 설정은 `google-services.json`을 Gradle plugin이 처리해서 생성한 Android resource에서 만들어진다.

그런데 기존 상태는 다음과 같았다.

| 항목 | 상태 |
|---|---|
| `mobile/android/app/google-services.json` | 있음 |
| `com.google.gms:google-services` classpath | 없음 |
| `com.google.gms.google-services` app plugin | 없음 |
| `:app:processDebugGoogleServices` task | 실행되지 않음 |

그래서 파일은 있어도 Firebase 설정이 APK 안에 들어가지 않았고, 런타임에서 기본 Firebase app이 없다는 오류가 발생했다.

## 수정 순서

### 1. `google-services.json` 위치 확인

사용자가 추가한 파일:

```text
mobile/android/app/google-services.json
```

이 위치가 맞다.

주의:

```text
mobile/android/google-services.json
```

이 위치가 아니다.

Google Services plugin은 app module root의 `google-services.json`을 처리한다.

## 2. project-level Gradle 수정

파일:

```text
mobile/android/build.gradle
```

수정 전:

```gradle
dependencies {
    classpath("com.android.tools.build:gradle")
    classpath("com.facebook.react:react-native-gradle-plugin")
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin")
}
```

수정 후:

```gradle
dependencies {
    classpath("com.android.tools.build:gradle")
    classpath("com.facebook.react:react-native-gradle-plugin")
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin")
    classpath("com.google.gms:google-services:4.5.0")
}
```

이 설정은 Gradle이 `com.google.gms.google-services` plugin을 찾을 수 있게 해준다.

## 3. app-level Gradle 수정

파일:

```text
mobile/android/app/build.gradle
```

수정 전:

```gradle
apply plugin: "com.android.application"
apply plugin: "org.jetbrains.kotlin.android"
apply plugin: "com.facebook.react"
```

수정 후:

```gradle
apply plugin: "com.android.application"
apply plugin: "org.jetbrains.kotlin.android"
apply plugin: "com.facebook.react"
apply plugin: "com.google.gms.google-services"
```

이 설정이 실제로 `google-services.json`을 처리한다.

## 4. 빌드 확인

처음 루트 경로에서 빌드했다.

```powershell
cd mobile/android
.\gradlew.bat :app:assembleDebug
```

이때 다음 task가 실행되는 것을 확인했다.

```text
:app:processDebugGoogleServices
```

이 task가 실행됐다는 것은 `google-services.json`을 Gradle이 읽고 Android resource로 변환했다는 뜻이다.

## 5. Windows 경로 길이 문제 발생

Firebase 설정 문제는 해결됐지만, 빌드 중 별도 문제가 발생했다.

```text
Filename longer than 260 characters
```

원인:

```text
C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile\...
```

프로젝트 경로가 너무 깊고, React Native 0.86 New Architecture가 C++ codegen/CMake 경로를 길게 만들면서 Windows 경로 제한에 걸렸다.

`newArchEnabled=false`를 시도했지만 React Native 0.82 이후로는 더 이상 지원되지 않는다는 경고가 나왔다.

```text
Setting newArchEnabled=false is not supported anymore since React Native 0.82.
The application will run with the New Architecture enabled by default.
```

따라서 New Architecture를 끄는 방식은 해결책이 아니었다.

## 6. 짧은 드라이브 경로로 우회

Windows 경로 길이 문제를 우회하기 위해 `subst`로 짧은 드라이브를 만들었다.

```powershell
subst S: "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse"
```

이후 짧은 경로에서 빌드했다.

```powershell
cd S:\mobile\android
.\gradlew.bat :app:assembleDebug -PreactNativeArchitectures=x86_64
```

`x86_64`만 빌드한 이유:

```text
Android emulator가 x86_64 system image이므로 emulator 테스트에는 x86_64 ABI만으로 충분하다.
```

빌드 결과:

```text
BUILD SUCCESSFUL
```

## 7. 왜 앱 재설치가 필요한가?

Firebase 설정은 JavaScript bundle이 아니라 Android native resource에 들어간다.

따라서 Metro reload나 Fast Refresh로는 반영되지 않는다.

반드시 native 앱을 다시 빌드하고 재설치해야 한다.

권장 순서:

```powershell
adb uninstall com.swimpulsemobile

subst S: "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse"
cd S:\mobile
npm run android
```

또는 직접 Gradle build 후 설치:

```powershell
cd S:\mobile\android
.\gradlew.bat :app:assembleDebug -PreactNativeArchitectures=x86_64

adb install -r S:\mobile\android\app\build\outputs\apk\debug\app-debug.apk
```

## 수정 파일

| 파일 | 변경 |
|---|---|
| `mobile/android/build.gradle` | Google Services plugin classpath 추가 |
| `mobile/android/app/build.gradle` | `com.google.gms.google-services` plugin 적용 |
| `reports/041_mobile-fcm-google-client-id-setup-guide-2026-07-05.md` | 실제 적용 버전과 Windows 경로 우회 방법 반영 |

## 검증 결과

확인한 것:

| 검증 | 결과 |
|---|---|
| `google-services.json` 위치 | 정상 |
| `:app:processDebugGoogleServices` 실행 | 정상 |
| 짧은 `S:` 경로 + `x86_64` 빌드 | `BUILD SUCCESSFUL` |

아직 남은 수동 확인:

```text
1. 앱을 emulator에 재설치
2. Google 로그인
3. 푸시 토큰 등록 버튼 실행
4. 백엔드 user_devices에 ANDROID token 저장 확인
5. 테스트 알림 수신 확인
```

## 다음에 같은 문제가 나면 볼 것

### `No Firebase App '[DEFAULT]' has been created`

확인 순서:

```text
1. mobile/android/app/google-services.json 존재 여부
2. mobile/android/build.gradle에 classpath("com.google.gms:google-services:...") 있는지
3. mobile/android/app/build.gradle에 apply plugin: "com.google.gms.google-services" 있는지
4. :app:processDebugGoogleServices task가 실행됐는지
5. 앱을 native 재빌드/재설치했는지
```

### `Filename longer than 260 characters`

해결:

```powershell
subst S: "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse"
cd S:\mobile\android
.\gradlew.bat :app:assembleDebug -PreactNativeArchitectures=x86_64
```

장기 해결:

```text
프로젝트를 더 짧은 경로로 이동
예: C:\dev\SwimPulse
```

## 참고 문서

- Google Services Gradle Plugin: https://developers.google.com/android/guides/google-services-plugin
- Firebase Android setup: https://firebase.google.com/docs/android/setup
