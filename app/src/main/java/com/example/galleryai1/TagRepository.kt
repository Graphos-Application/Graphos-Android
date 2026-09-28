package com.example.galleryai1

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * MobileCLIP 기반 태깅/검색의 저장소·오케스트레이션 레이어. 기존 `LabelStore`(SharedPreferences
 * JSON) + `AutoTagger`/`PreciseTagger`/`FaceTagger`/`AiCaptioner`/`AiPromptTagger`의 역할을
 * Room DB(setting.md의 `PhotoAlbum`/`TagMaster`/`PhotoTagMap` 3테이블) + [MobileClipEngine]
 * 하나로 합쳤다.
 *
 * Room DAO 호출은 메인 스레드에서 하면 예외가 나므로, 이 object는 자체 단일 스레드 executor를
 * 가지고 있다(기존 `AutoTagger` 등이 각자 스레드풀을 갖던 것과 같은 패턴). 이미 백그라운드
 * 스레드에 있는 호출부(MainActivity의 backgroundExecutor 안 등)를 위해 동기(`*Blocking`/직접
 * 반환) 버전도 같이 제공한다 — 이중으로 스레드를 넘나들지 않기 위함이다.
 */
object TagRepository {
    // 2026-09-05 실기기/에뮬레이터 확인 결과, 임계값 0.15·top-12는 관련 없는 태그가 너무 많이
    // 붙는 문제가 있었다(예: 돌고래 사진에 거북이 태그). 실제로 디버그 로그로 원인을 확인해보니
    // "거북이가 강한 오답"이 아니라, 그 돌고래 사진 자체가 모델에게 애매해서(가까이서 찍은 얼굴
    // 클로즈업) 전체 후보 태그가 0.15~0.166 사이에 다닥다닥 붙어 있고 확실한 1위가 아예 없었다
    // — 기존 임계값(0.15)이 이 "노이즈 바닥"과 거의 같아서, 정답이든 오답이든 그 근처 점수가 전부
    // 통과해버린 게 진짜 원인. 0.18로 올리면 이런 애매한 사진은 "태그 없음"(정직한 결과)이 되고,
    // 강아지 사진처럼 확실한 사진은 top 후보(dog 0.26/pet 0.25/animal 0.197)가 여전히 살아남는다
    // — 0.20까지 올려봤더니 animal(0.197)처럼 정답인데 애매하게 걸치는 것까지 잘려서 0.18로 낮췄다.
    // 여전히 사진 규모가 늘면 더 튜닝이 필요할 수 있는 값이라 상수로 분리해 뒀다.
    // [[galleryai1-mobileclip-migration]] 메모 참고.
    private const val TOP_K_PER_PHOTO = 6
    private const val SIMILARITY_THRESHOLD = 0.18f
    // 절대 임계값만 쓰면 "1위는 확실한데 그 뒤로 애매하게 걸치는 태그들이 줄줄이 딸려오는" 문제를
    // 못 막는다(사용자가 실제로 지적한 사례). 1위 태그와 코사인 유사도 차이가 이 값보다 크게 나는
    // 후보는 임계값을 넘겨도 버린다.
    private const val RELATIVE_MARGIN = 0.08f
    private const val VECTOR_SEARCH_TOP_K = 60

