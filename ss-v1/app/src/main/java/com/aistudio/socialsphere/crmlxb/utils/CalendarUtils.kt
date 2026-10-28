package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
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
 * Типы, для которых ПРОДУКТОВАЯ семантика уже сегодня подразумевает ежегодное
 * повторение — подтверждено PRE-FLIGHT по всем production creation paths:
 *  - GiftsTab: назначает RecurrenceMode.YEARLY при создании BIRTHDAY/
 *    ANNIVERSARY/NAMEDAY (IMPORTANT_DATE явно исключён);
 *  - CalendarItemEditScreen: автоматически YEARLY при выборе типа BIRTHDAY;
 *  - ImportScreens: импортированные дни рождения получают "FREQ=YEARLY".
 * IMPORTANT_DATE сюда НЕ входит — ни один из этих путей не назначает ему
 * YEARLY автоматически, значит продуктовая семантика этого не подтверждает
 * (см. explicit-инструкцию: не считать IMPORTANT_DATE annual без подтверждения).
 * ЕДИНСТВЕННОЕ место объявления этого набора — не дублировать его как
 * отдельный listOf(...) ни в CalendarScreen/CalendarUtils/NotificationScheduler,
 * ни где-либо ещё.
 */
private val LEGACY_ANNUAL_TYPES = setOf(
    CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY, CalendarItemType.NAMEDAY
)

/**
 * Единая policy интерпретации recurrence для ЧТЕНИЯ/отображения/планирования —
 * НЕ для записи. Compatibility interpretation, а не silent migration: raw
 * persisted recurrenceRule этой функцией никогда не переписывается, только
 * интерпретируется на лету.
 *
 * 1. Распознанная (непустая) recurrenceRule имеет приоритет независимо от
 *    [recurrenceExplicitlySet] — сама строка не трогается. Неизвестный
 *    нераспознанный (но НЕПУСТОЙ) raw rule не подменяется на YEARLY этой
 *    функцией — уходит в RecurrenceMode.fromRule() как есть (её собственный
 *    safe fallback на NONE, если явно не FREQ=YEARLY).
 * 2. recurrenceRule ОТСУТСТВУЕТ (null/пусто) и [recurrenceExplicitlySet] ==
 *    true → NONE. Пользователь (или современная версия приложения при
 *    создании) явно подтвердил отсутствие recurrence — legacy YEARLY-fallback
 *    для этой записи больше не применяется.
 * 3. recurrenceRule ОТСУТСТВУЕТ и [recurrenceExplicitlySet] == false
 *    (legacy/unset — например все записи, мигрировавшие с Room v18) и type
 *    исторически annual ([LEGACY_ANNUAL_TYPES]) → YEARLY, чтобы старые записи
 *    сохраняли прежний продуктовый смысл.
 * 4. Иначе — NONE.
 */
fun effectiveRecurrenceMode(type: CalendarItemType, recurrenceRule: String?, recurrenceExplicitlySet: Boolean): RecurrenceMode {
    if (!recurrenceRule.isNullOrBlank()) return RecurrenceMode.fromRule(recurrenceRule)
    if (recurrenceExplicitlySet) return RecurrenceMode.NONE
    return if (type in LEGACY_ANNUAL_TYPES) RecurrenceMode.YEARLY else RecurrenceMode.NONE
}

/**
 * Следующее НЕзавершённое occurrence recurring-серии — ЕДИНСТВЕННЫЙ canonical
 * источник «какое occurrence сейчас pending». Используется И рендерингом
 * (effectiveDate), И scheduler'ом (calculateNotificationTime), И business-layer
 * завершения (CalendarCompletion — targetOccurrenceDate) — recurrence-математика
 * не переизобретается ни в одном из этих мест отдельно.
 *
 * Anchor ВСЕГДА оригинальный startDate — lastCompletedOccurrenceDate НИКОГДА не
 * передаётся калькулятору как anchor (иначе monthly/yearly anti-drift калькулятора
 * ломается). Нижняя граница поиска — max(referenceDate, lastCompletedOccurrenceDate
 * + 1 день): уже завершённое occurrence никогда не вернётся как pending снова.
 */
fun CalendarItem.pendingOccurrenceDate(referenceDate: LocalDate = LocalDate.now()): LocalDate? {
    return try {
        val mode = effectiveRecurrenceMode(type, recurrenceRule, recurrenceExplicitlySet)
        val anchor = parseFlexibleDate(startDate, referenceDate.year) ?: return null
        val lastCompleted = lastCompletedOccurrenceDate?.let { parseFlexibleDate(it) }
        val lowerBound = when {
            lastCompleted == null -> referenceDate
            lastCompleted.plusDays(1).isAfter(referenceDate) -> lastCompleted.plusDays(1)
            else -> referenceDate
        }
        RecurrenceCalculator.nextOccurrenceOnOrAfter(anchor, mode, lowerBound)
    } catch (e: Exception) {
        null
    }
}

/**
 * Инвариант postpone-пары: production state обязан быть либо `from=null,
 * to=null`, либо `from!=null, to!=null` — никогда «половина пары». Чистая
 * проверка — ничего не чинит.
 */
fun CalendarItem.hasValidPostponePair(): Boolean =
    (postponedFromOccurrenceDate == null) == (postponedToDate == null)

/**
 * Typed pending occurrence (2026-09-17, runtime postpone semantics) —
 * НЕ использовать одну LocalDate для двух разных смыслов (requirement 1):
 *
 * [seriesDate] — identity occurrence ИСХОДНОЙ recurrence-серии. Используется
 * для: occurrence-completion (lastCompletedOccurrenceDate), occursOn-валидации,
 * anti-drift продвижения к следующему regular occurrence. НИКОГДА не меняется
 * постпоном — это то, ЧТО переносится, не КУДА.
 *
 * [effectiveDate] — дата, когда occurrence фактически должно отображаться/
 * напоминаться. Совпадает с [seriesDate] для обычного (не перенесённого)
 * occurrence; отличается только при активном valid postpone override.
 */
