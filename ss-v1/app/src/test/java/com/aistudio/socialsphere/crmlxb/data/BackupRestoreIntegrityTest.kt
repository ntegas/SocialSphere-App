package com.aistudio.socialsphere.crmlxb.data

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.*
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.ui.screens.performImport
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.addressRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.emailRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.nameRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.phoneRow
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate
import com.aistudio.socialsphere.crmlxb.utils.PhoneContactSync
import com.aistudio.socialsphere.crmlxb.utils.PhoneSyncResult
import com.aistudio.socialsphere.crmlxb.utils.VcfFixtures
import com.aistudio.socialsphere.crmlxb.utils.VcfImport
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BACKUP INTEGRITY PACK #3 (2026-09-21) — согласованный снимок Room + не-деструктивный restore.
 *
 * Инвариант: значение, успешно сохранённое и явно не удалённое пользователем, переживает
 * backup -> restore -> повторный restore и не может быть молча уничтожено восстановлением
 * более старого backup поверх более новых данных.
 *
 * Обозначения тестов соответствуют «TARGETED TEST SET» задачи (A–O). Ожидаемые значения — из
 * явно заданных констант и НЕЗАВИСИМЫХ чтений Room (DAO), а не из тестируемого кода/памяти.
 * Никаких delay() как доказательства синхронизации: порядок доказывается Deferred и гейтом писателя;
 * единственное ограниченное ожидание — негативная проверка «backup НЕ завершился, пока Save не закоммичен».
 */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreIntegrityTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.defaultTagsSeeded.value = true          // без сидов: теги сравниваются 1:1
        db = freshDb()
        AppStateStore.resetForTests()
        AppStateStore.writeGateForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.initialize(ctx, db)
        waitLoaded()
    }

    @After
    fun tearDown() {
        AppStateStore.writeFaultForTests = null
        AppStateStore.writeGateForTests?.complete(Unit)
        AppStateStore.writeGateForTests = null
        FakeContactsProvider.reset()
        clearMemory()
        db.close()
    }

    // ─── обвязка ────────────────────────────────────────────────

    private fun freshDb(): SocialsphereDatabase =
        Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()

    private fun waitLoaded() {
        val settled = pollUntilTrue(timeoutMs = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут")
    }

    /** Настоящая чистая установка: НОВАЯ пустая Room-БД (а не только очистка памяти). */
    private fun restartOnEmptyDb() {
        clearMemory()
        db.close()
        db = freshDb()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        waitLoaded()
        clearMemory()
    }

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

    private fun <T> awaitOffMainThread(timeoutMs: Long = 30_000, block: suspend () -> T): T {
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

    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }
    private fun closeGate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { AppStateStore.writeGateForTests = it }
    private fun backupJson(): String = runBlocking { ExportManager.backupJsonString() }
    private fun backup(): BackupData = ExportManager.parseJsonBackup(backupJson()) ?: fail("backup JSON не парсится") as Nothing
    private fun restore(json: String): Int = awaitOffMainThread { ExportManager.importJsonBackup(json) }
    private fun toJson(data: BackupData): String = ExportManager.backupAdapter.toJson(data)

    // ─── фикстуры ───────────────────────────────────────────────

    private val t0 = "2026-01-01T00:00:00"

    private fun contact(
        id: String, firstName: String = "Иван", phones: List<ContactPhone> = emptyList(),
        addresses: List<Address> = emptyList(), sizeInfo: SizeInfo? = null,
        gifts: List<GiftIdea> = emptyList(), notes: List<Note> = emptyList(),
    ) = Contact(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = phones, addresses = addresses, sizeInfo = sizeInfo, gifts = gifts, notes = notes,
        createdAt = t0, updatedAt = t0
    )
    private fun phone(cid: String, id: String, number: String, primary: Boolean = false) =
        ContactPhone(id, cid, number, PhoneType.MOBILE, primary)
    private fun addr(cid: String, id: String, line: String = "Ленина 5") =
        Address(id, AddressOwnerType.CONTACT, cid, AddressType.HOME, line, "Москва", "РФ")
    private fun gift(id: String, cid: String, title: String = "Книга") = GiftIdea(id, cid, title, null, null, null, GiftStatus.IDEA)
    private fun note(id: String, cid: String, text: String) = Note(id, cid, null, null, null, NoteType.GENERAL, text, null, false,
        createdAt = t0, updatedAt = t0)
    private fun calItem(id: String) = CalendarItem(id = id, title = "Событие", type = CalendarItemType.MEETING, startDate = "2027-01-01",
        isAllDay = true, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, createdAt = t0, updatedAt = t0)

    private fun addContactAndWait(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }

    // независимые чтения Room (DAO) — НЕ память, НЕ тестируемый код
    private data class RoomDump(
        val contacts: List<ContactEntity>, val companies: List<CompanyEntity>,
        val companyRelations: List<ContactCompanyRelationEntity>, val contactRelations: List<ContactRelationEntity>,
        val phones: List<ContactPhoneEntity>, val emails: List<ContactEmailEntity>, val messengers: List<MessengerEntity>,
        val addresses: List<AddressEntity>, val calendarItems: List<CalendarItemEntity>,
        val links: List<CalendarItemLinkEntity>, val reminders: List<ReminderRuleEntity>,
        val notes: List<NoteEntity>, val gifts: List<GiftIdeaEntity>, val sizeInfos: List<SizeInfoEntity>,
        val personalDetails: List<PersonalDetailEntity>, val groups: List<ContactGroupEntity>,
        val groupMembers: List<ContactGroupMemberEntity>, val tags: List<TagEntity>, val tagMembers: List<ContactTagMemberEntity>,
    )

    private fun dumpRoom(): RoomDump = runBlocking {
        RoomDump(
            db.contactDao().getAllContacts().sortedBy { it.id }, db.companyDao().getAllCompanies().sortedBy { it.id },
            db.contactDao().getContactCompanyRelations().sortedBy { it.id }, db.contactDao().getContactRelations().sortedBy { it.id },
            db.contactDao().getContactPhones().sortedBy { it.id }, db.contactDao().getContactEmails().sortedBy { it.id },
            db.contactDao().getMessengers().sortedBy { it.id }, db.addressDao().getAllAddresses().sortedBy { it.id },
            db.calendarDao().getAllCalendarItems().sortedBy { it.id }, db.calendarDao().getCalendarItemLinks().sortedBy { it.id },
            db.calendarDao().getReminderRules().sortedBy { it.id }, db.noteDao().getAllNotes().sortedBy { it.id },
            db.giftDao().getAllGifts().sortedBy { it.id }, db.contactDao().getSizeInfos().sortedBy { it.id },
            db.contactDao().getPersonalDetails().sortedBy { it.id }, db.contactDao().getContactGroups().sortedBy { it.id },
            db.contactDao().getContactGroupMembers().sortedBy { it.id },
            db.contactDao().getTags().first().sortedBy { it.id }, db.contactDao().getContactTagMembers().first().sortedBy { it.id },
        )
    }

    private fun assertDumpsEqual(expected: RoomDump, actual: RoomDump) {
        assertEquals("contacts", expected.contacts, actual.contacts)
        assertEquals("companies", expected.companies, actual.companies)
        assertEquals("contact_company_relations", expected.companyRelations, actual.companyRelations)
        assertEquals("contact_relations", expected.contactRelations, actual.contactRelations)
        assertEquals("contact_phones (контакты+компании)", expected.phones, actual.phones)
        assertEquals("contact_emails (контакты+компании)", expected.emails, actual.emails)
        assertEquals("messengers", expected.messengers, actual.messengers)
        assertEquals("addresses (+координаты)", expected.addresses, actual.addresses)
        assertEquals("calendar_items", expected.calendarItems, actual.calendarItems)
        assertEquals("calendar_item_links", expected.links, actual.links)
        assertEquals("reminder_rules", expected.reminders, actual.reminders)
        assertEquals("notes", expected.notes, actual.notes)
        assertEquals("gift_ideas", expected.gifts, actual.gifts)
        assertEquals("size_infos", expected.sizeInfos, actual.sizeInfos)
        assertEquals("personal_details", expected.personalDetails, actual.personalDetails)
        assertEquals("contact_groups", expected.groups, actual.groups)
        assertEquals("contact_group_members", expected.groupMembers, actual.groupMembers)
        assertEquals("tags", expected.tags, actual.tags)
        assertEquals("contact_tag_members", expected.tagMembers, actual.tagMembers)
    }

    // ═════════════════════════════════════════════════════════════
    //  A. FRONT 4 — acknowledged Save -> немедленный backup содержит значение.
    //     Плюс порядок: backup, начатый ПОСЛЕ принятого Save, не завершается, пока Save не закоммичен.
    // ═════════════════════════════════════════════════════════════

    /** Ограниченное негативное ожидание: backup не завершился, пока писатель не закоммитил Save. */
    private fun assertBackupWaitsForGatedSave(save: Deferred<Unit>, gate: CompletableDeferred<Unit>): BackupData {
        val backupJob = CoroutineScope(Dispatchers.IO).async { ExportManager.backupJsonString() }
        assertFalse("backup не вправе завершиться (и тем более читать старый снимок) до коммита принятого Save",
            pollUntilTrue(800) { backupJob.isCompleted })
        gate.complete(Unit)
        runBlocking { save.await() }
        return ExportManager.parseJsonBackup(runBlocking { backupJob.await() }) ?: fail("backup JSON не парсится") as Nothing
    }

    @Test
    fun A_giftSave_immediateBackup_containsGift() {
        addContactAndWait(contact("cA"))
        runBlocking { AppStateStore.addGift(gift("gA", "cA", "Часы")).await() }             // acknowledged
        val b = backup()                                                                     // сразу, без delay
        assertEquals(listOf("gA"), b.gifts.map { it.id })
        assertEquals("вложенный и плоский списки согласованы (один снимок)", listOf("gA"), b.contacts.single().gifts.map { it.id })
    }

    @Test
    fun A_giftSave_backupStartedDuringGatedWriter_waitsAndContainsGift() {
        addContactAndWait(contact("cA2"))
        val gate = closeGate()
        val save = AppStateStore.addGift(gift("gA2", "cA2", "Часы"))
        val b = assertBackupWaitsForGatedSave(save, gate)
        assertEquals(listOf("gA2"), b.gifts.map { it.id })
        assertEquals(listOf("gA2"), b.contacts.single().gifts.map { it.id })
    }

    @Test
    fun A_noteSave_immediateBackup_andGatedOrdering() {
        addContactAndWait(contact("cN"))
        runBlocking { AppStateStore.addNote(note("nN1", "cN", "первая")).await() }
        assertEquals(listOf("nN1"), backup().notes.map { it.id })

        val gate = closeGate()
        val save = AppStateStore.addNote(note("nN2", "cN", "вторая"))
        val b = assertBackupWaitsForGatedSave(save, gate)
        assertEquals(setOf("nN1", "nN2"), b.notes.map { it.id }.toSet())
        assertEquals(setOf("nN1", "nN2"), b.contacts.single().notes.map { it.id }.toSet())
    }

    @Test
    fun A_sizeInfoSave_immediateBackup_andGatedOrdering_currentValues() {
        addContactAndWait(contact("cS"))
        runBlocking { AppStateStore.setSizeInfo("cS", SizeInfo("sS", "cS", clothingSize = "M", shoeSize = "42")).await() }
        assertEquals("M", backup().contacts.single().sizeInfo?.clothingSize)

        val gate = closeGate()
        val save = AppStateStore.setSizeInfo("cS", SizeInfo("sS", "cS", clothingSize = "L", shoeSize = "43", ringSize = "17"))
        val b = assertBackupWaitsForGatedSave(save, gate)
        val s = b.contacts.single().sizeInfo!!
        assertEquals(Triple("L", "43", "17"), Triple(s.clothingSize, s.shoeSize, s.ringSize))
    }

    @Test
    fun A_coordinatesSave_immediateBackup_andGatedOrdering() {
        addContactAndWait(contact("cC", addresses = listOf(addr("cC", "aC"))))
        val gate = closeGate()
        val save = AppStateStore.setAddressCoords("aC", 55.75, 37.61)
        val b = assertBackupWaitsForGatedSave(save, gate)
        val a = b.contacts.single().addresses.single()
        assertEquals(55.75 to 37.61, a.latitude to a.longitude)
    }

    // ═════════════════════════════════════════════════════════════
    //  B / C. FRONT 3 — backup берётся из Room, а не из (возможно устаревшей) памяти.
    // ═════════════════════════════════════════════════════════════

    /** B: Room: latitude=55.75; вложенная проекция в памяти: null. Backup ОБЯЗАН содержать 55.75. */
    @Test
    fun B_addressCanonicalCoordinates_overrideStaleNestedMemory() {
        addContactAndWait(contact("cB", addresses = listOf(addr("cB", "aB"))))
        // координаты попали в Room МИМО памяти (другой процесс/receiver/устаревший reload)
        runBlocking {
            db.addressDao().insertAddresses(listOf(addr("cB", "aB").copy(latitude = 55.75, longitude = 37.61).toEntity()))
        }
        assertEquals("предусловие: память устарела", null, AppStateStore.getContact("cB")!!.addresses.single().latitude)

        val a = backup().contacts.single().addresses.single()

        assertEquals(55.75, a.latitude)
        assertEquals(37.61, a.longitude)
    }

    /** C: актуальные размеры из Room, а не устаревшая память. */
    @Test
    fun C_sizeInfoCurrentValuesExported_fromRoomNotStaleMemory() {
        addContactAndWait(contact("cC2", sizeInfo = SizeInfo("sC2", "cC2", clothingSize = "S")))
        runBlocking { db.contactDao().insertSizeInfo(SizeInfoEntity("sC2", "cC2", "XL", "45", null, "заметка")) }
        assertEquals("предусловие: память устарела", "S", AppStateStore.getContact("cC2")!!.sizeInfo!!.clothingSize)

        val s = backup().contacts.single().sizeInfo!!

        assertEquals(listOf("XL", "45", "заметка"), listOf(s.clothingSize, s.shoeSize, s.other))
    }

    /** Gifts/Notes: плоский и вложенный списки — из ОДНОГО снимка Room (нет mixed-version). */
    @Test
    fun flatAndNestedGiftsAndNotes_comeFromOneSnapshot() {
        addContactAndWait(contact("cF"))
        runBlocking {
            db.giftDao().insertGifts(listOf(GiftIdeaEntity("gF", "cF", "Только-в-Room", null, null, null, null, "IDEA")))
            db.noteDao().insertNotes(listOf(note("nF", "cF", "Только-в-Room").toEntity()))
        }
        val b = backup()
        assertEquals(listOf("gF"), b.gifts.map { it.id })
        assertEquals("nested == flat", b.gifts.map { it.id }, b.contacts.single().gifts.map { it.id })
        assertEquals(listOf("nF"), b.notes.map { it.id })
        assertEquals("nested == flat", b.notes.map { it.id }, b.contacts.single().notes.map { it.id })
    }

    // ═════════════════════════════════════════════════════════════
    //  D / O / J. FRONT 5 — canary: максимально заполненная БД -> backup -> пустая БД -> 1:1 по Room.
    // ═════════════════════════════════════════════════════════════

    private fun populateCanary() {
        val co = Company(
            id = "co-can", name = "Acme Canary", logoUri = "/photos/logo.png", industry = Industry.LOGISTICS,
            description = "Описание компании", website = "https://acme.example",
            phones = listOf(ContactPhone("cop1", "co-can", "+74950000001", PhoneType.WORK, true, "офис"),
                ContactPhone("cop2", "co-can", "+74950000002", PhoneType.OTHER, false)),
            emails = listOf(ContactEmail("coe1", "co-can", "info@acme.example", EmailType.WORK, true),
                ContactEmail("coe2", "co-can", "sales@acme.example", EmailType.WORK, false, "продажи")),
            addresses = listOf(
                Address("coa1", AddressOwnerType.COMPANY, "co-can", AddressType.OFFICE, "Тверская 1", "Москва", "РФ", "главный офис", postalCode = "101000", district = "Тверской"),
                Address("coa2", AddressOwnerType.COMPANY, "co-can", AddressType.BRANCH, "Невский 2", "Санкт-Петербург", "РФ")),
            createdAt = t0, updatedAt = t0)
        runBlocking { AppStateStore.addCompany(co).await() }

        val c1 = Contact(
            id = "c-can1", firstName = "Анна", lastName = "Каноничная", middleName = "Сергеевна", nickname = "Аня",
            namePrefix = "Dr.", nameSuffix = "мл.", phoneticFirstName = "Анна-ф", phoneticMiddleName = "Сергеевна-ф",
            phoneticLastName = "Каноничная-ф", photoUri = "/photos/anna.jpg", relationshipType = RelationshipType.CLIENT,
            customRelationshipType = "Кума", secondaryRelationshipTypes = listOf(RelationshipType.FRIEND, RelationshipType.COLLEAGUE),
            connectionLevel = ConnectionLevel.CLOSE, importanceLevel = ImportanceLevel.KEY, socialRole = SocialRole.EXPERT,
            communicationRhythm = CommunicationRhythm.CUSTOM, customRhythmDays = 45, contactStatus = ContactStatus.MAINTAIN,
            lastContactDate = "2026-09-01", nextStep = "Позвонить в пятницу", familyNote = "Муж и двое детей",
            profession = "Юрист", canHelpWith = "Договоры", iCanHelpWith = "Контакты", talkingPoints = "Футбол",
            meetContext = "Конференция", meetDate = "2024-05-05", deviceContactId = "device_contact_77", deviceLookupKey = "lookup-canary-77",
            companyRelations = listOf(ContactCompanyRelation("rel-can1", "c-can1", "co-can", "Директор", "Отдел продаж",
                "Руководитель", EmploymentStatus.CURRENT, "2020-01-01", null, "Управление", "Ключевые клиенты", "заметка",
                "coa1", true)),
            phones = listOf(phone("c-can1", "p1", "+79000000001", true), phone("c-can1", "p2", "+79000000002"),
                ContactPhone("p3", "c-can1", "+74950000003", PhoneType.WORK, false, "рабочий")),
            emails = listOf(ContactEmail("e1", "c-can1", "anna@example.com", EmailType.PERSONAL, true),
                ContactEmail("e2", "c-can1", "anna@work.example", EmailType.WORK, false, "работа")),
            messengers = listOf(Messenger("m1", "c-can1", MessengerType.TELEGRAM, "@anna", "https://t.me/anna", true, "основной"),
                Messenger("m2", "c-can1", MessengerType.WHATSAPP, "+79000000001", null, false)),
            addresses = listOf(
                Address("ca1", AddressOwnerType.CONTACT, "c-can1", AddressType.HOME, "Ленина 5", "Москва", "РФ", "домофон 12", postalCode = "119000", district = "Арбат"),
                Address("ca2", AddressOwnerType.CONTACT, "c-can1", AddressType.WORK, "Тверская 1", "Москва", "РФ")),
            sizeInfo = SizeInfo("s-can1", "c-can1", "M", "39", "16", "перчатки S"),
            personalDetails = listOf(PersonalDetail("d1", "c-can1", PersonalDetailCategory.LIKES, "чай", "зелёный"),
                PersonalDetail("d2", "c-can1", PersonalDetailCategory.ALLERGIES, "орехи")),
            createdAt = t0, updatedAt = t0)
        runBlocking { AppStateStore.addContact(c1).await() }
        val c2 = contact("c-can2", "Борис", phones = listOf(phone("c-can2", "p4", "+79000000004", true)))
        runBlocking { AppStateStore.addContact(c2).await() }

        AppStateStore.setAddressCoords("ca1", 55.75, 37.61)
        AppStateStore.setAddressCoords("coa1", 55.76, 37.62)
        listOf(
            Note("n-can1", "c-can1", null, null, null, NoteType.GENERAL, "Заметка контакта", "2026-08-01", true, true, t0, t0),
            Note("n-can2", null, "co-can", null, null, NoteType.WORK, "Заметка компании", null, false, false, t0, t0),
            Note("n-can3", null, null, "cal-can1", null, NoteType.DATE_EVENT, "Заметка события", null, false, false, t0, t0),
        ).forEach { AppStateStore.addNote(it) }
        listOf(
            GiftIdea("g-can1", "c-can1", "Книга", "автор Х", "https://shop.example/1", "2026-12-01", GiftStatus.IDEA),
            GiftIdea("g-can2", "c-can1", "Шарф", null, null, null, GiftStatus.BOUGHT),
            GiftIdea("g-can3", "c-can1", "Часы", "подарено", null, "2025-12-31", GiftStatus.GIVEN),
        ).forEach { AppStateStore.addGift(it) }
        AppStateStore.addContactRelation(ContactRelation("cr-can1", "c-can1", "c-can2", "Друг", "Друг", "школа"))

        val g1 = AppStateStore.addGroup("Клиенты")!!; val g2 = AppStateStore.addGroup("Футбол")!!
        AppStateStore.setContactGroups("c-can1", setOf(g1.id, g2.id)); AppStateStore.setContactGroups("c-can2", setOf(g1.id))
        val t1 = AppStateStore.addTag("КаноТег-1", "Категория А")!!; val t2 = AppStateStore.addTag("КаноТег-2")!!
        AppStateStore.setContactTags("c-can1", setOf(t1.id, t2.id)); AppStateStore.setContactTags("c-can2", setOf(t1.id))

        val cal1 = CalendarItem(
            id = "cal-can1", title = "Встреча с Анной", description = "обсудить договор", type = CalendarItemType.MEETING,
            startDate = "2027-03-10", startTime = "10:30", endDate = "2027-03-10", endTime = "11:30", isAllDay = false,
            status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.IMPORTANT, colorKey = "blue",
            recurrenceRule = "FREQ=WEEKLY", lastCompletedOccurrenceDate = "2027-03-03", recurrenceExplicitlySet = true,
            postponedFromOccurrenceDate = "2027-03-17", postponedToDate = "2027-03-19",
            links = listOf(CalendarItemLink("lnk1", "cal-can1", CalendarTargetType.CONTACT, "c-can1"),
                CalendarItemLink("lnk2", "cal-can1", CalendarTargetType.COMPANY, "co-can")),
            reminders = listOf(ReminderRule("rem1", "cal-can1", ReminderType.BEFORE, 30, ReminderOffsetUnit.MINUTES, null),
                ReminderRule("rem2", "cal-can1", ReminderType.CUSTOM_DATE_TIME, null, null, "2027-03-09T09:00")),
            createdAt = t0, updatedAt = t0)
        val cal2 = CalendarItem(id = "cal-can2", title = "ДР Бориса", type = CalendarItemType.BIRTHDAY, startDate = "1990-06-15",
            isAllDay = true, status = CalendarItemStatus.COMPLETED, importance = ImportanceLevel.NORMAL, recurrenceRule = null,
            recurrenceExplicitlySet = true, links = listOf(CalendarItemLink("lnk3", "cal-can2", CalendarTargetType.CONTACT, "c-can2")),
            createdAt = t0, updatedAt = t0)
        runBlocking { AppStateStore.addCalendarItem(cal1).await(); AppStateStore.addCalendarItem(cal2).await() }
        settle()
    }

    /** D: полная БД -> backup -> ПУСТАЯ БД -> restore -> Room совпадает поле-в-поле по всем 19 таблицам. */
    @Test
    fun D_fullPopulatedDatabase_backup_restoreToEmptyDb_roomEqualsFieldByField() {
        populateCanary()
        val json = backupJson()
        val before = dumpRoom()
        assertTrue("предусловие: канарейка реально заполнена", before.phones.size >= 5 && before.gifts.size == 3 &&
            before.links.size == 3 && before.reminders.size == 2 && before.tagMembers.size == 3 && before.groupMembers.size == 3)

        restartOnEmptyDb()
        assertTrue("предусловие: новая БД пуста", dumpRoom().contacts.isEmpty())
        val restored = restore(json)

        assertEquals(2, restored)
        assertDumpsEqual(before, dumpRoom())
    }

    /** O: id связей/членств/дочерних строк сохранены (не перегенерированы). */
    @Test
    fun O_linksMembershipsAndChildIds_preservedOnRestoreToEmpty() {
        populateCanary()
        val json = backupJson()
        val before = dumpRoom()
        restartOnEmptyDb()
        restore(json)
        val after = dumpRoom()
        assertEquals(before.links.map { it.id }, after.links.map { it.id })
        assertEquals(before.reminders.map { it.id }, after.reminders.map { it.id })
        assertEquals(before.groupMembers.map { it.id }, after.groupMembers.map { it.id })
        assertEquals(before.tagMembers.map { it.id }, after.tagMembers.map { it.id })
        assertEquals(before.companyRelations.map { it.id }, after.companyRelations.map { it.id })
        assertEquals(before.contactRelations.map { it.id }, after.contactRelations.map { it.id })
        assertEquals(before.phones.map { it.id }, after.phones.map { it.id })
        assertEquals(before.sizeInfos.map { it.id }, after.sizeInfos.map { it.id })
    }

    /** J: один и тот же backup ×3 — без дублей во ВСЕХ таблицах (и без изменений после первого). */
    @Test
    fun J_sameBackupRestoredThreeTimes_noDuplicatesAnywhere() {
        populateCanary()
        val json = backupJson()
        restartOnEmptyDb()
        restore(json)
        val afterFirst = dumpRoom()
        restore(json)
        restore(json)
        assertDumpsEqual(afterFirst, dumpRoom())
    }

    // ═════════════════════════════════════════════════════════════
    //  E–I, K. FRONT 6–10 — restore старого backup поверх более новых данных не разрушает текущее.
    // ═════════════════════════════════════════════════════════════

    private fun roomAddress(id: String): AddressEntity? = runBlocking { db.addressDao().getAllAddresses().firstOrNull { it.id == id } }
    private fun roomPhones(cid: String): Set<String> = runBlocking { db.contactDao().getContactPhones().filter { it.contactId == cid }.map { it.id }.toSet() }
    private fun roomContact(id: String): ContactEntity = runBlocking { db.contactDao().getAllContacts().first { it.id == id } }
    private fun roomCalendar(id: String): CalendarItemEntity = runBlocking { db.calendarDao().getAllCalendarItems().first { it.id == id } }

    /** E: текущие координаты 55.75; старый backup — поля координат нет вовсе -> текущие остаются. */
    @Test
    fun E_oldBackupWithoutCoordinates_overCurrentCoordinates_keepsCurrent() {
        addContactAndWait(contact("cE", addresses = listOf(addr("cE", "aE"))))
        AppStateStore.setAddressCoords("aE", 55.75, 37.61); settle()
        val old = toJson(BackupData(version = 3, contacts = listOf(contact("cE", addresses = listOf(addr("cE", "aE"))))))

        restore(old)

        val row = roomAddress("aE")!!
        assertEquals(55.75, row.latitude); assertEquals(37.61, row.longitude)
        val mem = AppStateStore.getContact("cE")!!.addresses.single()
        assertEquals("память после restore = Room", 55.75, mem.latitude)
    }

    /** F: старый backup без полей v19/v20 (recurrence/postpone) поверх более новых текущих значений. */
    @Test
    fun F_oldBackupWithoutRecurrenceFields_keepsCurrentNewerFields() {
        val current = calItem("cal-F").copy(recurrenceRule = "FREQ=WEEKLY", recurrenceExplicitlySet = true,
            lastCompletedOccurrenceDate = "2027-02-01", postponedFromOccurrenceDate = "2027-02-08", postponedToDate = "2027-02-10")
        runBlocking { AppStateStore.addCalendarItem(current).await() }
        val oldJson = """{"version":4,"calendarItems":[{"id":"cal-F","title":"Событие","type":"MEETING","startDate":"2027-01-01",
            "isAllDay":true,"status":"ACTIVE","importance":"NORMAL","createdAt":"$t0","updatedAt":"$t0"}]}"""

        restore(oldJson)

        val row = roomCalendar("cal-F")
        assertEquals("FREQ=WEEKLY", row.recurrenceRule)
        assertTrue("recurrenceExplicitlySet не сброшен в false", row.recurrenceExplicitlySet)
        assertEquals("2027-02-01", row.lastCompletedOccurrenceDate)
        assertEquals("2027-02-08" to "2027-02-10", row.postponedFromOccurrenceDate to row.postponedToDate)
    }

    /** G: current-only подарок переживает restore старого backup. */
    @Test
    fun G_currentOnlyGift_survivesOldBackupRestore() {
        addContactAndWait(contact("cG"))
        runBlocking { AppStateStore.addGift(gift("g-old", "cG", "Старый")).await(); AppStateStore.addGift(gift("g-new", "cG", "Новый")).await() }
        val old = toJson(BackupData(version = 5, contacts = listOf(contact("cG")), gifts = listOf(gift("g-old", "cG", "Старый"))))

        restore(old)

        assertEquals(setOf("g-old", "g-new"), runBlocking { db.giftDao().getAllGifts() }.map { it.id }.toSet())
        assertEquals(setOf("g-old", "g-new"), AppStateStore.gifts.map { it.id }.toSet())
    }

    /** H: current-only заметка переживает restore. */
    @Test
    fun H_currentOnlyNote_survivesOldBackupRestore() {
        addContactAndWait(contact("cH"))
        runBlocking { AppStateStore.addNote(note("n-old", "cH", "старая")).await(); AppStateStore.addNote(note("n-new", "cH", "новая")).await() }
        val old = toJson(BackupData(version = 5, contacts = listOf(contact("cH")), notes = listOf(note("n-old", "cH", "старая"))))

        restore(old)

        assertEquals(setOf("n-old", "n-new"), runBlocking { db.noteDao().getAllNotes() }.map { it.id }.toSet())
    }

    /** I: current-only телефон переживает restore (старый backup не доказывает удаление). */
    @Test
    fun I_currentOnlyPhone_survivesOldBackupRestore() {
        addContactAndWait(contact("cI", phones = listOf(phone("cI", "p-old", "+7900000001", true), phone("cI", "p-new", "+7900000002"))))
        val old = toJson(BackupData(version = 5, contacts = listOf(contact("cI", phones = listOf(phone("cI", "p-old", "+7900000001", true))))))

        restore(old)

        assertEquals(setOf("p-old", "p-new"), roomPhones("cI"))
        assertEquals(setOf("p-old", "p-new"), AppStateStore.getContact("cI")!!.phones.map { it.id }.toSet())
    }

    /** K: одно и то же id, расходящееся скалярное значение, нет метаданных свежести -> текущее НЕ затирается молча. */
    @Test
    fun K_sameIdConflictingScalar_noSilentDestructiveOverwrite() {
        addContactAndWait(contact("cK", firstName = "Иван П. (текущее)"))
        runBlocking { AppStateStore.addNote(note("nK", "cK", "текущий текст")).await() }
        val company = Company("coK", "Текущее название", null, Industry.IT, createdAt = t0, updatedAt = t0)
        runBlocking { AppStateStore.addCompany(company).await() }
        val old = toJson(BackupData(version = 5,
            contacts = listOf(contact("cK", firstName = "Иван (из старого backup)")),
            companies = listOf(company.copy(name = "Название из backup")),
            notes = listOf(note("nK", "cK", "текст из backup"))))

        restore(old)

        assertEquals("Иван П. (текущее)", roomContact("cK").firstName)
        assertEquals("Текущее название", runBlocking { db.companyDao().getAllCompanies() }.single().name)
        assertEquals("текущий текст", runBlocking { db.noteDao().getAllNotes() }.single().text)
    }

    // ═════════════════════════════════════════════════════════════
    //  M. FRONT 13 — после restore память == Room. N. FRONT 15 — неподдерживаемая версия: ноль записей.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun M_afterRestore_memoryEqualsRoom() {
        addContactAndWait(contact("cM", addresses = listOf(addr("cM", "aM"))))
        runBlocking { AppStateStore.addGift(gift("gM", "cM")).await(); AppStateStore.setSizeInfo("cM", SizeInfo("sM", "cM", clothingSize = "M")).await() }
        AppStateStore.setAddressCoords("aM", 55.75, 37.61); settle()
        val backupContact = contact("cM", addresses = listOf(addr("cM", "aM"))).copy(sizeInfo = SizeInfo("sM", "cM", clothingSize = "S"))
        val old = toJson(BackupData(version = 5, contacts = listOf(backupContact, contact("cM2", "Новый из backup", sizeInfo = SizeInfo("sM2", "cM2", shoeSize = "40"))),
            gifts = listOf(gift("gM2", "cM2"))))

        restore(old)

        val memContacts = AppStateStore.contacts.toList(); val memGifts = AppStateStore.gifts.toList(); val memSizes = AppStateStore.sizeInfos.toList()
        val memAddresses = AppStateStore.addresses.toList(); val memNotes = AppStateStore.notes.toList()
        awaitOffMainThread { AppStateStore.reloadFromDb() }            // эталон: то, что реально в Room
        assertEquals("contacts (вложенные проекции gifts/notes/sizeInfo/addresses)", AppStateStore.contacts.toList(), memContacts)
        assertEquals(AppStateStore.gifts.toList(), memGifts)
        assertEquals(AppStateStore.sizeInfos.toList(), memSizes)
        assertEquals(AppStateStore.addresses.toList(), memAddresses)
        assertEquals(AppStateStore.notes.toList(), memNotes)
    }

    @Test
    fun N_unsupportedFutureBackupVersion_zeroMutation() {
        addContactAndWait(contact("cNv", phones = listOf(phone("cNv", "pNv", "+7900000009"))))
        settle()
        val before = dumpRoom(); val memBefore = AppStateStore.contacts.toList()
        val future = toJson(BackupData(version = 99, contacts = listOf(contact("cNv", "ЧУЖОЕ"), contact("cNew")), gifts = listOf(gift("gNew", "cNew"))))

        val result = restore(future)

        assertEquals(-1, result)
        assertDumpsEqual(before, dumpRoom())
        assertEquals(memBefore, AppStateStore.contacts.toList())
    }

    // ═════════════════════════════════════════════════════════════
    //  INTEGRATION DATA SAFETY GATE #1 — Packs #1–#4 вместе (Data Ownership + Durable Save + Backup + Phone/VCF).
    //  Ожидаемые значения — явные константы и независимые чтения Room. Сверка «до backup == после restore» дополнена
    //  явными проверками значений, чтобы одинаково неверные обе стороны не давали зелёный тест.
    // ═════════════════════════════════════════════════════════════

    /** Фейковая телефонная книга + разрешение READ_CONTACTS (тот же приём, что в PhoneContactSyncTest). */
    private fun installPhoneBook() {
        FakeContactsProvider.reset()
        org.robolectric.Robolectric.buildContentProvider(FakeContactsProvider::class.java).create(ContactsContract.AUTHORITY)
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    private fun pull(id: String): PhoneSyncResult = awaitOffMainThread { PhoneContactSync.pullFromPhone(ctx, id) }

    /** Правка скаляра «как из экрана»: свежий снимок -> copy -> updateContact(base, edited) -> дождаться коммита. */
    private fun updateNow(id: String, change: (Contact) -> Contact) {
        val base = AppStateStore.getContact(id)!!
        runBlocking { AppStateStore.updateContact(base, change(base)).await() }
    }

    private fun linkPair(c: ContactEntity): List<String?> = listOf(c.deviceContactId, c.deviceLookupKey)

    /**
     * Сценарий 3. Максимально заполненный Contact (со связью с Android-контактом) -> правка скаляра -> Update from Phone ->
     * SizeInfo -> Gift -> Note, каждый шаг — подтверждённый коммит -> backup -> ПУСТАЯ БД -> restore -> Room совпадает.
     */
    @Test
    fun gate_crossPack_maxContact_scalarEdit_phoneSync_sizeInfo_gift_note_backup_freshRestore() {
        populateCanary()                                              // c-can1: device_contact_77 / lookup-canary-77
        installPhoneBook()
        FakeContactsProvider.contact(77, "lookup-canary-77", nameRow("Анна", "Каноничная"),
            phoneRow("+79005550077"), emailRow("anna.phone@example.com"), addressRow("Арбат 1", "Москва", "Россия"))

        updateNow("c-can1") { it.copy(nextStep = "GATE: новый шаг") }                                   // 1. скаляр
        val r = pull("c-can1")                                                                            // 2. Update from Phone
        assertTrue("$r", r is PhoneSyncResult.Applied)
        runBlocking { AppStateStore.setSizeInfo("c-can1", SizeInfo("s-can1", "c-can1", "L", "42", "17", "перчатки M")).await() }   // 3
        runBlocking { AppStateStore.addGift(gift("g-gate", "c-can1", "Гейт-подарок")).await() }          // 4
        runBlocking { AppStateStore.addNote(note("n-gate", "c-can1", "Гейт-заметка")).await() }          // 5

        val json = backupJson()                                                                          // 6. сразу, без settle()
        val before = dumpRoom()
        restartOnEmptyDb()
        assertTrue("предусловие: новая БД пуста", dumpRoom().contacts.isEmpty())
        restore(json)
        val after = dumpRoom()

        assertDumpsEqual(before, after)
        val c = after.contacts.single { it.id == "c-can1" }
        assertEquals("скаляр", "GATE: новый шаг", c.nextStep)
        // Заметка типа GENERAL — это «общение» (Batch 2F): lastContactDate = сегодня; главное — дата монотонна и не откатилась к старой.
        assertTrue("lastContactDate не откатилась назад", c.lastContactDate!! >= "2026-09-01")
        assertEquals("lastContactDate == состояние до backup", before.contacts.single { it.id == "c-can1" }.lastContactDate, c.lastContactDate)
        assertEquals("связь с Android-контактом — парой", listOf<String?>("device_contact_77", "lookup-canary-77"), linkPair(c))
        assertEquals("phone-owned: телефоны (старые + новый)", setOf("+79000000001", "+79000000002", "+74950000003", "+79005550077"),
            after.phones.filter { it.contactId == "c-can1" }.map { it.number }.toSet())
        assertTrue("phone-owned: почта", after.emails.any { it.contactId == "c-can1" && it.email == "anna.phone@example.com" })
        assertTrue("phone-owned: адрес", after.addresses.any { it.ownerId == "c-can1" && it.addressLine == "Арбат 1" })
        assertEquals("координаты старого адреса пережили phone sync + backup + restore", 55.75 to 37.61,
            after.addresses.single { it.id == "ca1" }.let { it.latitude to it.longitude })
        assertEquals("SizeInfo", listOf<String?>("L", "42", "17", "перчатки M"),
            after.sizeInfos.single { it.contactId == "c-can1" }.let { listOf(it.clothingSize, it.shoeSize, it.ringSize, it.other) })
        assertEquals("Gifts", setOf("g-can1", "g-can2", "g-can3", "g-gate"), after.gifts.map { it.id }.toSet())
        assertEquals("Notes", setOf("n-can1", "n-can2", "n-can3", "n-gate"), after.notes.map { it.id }.toSet())
        assertEquals("связь с компанией", 1, after.companyRelations.count { it.contactId == "c-can1" })
        assertEquals("теги/группы", 2 to 2, after.tagMembers.count { it.contactId == "c-can1" } to after.groupMembers.count { it.contactId == "c-can1" })
        assertEquals("память после restore == Room", "GATE: новый шаг", AppStateStore.getContact("c-can1")!!.nextStep)
    }

    /**
     * Сценарий 4. Устаревший снимок + Update from Phone + backup: phone-owned NEW, внутреннее NEW и несвязанный скаляр из
     * СТАРОГО снимка NEW — всё в backup и всё воспроизводится restore'ом (Pack #2 merge + Pack #4 sync + Pack #3 backup).
     */
    @Test
    fun gate_staleSnapshot_phoneSync_unrelatedScalar_immediateBackup_freshRestore() {
        populateCanary()
        installPhoneBook()
        FakeContactsProvider.contact(77, "lookup-canary-77", nameRow("Анна", "Каноничная"), phoneRow("+79005550077"), emailRow("anna.phone@example.com"))
        val stale = AppStateStore.getContact("c-can1")!!                                       // старый снимок экрана

        updateNow("c-can1") { it.copy(familyNote = "GATE: семья NEW") }                        // независимо изменено внутреннее поле
        val r = pull("c-can1")                                                                 // Update from Phone
        assertTrue("$r", r is PhoneSyncResult.Applied)
        runBlocking { AppStateStore.updateContact(stale, stale.copy(talkingPoints = "GATE: темы NEW")).await() }   // из СТАРОГО снимка — другое поле

        val json = backupJson()                                                                // сразу после подтверждённых коммитов
        val inBackup = backup().contacts.single { it.id == "c-can1" }
        assertEquals("GATE: семья NEW", inBackup.familyNote)
        assertEquals("GATE: темы NEW", inBackup.talkingPoints)
        assertTrue("phone-owned NEW в backup", inBackup.phones.any { it.number == "+79005550077" } && inBackup.emails.any { it.email == "anna.phone@example.com" })
        assertEquals("остальное не откатилось", "Позвонить в пятницу", inBackup.nextStep)

        val before = dumpRoom()
        restartOnEmptyDb()
        restore(json)
        val after = dumpRoom()
        assertDumpsEqual(before, after)
        val c = after.contacts.single { it.id == "c-can1" }
        assertEquals(listOf("GATE: семья NEW", "GATE: темы NEW", "Позвонить в пятницу"), listOf(c.familyNote, c.talkingPoints, c.nextStep))
        assertTrue(after.phones.any { it.contactId == "c-can1" && it.number == "+79005550077" })
        assertEquals("подарки не тронуты", 3, after.gifts.count { it.contactId == "c-can1" })
    }

    /**
     * Сценарий 5а. Backup v6 (до Room 21, поля deviceLookupKey нет вовсе) -> чистая Room 21: restore не ломается, deviceContactId
     * не теряется; следующий Update from Phone по старой связи доводит её до LOOKUP_KEY, ничего не стирая.
     */
    @Test
    fun gate_oldBackupV6_withoutLookupKey_intoRoom21_keepsDeviceContactId_thenPhoneSyncUpgradesKey() {
        val old = toJson(BackupData(version = 6, contacts = listOf(
            contact("cL1", "Старый", phones = listOf(phone("cL1", "pL1", "+79005550005", true))).copy(deviceContactId = "device_contact_5"),
            contact("cL2", "Второй"))))
        assertFalse("предусловие: JSON версии 6 не знает про deviceLookupKey", old.contains("deviceLookupKey"))

        assertEquals(2, restore(old))
        val row = roomContact("cL1")
        assertEquals("deviceContactId не удалён, ключа пока нет", listOf<String?>("device_contact_5", null), linkPair(row))
        assertEquals(setOf("pL1"), roomPhones("cL1"))
        assertEquals("контакт без связи остался без связи", listOf<String?>(null, null), linkPair(roomContact("cL2")))

        installPhoneBook()
        FakeContactsProvider.contact(5, "KEY-5", nameRow("Старый", "Петров"), phoneRow("+79005550005"), phoneRow("+79005550006"))
        val r = pull("cL1")
        assertTrue("$r", r is PhoneSyncResult.Applied)
        assertEquals("связь доведена до пары", listOf<String?>("device_contact_5", "KEY-5"), linkPair(roomContact("cL1")))
        assertEquals("старый номер жив, новый добавлен, без дублей", setOf("+79005550005", "+79005550006"),
            dumpRoom().phones.filter { it.contactId == "cL1" }.map { it.number }.toSet())
        assertEquals(PhoneSyncResult.NotLinked, pull("cL2"))
    }

    /**
     * Сценарий 5б. Backup v7 из Room 21 -> Room 21: устойчивые пары двух контактов не перемешиваются; повторный restore того же
     * backup поверх РАЗОШЕДШЕЙСЯ связи оставляет текущую пару целой (не (id из одной связи + ключ из другой)).
     */
    @Test
    fun gate_v7BackupIntoRoom21_linkPairsNeverMixAcrossContacts_orAcrossDivergedLink() {
        addContactAndWait(contact("cX").copy(deviceContactId = "device_contact_1", deviceLookupKey = "KEY-1"))
        addContactAndWait(contact("cY").copy(deviceContactId = "device_contact_2", deviceLookupKey = "KEY-2"))
        settle()
        val json = backupJson()
        val b = backup()
        assertEquals(listOf(7, 22), listOf(b.version, b.roomSchemaVersion))   // roomSchemaVersion: EXPLICIT RECOVERY PACK bump 21→22
        assertEquals(mapOf("cX" to ("device_contact_1" to "KEY-1"), "cY" to ("device_contact_2" to "KEY-2")),
            b.contacts.associate { it.id to (it.deviceContactId to it.deviceLookupKey) })

        restartOnEmptyDb()
        restore(json)
        assertEquals(listOf<String?>("device_contact_1", "KEY-1"), linkPair(roomContact("cX")))
        assertEquals(listOf<String?>("device_contact_2", "KEY-2"), linkPair(roomContact("cY")))

        updateNow("cX") { it.copy(deviceContactId = "device_contact_3", deviceLookupKey = "KEY-3") }     // пользователь перепривязал cX
        restore(json)                                                                                     // старый backup поверх
        assertEquals("текущая пара целая, не смешана с backup", listOf<String?>("device_contact_3", "KEY-3"), linkPair(roomContact("cX")))
        assertEquals(listOf<String?>("device_contact_2", "KEY-2"), linkPair(roomContact("cY")))
    }

    /**
     * Сценарий 6. Связь как данные переезжает вместе с backup; на «другом устройстве» Android-контакта нет — это LinkGone, а не падение
     * и не потеря данных SocialSphere; путь Relink работает.
     */
    @Test
    fun gate_phoneLinkAfterRestore_androidContactAbsentOnThisDevice_isLinkGone_dataIntact_relinkWorks() {
        populateCanary()
        val json = backupJson()
        restartOnEmptyDb()                                            // «другое устройство»
        restore(json)
        installPhoneBook()                                            // телефонная книга здесь пуста

        assertEquals("связь доехала данными", listOf<String?>("device_contact_77", "lookup-canary-77"), linkPair(roomContact("c-can1")))
        val before = dumpRoom()
        assertEquals(PhoneSyncResult.LinkGone, pull("c-can1"))
        assertEquals("данные SocialSphere целы, связь не стёрта автоматически", before.contacts, dumpRoom().contacts)
        assertDumpsEqual(before, dumpRoom())

        FakeContactsProvider.contact(500, "NEWKEY-500", nameRow("Анна", "Каноничная"), phoneRow("+79001230000"))     // пользователь выбирает контакт заново
        runBlocking { PhoneContactSync.link(AppStateStore.getContact("c-can1")!!, ImportContactCandidate(id = "device_contact_500", deviceLookupKey = "NEWKEY-500")).await() }
        val r = pull("c-can1")
        assertTrue("$r", r is PhoneSyncResult.Applied)
        assertEquals(listOf<String?>("device_contact_500", "NEWKEY-500"), linkPair(roomContact("c-can1")))
        assertTrue(dumpRoom().phones.any { it.contactId == "c-can1" && it.number == "+79001230000" })
        assertEquals("остальное на месте", before.gifts, dumpRoom().gifts)
    }

    /** Сценарий 7. Одна vCard-фикстура (Unicode-имя, телефон, email, ADR, ORG/TITLE) -> канонический парсер -> импорт -> backup -> ПУСТАЯ БД -> restore. */
    @Test
    fun gate_vcfImport_durableSave_backup_freshRestore_sameImportedValuesInFinalRoom() {
        val vcf = VcfFixtures.bytes(VcfFixtures.lines("BEGIN:VCARD", "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("Деденко")};${VcfFixtures.qp("Алексей")};;;",
            "ORG;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("ООО Ромашка")}",
            "TITLE;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("Директор")}",
            "TEL;CELL:+7 900 111-22-33", "EMAIL;HOME:aleksey@example.com",
            "ADR;HOME;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;;${VcfFixtures.qp("ул. Ленина 5")};${VcfFixtures.qp("Москва")};;;${VcfFixtures.qp("Россия")}",
            "END:VCARD"))
        val uri = Uri.parse("content://com.android.contacts/contacts/as_vcard/gate-1")
        org.robolectric.Shadows.shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(vcf))

        val load = awaitOffMainThread { VcfImport.load(ctx, uri) }
        val candidates = (load as? VcfImport.Load.Loaded)?.candidates ?: fail("ожидалось Loaded, получено $load") as Nothing
        assertEquals(1, candidates.size)
        awaitOffMainThread { performImport(candidates, ctx) }

        fun assertImported(d: RoomDump) {
            val c = d.contacts.single()
            assertEquals(listOf("Алексей", "Деденко"), listOf(c.firstName, c.lastName))
            assertEquals(listOf("+7 900 111-22-33"), d.phones.filter { it.contactId == c.id }.map { it.number })
            assertEquals(listOf("aleksey@example.com"), d.emails.filter { it.contactId == c.id }.map { it.email })
            assertEquals(listOf(listOf("ул. Ленина 5", "Москва", "Россия")),
                d.addresses.filter { it.ownerId == c.id }.map { listOf(it.addressLine, it.city, it.country) })
            assertEquals(listOf("ООО Ромашка"), d.companies.map { it.name })
            assertEquals("Директор", d.companyRelations.single { it.contactId == c.id }.position)
        }
        val before = dumpRoom()
        assertImported(before)                                        // после подтверждённого сохранения импорта

        val json = backupJson()
        restartOnEmptyDb()
        restore(json)
        val after = dumpRoom()
        assertImported(after)                                         // те же значения в финальном состоянии Room
        assertDumpsEqual(before, after)
    }

    /**
     * Сценарий 8. Контракт сбоя: (а) сбой записи -> сохранение НЕ подтверждено, backup после сбоя не содержит «фантомов» оптимистичной
     * памяти; (б) сбой коммита во время Update from Phone -> CommitFailed, внутреннее состояние SocialSphere цело; память сходится с Room.
     * (Откат транзакции restore при сбое — RestoreSemanticsTest.L; повтор после устранения сбоя — DurableSaveContractTest/PhoneContactSyncTest.)
     */
    @Test
    fun gate_failureContract_writeFailure_backupHasNoPhantom_phoneSyncCommitFailure_keepsInternalStateIntact() {
        populateCanary()
        installPhoneBook()
        FakeContactsProvider.contact(77, "lookup-canary-77", nameRow("Анна", "Каноничная"), phoneRow("+79005550077"))
        val committed = dumpRoom()

        val s = AppStateStore.getContact("c-can1")!!
        AppStateStore.writeFaultForTests = java.io.IOException("disk full")
        val save = AppStateStore.updateContact(s, s.copy(firstName = "ФАНТОМ", nextStep = "ФАНТОМ-шаг"))
        val giftWrite = AppStateStore.addGift(gift("g-phantom", "c-can1", "Фантомный подарок"))
        assertFalse("сохранение НЕ подтверждено", runBlocking { AppStateStore.awaitDurableCommit(save) })
        assertFalse("подарок НЕ подтверждён", runBlocking { AppStateStore.awaitDurableCommit(giftWrite) })
        assertEquals("(б) сбой коммита во время Update from Phone", PhoneSyncResult.CommitFailed, pull("c-can1"))
        AppStateStore.writeFaultForTests = null

        assertDumpsEqual(committed, dumpRoom())                       // Room: ни фантомов, ни следов неудавшегося phone sync
        val inBackup = backup()
        val c = inBackup.contacts.single { it.id == "c-can1" }
        assertEquals("backup после сбоя: только подтверждённое", listOf("Анна", "Позвонить в пятницу"), listOf(c.firstName, c.nextStep))
        assertEquals(setOf("g-can1", "g-can2", "g-can3"), inBackup.gifts.map { it.id }.toSet())
        assertFalse("телефон из неудавшегося sync не попал в backup", inBackup.contacts.flatMap { it.phones }.any { it.number == "+79005550077" })

        val converged = pollUntilTrue(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            AppStateStore.getContact("c-can1")?.let { it.firstName == "Анна" && it.nextStep == "Позвонить в пятницу" } == true &&
                AppStateStore.gifts.none { it.id == "g-phantom" }
        }
        assertTrue("память пересобрана из Room — оптимистичных фантомов не осталось", converged)
    }
}
