package com.aistudio.socialsphere.crmlxb.data

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.ContactEntity
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.nameRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.phoneRow
import com.aistudio.socialsphere.crmlxb.utils.PhoneContactSync
import com.aistudio.socialsphere.crmlxb.utils.PhoneSyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DATA SAFETY MICRO-PACK — resync после сбоя записи не имеет права откатывать более новую принятую запись.
 *
 * Инвариант: после ЛЮБОГО сбойного коммита память сходится с Room (eventually memory == Room) для всех
 * последующих принятых операций; ни восстановление, ни перечитывание не откатывает запись, принятую позже, и
 * обычная правка после восстановления не превращает временную рассинхронизацию в потерю сохранённых данных.
 *
 * Порядок доказывается без delay(): управляемый писатель ([AppStateStore.writeGateForTests] — Room ещё не
 * закоммитил, [AppStateStore.writeFaultForTests] — сбой коммита) и контрольная точка восстановления
 * ([AppStateStore.resyncAfterReadHookForTests]: снимок из Room уже прочитан, память ещё не тронута) на
 * CompletableDeferred-защёлках. Ожидаемые значения — константы и независимые чтения Room.
 */
@RunWith(RobolectricTestRunner::class)
class WriteFailureResyncOrderingTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.defaultTagsSeeded.value = true
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.writeGateForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.writeFaultOnceForTests = null
        AppStateStore.resyncAfterReadHookForTests = null
        AppStateStore.initialize(ctx, db)
        if (!pollUntilTrue(10_000) { idle(); !AppStateStore.isLoading }) fail("loadInitialData не завершился")
    }

    @After
    fun tearDown() {
        AppStateStore.resyncAfterReadHookForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.writeFaultOnceForTests = null
        AppStateStore.writeGateForTests?.complete(Unit)
        AppStateStore.writeGateForTests = null
        FakeContactsProvider.reset()
        clearMemory(); db.close()
    }

    // ─── обвязка ────────────────────────────────────────────────

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun clearMemory() {
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear(); AppStateStore.gifts.clear()
        AppStateStore.addresses.clear(); AppStateStore.calendarItems.clear(); AppStateStore.companyRelations.clear()
        AppStateStore.contactRelations.clear(); AppStateStore.groups.clear(); AppStateStore.groupMembers.clear()
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear(); AppStateStore.personalDetails.clear(); AppStateStore.sizeInfos.clear()
    }

    private fun pollUntilTrue(timeoutMs: Long = 10_000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { kotlinx.coroutines.delay(20); ok = check() }
        ok
    }

    private fun <T> awaitOffMain(timeoutMs: Long = 30_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch { try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) } }
        if (!pollUntilTrue(timeoutMs) { idle(); done.isCompleted }) fail("операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }

    private val t0 = "2026-01-01T00:00:00"

    private fun contact(id: String = "c1", link: Boolean = false) = Contact(
        id = id, firstName = "OLD", lastName = "Петров", photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, nextStep = "OLD_STEP",
        deviceContactId = if (link) "device_contact_10" else null, deviceLookupKey = if (link) "L10" else null,
        createdAt = t0, updatedAt = t0)

    private fun add(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun fresh(id: String = "c1"): Contact = AppStateStore.getContact(id)!!
    private fun roomContact(id: String = "c1"): ContactEntity = runBlocking { db.contactDao().getAllContacts().first { it.id == id } }
    private fun failureCount() = AppStateStore.writeFailureCount
    private fun closeGate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { AppStateStore.writeGateForTests = it }
    private fun fault() { AppStateStore.writeFaultForTests = java.io.IOException("disk full") }
    private fun clearFault() { AppStateStore.writeFaultForTests = null }

    /** true — коммит подтверждён, false — сбой (память уже пересобирается по политике resync). */
    private fun committed(w: Deferred<Unit>): Boolean = runBlocking { AppStateStore.awaitDurableCommit(w) }

    /** Ожидание конца серии восстановления: счётчик растёт ПОСЛЕ применения снимка к памяти. */
    private fun awaitSeriesDone(before: Int) {
        assertTrue("серия восстановления завершилась", pollUntilTrue(15_000) { idle(); failureCount() > before })
    }

    /** Контрольная точка: первый проход восстановления останавливается ПОСЛЕ чтения снимка, ДО применения к памяти. */
    private class Checkpoint {
        val reached = CompletableDeferred<PersistedState>()
        val release = CompletableDeferred<Unit>()
        private val armed = AtomicBoolean(true)
        val hook: suspend (PersistedState) -> Unit = { s -> if (armed.compareAndSet(true, false)) { reached.complete(s); release.await() } }
    }

    private fun armCheckpoint(): Checkpoint = Checkpoint().also { AppStateStore.resyncAfterReadHookForTests = it.hook }
    private fun waitReached(cp: Checkpoint) = assertTrue("восстановление дошло до контрольной точки", pollUntilTrue(15_000) { idle(); cp.reached.isCompleted })

    /** Память == Room, поле в поле по всем агрегатам (эталон — независимый снимок Room). */
    private fun assertMemoryEqualsRoom() {
        val room = runBlocking { AppStateStore.readPersistedState() }
        assertEquals("contacts (с вложенными проекциями)", room.contacts.sortedBy { it.id }, AppStateStore.contacts.sortedBy { it.id })
        assertEquals("gifts", room.gifts.sortedBy { it.id }, AppStateStore.gifts.sortedBy { it.id })
        assertEquals("notes", room.notes.sortedBy { it.id }, AppStateStore.notes.sortedBy { it.id })
        assertEquals("sizeInfos", room.sizeInfos.sortedBy { it.id }, AppStateStore.sizeInfos.sortedBy { it.id })
        assertEquals("addresses", room.addresses.sortedBy { it.id }, AppStateStore.addresses.sortedBy { it.id })
        assertEquals("personalDetails", room.personalDetails.sortedBy { it.id }, AppStateStore.personalDetails.sortedBy { it.id })
        assertEquals("companyRelations", room.companyRelations.sortedBy { it.id }, AppStateStore.companyRelations.sortedBy { it.id })
    }

    /** Сценарий A: сбойная запись A; во время восстановления принята B (nextStep), Room её ещё не видит; B коммитится. */
    private fun scenarioAThenB(): Int {
        add(contact())
        val before = failureCount()
        val cp = armCheckpoint()
        val s = fresh()
        fault()
        val a = AppStateStore.updateContact(s, s.copy(firstName = "FAILED_VALUE"))
        assertFalse("A не подтверждена", committed(a))
        waitReached(cp)                                                    // снимок прочитан, память ещё не тронута

        val gate = closeGate()
        val cur = fresh()
        val b = AppStateStore.updateContact(cur, cur.copy(nextStep = "NEW_STEP"))          // принята во время восстановления
        clearFault()
        assertEquals("предусловие: B ещё НЕ закоммичена в Room", "OLD_STEP", roomContact().nextStep)
        assertEquals("оптимистично B уже видна в памяти", "NEW_STEP", fresh().nextStep)

        cp.release.complete(Unit)                                          // восстановление продолжается
        gate.complete(Unit)                                                // B коммитится
        assertTrue("B подтверждена: успешная запись не считается сбойной из-за A", committed(b))
        awaitSeriesDone(before)
        return before
    }

    // ═════════════════════════════════════════════════════════════
    //  T1 — только сбой A
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T1_failedWriteOnly_memoryEqualsRoomEqualsOld() {
        add(contact())
        val before = failureCount()
        val s = fresh()
        fault()
        val a = AppStateStore.updateContact(s, s.copy(firstName = "FAILED_VALUE", nextStep = "FAILED_STEP"))
        assertFalse(committed(a))
        clearFault()
        awaitSeriesDone(before)

        assertEquals(listOf("OLD", "OLD_STEP"), roomContact().let { listOf(it.firstName, it.nextStep) })
        assertEquals("память = Room = OLD", listOf("OLD", "OLD_STEP"), fresh().let { listOf(it.firstName, it.nextStep) })
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  T2 — сбой A + успешная B, принятая во время восстановления
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T2_failedA_thenAcceptedB_duringRecovery_memoryEqualsRoomEqualsB() {
        scenarioAThenB()

        assertEquals("Room содержит B", "NEW_STEP", roomContact().nextStep)
        assertEquals("память содержит B (восстановление не откатило принятую запись)", "NEW_STEP", fresh().nextStep)
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  T3 — цепочка потери сохранённых данных: обычная правка после сбоя не откатывает B в Room
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T3_persistedLossChain_unrelatedEditAfterFailureAndB_doesNotRollBackB() {
        scenarioAThenB()

        val cur = fresh()                                                  // контакт из памяти — как его видит экран
        assertTrue(committed(AppStateStore.updateContact(cur, cur.copy(profession = "NEW_PROFESSION"))))

        val room = roomContact()
        assertEquals("B пережила несвязанную правку", "NEW_STEP", room.nextStep)
        assertEquals("NEW_PROFESSION", room.profession)
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  T4 — сбой A, затем две успешные записи B и C
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T4_failedA_thenTwoSuccessfulWrites_memoryEqualsRoomEqualsC() {
        add(contact())
        val before = failureCount()
        val cp = armCheckpoint()
        val s = fresh()
        fault()
        assertFalse(committed(AppStateStore.updateContact(s, s.copy(firstName = "FAILED_VALUE"))))
        waitReached(cp)

        val gate = closeGate()
        val b0 = fresh()
        val b = AppStateStore.updateContact(b0, b0.copy(nextStep = "B_STEP"))
        val c0 = fresh()                                                   // экран видит оптимистичное B
        val c = AppStateStore.updateContact(c0, c0.copy(nextStep = "C_STEP", profession = "C_PROFESSION"))
        clearFault()
        cp.release.complete(Unit)
        gate.complete(Unit)
        assertTrue(committed(b)); assertTrue(committed(c))
        awaitSeriesDone(before)

        assertEquals(listOf("C_STEP", "C_PROFESSION"), roomContact().let { listOf(it.nextStep, it.profession) })
        assertEquals(listOf("C_STEP", "C_PROFESSION"), fresh().let { listOf(it.nextStep, it.profession) })
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  T5 — после сбоя Contact-записи: Gift и Note других агрегатов (не только Contact)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T5_failedContactWrite_thenGiftAndNote_optimisticChangesOfOtherAggregatesSurvive() {
        add(contact())
        val before = failureCount()
        val cp = armCheckpoint()
        val s = fresh()
        fault()
        assertFalse(committed(AppStateStore.updateContact(s, s.copy(firstName = "FAILED_VALUE"))))
        waitReached(cp)

        val gate = closeGate()
        val g = AppStateStore.addGift(GiftIdea("g-new", "c1", "Гейт-подарок", null, null, null, GiftStatus.IDEA))
        val n = AppStateStore.addNote(Note("n-new", "c1", null, null, null, NoteType.GENERAL, "Гейт-заметка", null, false, createdAt = t0, updatedAt = t0))
        clearFault()
        cp.release.complete(Unit)
        gate.complete(Unit)
        assertTrue(committed(g)); assertTrue(committed(n))
        awaitSeriesDone(before)

        assertEquals("Gift в Room", listOf("g-new"), runBlocking { db.giftDao().getAllGifts() }.map { it.id })
        assertEquals("Note в Room", listOf("n-new"), runBlocking { db.noteDao().getAllNotes() }.map { it.id })
        assertEquals("Gift в памяти (плоский и вложенный списки)", listOf(listOf("g-new"), listOf("g-new")),
            listOf(AppStateStore.gifts.map { it.id }, fresh().gifts.map { it.id }))
        assertEquals("Note в памяти (плоский и вложенный списки)", listOf(listOf("n-new"), listOf("n-new")),
            listOf(AppStateStore.notes.map { it.id }, fresh().notes.map { it.id }))
        assertEquals("сбойная правка A откатена: следов нет ни в Room, ни в памяти", listOf("OLD", "OLD"),
            listOf(roomContact().firstName, fresh().firstName))
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  T6 — сбой коммита при «Обновить из телефона», затем обычная правка
    // ═════════════════════════════════════════════════════════════

    @Test
    fun T6_phoneSyncCommitFailed_thenRegularEdit_isPersistedAndNotLostByResync() {
        add(contact(link = true))
        FakeContactsProvider.reset()
        org.robolectric.Robolectric.buildContentProvider(FakeContactsProvider::class.java).create(ContactsContract.AUTHORITY)
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.contact(10, "L10", nameRow("OLD", "Петров"), phoneRow("+79001112233"))

        val before = failureCount()
        val cp = armCheckpoint()
        fault()
        assertEquals(PhoneSyncResult.CommitFailed, awaitOffMain { PhoneContactSync.pullFromPhone(ctx, "c1") })
        waitReached(cp)

        val gate = closeGate()
        val cur = fresh()
        val e = AppStateStore.updateContact(cur, cur.copy(nextStep = "NEW_STEP"))          // обычная успешная правка после CommitFailed
        clearFault()
        cp.release.complete(Unit)
        gate.complete(Unit)
        assertTrue(committed(e))
        awaitSeriesDone(before)

        assertEquals("правка сохранена в Room", "NEW_STEP", roomContact().nextStep)
        assertEquals("и не потеряна в памяти", "NEW_STEP", fresh().nextStep)
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  ORD — порядок: A, B (принята ДО сбоя A), восстановление читает Room только ПОСЛЕ B
    // ═════════════════════════════════════════════════════════════

    @Test
    fun ORD_recoveryIsOrderedBehindEveryAcceptedWrite_andReadsStateAfterThem() {
        add(contact())
        val before = failureCount()
        val cp = armCheckpoint()
        val gate = closeGate()                                                             // обе записи стоят в очереди одновременно
        AppStateStore.writeFaultOnceForTests = java.io.IOException("disk full")            // упадёт только первая: A
        val s = fresh()
        val a = AppStateStore.updateContact(s, s.copy(firstName = "FAILED_VALUE"))
        val cur = fresh()
        val b = AppStateStore.updateContact(cur, cur.copy(nextStep = "B_STEP"))            // принята ДО сбоя A, стоит за A
        assertEquals("предусловие: Room ещё старый", "OLD_STEP", roomContact().nextStep)
        assertFalse("предусловие: восстановление ещё не началось", cp.reached.isCompleted)

        gate.complete(Unit)                                                                // A падает, B коммитится
        assertFalse("A не подтверждена", committed(a))
        assertTrue("B (принята до сбоя A) подтверждена", committed(b))
        waitReached(cp)
        assertTrue("снимок восстановления прочитан только после того, как A и B отработали", a.isCompleted && b.isCompleted)
        val snapshot = runBlocking { cp.reached.await() }
        assertEquals("снимок содержит коммит B", "B_STEP", snapshot.contacts.single().nextStep)
        cp.release.complete(Unit)
        awaitSeriesDone(before)
        assertEquals("B_STEP", fresh().nextStep)
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  Backup — сразу после подтверждённой B backup содержит B
    // ═════════════════════════════════════════════════════════════

    @Test
    fun BACKUP_failedA_successfulB_immediateBackupContainsB() {
        add(contact())
        val before = failureCount()
        val cp = armCheckpoint()
        val s = fresh()
        fault()
        assertFalse(committed(AppStateStore.updateContact(s, s.copy(firstName = "FAILED_VALUE"))))
        waitReached(cp)
        clearFault()

        val cur = fresh()
        assertTrue(committed(AppStateStore.updateContact(cur, cur.copy(nextStep = "B_STEP"))))
        val backup = ExportManager.parseJsonBackup(runBlocking { ExportManager.backupJsonString() }) ?: fail("backup не парсится") as Nothing
        assertEquals("backup содержит B", "B_STEP", backup.contacts.single().nextStep)

        cp.release.complete(Unit)
        awaitSeriesDone(before)
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  Residual sweep — тот же класс гонки на пути restore: запись, принятая между коммитом restore и заменой памяти
    // ═════════════════════════════════════════════════════════════

    @Test
    fun RESTORE_writeAcceptedBetweenRestoreCommitAndMemoryApply_isNotOverwritten() {
        add(contact())
        val json = ExportManager.backupAdapter.toJson(BackupData(version = 7, contacts = listOf(contact("c1"), contact("c2"))))
        val cp = armCheckpoint()
        val restore = CoroutineScope(Dispatchers.IO).async { ExportManager.importJsonBackup(json) }
        waitReached(cp)                                                    // restore закоммичен, снимок для памяти прочитан

        val gate = closeGate()
        val cur = fresh()
        val b = AppStateStore.updateContact(cur, cur.copy(nextStep = "NEW_STEP"))          // принята между коммитом restore и заменой памяти
        cp.release.complete(Unit)
        gate.complete(Unit)
        assertTrue(committed(b))
        assertTrue("restore завершился", pollUntilTrue(15_000) { idle(); restore.isCompleted })
        assertTrue("restore не отклонён", runBlocking { restore.await() } >= 0)

        assertEquals("правка пережила restore в Room", "NEW_STEP", roomContact().nextStep)
        assertEquals("и в памяти", "NEW_STEP", fresh().nextStep)
        assertEquals(setOf("c1", "c2"), AppStateStore.contacts.map { it.id }.toSet())
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  Повтор — второй сбой ПОСЛЕ завершённого восстановления запускает новую серию (флаг серии снят вместе с применением)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun REPEAT_secondFailureAfterCompletedRecovery_startsNewSeries_andConverges() {
        add(contact())
        val before = failureCount()

        fault()
        val s1 = fresh()
        assertFalse(committed(AppStateStore.updateContact(s1, s1.copy(firstName = "FAILED_1"))))
        clearFault()
        awaitSeriesDone(before)
        assertEquals("после первой серии: память = Room = OLD", listOf("OLD", "OLD"), listOf(fresh().firstName, roomContact().firstName))

        fault()
        val s2 = fresh()
        assertFalse(committed(AppStateStore.updateContact(s2, s2.copy(nextStep = "FAILED_2"))))
        clearFault()
        awaitSeriesDone(before + 1)

        assertEquals("вторая серия отработала отдельно: два сигнала на два независимых сбоя", before + 2, failureCount())
        assertEquals(listOf("OLD_STEP", "OLD_STEP"), listOf(fresh().nextStep, roomContact().nextStep))
        assertMemoryEqualsRoom()
    }

    // ═════════════════════════════════════════════════════════════
    //  Failure count / toast — одна серия = один сигнал; успешная B не считается сбойной
    // ═════════════════════════════════════════════════════════════

    @Test
    fun COUNT_oneSeriesOfFailures_singleSignal_successfulBIsNotCountedAsFailed() {
        add(contact())
        val before = failureCount()
        val gate = closeGate()
        fault()
        val s = fresh()
        val a1 = AppStateStore.updateContact(s, s.copy(firstName = "FAILED_1"))
        val a2 = AppStateStore.addGift(GiftIdea("g-fail", "c1", "Не сохранится", null, null, null, GiftStatus.IDEA))    // два сбоя подряд
        gate.complete(Unit)
        assertFalse(committed(a1)); assertFalse(committed(a2))
        clearFault()
        val cur = fresh()
        val b = AppStateStore.updateContact(cur, cur.copy(nextStep = "B_STEP"))
        assertTrue("успешная B не объявлена сбойной", committed(b))
        awaitSeriesDone(before)

        runBlocking { AppStateStore.awaitPendingWrites() }; idle()
        assertEquals("ровно ОДИН сигнал на серию сбоев подряд", before + 1, failureCount())
        assertMemoryEqualsRoom()
    }
}
