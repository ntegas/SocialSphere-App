package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * CALENDAR RUNTIME & ALARM LIFECYCLE PACK — политика «пропущенный BEFORE-триггер» и её согласованность с Calendar UI.
 *
 * Дефект: recurring occurrence Sep16, postponedTo Sep18 10:00, «за 1 день»; postpone сделан Sep17 15:00 → триггер Sep17 10:00
 * уже прошёл, и планировщик перескакивал на СЛЕДУЮЩЕЕ регулярное occurrence, тогда как Calendar/Upcoming показывает перенесённое
 * Sep18. Политика: если BEFORE-триггер текущего pending occurrence прошёл, но само эффективное occurrence ещё впереди —
 * будильник на время occurrence (не «сейчас», чтобы reconcile не показывал catch-up повторно), identity остаётся seriesDate;
 * к следующему regular occurrence — только если прошло и само эффективное время.
 *
 * Все даты — относительно реального now и подобраны так, чтобы исход не зависел от часа прогона: «прошёл/впереди» обеспечено
 * разницей в ДНЯХ (или временем 00:00 для «occurrence уже позади»).
 */
@RunWith(RobolectricTestRunner::class)
class ReminderPostponeFallbackTest {

    private lateinit var ctx: Context
    private val today: LocalDate = LocalDate.now()

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
    }

    private fun alarms() = shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name }

    private fun epoch(d: LocalDate, time: String): Long =
        LocalDateTime.of(d, LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun before(id: String, days: Int) = ReminderRule(id, "it", ReminderType.BEFORE, days, ReminderOffsetUnit.DAYS, null)

    /** WEEKLY, anchor = день недели «сегодня» (4 недели назад) → occurrence сегодня, +7, +14... */
    private fun weekly(
        anchor: LocalDate, time: String, rule: ReminderRule, pair: Pair<LocalDate, LocalDate>? = null,
    ) = CalendarItem(
        id = "it", title = "t", type = CalendarItemType.CALL, startDate = anchor.toString(), startTime = time, isAllDay = false,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, recurrenceRule = RecurrenceMode.WEEKLY.toRRule(),
        recurrenceExplicitlySet = true, postponedFromOccurrenceDate = pair?.first?.toString(), postponedToDate = pair?.second?.toString(),
        reminders = listOf(rule), createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")

    private fun occurrenceExtra(): String? = shadowOf(alarms().single().operation).savedIntent.getStringExtra("occurrenceDate")

    /** P1 + P4: перенос сегодня→today+2; BEFORE-триггер (за 1 день = today+1 10:00) ещё впереди → обычное BEFORE-время. */
    @Test
    fun P1_postponedOccurrence_futureBeforeTrigger_usesNormalBeforeTime_andSeriesIdentity() {
        val rule = before("r1", 1)
        val item = weekly(today.minusWeeks(4), "10:00", rule, pair = today to today.plusDays(2))

        NotificationScheduler.scheduleReminder(ctx, item, rule)

        assertEquals("обычное BEFORE-время от ЭФФЕКТИВНОЙ даты (today+2 − 1 день)", epoch(today.plusDays(1), "10:00"), alarms().single().triggerAtTime)
        assertEquals("P4: payload — ИСХОДНАЯ дата серии, не дата переноса", today.toString(), occurrenceExtra())
    }

    /** P2 + P4 + «не сейчас»: BEFORE-триггер (today−1 10:00) прошёл, эффективное occurrence (today+2 10:00) впереди → fallback на его время. */
    @Test
    fun P2_missedBeforeTrigger_effectiveOccurrenceAhead_fallsBackToOccurrenceTime() {
        val rule = before("r2", 3)
        val item = weekly(today.minusWeeks(4), "10:00", rule, pair = today to today.plusDays(2))

        NotificationScheduler.scheduleReminder(ctx, item, rule)

        val alarm = alarms().single()
        assertEquals("fallback = время эффективного occurrence (перенесённое), а не следующее регулярное", epoch(today.plusDays(2), "10:00"), alarm.triggerAtTime)
        assertTrue("НЕ немедленный catch-up: будильник не «сейчас», а на время occurrence", alarm.triggerAtTime > System.currentTimeMillis() + 24L * 60 * 60 * 1000)
        assertEquals("identity остаётся seriesDate", today.toString(), occurrenceExtra())
        assertTrue("UI: «запланировано» согласовано с реально поставленным будильником", NotificationScheduler.isReminderScheduled(item, rule))
    }

    /** P3: эффективное occurrence (today−5 00:00) само уже позади → следующее РЕГУЛЯРНОЕ occurrence (today+7), его BEFORE-время. */
    @Test
    fun P3_effectiveOccurrenceItselfPast_movesToNextRegularOccurrence() {
        val rule = before("r3", 1)
        val item = weekly(today.minusWeeks(4), "00:00", rule, pair = today.minusDays(7) to today.minusDays(5))

        NotificationScheduler.scheduleReminder(ctx, item, rule)

        assertEquals("следующее регулярное occurrence today+7 → за 1 день = today+6 00:00", epoch(today.plusDays(6), "00:00"), alarms().single().triggerAtTime)
        assertEquals("identity — то самое следующее occurrence, не перенесённая и не исходная старая дата", today.plusDays(7).toString(), occurrenceExtra())
    }

    /** Та же политика для НЕперенесённого occurrence: завтра 10:00, «за 2 дня» уже пропущено → fallback на завтра, а не на следующую неделю. */
    @Test
    fun missedBeforeTrigger_regularOccurrenceAhead_fallsBackToItsTime_notNextWeek() {
        val tomorrow = today.plusDays(1)
        val rule = before("r4", 2)
        val item = weekly(tomorrow.minusWeeks(4), "10:00", rule)

        NotificationScheduler.scheduleReminder(ctx, item, rule)

        assertEquals(epoch(tomorrow, "10:00"), alarms().single().triggerAtTime)
        assertEquals(tomorrow.toString(), occurrenceExtra())
    }

    /** Одноразовое событие (NONE): пропущенный BEFORE при ещё будущем событии → fallback на время события; identity нет. */
    @Test
    fun oneShotEvent_missedBeforeTrigger_eventAhead_fallsBackToEventTime_noOccurrenceIdentity() {
        val rule = before("r5", 2)
        val tomorrow = today.plusDays(1)
        val item = CalendarItem(id = "it", title = "t", type = CalendarItemType.TASK, startDate = tomorrow.toString(), startTime = "10:00",
            isAllDay = false, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, reminders = listOf(rule),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")

        NotificationScheduler.scheduleReminder(ctx, item, rule)

        assertEquals(epoch(tomorrow, "10:00"), alarms().single().triggerAtTime)
        assertNull("у одноразового события occurrence-identity нет", occurrenceExtra())
        // и событие целиком в прошлом по-прежнему не получает ничего
        val past = item.copy(startDate = today.minusDays(3).toString())
        val before = alarms().size
        NotificationScheduler.scheduleReminder(ctx, past, rule.copy(id = "r5b"))
        assertEquals("событие в прошлом — будильника нет", before, alarms().size)
    }

    /** P6: isReminderScheduled() и AlarmManager-расчёт — один и тот же выбор (UI не показывает «запланировано» для другого occurrence). */
    @Test
    fun P6_isReminderScheduled_agreesWithActualAlarm_inEveryScenario() {
        val cases = listOf(
            weekly(today.minusWeeks(4), "10:00", before("c1", 1), pair = today to today.plusDays(2)),        // P1
            weekly(today.minusWeeks(4), "10:00", before("c2", 3), pair = today to today.plusDays(2)),        // P2 fallback
            weekly(today.minusWeeks(4), "00:00", before("c3", 1), pair = today.minusDays(7) to today.minusDays(5)), // P3 next regular
            weekly(today.plusDays(1).minusWeeks(4), "10:00", before("c4", 2)),                                // regular fallback
        )
        cases.forEach { item ->
            val rule = item.reminders.single()
            val before = alarms().size
            NotificationScheduler.scheduleReminder(ctx, item.copy(id = "it-${rule.id}"), rule.copy(calendarItemId = "it-${rule.id}"))
            val placed = alarms().size > before
            assertEquals("${rule.id}: «запланировано» == будильник реально поставлен", placed,
                NotificationScheduler.isReminderScheduled(item, rule))
            assertTrue("${rule.id}: во всех этих сценариях будущее occurrence существует", placed)
        }
        // и там, где будильника нет, UI тоже не обещает его
        val gone = weekly(today.minusWeeks(4), "10:00", before("c5", 1)).copy(status = CalendarItemStatus.ACTIVE, recurrenceRule = null,
            recurrenceExplicitlySet = true, startDate = today.minusDays(3).toString())
        assertFalse(NotificationScheduler.isReminderScheduled(gone, gone.reminders.single()))
    }
}
