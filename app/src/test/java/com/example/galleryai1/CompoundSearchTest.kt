package com.example.galleryai1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchSynonyms.expandGrouped]가 만드는 개념 그룹들을 MainActivity.searchWithCaptionSupport와
 * 동일한 방식(그룹 간 AND, 그룹 내 동의어는 OR)으로 매칭해서, 복합 문장 검색의 정확도를 검증한다.
 */
class CompoundSearchTest {

    private fun matches(query: String, labels: List<String>): Boolean {
        val haystack = labels.joinToString(" ").lowercase()
        val groups = SearchSynonyms.expandGrouped(query)
        return groups.all { group -> group.any { haystack.contains(it) } }
    }

    @Test
    fun `아기와 꽃밭이 둘 다 있는 사진만 걸린다`() {
        val babyAndFlowerPhoto = listOf("Baby", "Flower", "Field")
        val onlyBabyPhoto = listOf("Baby", "Indoor")
        val onlyFlowerPhoto = listOf("Flower", "Field")

        assertTrue(matches("아기가 꽃밭에 있는 사진을 찾아줘", babyAndFlowerPhoto))
        assertFalse("아기만 있고 꽃밭은 없는데 걸리면 안 됨", matches("아기가 꽃밭에 있는 사진을 찾아줘", onlyBabyPhoto))
        assertFalse("꽃밭만 있고 아기는 없는데 걸리면 안 됨", matches("아기가 꽃밭에 있는 사진을 찾아줘", onlyFlowerPhoto))
    }

    @Test
    fun `기존 안드로이드 갤러리의 약점이던 복합 검색이 정확히 걸러진다`() {
        // "강아지가 바다에서 노는 사진" - 강아지와 바다가 둘 다 있어야 함
        val dogAtBeach = listOf("Dog", "Sea", "Beach")
        val dogAtHome = listOf("Dog", "Indoor", "Sofa")
        val personAtBeach = listOf("Person", "Sea", "Beach")

        assertTrue(matches("강아지가 바다에서 노는 사진", dogAtBeach))
        assertFalse(matches("강아지가 바다에서 노는 사진", dogAtHome))
        assertFalse(matches("강아지가 바다에서 노는 사진", personAtBeach))
    }

    @Test
    fun `식당 관련 검색은 restaurant 라벨과 매칭된다`() {
        val restaurantPhoto = listOf("Restaurant", "Food", "Table")
        assertTrue(matches("식당에서 찍은 사진", restaurantPhoto))
        // "태국식당"처럼 특정 음식 종류가 붙은 복합어도 "식당" 부분만으로 매칭된다
        // (태국 음식인지 자체는 지금 모델로는 확인할 수 없다는 한계가 있음)
        assertTrue(matches("태국식당에서 찍은 사진", restaurantPhoto))
    }

    @Test
    fun `단일 개념 검색은 기존과 동일하게 동작한다`() {
        assertTrue(matches("강아지", listOf("Dog", "Pet")))
        assertFalse(matches("강아지", listOf("Cat", "Pet")))
    }

    @Test
    fun `사진 찾아줘처럼 의미 있는 단어가 없으면 매칭에 걸리지 않는다`() {
        // "사진", "찾아줘" 둘 다 불용어라 개념 그룹이 안 만들어지고, 그러면 expand() 폴백을 쓴다.
        // 이 경우 원문 전체("사진 찾아줘")가 라벨에 그대로 들어있을 리 없으니 매칭 안 되는 게 맞다.
        assertFalse(matches("사진 찾아줘", listOf("Dog", "Cat")))
    }
}
