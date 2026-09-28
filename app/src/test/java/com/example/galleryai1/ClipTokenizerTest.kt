package com.example.galleryai1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClipTokenizer]가 쓰는 순수 함수들(정규화/사전분리/바이트 인코딩/BPE 병합)을 검증한다.
 * 전체 알고리즘이 실제 HuggingFace 기준 구현과 토큰 id까지 정확히 일치하는지는 Python
 * (`tokenizers` 라이브러리)으로 12개 문장에 대해 먼저 검증한 뒤 이 로직을 그대로 옮겼다 —
 * 여기서는 포팅한 조각들이 각자 의도대로 동작하는지만 JVM에서 확인한다.
 */
class ClipTokenizerTest {

    @Test
    fun `바이트-유니코드 표는 256개 항목을 전부 서로 다른 문자로 매핑한다`() {
        val table = buildByteEncoder()
        assertEquals(256, table.size)
        assertEquals(256, table.values.toSet().size)
    }

    @Test
    fun `인쇄 가능한 아스키 바이트는 자기 자신으로 매핑된다`() {
        val table = buildByteEncoder()
        assertEquals('a', table.getValue('a'.code))
        assertEquals('!', table.getValue('!'.code))
    }

    @Test
    fun `정규화는 연속 공백을 하나로 줄이고 소문자화한다`() {
        // tokenizer.json의 정규화기는 공백을 하나로 "축소"만 하지 앞뒤를 잘라내진 않는다(trim 없음) —
        // 그래도 사전분리 정규식 자체가 공백을 매칭하지 않으므로 최종 토큰화 결과엔 영향이 없다.
        assertEquals(" hello world ", clipNormalize("  Hello   World  "))
    }

    @Test
    fun `사전분리는 문자 덩어리와 숫자를 각각 나누고 공백은 버린다`() {
        // 숫자는 정규식이 한 글자씩만 매칭한다(BPE 병합 단계에서 다시 합쳐짐) — CLIP 원조 동작.
        assertEquals(listOf("hello", "1", "2", "3"), clipPreTokenize("Hello 123"))
    }

    @Test
    fun `사전분리는 구두점을 별도 조각으로 분리한다`() {
        assertEquals(listOf("dog", ",", "cat", "!"), clipPreTokenize("dog, cat!"))
    }

    @Test
    fun `바이트레벨 인코딩은 인쇄 가능한 아스키 문자를 그대로 통과시킨다`() {
        assertEquals("cat", byteLevelEncode("cat", buildByteEncoder()))
    }

    @Test
    fun `병합 규칙이 없으면 글자 단위로만 쪼개고 마지막에 단어끝 표시를 붙인다`() {
        assertEquals(listOf("c", "a", "t</w>"), bpeMerge("cat", emptyMap()))
    }

    @Test
    fun `가장 순위가 낮은 병합부터 적용한다`() {
        val ranks = mapOf(("c" to "a") to 0)
        assertEquals(listOf("ca", "t</w>"), bpeMerge("cat", ranks))
    }

    @Test
    fun `한 글자 토큰은 병합 없이 단어끝 표시만 붙는다`() {
        assertEquals(listOf("a</w>"), bpeMerge("a", emptyMap()))
    }

    @Test
    fun `encode는 항상 길이 77로 고정되고 시작-끝 토큰을 포함한다`() {
        val vocab = mapOf("a</w>" to 100)
        val tokenizer = ClipTokenizer(vocab, emptyMap())
        val ids = tokenizer.encode("a")
        assertEquals(77, ids.size)
        assertEquals(ClipTokenizer.BOS_ID, ids[0])
        assertEquals(100, ids[1])
        assertEquals(ClipTokenizer.EOS_ID, ids[2])
        assertEquals(ClipTokenizer.PAD_ID, ids[3])
        assertNotEquals(ClipTokenizer.PAD_ID, ids[0])
    }

    @Test
    fun `77개를 넘는 문장은 잘리고 마지막 자리는 항상 eos로 마무리된다`() {
        // 위치 임베딩 테이블이 77칸 고정이라, 넘치면 자르되 정상적인 시퀀스 형태(끝이 eos)는 유지해야 한다.
        // 자르기 전엔 76번째 자리가 진짜 콘텐츠 토큰(id=5)이었을 걸 억지로 eos로 덮어써야 하므로,
        // vocab을 비워두지 않고 실제 값을 넣어서 "잘리는 자리"와 "eos로 덮이는 자리"가 다름을 확인한다.
        val tokenizer = ClipTokenizer(mapOf("a</w>" to 5), emptyMap())
        val longText = (1..100).joinToString(" ") { "a" }
        val ids = tokenizer.encode(longText)
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, ids.size)
        assertEquals(ClipTokenizer.EOS_ID, ids.last())
        // 마지막 바로 앞자리는 잘리기 전 원래 콘텐츠(5)가 그대로 남아있어야 한다 — 마지막 한 자리만 덮어썼다는 뜻.
        assertEquals(5, ids[ClipTokenizer.CONTEXT_LENGTH - 2])
        assertTrue(ids.none { it == ClipTokenizer.PAD_ID })
    }
}
