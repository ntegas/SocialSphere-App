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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RESTORE BATCH DEPENDENCIES & STANDALONE GROUPS MICROFIX (2026-09-24).
 *
 * Закрывает 2 остаточных gap'а, явно зафиксированных предыдущим пакетом (Restore Preview Remaining Data
 * Coverage) как "known limitation, not fixed":
 *
 * 1. ContactRelation между двумя КОНТАКТАМИ, оба whole-missing И оба выбраны В ОДНОМ apply batch — раньше
 *    dependency-проверка в [RestoreDiffEngine.buildApplyPlan] смотрела ТОЛЬКО на `current` (persisted до
 *    начала apply), не на entities, которые САМИ создаются этим же batch'ем. Фикс — `contactsInThisBatch`
 *    (curContacts ∪ выбранные-в-этом-batch whole-missing contacts), известно целиком заранее (selectedKeys
 *    передан целиком в buildApplyPlan), не зависит от порядка обхода preview.groups.
 *
 * 2. Standalone Group (backup Group БЕЗ единого membership) — [RestoreDiffEngine.analyze] сканировал
 *    ТОЛЬКО plan.groupMembers (группируя по contactId) — пустая группа никогда не посещалась ни в одной
 *    такой группировке, поэтому никогда не появлялась в preview вовсе. Фикс — отдельный проход по
 *    plan.groups, показывающий standalone MISSING-карточку ТОЛЬКО для групп без backing membership (группа
 *    С membership уже покрыта тем membership-diff'ом/его dependency — намеренно НЕ второй способ восстановить
 *    ту же группу).
 */
@RunWith(RobolectricTestRunner::class)
class RestoreBatchDependenciesMicrofixTest {

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

    // ─── обвязка (тот же паттерн, что RestorePreviewRelationsGroupsCalendarTest) ────────────────

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

    private fun apply(preview: RestorePreview, current: PersistedState, keys: Set<String>): Int =
        awaitOffMainThread { AppStateStore.applySelectiveRestore(RestoreDiffEngine.buildApplyPlan(preview, current, keys)) }

    private fun roomRelations(): List<ContactRelation> = runBlocking { db.contactDao().getContactRelations() }
        .map { ContactRelation(it.id, it.firstContactId, it.secondContactId, it.firstRole, it.secondRole, it.note) }
    private fun roomContactIds(): Set<String> = runBlocking { db.contactDao().getAllContacts() }.map { it.id }.toSet()
    private fun roomGroups(): List<ContactGroup> = runBlocking { db.contactDao().getContactGroups() }
        .map { ContactGroup(it.id, it.name, it.createdAt, it.updatedAt) }
    private fun roomGroupMembers(): List<ContactGroupMember> = runBlocking { db.contactDao().getContactGroupMembers() }
        .map { ContactGroupMember(it.id, it.groupId, it.contactId) }

    // ═════════════════════════════════════════════════════════════
    //  T1 — два whole-missing контакта + связь между ними → выбор ОБОИХ восстанавливает связь
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t1_twoMissingContactsWithRelation_selectBoth_relationRestored() {
        // Current НЕ пуста (иначе analyzeBackupForRestore ушёл бы быстрым FRESH-путём) — посторонний контакт.
        addContact(contact("cOther", "Other", "Person")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b", firstRole = "Sister", secondRole = "Brother", note = "childhood")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        val groupA = preview.groups.single { it.ownerId == "a" }
        val groupB = preview.groups.single { it.ownerId == "b" }
        assertTrue(groupA.wholeEntityMissing); assertTrue(groupB.wholeEntityMissing)

        val applied = apply(preview, current, setOf(groupA.wholeKey!!, groupB.wholeKey!!))
        assertTrue("A, B и связь применились", applied >= 3)

        assertEquals(setOf("cOther", "a", "b"), roomContactIds())
        val r = roomRelations().single()
        assertEquals("a", r.firstContactId); assertEquals("b", r.secondContactId)
        assertEquals("Sister", r.firstRole); assertEquals("childhood", r.note)
    }

    // ═════════════════════════════════════════════════════════════
    //  T2 — выбран только ОДИН из двух missing-контактов → связь НЕ восстанавливается
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t2_onlyOneMissingContactSelected_relationNotRestored() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val groupA = preview.groups.single { it.ownerId == "a" }

        apply(preview, current, setOf(groupA.wholeKey!!))

        assertEquals(setOf("cOther", "a"), roomContactIds())
        assertTrue("связь не восстановлена — B не выбран и не существует", roomRelations().isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T3 — один контакт уже существует, второй restored → связь восстанавливается
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t3_oneContactCurrent_secondRestored_relationRestored() {
        addContact(contact("a", "Anna")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val groupB = preview.groups.single { it.ownerId == "b" }
        assertTrue(groupB.wholeEntityMissing)

        apply(preview, current, setOf(groupB.wholeKey!!))

        assertEquals(setOf("a", "b"), roomContactIds())
        assertEquals(listOf("r1"), roomRelations().map { it.id })
    }

    // ═════════════════════════════════════════════════════════════
    //  T4 — Restore All Missing включает связь, когда обе стороны входят в тот же batch
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t4_restoreAllMissing_includesRelationWhenBothEndpointsIncluded() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        // Реальный вычисляемый apply plan (не просто UI-счётчик чекбоксов).
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, preview.allMissingKeys)
        assertEquals(2, applyPlan.wholeContacts.size)
        assertEquals(listOf("r1"), applyPlan.contactRelationsToAdd.map { it.id })

        apply(preview, current, preview.allMissingKeys)
        assertEquals(listOf("r1"), roomRelations().map { it.id })
    }

    // ═════════════════════════════════════════════════════════════
    //  T5 — повторный restore не создаёт дубликат связи
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t5_repeatedRestore_noDuplicateRelation() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val keys = preview.allMissingKeys

        apply(preview, current, keys)
        apply(preview, current, keys)   // тот же (устаревший) preview, тот же выбор — повторно

        assertEquals(1, roomRelations().size)
    }

