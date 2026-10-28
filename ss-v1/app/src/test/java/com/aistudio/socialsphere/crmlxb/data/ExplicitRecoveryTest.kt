package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
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
 * MANDATORY PRODUCT CORRECTION — Explicit Recovery of Deleted Data (2026-09-22).
 *
 * Инвариант: tombstone предотвращает СЛУЧАЙНОЕ воскрешение (SAFE MERGE), но никогда не мешает НАМЕРЕННОМУ
 * восстановлению, которое пользователь явно авторизовал (SELECTIVE / RESTORE_ALL_DELETED). FRESH restore не
 * знает про tombstones вовсе — клин-инсталл ничего не защищает. Ни один режим не удаляет несвязанные текущие
 * данные молча; повторное восстановление одного и того же не плодит дублей; сбой посередине сохраняет и
 * текущие записи, и историю удалений (атомарность транзакции restore, не новый механизм).
 *
 * Ожидаемые значения — явные константы и независимые чтения Room (DAO), не тестируемый код.
 */
@RunWith(RobolectricTestRunner::class)
class ExplicitRecoveryTest {

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

    // ─── обвязка ────────────────────────────────────────────────

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
    private fun restoreSafe(json: String) = awaitOffMainThread { ExportManager.importJsonBackupDetailed(json) }
    private fun restoreAuth(json: String, auth: RestoreAuthorization) =
        awaitOffMainThread { ExportManager.importJsonBackupDetailed(json, auth) }

    private fun contact(id: String, first: String = "Иван") = Contact(
        id = id, firstName = first, lastName = "Петров", photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t0, updatedAt = t0)
    private fun note(id: String, cid: String, text: String) = Note(id, cid, null, null, null, NoteType.GENERAL, text, null, false, createdAt = t0, updatedAt = t0)
    private fun gift(id: String, cid: String, title: String = "Книга") = GiftIdea(id, cid, title, null, null, null, GiftStatus.IDEA)

