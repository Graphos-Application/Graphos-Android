package com.example.galleryai1

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Gemini Nano([AiPromptTagger])는 구글이 기기별로 골라서 여는 베타 기능이라, 하드웨어가
 * 지원해도 특정 기기에선 서버 쪽 승인이 안 나 계속 막혀있을 수 있다(AICore FEATURE_NOT_FOUND).
 * 이 두 번째 AI 계층은 AICore 없이 **모든 안드로이드 기기**에서 도는 ML Kit 온디바이스
 * 번역 모델(한국어→영어)로 검색어를 번역해서 AI 라벨과 매칭한다.
 *
 * [SearchSynonyms]의 수동 사전은 등록해둔 단어만 매칭되는 한계가 있다("군복"을 안 넣어두면
 * 절대 안 걸림). 번역은 사전에 없는 단어도 일반적으로 커버해서, 사전을 계속 손으로 늘려야
 * 하는 유지보수 부담 자체를 줄이는 게 목적이다 — 사전은 번역이 놓칠 수 있는 동의어 보강용으로만 남긴다.
 *
 * 모델(~30MB)은 최초 검색 시 조용히 백그라운드로 내려받는다. 검색이라는 핵심 기능이 걸려있어
 * 다운로드를 미루는 게 더 손해라 별도 "설치" 다이얼로그 없이 자동으로 받는다. 다운로드 전/실패
 * 시엔 null을 돌려줘서 호출부(MainActivity)가 기존 사전 기반 매칭으로 조용히 폴백하게 한다 —
 * [AiPromptTagger]와 동일한 "되면 보강, 안 되면 조용히 기존 방식" 원칙.
 */
object TranslationTagger {
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var modelReady = false
    @Volatile private var downloadInFlight = false

    // 같은 단어가 여러 검색에 걸쳐 반복되므로(조사만 다르거나 재검색 등) 문장 단위가 아니라
    // 단어 단위로 캐싱한다 — AiPromptTagger의 promptCache보다 재사용률이 높다.
    private const val MAX_CACHE_ENTRIES = 200
    private val translationCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private fun ensureModelDownloading(context: Context) {
        if (modelReady || downloadInFlight) return
        downloadInFlight = true
        val mainExecutor = ContextCompat.getMainExecutor(context.applicationContext)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener(mainExecutor) { modelReady = true }
            .addOnFailureListener(mainExecutor) { downloadInFlight = false } // 다음 검색 때 재시도
    }

    /**
     * [SearchSynonyms.expandGrouped]와 같은 형태(그룹 간 AND, 그룹 내 동의어는 OR)로 검색어를
     * 확장한다. 번역 모델이 아직 준비 안 됐거나(다운로드 전/중) 실제 찾을 대상이 없으면(불용어뿐)
     * null을 돌려줘서 검색이 번역을 기다리며 멈추지 않게 한다.
     */
    fun expandToConceptGroups(context: Context, query: String, onResult: (List<Set<String>>?) -> Unit) {
        ensureModelDownloading(context)
        if (!modelReady) {
            onResult(null)
            return
        }

        val tokens = SearchSynonyms.extractMeaningfulTokens(query)
        if (tokens.isEmpty()) {
            onResult(null)
            return
        }

        val mainExecutor = ContextCompat.getMainExecutor(context.applicationContext)
        val groups = arrayOfNulls<Set<String>>(tokens.size)
        var remaining = tokens.size

        fun finishIfDone() {
            if (remaining == 0) {
                onResult(groups.map { it ?: emptySet() })
            }
        }

        tokens.forEachIndexed { index, token ->
            val cached = synchronized(translationCache) { translationCache[token] }
            if (cached != null) {
                groups[index] = buildTranslationGroup(token, cached)
                remaining--
                return@forEachIndexed
            }
            translator.translate(token)
                .addOnSuccessListener(mainExecutor) { translated ->
                    synchronized(translationCache) { translationCache[token] = translated }
                    groups[index] = buildTranslationGroup(token, translated)
                    remaining--
                    finishIfDone()
                }
                .addOnFailureListener(mainExecutor) {
                    groups[index] = buildTranslationGroup(token, null)
                    remaining--
                    finishIfDone()
                }
        }
        finishIfDone() // 전부 캐시 히트였으면 콜백 없이 바로 끝난다
    }