    // ═════════════════════════════════════════════════════════════
    //  T6 — сбой посередине откатывает связь вместе с контактами (atomic rollback)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t6_atomicFailure_rollsBackRelationToo() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("a", "Anna"), contact("b", "Boris")),
            contactRelations = listOf(relation("r1", "a", "b")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        AppStateStore.restoreMidTransactionHookForTests = { throw java.io.IOException("simulated mid-restore failure") }
        val threw = try { apply(preview, current, preview.allMissingKeys); false } catch (e: Exception) { true }
        AppStateStore.restoreMidTransactionHookForTests = null

        assertTrue("сбой обязан пробрасывать исключение", threw)
        assertEquals(setOf("cOther"), roomContactIds())
        assertTrue("связь не применилась частично", roomRelations().isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T7 — standalone-пустая группа видна в preview
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t7_emptyGroup_visibleInPreview() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("cOther")), groups = listOf(group("g1", "Friends from Greece")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        val groupCard = preview.groups.single { it.ownerType == TombstoneEntityType.GROUP }
        assertTrue(groupCard.wholeEntityMissing)
        assertEquals("Friends from Greece", groupCard.ownerDisplayName)
        assertTrue("OLD FAIL evidence: до фикса analyze() вообще не сканировал standalone-группы", preview.allMissingKeys.contains(groupCard.wholeKey))
    }

    // ═════════════════════════════════════════════════════════════
    //  T8 — выбранная пустая группа восстанавливается
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t8_selectedEmptyGroup_restored() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("cOther")), groups = listOf(group("g1", "Friends from Greece")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val groupCard = preview.groups.single { it.ownerType == TombstoneEntityType.GROUP }

        val applied = apply(preview, current, setOf(groupCard.wholeKey!!))
        assertEquals(1, applied)

        val roomGroup = roomGroups().single()
        assertEquals("g1", roomGroup.id); assertEquals("Friends from Greece", roomGroup.name)
        assertTrue("zero members — валидно, фиктивное membership не требуется", roomGroupMembers().isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T9 — невыбранная пустая группа остаётся отсутствующей
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t9_unselectedEmptyGroup_remainsAbsent() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("cOther")), groups = listOf(group("g1", "Friends from Greece")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        val applied = apply(preview, current, emptySet())
        assertEquals(0, applied)
        assertTrue(roomGroups().isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T10 — Group + missing contact + membership восстанавливаются в ОДНОМ batch
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t10_groupPlusMissingContactPlusMembership_restoredInOneBatch() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("cOther"), contact("a", "Anna")),
            groups = listOf(group("g1", "VIP")), groupMembers = listOf(groupMember("m1", "g1", "a")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        // Group с membership НЕ показывается как отдельная standalone-карточка (requirement 7 — не 2 способа).
        assertTrue("группа с membership не дублирует standalone-карточку", preview.groups.none { it.ownerType == TombstoneEntityType.GROUP })
        val groupA = preview.groups.single { it.ownerId == "a" }

        val applied = apply(preview, current, setOf(groupA.wholeKey!!))
        assertTrue(applied >= 2)

        assertEquals(setOf("cOther", "a"), roomContactIds())
        assertEquals(setOf("VIP"), roomGroups().map { it.name }.toSet())
        val member = roomGroupMembers().single()
        assertEquals("a", member.contactId); assertEquals(roomGroups().single().id, member.groupId)
    }

    // ═════════════════════════════════════════════════════════════
    //  T11 — повторный restore не создаёт дубликат группы/membership
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t11_repeatedRestore_noDuplicateGroupOrMembership() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("cOther")), groups = listOf(group("g1", "Friends from Greece")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        val keys = preview.allMissingKeys

        apply(preview, current, keys)
        apply(preview, current, keys)

        assertEquals(1, roomGroups().size)
    }

    // ═════════════════════════════════════════════════════════════
    //  T12 — reload из Room сохраняет пустую группу
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t12_reload_preservesEmptyGroup() {
        addContact(contact("cOther")); settle()
        val current = runBlocking { AppStateStore.readPersistedState() }
        val plan = RestorePlan(contacts = listOf(contact("cOther")), groups = listOf(group("g1", "Friends from Greece")))
        val preview = RestoreDiffEngine.analyze(current, plan)
        apply(preview, current, preview.allMissingKeys)

        awaitOffMainThread { AppStateStore.reloadFromDb() }

        assertEquals(setOf("Friends from Greece"), AppStateStore.groups.map { it.name }.toSet())
    }

    // ─── обвязка (продолжение) ────────────────────────────────────

    private fun addContact(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
}
