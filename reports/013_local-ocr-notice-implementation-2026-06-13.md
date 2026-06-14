# 로컬 OCR 공지 추출 구현 보고서

## 요약

상세 공지 본문이 이미지뿐인 경우를 위해 backend에 `Tesseract CLI` 기반 로컬 OCR를 추가했습니다.

현재 동작 순서:

1. 기존 HTML 규칙 파서 실행
2. 모집 기간을 못 찾았고 `img[src]`가 있으면 OCR 실행
3. OCR 텍스트를 기존 본문 텍스트 뒤에 붙여 같은 규칙 파서 재실행
4. 그래도 못 찾으면 `LINK_ONLY` 유지

이번 단계에서는 OpenAI fallback을 호출하지 않습니다.

## 주요 변경

- `NoticeCrawlerService`
  - `CURRENT_PARSER_VERSION`를 `3`으로 올렸습니다.
  - OCR 재시도 전용 흐름 `extractNoticeDetail(...)`를 추가했습니다.
  - 저장되는 `rawText`는 OCR 성공 시 `[OCR IMAGE TEXT]` 구간이 포함된 합성 텍스트가 됩니다.
- `NoticeImageOcrService`
  - OCR 추출 인터페이스를 추가했습니다.
- `TesseractNoticeImageOcrService`
  - 이미지 다운로드
  - TLS fallback
  - 임시 파일 저장
  - `tesseract` CLI 실행
  - timeout / fail-open 처리
  - 최대 이미지 수 제한
- OCR 대상 이미지 선택 보정
  - `.tbody` 안의 이미지 우선
  - `smartEditor/upload` 이미지 우선
  - `svg`, `logo`, `avatar`, `barcode` 계열 제외
- OCR temp cleanup 로그 추가
  - 임시 디렉터리 삭제 성공/실패를 로그로 남깁니다.
- `Dockerfile`
  - runtime image에 `tesseract-ocr`, `tesseract-ocr-kor`, `tesseract-ocr-eng`를 설치합니다.
- 설정
  - `swimpulse.notice.ocr.*` 속성을 추가했습니다.
- 문서
  - README와 `backend/.env.example`에 OCR 설정과 운영 주의사항을 반영했습니다.

## 테스트

자동 테스트:

- HTML만으로 기간이 잡히면 OCR를 호출하지 않음
- HTML 실패 후 OCR 텍스트로 기간을 추출함
- OCR 예외 발생 시 초기 HTML 결과 유지
- OCR 결과가 비어 있으면 초기 HTML 결과 유지
- OCR 서비스가 `max-images` 제한만큼만 처리함
- Spring context 포함 전체 backend 테스트 통과

실행 확인:

- `backend\\gradlew test`
- `docker compose build backend`

## 수동 확인 순서

1. `docker compose up -d --build backend`
2. 이미지 기반 상세 공지가 있는 수영장으로 `공지 확인` 실행
3. 결과의 기간이 추출되는지 확인
4. backend 로그에서 `Notice OCR started`, `Notice OCR completed`, `Notice OCR retry completed` 확인
5. OpenAI 관련 공지 추출 로그가 나오지 않는지 확인

## 제한 사항

- OCR 대상은 `img[src]`만입니다.
- PDF, canvas, CSS background image는 아직 처리하지 않습니다.
- Docker 기준으로 맞췄고, 로컬 Windows 직접 실행 환경에서 `tesseract` 설치는 별도입니다.
