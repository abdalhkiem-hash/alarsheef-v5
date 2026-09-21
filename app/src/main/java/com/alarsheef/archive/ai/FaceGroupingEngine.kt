package com.alarsheef.archive.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** نتيجة وجه واحد داخل صورة: مربع الوجه ومواقع العينين إن وجدتا */
data class DetectedFace(
    val box: RectF,
    val leftEye: PointF?,
    val rightEye: PointF?
)

/**
 * تجميع الوجوه على الجهاز بلا إنترنت:
 * 1) اكتشاف الوجوه عبر ML Kit (نموذج مضمّن في التطبيق - متاح في هذا المشروع).
 * 2) قصّ الوجه وتوجيهه أفقياً حسب العينين وتطبيعه 64×64 رمادي.
 * 3) بصمة رقمية dHash (64 بت) تقارن الوجوه بمقياس هامينغ:
 *    مسافة ≤ 10 بت تعني "نفس الشخص" غالبًا.
 *
 * الوصفة بصمتها تقريبية (دقة جيدة تحت إضاءة/زوايا متقاربة) — تُصحَّح يدويًا
 * بدمج/تقسيم المجموعات من الشاشة، وكلها محلية بالكامل.
 */
object FaceGroupingEngine {

    private const val MAX_DETECT_DIMENSION = 1024
    private const val CROP_SIZE = 64
    private const val FACE_PADDING = 0.35f
    /** أقصى مسافة هامينغ للبصمات لاعتبار الوجهين لنفس الشخص */
    const val MATCH_THRESHOLD = 10

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val detector by lazy { FaceDetection.getClient(options) }

    /** يكتشف الوجوه في ملف صورة (فك ترميز مصغّر حفاظًا على الذاكرة) */
    suspend fun detectFaces(file: File): List<DetectedFace> = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(file, MAX_DETECT_DIMENSION) ?: return@withContext emptyList()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = suspendCancellableCoroutine<List<Face>> { continuation ->
                detector.process(image)
                    .addOnSuccessListener {
                        if (continuation.isActive) continuation.resume(it)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
            }
            faces.map { face -> toDetectedFace(face, bitmap.width, bitmap.height) }
        } catch (e: Exception) {
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    private fun toDetectedFace(face: Face, bitmapW: Int, bitmapH: Int): DetectedFace {
        val box = face.boundingBox
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        return DetectedFace(
            box = RectF(
                box.left.toFloat().coerceIn(0f, bitmapW.toFloat()),
                box.top.toFloat().coerceIn(0f, bitmapH.toFloat()),
                box.right.toFloat().coerceIn(0f, bitmapW.toFloat()),
                box.bottom.toFloat().coerceIn(0f, bitmapH.toFloat())
            ),
            leftEye = left?.let { PointF(it.x, it.y) },
            rightEye = right?.let { PointF(it.x, it.y) }
        )
    }

    /**
     * يقتطع الوجه من الصورة ويوجّهه (حسب محور العينين) ويعيده مقاس 64×64 رمادي.
     * يعيد null لو فشل فك الترميز.
     */
    suspend fun extractCrop(file: File, face: DetectedFace): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeSampled(file, MAX_DETECT_DIMENSION) ?: return@withContext null
            try {
                cropFace(bitmap, face)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cropFace(bitmap: Bitmap, face: DetectedFace): Bitmap? {
        val pad = FACE_PADDING
        val left = (face.box.left - face.box.width() * pad).toInt().coerceAtLeast(0)
        val top = (face.box.top - face.box.height() * pad).toInt().coerceAtLeast(0)
        val right = (face.box.right + face.box.width() * pad).toInt()
            .coerceAtMost(bitmap.width)
        val bottom = (face.box.bottom + face.box.height() * pad).toInt()
            .coerceAtMost(bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null

        var cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)

        val eyeL = face.leftEye ?: face.rightEye ?: PointF(face.box.centerX(), face.box.centerY())
        val eyeR = face.rightEye ?: face.leftEye ?: PointF(face.box.centerX(), face.box.centerY())
        val angle = Math.toDegrees(
            Math.atan2((eyeR.y - eyeL.y).toDouble(), (eyeR.x - eyeL.x).toDouble())
        ).toFloat()

        if (angle != 0f) {
            val matrix = Matrix()
            matrix.postRotate(-angle, cropped.width / 2f, cropped.height / 2f)
            val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
            if (rotated != cropped) cropped.recycle()
            cropped = rotated
        }

        val normalized = Bitmap.createScaledBitmap(cropped, CROP_SIZE, CROP_SIZE, true)
        if (normalized != cropped) cropped.recycle()
        return normalized
    }

    /** بصمة dHash: بعد خفض الدقة إلى 9×8 رمادي بمعدل أخذ أقرب جار → 64 بت */
    fun dHash(bitmap: Bitmap): Long {
        val gray9 = IntArray(9 * 8)
        for (ry in 0 until 8) {
            val sy = (ry * bitmap.height) / 8
            for (rx in 0 until 9) {
                val sx = (rx * bitmap.width) / 9
                val c = bitmap.getPixel(sx.coerceAtMost(bitmap.width - 1), sy.coerceAtMost(bitmap.height - 1))
                val lum = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                gray9[ry * 9 + rx] = lum.toInt()
            }
        }
        var hash = 0L
        var bit = 0
        for (ry in 0 until 8) {
            for (rx in 0 until 8) {
                if (gray9[ry * 9 + rx] > gray9[ry * 9 + rx + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun hashToHex(hash: Long): String = java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')

    private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}