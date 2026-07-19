# 058 모바일 구독 기간 날짜·시간 선택 UX 개선

작성일: 2026-07-20

## 문제

모바일 구독 기간 수정 화면은 사용자가 다음 값을 직접 문자열로 입력해야 했다.

```text
날짜: 2026-07-20
시: 12
분: 00
```

입력 형식을 기억해야 하고 날짜 오타, 존재하지 않는 날짜, 시작·종료 시각 혼동이 발생하기 쉬운 구조였다.

## 개선 내용

`@react-native-community/datetimepicker`를 추가해 Android와 iOS의 네이티브 날짜·시간 선택기를 사용하도록 변경했다.

기간 수정 화면은 다음처럼 동작한다.

1. `날짜` 영역을 누르면 달력 선택기가 열린다.
2. `시간` 영역을 누르면 시계 선택기가 열린다.
3. 선택 결과는 `2026년 7월 20일`, `오후 12:00`처럼 읽기 쉬운 형식으로 표시된다.
4. 오전 9시, 오전 10시, 정오, 오후 6시, 오후 11시, 마감 23:59 빠른 선택은 유지한다.
5. 저장 시 선택값을 ISO-8601 UTC로 변환해 기존 API에 전달한다.
6. 종료 시각이 시작 시각보다 빠르면 기존처럼 저장을 막고 안내한다.

화면과 서버 변환 기준은 `Asia/Seoul`이다.

## 의존성

다음 네이티브 모듈이 추가됐다.

```text
@react-native-community/datetimepicker@9.1.0
```

React Native autolinking이 Android의 `RNDateTimePickerPackage`를 정상 인식하는 것을 확인했다.

## 테스트

다음 검증을 모두 통과했다.

```text
npx tsc --noEmit
npm run lint
npm test -- --runInBand
android/gradlew.bat :app:compileDebugKotlin
```

Jest에서는 네이티브 선택기 모듈을 mock 처리해 앱 렌더링 테스트가 실제 네이티브 환경 없이도 실행되도록 했다.

## C:\sp 빌드 절차

이번 변경은 `package.json`과 `package-lock.json`이 바뀐 네이티브 의존성 변경이다. 소스를 `C:\sp\mobile`로 복사한 뒤 반드시 의존성을 다시 설치해야 한다.

```powershell
robocopy "C:\Users\kimsunjae\Desktop\NewFolder\Java_INTELLIJ\SwimPulse\mobile" "C:\sp\mobile" /MIR /XD node_modules android\.gradle android\app\.cxx android\app\build android\build .gradle build .cxx

cd C:\sp\mobile
npm install

cd C:\sp\mobile\android
.\gradlew.bat assembleRelease
```

기존 앱에 덮어쓸 때는 대상 기기를 지정한다.

```powershell
adb -s <device-id> install -r "C:\sp\mobile\android\app\build\outputs\apk\release\app-release.apk"
```

이번에는 JavaScript UI만 바뀐 것이 아니라 네이티브 모듈이 추가됐으므로 Metro Fast Refresh만으로는 DateTimePicker를 사용할 수 없다. 새 APK를 한 번 재빌드하고 설치해야 한다.