    // 태깅 로직(임계값/후보 제외 목록 등)을 바꿀 때마다 올린다 — PhotoAlbumEntity.taggingVersion과
    // 비교해서, 이미 분석된 사진도 앱 데이터 초기화 없이 자동으로 재분석되게 한다.
    // 2026-09-07: 4→5. 로직 튜닝이 아니라 [MobileClipEngine]의 vision 모델을 fp16→fp32로 교체한
    // 것 때문에 올림 — 실기기에서 fp16으로 분석된 사진들은 taggingVersion=4로 저장돼 있지만
    // 임베딩이 전부 NaN이라(실기기 배포 후 Room DB를 직접 까봐서 확인함) 그대로 두면 "이미 분석
    // 완료"로 오인돼 재분석이 절대 안 된다. 버전을 올려야 이 사진들도 새 fp32 모델로 다시 도는지 확인됨.
    // 2026-09-07 (같은 날 또): 5→6. tag_master 공유 태그 풀에서 깨진 항목("길가" — 번역 실패 시
    // 한글 원문이 그대로 영어 태그명으로 들어간 버그, [addUserLabel] 참고)을 지웠다. 이 항목이
    // 거의 모든 사진과 비정상적으로 높은 유사도를 가져서 관련없는 사진들에도 계속 AI 태그로
    // 잘못 붙던 걸 확인·삭제함 — 이미 그 오염된 풀로 분석된 사진들(taggingVersion=5)의
    // photo_tag_map엔 그 여파로 낀 엉뚱한 태그들이 그대로 남아있어서, 버전을 올려 깨끗한 풀로
    // 다시 돌게 한다.
    // 2026-09-07 (세 번째): 6→7. [SearchSynonyms]/seed_tags.json에 "명찰"/"행사"/"공부" 등
    // 12개 개념을 새로 추가함(실기기에서 한능검 책/해커톤 명찰/단체 셀카 사진에 대응하는 개념이
    // 사전에 아예 없어서 제로샷이 근거 없이 헤매던 걸 확인 — 임계값 튜닝으론 못 고치는 종류의
    // 문제라 사전 보강으로 대응함). 이미 분석된 사진들도 새 개념과 다시 비교해봐야 하므로 버전을 올림.
    // 2026-09-07 (네 번째): 7→8. [YoloEngine](COCO 80종 탐지기) 하이브리드 추가 — "사람" 등
    // MobileCLIP 제로샷이 구조적으로 약한 개념을 보강. 이미 분석된 사진도 YOLO 패스를 한 번씩
    // 거쳐야 하므로 버전을 올림.
    // 2026-09-14: 8→9. 태깅 정확도 개선 — YOLO가 아는 COCO 80종 중 사전에 아예 없던 46개 개념
    // (트럭/벤치/코끼리/우산/병/컵/침대/텔레비전/냉장고 등)을 [SearchSynonyms]/`seed_tags.json`/
    // `YoloEngine.COCO_TO_TAG_EN` 세 곳에 같이 추가함. 이 사물들은 그동안 사전 자체가 없어서
    // MobileCLIP 제로샷 후보에도 안 잡히거나 임계값을 못 넘기기 쉬웠는데, YOLO의 강한 신뢰도(0.4+)로
    // 보강되게 했다. 이미 분석된 사진도 새 개념들과 다시 비교해봐야 하므로 버전을 올림.
    // 2026-09-14 (같은 날 또): 9→10. `tools/measure_tagging_accuracy.py` 측정 하네스로 실측해보니
    // recall 93.3%/precision 46.2%(15장 기준) — precision이 낮은 원인이 "YOLO는 이미 없다고 판정한
    // COCO 클래스를 CLIP이 우겨서 붙이는" 패턴으로 확인돼(트럭 사진에 버스, 백팩 사진에 스키 등)
    // [suppressYoloCompetingClipTags]를 추가함. 억제 로직이 적용되면 결과 태그 구성이 달라지므로
    // 이미 분석된 사진도 다시 돌게 버전을 올림.
    // 2026-09-14 (같은 날 세 번째): 10→11. v10을 그대로 에뮬레이터에 재배포해서 측정 하네스로
    // 재검증했더니 recall이 오히려 93.3%→73.3%로 추락 — 특이한 구도의 트럭/백팩/와인잔 사진에서는
    // YOLO 자신도 그 클래스를 놓쳤는데(신뢰도 미달) CLIP은 정확히 맞췄던 걸, v10의 억제 로직이
    // "YOLO가 없다고 했다"만 보고 CLIP의 정답까지 같이 지워버린 사고였다. `yoloDetections.isEmpty()`면
    // 아무 것도 억제하지 않는 가드를 추가해서 수정 — YOLO가 그 사진에서 뭔가는 성공적으로 찾았을
    // 때만 "없음" 판정을 신뢰하도록 좁혔다. 이미 v10 로직으로 분석된 사진도 다시 돌게 버전을 올림.
    // 2026-09-14 (같은 날 다섯 번째): 11→12. 측정 표본을 43장으로 늘려서(tools/ground_truth.json)
    // 다시 보니 "실내"/"실외"가 backpack/bench/tv/tulips 등 서로 무관한 사진 여러 개에 공통으로
    // 오탐으로 붙는 게 확인됨 — `fun`/`leisure`/`standing`/`cool`과 같은 종류(사진의 구체적인
    // 대상이 아니라 장면 전체 분위기만 보고 추측하는 추상적 서술어라 제로샷이 취약하고, 검색
    // 가치도 거의 없음: "실내 사진 보여줘"로 검색할 사람은 없다)라 같은 목록에 추가함. "테이블"/
    // "식당"/"파티" 등 겉보기엔 비슷해 보이는 다른 후보들은 실제로 사진에 있는 경우가 많아서
    // (그릇/음식 사진엔 거의 항상 테이블이 있다) 일부러 안 뺐다 — ground_truth.json도 이 사진들에
    // "테이블" 등을 정답 보너스로 보정해서, 이번 변경 전 진짜 precision이 45.8%가 아니라
    // 59.0%였음을 먼저 확인한 뒤에 이 변경을 넣었다([[galleryai1-yolo-hybrid-tagging]] 참고).
    private const val TAGGING_VERSION = 12

