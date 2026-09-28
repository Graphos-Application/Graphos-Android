package com.example.galleryai1

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * [MobileClipEngine]에 넘길 사진을 디코딩하는 헬퍼.
 *
 * 예전엔 ML Kit 4단 태거(AutoTagger/PreciseTagger/FaceTagger)가 각자 원본 해상도로 디코딩하다가
 * S24 Ultra 실기기(최대 2억 화소 카메라)에서 검색 한 번에 네이티브 메모리가 7GB 넘게 치솟아
 * 시스템(Heimdall/lmkd)이 앱을 강제종료시키는 사고가 실기기 로그로 확인됐다 — 사용자 입장에선
 * "검색하다 튕김"으로 보이지만 실제로는 Java 예외 없는 OOM 강제종료였다. MobileCLIP 비전
 * 인코더도 어차피 256x256으로 다시 리사이즈해서 쓰므로(`MobileClipEngine.preprocessBitmap`)
 * 원본을 그대로 넘길 필요가 없어, 그 축소 디코딩을 그대로 유지한다.
 *
 * `BitmapFactory`는 EXIF 회전 정보를 자동 반영하지 않으므로, 회전각을 직접 읽어서 [Matrix]로
 * 돌려둔다 — 안 그러면 세로로 찍은 사진 대부분(EXIF 회전 90/270도)이 모델에 옆으로 누운 채로
 * 들어가서 인식 정확도가 떨어진다.
 */
object TaggingImageLoader {
    private const val MAX_DIMENSION = 1024

    /** 디코딩에 실패하면(파일 접근 불가 등) null. 반환된 비트맵은 EXIF 회전이 이미 반영된 상태다. */
    fun load(context: Context, photoUri: String): Bitmap? {
        val uri = Uri.parse(photoUri)
        val resolver = context.contentResolver
        val bitmap = decodeSampledBitmap(resolver, uri) ?: return null
        val rotation = readExifRotationDegrees(resolver, uri)
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun decodeSampledBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        } catch (e: Exception) {
            null
        }
    }

    private fun readExifRotationDegrees(resolver: ContentResolver, uri: Uri): Int {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }
}

/**
 * 원본 해상도의 긴 변을 [maxDimension] 근처로 줄이기 위한 2의 거듭제곱 서브샘플링 배율을
 * 계산하는 순수 함수(Android BitmapFactory.Options에 안 얽매이게 분리 — 이 프로젝트의 다른
 * 순수 함수들과 같은 이유, JUnit에서 테스트하기 위함).
 */
internal fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (maxDimension <= 0) return 1
    val longestSide = maxOf(width, height)
    var sampleSize = 1
    while (longestSide / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}
