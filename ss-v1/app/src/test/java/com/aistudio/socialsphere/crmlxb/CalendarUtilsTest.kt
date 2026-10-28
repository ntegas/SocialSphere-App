package com.aistudio.socialsphere.crmlxb

import com.aistudio.socialsphere.crmlxb.utils.parseFlexibleDate
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * Тесты толерантного разбора дат события. Регрессия: NotificationScheduler ронял
 * DateTimeParseException на legacy-формах startDate («15 08 2025», «30 мая») и тихо
 * терял напоминания. parseFlexibleDate должен разбирать ISO + числовые + русские формы.
 */
class CalendarUtilsTest {

    @Test
    fun iso_isParsed() {
        assertEquals(LocalDate.of(2025, 8, 15), parseFlexibleDate("2025-08-15"))
    }

    @Test
    fun isoWithTime_isTruncatedAndParsed() {
        assertEquals(LocalDate.of(2025, 8, 15), parseFlexibleDate("2025-08-15T09:30:00"))
    }

    @Test
    fun numericSpaceSeparated_isParsed() {
        // Ровно та форма из лога устройства, что роняла парсинг
        assertEquals(LocalDate.of(2025, 8, 15), parseFlexibleDate("15 08 2025"))
    }

    @Test
    fun numericDottedAndSlashed_areParsed() {
        assertEquals(LocalDate.of(2025, 8, 15), parseFlexibleDate("15.08.2025"))
        assertEquals(LocalDate.of(2025, 8, 15), parseFlexibleDate("15/08/2025"))
    }

    @Test
    fun russianMonthWithoutYear_usesDefaultYear() {
        // demo-данные: «30 мая» без года → подставляется defaultYear
        assertEquals(LocalDate.of(2030, 5, 30), parseFlexibleDate("30 мая", defaultYear = 2030))
    }

    @Test
    fun russianMonthWithYear_isParsed() {
        assertEquals(LocalDate.of(2025, 6, 2), parseFlexibleDate("2 июня 2025"))
    }

    @Test
    fun blankOrUnparseable_returnsNull() {
        assertNull(parseFlexibleDate(null))
        assertNull(parseFlexibleDate(""))
        assertNull(parseFlexibleDate("не дата"))
        assertNull(parseFlexibleDate("15 13 2025"))  // месяц 13 невалиден
    }
}
