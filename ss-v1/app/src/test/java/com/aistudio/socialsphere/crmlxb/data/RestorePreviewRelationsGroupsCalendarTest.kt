package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toEntity
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

/**
 * RESTORE PREVIEW REMAINING DATA COVERAGE (2026-09-24) — закрывает SS-RISK-003 (Integration Gate #2):
 * [RestoreDiffEngine.analyze] раньше не сканировал `contactRelations`/`groups`/`groupMembers` вовсе —
 * backup, несущий связь между людьми или членство в группе, отсутствующие в current, НИКОГДА не появлялся
 * как recoverable Missing на непустой БД (единственный путь после Restore Preview Pack). Плюс отдельный,
 * независимо подтверждённый preflight-пропуск: недостающие reminders/links СУЩЕСТВУЮЩЕГО CalendarItem (не
 * whole-missing — те уже едут атомарно) тоже никогда не диффились.
 *
 * T1..T10 — литеральный список требований владельца. Пure-логика (T1/T3-часть/T4-T6/T7-часть) — без Room,
 * прямой вызов analyze()/buildApplyPlan() на построенных вручную PersistedState/RestorePlan (тот же паттерн,
 * что [RestoreDiffEngineTest]). Room round-trip (T2/T7-часть/T8/T9/T10) — Robolectric, тот же паттерн, что
 * [RestorePreviewIntegrationTest].
 */
