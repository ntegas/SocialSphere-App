package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Plain JUnit4, pure-JVM tests for [RecurrenceCalculator].
 *
 * Deliberately NOT @RunWith(RobolectricTestRunner::class) and does not touch any Android
 * framework class — RecurrenceCalculator has zero Android dependencies, and this test
 * proves it by never needing one.
 */
class RecurrenceCalculatorTest {

    // ---------------------------------------------------------------------
    // NONE
    // ---------------------------------------------------------------------

    @Test
    fun none_fromBeforeStart_returnsStart() {
        val start = LocalDate.of(2026, 5, 10)
        val from = LocalDate.of(2026, 5, 1)
        assertEquals(start, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.NONE, from))
    }

    @Test
    fun none_fromExactlyStart_returnsStart_inclusive() {
        val start = LocalDate.of(2026, 5, 10)
        assertEquals(start, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.NONE, start))
    }

    @Test
    fun none_fromAfterStart_returnsNull() {
        val start = LocalDate.of(2026, 5, 10)
        val from = LocalDate.of(2026, 5, 11)
        assertNull(RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.NONE, from))
    }

    @Test
    fun none_occursOn_trueOnlyOnStart() {
        val start = LocalDate.of(2026, 5, 10)
        assertTrue(RecurrenceCalculator.occursOn(start, RecurrenceMode.NONE, start))
        assertFalse(RecurrenceCalculator.occursOn(start, RecurrenceMode.NONE, start.minusDays(1)))
        assertFalse(RecurrenceCalculator.occursOn(start, RecurrenceMode.NONE, start.plusDays(1)))
    }

    // ---------------------------------------------------------------------
    // DAILY
    // ---------------------------------------------------------------------

    @Test
    fun daily_fromBeforeStart_returnsStart() {
        val start = LocalDate.of(2026, 3, 1)
        val from = LocalDate.of(2026, 2, 20)
        assertEquals(start, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.DAILY, from))
    }

    @Test
    fun daily_fromExactlyStart_returnsStart() {
        val start = LocalDate.of(2026, 3, 1)
        assertEquals(start, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.DAILY, start))
    }

    @Test
    fun daily_fromStartPlus100Days_returnsSameDate() {
        val start = LocalDate.of(2026, 3, 1)
        val from = start.plusDays(100)
        assertEquals(from, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.DAILY, from))
    }

    @Test
    fun daily_occursOn_falseBeforeStart() {
        val start = LocalDate.of(2026, 3, 1)
        assertFalse(RecurrenceCalculator.occursOn(start, RecurrenceMode.DAILY, start.minusDays(1)))
    }

    @Test
    fun daily_occursOn_trueOnAndAfterStart() {
        val start = LocalDate.of(2026, 3, 1)
        assertTrue(RecurrenceCalculator.occursOn(start, RecurrenceMode.DAILY, start))
        assertTrue(RecurrenceCalculator.occursOn(start, RecurrenceMode.DAILY, start.plusDays(42)))
    }

    // ---------------------------------------------------------------------
    // WEEKLY (anchor = 2026-09-16, a Wednesday)
    // ---------------------------------------------------------------------

    private val weeklyAnchor: LocalDate = LocalDate.of(2026, 9, 16)

    @Test
    fun weekly_fromExactAnchor_returnsAnchor() {
        assertEquals(
            weeklyAnchor,
            RecurrenceCalculator.nextOccurrenceOnOrAfter(weeklyAnchor, RecurrenceMode.WEEKLY, weeklyAnchor)
        )
    }

    @Test
    fun weekly_fromAnchorPlus1Day_returnsAnchorPlus7() {
        val from = weeklyAnchor.plusDays(1) // 2026-09-17
        val expected = weeklyAnchor.plusDays(7) // 2026-09-23
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(weeklyAnchor, RecurrenceMode.WEEKLY, from))
    }

    @Test
    fun weekly_severalWeeksLater_returnsCorrectNextMultipleOf7() {
        // 5 weeks + 2 days after anchor -> should round up to the next multiple of 7
        val from = weeklyAnchor.plusDays(5 * 7 + 2)
        val expected = weeklyAnchor.plusDays(6 * 7)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(weeklyAnchor, RecurrenceMode.WEEKLY, from))
    }

    @Test
    fun weekly_fromBeforeAnchor_returnsAnchor() {
        val from = weeklyAnchor.minusDays(3)
        assertEquals(weeklyAnchor, RecurrenceCalculator.nextOccurrenceOnOrAfter(weeklyAnchor, RecurrenceMode.WEEKLY, from))
    }

    @Test
    fun weekly_occursOn_trueOnAnchorPlusMultipleOf7() {
        val date = weeklyAnchor.plusWeeks(4)
        assertTrue(RecurrenceCalculator.occursOn(weeklyAnchor, RecurrenceMode.WEEKLY, date))
    }

    @Test
    fun weekly_occursOn_falseOnSameWeekdayBeforeAnchor() {
        // Same weekday (Wednesday) as anchor, but one week BEFORE anchor -> must be false
        // because of the start-boundary guard, not just the modulo-7 check.
        val sameWeekdayBefore = weeklyAnchor.minusWeeks(1)
        assertFalse(RecurrenceCalculator.occursOn(weeklyAnchor, RecurrenceMode.WEEKLY, sameWeekdayBefore))
    }

    // ---------------------------------------------------------------------
    // MONTHLY NORMAL (start = 2026-01-15)
    // ---------------------------------------------------------------------

    private val monthlyNormalStart: LocalDate = LocalDate.of(2026, 1, 15)

    @Test
    fun monthlyNormal_jan15Itself() {
        assertEquals(
            monthlyNormalStart,
            RecurrenceCalculator.nextOccurrenceOnOrAfter(monthlyNormalStart, RecurrenceMode.MONTHLY, monthlyNormalStart)
        )
    }

    @Test
    fun monthlyNormal_feb15() {
        val from = LocalDate.of(2026, 2, 1)
        val expected = LocalDate.of(2026, 2, 15)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(monthlyNormalStart, RecurrenceMode.MONTHLY, from))
    }

    @Test
    fun monthlyNormal_dec15SameYear() {
        val from = LocalDate.of(2026, 12, 1)
        val expected = LocalDate.of(2026, 12, 15)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(monthlyNormalStart, RecurrenceMode.MONTHLY, from))
    }

    @Test
    fun monthlyNormal_crossingIntoNextYear() {
        val from = LocalDate.of(2026, 12, 16) // just past Dec 15
        val expected = LocalDate.of(2027, 1, 15)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(monthlyNormalStart, RecurrenceMode.MONTHLY, from))
    }

    // ---------------------------------------------------------------------
    // MONTHLY 31 (start = 2026-01-31) — anti-drift proof
    // ---------------------------------------------------------------------

    private val monthly31Start: LocalDate = LocalDate.of(2026, 1, 31)

    @Test
    fun monthly31_feb_clampsTo28() {
        val from = LocalDate.of(2026, 2, 1)
        val expected = LocalDate.of(2026, 2, 28)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(monthly31Start, RecurrenceMode.MONTHLY, from))
    }

    @Test
    fun monthly31_mar_noDrift_backTo31() {
        val from = LocalDate.of(2026, 3, 1)
        val expected = LocalDate.of(2026, 3, 31)
        val actual = RecurrenceCalculator.nextOccurrenceOnOrAfter(monthly31Start, RecurrenceMode.MONTHLY, from)
        assertEquals(expected, actual)
        // Explicitly guard against the drift bug: Mar must NOT be 28 (which would happen
        // if Mar were computed by clamping Feb's already-clamped 28 instead of the
        // original anchorDay=31).
        assertFalse(LocalDate.of(2026, 3, 28) == actual)
    }

    @Test
    fun monthly31_apr_clampsTo30() {
        val from = LocalDate.of(2026, 4, 1)
        val expected = LocalDate.of(2026, 4, 30)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(monthly31Start, RecurrenceMode.MONTHLY, from))
    }

    @Test
    fun monthly31_may_backTo31() {
        val from = LocalDate.of(2026, 5, 1)
        val expected = LocalDate.of(2026, 5, 31)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(monthly31Start, RecurrenceMode.MONTHLY, from))
    }

    @Test
    fun monthly31_leapYear_febIs29ThenMarIs31() {
        val start = LocalDate.of(2028, 1, 31)
        val feb = RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.MONTHLY, LocalDate.of(2028, 2, 1))
        assertEquals(LocalDate.of(2028, 2, 29), feb)
        val mar = RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.MONTHLY, LocalDate.of(2028, 3, 1))
        assertEquals(LocalDate.of(2028, 3, 31), mar)
    }

    // ---------------------------------------------------------------------
    // MONTHLY 30 (start = 2026-01-30, non-leap Feb) — anti-drift proof
    // ---------------------------------------------------------------------

    private val monthly30Start: LocalDate = LocalDate.of(2026, 1, 30)

    @Test
    fun monthly30_feb_clampsTo28() {
        val from = LocalDate.of(2026, 2, 1)
        val expected = LocalDate.of(2026, 2, 28)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(monthly30Start, RecurrenceMode.MONTHLY, from))
    }

    @Test
    fun monthly30_mar_noPermanentDegradation_backTo30() {
        val from = LocalDate.of(2026, 3, 1)
        val expected = LocalDate.of(2026, 3, 30)
        val actual = RecurrenceCalculator.nextOccurrenceOnOrAfter(monthly30Start, RecurrenceMode.MONTHLY, from)
        assertEquals(expected, actual)
        // The anchor must not be permanently degraded to 28 after passing through February.
        assertFalse(LocalDate.of(2026, 3, 28) == actual)
    }

    // ---------------------------------------------------------------------
    // YEARLY NORMAL (start = 2020-09-16)
    // ---------------------------------------------------------------------

    private val yearlyNormalStart: LocalDate = LocalDate.of(2020, 9, 16)

    @Test
    fun yearlyNormal_threeConsecutiveYears() {
        assertEquals(
            LocalDate.of(2021, 9, 16),
            RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyNormalStart, RecurrenceMode.YEARLY, LocalDate.of(2021, 1, 1))
        )
        assertEquals(
            LocalDate.of(2022, 9, 16),
            RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyNormalStart, RecurrenceMode.YEARLY, LocalDate.of(2022, 1, 1))
        )
        assertEquals(
            LocalDate.of(2030, 9, 16),
            RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyNormalStart, RecurrenceMode.YEARLY, LocalDate.of(2030, 1, 1))
        )
    }

    @Test
    fun yearlyNormal_fromExactlyStart_returnsStart_inclusive() {
        assertEquals(
            yearlyNormalStart,
            RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyNormalStart, RecurrenceMode.YEARLY, yearlyNormalStart)
        )
    }

    @Test
    fun yearlyNormal_occursOn_trueOnAnniversaries() {
        assertTrue(RecurrenceCalculator.occursOn(yearlyNormalStart, RecurrenceMode.YEARLY, LocalDate.of(2025, 9, 16)))
        assertFalse(RecurrenceCalculator.occursOn(yearlyNormalStart, RecurrenceMode.YEARLY, LocalDate.of(2025, 9, 17)))
    }

    // ---------------------------------------------------------------------
    // YEARLY FEB 29 (start = 2024-02-29) — anti-drift proof
    // ---------------------------------------------------------------------

    private val yearlyFeb29Start: LocalDate = LocalDate.of(2024, 2, 29)

    @Test
    fun yearlyFeb29_chain_neverPermanentlyStuckOnMar1() {
        // Chain: each fromDate is the day after the previous result, proving the
        // implementation recomputes fresh from the fixed anchor each year rather than
        // chaining off the previous actual resolved date.
        var from = LocalDate.of(2025, 1, 1)

        val r2025 = RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyFeb29Start, RecurrenceMode.YEARLY, from)
        assertEquals(LocalDate.of(2025, 3, 1), r2025)

        from = r2025!!.plusDays(1)
        val r2026 = RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyFeb29Start, RecurrenceMode.YEARLY, from)
        assertEquals(LocalDate.of(2026, 3, 1), r2026)

        from = r2026!!.plusDays(1)
        val r2027 = RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyFeb29Start, RecurrenceMode.YEARLY, from)
        assertEquals(LocalDate.of(2027, 3, 1), r2027)

        from = r2027!!.plusDays(1)
        val r2028 = RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyFeb29Start, RecurrenceMode.YEARLY, from)
        // 2028 IS a leap year -> must resolve to Feb 29 again, NOT stay stuck on Mar 1.
        assertEquals(LocalDate.of(2028, 2, 29), r2028)

        from = r2028!!.plusDays(1)
        val r2029 = RecurrenceCalculator.nextOccurrenceOnOrAfter(yearlyFeb29Start, RecurrenceMode.YEARLY, from)
        assertEquals(LocalDate.of(2029, 3, 1), r2029)
    }

    @Test
    fun yearlyFeb29_occursOn_trueOn2028Feb29() {
        assertTrue(RecurrenceCalculator.occursOn(yearlyFeb29Start, RecurrenceMode.YEARLY, LocalDate.of(2028, 2, 29)))
    }

    // ---------------------------------------------------------------------
    // LARGE GAP — proves O(1)/bounded-correction arithmetic, not a day-by-day scan
    // ---------------------------------------------------------------------

    @Test
    fun largeGap_daily_thousandsOfDaysAfterStart_returnsExactDate() {
        val start = LocalDate.of(2000, 1, 1)
        val from = start.plusDays(9000)
        assertEquals(from, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.DAILY, from))
    }

    @Test
    fun largeGap_weekly_manyYearsAfterStart_returnsExactDate() {
        val start = LocalDate.of(2000, 1, 5) // Wednesday
        // 50 years later, offset a bit within a week
        val from = start.plusYears(50).plusDays(2)
        val result = RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.WEEKLY, from)
        assertTrue(result != null)
        // Must land on an exact multiple of 7 days from start, and on start's weekday.
        val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(start, result)
        assertEquals(0L, daysBetween % 7)
        assertEquals(start.dayOfWeek, result!!.dayOfWeek)
        assertTrue(!result.isBefore(from))
    }

    @Test
    fun largeGap_monthly_decadesAfterStart_honorsAnchorClamp() {
        val start = LocalDate.of(2000, 1, 31)
        // Decades later, land in a February of a non-leap year.
        val from = LocalDate.of(2050, 2, 1) // 2050 is not a leap year
        val expected = LocalDate.of(2050, 2, 28)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.MONTHLY, from))
    }

    @Test
    fun largeGap_yearly_decadesAfterStart_febLeapAndAdjacentNonLeap() {
        val start = LocalDate.of(2000, 2, 29)
        // 2052 is a leap year decades out -> resolves to Feb 29.
        val leapResult = RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.YEARLY, LocalDate.of(2052, 1, 1))
        assertEquals(LocalDate.of(2052, 2, 29), leapResult)
        // 2053 is not a leap year -> resolves to Mar 1.
        val nonLeapResult = RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.YEARLY, LocalDate.of(2053, 1, 1))
        assertEquals(LocalDate.of(2053, 3, 1), nonLeapResult)
    }

    // ---------------------------------------------------------------------
    // OLD BUG PROOF
    // ---------------------------------------------------------------------

    /**
     * Fixes the historical inconsistency where two separate ad-hoc implementations —
     * CalendarUtils.effectiveDate() and NotificationScheduler.calculateNotificationTime()
     * — disagreed on what the next occurrence after a 2024-02-29 YEARLY start is, for a
     * `fromDate` early the following year. The canonical answer is 2025-03-01.
     */
    @Test
    fun oldBugProof_yearlyFeb29_from2025_01_01_resolvesToMarch1() {
        val start = LocalDate.of(2024, 2, 29)
        val from = LocalDate.of(2025, 1, 1)
        val expected = LocalDate.of(2025, 3, 1)
        assertEquals(expected, RecurrenceCalculator.nextOccurrenceOnOrAfter(start, RecurrenceMode.YEARLY, from))
    }
}