data class PendingCalendarOccurrence(
    val seriesDate: LocalDate,
    val effectiveDate: LocalDate,
)

/**
 * requirement 3/4: валидный кандидат postpone для recurring occurrence —
 * ЕДИНСТВЕННОЕ место, проверяющее диапазон (используется и на write
 * (CalendarPostpone), и на read (pendingOccurrence ниже) — не дублируется).
 * Вся математика — через RecurrenceCalculator, arithmetic не переизобретается.
 *
 * seriesDate обязан быть: occurrence текущей canonical серии (occursOn); после
 * lastCompletedOccurrenceDate, если completion уже есть (иначе завершённое
 * occurrence нельзя «перенести» — оно уже в прошлом бизнес-смысла).
 * targetDate обязан быть: строго после seriesDate; строго ДО следующего
 * regular occurrence после seriesDate (requirement 4 — crossing next occurrence
 * запрещён: иначе два occurrence серии начинают пересекаться, а модель хранит
 * максимум один active override).
 *
 * DAILY: следующее regular occurrence — ровно seriesDate+1 (следующий день),
 * а targetDate обязан быть И строго после seriesDate, И строго до seriesDate+1
 * — между двумя соседними LocalDate такого значения не существует, поэтому
 * ЛЮБОЙ targetDate для DAILY отклоняется этой же формулой. Отдельного
 * DAILY-кейса не заводим — ограничение возникает из общего правила само собой.
 */
fun isValidPostponeTarget(
    anchor: LocalDate,
    mode: RecurrenceMode,
    lastCompleted: LocalDate?,
    seriesDate: LocalDate,
    targetDate: LocalDate,
): Boolean {
    if (mode == RecurrenceMode.NONE) return false
    if (!RecurrenceCalculator.occursOn(anchor, mode, seriesDate)) return false
    if (lastCompleted != null && !seriesDate.isAfter(lastCompleted)) return false
    if (!targetDate.isAfter(seriesDate)) return false
    val nextRegular = RecurrenceCalculator.nextOccurrenceOnOrAfter(anchor, mode, seriesDate.plusDays(1)) ?: return false
    return targetDate.isBefore(nextRegular)
}

/**
 * requirement 2: canonical pending occurrence С УЧЁТОМ valid postpone override.
 * Recurrence-математика не дублируется — normal (не перенесённое) occurrence
 * всегда идёт через pendingOccurrenceDate() (тот же anchor/RecurrenceCalculator/
 * lastCompleted, что и раньше).
 *
 * Override приоритетен и проверяется НЕЗАВИСИМО от referenceDate: если сейчас
 * (до postpone) «сегодня» уже прошло мимо seriesDate, normal-вычисление само
 * по себе уже продвинулось бы дальше (см. pendingOccurrenceDate) — но активный
 * валидный override не должен «протухать» только оттого, что время идёт
 * (иначе постпон терял бы смысл в день, когда его сделали). Поэтому pair
 * сначала проверяется САМ ПО СЕБЕ через isValidPostponeTarget (requirement 3),
 * и только если он невалиден/отсутствует — используется normal-вычисление.
 *
 * Data Safety (requirement 3): corrupt/невалидный persisted pair НИКОГДА не
 * применяется и НИКОГДА не переписывается здесь молча — просто игнорируется,
 * ремонт (если вообще нужен) — задача отдельного явного write-пути, не read.
 */
fun CalendarItem.pendingOccurrence(referenceDate: LocalDate = LocalDate.now()): PendingCalendarOccurrence? {
    val mode = effectiveRecurrenceMode(type, recurrenceRule, recurrenceExplicitlySet)
    if (mode != RecurrenceMode.NONE) {
        val from = postponedFromOccurrenceDate?.let { parseFlexibleDate(it) }
        val to = postponedToDate?.let { parseFlexibleDate(it) }
        if (from != null && to != null) {
            val anchor = parseFlexibleDate(startDate, referenceDate.year)
            val lastCompleted = lastCompletedOccurrenceDate?.let { parseFlexibleDate(it) }
            if (anchor != null && isValidPostponeTarget(anchor, mode, lastCompleted, from, to)) {
                return PendingCalendarOccurrence(from, to)
            }
        }
    }
    val normal = pendingOccurrenceDate(referenceDate) ?: return null
    return PendingCalendarOccurrence(normal, normal)
}

/**
 * Эффективная дата события для отображения и сортировки.
 *
 * NONE (обычные one-shot события, и annual-типы с явно нераспознанным/пустым
 * rule ТОЛЬКО если type не входит в LEGACY_ANNUAL_TYPES) — прежняя one-shot
 * семантика: возвращается startDate как есть, без проекции (raw-строка, не
 * переформатированная через parse — легаси-форматы не трогаются). Для
 * DAILY/WEEKLY/MONTHLY/YEARLY — pendingOccurrence().effectiveDate: если
 * текущее occurrence уже завершено (lastCompletedOccurrenceDate), Upcoming
 * автоматически продвигается на следующее; если оно активно перенесено
 * (postpone pair) — на дату переноса (requirement 7).
 */
fun CalendarItem.effectiveDate(today: LocalDate = LocalDate.now()): String {
    if (effectiveRecurrenceMode(type, recurrenceRule, recurrenceExplicitlySet) == RecurrenceMode.NONE) return startDate
    return pendingOccurrence(today)?.effectiveDate?.toString() ?: startDate
}
