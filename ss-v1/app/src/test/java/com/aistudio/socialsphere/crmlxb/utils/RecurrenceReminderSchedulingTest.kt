package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.ImportanceLevel
import com.aistudio.socialsphere.crmlxb.model.ReminderOffsetUnit
import com.aistudio.socialsphere.crmlxb.model.ReminderRule
import com.aistudio.socialsphere.crmlxb.model.ReminderType
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import org.junit.Assert.assertEquals
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
import java.time.format.DateTimeFormatter

/**
 * Проверяет подключение canonical RecurrenceCalculator к
 * NotificationScheduler.calculateNotificationTime() — единственная цель:
 * доказать, что occurrence-date для ЛЮБОГО режима (NONE/DAILY/WEEKLY/MONTHLY/
 * YEARLY) теперь реально определяется через калькулятор, а не через
 * собственную (или отсутствующую) recurrence-математику планировщика.
 * Арифметика самого калькулятора уже исчерпывающе доказана в
 * RecurrenceCalculatorTest — здесь калькулятор местами используется как
 * доверенный oracle, чтобы проверить именно WIRING (anchor передан
 * оригинальный, offset применяется ПОСЛЕ occurrence, bounded search реально
 * продвигается через calculator, а не руками).
 *
 * Даты строятся ОТНОСИТЕЛЬНО LocalDateTime.now() (а не хардкодятся), чтобы
 * тест был детерминирован независимо от реальной даты прогона — тот же
 * принцип, что и в остальных Robolectric-тестах этого проекта.
 */
