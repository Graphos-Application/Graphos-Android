package com.example.galleryai1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [letterboxTransform]/[bestConfidencePerClass]/[COCO_TO_TAG_EN]에 대한 유닛테스트.
 * (YoloEngine 나머지는 Bitmap/ONNX Runtime/Context에 의존해서 JVM 유닛테스트로는 못 돌리므로,
 * [TaggingImageLoaderTest]와 같은 패턴으로 순수 로직만 분리해서 검증한다.)
 */
class YoloEngineTest {

    @Test
    fun `가로가 더 긴 이미지는 가로 기준으로 축소되고 세로에 여백이 생긴다`() {
        // 1280x720 -> 640 정사각형: 가로 기준 스케일 0.5, 세로는 360으로 줄고 위아래 140씩 여백.
        val t = letterboxTransform(1280, 720, 640)
        assertEquals(0.5f, t.scale, 0.0001f)
        assertEquals(640, t.resizedWidth)
        assertEquals(360, t.resizedHeight)
        assertEquals(0, t.padX)
        assertEquals(140, t.padY)
    }

    @Test
    fun `세로가 더 긴 이미지는 세로 기준으로 축소되고 가로에 여백이 생긴다`() {
        val t = letterboxTransform(720, 1280, 640)
        assertEquals(360, t.resizedWidth)
        assertEquals(640, t.resizedHeight)
        assertEquals(140, t.padX)
        assertEquals(0, t.padY)
    }

    @Test
    fun `정사각형 이미지는 여백 없이 딱 맞게 줄어든다`() {
        val t = letterboxTransform(1000, 1000, 640)
        assertEquals(640, t.resizedWidth)
        assertEquals(640, t.resizedHeight)
        assertEquals(0, t.padX)
        assertEquals(0, t.padY)
    }

    @Test
    fun `클래스별로 전체 앵커 중 최고 신뢰도만 남는다`() {
        // raw: [4 + numClasses]개 행 x numAnchors개 열. 앞 4행(박스 좌표)은 무시돼야 한다.
        val numClasses = 3
        val numAnchors = 4
        val raw = Array(4 + numClasses) { row ->
            FloatArray(numAnchors) { col ->
                when (row) {
                    0, 1, 2, 3 -> 999f // 박스 좌표(무시되어야 함) — 신뢰도로 잘못 섞이면 테스트가 잡아냄
                    4 -> floatArrayOf(0.1f, 0.9f, 0.2f, 0.05f)[col] // person 클래스, 최고 0.9
                    5 -> floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f)[col] // bicycle 클래스, 전부 동일 0.3
                    else -> 0f // car 클래스, 전부 0(탐지 없음)
                }
            }
        }
        val best = bestConfidencePerClass(raw, numClasses)
        assertEquals(0.9f, best[0], 0.0001f)
        assertEquals(0.3f, best[1], 0.0001f)
        assertEquals(0f, best[2], 0.0001f)
    }

    @Test
    fun `COCO 매핑은 person을 포함하고 애매한 클래스는 여전히 빠져있다`() {
        assertEquals("person", COCO_TO_TAG_EN["person"])
        // 2026-09-14: 사전에 대응 개념이 없던 46개 클래스(toothbrush 포함)를 새로 매핑함
        // ([[galleryai1-yolo-hybrid-tagging]] 확장) — 여러 종목이 겹쳐 애매한 것과 국내 사진에
        // 드문 미국 도로표지류만 계속 제외한다.
        assertEquals("toothbrush", COCO_TO_TAG_EN["toothbrush"])
        assertTrue("sports ball처럼 여러 종목이 겹쳐 애매한 매핑은 일부러 뺐다", !COCO_TO_TAG_EN.containsKey("sports ball"))
        assertTrue("stop sign처럼 국내 사진에 드문 미국 도로표지류도 뺐다", !COCO_TO_TAG_EN.containsKey("stop sign"))
    }

    @Test
    fun `COCO_CLASSES는 80개이고 매핑 대상 클래스는 전부 그 목록 안에 있다`() {
        assertEquals(80, COCO_CLASSES.size)
        for (cocoClass in COCO_TO_TAG_EN.keys) {
            assertTrue("'$cocoClass'가 COCO_CLASSES에 없음", COCO_CLASSES.contains(cocoClass))
        }
    }
}
