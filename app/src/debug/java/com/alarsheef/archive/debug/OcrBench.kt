package com.alarsheef.archive.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.alarsheef.archive.ai.OcrPreprocess
import dev.ffmpegkit.tesseract.TesseractConfig
import dev.ffmpegkit.tesseract.TesseractOCR
import java.io.File

/**
 * أداة قياس لمحركات OCR (نسخة التطوير فقط).
 * الصور في files/ocrbench/img والنماذج في files/ocrbench/models، والنتائج تُلحق بـ results.tsv
 * (config ⇥ صورة ⇥ مللي ثانية ⇥ نص ⇥ ثقة). يستأنف من حيث توقف.
 */
class OcrBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val only = intent.getStringExtra("cfgs").orEmpty()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ocr_bench",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OcrBenchWorker>().setInputData(workDataOf("cfgs" to only)).build()
        )
    }
}

class OcrBenchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Cfg(val name: String, val lang: String, val psm: Int, val pre: OcrPreprocess.Options?)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val root = File(ctx.filesDir, "ocrbench")
        val images = File(root, "img").listFiles { f -> f.extension in setOf("png", "jpg") }?.sortedBy { it.name }
            ?: return Result.failure()
        val models = File(root, "models")
        val tessdata = File(ctx.filesDir, "tesseract/tessdata").apply { mkdirs() }
        // النموذج المضمّن في التطبيق (إنتاج) → ara
        val bundled = File(tessdata, "ara.traineddata")
        if (!bundled.exists()) ctx.assets.open("tessdata/ara.traineddata").use { i -> bundled.outputStream().use { i.copyTo(it) } }

        val d1 = OcrPreprocess.Options(deskew = true)
        val d2 = OcrPreprocess.Options(deskew = true, contrast = true)
        val u1 = OcrPreprocess.Options(longSide = 1600)
        val u2 = OcrPreprocess.Options(longSide = 1600, deskew = true)
        var cfgs = listOf(
            Cfg("prod", "", 0, null),
            Cfg("cur_raw", "ara+eng", 3, null),
            Cfg("cur_d", "ara+eng", 3, d1),
            Cfg("cur_dc", "ara+eng", 3, d2),
            Cfg("cur_u16", "ara+eng", 3, u1),
            Cfg("cur_u16d", "ara+eng", 3, u2),
            Cfg("cur_raw_psm6", "ara+eng", 6, null),
            Cfg("cur_d_psm6", "ara+eng", 6, d1),
            Cfg("cur_raw_psm11", "ara+eng", 11, null),
        )
        val only = inputData.getString("cfgs").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        if (only.isNotEmpty()) cfgs = cfgs.filter { it.name in only }

        val out = File(root, "results.tsv")
        val done = if (out.exists()) out.readLines().mapNotNull { l -> l.split('\t').take(2).takeIf { it.size == 2 }?.joinToString("|") }.toHashSet() else HashSet()

        val engines = HashMap<String, TesseractOCR>()
        suspend fun engineFor(cfg: Cfg): TesseractOCR = engines.getOrPut("${cfg.lang}|${cfg.psm}") {
            val e = TesseractOCR()
            File(models, "ara_best.traineddata").takeIf { it.exists() && !File(tessdata, "arabest.traineddata").exists() }
                ?.let { e.addLanguage(ctx, "arabest", it) }
            File(models, "ara_fast.traineddata").takeIf { it.exists() && !File(tessdata, "arafast.traineddata").exists() }
                ?.let { e.addLanguage(ctx, "arafast", it) }
            e.initialize(ctx, cfg.lang, TesseractConfig(pageSegMode = cfg.psm, ocrEngineMode = 1))
            e
        }
        try {
            for (cfg in cfgs) {
                val engine = if (cfg.name == "prod") null else engineFor(cfg)
                for (img in images) {
                    if ("${cfg.name}|${img.name}" in done) continue
                    val src = BitmapFactory.decodeFile(img.absolutePath) ?: continue
                    val t0 = System.currentTimeMillis()
                    var conf = -1
                    val text = try {
                        if (engine == null) {
                            com.alarsheef.archive.ai.OcrEngine.recognizeFile(ctx, img).orEmpty()
                        } else {
                        val prepared = if (cfg.pre != null) OcrPreprocess.apply(src, cfg.pre) else src
                        if (cfg.pre != null && (img.name == "s1_rot14.jpg" || img.name == "s1_photo.jpg")) {
                            File(root, "dump").apply { mkdirs() }
                            File(root, "dump/${cfg.name}_${img.name}.png").outputStream().use { prepared.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        val r = engine.recognize(prepared)
                        conf = r.confidence
                        r.text
                        }
                    } catch (t: Throwable) {
                        "ERR ${t.javaClass.simpleName}"
                    }
                    val ms = System.currentTimeMillis() - t0
                    out.appendText("${cfg.name}\t${img.name}\t$ms\t${text.replace('\n', ' ').replace('\t', ' ').trim()}\t$conf\n")
                }
                Log.i("OcrBench", "انتهى ${cfg.name}")
            }
        } finally {
            engines.values.forEach { runCatching { it.release() } }
        }
        File(root, "DONE").writeText(System.currentTimeMillis().toString())
        Log.i("OcrBench", "اكتمل القياس")
        return Result.success()
    }
}
