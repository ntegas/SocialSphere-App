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
            if (!tess.init(dataPath, LANGS)) return@withContext ""
            // Предобработка (фидбэк 2026-07-04 «плохо читает»): апскейл мелких
            // кадров + ч/б с усиленным контрастом — заметно поднимает точность
            // Tesseract на визитках при слабом свете/мелком шрифте.
            tess.setImage(preprocess(bitmap))
            tess.getUTF8Text().orEmpty().trim()
        } catch (e: Exception) {
            ""
        } finally {
            tess.recycle()
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
