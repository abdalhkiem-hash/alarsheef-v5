package com.alarsheef.archive.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * كاشف الوجوه يدعم ملفات محلية وصور MediaStore عبر content URI.
 */
object FaceDetectionUtil {

    private const val MAX_ANALYSIS_DIMENSION = 1024

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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

    /** true لو صورة MediaStore عبر content URI فيها وجه (تُنسخ مؤقتًا للتحليل). */
    suspend fun hasFace(context: Context, uri: Uri): Boolean {
        return try {
            val temp = File(context.cacheDir, "face_${System.nanoTime()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            try { hasFace(temp) } finally { temp.delete() }
        } catch (e: Exception) { false }
    }
}
