package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.shadows.ShadowLog
import java.time.LocalDateTime

/**
 * INVARIANT: будильник напоминания о событии — только сигнал
 * «calendarItemId + идентичность напоминания → пора проверить событие».
 * Он НЕ источник динамических данных: в момент срабатывания актуальное состояние
 * (событие, его статус, связи, имена и телефоны контактов) читается из Room.
 *
 * Путь настоящий: планирует реальный NotificationScheduler.scheduleReminder,
 * будильник берётся из ShadowAlarmManager (тот самый PendingIntent, что поставила
 * система), доставляется в NotificationReceiver с настоящим PendingResult
 * (goAsync()/finish()), результат читается из показанного уведомления.
 *
 * Память AppStateStore перед доставкой всегда очищается: содержимое обязано
 * собираться из Room, а не из загруженного состояния приложения.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarReminderFireTest {

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context
    private val defaultProvider = AppStateStore.databaseProvider

    private val contactId = "cr-c1"
    private val itemId = "cr-ev1"
    private val oldDate = "2025-01-01"

    @Before
    fun setup() {
        ShadowLog.clear()
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppStateStore.databaseProvider = { db }
        AppStateStore.resetForTests()
        clearMemory()
        awaitOffMain { AppStateStore.ensureLoaded(ctx) }
        clearMemory()
    }

    @After
    fun tearDown() {
        AppStateStore.resetForTests()
        clearMemory()
        AppStateStore.databaseProvider = defaultProvider
        db.close()
    }

    // ─── обвязка ───────────────────────────────────────────────

    private fun clearMemory() {
        AppStateStore.contacts.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.notes.clear()
        AppStateStore.companies.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
    }

    private fun pollUntil(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(20)
        }
        fail("не дождался: $what")
    }

    private fun <T> awaitOffMain(block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch {
            try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        pollUntil("suspend-операция завершилась") { done.isCompleted }
        return runBlocking { done.await() }
    }

    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun alarmManager() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Доставка будильника в приёмник с настоящим PendingResult (goAsync/finish). */
    private fun deliver(intent: Intent) {
        val receiver = NotificationReceiver()
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val pending = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
        BroadcastReceiver::class.java
            .getMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, pending)
        receiver.onReceive(ctx, intent)
        val finished = Shadow.extract<ShadowBroadcastPendingResult>(pending).future
        pollUntil("NotificationReceiver: PendingResult.finish() вызван") { finished.isDone }
    }

    private val eventStart: LocalDateTime =
        LocalDateTime.now().plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0)

    private fun contact(
        phone: String = "+111",
        first: String = "Ivan",
        last: String = "Petrov",
    ) = Contact(
        id = contactId, firstName = first, lastName = last, photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = listOf(ContactPhone("cr-p1", contactId, phone, PhoneType.MOBILE, isPrimary = true)),
        lastContactDate = oldDate,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun item(type: CalendarItemType = CalendarItemType.CALL, linked: Boolean = true) = CalendarItem(
        id = itemId, title = "Звонок", type = type,
        startDate = eventStart.toLocalDate().toString(),
        startTime = "%02d:%02d".format(eventStart.hour, eventStart.minute),
        isAllDay = false, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
        links = if (linked) listOf(CalendarItemLink("cr-l1", itemId, CalendarTargetType.CONTACT, contactId)) else emptyList(),
        reminders = listOf(rule),
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private val rule = ReminderRule(id = "cr-r1", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)

    /** Продакшн-планирование + снятый системой PendingIntent будильника. */
    private fun scheduleAndCaptureAlarm(item: CalendarItem): Intent {
        NotificationScheduler.scheduleReminder(ctx, item, item.reminders.first())
        val alarms = shadowOf(alarmManager()).scheduledAlarms
            .filter { shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name }
        assertEquals("продакшн-планирование обязано поставить ровно один будильник", 1, alarms.size)
        return shadowOf(alarms.single().operation).savedIntent
    }

    private fun seed(item: CalendarItem, contact: Contact?) {
        contact?.let { runBlocking { AppStateStore.addContact(it).await() } }
        runBlocking { AppStateStore.addCalendarItem(item).await() }
    }

    private fun shownNotification(alarm: Intent): Notification? {
        val nid = alarm.getIntExtra("notificationId", 0)
        deliver(alarm)
        return shadowOf(nm()).getNotification(nid)
    }

    /**
     * "Не показано" обязано означать «сработала одна из САНКЦИОНИРОВАННЫХ проверок
     * (событие удалено / не ACTIVE)», а не «resolveCalendarReminder упал и
     * runAsync() тихо проглотил исключение». Обе картины дают одинаковый внешний
     * результат (notification == null), поэтому дополнительно проверяем, что
     * NotificationReceiver ничего не залогировал как ошибку — иначе тест на
     * «правильное непоказывание» остался бы зелёным и при регрессии в саму
     * проверку статуса.
     */
    private fun assertNotShown(alarm: Intent, because: String) {
        val n = shownNotification(alarm)
        val errors = ShadowLog.getLogsForTag("NotificationReceiver")
        assertTrue("$because: не показано, но НЕМОЛЧА — тихое исключение вместо " +
            "санкционированной проверки статуса, лог: ${errors.map { it.msg }}", errors.isEmpty())
        assertNull(because, n)
    }

    private fun requireShown(alarm: Intent): Notification =
        shownNotification(alarm) ?: fail("уведомление не показано") as Nothing

    private fun textOf(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()

    private fun titleOf(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()

    private fun actionTitles(n: Notification) = (n.actions ?: emptyArray()).map { it.title.toString() }

    private fun callNumber(n: Notification): String? {
        val call = (n.actions ?: emptyArray()).firstOrNull { it.title.toString() == ctx.getString(R.string.notif_action_call) }
            ?: return null
        return shadowOf(call.actionIntent).savedIntent.data?.schemeSpecificPart
    }

    private fun lastContactInDb(): String? =
        runBlocking { db.contactDao().getAllContacts() }.firstOrNull { it.id == contactId }?.lastContactDate

    // ─── A. Смена номера после планирования ───

    @Test
    fun a_phoneChangedAfterScheduling_callActionUsesCurrentNumber() {
        seed(item(), contact(phone = "+111"))
        val alarm = scheduleAndCaptureAlarm(item())

        runBlocking {
            AppStateStore.getContact(contactId)!!.let { cur -> AppStateStore.updateContact(cur, cur.copy(
                phones = listOf(ContactPhone("cr-p1", contactId, "+222", PhoneType.MOBILE, isPrimary = true))
            )) }
            AppStateStore.awaitPendingWrites()
        }
        clearMemory() // содержимое обязано прийти из Room, а не из памяти

        val n = requireShown(alarm)
        assertEquals("«Позвонить» обязана набирать актуальный номер", "+222", callNumber(n))
    }

    // ─── B. Смена имени после планирования ───

    @Test
    fun b_nameChangedAfterScheduling_notificationShowsCurrentName() {
        seed(item(), contact(first = "Ivan", last = "Petrov"))
        val alarm = scheduleAndCaptureAlarm(item())

        runBlocking {
            AppStateStore.getContact(contactId)!!.let { cur -> AppStateStore.updateContact(cur, cur.copy(firstName = "Pyotr", lastName = "Novy")) }
            AppStateStore.awaitPendingWrites()
        }
        clearMemory()

        val text = textOf(requireShown(alarm))
        assertTrue("в тексте обязано быть актуальное имя, есть «$text»", text.contains("Pyotr Novy"))
        assertFalse("старого имени быть не должно, есть «$text»", text.contains("Ivan Petrov"))
    }

    // ─── C. Контакт удалён после планирования ───

    @Test
    fun c_contactDeletedAfterScheduling_noStalePhoneOrNameAndNoCommunicationActions() {
        seed(item(), contact(phone = "+111", first = "Ivan", last = "Petrov"))
        val alarm = scheduleAndCaptureAlarm(item())

        runBlocking { AppStateStore.deleteContact(contactId).await() }
        clearMemory()

        val n = requireShown(alarm)
        val text = textOf(n)
        assertNull("у удалённого контакта кнопки «Позвонить» быть не должно", callNumber(n))
        assertFalse("кнопки связи отсутствуют, есть ${actionTitles(n)}",
            ctx.getString(R.string.notif_action_message) in actionTitles(n))
        assertFalse("старое имя не используется, есть «$text»", text.contains("Ivan Petrov"))
        assertFalse("старый номер не используется, есть «$text»", text.contains("+111"))
    }

    // ─── D. Холодный процесс: данные только в Room ───

    /**
     * Холодный процесс на ОБОИХ концах, как при восстановлении после перезагрузки:
     * хранилище не подключено и память пуста и в момент планирования (rescheduleAll
     * собирает событие из Room), и в момент срабатывания. Старая схема брала имена
     * и телефон из памяти при планировании — здесь ей было бы нечего «запечь».
     */
    @Test
    fun d_coldProcess_withEmptyMemory_showsCurrentNameAndPhoneActions() {
        seed(item(), contact(phone = "+333", first = "Cold", last = "Start"))

        AppStateStore.resetForTests()
        clearMemory()
        assertTrue("предусловие: память пуста", AppStateStore.contacts.isEmpty() && AppStateStore.calendarItems.isEmpty())

        // Событие для планирования — только из Room, как в rescheduleAll().
        val fromRoom = runBlocking {
            val entity = db.calendarDao().getAllCalendarItems().first { it.id == itemId }
            entity.toDomain().copy(
                links = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == itemId }.map { it.toDomain() },
                reminders = db.calendarDao().getReminderRules().filter { it.calendarItemId == itemId }.map { it.toDomain() }
            )
        }
        val alarm = scheduleAndCaptureAlarm(fromRoom)

        val n = requireShown(alarm)
        assertEquals("телефон собран из Room", "+333", callNumber(n))
        assertTrue("имя собрано из Room, есть «${textOf(n)}»", textOf(n).contains("Cold Start"))
    }

    // ─── E / F / G. События нет, выполнено, отменено ───

    @Test
    fun e_eventDeletedAfterScheduling_notificationIsNotShown() {
        seed(item(), contact())
        val alarm = scheduleAndCaptureAlarm(item())

        runBlocking { AppStateStore.deleteCalendarItem(itemId).await() }
        clearMemory()

        assertNotShown(alarm, "уведомление об удалённом событии не показывается")
    }

    @Test
    fun f_completedEvent_notificationIsNotShown() {
        seed(item(), contact())
        val alarm = scheduleAndCaptureAlarm(item())

        runBlocking {
            AppStateStore.updateCalendarItem(item(), item().copy(status = CalendarItemStatus.COMPLETED))
            AppStateStore.awaitPendingWrites()
        }
        clearMemory()

        assertNotShown(alarm, "уведомление о выполненном событии не показывается")
    }

    @Test
    fun g_cancelledEvent_notificationIsNotShown() {
        seed(item(), contact())
        val alarm = scheduleAndCaptureAlarm(item())

        runBlocking {
            AppStateStore.updateCalendarItem(item(), item().copy(status = CalendarItemStatus.CANCELLED))
            AppStateStore.awaitPendingWrites()
        }
        clearMemory()

        assertNotShown(alarm, "уведомление об отменённом событии не показывается")
    }

    // ─── H. Семантика Batch 6 сохранена ───

    @Test
    fun h_callAndMessageActions_doNotChangeLastContactDate() {
        seed(item(), contact(phone = "+111"))
        val alarm = scheduleAndCaptureAlarm(item())
        clearMemory()
        val n = requireShown(alarm)

        (n.actions ?: emptyArray())
            .filter { it.title.toString() in setOf(ctx.getString(R.string.notif_action_call), ctx.getString(R.string.notif_action_message)) }
            .forEach { a ->
                val pi = shadowOf(a.actionIntent)
                assertTrue("«${a.title}» обязана открывать системную Activity, а не код приложения", pi.isActivityIntent)
                a.actionIntent.send()
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }

        runBlocking { AppStateStore.awaitPendingWrites() }
        assertEquals("кнопки связи сами по себе не меняют дату последнего контакта", oldDate, lastContactInDb())
    }

    // ─── I. Прямое доказательство: старые extras больше не источник истины ───

    /**
     * Будильник со ЗАВЕДОМО устаревшим payload'ом — ровно так выглядят будильники,
     * поставленные версией до этого фикса (title/content/phone внутри). В Room
     * при этом другие данные. Уведомление обязано показать Room.
     */
    @Test
    fun i_alarmWithStaleExtras_isIgnoredInFavourOfRoom() {
        seed(item(), contact(phone = "+222", first = "New", last = "Name"))
        val nid = "stale-payload".hashCode()
        val legacyAlarm = Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", itemId)
            putExtra("notificationId", nid)
            putExtra("title", "Old Title")
            putExtra("content", "Old Name")
            putExtra("phone", "+111")
            putExtra("channel", NotificationHelper.CHANNEL_BIRTHDAY)
        }
        clearMemory()

        deliver(legacyAlarm)
        val n = shadowOf(nm()).getNotification(nid) ?: fail("уведомление не показано") as Nothing

        assertEquals("телефон — из Room, не из extras", "+222", callNumber(n))
        assertTrue("имя — из Room, есть «${textOf(n)}»", textOf(n).contains("New Name"))
        assertFalse("старое имя из extras не используется", textOf(n).contains("Old Name"))
        assertFalse("старый заголовок из extras не используется", titleOf(n) == "Old Title")
    }

    // ─── J. «Отложить»: снятый будильник несёт только identity, повтор снова тянет Room ───

    /**
     * «Отложить» — единственный путь, где будильник события пересобирается НЕ в
     * scheduleReminder(), а в scheduleSnooze(). Инвариант «будильник — только
     * identity» обязан держаться и здесь: отложенный будильник не должен нести
     * title/content/phone, а его срабатывание через сутки — снова читать Room, а
     * не то, что было известно в момент нажатия «Отложить».
     */
    @Test
    fun j_snoozingCalendarReminder_keepsOnlyIdentity_andRefiresWithFreshRoomData() {
        seed(item(), contact(phone = "+111", first = "Ivan", last = "Petrov"))
        val alarm = scheduleAndCaptureAlarm(item())
        val nid = alarm.getIntExtra("notificationId", 0)

        val firstShow = requireShown(alarm)
        val snooze = (firstShow.actions ?: emptyArray())
            .firstOrNull { it.title.toString() == ctx.getString(R.string.notif_action_snooze) }
            ?: fail("нет кнопки «Отложить»") as Nothing
        // ACTION_SNOOZE в приёмнике синхронный (cancel+reschedule без goAsync) —
        // в отличие от остальных веток, ждать PendingResult.finish() здесь нечего.
        NotificationReceiver().onReceive(ctx, shadowOf(snooze.actionIntent).savedIntent)

        // ALARM IDENTITY (Calendar Runtime & Alarm Lifecycle Pack): snooze — ОТДЕЛЬНАЯ identity (kind=snooze), не затирающая и
        // не затираемая основным напоминанием; поэтому ищем именно snooze-будильник этого уведомления.
        val snoozedAlarms = shadowOf(alarmManager()).scheduledAlarms.filter {
            val saved = shadowOf(it.operation).savedIntent
            saved.getIntExtra("notificationId", -1) == nid && saved.data?.authority == "snooze"
        }
        assertEquals("«Отложить» ставит ОДИН snooze-будильник этого уведомления", 1, snoozedAlarms.size)
        val snoozedIntent = shadowOf(snoozedAlarms.single().operation).savedIntent
        assertEquals("отложенный будильник несёт только id события",
            setOf("calendarItemId", "notificationId"), snoozedIntent.extras!!.keySet())
        assertTrue("отложенный будильник — на сутки вперёд",
            snoozedAlarms.single().triggerAtTime > System.currentTimeMillis() + 23L * 60 * 60 * 1000)

        // Между «Отложить» и повторным срабатыванием контакт сменил номер и имя.
        runBlocking {
            AppStateStore.getContact(contactId)!!.let { cur -> AppStateStore.updateContact(cur, cur.copy(
                firstName = "Pyotr", lastName = "Novy",
                phones = listOf(ContactPhone("cr-p1", contactId, "+999", PhoneType.MOBILE, isPrimary = true))
            )) }
            AppStateStore.awaitPendingWrites()
        }
        clearMemory()

        deliver(snoozedIntent)
        val n2 = shadowOf(nm()).getNotification(nid) ?: fail("повторное уведомление не показано") as Nothing
        assertEquals("после «Отложить» повтор берёт АКТУАЛЬНЫЙ номер из Room", "+999", callNumber(n2))
        assertTrue("и актуальное имя, есть «${textOf(n2)}»", textOf(n2).contains("Pyotr Novy"))
    }
}
