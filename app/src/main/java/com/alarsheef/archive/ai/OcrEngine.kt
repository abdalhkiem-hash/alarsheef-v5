package com.alarsheef.archive.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dalvik.system.DexClassLoader
import dev.ffmpegkit.tesseract.TesseractConfig
import dev.ffmpegkit.tesseract.TesseractOCR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * قراءة نص على الجهاز، بلا Gemini.
 *
 * الترتيب: نموذج أندرويد 13+ إن وُجد، ثم محرك سامسونج إن قُرئت حزمته،
 * ثم تيسراكت بالعربية والإنجليزية، ثم نموذج لاتيني مدمج.
 * لا يُعاد نص أقصر من 3 أحرف، ولا يُسجَّل النص نفسه.
 */
object OcrEngine {

    private const val TAG = "OcrEngine"
    private const val CLASS_NAME = "android.graphics.text.TextRecognizer"
    private const val SAMSUNG_APK = "/system/app/OCRDataProvider/OCRDataProvider.apk"
    private const val HOOK_ASSET = "samsung_ocr_hook.jar"
    private const val MAX_DIMENSION = 2048
    private const val MIN_CHARS = 3

    /** نص أقصر من هذا يُكمَّل بمحرك لاحق؛ الأطول يُعتمد. */
    private const val STRONG_CHARS = 40

    @Volatile private var systemState: Boolean? = null
    @Volatile private var samsungDead = false
    @Volatile private var samsungMissingLogged = false
    @Volatile private var samsung: SamsungOcr? = null
    @Volatile private var arabicDead = false
    private var arabic: TesseractOCR? = null
    private val arabicMutex = Mutex()

