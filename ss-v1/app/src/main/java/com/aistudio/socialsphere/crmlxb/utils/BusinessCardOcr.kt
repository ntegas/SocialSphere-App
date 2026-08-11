package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OCR-слой сканера визиток на Tesseract (Tesseract4Android, Apache-2.0).
 *
 * Языковые данные (fast-модели eng/rus/ell) вшиты в assets/tessdata и при первом
 * запуске копируются в приватную папку — Tesseract умеет читать только с ФС, не
 * из assets. Мультиязычная инициализация "eng+rus+ell" покрывает три языка
 * приложения (латиница + кириллица + греческий), которые ML Kit не поддерживает.
 *
 * Результат — «сырой» текст, который дальше раскладывает [BusinessCardParser].
 */
object BusinessCardOcr {
    private const val LANGS = "eng+rus+ell"
    private val LANG_FILES = listOf("eng", "rus", "ell")

    /**
     * Копирует traineddata из assets в filesDir/tesseract/tessdata при первом
     * обращении и возвращает dataPath (родитель каталога tessdata).
     */
    private fun ensureTessData(context: Context): String {
        val dataPath = File(context.filesDir, "tesseract")
        val tessdata = File(dataPath, "tessdata")
        if (!tessdata.exists()) tessdata.mkdirs()
        LANG_FILES.forEach { lang ->
            val out = File(tessdata, "$lang.traineddata")
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("tessdata/$lang.traineddata").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return dataPath.absolutePath
    }

    /**
     * Распознаёт текст визитки на изображении. Тяжёлая операция — выполняется на
     * фоновом диспетчере. Возвращает распознанный текст (может быть пустым).
     */
    suspend fun recognize(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val dataPath = ensureTessData(context)
        val tess = TessBaseAPI()
        try {
            // ВРЕМЕННАЯ ДИАГНОСТИКА (2026-07-27, живая проверка с владельцем на
            // реальном устройстве) — сохраняет кадр ДО и ПОСЛЕ preprocess() в
            // externalFilesDir, чтобы вытащить через adb pull и увидеть глазами,
            // что реально уходит в Tesseract. Убрать после диагностики.
            saveDebugBitmap(context, bitmap, "01_captured_cropped.png")
            // ФИКС (2026-07-11, «билиберда» на выходе OCR): движок LSTM инициализировался
            // с дефолтным PageSegMode 3 (полностью автоматическая сегментация страницы) —
            // это режим для связного текста-страницы (колонки/абзацы), худший выбор для
            // визитки, где текст — разрозненные короткие строки по углам. Индустриальный
            // консенсус для такого layout — PSM_SPARSE_TEXT (11). OEM теперь тоже задаётся
            // явно третьим параметром init() (API проверен по исходнику Tesseract4Android
            // 4.9.0, не угадан) — раньше полагались на неявный дефолт библиотеки.
            if (!tess.init(dataPath, LANGS, TessBaseAPI.OEM_LSTM_ONLY)) return@withContext ""
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            // Предобработка (фидбэк 2026-07-04 «плохо читает»): апскейл мелких
            // кадров + ч/б с усиленным контрастом — заметно поднимает точность
            // Tesseract на визитках при слабом свете/мелком шрифте.
            val pre = preprocess(bitmap)
            saveDebugBitmap(context, pre, "02_preprocessed.png") // ВРЕМЕННО, см. выше
            tess.setImage(pre)
            val result = tess.getUTF8Text().orEmpty().trim()
            android.util.Log.d("BusinessCardOcr", "raw OCR text (${result.length} chars):\n$result") // ВРЕМЕННО
            result
        } catch (e: Exception) {
            android.util.Log.e("BusinessCardOcr", "OCR failed", e) // ВРЕМЕННО
            ""
        } finally {
            tess.recycle()
        }
    }

    /** ВРЕМЕННО (см. выше) — сохраняет bitmap в externalFilesDir/debug_scan для adb pull. */
    private fun saveDebugBitmap(context: Context, bitmap: Bitmap, name: String) {
        try {
            val dir = File(context.getExternalFilesDir(null), "debug_scan").apply { mkdirs() }
            File(dir, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            android.util.Log.e("BusinessCardOcr", "debug save failed", e)
        }
    }

    /** Ч/б + контраст ×1.6 + апскейл до ширины ≥1600px (Tesseract любит ~300dpi). */
    private fun preprocess(src: Bitmap): Bitmap {
        val minWidth = 1600
        val scaled = if (src.width < minWidth) {
            val k = minWidth.toFloat() / src.width
            Bitmap.createScaledBitmap(src, minWidth, (src.height * k).toInt(), true)
        } else src
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint()
        // Насыщенность 0 (grayscale), затем контраст: c=1.6, сдвиг к середине
        val gray = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val c = 1.6f
        val t = (1f - c) * 128f
        val contrast = android.graphics.ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
        gray.postConcat(contrast)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(gray)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }
}
