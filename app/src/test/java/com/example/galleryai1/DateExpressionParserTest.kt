package com.example.galleryai1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DateExpressionParserTest {

    // 기준일: 2026-09-03 (오늘 날짜 컨텍스트와 동일하게 맞춰서 테스트한다)
    private val today = LocalDate.of(2026, 9, 3)
    private val zone = ZoneId.systemDefault()

    private fun millisOf(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `재작년에는 2년 전 한 해 전체로 해석된다`() {
        val result = DateExpressionParser.parse("재작년에 태국식당에서 찍은 사진", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2024, 1, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2024, 12, 31)) in range)
        assertTrue(millisOf(LocalDate.of(2025, 1, 1)) !in range)
        assertTrue(millisOf(LocalDate.of(2023, 12, 31)) !in range)
        // "재작년"이 검색어에서 빠지고 나머지만 남아야 라벨 매칭에 엉뚱하게 끼어들지 않는다.
        assertTrue(!result.remainingQuery.contains("재작년"))
        assertTrue(result.remainingQuery.contains("태국식당"))
    }

    @Test
    fun `지난 여름은 이미 지난 올해 여름을 가리킨다`() {
        // 기준일(9월 3일)은 이미 올해 여름(6~8월)이 끝난 시점이므로 올해 여름이어야 한다.
        val result = DateExpressionParser.parse("지난 여름정도에 찍은 사진", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2026, 7, 15)) in range)
        assertTrue(millisOf(LocalDate.of(2026, 6, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2025, 7, 15)) !in range)
        assertTrue(millisOf(LocalDate.of(2026, 9, 1)) !in range)
    }

    @Test
    fun `아직 시작 전인 계절을 지난이라고 하면 작년 것을 가리킨다`() {
        // 3월 기준으로 "지난 여름"은 아직 올해 여름이 오지 않았으니 작년 여름이어야 한다.
        val marchToday = LocalDate.of(2026, 3, 1)
        val result = DateExpressionParser.parse("지난 여름 사진", marchToday)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2025, 7, 15)) in range)
        assertTrue(millisOf(LocalDate.of(2026, 7, 15)) !in range)
    }

    @Test
    fun `작년 여름처럼 연도와 계절이 함께 오면 그 조합으로 해석된다`() {
        val result = DateExpressionParser.parse("작년 여름 바다 사진", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2025, 7, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2026, 7, 1)) !in range)
    }

    @Test
    fun `겨울은 연말에서 다음 해 2월까지로 연도 경계를 넘는다`() {
        val result = DateExpressionParser.parse("작년 겨울 사진", today)
        val range = requireNotNull(result.dateRange)
        // 2025년 겨울 = 2025년 12월 ~ 2026년 2월
        assertTrue(millisOf(LocalDate.of(2025, 12, 15)) in range)
        assertTrue(millisOf(LocalDate.of(2026, 1, 20)) in range)
        assertTrue(millisOf(LocalDate.of(2026, 3, 1)) !in range)
    }

    @Test
    fun `시간 표현이 없으면 날짜 범위 없이 원문 그대로 돌려준다`() {
        val result = DateExpressionParser.parse("아기가 꽃밭에 있는 사진", today)
        assertNull(result.dateRange)
        assertEquals("아기가 꽃밭에 있는 사진", result.remainingQuery)
    }

    @Test
    fun `N년 전 표현도 해석된다`() {
        val result = DateExpressionParser.parse("3년 전에 찍은 사진", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2023, 6, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2024, 1, 1)) !in range)
    }

    @Test
    fun `2024년처럼 절대 연도만 말해도 그 해 전체로 해석된다`() {
        // 상대 표현(작년/재작년/올해)과 달리 "2024년"은 어느 규칙에도 안 걸려서 그대로 라벨
        // 매칭으로 새버리던 문제가 있었다 — 그 연도의 라벨은 존재할 리 없으니 항상 0건이고,
        // 그 전에 미분석 사진 전부를 무거운 모델로 분석해야 해서 느리기까지 했다.
        val result = DateExpressionParser.parse("2024년", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2024, 1, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2024, 12, 31)) in range)
        assertTrue(millisOf(LocalDate.of(2025, 1, 1)) !in range)
        assertTrue(millisOf(LocalDate.of(2023, 12, 31)) !in range)
        assertEquals("", result.remainingQuery)
    }

    @Test
    fun `2024년 여름처럼 절대 연도와 계절이 함께 오면 그 조합으로 해석된다`() {
        val result = DateExpressionParser.parse("2024년 여름 바다 사진", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2024, 7, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2025, 7, 1)) !in range)
        assertTrue(millisOf(LocalDate.of(2023, 7, 1)) !in range)
    }

    @Test
    fun `24년처럼 두 자리로 줄인 연도도 2024년으로 해석된다`() {
        // "2024년"은 되는데 "24년"으로 줄이면 안 되던 문제 — 4자리 규칙만 있고 2자리 규칙이 없었음.
        val result = DateExpressionParser.parse("24년", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2024, 1, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2024, 12, 31)) in range)
        assertTrue(millisOf(LocalDate.of(2025, 1, 1)) !in range)
        assertTrue(millisOf(LocalDate.of(2023, 12, 31)) !in range)
    }

    @Test
    fun `24년도처럼 뒤에 도가 붙어도 해석되고 남는 도는 필터링된다`() {
        val result = DateExpressionParser.parse("24년도", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2024, 1, 1)) in range)
        assertTrue(!SearchSynonyms.hasMeaningfulContent(result.remainingQuery))
    }

    @Test
    fun `24년 여름처럼 두 자리 연도와 계절이 함께 오면 그 조합으로 해석된다`() {
        val result = DateExpressionParser.parse("24년 여름 바다 사진", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(2024, 7, 1)) in range)
        assertTrue(millisOf(LocalDate.of(2025, 7, 1)) !in range)
    }

    @Test
    fun `N년 전 표현은 두 자리 절대 연도로 잘못 해석되지 않는다`() {
        // "24년 전"은 "2024년"이 아니라 "24년 전"(상대 연도)이어야 한다.
        val result = DateExpressionParser.parse("24년 전에 찍은 사진", today)
        val range = requireNotNull(result.dateRange)
        assertTrue(millisOf(LocalDate.of(today.year - 24, 6, 1)) in range)
    }

    @Test
    fun `68년은 2068년, 69년은 1969년으로 해석된다`() {
        // POSIX strptime %y 관례: 00~68 -> 2000년대, 69~99 -> 1900년대.
        val year68 = requireNotNull(DateExpressionParser.parse("68년", today).dateRange)
        assertTrue(millisOf(LocalDate.of(2068, 1, 1)) in year68)

        val year69 = requireNotNull(DateExpressionParser.parse("69년", today).dateRange)
        assertTrue(millisOf(LocalDate.of(1969, 1, 1)) in year69)
    }
}