@RunWith(RobolectricTestRunner::class)
class RecurrenceReminderSchedulingTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
    }

    private fun alarmManager() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun eventAlarms() = shadowOf(alarmManager()).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name }

    private fun epochMillisOf(dt: LocalDateTime): Long =
        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun item(
        id: String,
        startDate: LocalDate,
        startTime: String,
        recurrenceRule: String?,
        reminder: ReminderRule,
        type: CalendarItemType = CalendarItemType.TASK,
        isAllDay: Boolean = false,
    ) = CalendarItem(
        id = id, title = "t", type = type,
        startDate = startDate.toString(), startTime = startTime.takeIf { !isAllDay }, isAllDay = isAllDay,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
        recurrenceRule = recurrenceRule, reminders = listOf(reminder),
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    // ─── WEEKLY A: this week's occurrence still ahead — no advance needed ───
    // anchor = weekday of TOMORROW (4 weeks back): nextOccurrenceOnOrAfter(today)
    // lands exactly on tomorrow, whose reminder-time is unambiguously future
    // regardless of the real current time-of-day.

    @Test
    fun weeklyA_stillAheadThisCycle_noAdvanceNeeded_alarmOnExpectedDate() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val anchor = tomorrow.minusWeeks(4)
        val rule = ReminderRule(id = "r1", calendarItemId = "wa", reminderType = ReminderType.AT_TIME)
        val cal = item("wa", anchor, "10:00", RecurrenceMode.WEEKLY.toRRule(), rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals("ровно один будильник", 1, alarms.size)
        val expected = epochMillisOf(LocalDateTime.of(tomorrow, LocalTime.of(10, 0)))
        assertEquals("будильник — на завтрашнее occurrence 10:00", expected, alarms.single().triggerAtTime)
    }

    // ─── WEEKLY B: this cycle's reminder ALREADY passed — must advance via
    // calculator to the NEXT occurrence, not just drop the alarm ───
    // anchor = today's own weekday (4 weeks back): occurrence(today) = today.
    // offset BEFORE 1 day => reminder-time for today's occurrence = yesterday
    // 10:00, which is unconditionally in the past (yesterday's date < today's
    // date always, regardless of current time-of-day) — this is EXACTLY the
    // "Tuesday 11:00, Wednesday reminder already gone" case from the spec.

    // CALENDAR RUNTIME & ALARM LIFECYCLE PACK: политика «пропущенный BEFORE» изменена — если само occurrence ещё впереди, оно
    // НЕ пропускается (fallback на время occurrence, см. ReminderPostponeFallbackTest). К СЛЕДУЮЩЕМУ occurrence планировщик
    // переходит, только когда прошло и само эффективное время. Поэтому фикстура здесь — occurrence СЕГОДНЯ в 00:00 (время
    // occurrence уже позади независимо от часа прогона): и его BEFORE-триггер, и оно само в прошлом → следующее occurrence.

    @Test
    fun weeklyB_thisOccurrenceItselfAlreadyPassed_advancesToNextOccurrence_viaCalculator() {
        val today = LocalDate.now()
        val anchor = today.minusWeeks(4) // same weekday as today
        val rule = ReminderRule(
            id = "r2", calendarItemId = "wb", reminderType = ReminderType.BEFORE,
            offsetValue = 1, offsetUnit = ReminderOffsetUnit.DAYS
        )
        val cal = item("wb", anchor, "00:00", RecurrenceMode.WEEKLY.toRRule(), rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals("ровно один будильник — не отброшен как 'уже прошедший'", 1, alarms.size)
        // today's occurrence (00:00) is already behind us → next occurrence is today+7;
        // its BEFORE-1-day reminder is today+6 at 00:00.
        val expectedOccurrence = today.plusDays(7)
        val expected = epochMillisOf(LocalDateTime.of(expectedOccurrence.minusDays(1), LocalTime.of(0, 0)))
        assertEquals("будильник — на СЛЕДУЮЩЕЕ occurrence (today+6 00:00), не проигнорирован",
            expected, alarms.single().triggerAtTime)
        assertTrue("isReminderScheduled согласован с реально поставленным будильником",
            NotificationScheduler.isReminderScheduled(cal, rule))
    }

    // ─── DAILY: anchor полностью в прошлом — старый код НЕ ставил ни одного
    // будильника здесь вообще (единственная дата, без advance-логики для
    // DAILY); новый код обязан найти будущее occurrence ───

    @Test
    fun daily_fullyPastAnchor_stillSchedulesGoingForward_oldCodeScheduledNothingHere() {
        val today = LocalDate.now()
        val anchor = today.minusDays(10)
        val rule = ReminderRule(id = "r3", calendarItemId = "d1", reminderType = ReminderType.AT_TIME)
        val cal = item("d1", anchor, "10:00", RecurrenceMode.DAILY.toRRule(), rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals("DAILY с 10-дневным anchor'ом обязан получить будильник (на старом коде — 0)", 1, alarms.size)
        val now = System.currentTimeMillis()
        val trigger = alarms.single().triggerAtTime
        assertTrue("будильник обязан быть в будущем", trigger >= now)
        assertTrue("будильник обязан быть в разумных пределах (сегодня/завтра), не далёкий скан",
            trigger <= now + 36L * 60 * 60 * 1000)
        val scheduledTime = java.time.Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault()).toLocalTime()
        assertEquals("время суток сохранено (10:00), не искажено advance-логикой",
            LocalTime.of(10, 0), scheduledTime)
    }

    // ─── MONTHLY: anchor на 31-е число, десятилетия назад — сверяем с
    // RecurrenceCalculator как oracle (без дублирования его арифметики здесь) ───

    @Test
    fun monthly_decadesOldDay31Anchor_matchesCalculatorOracle_noDrift() {
        val anchor = LocalDate.of(2000, 1, 31)
        val rule = ReminderRule(id = "r4", calendarItemId = "m1", reminderType = ReminderType.AT_TIME)
        val cal = item("m1", anchor, "09:30", RecurrenceMode.MONTHLY.toRRule(), rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals("ровно один будильник", 1, alarms.size)
        val oracleOccurrence = RecurrenceCalculator.nextOccurrenceOnOrAfter(anchor, RecurrenceMode.MONTHLY, LocalDate.now())
            ?: throw AssertionError("oracle обязан вернуть occurrence для MONTHLY")
        val expected = epochMillisOf(LocalDateTime.of(oracleOccurrence, LocalTime.of(9, 30)))
        assertEquals("scheduler обязан совпасть с canonical calculator — без собственного дрифта",
            expected, alarms.single().triggerAtTime)
    }

    // ─── YEARLY Feb29: тот самый исторический баг (§ отчёт по recurrence-аудиту) ───

    @Test
    fun yearly_feb29Anchor_matchesCalculatorOracle_noDrift() {
        val anchor = LocalDate.of(2000, 2, 29) // 2000 was a leap year
        val rule = ReminderRule(id = "r5", calendarItemId = "y1", reminderType = ReminderType.AT_TIME)
        val cal = item("y1", anchor, "08:00", RecurrenceMode.YEARLY.toRRule(), rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals("ровно один будильник", 1, alarms.size)
        val oracleOccurrence = RecurrenceCalculator.nextOccurrenceOnOrAfter(anchor, RecurrenceMode.YEARLY, LocalDate.now())
            ?: throw AssertionError("oracle обязан вернуть occurrence для YEARLY")
        val expected = epochMillisOf(LocalDateTime.of(oracleOccurrence, LocalTime.of(8, 0)))
        assertEquals("scheduler обязан совпасть с canonical calculator (единая семантика Feb29 вместо старой дублированной)",
            expected, alarms.single().triggerAtTime)
    }

    // ─── NONE: единственный reminder-time уже в прошлом — НЕ искать
    // несуществующее следующее occurrence, просто ноль будильников ───

    @Test
    fun none_singleReminderAlreadyPast_schedulesNothing_doesNotSearchForNonexistentNext() {
        val anchor = LocalDate.now().minusDays(5)
        val rule = ReminderRule(id = "r6", calendarItemId = "n1", reminderType = ReminderType.AT_TIME)
        val cal = item("n1", anchor, "10:00", recurrenceRule = null, reminder = rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        assertEquals("NONE с прошедшим reminder-time не должен получить будильник", 0, eventAlarms().size)
        assertTrue("isReminderScheduled согласован", !NotificationScheduler.isReminderScheduled(cal, rule))
    }

    @Test
    fun none_futureReminder_schedulesNormally() {
        val anchor = LocalDate.now().plusDays(5)
        val rule = ReminderRule(id = "r7", calendarItemId = "n2", reminderType = ReminderType.AT_TIME)
        val cal = item("n2", anchor, "10:00", recurrenceRule = null, reminder = rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals(1, alarms.size)
        val expected = epochMillisOf(LocalDateTime.of(anchor, LocalTime.of(10, 0)))
        assertEquals(expected, alarms.single().triggerAtTime)
    }

    // ─── CUSTOM_DATE_TIME: абсолютная точка времени, НЕ затрагивается
    // recurrence, даже если событие YEARLY (см. комментарий у
    // calculateNotificationTime про намеренное отличие от старого кода) ───

    @Test
    fun customDateTime_onYearlyRecurringItem_usesAbsoluteTime_unaffectedByRecurrence() {
        val exact = LocalDateTime.now().plusDays(3).withSecond(0).withNano(0)
        val rule = ReminderRule(
            id = "r8", calendarItemId = "c1", reminderType = ReminderType.CUSTOM_DATE_TIME,
            exactDateTime = exact.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        // startDate специально СТАРЫЙ и YEARLY — если бы recurrence всё ещё
        // влиял на CUSTOM_DATE_TIME (как в старом коде), это исказило бы результат.
        val cal = item("c1", LocalDate.of(2000, 1, 1), "10:00", RecurrenceMode.YEARLY.toRRule(), rule)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals(1, alarms.size)
        assertEquals("CUSTOM_DATE_TIME — абсолютное время, recurrence не трогает",
            epochMillisOf(exact), alarms.single().triggerAtTime)
    }

    // ─── BACKWARD COMPATIBILITY: legacy BIRTHDAY без recurrenceRule — планировщик
    // обязан трактовать её как YEARLY через effectiveRecurrenceMode(), не NONE ───

    @Test
    fun legacyBirthdayWithoutRecurrenceRule_stillGetsYearlyReminder_backwardCompatibility() {
        val anchor = LocalDate.of(1990, 6, 15)
        val rule = ReminderRule(id = "r9", calendarItemId = "bday1", reminderType = ReminderType.AT_TIME)
        val cal = item("bday1", anchor, "09:00", recurrenceRule = null, reminder = rule,
            type = CalendarItemType.BIRTHDAY, isAllDay = true)

        NotificationScheduler.scheduleReminder(ctx, cal, rule)

        val alarms = eventAlarms()
        assertEquals("legacy ДР без recurrenceRule обязана получить будильник (YEARLY, не NONE)", 1, alarms.size)
        val oracleOccurrence = RecurrenceCalculator.nextOccurrenceOnOrAfter(anchor, RecurrenceMode.YEARLY, LocalDate.now())
            ?: throw AssertionError("oracle обязан вернуть occurrence для YEARLY")
        val expected = epochMillisOf(LocalDateTime.of(oracleOccurrence, LocalTime.of(9, 0)))
        assertEquals("scheduler обязан трактовать legacy ДР как YEARLY (effectiveRecurrenceMode)",
            expected, alarms.single().triggerAtTime)
    }
}