    private val samsungExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "arsheef-samsung-ocr").apply { isDaemon = true }
        }
    }

    private val latinClient by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun isAvailable(context: Context): Boolean =
        systemSupported(context) || File(SAMSUNG_APK).canRead() || latinClassPresent()

    suspend fun recognizeFile(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        val bitmap = try {
            decodeBitmap(file)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "تعذر فك الصورة: ${brief(t)}")
            null
        } ?: return@withContext null
        try {
            var best: String? = null
            fun keep(text: String?) {
                val cleaned = text?.trim().orEmpty()
                if (cleaned.length >= MIN_CHARS && cleaned.length > (best?.length ?: 0)) best = cleaned
            }
            if (systemSupported(context)) keep(recognizeSystem(context, bitmap))
            if ((best?.length ?: 0) < STRONG_CHARS) keep(recognizeSamsung(context, bitmap))
            if ((best?.length ?: 0) < STRONG_CHARS) keep(recognizeArabic(context, bitmap))
            if ((best?.length ?: 0) < STRONG_CHARS) keep(recognizeLatin(bitmap))
            android.util.Log.i(TAG, "OCR محلي: أحرف=${best?.length ?: 0}")
            best
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun systemSupported(context: Context): Boolean {
        systemState?.let { return it }
        val ok = probeSystem(context)
        systemState = ok
        return ok
    }

    private fun probeSystem(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        var instance: Any? = null
        return try {
            val cls = Class.forName(CLASS_NAME)
            instance = cls.getMethod("create", Context::class.java).invoke(null, context) ?: return false
            supportMethods().any { method ->
                runCatching { (cls.getMethod(method).invoke(instance) as? Boolean) ?: false }.getOrDefault(false)
            }
        } catch (_: Throwable) {
            false
        } finally {
            instance?.let { closeQuietly(it) }
        }
    }

    private fun recognizeSystem(context: Context, bitmap: Bitmap): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        var instance: Any? = null
        return try {
            val cls = Class.forName(CLASS_NAME)
            instance = cls.getMethod("create", Context::class.java).invoke(null, context) ?: return null
            val supported = supportMethods().any { method ->
                runCatching { (cls.getMethod(method).invoke(instance) as? Boolean) ?: false }.getOrDefault(false)
            }
            if (!supported) return null
            val result = cls.getMethod("recognize", Bitmap::class.java).invoke(instance, bitmap) ?: return null
            val available = runCatching {
                (result.javaClass.getMethod("isRecognizedTextAvailable").invoke(result) as? Boolean) ?: false
            }.getOrDefault(false)
            if (!available) return null
            val text = (result.javaClass.getMethod("getResultText").invoke(result) as? CharSequence)?.toString()
            android.util.Log.i(TAG, "OCR نظام: أحرف=${text?.trim()?.length ?: 0}")
            text
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "OCR نظام: ${brief(t)}")
            null
        } finally {
            instance?.let { closeQuietly(it) }
        }
    }

    private fun recognizeLatin(bitmap: Bitmap): String? = try {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = Tasks.await(latinClient.process(image), 20, TimeUnit.SECONDS).text
        android.util.Log.i(TAG, "OCR لاتيني: أحرف=${text?.trim()?.length ?: 0}")
        text
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "OCR لاتيني: ${brief(t)}")
        null
    }

    private suspend fun recognizeArabic(context: Context, bitmap: Bitmap): String? {
        if (arabicDead) return null
        return try {
            arabicMutex.withLock {
                val engine = arabicEngine(context.applicationContext)
                val text = engine.recognize(bitmap).text
                android.util.Log.i(TAG, "OCR عربي: أحرف=${text.trim().length}")
                text
            }
        } catch (t: Throwable) {
            arabicDead = true
            android.util.Log.w(TAG, "OCR عربي: ${brief(t)}")
            null
        }
    }

    private suspend fun arabicEngine(context: Context): TesseractOCR {
        arabic?.takeIf { it.isInitialized }?.let { return it }
        copyArabicModel(context)
        val engine = TesseractOCR()
        val config = TesseractConfig(pageSegMode = 3, ocrEngineMode = 1)
        try {
            engine.initialize(context, "ara+eng", config)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "OCR عربي ara+eng: ${brief(t)}")
            engine.initialize(context, "ara", config)
        }
        arabic = engine
        android.util.Log.i(TAG, "OCR عربي: جاهز")
        return engine
    }

    private fun copyArabicModel(context: Context) {
        val target = File(context.filesDir, "tesseract/tessdata/ara.traineddata")
        if (target.isFile && target.length() > 1_000_000L) return
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "ara.traineddata.tmp")
        context.assets.open("tessdata/ara.traineddata").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun samsungPackagePath(context: Context): String? {
        if (File(SAMSUNG_APK).canRead()) return SAMSUNG_APK
        val fromPackage = runCatching {
            val info = context.packageManager.getPackageInfo("com.samsung.android.sdk.ocr", 0)
            info.applicationInfo?.sourceDir?.takeIf { File(it).canRead() }
        }.getOrNull()
        if (fromPackage == null && !samsungMissingLogged) {
            samsungMissingLogged = true
            android.util.Log.w(TAG, "Samsung OCR: حزمة النظام غير قابلة للقراءة من التطبيق")
        }
        return fromPackage
    }

    private fun recognizeSamsung(context: Context, bitmap: Bitmap): String? {
        if (samsungDead && samsung == null) return null
        if (samsung == null && samsungPackagePath(context) == null) return null
        return try {
            samsungExecutor.submit<String?> {
                recognizeSamsungOnThread(context.applicationContext, bitmap)
            }.get(70, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Samsung OCR: ${brief(t)}")
            null
        }
    }

    private fun recognizeSamsungOnThread(context: Context, bitmap: Bitmap): String? {
        val ocr = samsung ?: openSamsung(context) ?: return null
        if (ocr.languages.isEmpty()) return null
        var best: String? = null
        for (lang in ocr.languages) {
            val text = try {
                recognizeWithLanguage(ocr, bitmap, lang)
            } catch (t: Throwable) {
                if (isLinkFailure(t)) {
                    samsungDead = true
                    samsung = null
                }
                android.util.Log.w(TAG, "Samsung OCR: لغة=$lang ${brief(t)}")
                null
            }
            if ((text?.length ?: 0) > (best?.length ?: 0)) best = text
            if ((best?.length ?: 0) >= STRONG_CHARS) break
        }
        return best
    }

    private fun recognizeWithLanguage(ocr: SamsungOcr, bitmap: Bitmap, lang: String): String? {
        val config = ocr.configClass.getConstructor(String::class.java).newInstance(lang)
        val capture = ocr.captureClass.getDeclaredConstructor().newInstance()
        return try {
            val error = ocr.recognize.invoke(ocr.recognizer, bitmap, config, capture)
            val errorName = error?.toString() ?: "null"
            val patient = errorName.contains("Success")
            val text = awaitCapture(ocr.captureClass, capture, patient)
            android.util.Log.i(TAG, "Samsung OCR: لغة=$lang نتيجة=$errorName أحرف=${text?.length ?: 0}")
            text
        } finally {
            closeQuietly(capture)
        }
    }

    private fun awaitCapture(cls: Class<*>, capture: Any, patient: Boolean): String? {
        val monitor = cls.getField("lock").get(capture) as Object
        val page = cls.getField("page").get(capture) as StringBuilder
        val lines = cls.getField("lines").get(capture) as StringBuilder
        val done = cls.getField("pageDone")
        val deadline = System.currentTimeMillis() + if (patient) 20_000L else 400L
        synchronized(monitor) {
            while (System.currentTimeMillis() < deadline) {
                if (done.getBoolean(capture) || lines.isNotEmpty()) break
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                monitor.wait(minOf(200L, left))
            }
            val pageText = page.toString().trim()
            if (pageText.length >= MIN_CHARS) return pageText
            val lineText = lines.toString().trim()
            return lineText.takeIf { it.length >= MIN_CHARS }
        }
    }

    private fun openSamsung(context: Context): SamsungOcr? {
        if (samsungDead) return null
        return try {
            val hook = hookFile(context)
            val opt = File(context.codeCacheDir, "samsung-ocr").apply { mkdirs() }
            val lib = listOf("/system/lib64", "/vendor/lib64", "/system/lib", "/vendor/lib")
                .filter { File(it).isDirectory }
                .joinToString(File.pathSeparator)
                .ifEmpty { "/system/lib64" }
            val apk = samsungPackagePath(context) ?: throw IllegalStateException("samsung apk unreadable")
            val loader = DexClassLoader(
                apk + File.pathSeparator + hook.absolutePath,
                opt.absolutePath,
                lib,
                context.classLoader
            )
            val engineClass = loader.loadClass("com.samsung.android.sdk.pen.ocr.SpenOcrEngine")
            val supported = runCatching {
                engineClass.getMethod("isSupported", Context::class.java).invoke(null, context) as? Boolean
            }.getOrNull()
            android.util.Log.i(TAG, "Samsung OCR: isSupported=$supported")
            val engine = newEngine(context, engineClass, loader)
            val version = runCatching {
                engineClass.getMethod("getEngineVersion").invoke(engine) as? String
            }.getOrNull()
            val languages = loadLanguages(context, engineClass, engine)
            android.util.Log.i(TAG, "Samsung OCR: إصدار=$version لغات=${languages.joinToString(",")}")
            val recognizer = engineClass.getMethod("createRecognizer").invoke(engine)
                ?: throw IllegalStateException("createRecognizer=null")
            val configClass = loader.loadClass("com.samsung.android.sdk.pen.ocr.SpenRecogConfig")
            val listenerClass = loader.loadClass("com.samsung.android.sdk.pen.ocr.SpenOcrRecognitionListener")
            val captureClass = loader.loadClass("com.alarsheef.archive.ocr.PageCapture")
            val recognize = recognizer.javaClass.getMethod(
                "recognize",
                Bitmap::class.java,
                configClass,
                listenerClass
            )
            SamsungOcr(engine, recognizer, languages, configClass, captureClass, recognize).also {
                samsung = it
            }
        } catch (t: Throwable) {
            samsungDead = true
            android.util.Log.w(TAG, "Samsung OCR: تعذر التشغيل: ${brief(t)}")
            null
        }
    }

    private fun newEngine(context: Context, engineClass: Class<*>, loader: ClassLoader): Any {
        val enumClass = loader.loadClass(
            "com.samsung.android.sdk.pen.ocr.SpenOcrModelLoaderFactory" + '$' + "MODEL_LOADER"
        )
        val ctor = runCatching { engineClass.getConstructor(Context::class.java, enumClass) }.getOrNull()
        val failures = ArrayList<String>()
        if (ctor != null) {
            for (name in listOf("PROVIDER_LATEST", "PROVIDER_ONEUI411")) {
                try {
                    val mode = enumClass.getField(name).get(null)
                    return ctor.newInstance(context, mode)
                } catch (t: Throwable) {
                    failures += "$name: ${brief(t)}"
                }
            }
        }
        try {
            return engineClass.getConstructor().newInstance()
        } catch (t: Throwable) {
            failures += "empty: ${brief(t)}"
            throw IllegalStateException(failures.joinToString(" || "))
        }
    }

    private fun loadLanguages(context: Context, engineClass: Class<*>, engine: Any): List<String> {
        val method = engineClass.getMethod("loadLanguageDB", String::class.java)
        val candidates = (installedLanguageCodes(context) + listOf(
            "ar_SA", "en_US", "ar", "en", "Arabic", "English"
        )).distinct().take(12)
        val loaded = ArrayList<String>()
        for (code in candidates) {
            val ok = runCatching { method.invoke(engine, code) as? Boolean ?: false }.getOrDefault(false)
            android.util.Log.i(TAG, "Samsung OCR: load $code=$ok")
            if (ok) loaded += code
        }
        val arabic = loaded.firstOrNull { it.contains("ar", ignoreCase = true) }
        val english = loaded.firstOrNull { code ->
            code.contains("en", ignoreCase = true) && code != arabic
        }
        return listOfNotNull(arabic, english).ifEmpty { loaded.take(1) }
    }

    private fun installedLanguageCodes(context: Context): List<String> {
        val uri = Uri.parse("content://com.samsung.android.sdk.ocr.resourcemanager/installed_languages")
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = (0 until cursor.columnCount).firstOrNull { column ->
                    val name = cursor.getColumnName(column).lowercase()
                    name == "language" || name == "languagecode" || name.contains("lang")
                } ?: return emptyList()
                val codes = ArrayList<String>()
                while (cursor.moveToNext()) {
                    val value = cursor.getString(index)?.trim().orEmpty()
                    val safe = value.length in 2..32 && value.all { it.isLetter() || it == '_' || it == '-' }
                    if (safe) codes += value
                }
                codes.distinct()
            } ?: emptyList()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Samsung OCR: تعذر سرد اللغات: ${brief(t)}")
            emptyList()
        }
    }

    private fun hookFile(context: Context): File {
        val out = File(context.filesDir, "ocr/samsung_ocr_hook.jar")
        val bytes = context.assets.open(HOOK_ASSET).use { it.readBytes() }
        if (out.length() != bytes.size.toLong()) {
            out.parentFile?.mkdirs()
            if (out.exists()) out.setWritable(true)
            out.writeBytes(bytes)
        }
        // أندرويد 14 يرفض تحميل dex قابل للكتابة.
        out.setReadOnly()
        runCatching {
            Os.chmod(
                out.absolutePath,
                OsConstants.S_IRUSR or OsConstants.S_IRGRP or OsConstants.S_IROTH
            )
        }
        return out
    }

    private fun decodeBitmap(file: File): Bitmap? {
        val ext = file.extension.lowercase()
        if (Build.VERSION.SDK_INT >= 28 && ext in setOf("heic", "heif")) {
            val heic = runCatching { decodeHeic(file) }.getOrNull()
            if (heic != null) return asArgb8888(heic)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null
        return asArgb8888(rotateUpright(file, decoded))
    }

    @RequiresApi(28)
    private fun decodeHeic(file: File): Bitmap {
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val maxSide = maxOf(width, height)
            if (maxSide > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / maxSide.toFloat()
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    }

    private fun rotateUpright(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun asArgb8888(source: Bitmap): Bitmap {
        if (source.config == Bitmap.Config.ARGB_8888) return source
        val copy = source.copy(Bitmap.Config.ARGB_8888, false) ?: return source
        if (copy !== source) source.recycle()
        return copy
    }

    private fun latinClassPresent(): Boolean = try {
        Class.forName("com.google.mlkit.vision.text.TextRecognition")
        true
    } catch (_: Throwable) {
        false
    }

    private fun closeQuietly(instance: Any) {
        runCatching { instance.javaClass.getMethod("close").invoke(instance) }
    }

    private fun isLinkFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard++ < 6) {
            if (cur is UnsatisfiedLinkError || cur is ExceptionInInitializerError) return true
            cur = cur.cause
        }
        return false
    }

    private fun brief(t: Throwable): String {
        val parts = ArrayList<String>(4)
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard++ < 4) {
            val message = cur.message?.replace('\n', ' ')?.take(140).orEmpty()
            parts += if (message.isEmpty()) cur.javaClass.simpleName else "${cur.javaClass.simpleName}: $message"
            cur = cur.cause
        }
        return parts.joinToString(" | ").take(300)
    }

    /** "isSupported" أُضيف في API 34؛ الأجهزة على API 33 تعرض isRecognizerSupported */
    private fun supportMethods(): List<String> = listOf("isSupported", "isRecognizerSupported")

    private class SamsungOcr(
        @Suppress("unused") val engine: Any,
        val recognizer: Any,
        val languages: List<String>,
        val configClass: Class<*>,
        val captureClass: Class<*>,
        val recognize: java.lang.reflect.Method
    )
}
