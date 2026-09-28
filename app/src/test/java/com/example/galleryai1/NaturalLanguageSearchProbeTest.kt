package com.example.galleryai1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MainActivity.performSearch()가 실제로 쓰는 매칭 로직(SearchSynonyms.expand() 결과 중
 * 하나라도 라벨 문자열에 포함되면 매치)을 그대로 재현해서, 진짜 자연어 문장으로 검색했을 때
 * 기대한 사진이 걸리는지 / 관련 없는 사진이 엉뚱하게 걸리지는 않는지 검증한다.
 */
class NaturalLanguageSearchProbeTest {

    // performSearch()의 핵심 매칭 로직 그대로: haystack(AI+사용자 라벨을 합친 문자열)에
    // 확장된 검색어 중 하나라도 포함되면 매치.
    private fun matches(query: String, labels: List<String>): Boolean {
        val haystack = labels.joinToString(" ").lowercase()
        return SearchSynonyms.expand(query).any { haystack.contains(it) }
    }

    // ---------- 자연어 문장으로도 원하는 사진이 잘 걸리는지 ----------

    @Test
    fun `자연어 문장 속 키워드로 AI 영어 라벨 사진을 찾는다`() {
        val dogPhotoLabels = listOf("Dog", "Mammal", "Pet")
        assertTrue(matches("귀여운 강아지 사진 보여줘", dogPhotoLabels))
        assertTrue(matches("우리 강아지 어디갔지", dogPhotoLabels))
    }

    @Test
    fun `조사가 붙어도 매칭된다`() {
        val beachPhotoLabels = listOf("Sea", "Beach", "Sky")
        assertTrue(matches("바다에서 찍은 사진", beachPhotoLabels))
        assertTrue(matches("바다가 예뻤던 날", beachPhotoLabels))
    }

    @Test
    fun `영어 문장으로 검색해도 매칭된다`() {
        val catPhotoLabels = listOf("Cat", "Whiskers")
        assertTrue(matches("photo of a cute cat", catPhotoLabels))
    }

    @Test
    fun `사용자가 직접 붙인 한글 라벨은 그대로 검색된다`() {
        // 사전에 없는 고유명사(사용자 라벨)라도 검색어와 라벨이 똑같으면 매칭돼야 함
        val labels = listOf("Person", "제주도여행", "가족")
        assertTrue(matches("제주도여행", labels))
    }

    // ---------- 관련 없는 사진이 엉뚱하게 걸리지 않는지 (오탐 검증) ----------

    @Test
    fun `지명에 포함된 음절 때문에 무관한 라벨과 오매칭되면 안 된다`() {
        // "부산"에 "산"이 음절로 포함되어 있다고 해서 Mountain 라벨 사진이 걸리면 안 됨
        val mountainPhotoLabels = listOf("Mountain", "Nature")
        assertFalse("부산 여행 사진인데 '산'이 부분일치해서 산 사진이 걸림", matches("부산 여행 사진", mountainPhotoLabels))
    }

    @Test
    fun `선물이라는 단어 때문에 물 라벨과 오매칭되면 안 된다`() {
        val waterPhotoLabels = listOf("Water", "Bottle")
        assertFalse("'선물'에 '물'이 부분일치해서 water 사진이 걸림", matches("생일 선물 뜯는 중", waterPhotoLabels))
    }

    @Test
    fun `동물이라는 단어 때문에 물 라벨과 오매칭되면 안 된다`() {
        val waterPhotoLabels = listOf("Water")
        assertFalse(matches("동물원에서 찍은 사진", waterPhotoLabels))
    }
}
