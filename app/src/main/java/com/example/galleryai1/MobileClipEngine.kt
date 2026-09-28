package com.example.galleryai1

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ML Kit 4단 태깅 체인(AutoTagger/PreciseTagger/AiCaptioner/AiPromptTagger/FaceTagger)을 대체하는
 * 단일 비전-언어 모델 — Apple MobileCLIP(S0)의 ONNX 변환본(Xenova/mobileclip_s0, fp16)을
 * ONNX Runtime Mobile로 돌린다. 이미지와 텍스트를 같은 512차원 공간에 임베딩해서, 사진과 텍스트
 * 사이의 코사인 유사도만으로 제로샷 태깅과 자연어 검색을 둘 다 처리한다(더 이상 Gemini Nano
 * AICore 승인이나 기기별 지원 여부에 의존하지 않는다 — 모든 계산이 이 모델 하나로 완전 로컬).
 *
 * 모델 정밀도: 원래 배포되는 int8 동적 양자화본(`*_quantized.onnx`, 각 12MB/43MB)은 실제로 돌려보면
 * 텍스트 인코더 정확도가 크게 무너져서(모든 이미지에 "person"이 1위로 나오는 등 이미지 구분이 사실상
 * 안 됨) 못 쓴다. 처음엔 fp16(vision 23MB/text 85MB, 총 108MB)을 채택했었는데 — PC(x86_64,
 * onnxruntime CPU EP)에서 5개 검증 이미지 전부 정답이라 통과시켰지만, **실기기(S24 Ultra,
 * arm64-v8a)에 배포해보니 vision 임베딩이 100% NaN으로 나와 태그가 전혀 안 붙는 버그가 났다**
 * (2026-09-07, [[galleryai1-s24-realdevice-session]] 참고 — Room DB를 직접 까봐서 확인함).
 * x86_64는 fp16을 소프트웨어로 fp32 업캐스트해서 계산하는 반면, arm64는 ARM NEON 네이티브 fp16
 * 커널을 타서 LayerNorm 분산 계산 등에서 정밀도 문제로 NaN이 나는 것으로 추정 — PC 검증만으로는
 * 못 잡는 종류의 기기별 버그였다. **fp32 원본(vision 45MB/text 170MB, 총 215MB)으로 교체**해서
 * 해결함 — 기획서의 "30~50MB" 목표와는 더 멀어졌지만, 정확도보다 먼저 "정상 동작"이 우선이었다.
 *
 * 전처리는 Xenova의 `preprocessor_config.json`과 timm/open_clip의 실제 pretrained_cfg(mean=0,
 * std=1 — 일반적인 CLIP과 달리 평균/표준편차 정규화가 아예 없다)를 직접 검증해서 확인했다:
 * 짧은 변 256으로 리사이즈(bilinear) → 256x256 중앙 크롭 → 0~1로 스케일(그 이상 정규화 없음).
 */
