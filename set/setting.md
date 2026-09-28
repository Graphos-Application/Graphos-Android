# Project Specification: On-Device Smart Gallery System

## 1. Project Background & Decisions
- **Target OS:** Android (API 26+)
- **Storage:** Jetpack Room (SQLite 기반 M:N 관계형 데이터베이스 구조)
- **AI Core Decisions:**
  - **Deprecated:** ML Kit Image Labeling (기본 라벨 수 한계 및 낮은 인식 정확도로 제거)
  - **Deprecated:** Gemini Nano AICore / VLM (Feature ID 636 롤아웃 서버 제약, 모델 용량 1GB 이상에 따른 다운로드/RAM OOM 리스크로 배제)
  - **Adopted:** **MobileCLIP (ONNX / TFLite)** 단독 채택 (30~50MB 경량화, 제로샷 태깅 및 벡터 검색 지원)
  - **Retained:** ML Kit Translate (온디바이스 한-영 번역 유지) + `SearchSynonyms` (동의어 매핑 및 폴백 유지)

---

## 2. System Architecture & Tech Stack

| Component | Technology | Responsibility |
| :--- | :--- | :--- |
| **Vision Inference** | MobileCLIP (ONNX Runtime Mobile / TFLite) | 이미지 512차원 임베딩 추출 및 제로샷 태그 판별 |
| **Text Translation** | ML Kit Translate (`com.google.mlkit:translate`) | 한글 쿼리/태그의 온디바이스 영문 번역 |
| **Database** | Android Jetpack Room (KSP) | 3-Table M:N 관계형 매핑 및 텍스트/인덱스 검색 |
| **Text Embedding (Optional)** | MobileCLIP Text Encoder | 사용자 정의 태그 동적 추가 시 텍스트 임베딩 생성 |

---

## 3. Database Schema (Room)

### 3.1 Entity Design
1. `PhotoAlbum`
   - `photoUri` (String, PK): 미디어 저장소 고유 URI
   - `dateAdded` (Long): 추가 일자
   - `dateModified` (Long): 수정 일자
2. `TagMaster`
   - `tagId` (Long, PK, AutoGenerate): 태그 식별자
   - `tagNameEn` (String, Indexed): 영문 태그명 (MobileCLIP 매칭 기준)
   - `tagNameKo` (String): 한글 태그명
   - `embedding` (ByteArray or String, Nullable): 태그 벡터값
3. `PhotoTagMap`
   - `photoUri` (String, FK -> `PhotoAlbum.photoUri`, ON DELETE CASCADE)
   - `tagId` (Long, FK -> `TagMaster.tagId`, ON DELETE CASCADE)
   - `confidence` (Float): 매칭 유사도 점수 (0.0 ~ 1.0)
   - *Composite Primary Key:* `(photoUri, tagId)`

---

## 4. End-to-End Pipeline Workflow

### 4.1 Background Sync (Daily 00:00 / Batch Process)
1. `ContentResolver`를 통해 로컬 갤러리 변경 사항(Delta) 스캔.
2. 신규 사진에 대해 MobileCLIP Vision Encoder를 실행하여 이미지 임베딩 추출.
3. 사전 정의된 `TagMaster` 임베딩 목록과 코사인 유사도(Cosine Similarity) 연산.
4. 임계값(Threshold) 이상의 상위 태그(Top-K)를 Room `PhotoTagMap`에 삽입.

### 4.2 Search Flow (Low Latency / Graceful Degradation)
1. **Query Input:** 사용자 한글 검색어 입력 (예: `"군복"`).
2. **Translation:** `ML Kit Translate`를 거쳐 영문 변환 (예: `"military uniform"`).
3. **Tier 1 (Index SQL Search - Primary):**
   - Room DB에서 조인 쿼리 수행:
     ```sql
     SELECT p.* FROM photo_album p
     INNER JOIN photo_tag_map m ON p.photoUri = m.photoUri
     INNER JOIN tag_master t ON m.tagId = t.tagId
     WHERE t.tagNameKo = :query OR t.tagNameEn = :query
     ORDER BY m.confidence DESC
     ```
   - NPU/AI 추론 없이 인덱스 조회로 수 ms 내 즉각 반환.
4. **Tier 2 (Vector Fallback - Secondary):**
   - Tier 1 결과가 없을 경우에 한해 MobileCLIP Text Encoder로 검색어 임베딩 생성 후 사진 임베딩과 유사도 비교 정렬.

---

## 5. Development Tasks Required
1. **Dependencies Cleanup:**
   - Remove `com.google.mlkit:image-labeling`
   - Keep `com.google.mlkit:translate`
   - Add `androidx.room:room-*` (via KSP)
   - Add ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android`) or TFLite dependencies
2. **Room Implementation:**
   - Create Entities (`PhotoAlbum`, `TagMaster`, `PhotoTagMap`), DAOs, and Database class.
3. **MobileCLIP Inference Manager:**
   - Implement image pre-processing (Resize, Normalize) and embedding extraction logic.
   - Implement cosine similarity matching against pre-computed tag vectors.
4. **Gallery Sync Service:**
   - Connect permission verification with `MediaStore` query and Room synchronization.