    // 제로샷 모델이 구체적 사물보다 훨씬 취약한 추상적 기분/상태/장면 서술어 — 사진 내용과 무관하게
    // 이것저것에 다 걸려서 자동 태깅 후보에서 아예 뺐다. 검색어 동의어 사전(SearchSynonyms)에는
    // 그대로 남아있고, 사용자가 직접 태그로 추가하는 것도 막지 않는다 — "MobileCLIP이 사진만 보고
    // 자동으로 붙이는" 후보에서만 제외한다.
    private val AUTO_TAG_EXCLUDED_EN = listOf("fun", "leisure", "standing", "cool", "indoor", "outdoor")

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var tagCache: List<TagMasterEntity>? = null
    // ensureSeeded()가 사진 한 장 분석할 때마다 불려서, 씨앗 데이터가 이미 들어있는지 매번 COUNT
    // 쿼리로 확인하면 사진이 많을수록(수백~수천 장) 불필요한 DB 왕복이 쌓인다. 한 번 확인되면
    // 그 뒤로는 이 플래그만 보고 즉시 리턴한다 — loadTagCache와 같은 이중 검사 락킹 패턴.
    @Volatile private var seeded = false

    // ---------- 사진 등록 / 삭제 감지 ----------

    /** MediaStore에서 조회한 사진 목록을 Room에 등록한다. 이미 있는 사진(분석 결과 포함)은 건드리지 않는다. */
    fun registerPhotos(context: Context, photos: List<GalleryItem.Photo>) {
        if (photos.isEmpty()) return
        val db = AppDatabase.getInstance(context)
        val entities = photos.map { PhotoAlbumEntity(it.uri.toString(), it.dateTakenMillis, it.dateTakenMillis) }
        db.photoAlbumDao().insertAllIfAbsent(entities)
    }

    /** 사진첩에서 삭제된 사진의 DB 행을 정리한다(CASCADE로 PhotoTagMap도 같이 지워짐). */
    fun pruneDeleted(context: Context, existingUris: Set<String>) {
        val dao = AppDatabase.getInstance(context).photoAlbumDao()
        val stored = dao.getAllUris().toSet()
        val stale = computeStaleUriKeys(stored, existingUris)
        if (stale.isNotEmpty()) dao.deleteByUris(stale)
    }

    // ---------- 사진 분석(태깅) ----------

    fun isAnalyzed(context: Context, photoUri: String): Boolean =
        AppDatabase.getInstance(context).photoAlbumDao().get(photoUri)?.taggingVersion == TAGGING_VERSION

    /**
     * 비동기 버전 — 메인 스레드 등 아무 곳에서나 호출 가능(내부에서 이미 분석됐는지 확인하는
     * Room 조회까지 전부 백그라운드 스레드에서 하므로, 호출하는 스레드에서 Room을 직접 건드리지 않는다).
     */
    fun analyzePhoto(context: Context, photoUri: String, onDone: () -> Unit) {
        executor.execute {
            analyzePhotoBlocking(context, photoUri)
            mainHandler.post(onDone)
        }
    }

