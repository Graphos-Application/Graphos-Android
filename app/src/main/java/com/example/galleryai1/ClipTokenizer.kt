package com.example.galleryai1

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * MobileCLIP(Xenova/mobileclip_s0) 텍스트 인코더가 기대하는 CLIP 원조 토크나이저(바이트 레벨 BPE)를
 * 순수 Kotlin으로 재구현한 것. HuggingFace `tokenizer.json`이 정의하는 파이프라인
 * (NFC 정규화 → 공백 축소 → 소문자화 → GPT2/CLIP 정규식으로 사전 분리 → 바이트→유니코드 매핑 →
 * BPE 병합 → 어휘 사전 조회)을 그대로 따른다. 온디바이스라 tokenizers 라이브러리(러스트/파이썬)를
 * 쓸 수 없어서 알고리즘을 직접 옮겼고, Python(`tokenizers` 라이브러리)으로 12개 샘플 문장에 대해
 * 토큰 id가 정확히 일치하는지 먼저 검증한 뒤 포팅했다.
 *
 * 어휘(vocab)와 병합 순위(merges)는 `assets/mobileclip/clip_vocab.txt`(줄 번호 = 토큰 id),
 * `assets/mobileclip/clip_merges.txt`(줄 번호 = 병합 우선순위)에서 읽는다 — 원본
 * `tokenizer.json`(2MB, 정규화기/사전분리기 스펙까지 포함)을 기기에서 매번 파싱할 필요 없이
 * 이 두 값만 뽑아 미리 저장해 뒀다.
 */
class ClipTokenizer(
    private val vocab: Map<String, Int>,
    private val mergeRank: Map<Pair<String, String>, Int>
) {
    companion object {
        const val CONTEXT_LENGTH = 77
        const val BOS_ID = 49406
        const val EOS_ID = 49407
        const val PAD_ID = 0

        private val BYTE_ENCODER: Map<Int, Char> = buildByteEncoder()

        fun loadFromAssets(context: Context): ClipTokenizer {
            val assets = context.applicationContext.assets
            val vocabList = assets.open("mobileclip/clip_vocab.txt").bufferedReader(Charsets.UTF_8)
                .use { it.readLines() }
            val vocab = HashMap<String, Int>(vocabList.size * 2)
            vocabList.forEachIndexed { id, token -> vocab[token] = id }

            val mergeRank = HashMap<Pair<String, String>, Int>(60000)
            assets.open("mobileclip/clip_merges.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { rank, line ->
                    val spaceIndex = line.indexOf(' ')
                    if (spaceIndex > 0) {
                        mergeRank[line.substring(0, spaceIndex) to line.substring(spaceIndex + 1)] = rank
                    }
                }
            }
            return ClipTokenizer(vocab, mergeRank)
        }
    }

    /**
     * 텍스트를 MobileCLIP 텍스트 인코더 입력 형식(길이 77 고정, `<|startoftext|>`/`<|endoftext|>` 포함,
     * 부족한 자리는 0으로 패딩)의 토큰 id 배열로 변환한다. 위치 임베딩 테이블이 77칸으로 고정돼 있어서
     * 이보다 짧거나 길면 모델이 에러를 낸다(패딩/자르기 필수).
     */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>(CONTEXT_LENGTH)
        ids.add(BOS_ID)
        for (chunk in clipPreTokenize(text)) {
            val byteStr = byteLevelEncode(chunk, BYTE_ENCODER)
            for (piece in bpeMerge(byteStr, mergeRank)) {
                ids.add(vocab[piece] ?: EOS_ID) // unk_token == eos, tokenizer.json 설정과 동일
            }
        }
        ids.add(EOS_ID)

        val result = IntArray(CONTEXT_LENGTH) { PAD_ID }
        val copyCount = minOf(ids.size, CONTEXT_LENGTH)
        for (i in 0 until copyCount) result[i] = ids[i]
        // 길이를 넘겨 잘라내는 경우에도 마지막 자리는 항상 eos로 마무리한다(정상 시퀀스 형태 유지).
        if (ids.size > CONTEXT_LENGTH) result[CONTEXT_LENGTH - 1] = EOS_ID
        return result
    }
}

