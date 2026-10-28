package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * EXPORT SNAPSHOT CONSISTENCY PACK (2026-09-23).
 *
 * Инвариант: Room committed state -> ОДИН канонический snapshot -> JSON/CSV/VCF/ZIP содержимое.
 * Экспорт НЕ должен зависеть от того, успела ли AppStateStore память догнать Room (холодный старт),
 * от оптимистичных незакоммиченных значений памяти, от неудавшейся записи (память ещё не resync'нута),
 * и от момента между построением разных файлов ОДНОГО ZIP-архива.
 *
 * «Холодный старт»/«оптимистичное значение» моделируются прямой мутацией `AppStateStore.<список>`
 * В ОБХОД очереди записи (тот же приём, что используется по всей кодовой базе для конструирования
 * «память разошлась с Room») — не гонка с реальным async loadInitialData()/записью, а детерминированное
 * построение состояния «Room имеет X, память имеет Y», которое и является сутью проверяемого инварианта.
 */
@RunWith(RobolectricTestRunner::class)
class ExportSnapshotConsistencyTest {

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
        AppStateStore.writeFaultOnceForTests = null
        AppStateStore.initialize(ctx, db)
        waitLoaded()
    }

    @After
    fun tearDown() {
        AppStateStore.writeGateForTests?.complete(Unit)
        AppStateStore.writeGateForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.writeFaultOnceForTests = null
        ExportManager.exportZipSnapshotCapturedHookForTests = null
        clearMemory()
        db.close()
    }

    // ─── обвязка (тот же паттерн, что TagsConsistencyTest/RestorePreviewIntegrationTest) ────────

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

    private fun contact(id: String, first: String = "Alex", last: String = "Johnson") = Contact(
        id = id, firstName = first, lastName = last, photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t0, updatedAt = t0)
    private fun phone(id: String, cid: String, number: String) = ContactPhone(id, cid, number, PhoneType.MOBILE, isPrimary = true)

    private fun addContact(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun currentContact(id: String): Contact = AppStateStore.getContactById(id) ?: fail("контакт $id должен быть в памяти") as Nothing
    private fun addTag(name: String): Tag = AppStateStore.addTag(name) ?: fail("addTag($name) вернул null") as Nothing

    private fun readZipEntries(zip: java.io.File): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        java.util.zip.ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { e -> result[e.name] = zf.getInputStream(e).bufferedReader().readText() }
        }
        return result
    }

    // ═════════════════════════════════════════════════════════════
    //  T1/T2 — «холодный старт»: Room закоммитил, память ещё пуста
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t1_csvColdStart_readsRoomEvenWhenMemoryEmpty() {
        addContact(contact("c1", "Alex", "Johnson"))
        settle()
        AppStateStore.contacts.clear()

        val file = awaitOffMainThread { ExportManager.exportContactsCsv(ctx) }

        assertTrue("CSV обязан прочитать Room, а не пустую память", file.readText().contains("Alex"))
    }

    @Test
    fun t2_vcfColdStart_readsRoomEvenWhenMemoryEmpty() {
        addContact(contact("c1", "Alex", "Johnson"))
        settle()
        AppStateStore.contacts.clear()

        val file = awaitOffMainThread { ExportManager.exportVCard(ctx) }

        assertTrue("VCF обязан прочитать Room, а не пустую память", file.readText().contains("Alex"))
    }

    // ═════════════════════════════════════════════════════════════
    //  T3 — JSON backup уже канонический (подтверждающий тест, не регресс)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t3_jsonBackup_stillReadsCanonicalPersistedState() {
        addContact(contact("c1", "Alex", "Johnson"))
        settle()
        AppStateStore.contacts.clear()

        val json = runBlocking { ExportManager.backupJsonString() }

        assertTrue("JSON backup уже использует канонический снимок — регресса быть не должно", json.contains("Alex"))
    }

    // ═════════════════════════════════════════════════════════════
    //  T4/T5 — один ZIP = один snapshot для ВСЕХ внутренних файлов
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t4_fullZip_usesOneSnapshotForAllInternalFormats() {
        addContact(contact("c1", "Alex", "Johnson").copy(phones = listOf(phone("p1", "c1", "+301111111"))))
        settle()

        ExportManager.exportZipSnapshotCapturedHookForTests = {
            // «Запись, случившаяся во время экспорта» — ПОСЛЕ захвата единого snapshot (requirement 11).
            val cur = currentContact("c1")
            AppStateStore.updateContact(cur, cur.copy(phones = listOf(phone("p1", "c1", "+302222222")))).await()
        }
        val zip = try {
            awaitOffMainThread { ExportManager.exportFullZip(ctx) }
        } finally {
            ExportManager.exportZipSnapshotCapturedHookForTests = null
        }

        val entries = readZipEntries(zip)
        assertTrue("ZIP должен содержать хотя бы несколько внутренних файлов", entries.size >= 3)
        // companies_*.csv намеренно не несёт телефон КОНТАКТА — проверяем только файлы, которые вообще
        // МОГУТ его нести (contacts_*.csv/.vcf, backup_*.json), иначе тест бьётся о собственную схему.
        val relevant = entries.filterKeys { it.startsWith("contacts_") || it.startsWith("backup_") }
        assertTrue("должно быть хотя бы 3 релевантных файла (csv+vcf+json)", relevant.size >= 3)
        relevant.forEach { (name, content) ->
            assertTrue("$name должен отражать snapshot ДО записи (+301111111)", content.contains("301111111"))
            assertFalse("$name НЕ должен содержать запись, случившуюся ПОСЛЕ захвата snapshot (+302222222)",
                content.contains("302222222"))
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  T6 — оптимистичное, никогда не закоммиченное значение памяти не экспортируется
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t6_optimisticUncommittedMemoryValue_isNotExported() {
        addContact(contact("c1", "Alex", "Johnson").copy(phones = listOf(phone("p1", "c1", "+301111111"))))
        settle()

        val idx = AppStateStore.contacts.indexOfFirst { it.id == "c1" }
        AppStateStore.contacts[idx] = AppStateStore.contacts[idx].copy(phones = listOf(phone("p1", "c1", "+309999999")))

        val file = awaitOffMainThread { ExportManager.exportContactsCsv(ctx) }

        assertFalse("CSV не должен содержать неподтверждённое оптимистичное значение памяти", file.readText().contains("309999999"))
        assertTrue("CSV должен содержать подтверждённое Room-значение", file.readText().contains("301111111"))
    }

    // ═════════════════════════════════════════════════════════════
    //  T7 — значение неудавшейся записи не экспортируется
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t7_failedWriteValue_isNotExported() {
        addContact(contact("c1", "Alex", "Johnson").copy(phones = listOf(phone("p1", "c1", "+301111111"))))
        settle()

        AppStateStore.writeFaultOnceForTests = java.io.IOException("simulated disk full")
        val cur = currentContact("c1")
        // Оптимистичная память СИНХРОННО видит новое значение до провала коммита (установленный контракт AppStateStore).
        AppStateStore.updateContact(cur, cur.copy(phones = listOf(phone("p1", "c1", "+309999999"))))
        assertEquals(setOf("+309999999"), currentContact("c1").phones.map { it.number }.toSet())

        val file = awaitOffMainThread { ExportManager.exportContactsCsv(ctx) }

        assertFalse("CSV не должен содержать значение неудавшейся записи", file.readText().contains("309999999"))
        assertTrue("CSV должен содержать то, что реально осталось закоммиченным в Room", file.readText().contains("301111111"))
    }

    // ═════════════════════════════════════════════════════════════
    //  T8 — немедленный экспорт после успешного коммита включает новое значение (позитивный контроль)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t8_immediateExportAfterSuccessfulCommit_includesNewValue() {
        addContact(contact("c1", "Alex", "Johnson").copy(phones = listOf(phone("p1", "c1", "+301111111"))))
        settle()
        val cur = currentContact("c1")
        awaitOffMainThread { AppStateStore.updateContact(cur, cur.copy(phones = listOf(phone("p1", "c1", "+302222222")))).await() }

        val file = awaitOffMainThread { ExportManager.exportContactsCsv(ctx) }

        assertTrue("экспорт сразу после подтверждённого коммита обязан содержать новое значение",
            file.readText().contains("302222222"))
    }

    // ═════════════════════════════════════════════════════════════
    //  T9/T10 — managed-теги в VCF из ТОГО ЖЕ snapshot, не отдельным live-чтением
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t9_managedTagsExportedConsistentlyToVcf() {
        addContact(contact("c1", "Alex", "Johnson"))
        settle()
        val vip = addTag("VIP")
        runBlocking { AppStateStore.setContactTags("c1", setOf(vip.id)).await() }

        val file = awaitOffMainThread { ExportManager.exportVCard(ctx) }

        assertTrue("VCF обязан содержать managed-тег VIP", file.readText().contains("VIP"))
    }

    @Test
    fun t10_tagExportReflectsSameSnapshotAsRestOfVcf_notLiveMemoryTags() {
        addContact(contact("c1", "Alex", "Johnson"))
        val vip = addTag("VIP")
        runBlocking { AppStateStore.setContactTags("c1", setOf(vip.id)).await() }
        settle()
        // Оптимистичная память получает ЕЩЁ один тег в обход очереди — никогда не закоммичен.
        val biz = addTag("Business")
        AppStateStore.tagMembers.add(ContactTagMember(id = "opt-m", tagId = biz.id, contactId = "c1"))

        val file = awaitOffMainThread { ExportManager.exportVCard(ctx) }
        val content = file.readText()

        assertTrue("VIP (реально закоммичен) обязан быть в VCF", content.contains("VIP"))
        assertFalse("Business (только в памяти, не в Room) не должен утечь в VCF", content.contains("Business"))
    }

    // ═════════════════════════════════════════════════════════════
    //  T11 — сбой чтения snapshot -> controlled failure, не ложный успех
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t11_snapshotReadFailure_doesNotProduceFalseSuccess() {
        addContact(contact("c1", "Alex", "Johnson"))
        settle()
        AppStateStore.writeFaultForTests = java.io.IOException("simulated disk full")

        var threw = false
        try {
            awaitOffMainThread { ExportManager.exportContactsCsv(ctx) }
        } catch (e: Throwable) {
            threw = true
        }

        assertTrue("сбой чтения snapshot обязан пробрасываться вызывающему, не создавать 'успешный' файл", threw)
    }
}
