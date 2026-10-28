package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.Deferred
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
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CALENDAR CONCURRENCY PROOF & FIX (2026-09-23).
 *
 * Инвариант: [AppStateStore.updateCalendarItem]/[AppStateStore.markCalendarItemCompleted]/
 * [AppStateStore.postponeCalendarItemOccurrence] каждый читает calendarItems[idx] («каноническое
 * состояние»), вычисляет замену и пишет её обратно СИНХРОННО (без suspend-точек) — но НИКАК не
 * координируются друг с другом. DbWriteQueue сериализует только сам Room-коммит (то, что enqueue
 * ставит В очередь), а не эту read-modify-write последовательность ДО enqueue. Если две из этих
 * функций вызваны по-настоящему конкурентно (разные реальные потоки — completionScope/postponeScope/
 * calendarEditScope/Main все опираются на Dispatchers.IO с истинным параллелизмом) для ОДНОГО и
 * того же CalendarItem, обе могут прочитать ОДНО каноническое состояние ДО того, как любая из них
 * записала своё — независимое изменение той, что закоммитится РАНЬШЕ, будет молча стёрто полной
 * заменой строки той, что закоммитится ПОЗЖЕ (Room `insertCalendarItem` — REPLACE, не partial update).
 *
 * [AppStateStore.calendarRaceHookForTests] — детерминированный тестовый шов (не sleep()): обе
 * гонящиеся операции синхронно паузятся СРАЗУ после чтения канонического состояния, ДО вычисления
 * замены — тест дожидается, что ОБЕ (или ровно одна — под исправленным кодом, гарантирующим взаимное
 * исключение, вторая физически не может дойти до шва, пока не отпустят первую) достигли паузы, затем
 * отпускает разом. Единый JVM-lock фикса (см. AppStateStore.calendarMutationLock) НЕ suspend-safe по
 * конструкции — критическая секция не содержит ни одной suspend-точки (enqueueWrite сам не suspend),
 * поэтому synchronized корректен и не может привести к deadlock/IllegalMonitorStateException.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarConcurrencyRaceTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase
    private val today: LocalDate = LocalDate.now()
    private val t0 = "2026-01-01T00:00:00"

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.defaultTagsSeeded.value = true
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.writeGateForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.calendarRaceHookForTests = null
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        if (!settled) fail("loadInitialData не завершился")
    }

    @After
    fun tearDown() {
        AppStateStore.calendarRaceHookForTests = null
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear(); AppStateStore.gifts.clear()
        AppStateStore.calendarItems.clear(); AppStateStore.addresses.clear(); AppStateStore.companyRelations.clear()
        AppStateStore.contactRelations.clear(); AppStateStore.groups.clear(); AppStateStore.groupMembers.clear()
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear(); AppStateStore.personalDetails.clear(); AppStateStore.sizeInfos.clear()
        db.close()
    }

    // ─── обвязка ────────────────────────────────────────────────

    private fun pollUntilTrue(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { Thread.sleep(20); ok = check() }
        return ok
    }

    private fun event(
        id: String, type: CalendarItemType = CalendarItemType.MEETING, start: LocalDate = today.plusDays(10),
        rule: String? = null, explicit: Boolean = false, reminders: List<ReminderRule> = emptyList(),
        title: String = "Original",
    ) = CalendarItem(
        id = id, title = title, type = type, startDate = start.toString(), startTime = "10:00", isAllDay = false,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, recurrenceRule = rule, recurrenceExplicitlySet = explicit,
        reminders = reminders, createdAt = t0, updatedAt = t0)

    /** WEEKLY-серия, anchor на день недели «сегодня» → pending occurrence = today (тот же паттерн, что CalendarStaleEditorSafetyTest). */
    private fun weekly(id: String) =
        event(id, type = CalendarItemType.CALL, start = today.minusWeeks(4), rule = RecurrenceMode.WEEKLY.toRRule(), explicit = true)

    private fun rem(item: String, id: String, minutes: Int) =
        ReminderRule(id, item, ReminderType.BEFORE, minutes, ReminderOffsetUnit.MINUTES, null)

    private fun add(e: CalendarItem): CalendarItem { runBlocking { AppStateStore.addCalendarItem(e).await() }; return cur(e.id) }
    private fun cur(id: String): CalendarItem = AppStateStore.getCalendarItemById(id)!!

    /** Независимое чтение Room (DAO) — не через AppStateStore memory. */
    private fun room(id: String): CalendarItem = runBlocking {
        db.calendarDao().getAllCalendarItems().first { it.id == id }.toDomain().copy(
            reminders = db.calendarDao().getReminderRules().filter { it.calendarItemId == id }.map { it.toDomain() })
    }

    /**
     * Форсирует детерминированный interleaving двух гонящихся writer-вызовов.
     *
     * ops — по одной лямбде НА КАЖДУЮ гонящуюся операцию, вызывающей ровно ОДНУ из
     * updateCalendarItem/markCalendarItemCompleted/postponeCalendarItemOccurrence (сама лямбда
     * выполняется на СВОЁМ потоке). Обе (под текущим кодом БЕЗ synchronized) либо ровно одна (под
     * исправленным кодом — вторая физически блокируется на synchronized, до шва не доходит)
     * достигают паузы внутри calendarRaceHookForTests — тест ждёт бегущим окном (bothReadyOrTimeout),
     * затем отпускает разом (go), затем дожидается завершения ОБОИХ потоков (bounded join).
     *
     * НЕ sleep()-синхронизация: bothReadyOrTimeout.await(...) — это верхняя граница ожидания
     * ФАКТИЧЕСКОГО сигнала (countDown из самого шва), а не «подождать и понадеяться». Отсутствие
     * второго сигнала в пределах окна — ожидаемый, а не аварийный исход (под исправленным кодом).
     */
    private fun raceRun(opA: () -> Deferred<*>, opB: () -> Deferred<*>) {
        val bothReadyOrTimeout = CountDownLatch(2)
        val go = CountDownLatch(1)
        AppStateStore.calendarRaceHookForTests = {
            bothReadyOrTimeout.countDown()
            go.await()
        }
        var errorA: Throwable? = null
        var errorB: Throwable? = null
        val threadA = Thread {
            try { val d = opA(); runBlocking { d.await() } }
            catch (t: Throwable) { errorA = t }
        }
        val threadB = Thread {
            try { val d = opB(); runBlocking { d.await() } }
            catch (t: Throwable) { errorB = t }
        }
        threadA.start()
        threadB.start()

        bothReadyOrTimeout.await(2, TimeUnit.SECONDS)
        AppStateStore.calendarRaceHookForTests = null
        go.countDown()

        threadA.join(5_000)
        threadB.join(5_000)
        assertFalse("threadA не завершился (deadlock?)", threadA.isAlive)
        assertFalse("threadB не завершился (deadlock?)", threadB.isAlive)
        errorA?.let { throw it }
        errorB?.let { throw it }
    }

    // ═════════════════════════════════════════════════════════════
    //  T1 — правка независимого поля (editor save) vs завершение (notification "Готово"), non-recurring.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T1_concurrentTitleEditAndCompletion_bothSurvive() {
        val base = add(event("t1"))

        raceRun(
            opA = { AppStateStore.updateCalendarItem(base, base.copy(title = "EDITED-BY-A")) },
            opB = { AppStateStore.markCalendarItemCompleted(cur("t1"), null) },
        )

        val r = room("t1")
        assertEquals("правка названия не потеряна", "EDITED-BY-A", r.title)
        assertEquals("завершение не потеряно", CalendarItemStatus.COMPLETED, r.status)
    }

    // ═════════════════════════════════════════════════════════════
    //  T2 — перенос текущего occurrence (postpone) vs независимая правка напоминаний (editor save), recurring.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T2_concurrentPostponeAndReminderEdit_bothSurvive() {
        val base = add(weekly("t2"))
        val seriesDate = today
        val targetDate = today.plusDays(2)

        raceRun(
            opA = { AppStateStore.postponeCalendarItemOccurrence(cur("t2"), seriesDate, targetDate) },
            opB = { AppStateStore.updateCalendarItem(base, base.copy(reminders = base.reminders + rem("t2", "newRem", 30))) },
        )

        val r = room("t2")
        assertEquals("postpone-пара не потеряна (from)", seriesDate.toString(), r.postponedFromOccurrenceDate)
        assertEquals("postpone-пара не потеряна (to)", targetDate.toString(), r.postponedToDate)
        assertTrue("напоминание не потеряно", r.reminders.any { it.id == "newRem" })
    }

    // ═════════════════════════════════════════════════════════════
    //  T3 — завершение из уведомления (без editBase) vs завершение В ТОМ ЖЕ save редактора,
    //  несущее независимую правку названия (editBase != null), одно occurrence/событие.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T3_concurrentCompletionTransitionsCarryingDifferentFieldEdits_bothFieldsSurvive() {
        // Обе стороны — «редактор завершает В ОДНОМ save» (editBase != null, requirement 5 пакета «Tags Backup
        // Recovery»/Calendar Data Safety: title и description — НЕЗАВИСИМЫЕ поля, обе обязаны выжить вне зависимости
        // от того, чья запись физически закоммитится в Room последней (оба выставляют status=COMPLETED — это
        // ОБЩЕЕ для обеих сторон, поэтому само по себе не может служить признаком потери; независимый признак —
        // title у A и description у B, каждый теряется ТОЛЬКО если его сторона проиграла гонку).
        val original = add(event("t3"))

        raceRun(
            opA = { AppStateStore.markCalendarItemCompleted(original.copy(title = "A-EDIT"), null, original) },
            opB = { AppStateStore.markCalendarItemCompleted(original.copy(description = "B-EDIT"), null, original) },
        )

        val r = room("t3")
        assertEquals("завершено", CalendarItemStatus.COMPLETED, r.status)
        assertEquals("правка A (title) не потеряна", "A-EDIT", r.title)
        assertEquals("правка B (description) не потеряна", "B-EDIT", r.description)
    }

    // ═════════════════════════════════════════════════════════════
    //  T4 — операция над ОДНИМ CalendarItem не должна задевать ДРУГОЙ.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T4_concurrentOperationsOnDifferentItems_noCrossContamination() {
        val e1 = add(event("t4-e1", title = "E1-Original"))
        val e2 = add(weekly("t4-e2"))

        raceRun(
            opA = { AppStateStore.updateCalendarItem(e1, e1.copy(title = "E1-EDITED")) },
            opB = { AppStateStore.postponeCalendarItemOccurrence(cur("t4-e2"), today, today.plusDays(2)) },
        )

        val r1 = room("t4-e1")
        val r2 = room("t4-e2")
        assertEquals("E1: своя правка применена", "E1-EDITED", r1.title)
        assertEquals("E1: postpone-поля E2 НЕ просочились", null, r1.postponedFromOccurrenceDate)
        assertEquals("E2: своя postpone-пара применена", today.toString(), r2.postponedFromOccurrenceDate)
        assertEquals("E2: title НЕ задет операцией над E1", "Original", r2.title)
    }
}