/**
 * GPT2/CLIP가 쓰는 바이트→유니코드 매핑 테이블(256개 항목)을 만든다.
 * 제어문자·공백처럼 사람이 읽기 애매한 바이트를 인쇄 가능한 유니코드 코드포인트로 옮겨서,
 * 어떤 바이트 시퀀스든 텍스트로 안전하게 다루면서 BPE 병합을 적용할 수 있게 한다.
 * (OpenAI CLIP/GPT2 원본 구현과 동일한 알고리즘 — 순서까지 그대로 맞춰야 vocab의 토큰 문자열과 일치한다.)
 */
internal fun buildByteEncoder(): Map<Int, Char> {
    val bs = mutableListOf<Int>()
    bs.addAll('!'.code..'~'.code)
    bs.addAll(0xA1..0xAC)
    bs.addAll(0xAE..0xFF)
    val cs = bs.toMutableList()
    var n = 0
    for (b in 0..255) {
        if (b !in bs) {
            bs.add(b)
            cs.add(256 + n)
            n++
        }
    }
    return bs.indices.associate { i -> bs[i] to cs[i].toChar() }
}

/** NFC 정규화 → 연속 공백을 하나로 축소 → 소문자화. tokenizer.json의 정규화기 설정과 동일. */
internal fun clipNormalize(text: String): String {
    val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
    return nfc.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

// OpenAI CLIP 원조 정규식: 축약형(it's 등) 우선, 그 다음 연속 문자/연속 숫자/기타 기호 덩어리.
private val PRETOKENIZE_REGEX =
    Regex("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")

/** 정규화한 텍스트를 CLIP 정규식으로 "단어" 단위 조각으로 나눈다(공백 자체는 버려진다). */
internal fun clipPreTokenize(text: String): List<String> =
    PRETOKENIZE_REGEX.findAll(clipNormalize(text)).map { it.value }.toList()

/** 사전 분리된 조각 하나(예: "dog")를 UTF-8 바이트로 인코딩한 뒤 바이트→유니코드 표로 치환한다. */
internal fun byteLevelEncode(chunk: String, byteEncoder: Map<Int, Char>): String {
    val bytes = chunk.toByteArray(Charsets.UTF_8)
    val sb = StringBuilder(bytes.size)
    for (b in bytes) sb.append(byteEncoder.getValue(b.toInt() and 0xFF))
    return sb.toString()
}

/**
 * 바이트 레벨로 인코딩된 조각 하나에 BPE 병합을 반복 적용해 서브워드 목록으로 쪼갠다.
 * 마지막 글자에 `</w>`(단어 끝 표시)를 붙이고, 병합 우선순위(rank)가 가장 낮은(=먼저 병합해야 하는)
 * 인접 쌍을 더 이상 없을 때까지 합쳐나간다 — OpenAI CLIP 원조 BPE 알고리즘과 동일.
 */
internal fun bpeMerge(token: String, mergeRank: Map<Pair<String, String>, Int>): List<String> {
    if (token.length < 2) return listOf(token + "</w>")

    var word: List<String> = token.dropLast(1).map { it.toString() } + (token.last() + "</w>")
    var pairs = adjacentPairs(word)
    if (pairs.isEmpty()) return word

    while (true) {
        val bestPair = pairs.minByOrNull { mergeRank[it] ?: Int.MAX_VALUE } ?: break
        if (mergeRank[bestPair] == null) break

        val (first, second) = bestPair
        val merged = ArrayList<String>(word.size)
        var i = 0
        while (i < word.size) {
            if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                merged.add(first + second)
                i += 2
            } else {
                merged.add(word[i])
                i += 1
            }
        }
        word = merged
        if (word.size == 1) break
        pairs = adjacentPairs(word)
    }
    return word
}

private fun adjacentPairs(word: List<String>): Set<Pair<String, String>> =
    (0 until word.size - 1).map { word[it] to word[it + 1] }.toSet()
