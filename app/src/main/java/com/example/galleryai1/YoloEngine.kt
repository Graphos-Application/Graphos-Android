package com.example.galleryai1

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * COCO 80종 객체 탐지기(YOLO11n, Ultralytics 공식 아키텍처의 nano 사이즈, ONNX 변환본
 * [deepghs/yolos](https://huggingface.co/deepghs/yolos)에서 받음, 10.1MB) — [MobileClipEngine]의
 * 제로샷 태깅을 보완하는 두 번째 AI 계층이다.
 *
 * 왜 추가했나: 2026-09-07 실기기 검증 중 "사람" 태그가 MobileCLIP 제로샷으로는 구조적으로 약하다는
 * 걸 확인했다(실제 인물 사진 2장에서 0.14~0.17로 임계값 0.18을 계속 못 넘음 — [[galleryai1-s24-realdevice-session]]
 * 참고). 처음엔 RAM(Recognize Anything Model, 4585개 태그) 전면 교체를 검토했으나 원본 체크포인트가
 * 5.63GB에 공식 모바일/ONNX 포팅 사례가 전무해 리스크가 너무 컸다(웹 검색으로 확인). 대신 COCO
 * 데이터셋(사람 인스턴스 26만 개+)으로 전용 훈련된 소형 탐지기를 MobileCLIP과 나란히 쓰는 하이브리드로
 * 결정 — 크기(10MB)·ONNX Runtime Android 공식 튜토리얼 존재·기존 ONNX Runtime 의존성 재사용 등
 * 리스크가 훨씬 낮다. person 탐지 신뢰도가 실제 인물 사진에서 0.8+로 나와 MobileCLIP의 0.14~0.17보다
 * 압도적으로 강함을 Python으로 먼저 검증한 뒤 포팅했다.
 *
 * MobileCLIP과의 역할 분담: YOLO는 COCO 80개 고정 클래스만 알아서 "명찰"/"행사"/"공부" 같은 추상적
 * 개념이나 자유 텍스트 검색은 못 한다(그래서 RAM처럼 MobileCLIP을 완전히 대체할 수 없다) — 대신
 * person을 포함해 겹치는 소수 클래스([COCO_TO_TAG_EN])만 정확도를 보강하는 용도로 좁게 쓴다.
 *
 * 바운딩 박스 좌표는 쓰지 않는다(사진 한 장에 "이 클래스가 있는지 없는지"만 필요한 태깅 용도라
 * 박스 위치는 무의미) — 그래서 표준 YOLO 후처리에 필요한 NMS(비최대억제)도 구현하지 않았다. 같은
 * 클래스가 여러 앵커에서 중복 검출돼도 클래스별 "가장 높은 신뢰도" 하나만 취하면 충분하다.
 */
object YoloEngine {
    private const val INPUT_SIZE = 640
    private const val NUM_CLASSES = 80

    /** Python으로 5장 실사진 사전검증 시 이 값 이상이면 오탐 없이 실제 사람 사진만 걸러냈다(0.4). */
    const val MIN_CONFIDENCE = 0.4f

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (session != null) return
        val appContext = context.applicationContext
        // MobileClipEngine과 같은 이유(85MB text_model.onnx가 에뮬레이터에서 OOM 났던 사건) —
        // assets는 파일 경로로 직접 못 읽으므로 최초 1회 내부 저장소로 스트리밍 복사 후 파일
        // 경로에서 세션을 만든다. 이 모델은 10MB뿐이라 원래 OOM 위험은 낮지만, 패턴을 통일해서
        // 다음에 더 큰 YOLO 변형(s/m/l)으로 바꿔도 안전하게 해둔다.
        val path = copyAssetToFilesDir(appContext, "yolo/yolo11n.onnx", "yolo11n.onnx")
        session = env.createSession(path, OrtSession.SessionOptions())
    }

    private fun copyAssetToFilesDir(context: Context, assetPath: String, fileName: String): String {
        val dir = File(context.filesDir, "yolo").apply { mkdirs() }
        val outFile = File(dir, fileName)
        if (!outFile.exists()) {
            val tempFile = File(dir, "$fileName.tmp")
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 16) }
            }
            tempFile.renameTo(outFile)
        }
        return outFile.absolutePath
    }

    /**
     * 비트맵 한 장에서 COCO 클래스별 최고 신뢰도를 계산한다. [MIN_CONFIDENCE] 미만인 클래스는
     * 아예 안 들어있다(호출부가 매번 80개를 다 걸러낼 필요 없게). 모델 로딩·추론이 무거우니
     * 백그라운드 스레드에서 호출할 것 — [MobileClipEngine.embedImage]와 같은 스레딩 계약.
     */
    @Synchronized
    fun detect(context: Context, bitmap: Bitmap): Map<String, Float> {
        ensureLoaded(context)
        val transform = letterboxTransform(bitmap.width, bitmap.height, INPUT_SIZE)
        val pixels = letterboxToFloatArray(bitmap, transform, INPUT_SIZE)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
            .use { inputTensor ->
                session!!.run(mapOf("images" to inputTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = (result[0].value as Array<Array<FloatArray>>)[0] // [84][numAnchors]
                    val bestPerClass = bestConfidencePerClass(raw, NUM_CLASSES)
                    val out = LinkedHashMap<String, Float>()
                    for (c in 0 until NUM_CLASSES) {
                        if (bestPerClass[c] >= MIN_CONFIDENCE) out[COCO_CLASSES[c]] = bestPerClass[c]
                    }
                    return out
                }
            }
    }
}