    /** 동기 버전 — 호출부가 이미 백그라운드 스레드일 때(MainActivity 검색 흐름 등) 이중 디스패치 없이 쓴다. */
    fun analyzePhotoBlocking(context: Context, photoUri: String) {
        if (isAnalyzed(context, photoUri)) return
        val db = AppDatabase.getInstance(context)
        ensureSeeded(context, db)

        val existing = db.photoAlbumDao().get(photoUri)
        val dateAdded = existing?.dateAdded ?: System.currentTimeMillis()
        val dateModified = existing?.dateModified ?: dateAdded

        val bitmap = TaggingImageLoader.load(context, photoUri)
        if (bitmap == null) {
            // 디코딩 자체가 안 되는 사진(파일 접근 불가 등) — 재시도로 매번 실패하지 않도록 버전만 표시.
            db.photoAlbumDao().upsert(PhotoAlbumEntity(photoUri, dateAdded, dateModified, existing?.embedding, taggingVersion = TAGGING_VERSION))
            return
        }

        val embedding: FloatArray
        val yoloDetections: Map<String, Float>
        try {
            embedding = MobileClipEngine.embedImage(context, bitmap)
            // 2026-09-07: YoloEngine 추가 — MobileCLIP 제로샷은 "사람" 같은 흔한 개념에 구조적으로
            // 약해서(실기기 실측 0.14~0.17, 임계값 0.18 문턱을 못 넘음), COCO로 전용 훈련된 소형
            // 탐지기를 나란히 돌려 [COCO_TO_TAG_EN]에 있는 클래스만 보강한다. 같은 비트맵을
            // 재사용(다시 디코딩 안 함) — 아래에서 두 계층 다 끝난 뒤 한 번에 recycle().
            yoloDetections = YoloEngine.detect(context, bitmap)
        } finally {
            bitmap.recycle()
        }

        // 주의: PhotoAlbum 갱신을 반드시 PhotoTagMap 쓰기보다 먼저 커밋해야 한다. photoUri가 이미
        // 존재하는 상태에서 REPLACE 방식 upsert를 하면 SQLite가 내부적으로 "기존 행 삭제 후 재삽입"으로
        // 처리하는데, PhotoTagMap의 FK가 photoUri에 ON DELETE CASCADE로 걸려 있어서 이 삭제가 CASCADE로
        // 전파돼 방금 넣은 태그 매핑을 통째로 지워버린다(실기기 에뮬레이터 테스트 중 실제로 이 순서
        // 버그로 모든 사진이 "AI 태그 없음"으로 나오는 걸 재현·확인함). 그래서 "부모(PhotoAlbum) 먼저
        // 커밋 → 자식(PhotoTagMap) 나중"순서를 반드시 지킨다.
        db.photoAlbumDao().upsert(PhotoAlbumEntity(photoUri, dateAdded, dateModified, embeddingToBytes(embedding), taggingVersion = TAGGING_VERSION))

        val clipMatchesRaw = topMatchingTags(embedding, loadTagCache(db), TOP_K_PER_PHOTO, SIMILARITY_THRESHOLD, RELATIVE_MARGIN)
        // 2026-09-14: precision 개선 — 측정 하네스(tools/measure_tagging_accuracy.py)로 재보니
        // recall은 93.3%까지 올랐는데 precision은 46.2%로 낮았다(예: 백팩 사진에 스키/스노보드/
        // 테니스라켓, 트럭 사진에 버스, 냉장고 사진에 전자레인지/병 같은 CLIP 제로샷 오탐이 같이
        // 붙음). 공통점: 이 오탐들이 전부 COCO_TO_TAG_EN에 매핑된 개념인데, YOLO는 같은 사진에서
        // 이미 그 클래스를 "없음"(신뢰도 0.4 미달)으로 판정했었다는 것 — YOLO가 그 클래스 전용으로
        // 훈련된 탐지기라 제로샷보다 신뢰도가 훨씬 높으므로([[galleryai1-yolo-hybrid-tagging]]
        // person 0.90 vs 0.17), 그 판정을 CLIP의 경쟁 후보 억제에도 재사용한다.
        val clipMatches = suppressYoloCompetingClipTags(clipMatchesRaw, yoloDetections)
        // 두 계층의 결과를 tagId 기준으로 합친다(같은 태그를 둘 다 찾으면 더 높은 신뢰도를 남김) —
        // 합치기 전에 한 번만 delete+insert해야 한다. CLIP 결과를 먼저 넣고 YOLO로 덮어써서, 같은
        // 개념이면 보통 더 자신 있는 YOLO 점수(0.4+)가 CLIP의 애매한 점수(0.15~0.2대)를 이기게 한다.
        val merged = LinkedHashMap<Long, Float>()
        for ((tag, score) in clipMatches) merged[tag.tagId] = score
        for ((cocoClass, score) in yoloDetections) {
            val en = COCO_TO_TAG_EN[cocoClass] ?: continue
            val tag = findTagByName(db, en) ?: continue
            val prev = merged[tag.tagId]
            if (prev == null || score > prev) merged[tag.tagId] = score
        }
        db.photoTagMapDao().deleteBySource(photoUri, TagSource.AI)
        if (merged.isNotEmpty()) {
            db.photoTagMapDao().insertAll(merged.map { (tagId, score) -> PhotoTagMapEntity(photoUri, tagId, score, TagSource.AI) })
        }
    }

