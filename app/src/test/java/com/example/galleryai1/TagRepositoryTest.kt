package com.example.galleryai1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TagRepository]가 쓰는 순수 함수들(사진 삭제 감지 diff, 태그 유사도 상위-K 선별, 임베딩
 * 바이트 packing)을 검증한다. Room/MobileClipEngine 의존 부분은 안드로이드 계측 테스트 영역이라
 * 여기서는 다루지 않는다(이 프로젝트의 다른 순수 함수 테스트들과 같은 방침).
 */
class TagRepositoryTest {

    @Test
    fun `사진첩에서 사라진 URI만 삭제 대상으로 고른다`() {
        val stored = setOf("a", "b", "c")
        val existing = setOf("a", "c")
        assertEquals(listOf("b"), computeStaleUriKeys(stored, existing))
    }

    @Test
    fun `전부 남아있으면 삭제 대상이 없다`() {
        val stored = setOf("a", "b")
        assertTrue(computeStaleUriKeys(stored, stored).isEmpty())
    }

    @Test
    fun `임베딩을 바이트로 packing했다가 복원하면 원래 값과 같다`() {
        val original = floatArrayOf(0.1f, -0.2f, 0.0f, 1.0f, -1.0f)
        val roundTripped = bytesToEmbedding(embeddingToBytes(original))
        assertArrayEquals(original, roundTripped, 1e-6f)
    }

    private fun tag(en: String, embedding: FloatArray) =
        TagMasterEntity(tagNameEn = en, tagNameKo = en, embedding = embeddingToBytes(embedding))

    @Test
    fun `임계값 미만인 태그는 제외한다`() {
        val query = floatArrayOf(1f, 0f)
        val tags = listOf(
            tag("close", floatArrayOf(1f, 0f)),      // sim = 1.0
            tag("far", floatArrayOf(0f, 1f))          // sim = 0.0
        )
        val result = topMatchingTags(query, tags, topK = 10, threshold = 0.5f)
        assertEquals(1, result.size)
        assertEquals("close", result[0].first.tagNameEn)
    }

    @Test
    fun `상위 K개만 유사도 내림차순으로 돌려준다`() {
        // cosineSimilarityOfNormalized는 순수 내적이라, 벡터가 단위 길이가 아니어도 내적값
        // 순서로 정렬이 되는지만 확인하면 된다.
        val query = floatArrayOf(1f, 0f)
        val tags = listOf(
            tag("low", floatArrayOf(0.5f, 10f)),
            tag("high", floatArrayOf(0.95f, 0.1f)),
            tag("mid", floatArrayOf(0.8f, 0.6f))
        )
        val result = topMatchingTags(query, tags, topK = 2, threshold = 0f)
        assertEquals(2, result.size)
        assertEquals("high", result[0].first.tagNameEn)
        assertEquals("mid", result[1].first.tagNameEn)
    }

    @Test
    fun `1위와 유사도 차이가 큰 후보는 임계값을 넘어도 상대 마진에 걸러진다`() {
        // 실제로 신고된 사례(돌고래 사진에 거북이 태그처럼, 1위는 확실한데 애매하게 걸치는 태그가
        // 줄줄이 붙는 문제)를 재현한다 — "mid"는 threshold(0.15)는 넘지만 1위(dolphin=0.9)와
        // 0.08 이상 차이나므로 제외돼야 한다.
        val query = floatArrayOf(1f, 0f)
        val tags = listOf(
            tag("dolphin", floatArrayOf(0.9f, 0.1f)),
            tag("turtle", floatArrayOf(0.2f, 0.1f))
        )
        val result = topMatchingTags(query, tags, topK = 10, threshold = 0.15f, relativeMargin = 0.08f)
        assertEquals(listOf("dolphin"), result.map { it.first.tagNameEn })
    }

