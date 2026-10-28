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
 * ПОЛНОЕ УДАЛЕНИЕ ДАННЫХ (PrivacySettingsScreen → AppStateStore.wipeAllData())
 * относительно Calendar/Reminder scheduling.
 *
 * INVARIANT: после успешного wipeAllData() не должно существовать
 * scheduled/background state, способного позже использовать или показывать
 * удалённые пользовательские данные. Room очищен ⇒ связанные alarms тоже
 * отсутствуют — как будильники напоминаний КАЖДОГО CalendarItem, так и
 * ежедневный будильник «пора связаться» (StaleContactsReceiver).
 *
 * DEFENSE IN DEPTH (Batch 7.2, не создаётся здесь заново, а только
 * проверяется как второй уровень): даже если физическая отмена почему-то не
 * сработала и старый PendingIntent доставлен ПОСЛЕ wipe, NotificationReceiver
 * читает Room, не находит CalendarItem и не показывает уведомление — тест E.
 */
@RunWith(RobolectricTestRunner::class)
class WipeAllDataAlarmCleanupTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase

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

    private fun calendarItem(id: String, reminders: List<ReminderRule>) = CalendarItem(
        id = id, title = "Событие $id", type = CalendarItemType.CALL,
        startDate = eventStart.toLocalDate().toString(),
        startTime = "%02d:%02d".format(eventStart.hour, eventStart.minute),
        isAllDay = false, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
        reminders = reminders,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun rule(itemId: String, ruleId: String) =
        ReminderRule(id = ruleId, calendarItemId = itemId, reminderType = ReminderType.AT_TIME)

    /** Персистит событие через настоящий production-путь (addCalendarItem) и
     *  ставит будильники его напоминаний через настоящий scheduleReminder —
     *  ровно то, что делает редактор при сохранении. */
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

    /** Синхронный wipeAllData(): дожидается onDone() через главный Looper —
     *  enqueueWrite/withContext(Main) внутри требуют его прокрутки. */
    private fun wipeSync(): Boolean {
        var result: Boolean? = null
        AppStateStore.wipeAllData { ok -> result = ok }
        pollUntil(5_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); result != null }
        return result ?: fail("wipeAllData() не завершился за таймаут") as Nothing
    }

    /**
     * Доставка broadcast'а приёмнику так, как это делает система: новый
     * экземпляр класса из intent.component и настоящий PendingResult (без него
     * goAsync() не работает) — тот же паттерн, что и в
     * NotificationActionSemanticsTest (Robolectric не регистрирует приёмники
     * манифеста без intent-filter, sendBroadcast() до них не доходит).
     */
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

    // ─── A. Calendar alarm exists → wipeAllData() → alarm отсутствует ───
    //  (критический тест — на старой реализации wipeAllData() вообще не
    //  трогал AlarmManager: Room пуста, будильник остаётся)

    @Test
    fun a_calendarAlarmExists_wipeAllData_alarmGone() {
        val item = calendarItem("wipe-ev-a", listOf(rule("wipe-ev-a", "wipe-r-a")))
        persistAndScheduleItem(item)
        assertEquals("предусловие: будильник поставлен", 1, calendarReminderAlarms().size)

        assertTrue("wipe должен завершиться успешно", wipeSync())

        assertEquals("после wipe будильника напоминания быть не должно", 0, calendarReminderAlarms().size)
    }

    // ─── B. Несколько CalendarItems × несколько reminder rules → 0 alarms ───
    //  (критический тест — та же причина, что и A)

    @Test
    fun b_multipleItemsMultipleRules_wipeAllData_zeroAlarms() {
        val item1 = calendarItem("wipe-ev-b1", listOf(rule("wipe-ev-b1", "wipe-r-b1a"), rule("wipe-ev-b1", "wipe-r-b1b")))
        val item2 = calendarItem("wipe-ev-b2", listOf(rule("wipe-ev-b2", "wipe-r-b2a")))
        val item3 = calendarItem("wipe-ev-b3", listOf(rule("wipe-ev-b3", "wipe-r-b3a"), rule("wipe-ev-b3", "wipe-r-b3b")))
        persistAndScheduleItem(item1)
        persistAndScheduleItem(item2)
        persistAndScheduleItem(item3)
        assertEquals("предусловие: 5 будильников поставлены", 5, calendarReminderAlarms().size)

        assertTrue(wipeSync())

        assertEquals("после wipe ни одного будильника напоминаний", 0, calendarReminderAlarms().size)
    }

    // ─── C. Stale-contact daily alarm существует → после wipe отсутствует ───

    @Test
    fun c_staleContactAlarmExists_wipeAllData_gone() {
        NotificationScheduler.scheduleStaleCheck(ctx)
        assertEquals("предусловие: ежедневный будильник стоит", 1, staleCheckAlarms().size)

        assertTrue(wipeSync())

        assertEquals("после wipe ежедневного будильника нет", 0, staleCheckAlarms().size)
    }

    // ─── D. Room после wipe действительно пуст ───

    @Test
    fun d_room_actuallyEmptyAfterWipe() {
        persistAndScheduleItem(calendarItem("wipe-ev-d", listOf(rule("wipe-ev-d", "wipe-r-d"))))
        NotificationScheduler.scheduleStaleCheck(ctx)

        assertTrue(wipeSync())

        val itemsInDb = runBlocking { db.calendarDao().getAllCalendarItems() }
        val rulesInDb = runBlocking { db.calendarDao().getReminderRules() }
        assertTrue("Room: calendar_items пуст", itemsInDb.isEmpty())
        assertTrue("Room: reminder_rules пуст", rulesInDb.isEmpty())
        assertTrue("память: calendarItems пуста", AppStateStore.calendarItems.isEmpty())
    }

    // ─── E. Старый Calendar PendingIntent доставлен ПОСЛЕ wipe → notification НЕ показывается ───
    //  Второй уровень defense in depth (Batch 7.2): даже если бы физическая
    //  отмена не сработала, receiver сам не покажет уведомление по удалённым данным.

    @Test
    fun e_staleDeliveredPendingIntentAfterWipe_doesNotShowNotification() {
        val itemId = "wipe-ev-e"
        val notificationId = 424242
        val item = calendarItem(itemId, listOf(rule(itemId, "wipe-r-e")))
        runBlocking { AppStateStore.addCalendarItem(item).await() }
        // Тот же intent, что кладёт scheduleReminder — сохраняем ДО wipe.
        val savedIntent = Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", itemId)
            putExtra("notificationId", notificationId)
        }
        NotificationScheduler.scheduleReminder(ctx, item, item.reminders.single())
        assertEquals("предусловие: будильник поставлен", 1, calendarReminderAlarms().size)

        assertTrue(wipeSync())

        deliver(savedIntent)

        assertNull("уведомление НЕ должно быть показано — события в Room больше нет",
            shadowOf(nm()).getNotification(notificationId))
    }

    // ─── F. Повторный wipeAllData() → безопасен и идемпотентен, alarms по-прежнему 0 ───

    @Test
    fun f_repeatedWipeAllData_idempotentAndSafe_alarmsStayZero() {
        persistAndScheduleItem(calendarItem("wipe-ev-f", listOf(rule("wipe-ev-f", "wipe-r-f"))))
        NotificationScheduler.scheduleStaleCheck(ctx)

        assertTrue("первый wipe успешен", wipeSync())
        assertEquals(0, calendarReminderAlarms().size)
        assertEquals(0, staleCheckAlarms().size)

        assertTrue("повторный wipe тоже успешен", wipeSync())
        assertEquals("calendar alarms по-прежнему 0", 0, calendarReminderAlarms().size)
        assertEquals("stale alarm по-прежнему 0", 0, staleCheckAlarms().size)
        assertTrue("Room по-прежнему пуст", runBlocking { db.calendarDao().getAllCalendarItems() }.isEmpty())
    }

    // ─── G. Wipe при отсутствии alarms → не падает ───

    @Test
    fun g_wipeWithNoAlarms_doesNotCrash() {
        assertEquals("предусловие: будильников нет", 0, calendarReminderAlarms().size + staleCheckAlarms().size)

        assertTrue("wipe без единого будильника всё равно успешен", wipeSync())

        assertEquals(0, calendarReminderAlarms().size)
        assertEquals(0, staleCheckAlarms().size)
    }

    // ═════════════════════════════════════════════════════════════
    //  Уже ПОКАЗАННЫЕ notifications (не только scheduled alarms) — тот же
    //  INVARIANT: после успешного wipe ни одно SocialSphere-уведомление не
    //  должно продолжать показывать удалённые данные. Canonical cleanup path
    //  (wipeAllData()) теперь вызывает и NotificationHelper.cancelAll()
    //  (системный NotificationManager.cancelAll() — без ручного перечисления
    //  notificationId), в том же best-effort try/catch, что и отмена alarms.
    // ═════════════════════════════════════════════════════════════

    private fun notificationCount() = shadowOf(nm()).size()

    // ─── A. Обычное Calendar notification с пользовательским текстом → после wipe отсутствует ───
    //  (критический тест — на текущей реализации wipeAllData() уведомление переживает wipe)

    @Test
    fun a2_shownCalendarNotification_wipeAllData_notificationGone() {
        NotificationHelper.createNotificationChannel(ctx)
        val notificationId = 555001
        NotificationHelper.showNotification(
            context = ctx, notificationId = notificationId,
            title = "Позвонить Ивану Петрову", content = "Сегодня в 15:00",
            targetCalendarItemId = "wipe-notif-a", phone = "+10000000001",
            channelId = NotificationHelper.CHANNEL_ID, kind = NotificationHelper.Kind.CALENDAR_REMINDER,
            calendarItemType = CalendarItemType.CALL, calendarItemCompletable = true,
        )
        assertNotNull("предусловие: уведомление показано", shadowOf(nm()).getNotification(notificationId))

        assertTrue(wipeSync())

        assertNull("после wipe уведомление не должно отображаться", shadowOf(nm()).getNotification(notificationId))
    }

    // ─── B. Stale-contact notification → после wipe отсутствует ───

    @Test
    fun b2_shownStaleContactNotification_wipeAllData_notificationGone() {
        NotificationHelper.createNotificationChannel(ctx)
        val notificationId = 555002
        NotificationHelper.showNotification(
            context = ctx, notificationId = notificationId,
            title = "Пора связаться", content = "Иван Петров — 30 дней",
            targetCalendarItemId = null, phone = "+10000000001",
            channelId = NotificationHelper.CHANNEL_REACH_OUT, kind = NotificationHelper.Kind.STALE_CONTACT,
            contactId = "wipe-notif-b-contact",
        )
        assertNotNull("предусловие: уведомление показано", shadowOf(nm()).getNotification(notificationId))

        assertTrue(wipeSync())

        assertNull("после wipe уведомление не должно отображаться", shadowOf(nm()).getNotification(notificationId))
    }

    // ─── C. Несколько разных SocialSphere notifications → после wipe active notifications = 0 ───

    @Test
    fun c2_multipleDifferentNotifications_wipeAllData_zeroActiveNotifications() {
        NotificationHelper.createNotificationChannel(ctx)
        NotificationHelper.showNotification(
            context = ctx, notificationId = 555011,
            title = "Позвонить", content = "Событие", targetCalendarItemId = "wipe-notif-c1",
            phone = "+10000000001", channelId = NotificationHelper.CHANNEL_ID,
            kind = NotificationHelper.Kind.CALENDAR_REMINDER, calendarItemType = CalendarItemType.CALL,
        )
        NotificationHelper.showNotification(
            context = ctx, notificationId = 555012,
            title = "Пора связаться", content = "Контакт", targetCalendarItemId = null,
            channelId = NotificationHelper.CHANNEL_REACH_OUT, kind = NotificationHelper.Kind.STALE_CONTACT,
            contactId = "wipe-notif-c2-contact",
        )
        NotificationHelper.showNotification(
            context = ctx, notificationId = 555013,
            title = "День рождения", content = "Сегодня", targetCalendarItemId = "wipe-notif-c3",
            channelId = NotificationHelper.CHANNEL_BIRTHDAY, kind = NotificationHelper.Kind.BIRTHDAY_TODAY,
        )
        assertEquals("предусловие: 3 уведомления показаны", 3, notificationCount())

        assertTrue(wipeSync())

        assertEquals("после wipe активных уведомлений быть не должно", 0, notificationCount())
    }

    // ─── D. Повторный wipe при отсутствии notifications → безопасен ───

    @Test
    fun d2_repeatedWipe_noNotifications_doesNotCrash() {
        assertEquals("предусловие: уведомлений нет", 0, notificationCount())

        assertTrue("первый wipe без уведомлений успешен", wipeSync())
        assertEquals(0, notificationCount())

        assertTrue("повторный wipe без уведомлений тоже успешен", wipeSync())
        assertEquals(0, notificationCount())
    }
}
