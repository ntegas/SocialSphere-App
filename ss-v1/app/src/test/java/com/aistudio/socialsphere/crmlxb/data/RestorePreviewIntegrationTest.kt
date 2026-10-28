package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toEntity
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22) — DB-round-trip половина T1..T20: то, что
 * [RestoreDiffEngineTest] (чистая логика) не может проверить без Room — реальная атомарная транзакция
 * [AppStateStore.applySelectiveRestore], resync памяти, отсутствие мутаций Room до подтверждения,
 * идемпотентность повторного применения и FRESH-путь на реально пустой БД.
 *
 * Инвариант пакета: backup сам ничего не решает — CURRENT + BACKUP -> DIFFERENCE ANALYSIS -> USER
 * SELECTION -> PREVIEW -> ATOMIC APPLY. Ни одна строка не должна появиться в Room раньше явного выбора.
 */
@RunWith(RobolectricTestRunner::class)
class RestorePreviewIntegrationTest {

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

    // ─── обвязка (тот же паттерн, что ExplicitRecoveryTest/RestoreSemanticsTest) ────────────────

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

    private fun restartOnEmptyDb() {
        clearMemory(); db.close(); db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests(); AppStateStore.initialize(ctx, db); waitLoaded(); clearMemory()
    }

    private fun <T> awaitOffMainThread(timeoutMs: Long = 30_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch { try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) } }
        val settled = pollUntilTrue(timeoutMs) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); done.isCompleted }
        if (!settled) fail("suspend-операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }

    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }
    private fun backupJson(): String = runBlocking { ExportManager.backupJsonString() }
    private fun analyze(json: String) = awaitOffMainThread { ExportManager.analyzeBackupForRestore(json) }
    private fun restoreSafe(json: String) = awaitOffMainThread { ExportManager.importJsonBackupDetailed(json) }

    private fun contact(id: String, first: String = "Alex", last: String = "Johnson") = Contact(
        id = id, firstName = first, lastName = last, photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t0, updatedAt = t0)
    private fun note(id: String, cid: String, text: String) = Note(id, cid, null, null, null, NoteType.GENERAL, text, null, false, createdAt = t0, updatedAt = t0)
    private fun gift(id: String, cid: String, title: String = "Whisky") = GiftIdea(id, cid, title, null, null, null, GiftStatus.IDEA)
    private fun phone(id: String, cid: String, number: String) = ContactPhone(id, cid, number, PhoneType.MOBILE, isPrimary = true)
    private fun company(id: String, name: String = "Acme") = Company(id = id, name = name, industry = Industry.IT, createdAt = t0, updatedAt = t0)
    /** Заметка компании (contactId=null, companyId=id) — та же позиционная форма, что [note] для контакта. */
    private fun companyNote(id: String, companyId: String, text: String) = Note(id, null, companyId, null, null, NoteType.GENERAL, text, null, false, createdAt = t0, updatedAt = t0)

    private fun addContact(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun addCompany(c: Company) { runBlocking { AppStateStore.addCompany(c).await() } }
    private fun addNote(n: Note) { runBlocking { AppStateStore.addNote(n).await() } }
    private fun deleteNote(id: String) { runBlocking { AppStateStore.deleteNote(id).await() } }

    // ── TAGS BACKUP RECOVERY PACK: теги в Room — напрямую через DAO, в обход AppStateStore.addTag()
    // (который сам генерирует id) — фикстурам нужны УСТОЙЧИВЫЕ id, совпадающие/расходящиеся с backup намеренно.
    private fun tag(id: String, name: String) = Tag(id = id, name = name, createdAt = t0, updatedAt = t0)
    private fun tagMember(id: String, tagId: String, contactId: String) = ContactTagMember(id = id, tagId = tagId, contactId = contactId)
    private fun addTagRoom(t: Tag) { runBlocking { db.contactDao().insertTag(t.toEntity()) } }
    private fun addTagMemberRoom(m: ContactTagMember) { runBlocking { db.contactDao().insertContactTagMembers(listOf(m.toEntity())) } }
    private fun roomTagIds(): Set<String> = runBlocking { db.contactDao().getTagsOnce() }.map { it.id }.toSet()
    private fun roomTagNamesFor(contactId: String): Set<String> {
        val tagsById = runBlocking { db.contactDao().getTagsOnce() }.associateBy { it.id }
        return runBlocking { db.contactDao().getContactTagMembersOnce() }
            .filter { it.contactId == contactId }.mapNotNull { tagsById[it.tagId]?.name }.toSet()
    }

    private fun roomNoteIds(): Set<String> = runBlocking { db.noteDao().getAllNotes() }.map { it.id }.toSet()
    private fun roomGiftIds(): Set<String> = runBlocking { db.giftDao().getAllGifts() }.map { it.id }.toSet()
    private fun roomContactIds(): Set<String> = runBlocking { db.contactDao().getAllContacts() }.map { it.id }.toSet()
    private fun roomCompanyIds(): Set<String> = runBlocking { db.companyDao().getAllCompanies() }.map { it.id }.toSet()
    private fun roomPhoneNumbers(cid: String): Set<String> =
        runBlocking { db.contactDao().getContactPhones() }.filter { it.contactId == cid }.map { it.number }.toSet()
    private fun roomTombstoneKeys(): Set<TombstoneKey> =
        runBlocking { db.tombstoneDao().getAll() }.map { TombstoneKey(TombstoneEntityType.valueOf(it.entityType), it.entityId) }.toSet()

    private fun previewOf(json: String): RestorePreview =
        (analyze(json) as? ExportManager.RestoreAnalysisOutcome.Preview)?.preview ?: fail("ожидался Preview") as Nothing

    private fun apply(preview: RestorePreview, keys: Set<String>): Int =
        awaitOffMainThread { ExportManager.applyRestoreSelection(preview, keys) }

    // ═════════════════════════════════════════════════════════════
    //  T10 (полный DB round-trip) — выбор tombstoned-заметки восстанавливает её И снимает tombstone
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t10_selectedDeletedNote_restoredInRoom_tombstoneCleared() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "старая заметка"))
        settle()
        val json = backupJson()
        deleteNote("n1")
        settle()
        assertTrue(roomNoteIds().isEmpty())
        assertEquals(setOf(TombstoneKey(TombstoneEntityType.NOTE, "n1")), roomTombstoneKeys())

        val preview = previewOf(json)
        val diff = preview.groups.single().differences.single()
        assertTrue(diff.previouslyDeleted)
        val applied = apply(preview, setOf(diff.key))

        assertEquals(1, applied)
        assertEquals(setOf("n1"), roomNoteIds())
        assertTrue("tombstone снят вместе с восстановлением", roomTombstoneKeys().isEmpty())
        assertEquals("память согласована с Room (resyncMemoryFromRoom)", setOf("n1"), AppStateStore.notes.map { it.id }.toSet())
    }

    // ═════════════════════════════════════════════════════════════
    //  T11 (полный DB round-trip) — невыбранная удалённая заметка остаётся удалённой
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t11_unselectedDeletedNote_remainsDeletedInRoom() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "текст"))
        settle()
        val json = backupJson()
        deleteNote("n1")
        settle()

        val preview = previewOf(json)
        val applied = apply(preview, emptySet())

        assertEquals(0, applied)
        assertTrue(roomNoteIds().isEmpty())
        assertEquals(setOf(TombstoneKey(TombstoneEntityType.NOTE, "n1")), roomTombstoneKeys())
    }

    // ═════════════════════════════════════════════════════════════
    //  T12 (полный DB round-trip) — полностью отсутствующий Contact восстанавливается СО ВСЕМИ
    //  дочерними записями атомарно (регресс-тест на найденный и исправленный в этом же пакете баг:
    //  restoreContactInTransaction/addContactDb не писали nested notes/gifts).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t12_missingContact_restoredWithDependencies_persistedInRoom_noOrphan() {
        // Текущая БД НЕ пуста (иначе analyzeBackupForRestore ушёл бы быстрым EmptyDatabase-путём,
        // а не в preview) — держим посторонний контакт, не участвующий в backup ниже (requirement 26).
        addContact(contact("cOther", "Other", "Person"))
        settle()

        val bak = contact("c1").copy(notes = listOf(note("n1", "c1", "заметка")), gifts = listOf(gift("g1", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val group = preview.groups.single { it.ownerId == "c1" }
        assertTrue(group.wholeEntityMissing)
        val applied = apply(preview, setOf(group.wholeKey!!))

        assertTrue(applied >= 1)
        assertEquals(setOf("cOther", "c1"), roomContactIds())
        assertEquals("заметка восстановлена ВМЕСТЕ с контактом, не потеряна", setOf("n1"), roomNoteIds())
        assertEquals("подарок восстановлен вместе с контактом", setOf("g1"), roomGiftIds())
        assertEquals("подарок реально ссылается на восстановленного родителя, не сирота",
            "c1", runBlocking { db.giftDao().getAllGifts() }.single().contactId)
    }

    // ═════════════════════════════════════════════════════════════
    //  T16 — сбой посередине атомарного apply откатывает ВСЁ (и данные, и историю удаления)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t16_atomicFailure_leavesRoomCompletelyUnchanged() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "старая заметка"))
        settle()
        val json = backupJson()
        deleteNote("n1")
        settle()
        val beforeNoteIds = roomNoteIds()
        val beforeTombstones = roomTombstoneKeys()
        assertTrue(beforeNoteIds.isEmpty())

        val preview = previewOf(json)
        val diff = preview.groups.single().differences.single()

        AppStateStore.restoreMidTransactionHookForTests = { throw java.io.IOException("simulated mid-restore failure") }
        val threw = try { apply(preview, setOf(diff.key)); false } catch (e: Exception) { true }
        AppStateStore.restoreMidTransactionHookForTests = null

        assertTrue("сбой обязан пробрасывать исключение вызывающему", threw)
        assertEquals("ни одна выбранная запись не применилась частично", beforeNoteIds, roomNoteIds())
        assertEquals("история удаления цела — restore не 'наполовину применился'", beforeTombstones, roomTombstoneKeys())
        val resynced = pollUntilTrue { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); AppStateStore.writeFailureCount > 0 }
        assertTrue("память тоже не разошлась с Room после сбоя", resynced)
        assertEquals(beforeNoteIds, AppStateStore.notes.map { it.id }.toSet())
    }

    // ═════════════════════════════════════════════════════════════
    //  T17 — повторное применение того же выбора не плодит дублей
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t17_repeatedApplication_createsNoDuplicates() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "текст"))
        settle()
        val json = backupJson()
        deleteNote("n1")
        settle()

        val preview = previewOf(json)
        val diff = preview.groups.single().differences.single()
        val keys = setOf(diff.key)

        val applied1 = apply(preview, keys)
        assertEquals(1, applied1)
        assertEquals(setOf("n1"), roomNoteIds())

        // тот же (устаревший) preview и тот же выбор — применяем повторно
        apply(preview, keys)

        assertEquals("одна заметка, не дубль", 1, runBlocking { db.noteDao().getAllNotes() }.size)
        assertEquals(setOf("n1"), roomNoteIds())
    }

    // ═════════════════════════════════════════════════════════════
    //  T18 — по-настоящему пустая БД по-прежнему поддерживает быстрый полный restore (FRESH)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t18_freshDb_stillSupportsFullRestore_withoutManualPreviewSelection() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "заметка"))
        settle()
        val json = backupJson()

        restartOnEmptyDb()

        val outcome = analyze(json)
        assertTrue("клин-инсталл -> быстрый путь, не preview с сотнями строк",
            outcome is ExportManager.RestoreAnalysisOutcome.EmptyDatabase)

        val result = (restoreSafe(json) as ExportManager.BackupImportOutcome.Restored).result
        assertEquals(RestoreMode.FRESH, result.mode)
        assertEquals(setOf("c1"), roomContactIds())
        assertEquals(setOf("n1"), roomNoteIds())
    }

    // ═════════════════════════════════════════════════════════════
    //  T19 — анализ backup НИКОГДА не мутирует Room до явного подтверждения
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t19_analyzeBackupForRestore_neverMutatesRoomBeforeConfirmation() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "старая"))
        settle()
        val json = backupJson()
        // текущая БД теперь РАСХОДИТСЯ с backup — второй контакт, изменённая заметка удалена
        addContact(contact("c2", "Nina", "Petrova"))
        deleteNote("n1")
        settle()
        val beforeContacts = roomContactIds()
        val beforeNotes = roomNoteIds()
        val beforeTombstones = roomTombstoneKeys()

        val outcome = analyze(json)
        assertTrue(outcome is ExportManager.RestoreAnalysisOutcome.Preview)
        val preview = (outcome as ExportManager.RestoreAnalysisOutcome.Preview).preview
        assertTrue("есть о чём показать preview — иначе тест ничего не проверяет", preview.groups.isNotEmpty())

        assertEquals("Room не тронут одним лишь анализом", beforeContacts, roomContactIds())
        assertEquals(beforeNotes, roomNoteIds())
        assertEquals(beforeTombstones, roomTombstoneKeys())
    }

    // ═════════════════════════════════════════════════════════════
    //  T20 (полный round-trip) — итоговая сводка соответствует РЕАЛЬНО применённым операциям
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t20_summary_matchesActuallyAppliedOperations_endToEnd() {
        addContact(contact("c1").copy(profession = "Sales Manager"))
        settle()

        // «backup» — не JSON-раунд-трип (несущественно для этого теста), а прямой RestorePlan с
        // расходящимся профилем (CONFLICT) и лишним телефоном (MISSING) — ровно две разницы.
        val backupContact = contact("c1").copy(profession = "Account Executive", phones = listOf(phone("p1", "c1", "+301111111")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(backupContact)))
        val allKeys = preview.groups.single().differences.map { it.key }.toSet()
        assertEquals("телефон MISSING + профессия CONFLICT", 2, allKeys.size)
        val summaryBefore = RestoreDiffEngine.summarize(preview, allKeys)

        val applied = apply(preview, allKeys)

        assertEquals("применено ровно столько операций, сколько сводка обещала",
            summaryBefore.additions + summaryBefore.replacements, applied)
        assertEquals("Account Executive", AppStateStore.getContactById("c1")!!.profession)
        assertEquals(setOf("+301111111"), roomPhoneNumbers("c1"))
    }

    // ═════════════════════════════════════════════════════════════
    //  COMPANY NOTES RESTORE MICROFIX (2026-09-23) — finding F1 из Data Safety Residual Audit:
    //  buildApplyPlan для ПОЛНОСТЬЮ отсутствующей компании обещал заметки в preview
    //  (childSummaryLines/summarize), но не включал их в standaloneNotes — они не восстанавливались.
    // ═════════════════════════════════════════════════════════════

    /** T1 (fixture из задачи) + T3 (idempotency): восстановление отсутствующей компании возвращает
     *  ОБЕ её заметки, они привязаны именно к ней, посторонняя компания D/её заметка N3 не тронуты,
     *  повторное применение того же (устаревшего) preview не плодит дублей. */
    @Test
    fun companyRestore_wholeCompany_restoresBothNotes_andIsIdempotentOnReapply() {
        // Текущая БД непуста (иначе analyzeBackupForRestore ушёл бы быстрым EmptyDatabase-путём) —
        // посторонняя компания D с собственной заметкой N3, не участвующая в backup.
        addCompany(company("D", "Acme D"))
        addNote(companyNote("n3", "D", "заметка D — не трогать"))
        settle()

        val bak = RestorePlan(
            companies = listOf(company("C", "Acme C")),
            notes = listOf(companyNote("n1", "C", "заметка 1"), companyNote("n2", "C", "заметка 2")),
        )
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)

        val group = preview.groups.single { it.ownerId == "C" }
        assertTrue("компания целиком отсутствует", group.wholeEntityMissing)
        assertEquals("preview ОБЕЩАЕТ обе заметки в информационных строках",
            listOf("+ Note: заметка 1", "+ Note: заметка 2"), group.childSummaryLines)

        val applied1 = apply(preview, setOf(group.wholeKey ?: fail("wholeKey должен существовать для missing-владельца") as Nothing))

        assertTrue(applied1 >= 1)
        assertEquals(setOf("C", "D"), roomCompanyIds())
        assertEquals("обе заметки компании реально записаны в Room", setOf("n1", "n2", "n3"), roomNoteIds())
        val restoredNotes = runBlocking { db.noteDao().getAllNotes() }.filter { it.id == "n1" || it.id == "n2" }
        assertEquals("заметки привязаны именно к восстановленной компании C", setOf("C"), restoredNotes.map { it.companyId }.toSet())
        assertEquals("посторонняя заметка N3 всё ещё принадлежит компании D",
            "D", runBlocking { db.noteDao().getAllNotes() }.single { it.id == "n3" }.companyId)

        // T3: повторное восстановление того же (устаревшего) preview/выбора — не плодит дублей.
        apply(preview, setOf(group.wholeKey ?: fail("wholeKey") as Nothing))
        assertEquals("повторное восстановление не создаёт дублей", 3, runBlocking { db.noteDao().getAllNotes() }.size)
        assertEquals(setOf("n1", "n2", "n3"), roomNoteIds())
    }

    /** T2: невыбранная компания и её заметки не восстанавливаются; посторонняя компания/заметка целы. */
    @Test
    fun companyRestore_unselectedCompany_notesNotRestored() {
        addCompany(company("D", "Acme D"))
        addNote(companyNote("n3", "D", "заметка D"))
        settle()

        val bak = RestorePlan(
            companies = listOf(company("C", "Acme C")),
            notes = listOf(companyNote("n1", "C", "заметка 1"), companyNote("n2", "C", "заметка 2")),
        )
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)

        val applied = apply(preview, emptySet())

        assertEquals(0, applied)
        assertEquals(setOf("D"), roomCompanyIds())
        assertEquals(setOf("n3"), roomNoteIds())
    }

    /** T4: сбой посередине атомарного apply (после вставки компании, до вставки её заметок —
     *  restoreMidTransactionHookForTests срабатывает ровно на этой границе) откатывает ВСЁ,
     *  компания не остаётся частично восстановленной без своих заметок. */
    @Test
    fun companyRestore_atomicFailure_leavesRoomCompletelyUnchanged() {
        addCompany(company("D", "Acme D"))
        addNote(companyNote("n3", "D", "заметка D"))
        settle()
        val beforeCompanies = roomCompanyIds()
        val beforeNotes = roomNoteIds()

        val bak = RestorePlan(
            companies = listOf(company("C", "Acme C")),
            notes = listOf(companyNote("n1", "C", "заметка 1"), companyNote("n2", "C", "заметка 2")),
        )
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val group = preview.groups.single { it.ownerId == "C" }

        AppStateStore.restoreMidTransactionHookForTests = { throw java.io.IOException("simulated mid-restore failure") }
        val threw = try {
            apply(preview, setOf(group.wholeKey ?: fail("wholeKey") as Nothing)); false
        } catch (e: Exception) { true }
        AppStateStore.restoreMidTransactionHookForTests = null

        assertTrue("сбой обязан пробрасывать исключение вызывающему", threw)
        assertEquals("компания не осталась частично восстановленной", beforeCompanies, roomCompanyIds())
        assertEquals("заметки компании не сохранились отдельно от отката компании", beforeNotes, roomNoteIds())
    }

    /** T6: отдельный выбор ОДНОЙ заметки СУЩЕСТВУЮЩЕЙ компании (не whole-entity ветка, её фикс не
     *  трогал) продолжает работать как раньше. */
    @Test
    fun companyRestore_existingCompany_individualNoteSelection_stillWorks() {
        addCompany(company("C", "Acme C"))
        settle()

        val bak = RestorePlan(companies = listOf(company("C", "Acme C")), notes = listOf(companyNote("n1", "C", "заметка")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val group = preview.groups.single { it.ownerId == "C" }
        assertFalse("компания уже существует — это НЕ whole-missing ветка", group.wholeEntityMissing)
        val diff = group.differences.single()
        assertEquals(RestoreFieldKind.WHOLE_NOTE, diff.fieldKind)

        val applied = apply(preview, setOf(diff.key))

        assertEquals(1, applied)
        assertEquals(setOf("n1"), roomNoteIds())
    }

    // ═════════════════════════════════════════════════════════════
    //  TAGS BACKUP RECOVERY PACK (2026-09-23) — T2..T10 (T1 чистая логика — RestoreDiffEngineTest).
    //  Managed Tag/ContactTagMember восстановление: тег — ОТДЕЛЬНОЕ определение (Tag) от назначения
    //  контакту (ContactTagMember); backup может нести любую из двух комбинаций «определение уже есть» /
    //  «нужно создать оба». Legacy Contact.tags здесь НЕ используется как источник — вся логика читает
    //  только managed plan.tags/plan.tagMembers (Data Safety Residual Audit, finding TAG-1/TAG-2 — тот
    //  риск отдельный, легаси-поле сюда не подключено).
    // ═════════════════════════════════════════════════════════════

    /** T2: тег УЖЕ существует в current, membership — только в backup; выбор -> membership в Room. */
    @Test
    fun tagsT2_selectedTag_membershipCreatedInRoom() {
        addContact(contact("c1"))
        addTagRoom(tag("t-vip", "VIP"))
        settle()

        val bak = RestorePlan(contacts = listOf(contact("c1")), tags = listOf(tag("t-vip", "VIP")), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.backupValue == "VIP" }

        val applied = apply(preview, setOf(diff.key))

        assertEquals(1, applied)
        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))
    }

    /** T3: тег НЕ выбран -> membership отсутствует в Room. */
    @Test
    fun tagsT3_unselectedTag_membershipRemainsAbsent() {
        addContact(contact("c1"))
        addTagRoom(tag("t-vip", "VIP"))
        settle()

        val bak = RestorePlan(contacts = listOf(contact("c1")), tags = listOf(tag("t-vip", "VIP")), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)

        val applied = apply(preview, emptySet())

        assertEquals(0, applied)
        assertTrue(roomTagNamesFor("c1").isEmpty())
    }

    /** T4: ни определения тега, ни membership нет в current — оба есть в backup; выбор создаёт ОБА,
     *  не создавая orphan membership (Tag вставляется как зависимость раньше membership). */
    @Test
    fun tagsT4_missingTagDefinitionAndMembership_bothCreatedOnSelection() {
        addContact(contact("c1"))
        settle()

        val bak = RestorePlan(contacts = listOf(contact("c1")), tags = listOf(tag("t-vip", "VIP")), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.backupValue == "VIP" }

        val applied = apply(preview, setOf(diff.key))

        assertEquals(1, applied)
        assertEquals(setOf("t-vip"), roomTagIds())
        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))
    }

    /** T5: полностью отсутствующий Contact с тегом VIP в backup — whole-contact restore приносит
     *  Contact + Tag definition (как зависимость) + ContactTagMember атомарно, одним выбором. */
    @Test
    fun tagsT5_wholeMissingContact_restoresTagDefinitionAndMembership() {
        addContact(contact("cOther", "Other", "Person")) // держим БД непустой
        settle()

        val bak = RestorePlan(contacts = listOf(contact("c1")), tags = listOf(tag("t-vip", "VIP")), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val group = preview.groups.single { it.ownerId == "c1" }
        assertTrue(group.wholeEntityMissing)
        assertTrue("preview обещает тег в информационных строках владельца", group.childSummaryLines.any { it.contains("VIP") })

        val applied = apply(preview, setOf(group.wholeKey ?: fail("wholeKey должен существовать") as Nothing))

        assertTrue(applied >= 1)
        assertEquals(setOf("cOther", "c1"), roomContactIds())
        assertEquals(setOf("t-vip"), roomTagIds())
        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))
    }

    /** T6: повторное восстановление того же (устаревшего) preview/выбора не плодит дублей ни Tag, ни ContactTagMember. */
    @Test
    fun tagsT6_repeatedRestore_noDuplicateTagOrMembership() {
        addContact(contact("c1"))
        settle()

        val bak = RestorePlan(contacts = listOf(contact("c1")), tags = listOf(tag("t-vip", "VIP")), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.backupValue == "VIP" }

        apply(preview, setOf(diff.key))
        apply(preview, setOf(diff.key))

        assertEquals("один Tag, не дубль", 1, runBlocking { db.contactDao().getTagsOnce() }.size)
        assertEquals("одно членство, не дубль", 1, runBlocking { db.contactDao().getContactTagMembersOnce() }.size)
        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))
    }

    /** T7: «холодный перезапуск» (свежая инициализация AppStateStore из ТОЙ ЖЕ Room-базы) — тег остаётся
     *  на контакте, читается через обычный tagsOfContact(), не зависит от переживших сессию оптимистичных данных. */
    @Test
    fun tagsT7_afterFreshReload_tagStillOnContact() {
        addContact(contact("c1"))
        settle()

        val bak = RestorePlan(contacts = listOf(contact("c1")), tags = listOf(tag("t-vip", "VIP")), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.backupValue == "VIP" }
        apply(preview, setOf(diff.key))

        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        waitLoaded()

        assertEquals(setOf("VIP"), AppStateStore.tagsOfContact("c1").map { it.name }.toSet())
    }

    /** T8+T9: посторонний Contact B и его СОБСТВЕННЫЙ тег не меняются; его НЕВЫБРАННЫЙ backup-membership
     *  (VIP тоже есть в backup для B, но выбираем только для A) не восстанавливается. */
    @Test
    fun tagsT8T9_unrelatedContactAndUnselectedMembership_untouched() {
        addContact(contact("c1"))
        addContact(contact("c2", "Nina", "Petrova"))
        addTagRoom(tag("t-biz", "Business"))
        addTagMemberRoom(tagMember("mExisting", "t-biz", "c2"))
        settle()

        val bak = RestorePlan(
            contacts = listOf(contact("c1"), contact("c2", "Nina", "Petrova")),
            tags = listOf(tag("t-vip", "VIP"), tag("t-biz", "Business")),
            tagMembers = listOf(tagMember("m1", "t-vip", "c1"), tagMember("m2", "t-vip", "c2")),
        )
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diffC1 = preview.groups.single { it.ownerId == "c1" }.differences.single { it.backupValue == "VIP" }

        apply(preview, setOf(diffC1.key))

        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))
        assertEquals("Contact B: исходный Business цел, невыбранный VIP из backup не восстановлен",
            setOf("Business"), roomTagNamesFor("c2"))
    }

    /** T10: сбой посередине атомарного apply откатывает и Tag definition, и ContactTagMember вместе с остальным. */
    @Test
    fun tagsT10_atomicFailure_rollsBackTagsToo() {
        addContact(contact("c1"))
        settle()
        val beforeTagIds = roomTagIds()
        val beforeMemberIds = runBlocking { db.contactDao().getContactTagMembersOnce() }.map { it.id }.toSet()

        val bak = RestorePlan(contacts = listOf(contact("c1")), tags = listOf(tag("t-vip", "VIP")), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.backupValue == "VIP" }

        AppStateStore.restoreMidTransactionHookForTests = { throw java.io.IOException("simulated mid-restore failure") }
        val threw = try { apply(preview, setOf(diff.key)); false } catch (e: Exception) { true }
        AppStateStore.restoreMidTransactionHookForTests = null

        assertTrue("сбой обязан пробрасывать исключение вызывающему", threw)
        assertEquals("тег не остался частично созданным", beforeTagIds, roomTagIds())
        assertEquals("членство не осталось частично созданным", beforeMemberIds, runBlocking { db.contactDao().getContactTagMembersOnce() }.map { it.id }.toSet())
    }

    // ═════════════════════════════════════════════════════════════
    //  LEGACY TAGS COMPATIBILITY & PHONE SYNC SAFETY PACK (2026-09-23) — PART C: backup, чьи теги
    //  существуют ТОЛЬКО в легаси Contact.tags (никаких plan.tags/plan.tagMembers), должен всё равно
    //  дойти до Restore Preview как recoverable-кандидат, а выбор — реально восстановить managed
    //  Tag+ContactTagMember в Room (DB round-trip половина T4..T11, T4 — чистая логика в RestoreDiffEngineTest).
    // ═════════════════════════════════════════════════════════════

    /** T5: невыбранный legacy-only тег остаётся отсутствующим в Room. */
    @Test
    fun tagsPartC_T5_unselectedLegacyOnlyTag_remainsAbsent() {
        addContact(contact("c1"))
        settle()
        val bak = RestorePlan(contacts = listOf(contact("c1").copy(tags = listOf("VIP"))))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)

        val applied = apply(preview, emptySet())

        assertEquals(0, applied)
        assertTrue(roomTagNamesFor("c1").isEmpty())
    }

    /** T6: выбранный legacy-only тег восстанавливает managed Tag + ContactTagMember в Room;
     *  T11: тег переживает переинициализацию AppStateStore из ТОЙ ЖЕ Room-базы. */
    @Test
    fun tagsPartC_T6_selectedLegacyOnlyTag_restoresManagedTagAndMembership() {
        addContact(contact("c1"))
        settle()
        val bak = RestorePlan(contacts = listOf(contact("c1").copy(tags = listOf("VIP"))))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.fieldKind == RestoreFieldKind.TAG }

        val applied = apply(preview, setOf(diff.key))

        assertEquals(1, applied)
        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))

        // T11
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        waitLoaded()
        assertEquals(setOf("VIP"), AppStateStore.tagsOfContact("c1").map { it.name }.toSet())
    }

    /** T7: тег с таким же именем УЖЕ существует (под своим id) — переиспользуется, не дублируется. */
    @Test
    fun tagsPartC_T7_existingSameNameTag_reusedNoDuplicate() {
        addContact(contact("c1"))
        addTagRoom(tag("t-vip", "VIP"))
        settle()
        val bak = RestorePlan(contacts = listOf(contact("c1").copy(tags = listOf("VIP"))))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.fieldKind == RestoreFieldKind.TAG }

        apply(preview, setOf(diff.key))

        assertEquals("не создан второй VIP с другим id", setOf("t-vip"), roomTagIds())
        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))
    }

    /** T8: полностью отсутствующий Contact с legacy-only тегами — whole-contact restore
     *  восстанавливает Contact + managed Tag definitions + ContactTagMembers атомарно. */
    @Test
    fun tagsPartC_T8_wholeMissingContact_restoresLegacyOnlyTags() {
        addContact(contact("cOther", "Other", "Person"))
        settle()
        val bak = contact("c1").copy(tags = listOf("VIP", "Investor"))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val group = preview.groups.single { it.ownerId == "c1" }
        assertTrue(group.wholeEntityMissing)
        assertTrue(group.childSummaryLines.any { it.contains("VIP") })
        assertTrue(group.childSummaryLines.any { it.contains("Investor") })

        val applied = apply(preview, setOf(group.wholeKey ?: fail("wholeKey должен существовать") as Nothing))

        assertTrue(applied >= 1)
        assertEquals(setOf("cOther", "c1"), roomContactIds())
        assertEquals(setOf("VIP", "Investor"), roomTagNamesFor("c1"))
    }

    /** T10: повторное восстановление того же legacy-only выбора не плодит дублей ни Tag, ни ContactTagMember. */
    @Test
    fun tagsPartC_T10_repeatedRestoreOfLegacyOnlyTag_noDuplicates() {
        addContact(contact("c1"))
        settle()
        val bak = RestorePlan(contacts = listOf(contact("c1").copy(tags = listOf("VIP"))))
        val current = runBlocking { AppStateStore.readPersistedState() }
        val preview = RestoreDiffEngine.analyze(current, bak)
        val diff = preview.groups.single { it.ownerId == "c1" }.differences.single { it.fieldKind == RestoreFieldKind.TAG }

        apply(preview, setOf(diff.key))
        apply(preview, setOf(diff.key))

        assertEquals("один Tag, не дубль", 1, runBlocking { db.contactDao().getTagsOnce() }.size)
        assertEquals("одно членство, не дубль", 1, runBlocking { db.contactDao().getContactTagMembersOnce() }.size)
        assertEquals(setOf("VIP"), roomTagNamesFor("c1"))
    }
}
