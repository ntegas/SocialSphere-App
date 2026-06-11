package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import java.time.LocalDate

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
        val d = LocalDate.parse(startDate.take(10))
        val thisYear = d.withYear(today.year)
        (if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear).toString()
    } catch (e: Exception) {
        startDate
    }
}
