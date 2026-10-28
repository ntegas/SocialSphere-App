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
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDateTime

/**
 * INVARIANT: будильник напоминания может существовать только для
 * `CalendarItem.status == ACTIVE`. Для COMPLETED/CANCELLED/любого другого
 * статуса количество будущих alarms этого события — 0, и это правило
 * обеспечивает сам NotificationScheduler (scheduleReminder — единственный
 * барьер), а не отдельные экраны.
 *
 * Планирование не трогает Room/AppStateStore/контакты — calculateNotificationTime
 * зависит только от CalendarItem.startDate/startTime и ReminderRule, поэтому тесты
 * зовут ПРОДАКШН scheduleReminder()/rescheduleReminders() напрямую (это и есть
 * функции, которые вызывают CalendarItemEditScreen/CalendarItemDetailScreen) и
 * проверяют реальный ShadowAlarmManager — без надобности поднимать Compose UI.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarReminderStatusLifecycleTest {

    private lateinit var ctx: Context
    private val itemId = "sl-ev1"

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
    }

    @After
    fun tearDown() {
        shadowOf(alarmManager()).scheduledAlarms.toList().forEach { alarm ->
            alarm.operation?.let { alarmManager().cancel(it) }
        }
    }

    // ─── обвязка ───────────────────────────────────────────────

    private fun alarmManager() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val eventStart: LocalDateTime =
        LocalDateTime.now().plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0)

    private fun item(
        status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        start: LocalDateTime = eventStart,
        reminders: List<ReminderRule> = emptyList(),
    ) = CalendarItem(
        id = itemId, title = "Событие", type = CalendarItemType.CALL,
        startDate = start.toLocalDate().toString(),
        startTime = "%02d:%02d".format(start.hour, start.minute),
        isAllDay = false, status = status, importance = ImportanceLevel.NORMAL,
        reminders = reminders,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun atEvent(id: String = "sl-r1") =
        ReminderRule(id = id, calendarItemId = itemId, reminderType = ReminderType.AT_TIME)

    private fun hourBefore(id: String = "sl-r2") =
        ReminderRule(id = id, calendarItemId = itemId, reminderType = ReminderType.BEFORE,
            offsetValue = 1, offsetUnit = ReminderOffsetUnit.HOURS)

    /** Все будильники, поставленные под конкретное правило (по requestCode = notificationId). */
    private fun alarmsForRule(rule: ReminderRule): List<org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm> {
        val nid = notificationId(rule)
        return shadowOf(alarmManager()).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name &&
                shadowOf(it.operation).savedIntent.getIntExtra("notificationId", -1) == nid
        }
    }

    private fun countForRule(rule: ReminderRule): Int = alarmsForRule(rule).size

    /** Тот же алгоритм id, что и в NotificationScheduler (requestCode = notificationId). */
    private fun notificationId(rule: ReminderRule): Int = (itemId + "|" + rule.id).hashCode()

    private fun allEventAlarmsCount(): Int = shadowOf(alarmManager()).scheduledAlarms.count {
        shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name
    }

    // ─── A. ACTIVE + напоминание → будильник есть ───

    @Test
    fun a_activeEventWithReminder_alarmExists() {
        val rule = atEvent()
        NotificationScheduler.scheduleReminder(ctx, item(reminders = listOf(rule)), rule)

        assertEquals("ACTIVE-событие обязано получить будильник", 1, countForRule(rule))
    }

    // ─── B / C. ACTIVE → COMPLETED / CANCELLED через reschedule ───

    @Test
    fun b_activeToCompleted_viaReschedule_oldAlarmGoneNoNewOne() {
        val rule = atEvent()
        NotificationScheduler.scheduleReminder(ctx, item(reminders = listOf(rule)), rule)
        assertEquals("предусловие: будильник поставлен, пока событие ACTIVE", 1, countForRule(rule))

        val completed = item(status = CalendarItemStatus.COMPLETED, reminders = listOf(rule))
        NotificationScheduler.rescheduleReminders(ctx, listOf(rule), completed)

        assertEquals("после ACTIVE→COMPLETED будильника быть не должно", 0, countForRule(rule))
    }

    @Test
    fun c_activeToCancelled_viaReschedule_oldAlarmGoneNoNewOne() {
        val rule = atEvent()
        NotificationScheduler.scheduleReminder(ctx, item(reminders = listOf(rule)), rule)
        assertEquals("предусловие: будильник поставлен, пока событие ACTIVE", 1, countForRule(rule))

        val cancelled = item(status = CalendarItemStatus.CANCELLED, reminders = listOf(rule))
        NotificationScheduler.rescheduleReminders(ctx, listOf(rule), cancelled)

        assertEquals("после ACTIVE→CANCELLED будильника быть не должно", 0, countForRule(rule))
    }

    // ─── D / E. Уже non-ACTIVE событие напрямую в scheduleReminder() ───

    @Test
    fun d_alreadyCompletedEvent_directlyToScheduleReminder_noAlarmCreated() {
        val rule = atEvent()
        NotificationScheduler.scheduleReminder(ctx, item(status = CalendarItemStatus.COMPLETED, reminders = listOf(rule)), rule)

        assertEquals("scheduleReminder() обязан отказать выполненному событию", 0, countForRule(rule))
    }

    @Test
    fun e_alreadyCancelledEvent_directlyToScheduleReminder_noAlarmCreated() {
        val rule = atEvent()
        NotificationScheduler.scheduleReminder(ctx, item(status = CalendarItemStatus.CANCELLED, reminders = listOf(rule)), rule)

        assertEquals("scheduleReminder() обязан отказать отменённому событию", 0, countForRule(rule))
    }

    // ─── F / G. Уже non-ACTIVE событие повторно сохранено/rescheduled ───
    //  (в отличие от B/C — это не момент перехода, а resave уже non-ACTIVE
    //  события; для полноты симулируем ЗАСТРЯВШИЙ будильник, который мог
    //  остаться от старой реализации без барьера, и проверяем, что
    //  rescheduleReminders() его снимает и не восстанавливает).

    private fun seedStaleAlarmBypassingBarrier(rule: ReminderRule) {
        val intent = android.content.Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", itemId)
            putExtra("notificationId", notificationId(rule))
        }
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, notificationId(rule), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager().setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            eventStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            pi
        )
    }

    @Test
    fun f_completedEvent_resavedOrRescheduled_zeroAlarms() {
        val rule = atEvent()
        seedStaleAlarmBypassingBarrier(rule)
        assertEquals("предусловие: застрявший будильник смоделирован", 1, countForRule(rule))

        val completed = item(status = CalendarItemStatus.COMPLETED, reminders = listOf(rule))
        NotificationScheduler.rescheduleReminders(ctx, listOf(rule), completed)

        assertEquals("повторное сохранение COMPLETED-события снимает будильник и не ставит новый", 0, countForRule(rule))
    }

    @Test
    fun g_cancelledEvent_resavedOrRescheduled_zeroAlarms() {
        val rule = atEvent()
        seedStaleAlarmBypassingBarrier(rule)
        assertEquals("предусловие: застрявший будильник смоделирован", 1, countForRule(rule))

        val cancelled = item(status = CalendarItemStatus.CANCELLED, reminders = listOf(rule))
        NotificationScheduler.rescheduleReminders(ctx, listOf(rule), cancelled)

        assertEquals("повторное сохранение CANCELLED-события снимает будильник и не ставит новый", 0, countForRule(rule))
    }

    // ─── H. ACTIVE-событие после смены даты/правила: старый исчез, новый есть ───

    @Test
    fun h_activeEvent_dateAndRuleChanged_oldAlarmGoneExactlyOneNewAlarm() {
        val oldRule = atEvent("sl-old")
        NotificationScheduler.scheduleReminder(ctx, item(reminders = listOf(oldRule)), oldRule)
        assertEquals("предусловие: старый будильник стоит", 1, countForRule(oldRule))

        val newStart = eventStart.plusDays(3)
        val newRule = hourBefore("sl-new")
        val moved = item(start = newStart, reminders = listOf(newRule))
        NotificationScheduler.rescheduleReminders(ctx, listOf(oldRule), moved)

        assertEquals("старое правило больше не имеет будильника", 0, countForRule(oldRule))
        val alarms = alarmsForRule(newRule)
        assertEquals("ровно один новый будильник на новое правило", 1, alarms.size)
        val expectedTrigger = newStart.minusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("время срабатывания — по новой дате/правилу", expectedTrigger, alarms.single().triggerAtTime)
    }

    // ─── I. Идемпотентность: повторный rescheduleReminders() для non-ACTIVE ───

    @Test
    fun i_repeatedRescheduleOfNonActiveEvent_staysZeroAlarms() {
        val rule = atEvent()
        val cancelled = item(status = CalendarItemStatus.CANCELLED, reminders = listOf(rule))

        NotificationScheduler.rescheduleReminders(ctx, emptyList(), cancelled)
        assertEquals("первый вызов — 0 будильников", 0, countForRule(rule))

        NotificationScheduler.rescheduleReminders(ctx, listOf(rule), cancelled)
        assertEquals("повторный вызов — по-прежнему 0 будильников", 0, countForRule(rule))

        NotificationScheduler.rescheduleReminders(ctx, listOf(rule), cancelled)
        assertEquals("третий вызов — по-прежнему 0, без накопления", 0, countForRule(rule))
        assertTrue("и в целом на событие ни одного будильника", allEventAlarmsCount() == 0)
    }
}