    @Test
    fun `1위와 충분히 가까운 후보는 상대 마진을 통과한다`() {
        val query = floatArrayOf(1f, 0f)
        val tags = listOf(
            tag("dog", floatArrayOf(0.9f, 0.1f)),
            tag("pet", floatArrayOf(0.85f, 0.1f))
        )
        val result = topMatchingTags(query, tags, topK = 10, threshold = 0.15f, relativeMargin = 0.08f)
        assertEquals(listOf("dog", "pet"), result.map { it.first.tagNameEn })
    }

    @Test
    fun `마진을 안 주면(기본값) 절대 임계값만 적용된다`() {
        val query = floatArrayOf(1f, 0f)
        val tags = listOf(
            tag("dolphin", floatArrayOf(0.9f, 0.1f)),
            tag("turtle", floatArrayOf(0.2f, 0.1f))
        )
        val result = topMatchingTags(query, tags, topK = 10, threshold = 0.15f)
        assertEquals(2, result.size)
    }

    // --- suppressYoloCompetingClipTags: 2026-09-14, precision 개선(측정 하네스로 확인된 실제
    // 오탐 패턴 — 트럭 사진에 "버스", 백팩 사진에 "스키"/"스노보드" — 를 재현) ---

    private val fakeCocoMap = mapOf("bus" to "bus", "skis" to "skis", "truck" to "truck")

    @Test
    fun `YOLO가 이미 없다고 판정한 COCO 클래스는 CLIP 후보에서 제거된다`() {
        // 실제 사례 재현: 트럭 사진 - YOLO는 truck만 찾았고 bus는 못 찾았는데, CLIP은 bus도 후보로 냄.
        val clipMatches = listOf(tag("truck", floatArrayOf(1f, 0f)) to 0.24f, tag("bus", floatArrayOf(1f, 0f)) to 0.19f)
        val yoloDetections = mapOf("truck" to 0.24f) // bus는 YOLO가 못 찾음(신뢰도 미달이라 맵에 아예 없음)
        val result = suppressYoloCompetingClipTags(clipMatches, yoloDetections, fakeCocoMap)
        assertEquals(listOf("truck"), result.map { it.first.tagNameEn })
    }

    @Test
    fun `YOLO도 같이 찾은 COCO 클래스는 CLIP 후보에서 안 빠진다`() {
        val clipMatches = listOf(tag("bus", floatArrayOf(1f, 0f)) to 0.19f)
        val yoloDetections = mapOf("bus" to 0.55f) // YOLO도 실제로 찾음
        val result = suppressYoloCompetingClipTags(clipMatches, yoloDetections, fakeCocoMap)
        assertEquals(listOf("bus"), result.map { it.first.tagNameEn })
    }

    @Test
    fun `COCO에 아예 없는 개념은 YOLO 판정과 무관하게 그대로 남는다`() {
        val clipMatches = listOf(tag("dolphin", floatArrayOf(1f, 0f)) to 0.27f)
        val result = suppressYoloCompetingClipTags(clipMatches, emptyMap(), fakeCocoMap)
        assertEquals(listOf("dolphin"), result.map { it.first.tagNameEn })
    }

    @Test
    fun `YOLO가 이 사진에서 아무것도 못 찾았으면 아무 것도 억제하지 않는다`() {
        // 실제로 이 가드 없이 배포했다가 사고가 남: 특이한 구도의 트럭/백팩/와인잔 사진에서
        // YOLO 자신도 그 클래스를 놓쳤는데(신뢰도 미달이라 맵이 비어있음) CLIP은 정확히 맞춤 —
        // "YOLO가 없다고 했다"는 신호가 이 사진 자체엔 탐지기가 안 맞았다는 뜻일 뿐이라, 완전히
        // 빈 yoloDetections는 어떤 클래스도 "없다"고 확정할 근거가 못 된다.
        val clipMatches = listOf(tag("skis", floatArrayOf(1f, 0f)) to 0.24f, tag("dolphin", floatArrayOf(1f, 0f)) to 0.27f)
        val result = suppressYoloCompetingClipTags(clipMatches, emptyMap(), fakeCocoMap)
        assertEquals(listOf("skis", "dolphin"), result.map { it.first.tagNameEn })
    }
}
