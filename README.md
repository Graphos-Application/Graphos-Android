# Graphos (GalleryAI1)

온디바이스 AI로 사진을 자동 태깅하고 자연어로 검색하는 안드로이드 갤러리 앱.
서버 호출도, 기기별 AI 기능 승인(AICore/Gemini Nano)도 필요 없이 전부 로컬에서 추론한다.

## 주요 기능

- **자동 태깅** — MobileCLIP(이미지-텍스트 임베딩)과 YOLO11n(객체 탐지)을 결합한 하이브리드 방식으로 사진마다 태그를 자동 부여
- **자연어 검색** — 한국어 문장으로 검색하면 태그 매칭(1차)과 CLIP 벡터 유사도 검색(2차)을 함께 사용해 결과를 찾음
- **날짜 표현 이해** — "작년 여름", "2024년" 같은 상대/절대 날짜 표현을 인식해 기간으로 필터링
- **라벨 직접 편집** — 자동 태그가 틀렸거나 부족하면 사용자가 직접 추가/수정 가능
- **완전 오프라인** — 모든 추론이 기기 내에서 실행되며 네트워크나 계정이 필요 없음

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어/UI | Kotlin, XML 레이아웃(Compose 미사용) |
| AI 추론 | ONNX Runtime Mobile — MobileCLIP(vision + text), YOLO11n(COCO 80종), 자체 구현 CLIP 토크나이저(BPE) |
| 번역 | ML Kit Translate — 한국어 검색어를 CLIP 벡터 검색용 영문으로 변환 |
| 저장소 | Room — `PhotoAlbum` / `TagMaster` / `PhotoTagMap` 3테이블 구조 |
| 이미지 로딩 | Glide, PhotoView |
| 빌드 | AGP 9.1.1, KSP, minSdk 26 / targetSdk·compileSdk 37 |

## 아키텍처

```
사진 스캔 → MobileCLIP 임베딩 + YOLO11n 객체 탐지
          → 두 결과를 교차 검증해 태그 확정(TagRepository)
          → Room DB에 저장(PhotoAlbum/TagMaster/PhotoTagMap)

검색어 입력 → 날짜 표현 분리(DateExpressionParser)
           → Tier1: 태그 사전 매칭(SearchSynonyms)
           → Tier2: 실패 시 CLIP 벡터 검색(검색어는 번역 후 비교)
           → 두 결과를 합쳐 표시
```

자세한 설계 이력과 실험 기록은 [`tech_stack.txt`](tech_stack.txt), [`PROJECT_NOTES.md`](PROJECT_NOTES.md), [`tools/README.md`](tools/README.md)에 정리되어 있다.

## 빌드

```
./gradlew assembleDebug
```

- JDK 21 필요 (`JAVA_HOME`을 Android Studio 번들 JBR로 지정 권장)
- 온디바이스 모델 파일(`app/src/main/assets/mobileclip/`, `app/src/main/assets/yolo/`)은 **Git LFS**로 관리됨 — clone 후 `git lfs pull` 필요
- APK는 `arm64-v8a`, `x86_64`만 빌드(32비트 아키텍처 제외로 용량 절감)

## 정확도 측정 도구

`tools/` 디렉터리에 태깅 정확도(recall/precision)와 검색 실패 사례를 실측하는 파이썬 하네스가 있다. Room DB를 직접 열어 사람이 만든 정답지와 비교하는 방식으로, 체감이 아닌 실제 수치로 회귀를 잡아낸다. 자세한 사용법은 [`tools/README.md`](tools/README.md) 참고.

## 프로젝트 배경

교내 프로젝트 기획서 `Graphos.pdf`(프로젝트 루트)를 기반으로 만든 iOS 사진 앱 스타일의 갤러리 + AI 자동 태깅/검색 안드로이드 앱이다.