    // ---------- 라벨 조회(AI 자동 태그 / 사용자 태그) ----------

    fun getAiLabels(context: Context, photoUri: String): List<String> =
        AppDatabase.getInstance(context).photoTagMapDao().getLabelsForPhoto(photoUri, TagSource.AI).map { it.tagNameKo }

    fun getUserLabels(context: Context, photoUri: String): List<String> =
        AppDatabase.getInstance(context).photoTagMapDao().getLabelsForPhoto(photoUri, TagSource.USER).map { it.tagNameKo }

    /** [getAiLabels]/[getUserLabels]를 백그라운드에서 조회해 메인 스레드로 결과를 돌려준다(LabelEditDialog용). */
    fun loadPhotoLabels(context: Context, photoUri: String, onResult: (aiLabels: List<String>, userLabels: List<String>) -> Unit) {
        executor.execute {
            val ai = getAiLabels(context, photoUri)
            val user = getUserLabels(context, photoUri)
            mainHandler.post { onResult(ai, user) }
        }
    }

    // ---------- 사용자 라벨 추가/삭제 (기획서 3.1절 "사용자 정의 태그 동적 추가") ----------

    /**
     * 사용자가 입력한 라벨을 이 사진에 추가한다. 이미 있는 태그면 그대로 매핑만 추가하고,
     * 완전히 새로운 태그면 (한글이면 먼저 영어로 번역 후) MobileCLIP 텍스트 임베딩을 계산해
     * TagMaster에 새로 등록한 뒤, 이미 분석된 다른 사진들에도 소급 적용한다([backfillTagAcrossPhotos]).
     */
    fun addUserLabel(context: Context, photoUri: String, rawLabel: String, onDone: (List<String>) -> Unit) {
        val label = rawLabel.trim()
        if (label.isEmpty()) {
            executor.execute { postUserLabels(context, photoUri, onDone) }
            return
        }
        executor.execute {
            val db = AppDatabase.getInstance(context)
            ensureSeeded(context, db)
            val existingTag = findTagByName(db, label)
            if (existingTag != null) {
                db.photoTagMapDao().insert(PhotoTagMapEntity(photoUri, existingTag.tagId, 1f, TagSource.USER))
                postUserLabels(context, photoUri, onDone)
                return@execute
            }

            val looksKorean = label.any { it in '가'..'힣' }
            if (looksKorean) {
                TranslationTagger.translateToEnglish(context, label) { translated ->
                    val en = translated?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
                    if (en == null) {
                        // 번역 모델이 아직 준비 안 됐거나 실패한 경우: 예전엔 번역 없이 한글 원문을
                        // 그대로 "영어" 태그명으로 써서 MobileCLIP 텍스트 인코더(영어 전용)에 넣었는데,
                        // 이러면 의미 없는 임베딩이 나오고 그게 우연히 거의 모든 사진과 비정상적으로
                        // 높은 유사도를 갖게 돼서 그 태그가 전혀 상관없는 사진들에도 AI 태그로 계속
                        // 잘못 붙는 버그가 실기기에서 실제로 발생했다(2026-09-07, "길가" 태그 사례 —
                        // [[galleryai1-s24-realdevice-session]] 참고, tag_master는 모든 사진의 자동
                        // 태깅에 공유되는 풀이라 한 번 나쁜 임베딩이 들어가면 전체를 오염시킨다).
                        // 조용히 폴백하는 대신 태그 추가를 보류하고 사용자에게 알린다 — 번역 모델은
                        // 보통 몇 초~몇 분 내로 다운로드가 끝나므로 잠시 후 재시도하면 된다.
                        mainHandler.post {
                            Toast.makeText(context, "번역 모델을 준비하고 있어요. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                        }
                        postUserLabels(context, photoUri, onDone)
                        return@translateToEnglish
                    }
                    executor.execute { finishAddUserLabel(context, db, photoUri, label, en, onDone) }
                }
            } else {
                finishAddUserLabel(context, db, photoUri, label, label.lowercase(Locale.ROOT), onDone)
            }
        }
    }

    private fun finishAddUserLabel(context: Context, db: AppDatabase, photoUri: String, ko: String, en: String, onDone: (List<String>) -> Unit) {
        val tag = db.tagMasterDao().findByEn(en) ?: run {
            val embedding = MobileClipEngine.embedText(context, "a photo of $en")
            val created = TagMasterEntity(tagNameEn = en, tagNameKo = ko, embedding = embeddingToBytes(embedding))
            val id = db.tagMasterDao().insert(created)
            invalidateTagCache()
            val withId = created.copy(tagId = id)
            backfillTagAcrossPhotos(context, db, withId)
            withId
        }
        db.photoTagMapDao().insert(PhotoTagMapEntity(photoUri, tag.tagId, 1f, TagSource.USER))
        postUserLabels(context, photoUri, onDone)
    }

    fun removeUserLabel(context: Context, photoUri: String, label: String, onDone: (List<String>) -> Unit) {
        executor.execute {
            val db = AppDatabase.getInstance(context)
            findTagByName(db, label)?.let { db.photoTagMapDao().delete(photoUri, it.tagId, TagSource.USER) }
            postUserLabels(context, photoUri, onDone)
        }
    }

    private fun postUserLabels(context: Context, photoUri: String, onDone: (List<String>) -> Unit) {
        val labels = getUserLabels(context, photoUri)
        mainHandler.post { onDone(labels) }
    }

    /** 새로 만든 태그를 이미 분석된 다른 사진들에도 코사인 유사도로 소급 적용한다. 결과를 기다릴 필요 없어 fire-and-forget. */
    private fun backfillTagAcrossPhotos(context: Context, db: AppDatabase, tag: TagMasterEntity) {
        executor.execute {
            val tagEmbedding = bytesToEmbedding(tag.embedding)
            val rows = db.photoAlbumDao().getAllEmbeddings()
            val mappings = rows.mapNotNull { row ->
                val sim = cosineSimilarityOfNormalized(tagEmbedding, bytesToEmbedding(row.embedding))
                if (sim >= SIMILARITY_THRESHOLD) PhotoTagMapEntity(row.photoUri, tag.tagId, sim, TagSource.AI) else null
            }
            if (mappings.isNotEmpty()) db.photoTagMapDao().insertAll(mappings)
        }
    }

    private fun findTagByName(db: AppDatabase, raw: String): TagMasterEntity? {
        val trimmed = raw.trim()
        return db.tagMasterDao().findByKoOrEn(trimmed, trimmed.lowercase(Locale.ROOT))
    }

    // ---------- 검색: Tier1(색인) / Tier2(벡터 폴백) ----------

    /**
     * Tier1: 개념 그룹(그룹 간 AND, 그룹 내 동의어는 OR)으로 태그 인덱스를 조회한다.
     * [conceptGroups]는 비어 있지 않아야 한다(빈 그룹 처리는 호출부 책임).
     */
    fun findMatchingUris(context: Context, conceptGroups: List<Set<String>>): Set<String> {
        val dao = AppDatabase.getInstance(context).photoTagMapDao()
        var result: Set<String>? = null
        for (group in conceptGroups) {
            val terms = group.map { it.lowercase(Locale.ROOT) }
            val uris = dao.findPhotoUrisByTagTerms(terms).toSet()
            result = result?.intersect(uris) ?: uris
            if (result.isEmpty()) return emptySet()
        }
        return result ?: emptySet()
    }

    /**
     * Tier2: 태그 인덱스에 걸리는 게 하나도 없을 때, 검색어 문장 전체를 임베딩해서 사진 임베딩과
     * 코사인 유사도로 랭킹한다.
     *
     * 2026-09-14: 한글 원문을 그대로 넣기 전에 먼저 영어로 번역한다(`TranslationTagger.translateToEnglishBlocking`).
     * `tools/search_harness.py`로 실측한 결과, MobileCLIP 텍스트 인코더가 영어 전용 바이트 레벨
     * BPE라 한글 문장이 조금만 길어져도 의미 없는 바이트 조각으로 쪼개져서 서로 무관한 질의끼리
     * 코사인 유사도가 0.93~0.98(사실상 구분 불가)로 수렴하는 걸 확인했다 — "오랑우탄 사진"이
     * 실제 오랑우탄 사진을 전혀 못 찾고 매번 같은 사진 몇 장으로만 수렴함. 영어로 번역해서
     * 넣으면("orangutan photo") 정답 사진이 1위로 올라옴을 같은 방법으로 확인함. Tier1 경로
     * (`SearchSynonyms.expandGrouped`/`TranslationTagger.expandToConceptGroups`)엔 이미 번역이
     * 연결돼 있었는데 Tier2만 빠져 있었던 것 — 번역 실패/미준비 시엔 원문 그대로 쓰던 기존 동작으로
     * 조용히 폴백한다(다른 곳과 동일한 "되면 보강, 안 되면 기존 방식" 원칙).
     */
    fun vectorSearch(context: Context, queryText: String): List<String> {
        if (!SearchSynonyms.hasMeaningfulContent(queryText)) return emptyList()
        val db = AppDatabase.getInstance(context)
        val translated = TranslationTagger.translateToEnglishBlocking(context, queryText)
        val queryEmbedding = MobileClipEngine.embedText(context, translated ?: queryText)
        val rows = db.photoAlbumDao().getAllEmbeddings()
        return rows.asSequence()
            .map { it.photoUri to cosineSimilarityOfNormalized(queryEmbedding, bytesToEmbedding(it.embedding)) }
            .filter { it.second >= SIMILARITY_THRESHOLD }
            .sortedByDescending { it.second }
            .take(VECTOR_SEARCH_TOP_K)
            .map { it.first }
            .toList()
    }

    // ---------- TagMaster 씨앗 데이터 ----------

    /** `assets/mobileclip/seed_tags.json`(SearchSynonyms 사전 기반, 미리 계산된 임베딩)으로 TagMaster를 채운다. */
    private fun ensureSeeded(context: Context, db: AppDatabase) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            val json = context.applicationContext.assets.open("mobileclip/seed_tags.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(json)
            // 2026-09-07 이전엔 tag_master가 완전히 비어있을 때(count()==0)만 씨앗을 넣었는데, 그러면
            // 이미 씨앗이 들어간 기기(=거의 모든 기존 설치)엔 seed_tags.json에 새 개념을 보태도 영원히
            // 반영이 안 됐다(실기기 실사용 중 "명찰"/"행사" 등 사전에 없는 개념이 계속 안 잡히는 걸
            // 보고 발견 — [[galleryai1-s24-realdevice-session]] 참고). insert()가 tagNameEn 유니크
            // 인덱스에 IGNORE 전략이라 이미 있는 이름은 조용히 스킵되므로, count 체크 없이 매번 전체
            // 목록을 다시 시도해도 안전하다 — 최초 1회 전체 삽입과 이후의 "새로 추가된 개념만 보강"이
            // 이 한 루프로 같이 처리된다. 134개(→146개)를 한 건씩 따로 INSERT하면 그만큼 디스크
            // 커밋이 따로 일어나므로 트랜잭션 하나로 묶어서 커밋 횟수를 줄인다.
            db.runInTransaction {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val ko = obj.getString("ko")
                    val en = obj.getString("en").lowercase(Locale.ROOT)
                    val embArr = obj.getJSONArray("emb")
                    val emb = FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() }
                    db.tagMasterDao().insert(TagMasterEntity(tagNameEn = en, tagNameKo = ko, embedding = embeddingToBytes(emb)))
                }
            }
            // 이전 버전에서 이미 씨앗이 들어간 기기에도 적용되도록 매번 정리한다(제외 목록이 몇 개
            // 안 돼서 가볍다) — CASCADE로 이미 사진에 붙어있던 노이즈 태그 매핑까지 같이 지워진다.
            db.tagMasterDao().deleteByEnNames(AUTO_TAG_EXCLUDED_EN)
            invalidateTagCache()
            seeded = true
        }
    }

    private fun loadTagCache(db: AppDatabase): List<TagMasterEntity> {
        tagCache?.let { return it }
        synchronized(this) {
            tagCache?.let { return it }
            val all = db.tagMasterDao().getAll()
            tagCache = all
            return all
        }
    }

    private fun invalidateTagCache() {
        tagCache = null
    }
}

