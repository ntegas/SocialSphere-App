package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import java.time.LocalDate

// Названия месяцев — ДАННЫЕ для разбора legacy-строк (не UI-текст), родительный
// падеж как в demo-данных («30 мая», «02 июня»). Локализация не требуется.
private val RU_MONTHS_GENITIVE = mapOf(
    "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4,
    "мая" to 5, "июня" to 6, "июля" to 7, "августа" to 8,
    "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12
)

/**
 * Толерантный разбор строки даты события. По проекту startDate должен быть ISO
 * (yyyy-MM-dd), но в данных встречаются legacy-формы: числовые «15 08 2025» /
 * «15.08.2025» / «15/08/2025» и русские «30 мая», «2 июня 2025». Из-за строгого
 * LocalDate.parse(ISO) такие события тихо теряли напоминания (DateTimeParseException
 * в NotificationScheduler). Возвращает LocalDate или null, если форму не распознать.
 * Для форм без года подставляется defaultYear (текущий) — события годовые.
 */
fun parseFlexibleDate(raw: String?, defaultYear: Int = LocalDate.now().year): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    // 0) vCard-стиль «--MM-DD» / «--MMDD» — дата БЕЗ года (дни рождения из
    //    телефонной книги и введённые вручную «без года»). Год = defaultYear;
    //    29 февраля в невисокосный год сдвигается на 28-е, а не теряется.
    if (s.startsWith("--")) {
        val digits = s.removePrefix("--").replace("-", "")
        if (digits.length == 4) {
            val mo = digits.take(2).toIntOrNull()
            val d  = digits.takeLast(2).toIntOrNull()
            if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                runCatching {
                    val maxDay = java.time.YearMonth.of(defaultYear, mo).lengthOfMonth()
                    return LocalDate.of(defaultYear, mo, d.coerceAtMost(maxDay))
                }
            }
        }
        return null
    }
    // 1) ISO (в т.ч. с временем «2025-08-15T…») — основной путь
    runCatching { return LocalDate.parse(s.take(10)) }
    // 2) числовые dd<sep>MM<sep>yyyy
    Regex("""^(\d{1,2})[ ./\-](\d{1,2})[ ./\-](\d{4})$""").find(s)?.let { m ->
        val (d, mo, y) = m.destructured
        runCatching { return LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }
    }
    // 3) русское «d MMMM[ yyyy]» (родительный падеж)
    Regex("""^(\d{1,2})\s+([А-Яа-яЁё]+)(?:\s+(\d{4}))?$""").find(s)?.let { m ->
        val day = m.groupValues[1].toIntOrNull()
        val mon = RU_MONTHS_GENITIVE[m.groupValues[2].lowercase()]
        val year = m.groupValues[3].toIntOrNull() ?: defaultYear
        if (day != null && mon != null)
            runCatching { return LocalDate.of(year, mon, day) }
    }
    return null
}

/** true, если дата хранится без года (vCard-стиль «--MM-DD»). */
fun isYearlessDate(raw: String?): Boolean = raw?.trim()?.startsWith("--") == true

/**
 * Человекочитаемая дата события для UI. Даты без года («--03-14») показываются
 * как «14 марта» (локаль системы; MMMM в формат-контексте даёт родительный падеж);
 * обычные строки возвращаются как есть — прежнее поведение не меняется.
 */
fun displayEventDate(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val s = raw.trim()
    if (!s.startsWith("--")) return s
    val d = parseFlexibleDate(s) ?: return s
    return d.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))
}

/**
 * Эффективная дата события для отображения и сортировки.
 *
 * Дни рождения (и любые FREQ=YEARLY) хранятся с исходной датой
 * («1990-06-15»), которая навсегда в прошлом — из-за этого импортированные
 * ДР создавались, но были невидимы в календаре и «Ближайшем».
 * Проецируем на ближайшее наступление: в этом году или в следующем.
 */
fun CalendarItem.effectiveDate(today: LocalDate = LocalDate.now()): String {
    val isYearly = type == CalendarItemType.BIRTHDAY ||
        recurrenceRule?.contains("YEARLY", ignoreCase = true) == true
    if (!isYearly) return startDate
    return try {
        // parseFlexibleDate: ISO + legacy-формы («15 08 2025», «30 мая»), иначе
        // годовые события с не-ISO датой не проецировались на ближайшее наступление.
        val d = parseFlexibleDate(startDate, today.year) ?: return startDate
        val thisYear = d.withYear(today.year)
        (if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear).toString()
    } catch (e: Exception) {
        startDate
    }
}
