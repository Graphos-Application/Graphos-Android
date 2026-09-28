# GalleryAI1 (Graphos) 작업 정리

> 학교 과제 기획서 "Graphos" PDF(`Graphos.pdf`, 프로젝트 루트) 기반으로 만드는
> iOS 사진앱 스타일 갤러리 + AI 자동 태깅 · 자연어 검색 안드로이드 앱.
> 최종 갱신: 2026-09-05 (검색 버그 3건 수정 + `app-debug-v3.apk` 배포함, **실기기 테스트 아직 안 함**)

## 1. 기술 스택

- **언어/구조**: Kotlin, minSdk 26, **XML 레이아웃**(Compose 아님)
- **패키지**: `com.example.galleryai1`
- **온디바이스 AI 4종**
  - ML Kit **Image Labeling**(`image-labeling`) — Open Images ~400종, APK 번들, 완전 오프라인
  - **EfficientNet-Lite0**(TFLite, ImageNet 1000종) 커스텀 모델 — `app/src/main/assets/efficientnet-lite0.tflite` 직접 번들
  - ML Kit **GenAI**(Gemini Nano / AICore) 2종 — **이 기기(S24 Ultra 한국 모델)에선 구글 서버측 entitlement 문제로 UNAVAILABLE 확정**(아래 6번 참고)
    - `genai-image-description` — 사진 → 캡션 (고정 용도 API)
    - `genai-prompt` (`com.google.mlkit:genai-prompt:1.0.0-beta4`) — 자유 형식 프롬프트 → 텍스트. suspend/Flow 기반
  - ML Kit **Translation**(`translate:17.0.3`, 신규) — Gemini Nano가 막힌 기기에서도 동작하는 2번째 AI 계층, AICore 불필요
- **저장소**: `LabelStore` — SharedPreferences에 JSON blob 한 덩어리로 저장(기획서가 요구한 "RDB 3테이블" 구조는 **미착수**)
- **빌드**: `JAVA_HOME=D:\AndroidStudio\jbr`(JDK 21) 지정 후 `gradlew.bat`. **PowerShell 툴로만 실행**(Bash/Git Bash로 `./gradlew` 직접 실행 금지 — MSYS의 `cygpath` 변환이 gradlew의 빈 CLASSPATH를 깨뜨려 조용히 실패함)
- **APK 용량**: `translate` 추가로 65MB→136MB 폭증 → `defaultConfig.ndk.abiFilters`를 arm64-v8a(실기기)+x86_64(로컬 AVD `Pixel_6_Pro_2`)만 남겨서 81MB로 절감

## 2. AI 태깅/검색 4단 구조

| 모듈 | 역할 | 동작 시점 | 비고 |
|---|---|---|---|
| `AutoTagger.kt` | ML Kit 기본 라벨링 | 그리드 스크롤 중 자동, 가벼움 | 완전 오프라인. **"person"류 라벨 자체가 없음**(구글이 프라이버시 이유로 뺌) |
| `PreciseTagger.kt` | EfficientNet-Lite0 세부 분류 | 라벨 편집 다이얼로그·검색 시만(스크롤 중 안 돎) | ImageNet 1000종엔 "military uniform"(군복) 등은 있지만 범용 "person" 클래스는 없음 |
| `AiCaptioner.kt` | Gemini Nano 이미지 캡션 | AICore 지원 기기만 | 이 기기에서 UNAVAILABLE 확정(6번 참고) |
| `AiPromptTagger.kt` | 검색어→개념그룹 (Gemini Nano) | 검색 시, 아래 3종과 병렬 확인 | 이 기기에서 UNAVAILABLE 확정 |
| `TranslationTagger.kt` (신규, 09-05) | 검색어→개념그룹 (온디바이스 번역) | 검색 시, Gemini Nano 실패 시 대체 | **모든 안드로이드 기기에서 동작**, AICore 불필요 |

**검색어 AI 처리 우선순위**: `AiPromptTagger(Gemini Nano)` → `TranslationTagger(번역)` → `SearchSynonyms(수동 사전)`. 앞이 실패/미지원이면 조용히 다음 단계로 폴백, 검색이 막히는 일은 없음.

`TranslationTagger` 상세:
- ML Kit Translation(한→영)으로 검색어의 "의미 있는 토큰"(불용어 제거 후)만 번역해서 원문+번역+사전매칭 결과를 합쳐 그룹 구성
- 모델(~30MB)은 첫 검색 시 **설치 다이얼로그 없이 조용히 백그라운드 다운로드**(가볍게 유지)
- 단어 단위 LRU 캐시(`translationCache`, 200개)로 반복 검색 재번역 방지
- 순수 병합 로직 `buildTranslationGroup()`은 object 밖 top-level 함수로 분리(테스트 가능하게, 기존 컨벤션과 동일)

`AiPromptTagger` 상세: 시스템 프롬프트로 "찾을 대상만 한 줄에 한 개념씩" 요청 → `parsePromptTagResponse()`(top-level 순수함수)로 파싱 → `SearchSynonyms.matchDictionaryTerms`로 사전 보강. `INFERENCE_TIMEOUT_MS` 6초, `promptCache`(LRU 50개).

