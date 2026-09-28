package com.example.galleryai1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildTranslationGroup]에 대한 유닛테스트.
 * (TranslationTagger 나머지는 ML Kit Translator(Android 런타임 의존)에 기대므로
 * JVM 유닛테스트로는 못 돌리고, 번역 결과를 그룹으로 합치는 순수 로직만 검증한다.)
 */
class TranslationTaggerTest {

    @Test
    fun `원문 토큰과 번역 결과를 모두 포함한다`() {
        val group = buildTranslationGroup("군복", "military uniform")

        assertTrue(group.contains("군복"))
        assertTrue(group.contains("military uniform"))
    }

    @Test
    fun `번역 결과는 소문자로 정규화된다`() {
        val group = buildTranslationGroup("강아지", "Dog")

        assertTrue(group.contains("dog"))
        assertFalse(group.contains("Dog"))
    }

    @Test
    fun `번역이 실패해도(null) 원문 토큰은 남는다`() {
        val group = buildTranslationGroup("트립", null)

        assertTrue(group.contains("트립"))
    }

    @Test
    fun `사전에 등록된 단어면 동의어까지 보강된다`() {
        // "강아지"는 사전에 "puppy"까지 등록돼 있다 — 번역이 "dog"만 줘도 puppy가 같이 들어와야 한다.
        val group = buildTranslationGroup("강아지", "dog")

        assertTrue(group.contains("puppy"))
    }

    @Test
    fun `빈 번역 결과는 무시된다`() {
        val group = buildTranslationGroup("트립", "   ")

        assertFalse(group.contains("   "))
        assertTrue(group.contains("트립"))
    }
}
