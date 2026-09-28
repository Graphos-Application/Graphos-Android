package com.example.galleryai1

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [calculateSampleSize]에 대한 유닛테스트.
 * (TaggingImageLoader 나머지는 ContentResolver/BitmapFactory에 의존해서 JVM 유닛테스트로는
 * 못 돌리므로, 서브샘플링 배율 계산 로직만 순수 함수로 분리해서 검증한다.)
 */
class TaggingImageLoaderTest {

    @Test
    fun `이미 목표 크기보다 작으면 축소하지 않는다`() {
        assertEquals(1, calculateSampleSize(800, 600, 1024))
        assertEquals(1, calculateSampleSize(1024, 768, 1024))
    }

    @Test
    fun `200MP급 초고해상도 사진은 큰 배율로 축소된다`() {
        // 16320x12240 (약 2억 화소, S24 Ultra 200MP 모드) -> 긴 변이 1024 근처로 줄어야 한다.
        val sampleSize = calculateSampleSize(16320, 12240, 1024)
        assertEquals(8, sampleSize)
        assertEquals(2040, 16320 / sampleSize)
    }

    @Test
    fun `가로가 긴 파노라마 사진도 긴 변 기준으로 축소된다`() {
        // 세로가 이미 작아도(800) 가로가 훨씬 길면(6000) 가로 기준으로 축소해야 한다.
        val sampleSize = calculateSampleSize(6000, 800, 1024)
        assertEquals(4, sampleSize)
        assertEquals(1500, 6000 / sampleSize)
    }

    @Test
    fun `세로 사진도 동일하게 동작한다`() {
        val sampleSize = calculateSampleSize(3000, 4000, 1024)
        assertEquals(2, sampleSize)
    }

    @Test
    fun `잘못된 목표 크기가 들어와도 안전하게 1을 반환한다`() {
        assertEquals(1, calculateSampleSize(4000, 3000, 0))
        assertEquals(1, calculateSampleSize(4000, 3000, -100))
    }
}