## 3. 검색 파이프라인

`MainActivity.performSearch` → `searchWithCaptionSupport`:

1. `DateExpressionParser.kt`로 상대/절대 시간 표현("재작년에", "지난 여름", "N년 전", "2024년" 등)을 날짜 범위로 먼저 걷어냄(EXIF `DATE_TAKEN` 필터)
2. 날짜를 걷어내고 남은 텍스트가 **불용어뿐이면**(`SearchSynonyms.hasMeaningfulContent()` 판별, 09-05 신규) 조건으로 취급 안 하고 날짜 필터 결과만 바로 렌더링 — "2024년 사진 찾아줘"가 0건으로 나오던 버그를 이걸로 고침
3. 남은 텍스트를 AI(Gemini Nano→번역→사전 순, 위 2번 참고)로 **개념 그룹**으로 분해 — **그룹 간 AND, 그룹 내 동의어는 OR**
4. 매칭 대상 haystack = AI 라벨 + 정밀 라벨 + 사용자 라벨 + Gemini 캡션(있으면)
5. 분석 끝난 사진부터 즉시 표시, 나머지는 점진적 추가 — 이때 **검색 세대(`searchGeneration`) 토큰**으로 이전 검색/취소된 검색의 뒤늦은 콜백이 화면을 덮어쓰지 못하게 막음(09-05 신규, 아래 6번 버그 3 참고)
6. 날짜 필터만 있고 텍스트 조건이 없으면 AI 분석 자체를 생략하고 즉시 렌더링(성능 단축 경로)

## 4. 사진 삭제 감지

- `LabelStore.pruneDeleted()` + `onResume`에서 조용히 백그라운드 재조회, 화면 갱신은 검색 중이 아닐 때만 `GalleryAdapter.updateItems()`(DiffUtil, 스크롤 유지)로 반영. (초기 버전이 `setupRecyclerView()`를 재사용해서 스크롤 리셋/검색결과 소실 회귀를 냈던 걸 수정한 최종 형태 — 자세한 경위는 memory 참고.)
- 순수 함수(`computeStaleLabelKeys`, `parsePromptTagResponse`, `buildTranslationGroup`)는 전부 object 밖 top-level로 분리하는 컨벤션 — object 안에 두면 Android 의존 정적 초기화가 같이 돌아서 순수 JUnit 테스트 불가(Robolectric 없음).

## 5. 실기기/에뮬레이터 테스트 노하우

**에뮬레이터**(AVD `Pixel_6_Pro_2`, x86_64, AICore 미지원):
- 함정1: `adb shell input text`의 공백은 `%s`로 인코딩
- 함정2: 기본 키보드가 한국어 두벌식 — 영어 대신 두벌식 로마자 시퀀스로 한글 입력
- 함정3: 검색창 포커스 자주 빠짐 — `input tap` 재포커스 → `uiautomator dump`로 확인 → Enter
- 함정4: `adb exec-out screencap`이 죽은 화면 반환할 때 있음 — `uiautomator dump`가 더 신뢰도 높음

**S24 Ultra 실기기**(SM-S928N, adb 경로 `C:\Users\kangh\AppData\Local\Android\Sdk\platform-tools\adb.exe`):
- USB 디버깅 켜면 `adb install -r`로 훨씬 빠르고, `adb logcat`으로 실제 예외까지 확인 가능(6번 참고) — 단, 사용자가 껐다 켰다 함, 꺼져있으면 아래 MTP 방식
- MTP(파일 전송) 방식: PowerShell `Shell.Application` COM으로 Download 폴더에 APK 복사
  - 함정6: 하위 폴더는 경로 문자열이 아니라 **Folder 객체**를 그대로 다음 `NameSpace()`에 체이닝
  - 함정7: `CopyHere()`와 완료 확인은 **반드시 같은 PowerShell 호출 안에서**
  - **함정8(신규, 09-05)**: 같은 파일명에 `CopyHere()`를 다시 호출해도 **조용히 덮어쓰기가 안 됨**(NOCONFIRMATION 등 강제 플래그를 다 써도 무효, `InvokeVerb("delete")`로 먼저 지우는 것도 확인 다이얼로그 때문에 자동화 호출 자체가 멈춤/타임아웃). → **매번 다른 파일명으로 복사**(`app-debug-v2.apk`, `v3.apk`, ...)하는 걸로 우회. 사용자에게 최신 버전 파일명만 안내.
- `efficientnet-lite0.tflite`는 **zip으로 취급하면 임베디드 라벨 텍스트 파일을 직접 추출**할 수 있음(`Copy-Item ... .zip; Expand-Archive`) — 모델이 어떤 라벨을 실제로 갖고 있는지 확인할 때 유용(예: "military uniform" 존재 확인, "person" 부재 확인)

## 6. 발견·수정된 버그 (전체, 최신순)