    private fun addContact(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun addNote(n: Note) { runBlocking { AppStateStore.addNote(n).await() } }
    private fun addGift(g: GiftIdea) { runBlocking { AppStateStore.addGift(g).await() } }
    private fun deleteNote(id: String) { runBlocking { AppStateStore.deleteNote(id).await() } }
    private fun deleteGift(id: String) { runBlocking { AppStateStore.deleteGift(id).await() } }
    private fun deleteContact(id: String) { runBlocking { AppStateStore.deleteContact(id).await() } }

    private fun roomNoteIds(): Set<String> = runBlocking { db.noteDao().getAllNotes() }.map { it.id }.toSet()
    private fun roomGiftIds(): Set<String> = runBlocking { db.giftDao().getAllGifts() }.map { it.id }.toSet()
    private fun roomContactIds(): Set<String> = runBlocking { db.contactDao().getAllContacts() }.map { it.id }.toSet()
    private fun roomTombstoneKeys(): Set<TombstoneKey> =
        runBlocking { db.tombstoneDao().getAll() }.map { TombstoneKey(safeEnumOf(it.entityType), it.entityId) }.toSet()
    private fun safeEnumOf(s: String) = TombstoneEntityType.valueOf(s)

    private fun outcomeResult(o: ExportManager.BackupImportOutcome): RestoreResult =
        (o as? ExportManager.BackupImportOutcome.Restored)?.result ?: fail("restore отклонён: $o") as Nothing

    // ═════════════════════════════════════════════════════════════
    //  1. Safe merge keeps deleted Note deleted
    // ═════════════════════════════════════════════════════════════

    @Test
    fun safeMerge_keepsDeletedNoteDeleted() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "старая заметка"))
        val json = backupJson()                                    // backup ДО удаления — содержит n1
        deleteNote("n1")
        settle()
        assertTrue("предусловие: заметка реально удалена", roomNoteIds().isEmpty())

        val result = outcomeResult(restoreSafe(json))

        assertTrue("SAFE MERGE не воскрешает удалённую заметку", roomNoteIds().isEmpty())
        assertEquals("заметка корректно распознана как tombstoned, не молча проигнорирована", 1, result.skippedTombstoned)
        assertTrue("память согласована с Room", AppStateStore.notes.isEmpty())
        assertEquals("tombstone цел", setOf(TombstoneKey(TombstoneEntityType.NOTE, "n1")), roomTombstoneKeys())
    }

    // ═════════════════════════════════════════════════════════════
    //  2. Explicit selective recovery restores that Note
    //  3. Unselected deleted Notes remain deleted
    // ═════════════════════════════════════════════════════════════

    @Test
    fun selectiveRecovery_restoresChosenNote_leavesUnselectedNoteDeleted() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "текст A"))
        addNote(note("n2", "c1", "текст B"))
        val json = backupJson()                                    // backup содержит и n1, и n2
        deleteNote("n1"); deleteNote("n2")
        settle()
        assertTrue(roomNoteIds().isEmpty())

        val result = outcomeResult(restoreAuth(json, RestoreAuthorization.Selective(setOf(TombstoneKey(TombstoneEntityType.NOTE, "n1")))))

        assertEquals("выбранная заметка восстановлена", setOf("n1"), roomNoteIds())
        assertEquals("невыбранная осталась удалённой", 1, result.skippedTombstoned)
        assertEquals("восстановлена ровно одна", 1, result.recoveredFromTombstone)
        assertEquals("tombstone восстановленной снят, у невыбранной остался",
            setOf(TombstoneKey(TombstoneEntityType.NOTE, "n2")), roomTombstoneKeys())
        val restoredNote = runBlocking { db.noteDao().getAllNotes() }.single()
        assertEquals("восстановлена С ЕЁ данными, не заглушкой", "текст A", restoredNote.text)
    }

    // ═════════════════════════════════════════════════════════════
    //  4. Restore all returns all supported deleted records from the selected backup
    // ═════════════════════════════════════════════════════════════

    @Test
    fun restoreAllDeleted_returnsEverySupportedRecoverableRecord() {
        addContact(contact("cKeep"))
        addContact(contact("cGone", "Пётр"))
        addNote(note("n1", "cKeep", "заметка"))
        addGift(gift("g1", "cKeep", "Подарок"))
        settle()
        val json = backupJson()                                    // содержит cKeep, cGone, n1, g1

        deleteContact("cGone"); deleteNote("n1"); deleteGift("g1")
        settle()
        assertEquals(setOf("cKeep"), roomContactIds())
        assertTrue(roomNoteIds().isEmpty()); assertTrue(roomGiftIds().isEmpty())

        // recoverableRecords — то же, что видел бы UI перед RESTORE_ALL
        val candidates = runBlocking { ExportManager.recoverableRecords(json) }!!
        assertEquals(setOf(
            TombstoneKey(TombstoneEntityType.CONTACT, "cGone"),
            TombstoneKey(TombstoneEntityType.NOTE, "n1"),
            TombstoneKey(TombstoneEntityType.GIFT, "g1"),
        ), candidates.map { it.key }.toSet())

        val result = outcomeResult(restoreAuth(json, RestoreAuthorization.RestoreAllDeleted))

        assertEquals(setOf("cKeep", "cGone"), roomContactIds())
        assertEquals(setOf("n1"), roomNoteIds())
        assertEquals(setOf("g1"), roomGiftIds())
        assertEquals(3, result.recoveredFromTombstone)
        assertEquals(0, result.skippedTombstoned)
        assertTrue("ни одного tombstone не осталось — всё восстановимое восстановлено", roomTombstoneKeys().isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  5. Repeated recovery creates no duplicates
    // ═════════════════════════════════════════════════════════════

    @Test
    fun repeatedRecovery_createsNoDuplicates() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "текст"))
        val json = backupJson()
        deleteNote("n1")
        settle()

        val auth = RestoreAuthorization.Selective(setOf(TombstoneKey(TombstoneEntityType.NOTE, "n1")))
        outcomeResult(restoreAuth(json, auth))
        val afterFirst = roomNoteIds()
        outcomeResult(restoreAuth(json, auth))                     // тот же backup, та же авторизация — второй раз
        outcomeResult(restoreSafe(json))                           // и обычный SAFE MERGE поверх — тоже не плодит дублей

        assertEquals("одна заметка, не три", 1, runBlocking { db.noteDao().getAllNotes() }.size)
        assertEquals(afterFirst, roomNoteIds())
    }

    // ═════════════════════════════════════════════════════════════
    //  6. Fresh restore returns the full archive
    // ═════════════════════════════════════════════════════════════

    @Test
    fun freshRestore_returnsFullArchive_tombstonesDoNotBlockIt() {
        addContact(contact("c1"))
        addNote(note("n1", "c1", "заметка"))
        addGift(gift("g1", "c1", "подарок"))
        settle()
        val json = backupJson()

        restartOnEmptyDb()                                          // клин-инсталл: ни данных, ни tombstones
        val result = outcomeResult(restoreSafe(json))                // authorization по умолчанию SafeMerge — но mode = FRESH

        assertEquals(RestoreMode.FRESH, result.mode)
        assertEquals(setOf("c1"), roomContactIds())
        assertEquals(setOf("n1"), roomNoteIds())
        assertEquals(setOf("g1"), roomGiftIds())
        assertEquals(0, result.skippedTombstoned)
    }

    // ═════════════════════════════════════════════════════════════
    //  7. Failed recovery preserves both current records and deletion history
    // ═════════════════════════════════════════════════════════════

    @Test
    fun failedRecovery_preservesCurrentRecordsAndDeletionHistory() {
        addContact(contact("c1"))
        addNote(note("n-kept", "c1", "останется"))
        addNote(note("n-gone", "c1", "будет удалена"))
        val json = backupJson()
        deleteNote("n-gone")
        settle()
        val beforeNoteIds = roomNoteIds(); val beforeTombstones = roomTombstoneKeys()
        assertEquals(setOf("n-kept"), beforeNoteIds)
        assertEquals(setOf(TombstoneKey(TombstoneEntityType.NOTE, "n-gone")), beforeTombstones)

        AppStateStore.restoreMidTransactionHookForTests = { throw java.io.IOException("simulated mid-restore failure") }
        val threw = try {
            awaitOffMainThread { ExportManager.importJsonBackupDetailed(json, RestoreAuthorization.RestoreAllDeleted) }
            false
        } catch (e: Exception) { true }
        AppStateStore.restoreMidTransactionHookForTests = null

        assertTrue("сбой посередине транзакции обязан пробросить исключение вызывающему", threw)
        assertEquals("current-запись не тронута откатом", beforeNoteIds, roomNoteIds())
        assertEquals("история удаления (tombstone) не тронута откатом — restore не 'наполовину применился'",
            beforeTombstones, roomTombstoneKeys())
        // память тоже не разошлась с Room после сбоя (resync по общему контракту DurableSaveContractTest)
        val resynced = pollUntilTrue { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); AppStateStore.writeFailureCount > 0 }
        assertTrue(resynced)
        assertEquals(beforeNoteIds, AppStateStore.notes.map { it.id }.toSet())
    }

    // ═════════════════════════════════════════════════════════════
    //  Зависимость: удалённый ребёнок удалённого родителя — не сирота, родитель авторизуется вместе с ним
    // ═════════════════════════════════════════════════════════════

    /** Выбор ребёнка (Gift) без выбора родителя (Contact, тоже tombstoned) — родитель добавляется автоматически. */
    @Test
    fun selectiveRecovery_childOfDeletedParent_autoIncludesParent_noOrphan() {
        addContact(contact("cParent"))
        addGift(gift("g1", "cParent", "Подарок"))
        settle()
        val json = backupJson()
        deleteContact("cParent")                                    // каскад: gift g1 тоже удалён, но БЕЗ собственного tombstone
        settle()
        assertTrue(roomContactIds().isEmpty()); assertTrue(roomGiftIds().isEmpty())

        val result = outcomeResult(restoreAuth(json, RestoreAuthorization.Selective(setOf(TombstoneKey(TombstoneEntityType.GIFT, "g1")))))

        assertEquals("родитель восстановлен автоматически — иначе g1 был бы сиротой", setOf("cParent"), roomContactIds())
        assertEquals(setOf("g1"), roomGiftIds())
        assertEquals("подарок реально ссылается на восстановленного родителя, не в никуда",
            "cParent", runBlocking { db.giftDao().getAllGifts() }.single().contactId)
        assertEquals(RestoreMode.MERGE, result.mode)   // удаление ВСЕХ данных ≠ клин-инсталл: история удаления не даёт «просесть» в FRESH
        assertTrue("родитель восстановлен явной авторизацией (whole-parent recovery), учтён в счётчике", result.recoveredFromTombstone >= 1)
    }

    /** Выбор родителя (Contact) целиком автоматически подтягивает каскадно удалённого ребёнка (Note) без его
     *  собственного tombstone — иначе восстановленный контакт навсегда терял бы старую заметку без возможности выбора. */
    @Test
    fun selectiveRecovery_choosingParent_bringsBackCascadeDeletedChildAutomatically() {
        addContact(contact("cParent"))
        addNote(note("n1", "cParent", "старая заметка"))
        settle()
        val json = backupJson()
        deleteContact("cParent")
        settle()

        outcomeResult(restoreAuth(json, RestoreAuthorization.Selective(setOf(TombstoneKey(TombstoneEntityType.CONTACT, "cParent")))))

        assertEquals(setOf("cParent"), roomContactIds())
        assertEquals("каскадная заметка вернулась вместе с контактом", setOf("n1"), roomNoteIds())
    }

    // ═════════════════════════════════════════════════════════════
    //  «Всё удалено» ≠ клин-инсталл: история удаления не даёт установке ложно выглядеть FRESH
    // ═════════════════════════════════════════════════════════════

    /** Единственный контакт удалён — current.contacts/notes/gifts/... пусты, но tombstone есть: SAFE MERGE
     *  обязан остаться MERGE (и защитить tombstoned запись), а не решить, что БД «никогда не использовалась». */
    @Test
    fun allDataDeleted_isNotMistakenForFreshInstall_safeMergeStillProtectsTombstone() {
        addContact(contact("cOnly"))
        settle()
        val json = backupJson()
        deleteContact("cOnly")
        settle()
        assertTrue("предусловие: current пуст по всем спискам", roomContactIds().isEmpty())

        val result = outcomeResult(restoreSafe(json))

        assertEquals("НЕ FRESH — есть история удаления", RestoreMode.MERGE, result.mode)
        assertTrue("SAFE MERGE не воскрешает единственный удалённый контакт только потому, что БД теперь пуста",
            roomContactIds().isEmpty())
        assertEquals(1, result.skippedTombstoned)
    }

    // ═════════════════════════════════════════════════════════════
    //  Неудалённые данные никогда не трогаются
    // ═════════════════════════════════════════════════════════════

    @Test
    fun anyMode_neverTouchesUnrelatedCurrentData() {
        addContact(contact("cUnrelated", "Совсем другой"))
        addNote(note("nUnrelated", "cUnrelated", "не имеет отношения к удалению"))
        addContact(contact("c1"))
        addNote(note("n1", "c1", "будет удалена"))
        settle()
        val json = backupJson()
        deleteNote("n1")
        settle()

        outcomeResult(restoreAuth(json, RestoreAuthorization.RestoreAllDeleted))

        assertEquals("несвязанный контакт цел", setOf("cUnrelated", "c1"), roomContactIds())
        assertEquals("несвязанная заметка цела, восстановленная тоже на месте",
            setOf("nUnrelated", "n1"), roomNoteIds())
    }
}
