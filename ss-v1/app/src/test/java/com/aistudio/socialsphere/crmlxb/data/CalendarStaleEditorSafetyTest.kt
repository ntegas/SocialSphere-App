package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.ui.screens.persistCalendarItemEdit
import com.aistudio.socialsphere.crmlxb.utils.NotificationReceiver
import com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
import com.aistudio.socialsphere.crmlxb.utils.effectiveRecurrenceMode
import com.aistudio.socialsphere.crmlxb.utils.pendingOccurrenceDate
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
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

/**
 * CALENDAR DATA SAFETY PACK — устаревший снимок редактора не откатывает более новую независимую бизнес-операцию.
 *
 * Инвариант: сохранение редактора = `текущее состояние + diff(base → edited)`; менять каноническое состояние вправе только то, что
 * пользователь РЕАЛЬНО изменил в форме. Завершение, lastCompleted, постпон-пара, recurrence, связи и напоминания, изменённые другой
 * операцией, пока форма была открыта, переживают сохранение несвязанной правки.
 *
 * Схема каждого теста: e1 = снимок формы при открытии (base) → НЕЗАВИСИМАЯ операция меняет событие (её писатель или другая
 * сессия редактора) → e1 НЕ обновляется → через e1 сохраняется несвязанная (или намеренная) правка. Ожидаемые значения — явные
 * константы и независимые чтения Room (DAO), а не объект, прошедший через тестируемый код.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarStaleEditorSafetyTest {

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
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        if (!settled) fail("loadInitialData не завершился")
    }

    @After
    fun tearDown() {
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

    private fun addContacts(vararg ids: String) = ids.forEach { id ->
        runBlocking {
            AppStateStore.addContact(Contact(id = id, firstName = "C-$id", lastName = "Тест", photoUri = null,
                relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
                importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
                communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t0, updatedAt = t0)).await()
        }
    }

    private fun link(item: String, contact: String, id: String = UUID.randomUUID().toString()) =
        CalendarItemLink(id, item, CalendarTargetType.CONTACT, contact)

    private fun rem(item: String, id: String, minutes: Int) =
        ReminderRule(id, item, ReminderType.BEFORE, minutes, ReminderOffsetUnit.MINUTES, null)

    private fun event(
        id: String, type: CalendarItemType = CalendarItemType.MEETING, start: LocalDate = today.plusDays(10),
        rule: String? = null, explicit: Boolean = false, links: List<CalendarItemLink> = emptyList(),
        reminders: List<ReminderRule> = emptyList(), pair: Pair<LocalDate, LocalDate>? = null, title: String = "Original",
    ) = CalendarItem(
        id = id, title = title, type = type, startDate = start.toString(), startTime = "10:00", isAllDay = false,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, recurrenceRule = rule, recurrenceExplicitlySet = explicit,
        postponedFromOccurrenceDate = pair?.first?.toString(), postponedToDate = pair?.second?.toString(),
        links = links, reminders = reminders, createdAt = t0, updatedAt = t0)

    /** WEEKLY-серия, anchor на день недели «сегодня» → pending occurrence = today. */
    private fun weekly(id: String, links: List<CalendarItemLink> = emptyList(), pair: Pair<LocalDate, LocalDate>? = null) =
        event(id, type = CalendarItemType.CALL, start = today.minusWeeks(4), rule = RecurrenceMode.WEEKLY.toRRule(),
            explicit = true, links = links, pair = pair)

    private fun add(e: CalendarItem): CalendarItem { runBlocking { AppStateStore.addCalendarItem(e).await() }; return cur(e.id) }
    private fun cur(id: String): CalendarItem = AppStateStore.getCalendarItemById(id)!!
    private fun edit(base: CalendarItem, edited: CalendarItem) { runBlocking { AppStateStore.updateCalendarItem(base, edited).await() } }

    /** Независимое чтение Room (DAO): событие + его связи и напоминания. */
    private fun room(id: String): CalendarItem = runBlocking {
        db.calendarDao().getAllCalendarItems().first { it.id == id }.toDomain().copy(
            links = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == id }.map { it.toDomain() },
            reminders = db.calendarDao().getReminderRules().filter { it.calendarItemId == id }.map { it.toDomain() })
    }

    private fun linkKeys(i: CalendarItem) = i.links.map { it.targetType to it.targetId }.toSet()
    private fun reminderKeys(i: CalendarItem) = i.reminders.map { listOf(it.reminderType, it.offsetValue, it.offsetUnit) }.toSet()
    private fun mode(i: CalendarItem) = effectiveRecurrenceMode(i.type, i.recurrenceRule, i.recurrenceExplicitlySet)

    private fun assertMemoryEqualsRoom(id: String) {
        fun norm(i: CalendarItem) = i.copy(links = i.links.sortedBy { it.id }, reminders = i.reminders.sortedBy { it.id })
        assertEquals("память == Room", norm(room(id)), norm(cur(id)))
    }

    private fun alarms(itemId: String) =
        shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name &&
                shadowOf(it.operation).savedIntent.getStringExtra("calendarItemId") == itemId
        }

    private fun epoch(dt: java.time.LocalDateTime) = dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ═════════════════════════════════════════════════════════════
    //  Ownership: status / lastCompleted / postpone — владеют completion и postpone, не редактор
    //  (T1 — DurableSaveContractTest.calendar_staleEditor_doesNotRevertCompletion)
    // ═════════════════════════════════════════════════════════════

    /** T2: completion записал lastCompleted, пока форма держит null; несвязанная правка не стирает и не откатывает дату. */
    @Test
    fun T2_staleEditor_doesNotRevertLastCompletedOccurrence() {
        val s1 = add(weekly("r-t2"))
        runBlocking { AppStateStore.markCalendarItemCompleted(cur("r-t2"), today).await() }        // завершение в другом месте

        edit(s1, s1.copy(description = "EDITED"))

        assertEquals("lastCompleted не стёрт", today.toString(), room("r-t2").lastCompletedOccurrenceDate)
        assertEquals("EDITED", room("r-t2").description)
        assertEquals("occurrence не открыт заново: серия ACTIVE", CalendarItemStatus.ACTIVE, room("r-t2").status)

        // и не откатывается на БОЛЕЕ СТАРУЮ дату: форма открыта на today, затем завершено следующее occurrence
        val s2 = cur("r-t2")
        runBlocking { AppStateStore.markCalendarItemCompleted(cur("r-t2"), today.plusWeeks(1)).await() }
        edit(s2, s2.copy(title = "EDITED-2"))
        assertEquals("lastCompleted не откачен назад", today.plusWeeks(1).toString(), room("r-t2").lastCompletedOccurrenceDate)
        assertMemoryEqualsRoom("r-t2")
    }

    /** T3: postpone записан, пока форма открыта; правка названия не стирает пару (ни целиком, ни наполовину). */
    @Test
    fun T3_staleEditor_doesNotEraseOccurrencePostpone() {
        val s1 = add(weekly("r-t3"))
        runBlocking { AppStateStore.postponeCalendarItemOccurrence(cur("r-t3"), today, today.plusDays(2)).await() }

        edit(s1, s1.copy(title = "EDITED"))

        val r = room("r-t3")
        assertEquals("пара цела: (from, to)", today.toString() to today.plusDays(2).toString(),
            r.postponedFromOccurrenceDate to r.postponedToDate)
        assertEquals("EDITED", r.title)
        assertMemoryEqualsRoom("r-t3")
    }

    // ═════════════════════════════════════════════════════════════
    //  Recurrence
    // ═════════════════════════════════════════════════════════════

    /** T4: другая сессия сменила recurrence на MONTHLY; форма (база WEEKLY) правит только название → MONTHLY остаётся. */
    @Test
    fun T4_staleEditor_doesNotRevertConcurrentRecurrenceChange() {
        val s1 = add(weekly("r-t4"))
        val other = cur("r-t4")
        edit(other, other.copy(recurrenceRule = RecurrenceMode.MONTHLY.toRRule(), recurrenceExplicitlySet = true))

        edit(s1, s1.copy(title = "EDITED"))

        assertEquals("recurrence не откачена к WEEKLY", RecurrenceMode.MONTHLY, mode(room("r-t4")))
        assertEquals("EDITED", room("r-t4").title)
    }

    /** T5: намеренная правка recurrence применяется — и поверх чужой смены (воля пользователя побеждает). */
    @Test
    fun T5_intentionalRecurrenceEdit_applies_evenOverConcurrentChange() {
        val s1 = add(weekly("r-t5"))
        edit(s1, s1.copy(recurrenceRule = RecurrenceMode.DAILY.toRRule(), recurrenceExplicitlySet = true))
        assertEquals("намеренная правка: DAILY", RecurrenceMode.DAILY, mode(room("r-t5")))

        val s2 = add(weekly("r-t5b"))
        val other = cur("r-t5b")
        edit(other, other.copy(recurrenceRule = RecurrenceMode.MONTHLY.toRRule(), recurrenceExplicitlySet = true))
        edit(s2, s2.copy(recurrenceRule = RecurrenceMode.DAILY.toRRule(), recurrenceExplicitlySet = true))
        assertEquals("воля пользователя — DAILY, не чужой MONTHLY", RecurrenceMode.DAILY, mode(room("r-t5b")))
    }

    /** T6: rule и explicit — один aggregate: не смешиваются половинками, «Не повторять» не превращается в legacy-unset. */
    @Test
    fun T6_recurrenceRuleAndExplicitFlag_stayConsistent() {
        // (i) legacy BIRTHDAY (rule=null, explicit=false → effective YEARLY); пользователь явно выбирает «Не повторять»
        val legacy = add(event("r-t6a", type = CalendarItemType.BIRTHDAY, start = today.plusDays(20)))
        assertEquals(RecurrenceMode.YEARLY, mode(legacy))
        edit(legacy, legacy.copy(recurrenceRule = null, recurrenceExplicitlySet = true))
        assertEquals("явный NONE: пара (null, true), не (null, false)", null to true,
            room("r-t6a").let { it.recurrenceRule to it.recurrenceExplicitlySet })
        assertEquals(RecurrenceMode.NONE, mode(room("r-t6a")))

        // (ii) несвязанная правка legacy-записи: остаётся legacy (null, false) → всё ещё YEARLY
        val legacy2 = add(event("r-t6b", type = CalendarItemType.BIRTHDAY, start = today.plusDays(20)))
        edit(legacy2, legacy2.copy(title = "EDITED"))
        assertEquals(null to false, room("r-t6b").let { it.recurrenceRule to it.recurrenceExplicitlySet })
        assertEquals(RecurrenceMode.YEARLY, mode(room("r-t6b")))

        // (iii) чужая смена (WEEKLY, explicit=true) и несвязанная правка формы: пара пришла целиком, не (WEEKLY, false)
        val legacy3 = add(event("r-t6c", type = CalendarItemType.BIRTHDAY, start = today.plusDays(20)))
        val other = cur("r-t6c")
        edit(other, other.copy(recurrenceRule = RecurrenceMode.WEEKLY.toRRule(), recurrenceExplicitlySet = true))
        edit(legacy3, legacy3.copy(title = "EDITED"))
        assertEquals(RecurrenceMode.WEEKLY.toRRule() to true, room("r-t6c").let { it.recurrenceRule to it.recurrenceExplicitlySet })
    }

    // ═════════════════════════════════════════════════════════════
    //  Links
    // ═════════════════════════════════════════════════════════════

    /** T8a (Test D): удалённая параллельно связь не воскресает; T8b: намеренный diff [A,B]→[A,C] сохраняет параллельное D → [A,C,D]. */
    @Test
    fun T8_links_removedLinkStaysRemoved_andIntentionalDiffKeepsConcurrentAdditions() {
        addContacts("cA", "cB", "cC", "cD")

        // D: base [A,B]; параллельно снят B; форма правит только название (пересобрала связи с НОВЫМИ id)
        val s1 = add(event("l-1", links = listOf(link("l-1", "cA"), link("l-1", "cB"))))
        val other = cur("l-1")
        edit(other, other.copy(links = other.links.filter { it.targetId == "cA" }))
        edit(s1, s1.copy(title = "EDITED", links = listOf(link("l-1", "cA"), link("l-1", "cB"))))
        assertEquals("B не воскрес", setOf(CalendarTargetType.CONTACT to "cA"), linkKeys(room("l-1")))
        assertEquals("EDITED", room("l-1").title)
        assertEquals("id связи A — текущий, не перегенерированный формой", other.links.single { it.targetId == "cA" }.id,
            room("l-1").links.single().id)

        // намеренный diff: base [A,B]; текущее [A,B,D]; форма: [A,C] → −B (намеренно), +C (намеренно), D сохранён
        val s2 = add(event("l-2", links = listOf(link("l-2", "cA"), link("l-2", "cB"))))
        val other2 = cur("l-2")
        edit(other2, other2.copy(links = other2.links + link("l-2", "cD")))
        edit(s2, s2.copy(links = listOf(link("l-2", "cA"), link("l-2", "cC"))))
        assertEquals(setOf("cA", "cC", "cD"), room("l-2").links.map { it.targetId }.toSet())
        assertEquals("без дублей (identity — цель, не id)", 3, room("l-2").links.size)
        assertMemoryEqualsRoom("l-2")
    }

    /** T9 (Test E): контакт удалён, пока форма открыта, и форма ДОБАВИЛА связь на него — сирота не создаётся. */
    @Test
    fun T9_deletedContact_cannotBeResurrectedAsOrphanLink() {
        addContacts("cA", "cC")
        val s1 = add(event("l-9", links = listOf(link("l-9", "cA"))))
        runBlocking { AppStateStore.deleteContact("cC").await() }                                   // C удалён глобально

        edit(s1, s1.copy(links = listOf(link("l-9", "cA"), link("l-9", "cC"))))                     // форма добавила C

        assertEquals("сирота на удалённого C не создан", setOf(CalendarTargetType.CONTACT to "cA"), linkKeys(room("l-9")))
        assertEquals(setOf(CalendarTargetType.CONTACT to "cA"), linkKeys(cur("l-9")))
    }

    // ═════════════════════════════════════════════════════════════
    //  Reminders
    // ═════════════════════════════════════════════════════════════

    /** T10: напоминание удалено параллельно — форма (перебрала его с новым id) не восстанавливает. */
    @Test
    fun T10_staleEditor_doesNotRestoreRemovedReminder() {
        val s1 = add(event("m-10", reminders = listOf(rem("m-10", "r30", 30))))
        val other = cur("m-10")
        edit(other, other.copy(reminders = emptyList()))

        edit(s1, s1.copy(title = "EDITED", reminders = listOf(rem("m-10", "r30-again", 30))))

        assertTrue("напоминание не воскресло", room("m-10").reminders.isEmpty())
        assertEquals("EDITED", room("m-10").title)
    }

    /** T11: намеренная правка 30→10 применяется; независимо добавленное 1h не удаляется без явного намерения. */
    @Test
    fun T11_intentionalReminderEdit_applies_andPreservesConcurrentlyAddedReminder() {
        val s1 = add(event("m-11", reminders = listOf(rem("m-11", "r30", 30))))
        val other = cur("m-11")
        edit(other, other.copy(reminders = other.reminders + rem("m-11", "r60", 60)))            // параллельно добавлено 60

        edit(s1, s1.copy(reminders = listOf(rem("m-11", "r10", 10))))                            // намеренно: 30 → 10

        assertEquals("30 убрано (намеренно), 10 добавлено (намеренно), 60 сохранено", setOf(listOf(ReminderType.BEFORE, 10, ReminderOffsetUnit.MINUTES),
            listOf(ReminderType.BEFORE, 60, ReminderOffsetUnit.MINUTES)), reminderKeys(room("m-11")))
        assertEquals("id независимо добавленного напоминания сохранён", "r60", room("m-11").reminders.single { it.offsetValue == 60 }.id)
    }

    /**
     * FRONT 15 (Alarm Lifecycle Pack): «одна и та же строка» напоминания. Форма редактирует НАБОР пунктов (chips), а не строку по
     * id — редактор перегенерирует id при каждом сохранении, поэтому «30→60» и «30→10» на уровне данных — это «убрать 30, добавить
     * 60» и «убрать 30, добавить 10»; иной идентичности строки (persisted stable id между сессиями) в модели нет. Явная политика:
     * set-семантика — расходящиеся замены одного и того же исходного пункта ОБЕ сохраняются (ни одна правка не теряется молча),
     * совпадающая цель схлопывается в ОДНУ строку (без дубля), независимо добавленное напоминание остаётся.
     */
    @Test
    fun T16_reminderSameBaseRow_divergentReplacementsKeepBothEdits_identicalTargetCollapses() {
        fun offsets(id: String) = room(id).reminders.mapNotNull { it.offsetValue }.sorted()

        // расходящиеся: другая сессия 30→60, устаревшая форма 30→10
        val s1 = add(event("m-16", reminders = listOf(rem("m-16", "r30", 30))))
        val other = cur("m-16")
        edit(other, other.copy(reminders = listOf(rem("m-16", "r60", 60))))
        edit(s1, s1.copy(reminders = listOf(rem("m-16", "r10", 10))))
        assertEquals("обе правки живы, по одной строке на цель", listOf(10, 60), offsets("m-16"))
        assertEquals("id строки другой сессии сохранён (форма его не перегенерировала)", "r60", room("m-16").reminders.single { it.offsetValue == 60 }.id)

        // совпадающая цель: обе сессии 30→60 → одна строка 60
        val s2 = add(event("m-16b", reminders = listOf(rem("m-16b", "r30", 30))))
        val o2 = cur("m-16b")
        edit(o2, o2.copy(reminders = listOf(rem("m-16b", "r60", 60))))
        edit(s2, s2.copy(reminders = listOf(rem("m-16b", "r60-again", 60))))
        assertEquals("одна и та же цель — не два будильника", listOf(60), offsets("m-16b"))

        // независимое ДОБАВЛЕНИЕ 60 + правка формы 30→10 → [10, 60], тот же итог: «edit-same» и «add-another» не теряют друг друга
        val s3 = add(event("m-16c", reminders = listOf(rem("m-16c", "r30", 30))))
        val o3 = cur("m-16c")
        edit(o3, o3.copy(reminders = o3.reminders + rem("m-16c", "r60", 60)))
        edit(s3, s3.copy(reminders = listOf(rem("m-16c", "r10", 10))))
        assertEquals(listOf(10, 60), offsets("m-16c"))
    }

    // ═════════════════════════════════════════════════════════════
    //  Postpone + правка серии
    // ═════════════════════════════════════════════════════════════

    /** T12 (Test F): несвязанная правка названия НЕ очищает существующий постпон. */
    @Test
    fun T12_unrelatedTitleEdit_keepsExistingPostpone() {
        val pair = today to today.plusDays(2)
        val s1 = add(weekly("r-12", pair = pair))
        edit(s1, s1.copy(title = "EDITED"))
        assertEquals(pair.first.toString() to pair.second.toString(),
            room("r-12").let { it.postponedFromOccurrenceDate to it.postponedToDate })
    }

    /** T13: НАМЕРЕННАЯ смена даты-anchor или recurrence очищает пару (существующая политика); при неизменной серии — нет. */
    @Test
    fun T13_intentionalStartDateOrRecurrenceEdit_clearsPostpone() {
        val pair = today to today.plusDays(2)
        val a = add(weekly("r-13a", pair = pair))
        edit(a, a.copy(startDate = today.minusWeeks(3).toString()))
        assertEquals("смена даты-anchor → пара очищена целиком", null to null,
            room("r-13a").let { it.postponedFromOccurrenceDate to it.postponedToDate })

        val b = add(weekly("r-13b", pair = pair))
        edit(b, b.copy(recurrenceRule = RecurrenceMode.MONTHLY.toRRule(), recurrenceExplicitlySet = true))
        assertEquals("смена recurrence → пара очищена целиком", null to null,
            room("r-13b").let { it.postponedFromOccurrenceDate to it.postponedToDate })
    }

    // ═════════════════════════════════════════════════════════════
    //  Правка + completion в одном сохранении (FixedSeries)
    // ═════════════════════════════════════════════════════════════

    /** T14: recurrence WEEKLY→MONTHLY + «Выполнено» в одном save при параллельно снятой связи: target валидируется по ИСХОДНОЙ серии. */
    @Test
    fun T14_editorRecurrenceEditPlusCompletion_usesFixedSeries_andPreservesConcurrentLinkRemoval() {
        addContacts("cA", "cB")
        val id = "r-14"
        val s1 = add(weekly(id, links = listOf(link(id, "cA"), link(id, "cB"))))
        assertEquals("предусловие: pending = today", today, s1.pendingOccurrenceDate())
        runBlocking { AppStateStore.deleteContact("cB").await() }                                   // параллельно: связь снята

        val edited = s1.copy(title = "EDITED", recurrenceRule = RecurrenceMode.MONTHLY.toRRule(), recurrenceExplicitlySet = true,
            status = CalendarItemStatus.COMPLETED, links = listOf(link(id, "cA"), link(id, "cB")))
        assertEquals(true, runBlocking { persistCalendarItemEdit(ctx, true, s1, edited).await() })

        val r = room(id)
        assertEquals("EDITED", r.title)
        assertEquals("завершено occurrence ИСХОДНОЙ серии, которое видел пользователь", today.toString(), r.lastCompletedOccurrenceDate)
        assertEquals("серия остаётся ACTIVE", CalendarItemStatus.ACTIVE, r.status)
        assertEquals("новая серия применена", RecurrenceMode.MONTHLY, mode(r))
        assertEquals("удалённая параллельно связь не воскресла", setOf(CalendarTargetType.CONTACT to "cA"), linkKeys(r))
        assertNull("пара очищена", r.postponedFromOccurrenceDate)
        assertEquals("contact side-effect ровно один раз", today.toString(), AppStateStore.getContact("cA")!!.lastContactDate)
        assertMemoryEqualsRoom(id)
    }

    /** Completion в редакторе, когда событие УЖЕ завершено в другом месте: правки того же сохранения не теряются. */
    @Test
    fun EDIT_COMPLETE_currentAlreadyCompleted_keepsEditorEdits() {
        addContacts("cA")
        val id = "e-15"
        val s1 = add(event(id, type = CalendarItemType.CALL, start = today.plusDays(3), links = listOf(link(id, "cA"))))
        runBlocking { AppStateStore.markCalendarItemCompleted(cur(id)).await() }                     // завершено в другом месте

        val edited = s1.copy(title = "EDITED", status = CalendarItemStatus.COMPLETED)
        assertEquals("идемпотентно true", true, runBlocking { persistCalendarItemEdit(ctx, true, s1, edited).await() })

        assertEquals(CalendarItemStatus.COMPLETED, room(id).status)
        assertEquals("правка названия того же сохранения применена", "EDITED", room(id).title)
    }

    // ═════════════════════════════════════════════════════════════
    //  Scheduler: reschedule после commit по ИТОГОВОМУ состоянию
    // ═════════════════════════════════════════════════════════════

    /** T15: устаревшая форма (база 30 мин) сохраняет название после того, как другая сессия сменила напоминание на 60 мин. */
    @Test
    fun T15_scheduler_usesFinalMergedPersistedState_notStaleEditedObject() {
        val id = "a-15"
        val start = today.plusDays(10)
        val s1 = add(event(id, start = start, reminders = listOf(rem(id, "r30", 30))))
        NotificationScheduler.scheduleReminder(ctx, s1, s1.reminders.single())
        assertEquals("предусловие: стоит будильник по 30 мин", listOf(epoch(start.atTime(10, 0).minusMinutes(30))), alarms(id).map { it.triggerAtTime })

        val s2 = cur(id)                                                                              // другая сессия: 30 → 60 мин
        runBlocking { persistCalendarItemEdit(ctx, true, s2, s2.copy(reminders = listOf(rem(id, "r60-new", 60)))).await() }
        assertEquals("после параллельной правки — будильник по 60 мин", listOf(epoch(start.atTime(10, 0).minusMinutes(60))), alarms(id).map { it.triggerAtTime })

        // устаревшая форма: правит только название, напоминание пересобрано «как было» (30 мин, новый id)
        runBlocking { persistCalendarItemEdit(ctx, true, s1, s1.copy(title = "EDITED", reminders = listOf(rem(id, "r30-again", 30)))).await() }

        assertEquals("будильник ОДИН и по ИТОГОВОМУ напоминанию (60 мин), не по устаревшему объекту формы (30)",
            listOf(epoch(start.atTime(10, 0).minusMinutes(60))), alarms(id).map { it.triggerAtTime })
        assertEquals(setOf(listOf(ReminderType.BEFORE, 60, ReminderOffsetUnit.MINUTES)), reminderKeys(room(id)))
        assertEquals("EDITED", room(id).title)
    }

    // ═════════════════════════════════════════════════════════════
    //  Быстрые параллельные операции + guards
    // ═════════════════════════════════════════════════════════════

    /**
     * Форма S1 открыта; затем: завершение occurrence → перенос СЛЕДУЮЩЕГО occurrence → снята связь; форма сохраняет название.
     * Семантика зафиксирована: завершение очищает пару только при завершении переносимого occurrence, а здесь перенос идёт
     * ПОСЛЕ завершения (следующее occurrence = today+7) — пара валидна и сохраняется; форма не воскрешает ни старое состояние
     * занятия, ни связь.
     */
    @Test
    fun ADV_rapidIndependentOperations_thenStaleEditorSave_finalStateHasEveryLatestOperation() {
        addContacts("cA", "cB")
        val id = "r-adv"
        val s1 = add(weekly(id, links = listOf(link(id, "cA"), link(id, "cB"))))
        runBlocking { AppStateStore.markCalendarItemCompleted(cur(id), today).await() }                                   // 1. завершено today
        runBlocking { AppStateStore.postponeCalendarItemOccurrence(cur(id), today.plusWeeks(1), today.plusWeeks(1).plusDays(2)).await() } // 2. перенос today+7
        runBlocking { AppStateStore.deleteContact("cB").await() }                                                         // 3. связь B снята

        edit(s1, s1.copy(title = "EDITED"))                                                                               // 4. форма S1: название

        val r = room(id)
        assertEquals("EDITED", r.title)
        assertEquals("последнее завершение", today.toString(), r.lastCompletedOccurrenceDate)
        assertEquals("валидный перенос", today.plusWeeks(1).toString() to today.plusWeeks(1).plusDays(2).toString(),
            r.postponedFromOccurrenceDate to r.postponedToDate)
        assertEquals("актуальные связи", setOf(CalendarTargetType.CONTACT to "cA"), linkKeys(r))
        assertEquals(CalendarItemStatus.ACTIVE, r.status)
        assertMemoryEqualsRoom(id)
    }

    /** Dedicated writers (completion/postpone) применяются к КАНОНИЧЕСКОМУ состоянию, а не к снимку, который им передали. */
    @Test
    fun DEDICATED_completionAndPostpone_applyToCanonicalState_notToCallerSnapshot() {
        addContacts("cA", "cB")
        val a = add(event("d-1", start = today.plusDays(4), links = listOf(link("d-1", "cA"), link("d-1", "cB")), reminders = listOf(rem("d-1", "r30", 30))))
        edit(a, a.copy(links = listOf(link("d-1", "cA")), reminders = emptyList()))          // независимо: связь B и напоминание сняты
        runBlocking { AppStateStore.markCalendarItemCompleted(a).await() }                    // завершению передан УСТАРЕВШИЙ снимок a
        assertEquals(CalendarItemStatus.COMPLETED, room("d-1").status)
        assertEquals("связь B не воскресла", setOf(CalendarTargetType.CONTACT to "cA"), linkKeys(room("d-1")))
        assertTrue("напоминание не воскресло", room("d-1").reminders.isEmpty())

        val w = add(weekly("d-2", links = listOf(link("d-2", "cA"), link("d-2", "cB"))))
        edit(w, w.copy(links = listOf(link("d-2", "cA"))))
        runBlocking { AppStateStore.postponeCalendarItemOccurrence(w, today, today.plusDays(2)).await() }
        assertEquals(today.toString() to today.plusDays(2).toString(), room("d-2").let { it.postponedFromOccurrenceDate to it.postponedToDate })
        assertEquals("связь B не воскресла", setOf(CalendarTargetType.CONTACT to "cA"), linkKeys(room("d-2")))
    }

    /**
     * Форма показывает не всё, что есть у события (контакты + ОДНА компания; шесть стандартных напоминаний): невидимое ей
     * состояние не должно «удаляться» несвязанным сохранением — редактор переносит его как есть.
     */
    @Test
    fun EDITOR_carriesStateTheFormCannotShow_unrelatedSaveDoesNotDropIt() {
        addContacts("cA")
        runBlocking {
            AppStateStore.addCompany(Company("co1", "Acme", industry = Industry.IT, createdAt = t0, updatedAt = t0)).await()
            AppStateStore.addCompany(Company("co2", "Beta", industry = Industry.IT, createdAt = t0, updatedAt = t0)).await()
        }
        val id = "ed-1"
        fun co(company: String) = CalendarItemLink(UUID.randomUUID().toString(), id, CalendarTargetType.COMPANY, company)
        val custom = ReminderRule("r-custom", id, ReminderType.CUSTOM_DATE_TIME, null, null, today.plusDays(9).atTime(9, 0).toString())
        val odd = ReminderRule("r-odd", id, ReminderType.BEFORE, 2, ReminderOffsetUnit.HOURS, null)
        val s1 = add(event(id, links = listOf(link(id, "cA"), co("co1"), co("co2"),
            CalendarItemLink("l-gift", id, CalendarTargetType.GIFT, "g1")), reminders = listOf(rem(id, "r30", 30), custom, odd)))

        // «Готово» формы: контакты + ПЕРВАЯ компания, напоминание «за 30 мин» (единственное представимое), новые id
        val edited = s1.copy(title = "EDITED", links = listOf(link(id, "cA"), co("co1")), reminders = listOf(rem(id, "r30-new", 30)))
        runBlocking { persistCalendarItemEdit(ctx, true, s1, edited).await() }

        val r = room(id)
        assertEquals("EDITED", r.title)
        assertEquals("связи с подарком и второй компанией не потеряны",
            setOf(CalendarTargetType.CONTACT to "cA", CalendarTargetType.COMPANY to "co1", CalendarTargetType.COMPANY to "co2",
                CalendarTargetType.GIFT to "g1"), linkKeys(r))
        assertEquals("нестандартные напоминания не потеряны", setOf("r30", "r-custom", "r-odd"), r.reminders.map { it.id }.toSet())
    }

    /** Undo слияния возвращает событию только связи: завершение в окне отмены не откатывается, удалённое событие не воскресает. */
    @Test
    fun UNDO_mergeUndo_restoresOnlyLinks_notCompletion_norDeletedEvent() {
        addContacts("cA", "cB")
        val kept = add(event("u-1", links = listOf(link("u-1", "cB"))))
        val doomed = add(event("u-2", links = listOf(link("u-2", "cB"))))
        val snapshot = AppStateStore.mergeContacts(listOf("cA", "cB"))
        assertTrue("слияние выполнено", snapshot != null)
        runBlocking { AppStateStore.awaitPendingWrites() }

        runBlocking { AppStateStore.markCalendarItemCompleted(cur("u-1")).await() }              // в окне отмены: завершено
        runBlocking { AppStateStore.deleteCalendarItem("u-2").await() }                           // и удалено другое событие
        runBlocking { AppStateStore.undoMerge(snapshot!!).await() }

        assertEquals("завершение не откачено undo", CalendarItemStatus.COMPLETED, room("u-1").status)
        assertEquals("связь возвращена к исходному контакту", setOf(CalendarTargetType.CONTACT to "cB"), linkKeys(room("u-1")))
        assertTrue("удалённое событие не воскресло",
            runBlocking { db.calendarDao().getAllCalendarItems() }.none { it.id == doomed.id })
        assertEquals("kept", kept.id, cur("u-1").id)
    }

    /** Guard: у КАЖДОГО свойства CalendarItem есть правило слияния — забытое поле молча вернулось бы к «побеждает edited». */
    @Test
    fun mergeRule_existsForEveryCalendarItemProperty() {
        // aggregate/владельцы с собственными поведенческими тестами выше
        val special = setOf("id", "updatedAt", "links", "reminders", "recurrenceRule", "recurrenceExplicitlySet",
            "lastCompletedOccurrenceDate", "postponedFromOccurrenceDate", "postponedToDate")
        val base = event("g")
        val checked = mutableSetOf<String>()
        for (p in CalendarItem::class.memberProperties.filter { it.name !in special }) {
            val v = p.get(base)
            val other: Any = when (v) {
                null -> "x"
                is String -> "$v-x"
                is Boolean -> !v
                is Enum<*> -> v.javaClass.enumConstants.first { it != v }
                else -> fail("${p.name}: тест не знает тип ${v.javaClass} — добавить правило слияния и вариант") as Nothing
            }
            val copy = CalendarItem::class.memberFunctions.first { it.name == "copy" }
            fun withProp(b: CalendarItem, value: Any?): CalendarItem {
                val args = mutableMapOf<KParameter, Any?>(copy.parameters.first() to b)
                args[copy.parameters.first { it.name == p.name }] = value
                return copy.callBy(args) as CalendarItem
            }
            // (a) форма поле не меняла (edited == base) → побеждает текущее (чужое) значение
            assertEquals("${p.name}: не менявшееся поле остаётся текущим", other, p.get(CalendarEditMerge.merge(withProp(base, other), base, base)))
            // (b) пользователь изменил → побеждает edited
            assertEquals("${p.name}: намеренная правка применяется", other, p.get(CalendarEditMerge.merge(base, base, withProp(base, other))))
            checked += p.name
        }
        assertEquals("каждое свойство либо проверено здесь, либо в special", CalendarItem::class.memberProperties.map { it.name }.toSet(), checked + special)
    }

    /** Guard API: одноаргументного `updateCalendarItem(item)` («замени всё») больше нет. */
    @Test
    fun updateCalendarItem_hasNoWholeObjectOverload() {
        val overloads = AppStateStore::class.memberFunctions.filter { it.name == "updateCalendarItem" }
        assertTrue(overloads.isNotEmpty())
        assertFalse("остались перегрузки без base: ${overloads.map { it.parameters.size }}", overloads.any { it.parameters.size == 2 })
    }
}