@RunWith(RobolectricTestRunner::class)
class RestorePreviewRelationsGroupsCalendarTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase
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
        AppStateStore.restoreMidTransactionHookForTests = null
        AppStateStore.initialize(ctx, db)
        waitLoaded()
    }

    @After
    fun tearDown() {
        AppStateStore.restoreMidTransactionHookForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.writeGateForTests?.complete(Unit)
        AppStateStore.writeGateForTests = null
        clearMemory()
        db.close()
    }

    // ─── обвязка (тот же паттерн, что RestorePreviewIntegrationTest) ────────────────

    private fun clearMemory() {
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear()
        AppStateStore.gifts.clear(); AppStateStore.addresses.clear(); AppStateStore.calendarItems.clear()
        AppStateStore.companyRelations.clear(); AppStateStore.contactRelations.clear(); AppStateStore.groups.clear()
        AppStateStore.groupMembers.clear(); AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
        AppStateStore.personalDetails.clear(); AppStateStore.sizeInfos.clear(); AppStateStore.tombstones.clear()
    }

    private fun pollUntilTrue(timeoutMs: Long = 10_000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { kotlinx.coroutines.delay(20); ok = check() }
        ok
    }

    private fun waitLoaded() {
        val settled = pollUntilTrue { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут")
    }

    private fun <T> awaitOffMainThread(timeoutMs: Long = 30_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch { try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) } }
        val settled = pollUntilTrue(timeoutMs) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); done.isCompleted }
        if (!settled) fail("suspend-операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }

    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    // ─── фабрики ────────────────────────────────────────────────

    private fun contact(id: String, first: String = "Alex", last: String = "Johnson") = Contact(
        id = id, firstName = first, lastName = last, photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t0, updatedAt = t0)

    private fun relation(id: String, first: String, second: String, firstRole: String = "Wife", secondRole: String = "Husband", note: String? = "met 2020") =
        ContactRelation(id = id, firstContactId = first, secondContactId = second, firstRole = firstRole, secondRole = secondRole, note = note)

    private fun group(id: String, name: String) = ContactGroup(id = id, name = name, createdAt = t0, updatedAt = t0)
    private fun groupMember(id: String, groupId: String, contactId: String) = ContactGroupMember(id = id, groupId = groupId, contactId = contactId)

    private fun calendarItem(id: String, title: String = "Meeting", reminders: List<ReminderRule> = emptyList(), links: List<CalendarItemLink> = emptyList()) = CalendarItem(
        id = id, title = title, type = CalendarItemType.MEETING, startDate = "2026-06-01", isAllDay = false,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, reminders = reminders, links = links,
        createdAt = t0, updatedAt = t0)
    private fun reminder(id: String, itemId: String, minutes: Int = 30) =
        ReminderRule(id = id, calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = minutes, offsetUnit = ReminderOffsetUnit.MINUTES)
    private fun link(id: String, itemId: String, targetType: CalendarTargetType, targetId: String) =
        CalendarItemLink(id = id, calendarItemId = itemId, targetType = targetType, targetId = targetId)

    private fun persisted(
        contacts: List<Contact> = emptyList(), calendarItems: List<CalendarItem> = emptyList(),
        contactRelations: List<ContactRelation> = emptyList(), groups: List<ContactGroup> = emptyList(),
        groupMembers: List<ContactGroupMember> = emptyList(),
    ) = PersistedState(
        contacts = contacts, companies = emptyList(), calendarItems = calendarItems, notes = emptyList(), gifts = emptyList(),
        companyRelations = emptyList(), contactRelations = contactRelations, addresses = emptyList(), sizeInfos = emptyList(),
        personalDetails = emptyList(), groups = groups, groupMembers = groupMembers, tags = emptyList(),
        tagMembers = emptyList(), tombstones = emptyList(), schemaVersion = 22,
    )

    // ═════════════════════════════════════════════════════════════
    //  T1 — Backup содержит ContactRelation A↔B (обе стороны уже существуют) -> Missing relation
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t1_backupOnlyRelation_bothSidesExist_shownAsMissing() {
        val current = persisted(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")))
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        val group = preview.groups.single { it.ownerId == "a" }
        val diff = group.differences.single { it.fieldKind == RestoreFieldKind.RELATION }
        assertEquals(RestoreDifferenceType.MISSING, diff.differenceType)
        assertNull(diff.currentValue)
        assertTrue("человекочитаемый label содержит имя другой стороны", diff.backupValue!!.contains("Boris"))
        assertTrue("OLD FAIL evidence: до фикса analyze() вообще не знал про contactRelations", preview.allMissingKeys.contains(diff.key))
    }

    /** Дополнение к T1 (section 5/SS-RISK-003 dependency guard): если ДРУГАЯ сторона связи не существует в
     *  current — связь НЕ предлагается (не создаём orphan-ссылку и не городим dependency-UI). */
    @Test
    fun t1b_relationToNonExistentContact_notShown_noOrphan() {
        val current = persisted(contacts = listOf(contact("a", "Anna")))   // "b" отсутствует и в current, и её нет в plan.contacts
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna")), contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        assertTrue("связь на несуществующую сторону не предлагается", preview.groups.none { g -> g.differences.any { it.fieldKind == RestoreFieldKind.RELATION } })
    }

    // ═════════════════════════════════════════════════════════════
    //  T2 (полный DB round-trip) — выбор связи -> появляется в Room с сохранением ролей и заметки
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t2_selectedRelation_persistedInRoomWithRolesAndNote() {
        addContact(contact("a", "Anna")); addContact(contact("b", "Boris")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b", firstRole = "Sister", secondRole = "Brother", note = "childhood friends")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val diff = preview.groups.single().differences.single()

        val applied = apply(preview, current, setOf(diff.key))
        assertEquals(1, applied)

        val roomRelation = roomRelations().single()
        assertEquals("a", roomRelation.firstContactId); assertEquals("b", roomRelation.secondContactId)
        assertEquals("Sister", roomRelation.firstRole); assertEquals("Brother", roomRelation.secondRole)
        assertEquals("childhood friends", roomRelation.note)
        assertEquals("память согласована с Room", setOf("r1"), AppStateStore.contactRelations.map { it.id }.toSet())
    }

    // ═════════════════════════════════════════════════════════════
    //  T3 — Пользователь НЕ выбирает связь -> она остаётся отсутствующей
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t3_unselectedRelation_remainsAbsentInRoom() {
        addContact(contact("a", "Anna")); addContact(contact("b", "Boris")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")), contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        val applied = apply(preview, current, emptySet())
        assertEquals(0, applied)
        assertTrue(roomRelations().isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T4 — Backup содержит Group G, current её не содержит -> группу можно восстановить
    //  (вместе с T6: реально проверяется через выбор membership — Group САМА по себе, без membership,
    //  не отдельно-выбираемая сущность, тем же принципом, что Tag; см. класс-комментарий).
    // ═════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════
    //  T5 — Backup содержит membership Contact A -> Group G, current её не содержит -> можно восстановить
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t5_backupOnlyMembership_shownAsMissing() {
        val current = persisted(contacts = listOf(contact("a", "Anna")), groups = listOf(group("g1", "Клиенты")))
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna")), groups = listOf(group("g1", "Клиенты")),
            groupMembers = listOf(groupMember("m1", "g1", "a")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val diff = preview.groups.single().differences.single { it.fieldKind == RestoreFieldKind.GROUP_MEMBERSHIP }
        assertEquals(RestoreDifferenceType.MISSING, diff.differenceType)
        assertEquals("Клиенты", diff.backupValue)
    }

    // ═════════════════════════════════════════════════════════════
    //  T4+T6 (полный DB round-trip) — Group G отсутствует; выбранное membership восстанавливает
    //  И membership, И саму Group как dependency (тот же принцип, что Tag/ContactTagMember).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t4t6_missingGroup_restoredAsDependencyOfSelectedMembership() {
        addContact(contact("a", "Anna")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna")), groups = listOf(group("g1", "Клиенты")),
            groupMembers = listOf(groupMember("m1", "g1", "a")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val diff = preview.groups.single().differences.single()

        val applied = apply(preview, current, setOf(diff.key))
        assertEquals(1, applied)

        assertEquals(setOf("Клиенты"), roomGroups().map { it.name }.toSet())
        val member = roomGroupMembers().single()
        assertEquals("a", member.contactId)
        assertEquals(roomGroups().single().id, member.groupId)
    }

    /** Group уже существует под ДРУГИМ id, но ТЕМ ЖЕ именем (регистронезависимо) — не плодит второй «Клиенты»
     *  (тот же принцип идентичности, что у Tag). */
    @Test
    fun groupIdentity_resolvesByNameFallback_noDuplicateGroup() {
        addContact(contact("a", "Anna")); settle()
        runBlocking { db.contactDao().insertContactGroup(group("existing-g", "клиенты").toEntity()) }
        AppStateStore.groups.add(group("existing-g", "клиенты")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna")), groups = listOf(group("backup-g", "Клиенты")),
            groupMembers = listOf(groupMember("m1", "backup-g", "a")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val diff = preview.groups.single().differences.single()

        apply(preview, current, setOf(diff.key))

        assertEquals("группа не задублирована по имени", 1, roomGroups().size)
        assertEquals("existing-g", roomGroups().single().id)
        assertEquals("existing-g", roomGroupMembers().single().groupId)
    }

    // ═════════════════════════════════════════════════════════════
    //  T7 — Restore All Missing включает новые типы; не заменяет существующие поля
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t7_restoreAllMissing_includesNewTypes_doesNotTouchUnrelatedExisting() {
        addContact(contact("a", "Anna").copy(profession = "Existing profession"))
        addContact(contact("b", "Boris")); addCalendarItem(calendarItem("ev1")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(
            contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")),
            groups = listOf(group("g1", "VIP")), groupMembers = listOf(groupMember("m1", "g1", "a")),
            calendarItems = listOf(calendarItem("ev1", reminders = listOf(reminder("rem1", "ev1")),
                links = listOf(link("lnk1", "ev1", CalendarTargetType.CONTACT, "a")))),
        )
        val preview = RestoreDiffEngine.analyze(current, plan)

        // ТА ЖЕ логика, что кнопка "Select all missing" (RestorePreviewScreen.kt): allMissingKeys.
        val applied = apply(preview, current, preview.allMissingKeys)
        assertTrue("все 4 новых типа применились", applied >= 4)

        assertEquals(setOf("r1"), roomRelations().map { it.id }.toSet())
        assertEquals(setOf("VIP"), roomGroups().map { it.name }.toSet())
        assertEquals(1, roomGroupMembers().size)
        assertEquals(setOf("rem1"), roomReminderIds("ev1"))
        assertEquals(setOf("lnk1"), roomLinkIds("ev1"))
        // существующее поле контакта A, не участвующее ни в одной differences, не тронуто
        assertEquals("Existing profession", runBlocking { db.contactDao().getAllContacts() }.single { it.id == "a" }.profession)
    }

    // ═════════════════════════════════════════════════════════════
    //  T8 — Повторный restore не создаёт дубликатов
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t8_repeatedApplication_createsNoDuplicates() {
        addContact(contact("a", "Anna")); addContact(contact("b", "Boris"))
        addCalendarItem(calendarItem("ev1")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(
            contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")),
            groups = listOf(group("g1", "VIP")), groupMembers = listOf(groupMember("m1", "g1", "a")),
            calendarItems = listOf(calendarItem("ev1", reminders = listOf(reminder("rem1", "ev1")))),
        )
        val preview = RestoreDiffEngine.analyze(current, plan)
        val keys = preview.allMissingKeys

        apply(preview, current, keys)
        apply(preview, current, keys)   // тот же (устаревший) preview, тот же выбор — повторно

        assertEquals(1, roomRelations().size)
        assertEquals(1, roomGroups().size)
        assertEquals(1, roomGroupMembers().size)
        assertEquals(1, roomReminderIds("ev1").size)
    }

    // ═════════════════════════════════════════════════════════════
    //  T9 — Ошибка в середине restore откатывает ВСЕ выбранные изменения (atomic rollback)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t9_atomicFailure_rollsBackRelationsGroupsAndCalendarChildren() {
        addContact(contact("a", "Anna")); addContact(contact("b", "Boris"))
        addCalendarItem(calendarItem("ev1")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(
            contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")),
            groups = listOf(group("g1", "VIP")), groupMembers = listOf(groupMember("m1", "g1", "a")),
            calendarItems = listOf(calendarItem("ev1", reminders = listOf(reminder("rem1", "ev1")))),
        )
        val preview = RestoreDiffEngine.analyze(current, plan)

        AppStateStore.restoreMidTransactionHookForTests = { throw java.io.IOException("simulated mid-restore failure") }
        val threw = try { apply(preview, current, preview.allMissingKeys); false } catch (e: Exception) { true }
        AppStateStore.restoreMidTransactionHookForTests = null

        assertTrue("сбой обязан пробрасывать исключение", threw)
        assertTrue("relation не применилась частично", roomRelations().isEmpty())
        assertTrue("group не применилась частично", roomGroups().isEmpty())
        assertTrue("reminder не применился частично", roomReminderIds("ev1").isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T10 — После reload из Room отношения, группы и memberships остаются на месте
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t10_afterReloadFromRoom_relationsGroupsMembershipsSurvive() {
        addContact(contact("a", "Anna")); addContact(contact("b", "Boris")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")),
            groups = listOf(group("g1", "VIP")), groupMembers = listOf(groupMember("m1", "g1", "a")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        apply(preview, current, preview.allMissingKeys)

        awaitOffMainThread { AppStateStore.reloadFromDb() }

        assertEquals(setOf("r1"), AppStateStore.contactRelations.map { it.id }.toSet())
        assertEquals(setOf("VIP"), AppStateStore.groups.map { it.name }.toSet())
        assertEquals(1, AppStateStore.groupMembers.size)
    }

    // ═════════════════════════════════════════════════════════════
    //  CALENDAR — reminders/links СУЩЕСТВУЮЩЕГО события: preflight OLD FAIL + fix
    // ═════════════════════════════════════════════════════════════

    /** OLD FAIL evidence (до фикса): analyze() для СУЩЕСТВУЮЩЕГО CalendarItem диффил только TITLE/DESCRIPTION —
     *  недостающее напоминание backup никогда не появлялось. */
    @Test
    fun calendar_missingReminderOfExistingItem_shownAsMissing_notEquivalentToWholeMissing() {
        val current = persisted(calendarItems = listOf(calendarItem("ev1")))   // событие СУЩЕСТВУЕТ, без напоминаний
        val plan = RestorePlan(calendarItems = listOf(calendarItem("ev1", reminders = listOf(reminder("rem1", "ev1")))))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val group = preview.groups.single()
        assertFalse("событие существует — НЕ whole-missing путь", group.wholeEntityMissing)
        val diff = group.differences.single { it.fieldKind == RestoreFieldKind.CALENDAR_REMINDER }
        assertEquals(RestoreDifferenceType.MISSING, diff.differenceType)
    }

    @Test
    fun calendar_missingLinkOfExistingItem_onlyWhenTargetExistsInCurrent() {
        val currentWithContact = persisted(contacts = listOf(contact("a", "Anna")), calendarItems = listOf(calendarItem("ev1")))
        val planWithLink = RestorePlan(contacts = listOf(contact("a", "Anna")),
            calendarItems = listOf(calendarItem("ev1", links = listOf(link("lnk1", "ev1", CalendarTargetType.CONTACT, "a")))))
        val previewOk = RestoreDiffEngine.analyze(currentWithContact, planWithLink)
        val diff = previewOk.groups.single().differences.single { it.fieldKind == RestoreFieldKind.CALENDAR_LINK }
        assertTrue(diff.backupValue!!.contains("Anna"))

        // target ("a") НЕ существует в current -> orphan-защита, ссылка не предлагается
        val currentWithoutContact = persisted(calendarItems = listOf(calendarItem("ev1")))
        val previewOrphan = RestoreDiffEngine.analyze(currentWithoutContact, planWithLink)
        assertTrue("orphan-ссылка не предлагается", previewOrphan.groups.none { g -> g.differences.any { it.fieldKind == RestoreFieldKind.CALENDAR_LINK } })
    }

    // ─── DB-round-trip обвязка (тот же паттерн, что RestorePreviewIntegrationTest) ────────────────

    private fun addContact(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun addCalendarItem(c: CalendarItem) { runBlocking { AppStateStore.addCalendarItem(c).await() } }
    private fun apply(preview: RestorePreview, current: PersistedState, keys: Set<String>): Int =
        awaitOffMainThread { AppStateStore.applySelectiveRestore(RestoreDiffEngine.buildApplyPlan(preview, current, keys)) }

    private fun roomRelations(): List<ContactRelation> = runBlocking {
        db.contactDao().getContactRelations()
    }.map { ContactRelation(it.id, it.firstContactId, it.secondContactId, it.firstRole, it.secondRole, it.note) }
    private fun roomGroups(): List<ContactGroup> = runBlocking { db.contactDao().getContactGroups() }
        .map { ContactGroup(it.id, it.name, it.createdAt, it.updatedAt) }
    private fun roomGroupMembers(): List<ContactGroupMember> = runBlocking { db.contactDao().getContactGroupMembers() }
        .map { ContactGroupMember(it.id, it.groupId, it.contactId) }
    private fun roomReminderIds(calendarItemId: String): Set<String> =
        runBlocking { db.calendarDao().getReminderRules() }.filter { it.calendarItemId == calendarItemId }.map { it.id }.toSet()
    private fun roomLinkIds(calendarItemId: String): Set<String> =
        runBlocking { db.calendarDao().getCalendarItemLinks() }.filter { it.calendarItemId == calendarItemId }.map { it.id }.toSet()
}
