package com.alarsheef.archive.scanner

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * كاشف الوجوه لملف صورة محلي (العامل ينسخ صور MediaStore للكاش أولًا).
 */
object FaceDetectionUtil {

    private const val MAX_ANALYSIS_DIMENSION = 1024

    /**
     * أصغر وجه يُرصد كنسبة من عرض الصورة. الافتراضي في ML Kit 10% — وجوه الصور
     * العائلية والجماعية أصغر من ذلك غالبًا فكانت تمرّ «بلا وجه» إلى العتبة الفضفاضة.
     */
    private const val MIN_FACE_SIZE = 0.05f

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setMinFaceSize(MIN_FACE_SIZE)
        .build()

    private val detector by lazy { FaceDetection.getClient(options) }

    /** true لو الصورة فيها وجه واحد أو أكثر. */
    suspend fun hasFace(file: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ANALYSIS_DIMENSION) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                suspendCancellableCoroutine<Boolean> { continuation ->
                    detector.process(image)
                        .addOnSuccessListener { faces -> if (continuation.isActive) continuation.resume(faces.isNotEmpty()) }
                        .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
                }
            } finally { bitmap.recycle() }
        } catch (e: Exception) { false } catch (e: OutOfMemoryError) { false }
    }
}