/**
 * [TagRepository.pruneDeleted]의 순수 비교 로직. 예전 `LabelStore.computeStaleLabelKeys`와 이유가
 * 같다 — Context/Room에 의존하지 않는 부분만 분리해서 JUnit에서 바로 테스트한다.
 */
internal fun computeStaleUriKeys(storedUris: Set<String>, existingUris: Set<String>): List<String> =
    storedUris.filterNot { existingUris.contains(it) }

/**
 * 이미지/텍스트 임베딩(L2 정규화됨) 중 후보 태그와 유사도가 높은 상위 [topK]개를 고른다.
 * 두 조건을 모두 만족해야 살아남는다: (1) 절대 유사도가 [threshold] 이상, (2) 1위 후보와의
 * 유사도 차이가 [relativeMargin] 이내. (2)가 없으면 1위는 확실한데 그 뒤로 애매하게 걸치는
 * 후보들이 절대 임계값만 넘으면 전부 살아남아서, 사진과 무관해 보이는 태그가 줄줄이 붙는 문제가
 * 생긴다(예: 돌고래 사진에 거북이 태그) — [TagRepository.RELATIVE_MARGIN] 참고.
 */
internal fun topMatchingTags(
    embedding: FloatArray,
    tags: List<TagMasterEntity>,
    topK: Int,
    threshold: Float,
    relativeMargin: Float = Float.MAX_VALUE
): List<Pair<TagMasterEntity, Float>> {
    val aboveThreshold = tags.asSequence()
        .map { it to cosineSimilarityOfNormalized(embedding, bytesToEmbedding(it.embedding)) }
        .filter { it.second >= threshold }
        .toList()
    val best = aboveThreshold.maxOfOrNull { it.second } ?: return emptyList()
    return aboveThreshold.asSequence()
        .filter { it.second >= best - relativeMargin }
        .sortedByDescending { it.second }
        .take(topK)
        .toList()
}