object MobileClipEngine {
    const val EMBED_DIM = 512
    private const val IMAGE_SIZE = 256

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var visionSession: OrtSession? = null
    private var textSession: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (visionSession != null) return
        val appContext = context.applicationContext
        val options = OrtSession.SessionOptions()
        // 주의: 예전엔 assets.open(...).readBytes()로 모델 전체를 자바 힙에 한 번에 올렸는데,
        // text_model.onnx(85MB)가 에뮬레이터(Pixel_6_Pro_2)에서 실제로 OutOfMemoryError를 냈다
        // (다른 할당까지 겹쳐 힙이 106MB 남았을 때 85MB 단일 배열 할당 시도 — logcat으로 확인).
        // 파일 경로로 세션을 만들면 ONNX Runtime이 네이티브 쪽에서 직접 읽어서, 이 85MB가 자바 힙
        // 성장 한도(growth limit)에 아예 안 걸린다. assets는 APK 안에서 압축돼 있을 수 있어 파일
        // 경로로 바로 못 쓰므로, 최초 1회만 내부 저장소로 스트리밍 복사(64KB 버퍼, 메모리에 전체를
        // 올리지 않음)해 두고 그 다음부턴 이미 복사된 파일을 재사용한다.
        val visionPath = copyAssetToFilesDir(appContext, "mobileclip/vision_model.onnx", "vision_model_v$MODEL_ASSET_VERSION.onnx")
        val textPath = copyAssetToFilesDir(appContext, "mobileclip/text_model.onnx", "text_model_v$MODEL_ASSET_VERSION.onnx")
        visionSession = env.createSession(visionPath, options)
        textSession = env.createSession(textPath, options)
        tokenizer = ClipTokenizer.loadFromAssets(appContext)
    }

    // 2026-09-07: fp16(v1) 모델이 실기기(arm64-v8a)에서 NaN 임베딩을 내는 게 확인돼(에뮬레이터
    // x86_64에선 fp16을 fp32로 소프트웨어 업캐스트해서 계산해 문제가 안 드러났던 것으로 추정 —
    // 실기기는 ARM NEON 네이티브 fp16 커널을 타면서 LayerNorm 분산 계산 등에서 정밀도 문제로
    // NaN이 발생한 것으로 보임) fp32 원본으로 교체(v2). 파일명에 버전을 넣은 이유: 파일명이
    // 같으면 아래 copyAssetToFilesDir()가 "이미 있으니 재사용"하고 넘어가는데, `adb install -r`/
    // 앱 업데이트는 내부 저장소(filesDir)를 지우지 않아서 파일명을 안 바꾸면 새 assets로 교체해도
    // 기기에 이미 깔려 있던 예전 버전 파일을 계속 쓰게 된다. 모델을 바꿀 때마다 이 값을 올릴 것 —
    // [[galleryai1-mobileclip-migration]]/[[galleryai1-s24-realdevice-session]] 참고.
    private const val MODEL_ASSET_VERSION = 2

    private fun copyAssetToFilesDir(context: Context, assetPath: String, fileName: String): String {
        val dir = File(context.filesDir, "mobileclip").apply { mkdirs() }
        val outFile = File(dir, fileName)
        if (!outFile.exists()) {
            // 복사 도중 프로세스가 죽어도 절반짜리 파일이 최종 이름으로 남지 않도록, 임시 파일에
            // 다 쓴 뒤 성공했을 때만 이름을 바꾼다(다음 실행에서 exists() 체크로 재복사 여부 결정).
            val tempFile = File(dir, "$fileName.tmp")
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 16) }
            }
            tempFile.renameTo(outFile)
        }
        return outFile.absolutePath
    }

    /** 비트맵 하나를 512차원 L2-정규화 임베딩으로 바꾼다. 모델 로딩·추론이 무거우니 백그라운드 스레드에서 호출할 것. */
    @Synchronized
    fun embedImage(context: Context, bitmap: Bitmap): FloatArray {
        ensureLoaded(context)
        val session = visionSession!!
        val pixels = preprocessBitmap(bitmap, IMAGE_SIZE)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()))
            .use { inputTensor ->
                session.run(mapOf("pixel_values" to inputTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (result[0].value as Array<FloatArray>)[0]
                    return l2Normalize(out)
                }
            }
    }

    /** 검색어(번역된 영어 권장) 하나를 512차원 L2-정규화 임베딩으로 바꾼다. */
    @Synchronized
    fun embedText(context: Context, text: String): FloatArray {
        ensureLoaded(context)
        val session = textSession!!
        val ids = tokenizer!!.encode(text)
        val idsLong = LongArray(ids.size) { ids[it].toLong() }
        OnnxTensor.createTensor(env, LongBuffer.wrap(idsLong), longArrayOf(1, idsLong.size.toLong()))
            .use { inputTensor ->
                session.run(mapOf("input_ids" to inputTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (result[0].value as Array<FloatArray>)[0]
                    return l2Normalize(out)
                }
            }
    }
}

/** 두 벡터가 이미 L2-정규화돼 있다는 전제 하의 코사인 유사도(= 내적). */
internal fun cosineSimilarityOfNormalized(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}

internal fun l2Normalize(v: FloatArray): FloatArray {
    var sumSq = 0f
    for (x in v) sumSq += x * x
    val norm = sqrt(sumSq)
    if (norm == 0f) return v.copyOf()
    return FloatArray(v.size) { v[it] / norm }
}

/**
 * MobileCLIP 비전 인코더 입력 형식으로 비트맵을 변환하는 순수 로직: 짧은 변을 [size]로 리사이즈,
 * 중앙 [size]x[size] 크롭, 0~1 스케일, CHW(채널 우선) float32 평탄 배열.
 * (Bitmap 자체는 순수 함수로 못 옮기지만, 계산 로직을 여기 모아 MobileClipEngine 밖에서도 읽기 쉽게 했다.)
 */
internal fun preprocessBitmap(bitmap: Bitmap, size: Int): FloatArray {
    val w = bitmap.width
    val h = bitmap.height
    val scale = size.toFloat() / minOf(w, h)
    val newW = (w * scale).roundToInt().coerceAtLeast(size)
    val newH = (h * scale).roundToInt().coerceAtLeast(size)
    val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

    val left = (newW - size) / 2
    val top = (newH - size) / 2
    val cropped = if (newW == size && newH == size) resized else
        Bitmap.createBitmap(resized, left, top, size, size)

    val pixels = IntArray(size * size)
    cropped.getPixels(pixels, 0, size, 0, 0, size, size)

    val plane = size * size
    val floatArray = FloatArray(3 * plane)
    for (i in 0 until plane) {
        val p = pixels[i]
        floatArray[i] = ((p shr 16) and 0xFF) / 255f
        floatArray[plane + i] = ((p shr 8) and 0xFF) / 255f
        floatArray[2 * plane + i] = (p and 0xFF) / 255f
    }

    if (cropped !== resized) cropped.recycle()
    if (resized !== bitmap) resized.recycle()
    return floatArray
}
