package com.example.galleryai1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSynonymsTest {

    @Test
    fun `한글 검색어는 대응하는 영어 라벨을 포함하도록 확장된다`() {
        val terms = SearchSynonyms.expand("가족")
        assertTrue(terms.contains("family"))
    }

    @Test
    fun `한글 검색어가 영어 동의어 여러 개로 확장될 수 있다`() {
        val terms = SearchSynonyms.expand("강아지")
        assertTrue(terms.contains("dog"))
        assertTrue(terms.contains("puppy"))
    }

    @Test
    fun `영어 검색어는 대응하는 한글 단어도 포함하도록 확장된다`() {
        val terms = SearchSynonyms.expand("dog")
        assertTrue(terms.contains("강아지"))
        assertTrue(terms.contains("개"))
    }

    @Test
    fun `사전에 없는 검색어는 원래 검색어만 그대로 반환된다`() {
        val terms = SearchSynonyms.expand("트립")
        assertTrue(terms.contains("트립"))
        assertFalse(terms.contains("trip"))
    }

    @Test
    fun `빈 검색어는 빈 집합을 반환한다`() {
        assertTrue(SearchSynonyms.expand("   ").isEmpty())
    }

    @Test
    fun `대소문자와 공백은 무시하고 매칭된다`() {
        val terms = SearchSynonyms.expand("  DOG  ")
        assertTrue(terms.contains("강아지"))
    }

    @Test
    fun `실제 테스트에서 나온 동물원 계열 AI 태그도 한글로 검색된다`() {
        assertTrue(SearchSynonyms.expand("돌고래").contains("dolphin"))
        assertTrue(SearchSynonyms.expand("원숭이").contains("monkey"))
        assertTrue(SearchSynonyms.expand("기린").contains("giraffe"))
        assertTrue(SearchSynonyms.expand("미어캣").contains("meerkat"))
        assertTrue(SearchSynonyms.expand("동물원").contains("zoo"))
    }

    @Test
    fun `수중 사물 옷차림 관련 AI 태그도 한글로 검색된다`() {
        assertTrue(SearchSynonyms.expand("수중").contains("underwater"))
        assertTrue(SearchSynonyms.expand("청바지").contains("jeans"))
        assertTrue(SearchSynonyms.expand("바위").contains("rock"))
        assertTrue(SearchSynonyms.expand("식기").contains("tableware"))
    }

    @Test
    fun `PreciseTagger가 붙이는 세부 동물 라벨도 한글로 검색된다`() {
        // 거북이는 기본 모델(AutoTagger)만으로는 못 잡고 PreciseTagger가 "terrapin" 등으로 잡아준다.
        assertTrue(SearchSynonyms.expand("거북이").contains("turtle"))
        assertTrue(SearchSynonyms.expand("거북이").contains("terrapin"))
        assertTrue(SearchSynonyms.expand("곰").contains("bear"))
        assertTrue(SearchSynonyms.expand("여우").contains("fox"))
        assertTrue(SearchSynonyms.expand("앵무새").contains("parrot"))
    }

    @Test
    fun `불용어뿐인 검색어는 의미 있는 내용이 없다고 판단한다`() {
        // "2024년 사진 찾아줘"에서 DateExpressionParser가 "2024년"을 걷어내고 나면
        // 남는 텍스트가 이런 형태가 된다 — 날짜 전용 검색인지 MainActivity가 이걸로 판별한다.
        assertFalse(SearchSynonyms.hasMeaningfulContent("사진 찾아줘"))
        assertFalse(SearchSynonyms.hasMeaningfulContent("사진을 보여줘"))
        assertFalse(SearchSynonyms.hasMeaningfulContent(""))
        assertFalse(SearchSynonyms.hasMeaningfulContent("   "))
    }

    @Test
    fun `실제 찾을 대상이 남아있으면 의미 있는 내용이 있다고 판단한다`() {
        assertTrue(SearchSynonyms.hasMeaningfulContent("아들과 식당에서 찍은 사진"))
        assertTrue(SearchSynonyms.hasMeaningfulContent("강아지"))
    }
}