/** [YoloEngine.detect]의 레터박스 변환 파라미터(순수 계산 — Bitmap 없이 JUnit에서 테스트하기 위함). */
internal data class LetterboxTransform(
    val scale: Float,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val padX: Int,
    val padY: Int
)

/**
 * YOLO 표준 전처리: 원본 종횡비를 유지한 채(찌그러뜨리지 않고) [targetSize] 정사각형 안에 들어가도록
 * 축소하는 배율·패딩을 계산한다. [MobileClipEngine.preprocessBitmap]의 중앙crop과 달리 YOLO는
 * 이미지를 자르지 않고 회색(114,114,114)으로 여백을 채운다("레터박싱") — 객체가 잘려나가면 탐지를
 * 놓칠 수 있어서 탐지 모델은 보통 crop 대신 letterbox를 쓴다.
 */
internal fun letterboxTransform(width: Int, height: Int, targetSize: Int): LetterboxTransform {
    val scale = targetSize.toFloat() / maxOf(width, height)
    val resizedWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val resizedHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val padX = (targetSize - resizedWidth) / 2
    val padY = (targetSize - resizedHeight) / 2
    return LetterboxTransform(scale, resizedWidth, resizedHeight, padX, padY)
}

/** [transform]에 따라 비트맵을 리사이즈+레터박싱해서 YOLO 입력 형식(0~1 스케일, CHW)으로 평탄화한다. */
internal fun letterboxToFloatArray(bitmap: Bitmap, transform: LetterboxTransform, targetSize: Int): FloatArray {
    val resized = Bitmap.createScaledBitmap(bitmap, transform.resizedWidth, transform.resizedHeight, true)
    val canvas = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(canvas).apply {
        drawColor(Color.rgb(114, 114, 114))
        drawBitmap(resized, transform.padX.toFloat(), transform.padY.toFloat(), null)
    }
    if (resized !== bitmap) resized.recycle()

    val plane = targetSize * targetSize
    val pixels = IntArray(plane)
    canvas.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
    val floatArray = FloatArray(3 * plane)
    for (i in 0 until plane) {
        val p = pixels[i]
        floatArray[i] = ((p shr 16) and 0xFF) / 255f
        floatArray[plane + i] = ((p shr 8) and 0xFF) / 255f
        floatArray[2 * plane + i] = (p and 0xFF) / 255f
    }
    canvas.recycle()
    return floatArray
}

/**
 * YOLO11/YOLOv8 ONNX 표준 출력([1, 4+numClasses, numAnchors], 앵커 프리 — objectness 채널 없음)에서
 * 클래스별 전체 앵커 중 최고 신뢰도만 뽑는다. 바운딩 박스 좌표(raw[0..3])는 태깅 용도에서 안 쓰므로
 * 무시하고, 같은 클래스의 중복 검출을 걸러내는 NMS(비최대억제)도 이 목적엔 불필요해서 구현하지
 * 않았다 — "이 클래스가 사진에 있는지"만 필요하지 "몇 개/어디에 있는지"는 필요 없기 때문.
 */
internal fun bestConfidencePerClass(raw: Array<FloatArray>, numClasses: Int): FloatArray {
    val best = FloatArray(numClasses)
    for (c in 0 until numClasses) {
        val row = raw[4 + c]
        var m = 0f
        for (v in row) if (v > m) m = v
        best[c] = m
    }
    return best
}

/** COCO 데이터셋의 80개 클래스, Ultralytics 공식 순서 그대로(모델 출력 채널 순서와 정확히 일치해야 함). */
internal val COCO_CLASSES = listOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
    "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
    "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
    "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush"
)

