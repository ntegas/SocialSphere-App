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
            tess.setImage(bitmap)
            tess.getUTF8Text().orEmpty().trim()
        } catch (e: Exception) {
            ""
        } finally {
            tess.recycle()
        }
    }
}
