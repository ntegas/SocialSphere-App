package com.aistudio.socialsphere.crmlxb.data

import android.app.AlarmManager
import android.content.Context
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
import com.aistudio.socialsphere.crmlxb.utils.NotificationReceiver
import com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RECONCILIATION Calendar/Reminder alarms после RESTORE (AppStateStore.restoreBackup,
 * вызывается из ExportManager.importJsonBackup/importNotesJson/importCalendarJson)
 * и после обычного STARTUP приложения (MainActivity.onCreate).
 *
 * INVARIANT: Room — canonical source. После reconciliation набор Calendar alarms
 * должен ТОЧНО соответствовать актуальному состоянию Room — ACTIVE future event
 * с валидным reminder rule имеет alarm, COMPLETED/CANCELLED/deleted/past — нет,
 * несколько rules дают несколько независимых alarms, повторный reconciliation
 * не создаёт дубликатов.
 *
 * ОДИН canonical reconcile-path — NotificationScheduler.reconcileAllUserDataAlarms(context, db) —
 * используется И restoreBackup() (после restore, порядок: transaction.await() →
 * reloadFromDb() → reconcile), И BootReceiver (перезагрузка), И MainActivity
 * (обычный старт). Тесты startup дёргают эту же функцию напрямую — то же, что
 * вызывает MainActivity.onCreate().
 */
