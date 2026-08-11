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
            // ФИКС (2026-08-11): имя файла раньше было фиксированным — каждый
            // следующий снимок перезатирал предыдущий, и после серии из
            // нескольких сканирований подряд (как в живой проверке владельца)
            // посмотреть, что именно было в НЕУДАЧНЫХ кадрах, было уже нельзя —
            // оставался только самый последний. Таймстемп в имени сохраняет всю
            // серию снимков одной сессии для сравнения удачных/неудачных кадров.
            val stamp = System.currentTimeMillis()
            saveDebugBitmap(context, bitmap, "${stamp}_01_captured_cropped.png")
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
            saveDebugBitmap(context, pre, "${stamp}_02_preprocessed.png") // ВРЕМЕННО, см. выше

            // ФИКС (2026-08-11, живая проверка: владелец прямо снял 2 визитки БОКОМ
            // относительно кадра — не телефон дёрнулся между открытием камеры и
            // снимком (это отдельный, уже исправленный класс бага через
            // OrientationEventListener в CardCameraScanner), а сама визитка легла
            // в кадр повёрнутой). Метаданные поворота устройства тут ничем не
            // помогут — телефон был неподвижен и держался правильно, просто
            // карточка внутри кадра оказалась боком. Tesseract4Android не даёт
            // отдельного OSD-метода (проверено по исходнику библиотеки) без
            // отдельной osd.traineddata модели — вместо этого пробуем все 4
            // поворота предобработанного кадра и берём тот, где meanConfidence()
            // реально выше: связный текст даёт куда более уверенное распознавание,
            // чем та же картинка боком. Дороже (до 4 OCR-проходов вместо одного),
            // но это разовое действие по кнопке, не то, что происходит в реальном
            // времени — секунды ожидания за рабочий результат вместо гарантированной
            // билиберды того стоят.
            var best = ""
            var bestConfidence = -1
            var bestDegrees = 0
            for (degrees in intArrayOf(0, 90, 180, 270)) {
                val candidate = if (degrees == 0) pre else rotateBitmapDegrees(pre, degrees)
                tess.setImage(candidate)
                val text = tess.getUTF8Text().orEmpty().trim()
                val confidence = tess.meanConfidence()
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    best = text
                    bestDegrees = degrees
                }
            }
            android.util.Log.d("BusinessCardOcr", // ВРЕМЕННО
                "[$stamp] best rotation=$bestDegrees° confidence=$bestConfidence raw OCR text (${best.length} chars):\n$best")
            best
        } catch (e: Exception) {
            android.util.Log.e("BusinessCardOcr", "OCR failed", e) // ВРЕМЕННО
            ""
        } finally {
            tess.recycle()
        }
    }

    /** Поворот bitmap на кратные 90° градусы — для перебора ориентаций в [recognize]. */
    private fun rotateBitmapDegrees(src: Bitmap, degrees: Int): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
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

    /**
     * Апскейл до ширины ≥1600px (Tesseract любит ~300dpi) + бинаризация по Соволе
     * (Sauvola & Pietikäinen, 2000) — локальный адаптивный порог по среднему/
     * стандартному отклонению яркости в окне вокруг пикселя.
     *
     * ФИКС (2026-07-27, диагностика с владельцем на реальном устройстве): раньше
     * здесь был ГЛОБАЛЬНЫЙ линейный контраст (один множитель на всё изображение).
     * Визитка снимается телефоном, а не сканируется — на фото почти всегда есть
     * неровное освещение (тень от руки/телефона, блик от глянцевой поверхности
     * визитки). Глобальный контраст усиливает и текст, и эту неровность одинаково:
     * тёмная половина кадра уходит в сплошной чёрный, светлая — в сплошной белый,
     * текст в обеих зонах теряется. Локальный порог считается ОТДЕЛЬНО для каждого
     * пикселя по его же окрестности — тень и блик перестают влиять на соседние
     * области, известная и проверенная техника именно для этого класса проблемы
     * (не придумана здесь — Sauvola/Pietikäinen — общепринятый алгоритм из
     * литературы по OCR-препроцессингу документов при неровном освещении).
     */
    private fun preprocess(src: Bitmap): Bitmap {
        val minWidth = 1600
        val scaled = if (src.width < minWidth) {
            val k = minWidth.toFloat() / src.width
            Bitmap.createScaledBitmap(src, minWidth, (src.height * k).toInt(), true)
        } else src
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        // Яркость (luminance) по стандартным весам ITU-R BT.601.
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // Интегральные изображения суммы и суммы квадратов — считают среднее и
        // стандартное отклонение в любом прямоугольном окне за O(1) на пиксель,
        // без них локальный порог для мегапиксельного фото не уложился бы в
        // разумное время на телефоне.
        val stride = w + 1
        val sum = LongArray(stride * (h + 1))
        val sumSq = LongArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            var rowSumSq = 0L
            val rowAbove = y * stride
            val rowHere = (y + 1) * stride
            for (x in 0 until w) {
                val v = gray[y * w + x].toLong()
                rowSum += v
                rowSumSq += v * v
                sum[rowHere + x + 1] = sum[rowAbove + x + 1] + rowSum
                sumSq[rowHere + x + 1] = sumSq[rowAbove + x + 1] + rowSumSq
            }
        }

        // Окно ~40-я часть меньшей стороны — достаточно, чтобы усреднить тень/блик,
        // но не настолько крупное, чтобы «размыть» сам текст между окном и соседним.
        val radius = (minOf(w, h) / 40).coerceIn(10, 30)
        val k = 0.34
        val rDynamicRange = 128.0
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(w - 1)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val s = sum[(y1 + 1) * stride + (x1 + 1)] - sum[y0 * stride + (x1 + 1)] -
                    sum[(y1 + 1) * stride + x0] + sum[y0 * stride + x0]
                val sq = sumSq[(y1 + 1) * stride + (x1 + 1)] - sumSq[y0 * stride + (x1 + 1)] -
                    sumSq[(y1 + 1) * stride + x0] + sumSq[y0 * stride + x0]
                val mean = s.toDouble() / area
                val variance = (sq.toDouble() / area) - mean * mean
                val stddev = if (variance > 0) Math.sqrt(variance) else 0.0
                val threshold = mean * (1 + k * (stddev / rDynamicRange - 1))
                out[y * w + x] = if (gray[y * w + x] > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Оценка размытости кадра (variance of Laplacian — стандартная быстрая метрика
     * фокуса/резкости). Резкий текст даёт высокую дисперсию градиента, размытый —
     * низкую. Используется ДО дорогого OCR, чтобы отсеять явно нечёткие снимки и
     * попросить переснять, а не молча скормить Tesseract кашу и получить билиберду
     * на выходе без объяснения причины владельцу.
     */
    fun isBlurry(bitmap: Bitmap): Boolean {
        val maxDim = 500
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        else bitmap
        val w = small.width
        val h = small.height
        if (w < 3 || h < 3) return false
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val p = pixels[i]
            (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
        }
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                // Дискретный лапласиан (4-связность): |4·центр − 4 соседа|.
                val lap = 4 * gray[y * w + x] - gray[(y - 1) * w + x] - gray[(y + 1) * w + x] -
                    gray[y * w + x - 1] - gray[y * w + x + 1]
                sum += lap
                sumSq += lap.toDouble() * lap
                count++
            }
        }
        if (count == 0) return false
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        // Порог подобран по практике сообщества OCR/CV для «resize-to-500px» масштаба
        // (тот же порядок величины, что типичные примеры blur-detection на Laplacian);
        // не абсолютная истина — компромисс между «пропустить размытое» и «дёргать
        // пользователя на резких, но контрастных по свету кадрах».
        return variance < 60.0
    }
}
