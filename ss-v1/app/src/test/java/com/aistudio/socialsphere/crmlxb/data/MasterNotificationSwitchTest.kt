package com.aistudio.socialsphere.crmlxb.data

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.ImportanceLevel
import com.aistudio.socialsphere.crmlxb.model.ReminderRule
import com.aistudio.socialsphere.crmlxb.model.ReminderType
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.NotificationHelper
import com.aistudio.socialsphere.crmlxb.utils.NotificationReceiver
import com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
import com.aistudio.socialsphere.crmlxb.utils.StaleContactsReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * LIFECYCLE ГЛАВНОГО ПЕРЕКЛЮЧАТЕЛЯ УВЕДОМЛЕНИЙ (AppSettings.isNotificationsEnabled).
 *
 * INVARIANT:
 *  OFF → новые Calendar alarms не планируются; существующие Calendar alarms и
 *  stale-contact daily alarm отменены; NotificationReceiver не показывает
 *  notification даже при доставке старого PendingIntent (defense in depth,
 *  НЕ полагается только на AlarmManager.cancel()).
 *  ON → актуальные ACTIVE Calendar reminders восстанавливаются из Room (не из
 *  памяти); stale-contact daily check восстанавливается; completed/cancelled/
 *  past events alarms не получают; повторное включение не плодит дубликаты.
 *
 * Тесты вызывают ровно то, что вызывает NotificationSettingsScreen.onCheckedChange
 * (NotificationScheduler.cancelAllUserDataAlarms / reconcileAllUserDataAlarms) —
 * не Compose UI: сама проводка — однострочная лямбда, логика — в этих
 * production-функциях.
 */
