package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Pure, isolated date-recurrence calculator.
 *
 * This is the FUTURE canonical replacement for the ~5 independent ad-hoc copies of
 * recurrence date logic scattered across the app (a confirmed Feb-29 drift bug exists
 * between two of them: CalendarUtils.effectiveDate() and
 * NotificationScheduler.calculateNotificationTime() disagree on what the next
 * occurrence after a 2024-02-29 YEARLY start is, for a `fromDate` early the next year).
 *
 * It is intentionally NOT wired into any existing caller yet — this file is scaffolding
 * and proof that a single, correct, O(1)-per-query implementation is possible. It has
 * zero Android dependencies (no Context, no Room, no Compose, no AlarmManager, no
 * implicit system clock/timezone) so it can be unit tested with plain JUnit.
 *
 * All arithmetic is anchor-based: the anchor (day-of-month for MONTHLY, month+day for
 * YEARLY) is read ONCE from [LocalDate]'s fields of `startDate` and reused unchanged for
 * every computed occurrence. Occurrences are NEVER derived by walking forward from a
 * previously resolved (and possibly clamped) occurrence — that is exactly the pattern
 * that causes drift (e.g. Jan31 -> Feb28 -> "Mar28" instead of Mar31, or a Feb-29 anchor
 * getting permanently stuck on Mar1 after the first non-leap year it passes through).
 */
object RecurrenceCalculator {

    fun occursOn(startDate: LocalDate, mode: RecurrenceMode, date: LocalDate): Boolean {
        if (date.isBefore(startDate)) return false
        return when (mode) {
            RecurrenceMode.NONE -> date == startDate
            RecurrenceMode.DAILY -> true // already know date is on/after startDate (checked above)
            RecurrenceMode.WEEKLY -> {
                val days = ChronoUnit.DAYS.between(startDate, date)
                days % 7 == 0L
            }
            RecurrenceMode.MONTHLY -> {
                val anchorDay = startDate.dayOfMonth
                val candidate = occurrenceForYearMonth(anchorDay, YearMonth.from(date))
                candidate == date
            }
            RecurrenceMode.YEARLY -> {
                val candidate = occurrenceForYear(startDate.monthValue, startDate.dayOfMonth, date.year)
                candidate == date
            }
        }
    }

    fun nextOccurrenceOnOrAfter(startDate: LocalDate, mode: RecurrenceMode, fromDate: LocalDate): LocalDate? {
        if (!fromDate.isAfter(startDate)) return startDate
        return when (mode) {
            RecurrenceMode.NONE -> null
            RecurrenceMode.DAILY -> fromDate
            RecurrenceMode.WEEKLY -> {
                val days = ChronoUnit.DAYS.between(startDate, fromDate)
                val remainder = days % 7
                if (remainder == 0L) fromDate else fromDate.plusDays(7 - remainder)
            }
            RecurrenceMode.MONTHLY -> {
                val anchorDay = startDate.dayOfMonth
                val targetYm = YearMonth.from(fromDate)
                val candidate = occurrenceForYearMonth(anchorDay, targetYm)
                if (!candidate.isBefore(fromDate)) candidate else occurrenceForYearMonth(anchorDay, targetYm.plusMonths(1))
            }
            RecurrenceMode.YEARLY -> {
                val anchorMonth = startDate.monthValue
                val anchorDay = startDate.dayOfMonth
                val candidate = occurrenceForYear(anchorMonth, anchorDay, fromDate.year)
                if (!candidate.isBefore(fromDate)) candidate else occurrenceForYear(anchorMonth, anchorDay, fromDate.year + 1)
            }
        }
    }

    private fun occurrenceForYearMonth(anchorDay: Int, ym: YearMonth): LocalDate {
        val day = minOf(anchorDay, ym.lengthOfMonth())
        return ym.atDay(day)
    }

    private fun occurrenceForYear(anchorMonth: Int, anchorDay: Int, year: Int): LocalDate {
        return if (anchorMonth == 2 && anchorDay == 29) {
            if (Year.isLeap(year.toLong())) LocalDate.of(year, 2, 29) else LocalDate.of(year, 3, 1)
        } else {
            LocalDate.of(year, anchorMonth, anchorDay)
        }
    }
}