@RunWith(RobolectricTestRunner::class)
class RestoreStartupReconcileTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
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

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        shadowOf(android.os.Looper.getMainLooper()).idle()
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            ok = check()
        }
        return ok
    }

    /**
     * suspend-вызов, которому нужен свободный главный поток: restoreBackup()
     * внутри переключается на Dispatchers.Main (reloadFromDb) — обычный
     * runBlocking{} на главном потоке теста означал бы взаимную блокировку
     * (§57 SOCIALSPHERE_KNOWLEDGE.md: тот же паттерн, что уже используют
     * NotificationActionSemanticsTest/CalendarReminderFireTest). Запускаем
     * suspend-блок на Dispatchers.IO, а главный Looper прокручиваем в
     * pollUntil — тогда переключение на Main внутри блока может реально
     * выполниться.
     */
    private fun <T> awaitOffMain(block: suspend () -> T): T {
        val done = kotlinx.coroutines.CompletableDeferred<T>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        if (!pollUntil(15_000) { done.isCompleted }) fail("suspend-операция не завершилась за таймаут")
        return runBlocking { done.await() }
    }

    private val eventStart: java.time.LocalDateTime =
        java.time.LocalDateTime.now().plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0)

    private fun calendarItem(
        id: String, status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        start: java.time.LocalDateTime = eventStart, reminders: List<ReminderRule> = emptyList(),
        title: String = "Событие $id",
    ) = CalendarItem(
        id = id, title = title, type = CalendarItemType.CALL,
        startDate = start.toLocalDate().toString(),
        startTime = "%02d:%02d".format(start.hour, start.minute),
        isAllDay = false, status = status, importance = ImportanceLevel.NORMAL,
        reminders = reminders,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun rule(itemId: String, ruleId: String) =
        ReminderRule(id = ruleId, calendarItemId = itemId, reminderType = ReminderType.AT_TIME)

    private fun calendarReminderAlarms() = shadowOf(alarmManager()).scheduledAlarms.filter {
        shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name
    }

    /** Продакшн-путь restore: AppStateStore.restoreBackup(), ровно то, что
     *  вызывают importJsonBackup/importNotesJson/importCalendarJson. */
    private fun restore(vararg items: CalendarItem) {
        awaitOffMain { AppStateStore.restoreBackup(RestorePlan(calendarItems = items.toList())) }
    }

    /** Продакшн-путь обычного старта приложения: MainActivity.onCreate()
     *  вызывает ровно эту функцию. */
    private fun startupReconcile() {
        runBlocking { NotificationScheduler.reconcileAllUserDataAlarms(ctx, db) }
    }

    // ─── A. RESTORE TO EMPTY INSTALL ───
    //  (критический тест — на текущей реализации restore не планировал alarm)

    @Test
    fun a_restoreToEmptyInstall_activeEventWithReminder_alarmCreated() {
        assertEquals("предусловие: alarms = 0", 0, calendarReminderAlarms().size)

        restore(calendarItem("rr-ev-a", reminders = listOf(rule("rr-ev-a", "rr-r-a"))))

        assertEquals("после restore alarm существует", 1, calendarReminderAlarms().size)
    }

    // ─── B. RESTORE НЕ ЗАМЕНЯЕТ ТЕКУЩЕЕ РАСПИСАНИЕ СТАРЫМ ───
    //  BACKUP PACK #3: restoreBackup() — data-safe слияние по id (см. RestoreMerge.kt), а не replace.
    //  Раньше этот тест закреплял деструктивную семантику: backup с ТЕМ ЖЕ событием в статусе
    //  COMPLETED затирал текущее ACTIVE, и alarm исчезал. Порядок «что новее» без метаданных
    //  свежести определить нельзя — текущее значение статуса остаётся, расхождение сообщается.
    //  Alarm поэтому остаётся ровно ОДИН (старый снят снимком ДО restore и восстановлен reconcile
    //  по финальному состоянию Room — не удвоен и не потерян).

    @Test
    fun b_restoreConflictingStatus_keepsCurrentActive_alarmRestoredNotDoubled() {
        val itemId = "rr-ev-b"
        val oldItem = calendarItem(itemId, reminders = listOf(rule(itemId, "rr-r-b-old")))
        runBlocking { AppStateStore.addCalendarItem(oldItem).await() }
        NotificationScheduler.scheduleReminder(ctx, oldItem, oldItem.reminders.single())
        assertEquals("предусловие: старый alarm стоит", 1, calendarReminderAlarms().size)

        val backupItem = calendarItem(itemId, status = CalendarItemStatus.COMPLETED, reminders = listOf(rule(itemId, "rr-r-b-old")))
        val result = awaitOffMain { AppStateStore.restoreBackup(RestorePlan(calendarItems = listOf(backupItem))) }

        assertEquals("статус НЕ заменён старым backup-значением",
            CalendarItemStatus.ACTIVE, runBlocking { db.calendarDao().getAllCalendarItems() }.single().status.let { CalendarItemStatus.valueOf(it) })
        assertEquals("расхождение статуса сообщено, а не разрешено молча", listOf("status"), result.conflicts.map { it.field })
        assertEquals("alarm ровно один: снят по снимку ДО restore и восстановлен reconcile по финальному Room",
            1, calendarReminderAlarms().size)
    }

    // ─── C. RESTORE NON-ACTIVE ───

    @Test
    fun c_restoreNonActive_completedAndCancelled_noAlarms() {
        val completed = calendarItem("rr-ev-c1", status = CalendarItemStatus.COMPLETED, reminders = listOf(rule("rr-ev-c1", "rr-r-c1")))
        val cancelled = calendarItem("rr-ev-c2", status = CalendarItemStatus.CANCELLED, reminders = listOf(rule("rr-ev-c2", "rr-r-c2")))

        restore(completed, cancelled)

        assertEquals("COMPLETED/CANCELLED после restore alarms не получают", 0, calendarReminderAlarms().size)
    }

    // ─── D. MULTIPLE RULES ───

    @Test
    fun d_multipleRules_exactlyTwoIndependentAlarms() {
        val item = calendarItem("rr-ev-d", reminders = listOf(rule("rr-ev-d", "rr-r-d1"), rule("rr-ev-d", "rr-r-d2")))

        restore(item)

        assertEquals("два правила — два независимых alarm", 2, calendarReminderAlarms().size)
    }

    // ─── E. STARTUP RECOVERY ───
    //  (критический тест — на текущей реализации обычный старт (не reboot) не
    //  восстанавливал Calendar reminders, только ежедневную проверку)

    @Test
    fun e_startupRecovery_activeFutureReminderInRoom_alarmMissing_restoredAfterStartup() {
        val item = calendarItem("rr-ev-e", reminders = listOf(rule("rr-ev-e", "rr-r-e")))
        runBlocking { AppStateStore.addCalendarItem(item).await() }
        assertEquals("предусловие: AlarmManager пуст (alarm потерян вне приложения)", 0, calendarReminderAlarms().size)

        startupReconcile()

        assertEquals("после обычного старта alarm восстановлен", 1, calendarReminderAlarms().size)
    }

    // ─── F. STARTUP IDEMPOTENCY ───

    @Test
    fun f_startupIdempotency_repeatedReconcile_alarmCountDoesNotGrow() {
        val item = calendarItem("rr-ev-f", reminders = listOf(rule("rr-ev-f", "rr-r-f")))
        runBlocking { AppStateStore.addCalendarItem(item).await() }

        startupReconcile()
        assertEquals(1, calendarReminderAlarms().size)
        startupReconcile()
        startupReconcile()
        assertEquals("трижды подряд — по-прежнему ровно один alarm", 1, calendarReminderAlarms().size)
    }

    // ─── G. STARTUP NON-ACTIVE ───

    @Test
    fun g_startupNonActive_onlyActualActiveFutureAlarmsRestored() {
        val active = calendarItem("rr-ev-g1", reminders = listOf(rule("rr-ev-g1", "rr-r-g1")))
        val completed = calendarItem("rr-ev-g2", status = CalendarItemStatus.COMPLETED, reminders = listOf(rule("rr-ev-g2", "rr-r-g2")))
        val cancelled = calendarItem("rr-ev-g3", status = CalendarItemStatus.CANCELLED, reminders = listOf(rule("rr-ev-g3", "rr-r-g3")))
        val past = calendarItem("rr-ev-g4", start = java.time.LocalDateTime.now().minusDays(3), reminders = listOf(rule("rr-ev-g4", "rr-r-g4")))
        listOf(active, completed, cancelled, past).forEach { runBlocking { AppStateStore.addCalendarItem(it).await() } }

        startupReconcile()

        val alarms = calendarReminderAlarms()
        assertEquals("восстановлен только actual ACTIVE/future", 1, alarms.size)
        assertEquals("rr-ev-g1", shadowOf(alarms.single().operation).savedIntent.getStringExtra("calendarItemId"))
    }

    // ─── H. MASTER OFF ───

    @Test
    fun h_masterOff_startupAndRestore_noAlarmsCreated() {
        AppSettings.isNotificationsEnabled.value = false
        val item1 = calendarItem("rr-ev-h1", reminders = listOf(rule("rr-ev-h1", "rr-r-h1")))
        runBlocking { AppStateStore.addCalendarItem(item1).await() }

        startupReconcile()
        assertEquals("startup при OFF: alarms не создаются", 0, calendarReminderAlarms().size)

        val item2 = calendarItem("rr-ev-h2", reminders = listOf(rule("rr-ev-h2", "rr-r-h2")))
        restore(item2)
        assertEquals("restore при OFF: alarms не создаются", 0, calendarReminderAlarms().size)
    }

    // ─── I. MASTER ON ───

    @Test
    fun i_masterOn_sameState_alarmsRestored() {
        val item1 = calendarItem("rr-ev-i1", reminders = listOf(rule("rr-ev-i1", "rr-r-i1")))
        runBlocking { AppStateStore.addCalendarItem(item1).await() }

        startupReconcile()
        assertEquals("startup при ON: alarm восстановлен", 1, calendarReminderAlarms().size)

        val item2 = calendarItem("rr-ev-i2", reminders = listOf(rule("rr-ev-i2", "rr-r-i2")))
        restore(item1, item2)
        assertEquals("restore при ON: оба alarm существуют", 2, calendarReminderAlarms().size)
    }

    // ─── J. RESTORE ORDERING ───
    //  Reconcile обязан читать ФИНАЛЬНОЕ, а не промежуточное состояние Room. BACKUP PACK #3: restore не
    //  заменяет текущие значения, поэтому проверка построена на изменении, которое merge реально
    //  делает — ЗАПОЛНЕНИИ пустого: у текущего события нет напоминаний, backup несёт напоминание для
    //  ТОГО ЖЕ id (и другое время старта, которое НЕ применяется — конфликт). Если бы reconcile строился
    //  по снимку ДО транзакции, alarm не появился бы вовсе; если бы по backup-объекту — встал бы на
    //  время из backup, а не на актуальное время события в Room.

    @Test
    fun j_restoreOrdering_reconcileReadsPostTransactionState_notPreTransactionSnapshot() {
        val itemId = "rr-ev-j"
        val current = calendarItem(itemId, start = eventStart, reminders = emptyList())
        runBlocking { AppStateStore.addCalendarItem(current).await() }
        assertEquals("предусловие: alarm нет (нет напоминаний)", 0, calendarReminderAlarms().size)

        val backupStart = eventStart.plusDays(5)
        val backupItem = calendarItem(itemId, start = backupStart, reminders = listOf(rule(itemId, "rr-r-j-new")))
        restore(backupItem)

        val alarms = calendarReminderAlarms()
        assertEquals("напоминание заполнено из backup — ровно один alarm", 1, alarms.size)
        val expectedTrigger = eventStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("alarm стоит на АКТУАЛЬНОМ (Room) времени события, а не на времени из backup — reconcile читал Room ПОСЛЕ commit",
            expectedTrigger, alarms.single().triggerAtTime)
    }

    // ─── K. FAILURE SAFETY ───
    //  «Сбой» планирования смоделирован безопасно и детерминированно: master
    //  notifications OFF в момент reconcile внутри restoreBackup() — тот же
    //  единственный барьер (scheduleReminder/scheduleStaleCheck), которым
    //  обходится любая недоступность/сбой планирования, потому что ни один
    //  из них не бросает исключений наверх при нормальной работе (см. отчёт —
    //  сама попытка форсировать РЕАЛЬНОЕ исключение через закрытую in-memory
    //  Room БД внутри этого прогона привела к зависанию Robolectric/SQLite на
    //  запросе к закрытой БД — задокументировано отдельной находкой, не
    //  повторяем такой риск здесь). Наблюдаемое свойство то же самое: alarm
    //  не создан сразу после restore, но восстановленные Room-данные не
    //  откатываются, и следующий reconcile, когда условия позволяют,
    //  восстанавливает alarm.
    @Test
    fun k_failureSafety_schedulingUnavailableAfterSuccessfulRestore_dataStaysSaved_nextReconcileRecovers() {
        AppSettings.isNotificationsEnabled.value = false
        val itemId = "rr-ev-k"
        restore(calendarItem(itemId, reminders = listOf(rule(itemId, "rr-r-k"))))

        assertEquals("restore успешно сохранил данные даже когда планирование не создало ни одного alarm",
            1, runBlocking { db.calendarDao().getAllCalendarItems() }.size)
        assertEquals("планирование действительно недоступно (симулированный сбой)",
            0, calendarReminderAlarms().size)

        AppSettings.isNotificationsEnabled.value = true
        startupReconcile()
        assertEquals("следующий reconcile восстанавливает пропущенный alarm",
            1, calendarReminderAlarms().size)
    }
}