@RunWith(RobolectricTestRunner::class)
class MasterNotificationSwitchTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase
    private val contactId = "mns-c1"

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
        AppSettings.remindStaleContacts.value = true
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("loadInitialData() не завершился за таймаут")
        AppStateStore.contacts.clear()
        AppStateStore.calendarItems.clear()
    }

    @After
    fun tearDown() {
        shadowOf(alarmManager()).scheduledAlarms.toList().forEach { alarm ->
            alarm.operation?.let { alarmManager().cancel(it) }
        }
        AppSettings.isNotificationsEnabled.value = true
        db.close()
    }

    // ─── обвязка ───────────────────────────────────────────────

    private fun alarmManager() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ok = check()
        }
        return ok
    }

    private val eventStart: java.time.LocalDateTime =
        java.time.LocalDateTime.now().plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0)

    private fun calendarItem(
        id: String, status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        start: java.time.LocalDateTime = eventStart, reminders: List<ReminderRule>,
    ) = CalendarItem(
        id = id, title = "Событие $id", type = CalendarItemType.CALL,
        startDate = start.toLocalDate().toString(),
        startTime = "%02d:%02d".format(start.hour, start.minute),
        isAllDay = false, status = status, importance = ImportanceLevel.NORMAL,
        reminders = reminders,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun rule(itemId: String, ruleId: String) =
        ReminderRule(id = ruleId, calendarItemId = itemId, reminderType = ReminderType.AT_TIME)

    private fun persistAndScheduleItem(item: CalendarItem) {
        runBlocking { AppStateStore.addCalendarItem(item).await() }
        item.reminders.forEach { r -> NotificationScheduler.scheduleReminder(ctx, item, r) }
    }

    private fun calendarReminderAlarms() = shadowOf(alarmManager()).scheduledAlarms.filter {
        shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name
    }

    private fun staleCheckAlarms() = shadowOf(alarmManager()).scheduledAlarms.filter {
        shadowOf(it.operation).savedIntent.component?.className == StaleContactsReceiver::class.java.name
    }

    /** То же, что NotificationSettingsScreen.onCheckedChange(false): флаг — ДО
     *  побочного эффекта; Room-backed (не in-memory снимок) — работает и из
     *  холодного состояния, когда AppStateStore.calendarItems не загружена. */
    private fun turnOff() {
        AppSettings.isNotificationsEnabled.value = false
        runBlocking { NotificationScheduler.cancelAllUserDataAlarmsFromRoom(ctx, db) }
    }

    /** То же, что NotificationSettingsScreen.onCheckedChange(true): флаг — ДО reconcile. */
    private fun turnOn() {
        AppSettings.isNotificationsEnabled.value = true
        runBlocking { NotificationScheduler.reconcileAllUserDataAlarms(ctx, db) }
    }

    private fun deliver(intent: Intent) {
        val cls = intent.component?.className ?: fail("у intent нет компонента-приёмника: $intent") as Nothing
        val receiver = Class.forName(cls).getDeclaredConstructor().newInstance() as android.content.BroadcastReceiver
        val create = org.robolectric.shadows.ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, android.os.Bundle::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val pending = create.invoke(null, 0, null, null, false) as android.content.BroadcastReceiver.PendingResult
        android.content.BroadcastReceiver::class.java
            .getMethod("setPendingResult", android.content.BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, pending)
        receiver.onReceive(ctx, intent)
        val finished = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBroadcastPendingResult>(pending).future
        pollUntil(5_000) { finished.isDone }
    }

    // ─── A. Существующий Calendar alarm, ON → OFF → alarm отсутствует ───
    //  (критический тест — на текущей реализации master-переключатель не отменял alarms)

    @Test
    fun a_existingCalendarAlarm_toggleOff_alarmGone() {
        persistAndScheduleItem(calendarItem("mns-ev-a", reminders = listOf(rule("mns-ev-a", "mns-r-a"))))
        assertEquals("предусловие: будильник поставлен", 1, calendarReminderAlarms().size)

        turnOff()

        assertEquals("после OFF будильника напоминания быть не должно", 0, calendarReminderAlarms().size)
    }

    // ─── B. Stale daily alarm, ON → OFF → отсутствует ───

    @Test
    fun b_staleDailyAlarm_toggleOff_gone() {
        NotificationScheduler.scheduleStaleCheck(ctx)
        assertEquals("предусловие: ежедневный будильник стоит", 1, staleCheckAlarms().size)

        turnOff()

        assertEquals("после OFF ежедневного будильника нет", 0, staleCheckAlarms().size)
    }

    // ─── C. Старый Calendar PendingIntent доставлен ПОСЛЕ OFF → notification не показывается ───
    //  (критический тест — defense in depth, второй уровень независимо от физической отмены)

    @Test
    fun c_staleDeliveredPendingIntentAfterOff_doesNotShowNotification() {
        persist(contact())
        val itemId = "mns-ev-c"
        val notificationId = 777001
        val item = calendarItem(itemId, reminders = listOf(rule(itemId, "mns-r-c")))
        runBlocking { AppStateStore.addCalendarItem(item).await() }
        val savedIntent = Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", itemId)
            putExtra("notificationId", notificationId)
        }
        NotificationScheduler.scheduleReminder(ctx, item, item.reminders.single())

        turnOff()
        deliver(savedIntent)

        assertNull("уведомление НЕ должно быть показано при выключенном master-флаге",
            shadowOf(nm()).getNotification(notificationId))
    }

    // ─── D. OFF → создать/изменить событие с reminder → alarm не появляется ───

    @Test
    fun d_off_createEventWithReminder_noAlarmAppears() {
        AppSettings.isNotificationsEnabled.value = false
        val item = calendarItem("mns-ev-d", reminders = listOf(rule("mns-ev-d", "mns-r-d")))

        persistAndScheduleItem(item)

        assertEquals("при выключенных уведомлениях новый alarm не появляется", 0, calendarReminderAlarms().size)
    }

    // ─── E. OFF → ON, Room содержит ACTIVE future event → alarm восстановлен ───
    //  (критический тест — на текущей реализации OFF→ON вообще не восстанавливал Calendar reminders)

    @Test
    fun e_offToOn_activeFutureEventInRoom_alarmRestored() {
        AppSettings.isNotificationsEnabled.value = false
        val item = calendarItem("mns-ev-e", reminders = listOf(rule("mns-ev-e", "mns-r-e")))
        runBlocking { AppStateStore.addCalendarItem(item).await() }
        assertEquals("предусловие: пока OFF, alarm не создан", 0, calendarReminderAlarms().size)

        turnOn()

        assertEquals("после ON alarm восстановлен из Room", 1, calendarReminderAlarms().size)
    }

    // ─── F. Room содержит COMPLETED/CANCELLED/past события, OFF → ON → alarms для них не появляются ───

    @Test
    fun f_offToOn_nonActiveAndPastEvents_noAlarmsForThem() {
        AppSettings.isNotificationsEnabled.value = false
        val completed = calendarItem("mns-ev-f1", status = CalendarItemStatus.COMPLETED, reminders = listOf(rule("mns-ev-f1", "mns-r-f1")))
        val cancelled = calendarItem("mns-ev-f2", status = CalendarItemStatus.CANCELLED, reminders = listOf(rule("mns-ev-f2", "mns-r-f2")))
        val past = calendarItem("mns-ev-f3", start = java.time.LocalDateTime.now().minusDays(5), reminders = listOf(rule("mns-ev-f3", "mns-r-f3")))
        listOf(completed, cancelled, past).forEach { runBlocking { AppStateStore.addCalendarItem(it).await() } }

        turnOn()

        assertEquals("COMPLETED/CANCELLED/past не получают alarms", 0, calendarReminderAlarms().size)
    }

    // ─── G. OFF → ON восстанавливает stale daily check ───

    @Test
    fun g_offToOn_restoresStaleDailyCheck() {
        AppSettings.isNotificationsEnabled.value = false
        NotificationScheduler.scheduleStaleCheck(ctx) // при OFF settings() сам снимает
        assertEquals("предусловие: пока OFF, ежедневного будильника нет", 0, staleCheckAlarms().size)

        turnOn()

        assertEquals("после ON ежедневный будильник восстановлен", 1, staleCheckAlarms().size)
    }

    // ─── H. OFF → ON → повторно ON → alarm count не растёт ───

    @Test
    fun h_offToOnToOnAgain_alarmCountDoesNotGrow() {
        AppSettings.isNotificationsEnabled.value = false
        val item = calendarItem("mns-ev-h", reminders = listOf(rule("mns-ev-h", "mns-r-h")))
        runBlocking { AppStateStore.addCalendarItem(item).await() }

        turnOn()
        assertEquals(1, calendarReminderAlarms().size)
        assertEquals(1, staleCheckAlarms().size)

        turnOn()
        assertEquals("повторный ON не плодит дубликаты calendar alarms", 1, calendarReminderAlarms().size)
        assertEquals("повторный ON не плодит дубликаты stale alarm", 1, staleCheckAlarms().size)
    }

    // ─── I. ON → OFF → ON несколько раз → scheduler остаётся идемпотентным ───

    @Test
    fun i_onOffOnRepeatedMultipleTimes_schedulerStaysIdempotent() {
        val item = calendarItem("mns-ev-i", reminders = listOf(rule("mns-ev-i", "mns-r-i")))
        persistAndScheduleItem(item)

        repeat(3) {
            turnOff()
            assertEquals(0, calendarReminderAlarms().size)
            assertEquals(0, staleCheckAlarms().size)
            turnOn()
            assertEquals("после каждого цикла ровно 1 calendar alarm", 1, calendarReminderAlarms().size)
            assertEquals("после каждого цикла ровно 1 stale alarm", 1, staleCheckAlarms().size)
        }
    }

    // ─── J. Cold state: данные только в Room, память пустая. OFF → ON → reminders корректно восстанавливаются ───

    @Test
    fun j_coldState_dataOnlyInRoom_memoryEmpty_offToOn_remindersRestoredCorrectly() {
        val item = calendarItem("mns-ev-j", reminders = listOf(rule("mns-ev-j", "mns-r-j")))
        runBlocking { AppStateStore.addCalendarItem(item).await() }
        AppSettings.isNotificationsEnabled.value = false
        // «Холодное» состояние: память пуста, данные — только в Room.
        AppStateStore.calendarItems.clear()
        assertTrue("предусловие cold: память пуста", AppStateStore.calendarItems.isEmpty())
        assertEquals("предусловие: Room по-прежнему содержит событие",
            1, runBlocking { db.calendarDao().getAllCalendarItems() }.size)

        turnOn()

        assertEquals("reconcile читает Room напрямую — alarm восстановлен даже при пустой памяти",
            1, calendarReminderAlarms().size)
    }

    // ═════════════════════════════════════════════════════════════
    //  ДВА ОСТАВШИХСЯ НАРУШЕНИЯ master notifications INVARIANT
    //  1) scheduleSnooze() не проверял master-флаг — уже показанное
    //     уведомление можно было отложить ПОСЛЕ выключения и получить новый
    //     alarm в обход единственного canonical барьера.
    //  2) OFF cleanup читал AppStateStore.calendarItems (in-memory) — в
    //     холодном состоянии (память не загружена/очищена) не находил, что
    //     отменять, хотя Room всё ещё содержит событие с активным alarm.
    // ═════════════════════════════════════════════════════════════

    // ─── A. Snooze bypass: master OFF → Snooze НЕ создаёт новый alarm ───
    //  (критический тест — на текущей реализации scheduleSnooze() не проверяет master flag)

    @Test
    fun snoozeA_masterOff_realSnoozeAction_noNewAlarm() {
        val itemId = "mns-snooze-a"
        val ruleId = "mns-snooze-r-a"
        val notificationId = (itemId + "|" + ruleId).hashCode()
        val item = calendarItem(itemId, reminders = listOf(rule(itemId, ruleId)))
        persistAndScheduleItem(item)
        assertEquals("предусловие: исходный alarm поставлен", 1, calendarReminderAlarms().size)

        // «Notification существует» — настоящий показ, тот же notificationId,
        // что и у alarm (getNotificationId = calendarItemId|reminderRuleId).
        NotificationHelper.createNotificationChannel(ctx)
        NotificationHelper.showNotification(
            context = ctx, notificationId = notificationId,
            title = "Позвонить", content = "Событие", targetCalendarItemId = itemId,
            channelId = NotificationHelper.CHANNEL_ID, kind = NotificationHelper.Kind.CALENDAR_REMINDER,
            calendarItemType = CalendarItemType.CALL, calendarItemCompletable = true,
        )
        assertNotNull("предусловие: уведомление показано", shadowOf(nm()).getNotification(notificationId))

        turnOff()
        assertEquals("после OFF исходный alarm отменён", 0, calendarReminderAlarms().size)

        // Настоящий Snooze action — тот же intent, что кладёт NotificationHelper.receiverPi.
        deliver(Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("action", NotificationHelper.ACTION_SNOOZE)
            putExtra("notificationId", notificationId)
            putExtra("calendarItemId", itemId)
            putExtra("title", "Позвонить")
            putExtra("content", "Событие")
        })

        assertEquals("после OFF Snooze не должен создавать новый alarm",
            0, calendarReminderAlarms().size)
    }

    // ─── B. Master ON: Snooze работает как раньше → после реального цикла «сработал → отложен» ровно один alarm ───
    //  ALARM LIFECYCLE PACK: identity напоминания и identity snooze теперь РАЗНЫЕ (FRONT 12/14 — коллизия
    //  requestCode не должна сливать разные логические будильники в один PendingIntent). Раньше они СЛУЧАЙНО
    //  совпадали (оба — голый Intent(NotificationReceiver) с тем же requestCode), поэтому Snooze молча подменял
    //  ещё не сработавший alarm напоминания в модели теста. В реальности это не «подмена одного и того же alarm»:
    //  Android сам снимает одноразовый alarm со своей очереди, когда он СРАБОТАЛ (до кнопки «Отложить» дело
    //  доходит только после срабатывания) — ShadowAlarmManager это автоматически не делает, поэтому тест сам
    //  моделирует срабатывание (доставка intent'а) и снятие сработавшего alarm, как это делает система.
    @Test
    fun snoozeB_masterOn_snoozeWorksAsBefore_exactlyOneAlarm() {
        val itemId = "mns-snooze-b"
        val ruleId = "mns-snooze-r-b"
        val notificationId = (itemId + "|" + ruleId).hashCode()
        val item = calendarItem(itemId, reminders = listOf(rule(itemId, ruleId)))
        persistAndScheduleItem(item)
        val fired = calendarReminderAlarms().single()
        assertEquals(1, calendarReminderAlarms().size)

        deliver(shadowOf(fired.operation).savedIntent)     // alarm сработал → показано уведомление
        fired.operation?.let { alarmManager().cancel(it) }  // Android снимает сработавший одноразовый alarm со своей очереди

        deliver(Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("action", NotificationHelper.ACTION_SNOOZE)
            putExtra("notificationId", notificationId)
            putExtra("calendarItemId", itemId)
            putExtra("title", "Позвонить")
            putExtra("content", "Событие")
        })

        assertEquals("после реального цикла срабатывания и Snooze — ровно один alarm (сам snooze)",
            1, calendarReminderAlarms().size)
        assertEquals("это именно snooze-identity, не случайно совпавший alarm напоминания", "snooze",
            shadowOf(calendarReminderAlarms().single().operation).savedIntent.data?.authority)
    }

    // ─── C. COLD OFF: Room содержит событие+alarm, память пуста → OFF всё равно отменяет ───
    //  (критический тест — на текущей реализации OFF читал AppStateStore.calendarItems,
    //  пустую в холодном состоянии, и не отменял alarm)

    @Test
    fun coldOffC_roomBackedCleanup_memoryEmpty_alarmGone() {
        val itemId = "mns-cold-c"
        val item = calendarItem(itemId, reminders = listOf(rule(itemId, "mns-cold-r-c")))
        persistAndScheduleItem(item)
        assertEquals("предусловие: alarm поставлен", 1, calendarReminderAlarms().size)

        // Холодное состояние: память очищена, данные — только в Room.
        AppStateStore.calendarItems.clear()
        assertTrue("предусловие cold: память пуста", AppStateStore.calendarItems.isEmpty())
        assertEquals("предусловие: Room по-прежнему содержит событие",
            1, runBlocking { db.calendarDao().getAllCalendarItems() }.size)

        turnOff()

        assertEquals("Room-backed OFF cleanup отменяет alarm даже при пустой памяти",
            0, calendarReminderAlarms().size)
    }

    // ─── D. Несколько Room events/rules + пустая memory → OFF снимает все их alarms и stale daily alarm ───

    @Test
    fun coldOffD_multipleEventsRulesEmptyMemory_offRemovesAllAndStale() {
        val item1 = calendarItem("mns-cold-d1", reminders = listOf(rule("mns-cold-d1", "mns-cold-r-d1a"), rule("mns-cold-d1", "mns-cold-r-d1b")))
        val item2 = calendarItem("mns-cold-d2", reminders = listOf(rule("mns-cold-d2", "mns-cold-r-d2")))
        persistAndScheduleItem(item1)
        persistAndScheduleItem(item2)
        NotificationScheduler.scheduleStaleCheck(ctx)
        assertEquals("предусловие: 3 calendar alarms", 3, calendarReminderAlarms().size)
        assertEquals("предусловие: stale alarm стоит", 1, staleCheckAlarms().size)

        AppStateStore.calendarItems.clear()

        turnOff()

        assertEquals("все calendar alarms сняты", 0, calendarReminderAlarms().size)
        assertEquals("stale alarm тоже снят", 0, staleCheckAlarms().size)
    }

    // ─── E. OFF (cold) → старый Calendar PendingIntent доставлен → notification по-прежнему не показывается ───

    @Test
    fun coldOffE_staleDeliveredPendingIntentAfterColdOff_notificationNotShown() {
        val itemId = "mns-cold-e"
        val notificationId = 888005
        val item = calendarItem(itemId, reminders = listOf(rule(itemId, "mns-cold-r-e")))
        val savedIntent = Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", itemId)
            putExtra("notificationId", notificationId)
        }
        persistAndScheduleItem(item)
        assertEquals(1, calendarReminderAlarms().size)

        AppStateStore.calendarItems.clear()
        turnOff()

        deliver(savedIntent)

        assertNull("уведомление НЕ должно быть показано — master OFF, cleanup был Room-backed",
            shadowOf(nm()).getNotification(notificationId))
    }

    // ─── F. OFF → ON после cold cleanup → alarms корректно восстанавливаются из Room ───

    @Test
    fun coldOffF_offThenOnAfterColdCleanup_alarmsRestoredFromRoom() {
        val item = calendarItem("mns-cold-f", reminders = listOf(rule("mns-cold-f", "mns-cold-r-f")))
        persistAndScheduleItem(item)
        AppStateStore.calendarItems.clear()

        turnOff()
        assertEquals(0, calendarReminderAlarms().size)

        turnOn()

        assertEquals("после ON alarm корректно восстановлен из Room", 1, calendarReminderAlarms().size)
    }

    // ─── обвязка для C: контакт (не критично для сценария, для полноты) ───

    private fun contact() = com.aistudio.socialsphere.crmlxb.model.Contact(
        id = contactId, firstName = "Ivan", lastName = "Tester", photoUri = null,
        relationshipType = com.aistudio.socialsphere.crmlxb.model.RelationshipType.FRIEND,
        connectionLevel = com.aistudio.socialsphere.crmlxb.model.ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL,
        socialRole = com.aistudio.socialsphere.crmlxb.model.SocialRole.REGULAR,
        communicationRhythm = com.aistudio.socialsphere.crmlxb.model.CommunicationRhythm.NOT_TRACKED,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun persist(c: com.aistudio.socialsphere.crmlxb.model.Contact) {
        runBlocking { AppStateStore.addContact(c).await() }
    }
}
