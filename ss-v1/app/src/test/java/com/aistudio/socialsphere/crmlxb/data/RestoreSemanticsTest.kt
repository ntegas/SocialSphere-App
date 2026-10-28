package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.*
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.BACKUP_FORMAT_VERSION
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import com.aistudio.socialsphere.crmlxb.utils.ExportManager.BackupImportOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * BACKUP INTEGRITY PACK #3 (2026-09-21) — семантика restore, отчёт о результате, атомарность, версии.
 * Дополняет [BackupRestoreIntegrityTest] (сквозные сценарии A–O) проверками конкретных правил
 * слияния (см. шапку RestoreMerge.kt) и контракта RestoreResult.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreSemanticsTest {

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
        AppStateStore.restoreMidTransactionHookForTests = null
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading
        }
        if (!settled) fail("loadInitialData не завершился")
    }

    @After
    fun tearDown() {
        AppStateStore.restoreMidTransactionHookForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.writeGateForTests?.complete(Unit); AppStateStore.writeGateForTests = null
        clearMemory(); db.close()
    }

    // ─── обвязка ────────────────────────────────────────────────
    private fun clearMemory() {
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear()
        AppStateStore.gifts.clear(); AppStateStore.addresses.clear(); AppStateStore.calendarItems.clear()
        AppStateStore.companyRelations.clear(); AppStateStore.contactRelations.clear(); AppStateStore.groups.clear()
        AppStateStore.groupMembers.clear(); AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
        AppStateStore.personalDetails.clear(); AppStateStore.sizeInfos.clear()
    }
    private fun pollUntilTrue(timeoutMs: Long, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { kotlinx.coroutines.delay(20); ok = check() }
        ok
    }
    private fun <T> awaitOffMainThread(timeoutMs: Long = 30_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch { try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) } }
        val settled = pollUntilTrue(timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); done.isCompleted
        }
        if (!settled) fail("suspend-операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }
    private fun toJson(d: BackupData) = ExportManager.backupAdapter.toJson(d)
    private fun outcome(json: String): BackupImportOutcome = awaitOffMainThread { ExportManager.importJsonBackupDetailed(json) }
    private fun restored(json: String): RestoreResult =
        (outcome(json) as? BackupImportOutcome.Restored)?.result ?: fail("restore отвергнут") as Nothing
    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    private val t0 = "2026-01-01T00:00:00"
    private fun contact(id: String, first: String = "Иван", phones: List<ContactPhone> = emptyList(), status: ContactStatus = ContactStatus.ACTIVE,
                        sizeInfo: SizeInfo? = null, nextStep: String? = null) = Contact(
        id = id, firstName = first, lastName = "Петров", photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, contactStatus = status, nextStep = nextStep,
        phones = phones, sizeInfo = sizeInfo, createdAt = t0, updatedAt = t0)
    private fun phone(cid: String, id: String, n: String) = ContactPhone(id, cid, n, PhoneType.MOBILE, false)
    private fun calItem(id: String, links: List<CalendarItemLink> = emptyList(), reminders: List<ReminderRule> = emptyList()) = CalendarItem(
        id = id, title = "Событие", type = CalendarItemType.MEETING, startDate = "2027-01-01", isAllDay = true,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, links = links, reminders = reminders, createdAt = t0, updatedAt = t0)
    private fun addContact(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }

    private data class Rows(
        val contacts: List<ContactEntity>, val phones: List<ContactPhoneEntity>, val gifts: List<GiftIdeaEntity>, val notes: List<NoteEntity>,
        val sizes: List<SizeInfoEntity>, val companies: List<CompanyEntity>, val calendar: List<CalendarItemEntity>,
        val links: List<CalendarItemLinkEntity>, val reminders: List<ReminderRuleEntity>, val groups: List<ContactGroupEntity>,
        val groupMembers: List<ContactGroupMemberEntity>, val tags: List<TagEntity>, val tagMembers: List<ContactTagMemberEntity>,
    )
    private fun rows(): Rows = runBlocking {
        Rows(db.contactDao().getAllContacts().sortedBy { it.id }, db.contactDao().getContactPhones().sortedBy { it.id },
            db.giftDao().getAllGifts().sortedBy { it.id }, db.noteDao().getAllNotes().sortedBy { it.id },
            db.contactDao().getSizeInfos().sortedBy { it.id }, db.companyDao().getAllCompanies().sortedBy { it.id },
            db.calendarDao().getAllCalendarItems().sortedBy { it.id }, db.calendarDao().getCalendarItemLinks().sortedBy { it.id },
            db.calendarDao().getReminderRules().sortedBy { it.id }, db.contactDao().getContactGroups().sortedBy { it.id },
            db.contactDao().getContactGroupMembers().sortedBy { it.id }, db.contactDao().getTagsOnce().sortedBy { it.id },
            db.contactDao().getContactTagMembersOnce().sortedBy { it.id })
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 7 / 8 / 9 — режимы и отчёт о конфликтах.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun freshDatabase_restoresBackupOneToOne_modeFresh_reportsAdds() {
        val backup = toJson(BackupData(
            contacts = listOf(contact("c1", phones = listOf(phone("c1", "p1", "+7900000001")), sizeInfo = SizeInfo("s1", "c1", "M"))),
            companies = listOf(Company("co1", "Acme", "/l.png", Industry.IT, "d", "w", createdAt = "2025-05-05T10:00:00", updatedAt = "2025-06-06T11:00:00")),
            notes = listOf(Note("n1", "c1", null, null, null, NoteType.GENERAL, "т", null, false, createdAt = t0, updatedAt = t0)),
            gifts = listOf(GiftIdea("g1", "c1", "Книга", null, null, null, GiftStatus.IDEA))))

        val r = restored(backup)

        assertEquals(RestoreMode.FRESH, r.mode)
        assertEquals(listOf(1, 1, 1, 1), listOf(r.addedContacts, r.addedCompanies, r.addedNotes, r.addedGifts))
        assertEquals(0, r.conflictCount)
        val co = rows().companies.single()
        assertEquals("даты backup сохранены, компания НЕ перештампована", "2025-05-05T10:00:00" to "2025-06-06T11:00:00", co.createdAt to co.updatedAt)
    }

    @Test
    fun existingData_mergeMode_conflictKeepsCurrent_andIsReported() {
        addContact(contact("c1", first = "Текущее имя"))
        val backup = toJson(BackupData(version = 5, contacts = listOf(contact("c1", first = "Имя из backup"))))

        val r = restored(backup)

        assertEquals(RestoreMode.MERGE, r.mode)
        assertEquals("Текущее имя", rows().contacts.single().firstName)
        assertEquals(1, r.conflictCount)
        val c = r.conflicts.single()
        assertEquals(listOf("contact", "c1", "firstName", "Текущее имя", "Имя из backup"), listOf(c.entity, c.id, c.field, c.kept, c.backup))
    }

    @Test
    fun emptyCurrentFieldIsFilledFromBackup_andCountedNotReportedAsConflict() {
        addContact(contact("c1", nextStep = null))
        val backup = toJson(BackupData(version = 5, contacts = listOf(contact("c1", nextStep = "Позвонить"))))

        val r = restored(backup)

        assertEquals("Позвонить", rows().contacts.single().nextStep)
        assertEquals(0, r.conflictCount)
        assertTrue(r.filledFields >= 1)
    }

    /** Значение по умолчанию в backup (старый формат не знал поля) не перекрывает осмысленное текущее. */
    @Test
    fun backupDefaultsAreTreatedAsAbsent_currentMeaningfulValuesKept() {
        addContact(contact("c1", status = ContactStatus.ARCHIVED))
        runBlocking { AppStateStore.addNote(Note("n1", "c1", null, null, null, NoteType.GENERAL, "т", null, true, true, t0, t0)).await() }
        val backup = toJson(BackupData(version = 4,
            contacts = listOf(contact("c1", status = ContactStatus.ACTIVE)),                       // ACTIVE = значение по умолчанию
            notes = listOf(Note("n1", "c1", null, null, null, NoteType.GENERAL, "т", null, false, false, t0, t0))))  // флаги false

        val r = restored(backup)

        assertEquals("ARCHIVED", rows().contacts.single().contactStatus)
        val n = rows().notes.single()
        assertTrue("флаги важности/приватности не сброшены в false", n.isImportant && n.isLocked)
        assertEquals("это не конфликты — backup просто не несёт значения", 0, r.conflictCount)
    }

    /** lastContactDate монотонна по смыслу: берётся более поздняя дата, без конфликта. */
    @Test
    fun lastContactDate_takesLaterDate_evenFromBackup() {
        addContact(contact("c1"))
        runBlocking { db.contactDao().updateLastContactDate("c1", "2026-03-01", "2026-03-01T00:00:00") }
        val newer = contact("c1").copy(lastContactDate = "2026-05-05")
        val older = contact("c1").copy(lastContactDate = "2025-01-01")

        restored(toJson(BackupData(version = 5, contacts = listOf(older))))
        assertEquals("2026-03-01", rows().contacts.single().lastContactDate)
        val r = restored(toJson(BackupData(version = 5, contacts = listOf(newer))))
        assertEquals("2026-05-05", rows().contacts.single().lastContactDate)
        assertEquals(0, r.conflictCount)
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 9 / 11 — дочерние коллекции по устойчивым id; без дублей.
    // ═════════════════════════════════════════════════════════════

    /** size_info — одна запись на контакт: другой id строки backup НЕ создаёт второй строки. */
    @Test
    fun sizeInfo_differentRowId_noDuplicateRow_currentRowIdKept() {
        addContact(contact("c1", sizeInfo = SizeInfo("s-cur", "c1", clothingSize = "M", shoeSize = null)))
        val backup = toJson(BackupData(version = 5, contacts = listOf(contact("c1", sizeInfo = SizeInfo("s-bak", "c1", clothingSize = "L", shoeSize = "42")))))

        val r = restored(backup)

        val row = rows().sizes.single()
        assertEquals("id текущей строки", "s-cur", row.id)
        assertEquals("текущее M не затёрто (конфликт), пустое shoeSize заполнено", "M" to "42", row.clothingSize to row.shoeSize)
        assertEquals(1, r.conflictCount)
        assertEquals("память после restore = Room", "42", AppStateStore.sizeInfos.single().shoeSize)
    }

    @Test
    fun repeatedRestoreOverExistingData_isIdempotent_noNewWritesNoDuplicates() {
        addContact(contact("c1", phones = listOf(phone("c1", "p-cur", "+7900000001"))))
        val backup = toJson(BackupData(version = 5,
            contacts = listOf(contact("c1", phones = listOf(phone("c1", "p-cur", "+7900000001"), phone("c1", "p-bak", "+7900000002")))),
            gifts = listOf(GiftIdea("g1", "c1", "Книга", null, null, null, GiftStatus.IDEA))))

        restored(backup)
        val afterFirst = rows()
        val second = restored(backup)
        val third = restored(backup)

        assertEquals(afterFirst, rows())
        assertEquals(setOf("p-cur", "p-bak"), rows().phones.map { it.id }.toSet())
        assertEquals("второй restore ничего не добавляет", 0, second.addedChildren + second.addedGifts + second.addedContacts)
        assertEquals(second.copy(), third)
    }

    @Test
    fun membershipsAreDeduplicatedByPair_evenWithDifferentRowIds() {
        addContact(contact("c1"))
        val g = AppStateStore.addGroup("Клиенты")!!
        AppStateStore.setContactGroups("c1", setOf(g.id)); settle()
        val t = AppStateStore.addTag("Мой-тег")!!
        AppStateStore.setContactTags("c1", setOf(t.id)); settle()
        val before = rows()
        val backup = toJson(BackupData(version = 6, contacts = listOf(contact("c1")),
            groups = listOf(ContactGroup(g.id, g.name, t0, t0)),
            groupMembers = listOf(ContactGroupMember("gm-other-id", g.id, "c1")),
            tags = listOf(Tag("tag-other-id", "мой-тег", null, t0, t0)),                    // то же логическое имя, другой id
            tagMembers = listOf(ContactTagMember("tm-other-id", "tag-other-id", "c1"))))

        restored(backup)

        assertEquals("членство группы не задвоено", before.groupMembers, rows().groupMembers)
        assertEquals("тег не задвоен (логическое имя)", before.tags, rows().tags)
        assertEquals("членство тега не задвоено", before.tagMembers, rows().tagMembers)
    }

    /** id links/reminders перегенерируются при каждом сохранении события — сравнение по смыслу, без дублей. */
    @Test
    fun calendarLinksAndReminders_withRegeneratedIds_areNotDuplicated() {
        addContact(contact("c1"))
        val current = calItem("cal1",
            links = listOf(CalendarItemLink("l-cur", "cal1", CalendarTargetType.CONTACT, "c1")),
            reminders = listOf(ReminderRule("r-cur", "cal1", ReminderType.BEFORE, 30, ReminderOffsetUnit.MINUTES, null)))
        runBlocking { AppStateStore.addCalendarItem(current).await() }
        val backup = toJson(BackupData(version = 6, contacts = listOf(contact("c1")), calendarItems = listOf(calItem("cal1",
            links = listOf(CalendarItemLink("l-old", "cal1", CalendarTargetType.CONTACT, "c1")),
            reminders = listOf(ReminderRule("r-old", "cal1", ReminderType.BEFORE, 30, ReminderOffsetUnit.MINUTES, null))))))

        val r = restored(backup)

        assertEquals(listOf("l-cur"), rows().links.map { it.id })
        assertEquals(listOf("r-cur"), rows().reminders.map { it.id })
        assertEquals("по смыслу совпадают — это не конфликт", 0, r.conflictCount)
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 12 — атомарность: сбой посередине restore -> ни одной записи.
    // ═════════════════════════════════════════════════════════════

    /** L: контакты/компании/теги уже записаны, сбой ДО подарков/событий/заметок -> Room откатывается целиком. */
    @Test
    fun L_failureMidRestore_rollsBackWholeTransaction_noPartialDb_memoryUntouched() {
        addContact(contact("c-cur", first = "Текущий"))
        runBlocking { AppStateStore.addGift(GiftIdea("g-cur", "c-cur", "Текущий подарок", null, null, null, GiftStatus.IDEA)).await() }
        settle()
        val before = rows(); val memBefore = AppStateStore.contacts.toList()
        val backup = toJson(BackupData(
            contacts = listOf(contact("c-cur", first = "Другое", nextStep = "заполнить"), contact("c-new", phones = listOf(phone("c-new", "p-new", "+7900000009")))),
            companies = listOf(Company("co-new", "Новая", null, Industry.IT, createdAt = t0, updatedAt = t0)),
            gifts = listOf(GiftIdea("g-new", "c-new", "Новый подарок", null, null, null, GiftStatus.IDEA)),
            calendarItems = listOf(calItem("cal-new")),
            notes = listOf(Note("n-new", "c-new", null, null, null, NoteType.GENERAL, "новая", null, false, createdAt = t0, updatedAt = t0))))
        AppStateStore.restoreMidTransactionHookForTests = { throw java.io.IOException("disk full mid-restore") }

        val failure = runCatching { awaitOffMainThread { ExportManager.importJsonBackup(backup) } }.exceptionOrNull()

        assertNotNull("сбой виден вызывающему (restore НЕ объявлен успешным)", failure)
        AppStateStore.restoreMidTransactionHookForTests = null
        assertEquals("Room не изменился ни в одной таблице (контакты, компании, подарки, события, заметки)", before, rows())
        assertEquals("память не тронута", memBefore, AppStateStore.contacts.toList())
        // после сбоя тот же restore проходит целиком
        val ok = restored(backup)
        assertEquals(RestoreMode.MERGE, ok.mode)
        assertEquals(setOf("c-cur", "c-new"), rows().contacts.map { it.id }.toSet())
        assertEquals(setOf("g-cur", "g-new"), rows().gifts.map { it.id }.toSet())
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 13 — память после restore == Room (тем же построителем).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun memoryAfterRestore_equalsPersistedState_includingNestedProjections() {
        addContact(contact("c1", first = "Текущий"))
        val backup = toJson(BackupData(version = 6,
            contacts = listOf(contact("c1", sizeInfo = SizeInfo("s1", "c1", "M")), contact("c2", phones = listOf(phone("c2", "p2", "+7900000002")))),
            gifts = listOf(GiftIdea("g1", "c2", "Подарок", null, null, null, GiftStatus.IDEA)),
            notes = listOf(Note("n1", "c2", null, null, null, NoteType.GENERAL, "т", null, false, createdAt = t0, updatedAt = t0))))

        restored(backup)

        val st = runBlocking { AppStateStore.readPersistedState() }
        assertEquals(st.contacts, AppStateStore.contacts.toList())
        assertEquals(st.gifts, AppStateStore.gifts.toList())
        assertEquals(st.notes, AppStateStore.notes.toList())
        assertEquals(st.sizeInfos, AppStateStore.sizeInfos.toList())
        assertEquals(st.addresses, AppStateStore.addresses.toList())
        assertEquals(st.companies, AppStateStore.companies.toList())
        assertEquals(st.calendarItems, AppStateStore.calendarItems.toList())
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 15 — версии формата: старые читаются, новые отвергаются ДО записи.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun N_versions_newerFormatOrSchemaRejectedBeforeAnyWrite_olderParsed() {
        addContact(contact("c-cur", first = "Текущий"))
        settle()
        val before = rows()
        val newContact = listOf(contact("c-new"))

        assertTrue(outcome(toJson(BackupData(version = BACKUP_FORMAT_VERSION + 1, contacts = newContact))) is BackupImportOutcome.TooNew)
        assertTrue("схема новее открытой БД", outcome(toJson(BackupData(version = BACKUP_FORMAT_VERSION, roomSchemaVersion = 999, contacts = newContact))) is BackupImportOutcome.TooNew)
        assertTrue(outcome(toJson(BackupData(version = 0, contacts = newContact))) is BackupImportOutcome.NotBackup)
        assertTrue(outcome("{не json") is BackupImportOutcome.NotBackup)
        assertEquals("ноль записей после всех отказов", before, rows())

        // текущая версия и схема, а также самые старые версии, — принимаются
        assertTrue(outcome(toJson(BackupData(version = BACKUP_FORMAT_VERSION, roomSchemaVersion = db.openHelper.readableDatabase.version, contacts = newContact))) is BackupImportOutcome.Restored)
        assertTrue(outcome("""{"version":1,"contacts":[{"id":"c-v1","firstName":"Старый","lastName":"Формат","photoUri":null,
            "relationshipType":"FRIEND","connectionLevel":"NORMAL","importanceLevel":"NORMAL","socialRole":"REGULAR",
            "communicationRhythm":"NOT_TRACKED","createdAt":"$t0","updatedAt":"$t0"}]}""") is BackupImportOutcome.Restored)
        assertTrue(rows().contacts.any { it.id == "c-v1" })
    }

    @Test
    fun newBackup_isStampedWithCurrentFormatAndSchema() {
        addContact(contact("c1"))
        val b = ExportManager.parseJsonBackup(runBlocking { ExportManager.backupJsonString() })!!
        assertEquals(BACKUP_FORMAT_VERSION, b.version)
        assertEquals(db.openHelper.readableDatabase.version, b.roomSchemaVersion)
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 0 — каждая user-data таблица Room обязана быть в карте покрытия.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun everyRoomTable_isDeclaredInBackupCoverage() {
        val tables = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        val userTables = tables - setOf("android_metadata", "sqlite_sequence", "room_master_table")
        assertEquals("новая таблица Room без записи в BackupCoverage не должна оставаться вне backup",
            BackupCoverage.ROOM_TABLES.keys, userTables)
    }
}