/**
 * YOLO가 담당하는 개념(COCO 매핑)인데 이번 사진에서 YOLO가 "없음"으로 판정한(신뢰도 [YoloEngine.MIN_CONFIDENCE]
 * 미달이라 [yoloDetections]에 아예 없는) 태그는, CLIP 제로샷 후보에서도 함께 제거한다. YOLO는 그 클래스
 * 전용으로 훈련된 탐지기라 신뢰도가 CLIP 제로샷보다 훨씬 높다는 전제([[galleryai1-yolo-hybrid-tagging]]
 * 실측: person 0.90 vs 0.17) — 실측 사례(트럭 사진에 "버스", 백팩 사진에 "스키"/"스노보드" 등)가 전부
 * "YOLO가 같은 사진에서 이미 없다고 판정한 COCO 클래스를 CLIP만 우겨서 붙인" 패턴이었다.
 * COCO에 없는 개념(사전 192개 중 YOLO가 아예 모르는 것들)은 이 필터에 안 걸리고 그대로 남는다.
 *
 * **[yoloDetections]가 완전히 비어있으면(이 사진에서 YOLO가 단 하나도 못 찾음) 아무 것도 억제하지
 * 않는다.** 처음 버전은 이 가드가 없어서, 측정 하네스로 검증하다가 정반대 사고가 실제로 남을 만큼
 * 관측됨: 특이한 구도의 트럭(사방을 화려하게 장식한 파키스탄 트럭)·백팩(등산스틱과 뒤엉킨 채 문 앞에
 * 세워둠)·와인잔(포도밭 말뚝 위에 얹힌) 사진에서 YOLO 자신도 그 클래스를 놓쳤는데(신뢰도 미달),
 * CLIP은 오히려 정확히 맞췄다 — 이런 사진에서 "YOLO가 없다고 했다"는 신호는 신뢰도 낮은 탐지기의
 * 침묵일 뿐 실제 부재의 증거가 아니므로, 위양성을 잡으려다 YOLO가 놓친 진짜 정답까지 같이 지워버리는
 * 사고로 이어졌다. YOLO가 그 사진에서 **뭔가는 성공적으로 찾았을 때만**(즉 탐지기가 이 사진 자체에는
 * 제대로 반응했다는 근거가 있을 때만) 그 "없음" 판정을 다른 클래스에도 신뢰한다.
 */
internal fun suppressYoloCompetingClipTags(
    clipMatches: List<Pair<TagMasterEntity, Float>>,
    yoloDetections: Map<String, Float>,
    tagEnToCocoClass: Map<String, String> = TAG_EN_TO_COCO_CLASS
): List<Pair<TagMasterEntity, Float>> {
    if (yoloDetections.isEmpty()) return clipMatches
    return clipMatches.filter { (tag, _) ->
        val cocoClass = tagEnToCocoClass[tag.tagNameEn]
        cocoClass == null || yoloDetections.containsKey(cocoClass)
    }
}

/** 512차원 float 임베딩을 Room BLOB 컬럼에 저장하기 위한 바이트 배열로 packing한다(리틀 엔디언 float32 x N). */
internal fun embeddingToBytes(v: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (x in v) buffer.putFloat(x)
    return buffer.array()
}

internal fun bytesToEmbedding(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(bytes.size / 4)
    for (i in out.indices) out[i] = buffer.getFloat()
    return out
}