    /**
     * 단어/짧은 문구 하나를 영어로 번역한다. [TagRepository]가 사용자가 새로 추가한 한글 태그의
     * MobileCLIP 텍스트 임베딩을 계산하기 전에(기획서 3.1절 "Text Embedding (Optional): 사용자
     * 정의 태그 동적 추가 시 텍스트 임베딩 생성") 영어로 바꿔두는 용도로 쓴다. 모델이 아직
     * 준비 안 됐거나 번역 실패면 null — 호출부가 원문을 그대로 쓰는 것으로 폴백한다.
     */
    fun translateToEnglish(context: Context, text: String, onResult: (String?) -> Unit) {
        ensureModelDownloading(context)
        if (!modelReady) {
            onResult(null)
            return
        }
        val cached = synchronized(translationCache) { translationCache[text] }
        if (cached != null) {
            onResult(cached)
            return
        }
        val mainExecutor = ContextCompat.getMainExecutor(context.applicationContext)
        translator.translate(text)
            .addOnSuccessListener(mainExecutor) { translated ->
                synchronized(translationCache) { translationCache[text] = translated }
                onResult(translated)
            }
            .addOnFailureListener(mainExecutor) { onResult(null) }
    }

    /**
     * [translateToEnglish]의 동기(블로킹) 버전 — [TagRepository.vectorSearch]처럼 이미 백그라운드
     * 스레드에서 도는 동기 함수 안에서 쓴다(메인 스레드에서 호출하면 안 됨, ML Kit Task를 그
     * 스레드에서 기다리는 것이라 데드락 위험이 있다).
     *
     * 2026-09-14 신설 — `tools/search_harness.py`로 검증해보니, Tier2(벡터검색 폴백)가 한글
     * 원문을 그대로 CLIP 텍스트 인코더(영어 전용, 바이트 레벨 BPE)에 넣고 있어서 문장이 조금만
     * 길어져도 서로 무관한 질의끼리 코사인 유사도가 0.93~0.98(사실상 구분 불가)로 수렴하는
     * 문제를 확인했다("오랑우탄 사진"이 실제 오랑우탄 사진을 전혀 못 찾고, 매번 같은 몇 장으로
     * 수렴함). 번역해서 넣으면(예: "orangutan photo") 정답 사진이 1위로 올라오는 것까지 실측
     * 확인함 — Tier1 경로([TranslationTagger.expandToConceptGroups])엔 이미 번역이 연결돼
     * 있었는데 Tier2만 빠져있었던 것.
     */
    fun translateToEnglishBlocking(context: Context, text: String, timeoutSeconds: Long = 5): String? {
        ensureModelDownloading(context)
        if (!modelReady) return null // 모델 다운로드 중이면 검색을 붙잡지 않고 원문으로 폴백
        synchronized(translationCache) { translationCache[text] }?.let { return it }
        return try {
            val translated = Tasks.await(translator.translate(text), timeoutSeconds, TimeUnit.SECONDS)
            synchronized(translationCache) { translationCache[text] = translated }
            translated
        } catch (_: Exception) {
            // 타임아웃/번역 실패 등 — 원문 그대로 CLIP에 넣는 기존 동작으로 조용히 폴백.
            null
        }
    }
}

// [TranslationTagger.expandToConceptGroups]가 번역 결과를 그룹으로 합치는 순수 함수.
// object 밖에 둔 이유는 parsePromptTagResponse/computeStaleLabelKeys와 같다 — object 안에 두면
// 이 함수 하나만 호출해도 object의 정적 초기화(Handler, 번역기 lazy 초기화 등 Android 의존)가
// 같이 실행돼서 순수 JUnit에서 테스트할 수 없다.
internal fun buildTranslationGroup(token: String, translated: String?): Set<String> {
    val set = mutableSetOf(token)
    translated?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { set.add(it) }
    set.addAll(SearchSynonyms.matchDictionaryTerms(setOf(token)))
    return set
}
