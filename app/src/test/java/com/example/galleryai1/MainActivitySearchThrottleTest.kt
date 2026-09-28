package com.example.galleryai1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldRenderSearchProgress]에 대한 유닛테스트 — 검색 중 태깅 진행 화면 갱신을 throttle하는 로직.
 * 사진이 많을 때(수백 장) 사진 한 장 끝날 때마다 Tier1 DB를 재조회하면 검색이 느려지는 문제를
 * 막으려고 넣은 로직이라, 아래 세 가지가 항상 성립해야 한다: 마지막 사진은 무조건 갱신, 간격이
 * 충분히 지나면 갱신, 그 사이엔 건너뜀.
 */
class MainActivitySearchThrottleTest {

    @Test
    fun `마지막 사진이면 간격과 무관하게 항상 갱신한다`() {
        assertTrue(shouldRenderSearchProgress(isLast = true, nowMs = 100, lastRenderMs = 99, throttleMs = 250))
    }

    @Test
    fun `throttle 간격이 안 지났으면 갱신하지 않는다`() {
        assertFalse(shouldRenderSearchProgress(isLast = false, nowMs = 100, lastRenderMs = 0, throttleMs = 250))
    }

    @Test
    fun `throttle 간격이 정확히 지나면 갱신한다`() {
        assertTrue(shouldRenderSearchProgress(isLast = false, nowMs = 250, lastRenderMs = 0, throttleMs = 250))
    }

    @Test
    fun `throttle 간격을 한참 넘기면 당연히 갱신한다`() {
        assertTrue(shouldRenderSearchProgress(isLast = false, nowMs = 10_000, lastRenderMs = 0, throttleMs = 250))
    }
}
