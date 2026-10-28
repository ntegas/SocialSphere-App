package com.aistudio.socialsphere.crmlxb.utils

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * INTERACTION SEMANTICS — кнопки уведомлений.
 *
 * INVARIANT: кнопка уведомления означает ровно то действие, которое выполняется.
 * «Готово» завершает событие (а не просто закрывает уведомление), «Связался»
 * отмечает контакт, у информационных уведомлений ложных бизнес-кнопок нет,
 * кнопок не больше трёх.
 *
 * Все сценарии идут через настоящие пути: уведомление показывает сам
 * NotificationReceiver (срабатывание будильника) или StaleContactsReceiver
 * (ежедневная проверка), кнопка нажимается через её PendingIntent.send(), а
 * результат проверяется в Room. Критические A и D стартуют из «холодного
 * процесса»: AppStateStore не инициализирован, память пуста — receiver обязан
 * сам поднять хранилище.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationActionSemanticsTest {

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context
    private val defaultProvider = AppStateStore.databaseProvider

    private val oldDate = "2025-01-01"
    private val today get() = java.time.LocalDate.now().toString()

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
        AppSettings.remindStaleContacts.value = true
        AppSettings.remindBirthdays.value = true
        AppSettings.remindNoNextStep.value = true
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppStateStore.databaseProvider = { db }
        AppStateStore.resetForTests()
        clearMemory()
        // НЕ runBlocking на главном потоке: reloadFromDb() переключается на Main (§57).
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
        AppStateStore.companies.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.notes.clear()
        AppStateStore.gifts.clear()
        AppStateStore.addresses.clear()
        AppStateStore.contactRelations.clear()
        AppStateStore.companyRelations.clear()
        AppStateStore.groups.clear()
        AppStateStore.groupMembers.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
        AppStateStore.personalDetails.clear()
        AppStateStore.sizeInfos.clear()
    }

    /** «Холодный процесс»: хранилище не подключено, в памяти ничего нет,
     *  данные есть только в Room. */
    private fun goCold() {
        AppStateStore.resetForTests()
        clearMemory()
        assertTrue("предусловие cold: память пуста", AppStateStore.calendarItems.isEmpty() && AppStateStore.contacts.isEmpty())
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

    /** suspend-вызов, которому нужен свободный главный поток: запускаем на IO,
     *  а главный Looper прокручиваем в pollUntil. */
    private fun <T> awaitOffMain(block: suspend () -> T): T {
        val done = kotlinx.coroutines.CompletableDeferred<T>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        pollUntil("suspend-операция завершилась") { done.isCompleted }
        return runBlocking { done.await() }
    }

    /**
     * Доставка broadcast'а приёмнику так, как это делает система: новый
     * экземпляр класса из intent.component и настоящий PendingResult (без него
     * goAsync() не работает). Нужна потому, что Robolectric не регистрирует
     * приёмники манифеста без intent-filter, и sendBroadcast() до них не доходит
     * ("Matching wrappers: []"). Проверяет контракт goAsync(): finish() обязан
     * быть вызван.
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
        pollUntil("${receiver.javaClass.simpleName}: PendingResult.finish() вызван") { finished.isDone }
    }

    /** Нажатие кнопки уведомления через её настоящий PendingIntent. */
    private fun press(action: Notification.Action) {
        val pi = shadowOf(action.actionIntent)
        if (pi.isActivityIntent) {
            action.actionIntent.send() // системная Activity — код приложения не выполняется
            shadowOf(android.os.Looper.getMainLooper()).idle()
        } else {
            assertTrue("«${action.title}»: ожидался broadcast в приложение", pi.isBroadcastIntent)
            deliver(pi.savedIntent)
        }
    }

    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(id: Int): Notification? = shadowOf(nm()).getNotification(id)

    private fun awaitNotification(id: Int, what: String): Notification {
        pollUntil("уведомление «$what» показано") { notification(id) != null }
        return notification(id)!!
    }

    private fun titles(n: Notification): List<String> = (n.actions ?: emptyArray()).map { it.title.toString() }

    private fun action(n: Notification, titleRes: Int): Notification.Action =
        (n.actions ?: emptyArray()).firstOrNull { it.title.toString() == ctx.getString(titleRes) }
            ?: fail("нет кнопки «${ctx.getString(titleRes)}», есть ${titles(n)}") as Nothing

    private val call get() = ctx.getString(R.string.notif_action_call)
    private val message get() = ctx.getString(R.string.notif_action_message)
    private val snooze get() = ctx.getString(R.string.notif_action_snooze)
    private val done get() = ctx.getString(R.string.notif_action_done)
    private val contacted get() = ctx.getString(R.string.notif_action_contacted)

    private fun contact(
        id: String,
        phone: String? = "+10000000001",
        rhythm: CommunicationRhythm = CommunicationRhythm.NOT_TRACKED,
        nextStep: String? = "позвонить"
    ) = Contact(
        id = id, firstName = "Ivan", lastName = id, photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = rhythm,
        phones = listOfNotNull(phone?.let { ContactPhone("ph-$id", id, it, PhoneType.MOBILE, true) }),
        nextStep = nextStep,
        lastContactDate = oldDate,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun item(id: String, type: CalendarItemType, contactId: String, startDate: String = "2030-01-01") = CalendarItem(
        id = id, title = type.name, type = type, startDate = startDate,
        isAllDay = true, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
        links = listOf(CalendarItemLink("l-$id", id, CalendarTargetType.CONTACT, contactId)),
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun persist(c: Contact) = runBlocking { AppStateStore.addContact(c).await() }
    private fun persist(i: CalendarItem) = runBlocking { AppStateStore.addCalendarItem(i).await() }

    private fun statusInDb(itemId: String): String? =
        runBlocking { db.calendarDao().getAllCalendarItems() }.firstOrNull { it.id == itemId }?.status

    private fun lastContactInDb(contactId: String): String? =
        runBlocking { db.contactDao().getAllContacts() }.firstOrNull { it.id == contactId }?.lastContactDate

    /** Срабатывание будильника напоминания — те же extras, что кладёт
     *  NotificationScheduler.scheduleReminder. */
    private fun fireReminderAlarm(itemId: String, notificationId: Int, phone: String?) {
        deliver(Intent(ctx, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", itemId)
            putExtra("title", "reminder")
            putExtra("content", "content")
            putExtra("notificationId", notificationId)
            if (phone != null) putExtra("phone", phone)
            putExtra("channel", NotificationHelper.CHANNEL_ID)
        })
    }

    private fun fireDailyCheck() {
        deliver(Intent(ctx, StaleContactsReceiver::class.java))
    }

    // ─── A. CALL-событие → «Готово», холодный процесс ───

    @Test
    fun calendarCallNotification_done_completesItemAndUpdatesContact_inColdProcess() {
        persist(contact("a1"))
        persist(item("ev-a", CalendarItemType.CALL, "a1"))
        goCold()

        val nid = 1001
        fireReminderAlarm("ev-a", nid, "+10000000001")
        val n = awaitNotification(nid, "CALL-напоминание")

        assertEquals("CALL-событие с телефоном: Позвонить, Отложить, Готово", listOf(call, snooze, done), titles(n))
        assertTrue("≤ 3 кнопок", titles(n).size <= NotificationHelper.MAX_ACTIONS)

        // G: «Позвонить» — системная Activity, данные не трогает.
        press(action(n, R.string.notif_action_call))
        assertEquals("«Позвонить» не меняет lastContactDate", oldDate, lastContactInDb("a1"))

        press(action(n, R.string.notif_action_done))

        pollUntil("событие стало COMPLETED в Room") { statusInDb("ev-a") == CalendarItemStatus.COMPLETED.name }
        pollUntil("контакт получил сегодняшнюю дату в Room") { lastContactInDb("a1") == today }
        pollUntil("уведомление закрыто") { notification(nid) == null }
        assertEquals("память совпадает с Room (без устаревшего снимка загрузки)",
            CalendarItemStatus.COMPLETED, AppStateStore.calendarItems.first { it.id == "ev-a" }.status)
        assertEquals(today, AppStateStore.getContact("a1")?.lastContactDate)
    }

    // ─── B. TASK-событие → «Готово»: COMPLETED, контакт не трогаем ───

    @Test
    fun calendarTaskNotification_done_completesItem_doesNotTouchLastContactDate() {
        persist(contact("b1"))
        persist(item("ev-b", CalendarItemType.TASK, "b1"))
        goCold()

        val nid = 1002
        fireReminderAlarm("ev-b", nid, "+10000000001")
        val n = awaitNotification(nid, "TASK-напоминание")
        assertTrue("у задачи есть «Готово»", done in titles(n))
        assertTrue("≤ 3 кнопок", titles(n).size <= NotificationHelper.MAX_ACTIONS)

        press(action(n, R.string.notif_action_done))

        pollUntil("задача стала COMPLETED в Room") { statusInDb("ev-b") == CalendarItemStatus.COMPLETED.name }
        pollUntil("уведомление закрыто") { notification(nid) == null }
        assertEquals("завершение задачи НЕ означает контакт", oldDate, lastContactInDb("b1"))
    }

    // ─── C. Даты: «Готово» отсутствует ───

    @Test
    fun dateOnlyCalendarReminders_haveNoDoneAction() {
        persist(contact("c1"))
        val dateTypes = listOf(
            CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
            CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE
        )
        dateTypes.forEachIndexed { i, type -> persist(item("ev-c$i", type, "c1")) }
        goCold()

        dateTypes.forEachIndexed { i, type ->
            val nid = 1100 + i
            fireReminderAlarm("ev-c$i", nid, "+10000000001")
            val n = awaitNotification(nid, "$type-напоминание")
            assertFalse("$type: у даты нет «Готово», есть ${titles(n)}", done in titles(n))
            assertTrue("$type: ≤ 3 кнопок", titles(n).size <= NotificationHelper.MAX_ACTIONS)
        }
    }

    @Test
    fun birthdayTodayDailyNotification_hasNoDoneAndNoSnooze() {
        persist(contact("c2"))
        val todayDate = java.time.LocalDate.now()
        persist(item("ev-bday", CalendarItemType.BIRTHDAY, "c2", startDate = "1990-%02d-%02d".format(todayDate.monthValue, todayDate.dayOfMonth)))
        goCold()

        fireDailyCheck()
        val n = awaitNotification(("bday_" + "ev-bday").hashCode(), "день рождения сегодня")

        assertEquals("информационное: только каналы связи", listOf(call, message), titles(n))
    }

    // ─── D. «Пора связаться» → «Связался», холодный процесс ───

    @Test
    fun staleContactNotification_contacted_updatesThatContact_inColdProcess() {
        persist(contact("d1", rhythm = CommunicationRhythm.MONTHLY))
        persist(contact("d2", rhythm = CommunicationRhythm.MONTHLY)) // соседний — не должен измениться
        goCold()

        fireDailyCheck()
        val nid = ("stale_" + "d1").hashCode()
        val n = awaitNotification(nid, "пора связаться")

        assertEquals("Позвонить, Написать, Связался", listOf(call, message, contacted), titles(n))
        assertFalse("нет абстрактного «Готово»", done in titles(n))

        // G: «Написать» — системная Activity, данные не трогает.
        press(action(n, R.string.notif_action_message))
        assertEquals("«Написать» не меняет lastContactDate", oldDate, lastContactInDb("d1"))

        press(action(n, R.string.notif_action_contacted))

        pollUntil("контакт d1 получил сегодняшнюю дату в Room") { lastContactInDb("d1") == today }
        pollUntil("уведомление закрыто") { notification(nid) == null }
        assertEquals("соседний контакт не тронут", oldDate, lastContactInDb("d2"))
        assertEquals("память совпадает с Room", today, AppStateStore.getContact("d1")?.lastContactDate)
    }

    // ─── E. Сводка «без следующего шага»: ложных кнопок нет ───

    @Test
    fun noNextStepSummary_hasNoFalseDoneOrContacted() {
        persist(contact("e1", phone = null, nextStep = null))
        goCold()

        fireDailyCheck()
        val n = awaitNotification("no_next_step_summary".hashCode(), "сводка без следующего шага")

        assertEquals("у сводки нет бизнес-действий", emptyList<String>(), titles(n))
    }

    // ─── F. Матрица: не больше 3 кнопок ни у одной комбинации ───

    @Test
    fun actionMatrix_neverExceedsThree_andBusinessActionsOnlyWhereMeaningful() {
        val types: List<CalendarItemType?> = CalendarItemType.values().toList() + null
        NotificationHelper.Kind.values().forEach { kind ->
            listOf(true, false).forEach { hasPhone ->
                listOf(true, false).forEach { hasContactId ->
                    types.forEach { type ->
                        // Завершаемость берётся из того же правила, что у экрана.
                        val completable = type != null && CalendarCompletion.offersCompletion(type)
                        val actions = NotificationHelper.actionsFor(kind, hasPhone, type, completable, hasContactId)
                        val where = "$kind phone=$hasPhone contact=$hasContactId type=$type"
                        assertTrue("$where: ${actions.size} > 3", actions.size <= NotificationHelper.MAX_ACTIONS)
                        assertEquals("$where: дубли кнопок", actions.size, actions.distinct().size)
                        if (NotificationHelper.Action.DONE in actions) {
                            assertEquals("$where: «Готово» только у напоминания о событии", NotificationHelper.Kind.CALENDAR_REMINDER, kind)
                            assertTrue("$where: «Готово» только у завершаемого типа", type != null && !CalendarCompletion.isDateOnly(type))
                        }
                        if (NotificationHelper.Action.CONTACTED in actions) {
                            assertEquals("$where: «Связался» только у «Пора связаться»", NotificationHelper.Kind.STALE_CONTACT, kind)
                            assertTrue("$where: «Связался» только с contactId", hasContactId)
                        }
                        if (kind == NotificationHelper.Kind.NO_NEXT_STEP_SUMMARY || kind == NotificationHelper.Kind.BIRTHDAY_TODAY) {
                            assertFalse("$where: у информационного нет «Готово»", NotificationHelper.Action.DONE in actions)
                            assertFalse("$where: у ежедневного нет «Отложить»", NotificationHelper.Action.SNOOZE in actions)
                        }
                    }
                }
            }
        }
    }

    /** Уже выполненное событие: напоминание о нём не показывается вовсе (а значит,
     *  и «Готово» повторно не предлагается). Раньше тест ждал показа без «Готово»;
     *  владелец утвердил строже: при срабатывании событие, которое не ACTIVE,
     *  не показывается (CalendarReminderFireTest). deliver() дожидается finish(),
     *  поэтому отсутствие уведомления после него — не гонка. */
    @Test
    fun completedCalendarItemReminder_isNotShown() {
        persist(contact("f1"))
        persist(item("ev-f", CalendarItemType.MEETING, "f1").copy(status = CalendarItemStatus.COMPLETED))
        goCold()

        val nid = 1200
        org.robolectric.shadows.ShadowLog.clear()
        fireReminderAlarm("ev-f", nid, null)
        assertEquals("напоминание о выполненном событии не показывается", null, notification(nid))
        // Непоказ обязан быть проверкой status != ACTIVE, а не проглоченным исключением
        // (runAsync ловит Throwable и только логирует) — иначе тест не отличил бы
        // санкционированную проверку от тихого сбоя resolveCalendarReminder.
        assertTrue("непоказ не должен быть тихим исключением",
            org.robolectric.shadows.ShadowLog.getLogsForTag("NotificationReceiver").isEmpty())
        assertEquals("и lastContactDate не тронут", oldDate, lastContactInDb("f1"))
    }
}
