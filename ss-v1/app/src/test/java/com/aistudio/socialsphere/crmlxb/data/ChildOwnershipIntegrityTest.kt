package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OWNERSHIP PACK #1 — FRONT 3 (дочерние сущности Contact), FRONT 4 (Company) и
 * FRONT 5 (merge/undo/restore) — 2026-09-21.
 *
 * Канонический паттерн lost-update этого пакета:
 *   снимок S1 -> независимое обновление child -> updateX(S1.copy(несвязанное поле))
 *   -> НОВОЕ значение child обязано выжить (Room, память, вложенная проекция,
 *   reloadFromDb, JSON).
 * Плюс обратный контроль «законной правки»: намеренное изменение/удаление
 * телефонов, email, мессенджеров, деталей, связей и адресов по-прежнему проходит.
 *
 * Expected-значения — из ИСХОДНЫХ fixture и независимых чтений Room, а не из
 * тестируемого (возможно устаревшего) объекта.
 */
@RunWith(RobolectricTestRunner::class)
class ChildOwnershipIntegrityTest {

    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(timeoutMs = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут в setup()")
    }

    @After
    fun tearDown() {
        clearMemory()
        db.close()
    }

    // ─── обвязка ────────────────────────────────────────────────

    private fun clearMemory() {
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear()
        AppStateStore.gifts.clear(); AppStateStore.addresses.clear(); AppStateStore.calendarItems.clear()
        AppStateStore.companyRelations.clear(); AppStateStore.contactRelations.clear(); AppStateStore.groups.clear()
        AppStateStore.groupMembers.clear(); AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
        AppStateStore.personalDetails.clear(); AppStateStore.sizeInfos.clear()
    }

    private fun pollUntilTrue(timeoutMs: Long = 10_000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { kotlinx.coroutines.delay(20); ok = check() }
        ok
    }

