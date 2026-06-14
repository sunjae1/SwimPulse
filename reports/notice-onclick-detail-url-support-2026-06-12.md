# 공지 상세 URL 복원 개선 보고서

## 배경

구리도시공사 계열 공지 게시판은 상세 공지 링크를 일반적인 `href`로 주지 않고 아래처럼 제공합니다.

```html
<a href="#none" onclick="fn_view(6593);">
  [갈매멀티스포츠센터] 2026년 6월 종목별 회원모집 안내
</a>
```

기존 로직은 `href`만 기준으로 상세 공지 URL을 만들었기 때문에, 이런 구조에서는 실제 상세 페이지로 진입하지 못했습니다.

그 결과:

- `notice-sources/reverify`에서는 상세 후보를 제대로 만들지 못해 `VERIFIED` 근거가 약해졌고
- 사용자 `공지 확인`에서도 상세 공지 분석까지 이어지지 못할 수 있었습니다.

## 이번 변경

### 1. `onclick fn_view(seq)` 기반 상세 URL 복원 추가

수정 파일:

- `backend/src/main/java/com/swimpulse/notice/NoticeCrawlerService.java`

추가한 동작:

- `href`가 정상 링크면 기존처럼 그대로 사용
- `href`가 `#none`, `#`, `javascript:` 같은 placeholder면 `onclick`을 확인
- `fn_view(6593)` 형태를 파싱해 `seq=6593` 추출
- 목록 URL 또는 hidden input에서 `bbsId` 추출
- 최종 상세 URL을 `/bbsArticle/view.do?seq=...&bbsId=...` 형태로 복원

즉 이제는 아래 같은 anchor도 상세 공지 후보가 됩니다.

```html
<a href="#none" onclick="fn_view(6593);">
  [갈매멀티스포츠센터] 2026년 6월 종목별 회원모집 안내
</a>
```

### 2. 후보 판정에 `onclick` 문자열도 포함

상세 공지 후보 여부를 판단할 때 기존에는 사실상 `제목 + href`만 봤습니다.

이번에는:

- `제목 + href + onclick`

을 함께 보고 `6월`, `회원`, `모집` 같은 신호를 평가하도록 보강했습니다.

### 3. 검증과 실제 스캔이 같은 URL 복원 로직을 공유

이번 수정의 중요한 점은 URL 복원 로직이 한 군데에만 쓰이는 땜빵이 아니라는 점입니다.

- `notice-sources/reverify`
  - 상세 후보 1개 이상 발견 시 `VERIFIED` 근거가 됨
- 사용자 `공지 확인`
  - 같은 후보 URL로 바로 상세 페이지 fetch 및 공지 저장/기간 파싱 시도

즉 source 검증과 실제 공지 스캔이 같은 상세 URL 복원 로직을 공유합니다.

## 테스트

수정 파일:

- `backend/src/test/java/com/swimpulse/notice/NoticeCrawlerServiceTests.java`

추가한 테스트:

1. `resolvesFnViewDetailUrlFromPlaceholderAnchor`
   - `href="#none" onclick="fn_view(6593)"` 구조에서
   - `https://www.guriuc.or.kr/sports/bbsArticle/view.do?seq=6593&bbsId=NOTICE`
   - 로 복원되는지 확인

2. `detailNoticeCandidatesSupportFnViewOnclickLinks`
   - 위 구조의 anchor가 실제로 detail candidate로 잡히는지 확인

실행 결과:

```text
backend ./gradlew test
BUILD SUCCESSFUL
```

## 기대 효과

- 구리도시공사처럼 `fn_view(seq)` 기반 게시판에서도 상세 공지 후보를 만들 수 있음
- `pool_notice_sources`가 `INACTIVE`로 잘못 떨어질 가능성을 줄임
- 사용자 `공지 확인` 시 실제 상세 공지 분석까지 이어질 가능성이 높아짐

## 이번 단계에서 남겨둔 한계

이번 작업은 상세 공지 **진입 경로 복원**까지 해결한 단계입니다.

아직 남아 있는 한계:

- 상세 페이지 본문이 이미지-only인 경우
- 현재 rule parser는 `document.text()` 기반
- OpenAI fallback도 이미지 URL 문자열만 전달하고 실제 이미지 OCR/vision 처리는 하지 않음

즉 다음 단계에서는:

- OCR 추가
  또는
- OpenAI vision 입력 방식 도입

이 필요합니다.
