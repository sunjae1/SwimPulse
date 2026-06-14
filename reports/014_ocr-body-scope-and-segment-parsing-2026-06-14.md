# OCR 본문 범위 축소 및 줄/블록 파싱 개선 보고서

## 요약

공지 상세 페이지의 이미지 OCR 결과가 나와도 기간 구조화 저장으로 이어지지 않던 문제를 개선했습니다.

이번 변경의 핵심은 다음 4가지입니다.

1. 상세 HTML 1차 파싱 텍스트를 `document.text()` 전체가 아니라 본문 범위 기준으로 축소
2. OCR 결과를 전체 blob 1회 파싱 대신 줄/블록 단위로 재파싱
3. OCR 텍스트 전처리 추가
4. OCR 다운로드/선택/매칭 로그 강화

## 구현 내용

### 1. HTML 1차 텍스트 범위 축소

- `NoticeCrawlerService`에 `buildNoticeBodyText(...)`를 추가했습니다.
- 이제 상세 공지 1차 텍스트는 `noticeContentScope(document).text()` 기반으로 생성합니다.
- 목적:
  - 메뉴
  - 헤더/푸터
  - 본문 밖의 `환불안내` 같은 주변 텍스트
  를 1차 파싱 입력에서 최대한 제거하기 위함입니다.

### 2. OCR 결과 줄/블록 단위 재파싱

- `extractNoticeDetail(...)`에서 OCR 성공 후 더 이상 전체 OCR 텍스트 blob을 그대로 `extractByRule(...)`에 넣지 않습니다.
- 대신:
  - OCR 텍스트 전처리
  - 후보 줄/블록 생성
  - 줄별/근접 블록별 `findMatchedPeriods(...)`
  순서로 재파싱합니다.
- 이 방식은 한 줄에 있는 `재등록`, `반변경`, `신규` 기간을 더 안정적으로 잡도록 돕습니다.

### 3. OCR 텍스트 전처리

- `_`를 공백으로 정리
- 줄바꿈 정리
- `6월 22일(월) 08:00 ~ 6월 24일(수) 15:00` 같은 시간 포함 범위를
  `6월 22일(월) ~ 6월 24일(수)` 형태로 축약
- `(을` 같은 OCR 오탈자를 `(일)` 쪽으로 보정

이 전처리는 현재 갈매멀티스포츠센터 사례처럼 시간 정보가 날짜 사이에 끼어 있는 OCR 결과를 기간 정규식이 읽을 수 있게 하는 목적입니다.

### 4. 로그 강화

추가된 주요 로그:

- OCR 후보 이미지 선택
  - `Notice OCR candidate images selected`
- OCR 이미지 다운로드 시작
  - `Notice OCR downloading image`
- OCR 이미지 다운로드 완료
  - `Notice OCR image downloaded`
- OCR 이미지별 OCR 결과 길이
  - `Notice OCR image processed`
- OCR 줄/블록 파싱 준비
  - `Notice OCR parsing prepared`
- 실제 기간이 잡힌 OCR 줄/블록
  - `Notice OCR segment matched`
- OCR temp cleanup 성공/실패
  - `Notice OCR temp cleanup completed`
  - `Notice OCR temp cleanup incomplete`

## 기대 효과

- 본문 외 잡음 텍스트 때문에 전체 파싱이 무력화되는 경우 감소
- OCR 결과가 나왔는데도 `LINK_ONLY`로 끝나는 케이스 감소
- 어떤 이미지 URL을 OCR했고, 어떤 OCR 줄에서 기간을 잡았는지 추적 가능

## 테스트

추가/검증한 테스트:

- 본문 범위 텍스트 추출 시 nav/footer 텍스트 제외
- OCR 이미지 우선순위 선택 유지
- OCR 결과의 시간 포함 날짜 범위를 줄 단위 재파싱으로 구조화 추출
- OCR 실패 / 빈 OCR 결과 시 기존 fallback 유지
- OCR temp cleanup 성공 검증

실행:

- `backend\\gradlew test --tests com.swimpulse.notice.NoticeCrawlerServiceTests --tests com.swimpulse.notice.TesseractNoticeImageOcrServiceTests`
- `backend\\gradlew test`

## 남은 관찰 포인트

- 실제 운영 로그에서 `Notice OCR segment matched`가 원하는 줄을 잘 잡는지 확인 필요
- OCR 결과 품질이 매우 낮은 이미지에 대해서는 추가 오탈자 보정 규칙이 더 필요할 수 있음
- 현재는 동기 OCR이므로 응답 시간이 긴 사이트는 여전히 느릴 수 있음