    private fun <T> awaitOffMainThread(timeoutMs: Long = 15_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch {
            try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        val settled = pollUntilTrue(timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            done.isCompleted
        }
        if (!settled) fail("suspend-операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }

    private fun contact(
        id: String, firstName: String = "Иван", lastContactDate: String? = null,
        phones: List<ContactPhone> = emptyList(), rels: List<ContactCompanyRelation> = emptyList(),
        sizeInfo: SizeInfo? = null, personalDetails: List<PersonalDetail> = emptyList(),
    ) = Contact(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, lastContactDate = lastContactDate,
        phones = phones, companyRelations = rels, sizeInfo = sizeInfo, personalDetails = personalDetails,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )
    private fun company(id: String, phones: List<ContactPhone> = emptyList()) = Company(
        id = id, name = "Acme $id", industry = Industry.IT, phones = phones,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )
    private fun rel(id: String, contactId: String, companyId: String, position: String = "Инженер") = ContactCompanyRelation(
        id = id, contactId = contactId, companyId = companyId, position = position, department = null, role = null,
        employmentStatus = EmploymentStatus.CURRENT, startDate = null, endDate = null, responsibilities = null,
        managedAccounts = null, workNote = null, officeAddressId = null, isPrimary = true
    )
    private fun phones(owner: String, n: Int) = (1..n).map { ContactPhone("p$owner$it", owner, "+7900000$owner$it", PhoneType.MOBILE, it == 1) }

    private fun addContactAndWait(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun updateContactAndWait(base: Contact, edited: Contact) { runBlocking { AppStateStore.updateContact(base, edited).await() } }
    private fun updateFreshAndWait(id: String, edit: (Contact) -> Contact) { val cur = AppStateStore.getContact(id)!!; updateContactAndWait(cur, edit(cur)) }
    private fun addCompanyAndWait(c: Company) { runBlocking { AppStateStore.addCompany(c).await() } }
    private fun updateCompanyAndWait(base: Company, edited: Company) { runBlocking { AppStateStore.updateCompany(base, edited).await() } }
    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    // независимые чтения Room
    private fun roomRelations(contactId: String): List<ContactCompanyRelation> {
        settle(); return runBlocking { db.contactDao().getContactCompanyRelations().map { it.toDomain() }.filter { it.contactId == contactId } }
    }
    private fun roomContact(id: String): Contact { settle(); return runBlocking { db.contactDao().getAllContacts().first { it.id == id }.toDomain() } }
    private fun roomSizeRows(cid: String): List<SizeInfo> { settle(); return runBlocking { db.contactDao().getSizeInfos().map { it.toDomain() }.filter { it.contactId == cid } } }
    private fun roomPhones(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getContactPhones().filter { it.contactId == owner }.map { it.number }.toSet() } }
    private fun roomEmails(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getContactEmails().filter { it.contactId == owner }.map { it.email }.toSet() } }
    private fun roomMessengers(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getMessengers().filter { it.contactId == owner }.map { it.value }.toSet() } }
    private fun roomDetails(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getPersonalDetails().filter { it.contactId == owner }.map { it.value }.toSet() } }
    private fun roomCompanyPhones(companyId: String): Set<String> { settle(); return runBlocking { db.contactDao().getContactPhones().filter { it.companyId == companyId }.map { it.number }.toSet() } }
    private fun backup(): BackupData = runBlocking {
        ExportManager.parseJsonBackup(ExportManager.backupJsonString()) ?: fail("backup JSON не парсится") as Nothing
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 3 — независимые писатели child/полей Contact.
    // ═════════════════════════════════════════════════════════════

    /** Notes: независимый CRUD; updateContact не должен ни менять таблицу, ни оставлять
     *  устаревшую вложенную проекцию (её читают SearchEngine/ContactNoteCodec/JSON). */
    @Test
    fun notes_staleContactUpdate_roomAndAllProjectionsKeepNote() {
        addContactAndWait(contact("cN"))
        val stale = AppStateStore.getContact("cN")!!
        val note = Note("n1", "cN", null, null, null, NoteType.GENERAL, "Аллергия на орехи", null, false,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        AppStateStore.addNote(note); settle()

        updateContactAndWait(stale, stale.copy(firstName = "Правка"))

        settle()
        assertEquals("Room: заметка на месте", listOf("Аллергия на орехи"), runBlocking { db.noteDao().getAllNotes().map { it.text } })
        assertEquals("память", listOf("n1"), AppStateStore.notes.map { it.id })
        assertEquals("вложенная проекция", listOf("n1"), AppStateStore.getContact("cN")!!.notes.map { it.id })
        assertEquals("JSON вложенный", listOf("n1"), backup().contacts.single { it.id == "cN" }.notes.map { it.id })
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", listOf("n1"), AppStateStore.getContact("cN")!!.notes.map { it.id })
    }

    /** lastContactDate: markContactedNow пишет его через DAO независимо — снимок не откатывает. */
    @Test
    fun lastContactDate_staleContactUpdate_doesNotRollBackNewerDate() {
        val today = java.time.LocalDate.now().toString()
        addContactAndWait(contact("cL", lastContactDate = "2025-01-01"))
        val stale = AppStateStore.getContact("cL")!!
        assertEquals("предусловие: устаревший снимок держит старую дату", "2025-01-01", stale.lastContactDate)

        AppStateStore.markContactedNow("cL"); settle()
        assertEquals("предусловие: Room уже содержит сегодняшнюю дату", today, roomContact("cL").lastContactDate)

        updateContactAndWait(stale, stale.copy(firstName = "Правка"))

        assertEquals("Room: дата не откатилась", today, roomContact("cL").lastContactDate)
        assertEquals("вложенная проекция", today, AppStateStore.getContact("cL")!!.lastContactDate)
        assertEquals("JSON", today, backup().contacts.single { it.id == "cL" }.lastContactDate)
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", today, AppStateStore.getContact("cL")!!.lastContactDate)
    }

    // ── связи с компанией: независимые писатели mergeCompanies/deleteCompany ──

    @Test
    fun relations_deleteCompany_thenUpdateEmployee_doesNotResurrectRelationInRoom() {
        addCompanyAndWait(company("coD"))
        addContactAndWait(contact("eD", rels = listOf(rel("rD", "eD", "coD"))))
        val heldBeforeDelete = AppStateStore.getContact("eD")!!

        runBlocking { AppStateStore.deleteCompany("coD").await() }
        assertTrue("предусловие: связь удалена из Room", roomRelations("eD").isEmpty())
        assertTrue("вложенная проекция сотрудника пересобрана после удаления компании",
            AppStateStore.getContact("eD")!!.companyRelations.isEmpty())

        // как свежий (уже пересобранный) объект, так и держащийся ДО удаления снимок
        updateFreshAndWait("eD") { it.copy(firstName = "Правка 1") }
        assertTrue("Room: связь с удалённой компанией не воскресла (свежий снимок)", roomRelations("eD").isEmpty())
        updateContactAndWait(heldBeforeDelete, heldBeforeDelete.copy(firstName = "Правка 2"))
        assertTrue("Room: связь не воскресла (снимок ДО удаления)", roomRelations("eD").isEmpty())
        assertTrue("память", AppStateStore.companyRelations.none { it.contactId == "eD" })
        assertTrue("вложенная проекция", AppStateStore.getContact("eD")!!.companyRelations.isEmpty())
    }

    @Test
    fun relations_mergeCompaniesRetarget_thenUpdateEmployee_keepsSurvivingCompany() {
        addCompanyAndWait(company("coK", phones = phones("K", 3)))
        addCompanyAndWait(company("coO"))
        addContactAndWait(contact("eK", rels = listOf(rel("rK", "eK", "coK"))))
        addContactAndWait(contact("eO", rels = listOf(rel("rO", "eO", "coO", position = "Аналитик"))))
        val heldBeforeMerge = AppStateStore.getContact("eO")!!

        AppStateStore.mergeCompanies(listOf("coK", "coO")); settle()
        assertEquals("предусловие: Room перенёс связь на выжившую компанию", "coK", roomRelations("eO").single().companyId)
        assertEquals("вложенная проекция сотрудника пересобрана", "coK", AppStateStore.getContact("eO")!!.companyRelations.single().companyId)

        updateFreshAndWait("eO") { it.copy(firstName = "Правка 1") }
        assertEquals("Room: перенос не откатился (свежий снимок)", "coK", roomRelations("eO").single().companyId)
        updateContactAndWait(heldBeforeMerge, heldBeforeMerge.copy(firstName = "Правка 2"))
        assertEquals("Room: перенос не откатился (снимок ДО слияния)", "coK", roomRelations("eO").single().companyId)
        assertEquals("память", "coK", AppStateStore.companyRelations.single { it.id == "rO" }.companyId)
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", "coK", AppStateStore.getContact("eO")!!.companyRelations.single().companyId)
        assertEquals("JSON", "coK", backup().contacts.single { it.id == "eO" }.companyRelations.single().companyId)
    }

    @Test
    fun relations_undoMergeCompanies_restoresProjectionAndSurvivesUnrelatedUpdate() {
        addCompanyAndWait(company("coK", phones = phones("K", 3)))
        addCompanyAndWait(company("coO"))
        addContactAndWait(contact("eO", rels = listOf(rel("rO", "eO", "coO"))))

        val snapshot = AppStateStore.mergeCompanies(listOf("coK", "coO"))!!; settle()
        runBlocking { AppStateStore.undoMergeCompanies(snapshot).await() }

        assertEquals("Room: связь вернулась к исходной компании", "coO", roomRelations("eO").single().companyId)
        assertEquals("память", "coO", AppStateStore.companyRelations.single { it.id == "rO" }.companyId)
        assertEquals("вложенная проекция", "coO", AppStateStore.getContact("eO")!!.companyRelations.single().companyId)

        updateFreshAndWait("eO") { it.copy(firstName = "После отмены") }
        assertEquals("несвязанное обновление не ломает восстановленную связь", "coO", roomRelations("eO").single().companyId)
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 5 — undoMerge (контакты): глобальные проекции возвращаются к снимку.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun undoMerge_restoresGlobalSizeRelationsDetails_dialogSaveDoesNotDuplicateRow() {
        addCompanyAndWait(company("coU"))
        val keepDetail = PersonalDetail("pd-keep", "cKeep", PersonalDetailCategory.LIKES, "Кофе")
        addContactAndWait(contact("cKeep", phones = phones("K", 3), rels = listOf(rel("rKeep", "cKeep", "coU")), personalDetails = listOf(keepDetail)))
        addContactAndWait(contact("cOther"))
        AppStateStore.setSizeInfo("cKeep", SizeInfo("sz-keep", "cKeep", clothingSize = "M")); settle()
        AppStateStore.setSizeInfo("cOther", SizeInfo("sz-other", "cOther", clothingSize = "L")); settle()

        val snapshot = AppStateStore.mergeContacts(listOf("cKeep", "cOther"))!!; settle()
        runBlocking { AppStateStore.undoMerge(snapshot).await() }

        // Room — эталон состояния «до слияния»; память обязана ему равняться.
        settle()
        val roomSizes = runBlocking { db.contactDao().getSizeInfos().map { it.toDomain() } }.sortedBy { it.contactId }
        assertEquals(listOf("cKeep:sz-keep:M", "cOther:sz-other:L"), roomSizes.map { "${it.contactId}:${it.id}:${it.clothingSize}" })
        assertEquals("память sizeInfos = Room после отмены слияния", roomSizes, AppStateStore.sizeInfos.toList().sortedBy { it.contactId })
        assertEquals("память companyRelations = Room", listOf("rKeep"), AppStateStore.companyRelations.filter { it.contactId == "cKeep" }.map { it.id })
        assertEquals("память personalDetails = Room", listOf("pd-keep"), AppStateStore.personalDetails.filter { it.contactId == "cKeep" }.map { it.id })

        // Диалог размеров берёт existing из ГЛОБАЛЬНОГО списка (ContactDetailScreen): вторая строка возникать не должна.
        val existing = AppStateStore.sizeInfos.find { it.contactId == "cKeep" }
        AppStateStore.setSizeInfo("cKeep", SizeInfo(existing?.id ?: java.util.UUID.randomUUID().toString(), "cKeep", clothingSize = "XL")); settle()
        val keepRows = roomSizeRows("cKeep")
        assertEquals("Room: ровно одна строка размеров у контакта", 1, keepRows.size)
        assertEquals("XL", keepRows.single().clothingSize)
    }

    // ═════════════════════════════════════════════════════════════
    //  «ЗАКОННАЯ ПРАВКА»: фикс не должен мешать намеренному редактированию.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun legit_contactChildrenEdits_addChangeRemove_stillPersist() {
        addCompanyAndWait(company("coL"))
        addContactAndWait(contact("cLegit"))
        val base = AppStateStore.getContact("cLegit")!!

        val p1 = ContactPhone("lp1", "cLegit", "+79001110001", PhoneType.MOBILE, true)
        val p2 = ContactPhone("lp2", "cLegit", "+79001110002", PhoneType.WORK, false)
        val e1 = ContactEmail("le1", "cLegit", "a@example.com", EmailType.PERSONAL, true)
        val m1 = Messenger("lm1", "cLegit", MessengerType.TELEGRAM, "@ivan", null, true)
        val d1 = PersonalDetail("ld1", "cLegit", PersonalDetailCategory.LIKES, "Чай")
        val r1 = rel("lr1", "cLegit", "coL")
        updateContactAndWait(base, base.copy(phones = listOf(p1, p2), emails = listOf(e1), messengers = listOf(m1), personalDetails = listOf(d1), companyRelations = listOf(r1)))

        assertEquals(setOf("+79001110001", "+79001110002"), roomPhones("cLegit"))
        assertEquals(setOf("a@example.com"), roomEmails("cLegit"))
        assertEquals(setOf("@ivan"), roomMessengers("cLegit"))
        assertEquals(setOf("Чай"), roomDetails("cLegit"))
        assertEquals(listOf("lr1"), roomRelations("cLegit").map { it.id })

        // намеренное изменение и удаление
        val cur = AppStateStore.getContact("cLegit")!!
        updateContactAndWait(cur, cur.copy(
            phones = listOf(p1.copy(number = "+79001119999")), emails = emptyList(), messengers = emptyList(),
            personalDetails = emptyList(), companyRelations = emptyList()
        ))
        assertEquals("изменённый телефон, удалённый второй", setOf("+79001119999"), roomPhones("cLegit"))
        assertTrue(roomEmails("cLegit").isEmpty())
        assertTrue(roomMessengers("cLegit").isEmpty())
        assertTrue(roomDetails("cLegit").isEmpty())
        assertTrue("намеренно удалённая связь с СУЩЕСТВУЮЩЕЙ компанией остаётся удалённой", roomRelations("cLegit").isEmpty())
    }

    @Test
    fun legit_companyPhonesEdit_stillPersists() {
        addCompanyAndWait(company("coP", phones = phones("C", 2)))
        val cur = AppStateStore.getCompany("coP")!!
        val kept = cur.phones.first().copy(number = "+79005550000")
        updateCompanyAndWait(cur, cur.copy(phones = listOf(kept)))

        assertEquals("телефон компании изменён, второй удалён", setOf("+79005550000"), roomCompanyPhones("coP"))
        assertEquals(listOf("+79005550000"), AppStateStore.getCompany("coP")!!.phones.map { it.number })
    }
}