/**
 * YOLO가 아는 COCO 클래스명 → 이 앱의 [TagMasterEntity.tagNameEn] 매핑. 의미가 1:1로 정확히
 * 대응하는 것만 넣었다(예: "dining table"→"table") — "sports ball"→"soccer"처럼 애매한 것들은
 * 오탐 위험이 커서 일부러 뺐다. 매핑 없는 COCO 클래스(예: toothbrush)는 그냥 무시된다 —
 * 이 프로젝트 사전에 대응 개념이 없으면 태그를 만들어내지 않는 게 안전하다는 원칙.
 */
internal val COCO_TO_TAG_EN: Map<String, String> = mapOf(
    "person" to "person",
    "bicycle" to "bicycle",
    "car" to "car",
    "motorcycle" to "motorcycle",
    "airplane" to "airplane",
    "bus" to "bus",
    "train" to "train",
    "boat" to "boat",
    "bird" to "bird",
    "cat" to "cat",
    "dog" to "dog",
    "horse" to "horse",
    "sheep" to "sheep",
    "cow" to "cow",
    "bear" to "bear",
    "giraffe" to "giraffe",
    "chair" to "chair",
    "dining table" to "table",
    "laptop" to "laptop",
    "cell phone" to "phone",
    "book" to "book",
    "clock" to "clock",
    "pizza" to "pizza",
    "cake" to "cake",

    // 2026-09-14: 나머지 COCO 클래스 중 의미가 애매하지 않은 것들을 마저 매핑함. 사전(seed_tags.json)
    // 에 대응 개념이 아예 없던 46개를 [SearchSynonyms]와 함께 새로 추가한 뒤(태깅 정확도 개선 —
    // 이 사물들은 그동안 CLIP 제로샷에만 의존해서 임계값 0.18을 못 넘기기 쉬웠다) 여기도 맞춰서
    // YOLO의 강한 신뢰도(0.4+)가 보강되게 했다. "sports ball"/"baseball bat"/"baseball glove"
    // (여러 종목이 겹쳐 애매함)와 "stop sign"/"parking meter"/"fire hydrant"(국내 사진에 드물고
    // 미국 도로표지 특화라 오탐/의미 괴리 우려)는 여전히 제외한다.
    "truck" to "truck",
    "bench" to "bench",
    "elephant" to "elephant",
    "zebra" to "zebra",
    "backpack" to "backpack",
    "umbrella" to "umbrella",
    "handbag" to "handbag",
    "tie" to "tie",
    "suitcase" to "suitcase",
    "frisbee" to "frisbee",
    "skis" to "skis",
    "snowboard" to "snowboard",
    "kite" to "kite",
    "skateboard" to "skateboard",
    "surfboard" to "surfboard",
    "tennis racket" to "tennis racket",
    "bottle" to "bottle",
    "wine glass" to "wine glass",
    "cup" to "cup",
    "fork" to "fork",
    "knife" to "knife",
    "spoon" to "spoon",
    "bowl" to "bowl",
    "banana" to "banana",
    "apple" to "apple",
    "sandwich" to "sandwich",
    "orange" to "orange",
    "hot dog" to "hot dog",
    "donut" to "donut",
    "couch" to "couch",
    "potted plant" to "potted plant",
    "bed" to "bed",
    "toilet" to "toilet",
    "tv" to "tv",
    "mouse" to "mouse",
    "remote" to "remote",
    "keyboard" to "keyboard",
    "microwave" to "microwave",
    "oven" to "oven",
    "sink" to "sink",
    "refrigerator" to "refrigerator",
    "vase" to "vase",
    "scissors" to "scissors",
    "teddy bear" to "teddy bear",
    "hair drier" to "hair drier",
    "toothbrush" to "toothbrush"
)

/**
 * [COCO_TO_TAG_EN]의 역방향 매핑(태그 영문명 → COCO 클래스명). [TagRepository]가 "YOLO가 담당하는
 * 개념인데 이번 사진에서 YOLO는 못 찾았다"를 판정할 때 태그 쪽에서 거꾸로 찾아가는 용도로 쓴다
 * ([TagRepository.suppressYoloCompetingClipTags] 참고) — 2026-09-14, precision 개선(YOLO-CLIP
 * 상충 억제) 작업에서 추가.
 */
internal val TAG_EN_TO_COCO_CLASS: Map<String, String> =
    COCO_TO_TAG_EN.entries.associate { (cocoClass, tagEn) -> tagEn to cocoClass }