1. **[09-05] 검색 콜백 경쟁 상태**: 검색을 취소하거나 새로 검색해도 이전 검색의 비동기 사진 분석 콜백이 나중에 도착해서 화면을 덮어씀("취소하면 사진이 다시 사라짐", "군복 검색이 깜빡이다 결과없음으로 끝남"). → `searchGeneration` 토큰 추가, `performSearch`/`exitSearchMode`에서 증가시키고 `renderResults()` 등에서 최신 세대인지 확인 후 아니면 무시.
2. **[09-05] "군복" 검색 안 됨**: AI 문제가 아니라 `SearchSynonyms` 사전에 한글 매핑이 없었던 것(PreciseTagger는 이미 "military uniform"을 정확히 잡고 있었음, tflite 임베디드 라벨 파일로 확인). → 사전에 "군복"/"유니폼"/"제복" 추가.
3. **[09-05] "2024년 사진 찾아줘" 안 됨**(순수 "2024년"만 입력하면 됐음): 날짜 걷어내고 남은 "사진 찾아줘"가 불용어뿐인데도 검색 조건으로 취급돼서 0건. → `SearchSynonyms.hasMeaningfulContent()` 추가.
4. **[09-05] Gemini Nano UNAVAILABLE 근본원인**: adb logcat으로 확인 결과 `GenAiException: FEATURE_NOT_FOUND: Feature 636 is not available` — AICore 자체는 정상 설치·최신 상태, **구글 서버측에서 이 기기에 genai-prompt(베타) entitlement를 아직 안 열어준 상태**. 코드/설정 문제 아님, 우리 쪽에서 더 손볼 게 없음(→ 그래서 2번 항목의 TranslationTagger를 대안으로 추가함).
5. **[09-03] 오프라인 시 검색 완전 정지**: `AiCaptioner.checkStatus()` 무응답 무한대기 → 3초 타임아웃.
6. **[09-03] 절대 연도 검색 항상 느리고 부정확**: `DateExpressionParser`에 절대 연도 규칙 추가 + 날짜 전용 쿼리 단축 경로.
7. **[09-04] onResume 회귀(스크롤 리셋/검색결과 소실)**: `onResume`이 `setupRecyclerView()`를 재사용해서 매번 새 어댑터 생성 → `GalleryAdapter.updateItems()`로 교체.
8. **[09-04] AI 검색 응답 지연**(콜드스타트): 타임아웃 8→6초 + `promptCache` LRU 캐시.

## 7. "사람" 태그가 잘 안 붙는 문제 (미해결, 보류 중)

구조적 한계로 확인됨:
- ML Kit Image Labeling(AutoTagger): 프라이버시 이유로 "Person"류 라벨 자체가 없음
- ImageNet(PreciseTagger): 범용 "person" 클래스 없음(확인함, `efficientnet-lite0.tflite`의 라벨 파일에 없음)
- Gemini Nano 캡션: 이 기기에서 UNAVAILABLE

**제안한 해결책**: ML Kit **Face Detection**(개인 식별 아니고 "얼굴 유무"만 판단, 아래 8-3번 인물인식보다 훨씬 가벼움)으로 자동 "사람" 태그 부여 — 사용자가 아직 할지 결정 안 함, 다음에 이어갈 것.

## 8. 아직 손 안 댄 것 (보류)

1. **저장 구조**: 기획서의 "RDB 3테이블" ↔ 현재는 `LabelStore` SharedPreferences JSON 단일 blob (미착수)
2. **식당/태국식당 등 업종·요리 세부 인식**: "Restaurant"까진 가능, 요리 종류 구분은 현재 모델로 불가
3. **인물/관계 인식**("아들과 찍은 사진"): 얼굴 임베딩/클러스터링 + 이름 태깅 UI 전체가 없음 — 별도 규모 작업 (7번의 단순 "사람 유무" Face Detection과는 다름)
4. **검색 트렌드/인구통계 분석 리포트**: 기술적으로 가능하나 계정 없음 + 서버/대시보드 없음 + 기획서의 "온디바이스 AI로 개인정보 보호" 철학과 배치 + 개인정보보호법 이슈 → 사용자가 "일단 보류" 결정함. 재요청 시 이 내용부터 재확인
5. PDF 목업 그대로의 온보딩 UI는 기능만 있고 디자인 미착수

## 9. 다음에 할 일

1. **S24 Ultra 재검증**(`app-debug-v3.apk`는 이미 MTP로 배포 완료, 사용자가 아직 설치·테스트 안 함):
   - "군복" 검색 정상 동작하는지
   - "2024년 사진 찾아줘" 정상 동작하는지
   - AI 검색 취소해도 전체 사진 유지되는지
   - 번역 기반 매칭이 실제로 도는지(Wi-Fi에서 몇 번 검색 후 확인)
2. "사람" 태그 문제 — Face Detection 추가할지 결정 필요
3. 위 재검증 끝나면 검색어 트렌드 리포트 기능(보류 중) 재논의
