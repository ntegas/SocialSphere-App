package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * CALENDAR RUNTIME & ALARM LIFECYCLE PACK — identity PendingIntent будильников (schedule / cancel / snooze).
 *
 * Раньше identity = компонент + requestCode = hash(itemId|ruleId); extras в Intent.filterEquals не участвуют, поэтому два
 * логических напоминания с одинаковым int-хэшем получали ОДИН PendingIntent, и второе молча заменяло первое. Теперь identity
 * включает data-URI `socialsphere-alarm://<kind>/<itemId>/<discriminator>`. Синтетическая коллизия строится на реальном
 * String.hashCode: "Aa" и "BB" имеют одинаковый хэш, поэтому "Aa|r" и "BB|r" — один requestCode при разных напоминаниях.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmIdentityTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
    }

    private fun alarms() = shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name }

    private fun item(id: String, vararg rules: ReminderRule) = CalendarItem(
        id = id, title = "t", type = CalendarItemType.CALL, startDate = LocalDate.now().plusDays(5).toString(), startTime = "10:00",
        isAllDay = false, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, reminders = rules.toList(),
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")

    private fun atEvent(itemId: String, ruleId: String) = ReminderRule(ruleId, itemId, ReminderType.AT_TIME)
    private fun hourBefore(itemId: String, ruleId: String) = ReminderRule(ruleId, itemId, ReminderType.BEFORE, 1, ReminderOffsetUnit.HOURS, null)

    private fun itemOf(alarm: org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm) =
        shadowOf(alarm.operation).savedIntent.getStringExtra("calendarItemId")

    /** Два РАЗНЫХ напоминания с ОДНИМ requestCode (hash-коллизия) — оба будильника живы; отмена одного не трогает другое. */
    @Test
    fun hashCollidingReminders_getDistinctAlarms_andCancelIsExact() {
        assertEquals("предусловие: реальная коллизия requestCode", "Aa|r".hashCode(), "BB|r".hashCode())
        val a = item("Aa", atEvent("Aa", "r"))
        val b = item("BB", atEvent("BB", "r"))

        NotificationScheduler.scheduleReminder(ctx, a, a.reminders.single())
        NotificationScheduler.scheduleReminder(ctx, b, b.reminders.single())

        assertEquals("второе напоминание НЕ заменило первое", setOf("Aa", "BB"), alarms().map { itemOf(it) }.toSet())
        assertEquals(2, alarms().size)

        NotificationScheduler.cancelReminder(ctx, "Aa", "r")
        assertEquals("отмена Aa снимает только Aa", listOf("BB"), alarms().map { itemOf(it) })
    }

    /** schedule X → cancel X → пусто; schedule X,Y (два правила ОДНОГО события) → cancel X → Y остаётся. */
    @Test
    fun cancelReminder_removesExactlyThatLogicalReminder_twoRulesOfOneItem() {
        val single = item("solo", atEvent("solo", "x"))
        NotificationScheduler.scheduleReminder(ctx, single, single.reminders.single())
        assertEquals(1, alarms().size)
        NotificationScheduler.cancelReminder(ctx, "solo", "x")
        assertEquals("X снято", 0, alarms().size)

        val two = item("duo", atEvent("duo", "x"), hourBefore("duo", "y"))
        two.reminders.forEach { NotificationScheduler.scheduleReminder(ctx, two, it) }
        assertEquals("два правила — два будильника", 2, alarms().size)

        NotificationScheduler.cancelReminder(ctx, "duo", "x")

        assertEquals("Y остаётся", 1, alarms().size)
        val remaining = shadowOf(alarms().single().operation).savedIntent
        assertEquals("остался именно Y (BEFORE 1 час), не X",
            ("duo|y").hashCode(), remaining.getIntExtra("notificationId", 0))

        NotificationScheduler.cancelAllRemindersForCalendarItem(ctx, two.reminders)
        assertEquals(0, alarms().size)
    }

    /**
     * Snooze: сохраняет calendarItemId и occurrenceDate; НЕ заменяет основной будильник того же напоминания и будильник другого;
     * повторный snooze того же уведомления заменяет прежний; snooze другого уведомления — отдельный.
     */
    @Test
    fun snooze_hasOwnIdentity_keepsItemAndOccurrence_neverReplacesAnotherReminder() {
        val it1 = item("sn", atEvent("sn", "r1"), atEvent("sn", "r2"))
        val other = item("other", atEvent("other", "r"))
        it1.reminders.forEach { NotificationScheduler.scheduleReminder(ctx, it1, it) }
        NotificationScheduler.scheduleReminder(ctx, other, other.reminders.single())
        assertEquals(3, alarms().size)

        fun src(itemId: String, ruleId: String) = Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", itemId)
            putExtra("notificationId", (itemId + "|" + ruleId).hashCode())
            putExtra("occurrenceDate", "2026-12-16")
        }
        NotificationScheduler.scheduleSnooze(ctx, src("sn", "r1"))

        val snoozes = alarms().filter { shadowOf(it.operation).savedIntent.data?.authority == "snooze" }
        assertEquals(1, snoozes.size)
        val snoozeIntent = shadowOf(snoozes.single().operation).savedIntent
        assertEquals("сохранён calendarItemId", "sn", snoozeIntent.getStringExtra("calendarItemId"))
        assertEquals("сохранён occurrenceDate", "2026-12-16", snoozeIntent.getStringExtra("occurrenceDate"))
        assertEquals("основные будильники всех напоминаний целы (snooze их не заменил)", 3,
            alarms().count { shadowOf(it.operation).savedIntent.data?.authority == "reminder" })

        NotificationScheduler.scheduleSnooze(ctx, src("sn", "r1"))
        assertEquals("повторный snooze того же уведомления заменяет прежний", 1,
            alarms().count { shadowOf(it.operation).savedIntent.data?.authority == "snooze" })

        NotificationScheduler.scheduleSnooze(ctx, src("sn", "r2"))
        assertEquals("snooze ДРУГОГО уведомления — отдельный будильник", 2,
            alarms().count { shadowOf(it.operation).savedIntent.data?.authority == "snooze" })
        assertTrue("snooze не сбрасывается перепостановкой основных напоминаний события",
            run {
                NotificationScheduler.rescheduleReminders(ctx, it1.reminders, it1)
                alarms().count { shadowOf(it.operation).savedIntent.data?.authority == "snooze" } == 2
            })
    }
}
