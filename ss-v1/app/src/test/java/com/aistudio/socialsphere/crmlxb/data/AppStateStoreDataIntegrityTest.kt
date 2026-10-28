package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BATCH 2/2B (data integrity) — regression tests for the confirmed defect
 * classes fixed in AppStateStore.kt/DbWriteQueue.kt:
 *  - Root Cause A (partial write): updateContact/deleteContact/updateCompany/
 *    deleteCompany/updateCalendarItem/etc. used to delete-then-reinsert child
 *    records across several separate DAO calls with no enclosing transaction.
 *  - Root Cause B (out-of-order persistence): all writes shared one
 *    fire-and-forget CoroutineScope with no ordering guarantee (Batch 2), then
 *    per-aggregate-type `limitedParallelism(1)` (still not a structural
 *    guarantee — see DbWriteQueue.kt/DbWriteQueueTest for why, and Batch 2B),
 *    so rapid successive updates of the SAME aggregate could commit to Room in
 *    a different order than they were called.
 *  - Confirmed Problem B (Batch 2B, cross-aggregate atomicity): deleteContact/
 *    deleteCompany used to clean up Calendar links through a SEPARATE enqueued
 *    write (the public updateCalendarItem()), not the same transaction as the
 *    delete itself.
 *
 * Same pattern as AppStateStoreRestoreTest: real in-memory Room DB, real
 * AppStateStore singleton, real production functions (updateContact() etc.)
 * — not a hand-copied mirror of the transaction/queue logic. Most write
 * functions now return a Deferred (Batch 2B, prep for Batch 3) — tests await
 * that directly where available; the few that don't expose one (merge/convert,
 * which keep their existing snapshot/id return values) still poll the DB with
 * a timeout, same technique as AppStateStoreRestoreTest.pollUntilNotEmpty.
 */
@RunWith(RobolectricTestRunner::class)
class AppStateStoreDataIntegrityTest {

    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // См. комментарий над resetForTests() в AppStateStoreRestoreTest —
        // тот же паттерн, та же причина (singleton object на весь JVM-процесс).
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        // BATCH 2D: initialize() запускает фоновый loadInitialData() (reloadFromDb
        // + seedDefaultTagsIfNeeded + migrateLegacyContactTagsIfNeeded +
        // repairMissingContactLinksFromTitles) в AppStateStore.scope — ОТДЕЛЬНОЙ
        // от тестового потока корутине. Раньше это было не важно (все три
        // функции были fire-and-forget, окно гонки — микроскопическое), но
        // теперь, когда D-001/D-002/D-003 сделали их await()'ящими свой
        // enqueued commit, loadInitialData() стал заметно "длиннее" — тест
        // может начать писать СВОИ данные ДО того, как этот фоновый прогон
        // реально закончится, и его reloadFromDb() (clear()+addAll() поверх
        // ГЛОБАЛЬНЫХ списков) может затереть то, что тест только что записал.
        // idle() прокручивает Robolectric-looper (loadInitialData() сам
        // финиширует через withContext(Dispatchers.Main) { isLoading = false }),
        // пока фоновый прогон ЭТОГО теста не завершится по-настоящему.
        val settled = pollUntilTrue(timeoutMs = 5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут в setup()")
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear()
        AppStateStore.companies.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.addresses.clear()
        AppStateStore.companyRelations.clear()
        // BATCH 2C: новые тесты этого файла (Note/Gift/Group/Tag/Relation-vs-
        // Delete, UndoMerge) впервые в этом файле населяют эти списки —
        // AppStateStore — синглтон на весь JVM-процесс (см. комментарий над
        // resetForTests()), без явной очистки здесь их содержимое утекло бы в
        // следующий тест этого же класса/файла в том же forked JVM.
        AppStateStore.notes.clear()
        AppStateStore.gifts.clear()
        AppStateStore.contactRelations.clear()
        AppStateStore.groups.clear()
        AppStateStore.groupMembers.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
        db.close()
    }

    /** Опрашивает БД, пока `check` не вернёт true или не истечёт таймаут —
     *  запись идёт в фоновой корутине AppStateStore-scope, не в тестовом
     *  диспетчере (тот же приём, что и в AppStateStoreRestoreTest). */
    private fun pollUntilTrue(timeoutMs: Long = 5000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
            ok = check()
        }
        ok
    }

    private fun contactWith(
        id: String,
        phoneNumber: String,
        emailAddr: String,
        addressLine: String
    ) = Contact(
        id = id, firstName = "Тест", lastName = "Контакт", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = listOf(ContactPhone(AppStateStore.generateId(), id, phoneNumber, PhoneType.MOBILE, true)),
        emails = listOf(ContactEmail(AppStateStore.generateId(), id, emailAddr, EmailType.PERSONAL, true)),
        addresses = listOf(Address(AppStateStore.generateId(), AddressOwnerType.CONTACT, id, AddressType.HOME, addressLine, "City", "Country")),
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    // ── TEST GROUP A: atomic contact update — old children gone, new children present ──
    @Test
    fun updateContact_atomicallyReplacesChildRecords() = runBlocking {
        val id = "ca1"
        AppStateStore.addContact(contactWith(id, "+10000000001", "a@old.test", "Old St 1")).await()

        AppStateStore.updateContact(AppStateStore.getContact(id)!!, contactWith(id, "+29999999999", "b@new.test", "New Ave 2")).await()

        val phones = db.contactDao().getContactPhones().filter { it.contactId == id }
        val emails = db.contactDao().getContactEmails().filter { it.contactId == id }
        val addresses = db.addressDao().getAllAddresses().filter { it.ownerId == id }

        assertEquals("old phone must be gone, only the new one remains", 1, phones.size)
        assertEquals("+29999999999", phones.single().number)
        assertEquals("old email must be gone, only the new one remains", 1, emails.size)
        assertEquals("b@new.test", emails.single().email)
        assertEquals("old address must be gone, only the new one remains", 1, addresses.size)
        assertEquals("New Ave 2", addresses.single().addressLine)
    }

    // ── TEST GROUP C: ordering — rapid successive updates must converge on the LAST call, not an earlier one ──
    // BATCH 2B (§23/§38): updateContact() now returns a Deferred — await the
    // LAST call deterministically instead of polling-with-timeout. Not
    // awaiting between V1/V2/V3 themselves is the whole point of the test
    // (rapid-fire, no delay) — only the FINAL result needs a completion signal.
    @Test
    fun updateContact_rapidSuccessiveUpdates_converageOnLastCall() = runBlocking {
        val id = "ca2"
        AppStateStore.addContact(contactWith(id, "+1", "v1@test.test", "St 1")).await()

        // Три обновления подряд, без задержек между ними — ровно сценарий из
        // Batch 2B §23 TEST GROUP C.
        AppStateStore.updateContact(AppStateStore.getContact(id)!!, contactWith(id, "+2", "v2@test.test", "St 2"))
        AppStateStore.updateContact(AppStateStore.getContact(id)!!, contactWith(id, "+3", "v3@test.test", "St 3")).await()

        // Не просто "V3 присутствует" — ОТСУТСТВИЕ V1/V2 доказывает, что каждое
        // обновление полностью (delete+insert одной транзакцией) применилось
        // ДО следующего, а не осталось перемешанным с более старой версией.
        val phones = db.contactDao().getContactPhones().filter { it.contactId == id }
        assertEquals("exactly one phone must remain — no leftover from V1/V2", 1, phones.size)
        assertEquals("+3", phones.single().number)
        val emails = db.contactDao().getContactEmails().filter { it.contactId == id }
        assertEquals("v3@test.test", emails.single().email)
    }

    // ── TEST GROUP D: unrelated contacts must not interfere with each other ──
    @Test
    fun updateContact_concurrentUpdatesOfDifferentContacts_bothPersist() = runBlocking {
        val idA = "ca3a"; val idB = "ca3b"
        AppStateStore.addContact(contactWith(idA, "+100", "a@test.test", "A St")).await()
        AppStateStore.addContact(contactWith(idB, "+200", "b@test.test", "B St")).await()

        val ua = AppStateStore.updateContact(AppStateStore.getContact(idA)!!, contactWith(idA, "+101", "a2@test.test", "A St 2"))
        val ub = AppStateStore.updateContact(AppStateStore.getContact(idB)!!, contactWith(idB, "+201", "b2@test.test", "B St 2"))
        ua.await(); ub.await()

        val phonesA = db.contactDao().getContactPhones().filter { it.contactId == idA }
        val phonesB = db.contactDao().getContactPhones().filter { it.contactId == idB }
        assertEquals(1, phonesA.size); assertEquals("+101", phonesA.single().number)
        assertEquals(1, phonesB.size); assertEquals("+201", phonesB.single().number)
    }

    // ── TEST GROUP E: Company aggregate round-trip (mirrors Group A) ──
    @Test
    fun updateCompany_atomicallyReplacesChildRecords() = runBlocking {
        val id = "co1"
        val v1 = Company(
            id = id, name = "Old Co", industry = Industry.OTHER,
            phones = listOf(ContactPhone(AppStateStore.generateId(), id, "+1", PhoneType.WORK, true)),
            emails = listOf(ContactEmail(AppStateStore.generateId(), id, "old@co.test", EmailType.WORK, true)),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        AppStateStore.addCompany(v1).await()

        val v2 = v1.copy(
            name = "New Co",
            phones = listOf(ContactPhone(AppStateStore.generateId(), id, "+2", PhoneType.WORK, true)),
            emails = listOf(ContactEmail(AppStateStore.generateId(), id, "new@co.test", EmailType.WORK, true))
        )
        AppStateStore.updateCompany(v1, v2).await()

        val phones = db.contactDao().getContactPhones().filter { it.companyId == id }
        val emails = db.contactDao().getContactEmails().filter { it.companyId == id }
        assertEquals("old company phone must be gone", 1, phones.size)
        assertEquals("+2", phones.single().number)
        assertEquals("old company email must be gone", 1, emails.size)
        assertEquals("new@co.test", emails.single().email)
        assertEquals("New Co", db.companyDao().getAllCompanies().single { it.id == id }.name)
    }

    // ── TEST GROUP F: Calendar aggregate — links/reminders stay consistent after update ──
    @Test
    fun updateCalendarItem_atomicallyReplacesLinks() = runBlocking {
        val itemId = "cal1"; val contactAId = "calA"; val contactBId = "calB"
        // CALENDAR DATA SAFETY PACK: добавляемая связь обязана указывать на существующую сущность (нет сирот) — контакты существуют.
        AppStateStore.contacts.add(contactWith(contactAId, "+1", "a@test.test", "St A"))
        AppStateStore.contacts.add(contactWith(contactBId, "+2", "b@test.test", "St B"))
        val v1 = CalendarItem(
            id = itemId, title = "Событие", type = CalendarItemType.MEETING,
            startDate = "2026-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, contactAId)),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        AppStateStore.addCalendarItem(v1).await()

        val v2 = v1.copy(
            links = listOf(CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, contactBId))
        )
        AppStateStore.updateCalendarItem(v1, v2).await()

        val links = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == itemId }
        assertEquals("old link (contact A) must be gone, only the new one remains", 1, links.size)
        assertEquals(contactBId, links.single().targetId)
    }

    // ── TEST GROUP F2 (v20 persistence-foundation, 2026-09-17): unrelated
    // update preserves postpone pair — если у CalendarItem уже есть постпон-
    // пара, а через AppStateStore.updateCalendarItem меняется только title,
    // обе postpone-поля обязаны пережить запись без изменений. ──
    @Test
    fun updateCalendarItem_unrelatedFieldChange_preservesPostponePair() = runBlocking {
        val itemId = "cal-postpone"
        val v1 = CalendarItem(
            id = itemId, title = "Original title", type = CalendarItemType.MEETING,
            startDate = "2026-09-02", isAllDay = false, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL, recurrenceRule = "FREQ=WEEKLY",
            postponedFromOccurrenceDate = "2026-09-16", postponedToDate = "2026-09-18",
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        AppStateStore.addCalendarItem(v1).await()

        val v2 = v1.copy(title = "Renamed title") // только title меняется
        AppStateStore.updateCalendarItem(v1, v2).await()

        val saved = db.calendarDao().getAllCalendarItems().single { it.id == itemId }
        assertEquals("Renamed title", saved.title)
        assertEquals("postpone-пара не тронута unrelated update", "2026-09-16", saved.postponedFromOccurrenceDate)
        assertEquals("2026-09-18", saved.postponedToDate)
    }

    // ── TEST GROUP (Batch 2B §24): deleteContact + Calendar cleanup is ONE atomic operation ──
    @Test
    fun deleteContact_calendarLinkCleanup_isAtomicWithDelete() = runBlocking {
        val contactId = "del1"; val itemId = "delcal1"
        AppStateStore.addContact(contactWith(contactId, "+1", "d@test.test", "St 1")).await()
        val item = CalendarItem(
            id = itemId, title = "Meeting", type = CalendarItemType.MEETING,
            startDate = "2026-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, contactId)),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        AppStateStore.addCalendarItem(item).await()

        AppStateStore.deleteContact(contactId).await()

        assertTrue("contact must be gone from Room", db.contactDao().getAllContacts().none { it.id == contactId })
        val links = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == itemId }
        assertTrue("calendar link to the deleted contact must be gone — not left as an orphan reference",
            links.none { it.targetId == contactId })
        assertTrue("contact's own child records must be gone",
            db.contactDao().getContactPhones().none { it.contactId == contactId })
    }

    // ── TEST GROUP (Batch 2B §25): deleteCompany + Calendar cleanup is ONE atomic operation ──
    @Test
    fun deleteCompany_calendarLinkCleanup_isAtomicWithDelete() = runBlocking {
        val companyId = "delco1"; val itemId = "delcal2"
        val company = Company(
            id = companyId, name = "DoomedCo", industry = Industry.OTHER,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        AppStateStore.addCompany(company).await()
        val item = CalendarItem(
            id = itemId, title = "Company event", type = CalendarItemType.COMPANY_EVENT,
            startDate = "2026-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.COMPANY, companyId)),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        AppStateStore.addCalendarItem(item).await()

        AppStateStore.deleteCompany(companyId).await()

        assertTrue("company must be gone from Room", db.companyDao().getAllCompanies().none { it.id == companyId })
        val links = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == itemId }
        assertTrue("calendar link to the deleted company must be gone — not left as an orphan reference",
            links.none { it.targetId == companyId })
    }

    // ── TEST GROUP (Batch 2B §26): cross-aggregate ordering — a plain Calendar
    // write and a Contact-merge that ALSO touches Calendar must go through the
    // SAME writer, not independent per-type dispatchers (the exact regression
    // Batch 2's three separate dispatchers would have allowed: updateCalendarItem
    // was on calendarWriteDispatcher, mergeContacts was on contactWriteDispatcher
    // — two unrelated queues). mergeContacts() doesn't expose a Deferred (keeps
    // its existing MergeUndoSnapshot? return value), so completion is polled —
    // acceptable per §26 for a call site with no awaitable handle available. ──
    @Test
    fun crossAggregate_calendarUpdateThenContactMerge_bothApplyCorrectly() {
        val idKeep = "mrgA"; val idOther = "mrgB"; val itemId = "mrgCal"
        AppStateStore.contacts.add(contactWith(idKeep, "+1", "keep@test.test", "St A"))
        AppStateStore.contacts.add(contactWith(idOther, "+2", "other@test.test", "St B"))
        val item = CalendarItem(
            id = itemId, title = "Original", type = CalendarItemType.MEETING,
            startDate = "2026-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, idOther)),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        runBlocking { AppStateStore.addCalendarItem(item).await() }

        // Plain calendar-only write, immediately followed by a contact merge
        // that retargets this SAME calendar item's link — no delay between them.
        AppStateStore.updateCalendarItem(item, item.copy(title = "Renamed"))
        AppStateStore.mergeContacts(listOf(idKeep, idOther))

        val settled = pollUntilTrue(timeoutMs = 8000) {
            db.calendarDao().getAllCalendarItems().any { it.id == itemId && it.title == "Renamed" } &&
                db.calendarDao().getCalendarItemLinks().any { it.calendarItemId == itemId && it.targetId == idKeep }
        }
        if (!settled) fail("calendar update and cross-aggregate merge did not both apply — not on the same writer")

        runBlocking {
            val links = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == itemId }
            assertEquals("merge must have retargeted the link to the kept contact, old link gone", 1, links.size)
            assertEquals(idKeep, links.single().targetId)
        }
    }

    // ── TEST GROUP G (Batch 2C §32): Note-vs-Delete ordering ──
    // addNote() и deleteContact() — ДВЕ отдельные enqueued команды на одном
    // глобальном writer'е. Без единого writer'а/FIFO-гарантии транзакция
    // deleteContact (которая внутри себя чистит notes через
    // deleteNotesForContact) могла закоммититься ДО того, как более ранний по
    // вызову addNote()'s insert реально применился — тогда insert выполнился
    // бы ПОСЛЕ удаления и "воскресил" бы Note, ссылающуюся на уже удалённый
    // контакт. Специально НЕ await() между addNote() и deleteContact() —
    // именно rapid-fire без промежуточной синхронизации и есть сценарий,
    // который проверяем.
    @Test
    fun addNoteThenDeleteContact_leavesNoOrphanNote() = runBlocking {
        val contactId = "note1"
        AppStateStore.addContact(contactWith(contactId, "+3001", "note1@test.test", "St 1")).await()

        AppStateStore.addNote(
            Note(
                id = AppStateStore.generateId(), contactId = contactId, type = NoteType.GENERAL,
                text = "test note", isImportant = false,
                createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
            )
        )
        AppStateStore.deleteContact(contactId).await()

        assertTrue("contact must be gone from Room", db.contactDao().getAllContacts().none { it.id == contactId })
        assertTrue("no orphan Note referencing the deleted contact must remain",
            db.noteDao().getAllNotes().none { it.contactId == contactId })
    }

    // ── TEST GROUP H (Batch 2C §8/§33): Address-vs-Delete ordering ──
    // Точный сценарий из комментария над setAddressCoords() в AppStateStore.kt:
    // поздний геокодер-insert НЕ должен "воскресить" адрес, чей владелец-контакт
    // уже удалён — единый writer гарантирует, что setAddressCoords'ная команда
    // (enqueued ПЕРВОЙ) коммитится до deleteContact'ной (enqueued ВТОРОЙ), а не
    // наоборот.
    @Test
    fun setAddressCoordsThenDeleteContact_doesNotResurrectAddress() = runBlocking {
        val contactId = "addr1"
        val contact = contactWith(contactId, "+3002", "addr1@test.test", "St 1")
        val addressId = contact.addresses.single().id
        AppStateStore.addContact(contact).await()

        AppStateStore.setAddressCoords(addressId, 1.23, 4.56)
        AppStateStore.deleteContact(contactId).await()

        assertTrue("address must be gone, not resurrected by the late geocoder write",
            db.addressDao().getAllAddresses().none { it.id == addressId })
    }

    // ── TEST GROUP I (Batch 2C §14/§35): Tag-membership-vs-Delete ordering ──
    @Test
    fun setContactTagsThenDeleteContact_leavesNoOrphanMembership() = runBlocking {
        val contactId = "tag1"
        AppStateStore.addContact(contactWith(contactId, "+3003", "tag1@test.test", "St 1")).await()
        val tag = AppStateStore.addTag("Test Tag $contactId")!!

        // setContactTags() не отдаёт Deferred (fire-and-forget UI call site) —
        // проверяем именно то, что единый writer сохраняет FIFO-порядок даже
        // без явного await на промежуточном шаге: команда setContactTags
        // enqueued раньше (синхронно, в этом же вызове), чем deleteContact.
        AppStateStore.setContactTags(contactId, setOf(tag.id))
        AppStateStore.deleteContact(contactId).await()

        assertTrue("no orphan ContactTagMember referencing the deleted contact must remain",
            db.contactDao().getContactTagMembers().first().none { it.contactId == contactId })
    }

    // ── TEST GROUP J (Batch 2C §11/§34): Group-membership-vs-Delete ordering (зеркало TEST GROUP I) ──
    @Test
    fun setContactGroupsThenDeleteContact_leavesNoOrphanMembership() = runBlocking {
        val contactId = "grp1"
        AppStateStore.addContact(contactWith(contactId, "+3004", "grp1@test.test", "St 1")).await()
        val group = AppStateStore.addGroup("Test Group $contactId")!!

        AppStateStore.setContactGroups(contactId, setOf(group.id))
        AppStateStore.deleteContact(contactId).await()

        assertTrue("no orphan ContactGroupMember referencing the deleted contact must remain",
            db.contactDao().getContactGroupMembers().none { it.contactId == contactId })
    }

    // Ждёт onDone-колбэк wipeAllData(), прокручивая Robolectric main looper —
    // тот же приём, что и в repair-link тесте выше: wipeAllData()'s writer-
    // команда теперь САМА делает withContext(Dispatchers.Main) для memory-
    // clear (Batch 2E, 2E-004), плюс отдельный withContext(Main) для самого
    // onDone — под Robolectric (LooperMode.PAUSED) это не резолвится само по
    // себе без idle().
    private fun awaitWipeOnDone(timeoutMs: Long = 8000): CompletableDeferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        AppStateStore.wipeAllData { ok -> done.complete(ok) }
        val settled = pollUntilTrue(timeoutMs = timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            done.isCompleted
        }
        if (!settled) fail("wipeAllData() onDone не сработал за таймаут")
        return done
    }

    // ── TEST GROUP K (Batch 2C §17/§36, усилен Batch 2E): Wipe ordering —
    // write enqueued BEFORE wipeAllData() must be gone once the wipe has
    // actually committed AND onDone reports success. ──
    @Test
    fun wipeAllData_clearsWriteThatWasEnqueuedBeforeIt() {
        val contactId = "wipe1"
        runBlocking { AppStateStore.addContact(contactWith(contactId, "+3005", "wipe1@test.test", "St 1")).await() }

        val done = awaitWipeOnDone()
        assertTrue("wipe must report success", done.getCompleted())

        val cleared = runBlocking { db.contactDao().getAllContacts().isEmpty() }
        assertTrue("Room must be empty after an enqueued wipe actually commits", cleared)
    }

    // ── TEST GROUP L (Batch 2C §17/§37, усилен Batch 2E): Writer-survives-wipe
    // — a write submitted AFTER an awaited wipe must still succeed
    // (db.clearAllTables() + the memory-clear that now lives in the SAME
    // writer command, Batch 2E 2E-004, must not kill the writer's single
    // consumer coroutine or its `for` loop). ──
    @Test
    fun wipeAllData_writerSurvivesAndAcceptsNewWriteAfterward() {
        val beforeId = "wipe2before"
        runBlocking { AppStateStore.addContact(contactWith(beforeId, "+3006", "wipe2b@test.test", "St 1")).await() }
        val done = awaitWipeOnDone()
        assertTrue("wipe must settle successfully before testing writer survival", done.getCompleted())

        val afterId = "wipe2after"
        runBlocking {
            AppStateStore.addContact(contactWith(afterId, "+3007", "wipe2a@test.test", "St 2")).await()
            assertTrue("a write submitted after an awaited wipe must still succeed — writer must stay alive",
                db.contactDao().getAllContacts().any { it.id == afterId })
        }
    }

    // ── TEST GROUP K2 (Batch 2E, 2E-004 core proof): onDone(true) must fire
    // ONLY after BOTH Room AND every relevant in-memory domain collection are
    // actually empty — this is the direct, unconfounded proof of the DB+memory
    // barrier (single isolated wipe, nothing else interleaved, so this is not
    // affected by the separate — pre-existing, out-of-scope — timing quirk
    // where a DIFFERENT write function's own immediate synchronous in-memory
    // mutation can race a concurrently-running wipe). Structural "next writer
    // command cannot start mid-barrier" guarantee is already covered by
    // DbWriteQueueTest.commandDoesNotStartUntilPreviousFullyCompletes — this
    // test proves wipeAllData()'s OWN command actually composes DB-clear and
    // memory-clear into that SAME structural guarantee, not a separate
    // decoupled coroutine (the old Batch 2C/2D design). ──
    @Test
    fun wipeAllData_onDoneFiresOnlyAfterBothRoomAndMemoryAreCleared() {
        val contactId = "wipe4"
        runBlocking {
            AppStateStore.addContact(contactWith(contactId, "+3010", "wipe4@test.test", "St 1")).await()
            AppStateStore.addGroup("Wipe Barrier Group")
        }
        assertTrue("precondition: contact must exist before wipe", AppStateStore.contacts.isNotEmpty())

        val done = awaitWipeOnDone()
        assertTrue("wipe must report success", done.getCompleted())

        // Проверяем СРАЗУ по завершении onDone, без дополнительного ожидания —
        // если бы memory-clear всё ещё жил в ОТДЕЛЬНОЙ, не входящей в ту же
        // writer-команду корутине (старый дизайн), порядок относительно
        // onDone был бы НЕ гарантирован этим же structural-guarantee.
        assertTrue("in-memory contacts must be empty by the time onDone fires", AppStateStore.contacts.isEmpty())
        assertTrue("in-memory groups must be empty by the time onDone fires", AppStateStore.groups.isEmpty())
        val dbEmpty = runBlocking {
            db.contactDao().getAllContacts().isEmpty() && db.contactDao().getContactGroups().isEmpty()
        }
        assertTrue("Room must also be empty by the time onDone fires", dbEmpty)
    }

    // ── TEST GROUP M (Batch 2C §20-21/§38): UndoMerge regression — one
    // awaited undoMerge() call must restore contacts, notes, gifts, relations,
    // calendar links AND group/tag membership together, as ONE atomic
    // operation (not the pre-Batch-2C mix of separate enqueue calls + raw
    // scope.launch, where a partial-undo on failure/process death was possible). ──
    @Test
    fun undoMerge_restoresEverythingFromBeforeTheMerge() {
        val idA = "umA"; val idB = "umB"; val itemId = "umCal"
        runBlocking {
            AppStateStore.addContact(contactWith(idA, "+4001", "a@um.test", "St A")).await()
            AppStateStore.addContact(contactWith(idB, "+4002", "b@um.test", "St B")).await()
        }

        val noteA = Note(
            id = AppStateStore.generateId(), contactId = idA, type = NoteType.GENERAL,
            text = "note on A", isImportant = false,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        val giftB = GiftIdea(
            id = AppStateStore.generateId(), contactId = idB, title = "gift for B", status = GiftStatus.IDEA
        )
        AppStateStore.addNote(noteA)
        AppStateStore.addGift(giftB)

        val relation = ContactRelation(
            id = AppStateStore.generateId(), firstContactId = idA, secondContactId = idB,
            firstRole = "colleague", secondRole = "colleague"
        )
        AppStateStore.addContactRelation(relation)

        val group = AppStateStore.addGroup("UM Group")!!
        AppStateStore.setContactGroups(idB, setOf(group.id))
        val tag = AppStateStore.addTag("UM Tag")!!
        AppStateStore.setContactTags(idB, setOf(tag.id))

        val item = CalendarItem(
            id = itemId, title = "Shared event", type = CalendarItemType.MEETING,
            startDate = "2026-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, idB)),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        runBlocking { AppStateStore.addCalendarItem(item).await() }

        // "before"-состояние должно реально закоммититься в Room ДО merge —
        // сам merge не ждёт эти fire-and-forget записи явно (тот же порядок,
        // что и в реальном UI: несколько независимых экранов дописывают
        // контакту заметки/теги/группы до того, как пользователь запускает слияние).
        val settledBeforeMerge = pollUntilTrue(timeoutMs = 8000) {
            db.noteDao().getAllNotes().any { it.id == noteA.id } &&
                db.giftDao().getAllGifts().any { it.id == giftB.id } &&
                db.contactDao().getContactGroupMembers().any { it.contactId == idB } &&
                db.contactDao().getContactTagMembers().first().any { it.contactId == idB }
        }
        assertTrue("setup writes must settle before merge", settledBeforeMerge)

        val snapshot = AppStateStore.mergeContacts(listOf(idA, idB))
        assertTrue("merge must succeed for two valid contacts", snapshot != null)
        val mergeSettled = pollUntilTrue(timeoutMs = 8000) {
            db.contactDao().getAllContacts().count { it.id == idA || it.id == idB } == 1
        }
        assertTrue("merge must settle before undo", mergeSettled)

        runBlocking { AppStateStore.undoMerge(snapshot!!).await() }

        val contactsAfterUndo = runBlocking { db.contactDao().getAllContacts().filter { it.id == idA || it.id == idB } }
        assertEquals("both original contacts must exist again after undo", 2, contactsAfterUndo.size)

        runBlocking {
            assertTrue("note must be back on its ORIGINAL owner (A), not left on keep/other",
                db.noteDao().getAllNotes().any { it.id == noteA.id && it.contactId == idA })
            assertTrue("gift must be back on its ORIGINAL owner (B)",
                db.giftDao().getAllGifts().any { it.id == giftB.id && it.contactId == idB })

            val relationsAfterUndo = db.contactDao().getContactRelations().filter { it.id == relation.id }
            assertEquals(1, relationsAfterUndo.size)
            assertEquals(idA, relationsAfterUndo.single().firstContactId)
            assertEquals(idB, relationsAfterUndo.single().secondContactId)

            val groupMembersAfterUndo = db.contactDao().getContactGroupMembers().filter { it.groupId == group.id }
            assertEquals("group membership must be back on B only, not lingering on keep after undo",
                listOf(idB), groupMembersAfterUndo.map { it.contactId })
            val tagMembersAfterUndo = db.contactDao().getContactTagMembers().first().filter { it.tagId == tag.id }
            assertEquals("tag membership must be back on B only",
                listOf(idB), tagMembersAfterUndo.map { it.contactId })

            val linksAfterUndo = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == itemId }
            assertEquals("calendar link must be retargeted back to B", listOf(idB), linksAfterUndo.map { it.targetId })
        }
    }

    // ── TEST GROUP R (Batch 2F, 2F-001/§10): addNote для заметки-общения —
    // Note И lastContactDate контакта должны оказаться в Room вместе (одна
    // writer-команда, одна транзакция). Раньше это были ДВЕ отдельные команды:
    // сбой второй оставлял бы lastContactDate=сегодня без самой заметки. ──
    @Test
    fun addNote_interactionNote_persistsNoteAndLastContactDateTogether() {
        val contactId = "note2f1"
        runBlocking {
            AppStateStore.addContact(contactWith(contactId, "+7001", "n2f1@test.test", "St 1")).await()
        }
        val before = runBlocking { db.contactDao().getAllContacts().single { it.id == contactId }.lastContactDate }

        val noteId = AppStateStore.generateId()
        AppStateStore.addNote(
            Note(
                id = noteId, contactId = contactId, type = NoteType.GENERAL,
                text = "созвонились", isImportant = false,
                createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
            )
        )

        val settled = pollUntilTrue(timeoutMs = 8000) {
            db.noteDao().getAllNotes().any { it.id == noteId } &&
                db.contactDao().getAllContacts().single { it.id == contactId }.lastContactDate != before
        }
        if (!settled) fail("Note и lastContactDate не долетели до Room за таймаут")

        runBlocking {
            val note = db.noteDao().getAllNotes().single { it.id == noteId }
            assertEquals(contactId, note.contactId)
            val row = db.contactDao().getAllContacts().single { it.id == contactId }
            assertTrue("lastContactDate должен быть заполнен после заметки-общения",
                !row.lastContactDate.isNullOrBlank())
            // Тот же логический timestamp (§24): дата контакта — префикс
            // updatedAt самой заметки (оба из одного nowIso()).
            assertEquals("дата последнего контакта должна совпадать с датой заметки",
                note.updatedAt.take(10), row.lastContactDate)
        }
    }

    // ── TEST GROUP R2 (Batch 2F §6/§7/§39): semantics НЕ изменены — заметка,
    // которая по действующим правилам НЕ считается общением (подарок/личная
    // деталь/дата-событие), по-прежнему НЕ трогает lastContactDate. ──
    @Test
    fun addNote_nonInteractionNote_doesNotTouchLastContactDate() {
        val contactId = "note2f2"
        runBlocking {
            AppStateStore.addContact(contactWith(contactId, "+7002", "n2f2@test.test", "St 1")).await()
        }
        val before = runBlocking { db.contactDao().getAllContacts().single { it.id == contactId }.lastContactDate }

        val noteId = AppStateStore.generateId()
        AppStateStore.addNote(
            Note(
                id = noteId, contactId = contactId, type = NoteType.GIFT,
                text = "идея подарка", isImportant = false,
                createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
            )
        )

        val settled = pollUntilTrue(timeoutMs = 8000) { db.noteDao().getAllNotes().any { it.id == noteId } }
        if (!settled) fail("Note не долетела до Room за таймаут")

        runBlocking {
            val row = db.contactDao().getAllContacts().single { it.id == contactId }
            assertEquals("lastContactDate не должен меняться для не-interaction заметки",
                before, row.lastContactDate)
        }
    }

    // ── TEST GROUP S (Batch 2F, 2F-002/§20): markCalendarItemCompleted для
    // события-общения с ДВУМЯ связанными контактами — статус события и
    // lastContactDate ОБОИХ контактов в одной writer-команде/транзакции.
    // Раньше это были 1+N отдельных команд. ──
    @Test
    fun markCalendarItemCompleted_interactionEvent_completesItemAndAllLinkedContactsAtomically() {
        val contactA = "cal2fA"; val contactB = "cal2fB"; val itemId = "cal2fItem"
        runBlocking {
            AppStateStore.addContact(contactWith(contactA, "+7101", "a2f@test.test", "St A")).await()
            AppStateStore.addContact(contactWith(contactB, "+7102", "b2f@test.test", "St B")).await()
        }
        val item = CalendarItem(
            id = itemId, title = "Встреча", type = CalendarItemType.MEETING,
            startDate = "2026-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            links = listOf(
                CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, contactA),
                CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, contactB)
            ),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        runBlocking { AppStateStore.addCalendarItem(item).await() }

        AppStateStore.markCalendarItemCompleted(item)

        val settled = pollUntilTrue(timeoutMs = 8000) {
            val row = db.calendarDao().getAllCalendarItems().firstOrNull { it.id == itemId }
            val cs = db.contactDao().getAllContacts()
            row?.status == CalendarItemStatus.COMPLETED.name &&
                !cs.single { it.id == contactA }.lastContactDate.isNullOrBlank() &&
                !cs.single { it.id == contactB }.lastContactDate.isNullOrBlank()
        }
        if (!settled) fail("статус события и/или lastContactDate контактов не долетели до Room за таймаут")

        runBlocking {
            val row = db.calendarDao().getAllCalendarItems().single { it.id == itemId }
            assertEquals(CalendarItemStatus.COMPLETED.name, row.status)
            val cs = db.contactDao().getAllContacts()
            val dateA = cs.single { it.id == contactA }.lastContactDate
            val dateB = cs.single { it.id == contactB }.lastContactDate
            assertTrue("оба связанных контакта должны получить lastContactDate",
                !dateA.isNullOrBlank() && !dateB.isNullOrBlank())
            // §24: один логический timestamp на всё действие — обе даты равны
            // между собой И равны дате updatedAt самого события.
            assertEquals("оба контакта должны получить ОДНУ И ТУ ЖЕ дату", dateA, dateB)
            assertEquals("дата контактов должна совпадать с updatedAt завершённого события",
                row.updatedAt.take(10), dateA)
        }
    }

    // ── TEST GROUP S2 (Batch 2F §16): событие НЕ-общение (напр. день рождения)
    // не должно трогать lastContactDate — semantics сохранены 1:1. ──
    @Test
    fun markCalendarItemCompleted_nonInteractionEvent_doesNotTouchLastContactDate() {
        val contactId = "cal2fC"; val itemId = "cal2fBday"
        runBlocking {
            AppStateStore.addContact(contactWith(contactId, "+7103", "c2f@test.test", "St C")).await()
        }
        val before = runBlocking { db.contactDao().getAllContacts().single { it.id == contactId }.lastContactDate }
        val item = CalendarItem(
            id = itemId, title = "День рождения", type = CalendarItemType.BIRTHDAY,
            startDate = "1990-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink(AppStateStore.generateId(), itemId, CalendarTargetType.CONTACT, contactId)),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        runBlocking { AppStateStore.addCalendarItem(item).await() }

        AppStateStore.markCalendarItemCompleted(item)

        val settled = pollUntilTrue(timeoutMs = 8000) {
            db.calendarDao().getAllCalendarItems().firstOrNull { it.id == itemId }?.status ==
                CalendarItemStatus.COMPLETED.name
        }
        if (!settled) fail("статус события не долетел до Room за таймаут")

        runBlocking {
            val row = db.contactDao().getAllContacts().single { it.id == contactId }
            assertEquals("lastContactDate не должен меняться для события-не-общения",
                before, row.lastContactDate)
        }
    }

    // ── TEST GROUP R3/S3 (Batch 2F §11/§21, atomic-failure rollback): TEST GAP —
    // CONTROLLED FAILURE INJECTION.
    // Тот же класс ограничения, что TEST GROUP B/N/P2: DAO — конкретные Room-
    // классы без DI-слоя, искусственный throw из production-кода запрещён.
    //   ЧТО должно быть проверено: при исключении ВНУТРИ
    //   db.withTransaction{ insertNote + updateLastContactDate } (и, симметрично,
    //   внутри { updateCalendarItem + N×updateLastContactDate }) Room обязан
    //   откатить ВСЮ транзакцию — недопустимы durable-состояния
    //   «lastContactDate обновлён, Note отсутствует» и «событие COMPLETED,
    //   контакты со старой датой».
    //   ПОЧЕМУ уверенность есть без теста: сама транзакция — production-real
    //   (не тестовый дубль), а атомарность db.withTransaction — гарантия
    //   Room/SQLite, уже опирающаяся на тот же механизм в deleteContact/
    //   mergeContacts/undoMerge, покрытых зелёными тестами выше.
    // ── TEST GROUP N (Batch 2C §12/§39, multi-step tag/group rollback): TEST GAP ──
    // Тот же класс ограничения, что и TEST GROUP B ниже: DAO — конкретные Room-
    // классы без DI-слоя за абстракцией, и Batch 2 §11 явно запрещает как
    // тестовые дубли конкретных DAO, так и искусственный throw из production-
    // кода ради одного теста. Формально TEST GAP, не тихий пропуск:
    //   ЧТО должно быть проверено: если исключение происходит ВНУТРИ
    //   db.withTransaction у setContactGroups/setContactTags/deleteGroup/
    //   deleteTag (например, между deleteGroupMembersForGroup и
    //   deleteContactGroup), Room обязан откатить ВСЮ транзакцию — старое
    //   согласованное membership остаётся нетронутым, группа/тег не исчезает
    //   "наполовину".
    //   ПОЧЕМУ уверенность есть без теста: гарантия самого Room/SQLite
    //   (`withTransaction`, не наш код) — тот же аргумент, что и в TEST GROUP B,
    //   применённый к тем же delete-old+insert-new/delete-membership+delete-
    //   parent транзакциям, которые Batch 2C только что перевёл на
    //   enqueueWrite { db.withTransaction { ... } }.
    // ── TEST GROUP O (Batch 2D, D-001/§12): repairMissingContactLinksFromTitles()
    // must go through the global writer AND its suspend contract must be
    // durable-on-return (no direct raw Room write left, no orphan poll needed). ──
    @Test
    fun repairMissingContactLinksFromTitles_relinksOrphanDateEvent_viaGlobalWriter() {
        val contactId = "repairlink1"
        val eventId = "repaircal1"
        runBlocking {
            AppStateStore.addContact(
                Contact(
                    id = contactId, firstName = "Иван", lastName = "Петров", photoUri = null,
                    relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
                    importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
                    communicationRhythm = CommunicationRhythm.NOT_TRACKED,
                    createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
                )
            ).await()
            AppStateStore.addCalendarItem(
                CalendarItem(
                    id = eventId, title = "День рождения: Иван Петров", type = CalendarItemType.BIRTHDAY,
                    startDate = "1990-06-15", isAllDay = true, status = CalendarItemStatus.ACTIVE,
                    importance = ImportanceLevel.KEY, links = emptyList(),
                    createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
                )
            ).await()
        }

        // repairMissingContactLinksFromTitles() содержит withContext(Dispatchers.Main)
        // (пред-существующий код ДО D-001, этим фиксом не тронут) — под
        // Robolectric LooperMode.PAUSED (дефолт) он никогда не резолвится сам
        // по себе на потоке, блокированном изнутри runBlocking{} — тот же
        // класс проблемы, что делает AppStateStoreRestoreTest.kt
        // недетерминированно зависающим целиком (см. project memory). Поэтому
        // вызов идёт в ОТДЕЛЬНОЙ корутине (не блокируя тестовый поток), а
        // тестовый поток тем временем прокручивает (idle()) главный looper
        // через уже существующий pollUntilTrue, пока корутина не завершится.
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            AppStateStore.repairMissingContactLinksFromTitles()
            done.complete(Unit)
        }
        val settled = pollUntilTrue(timeoutMs = 5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            done.isCompleted
        }
        if (!settled) fail("repairMissingContactLinksFromTitles() не завершилась за таймаут")

        val repaired = AppStateStore.calendarItems.find { it.id == eventId }
        assertTrue("после repair у события должна появиться связь с $contactId, links=${repaired?.links}",
            repaired!!.links.any { it.targetId == contactId })
        // Durable: связь уже должна быть в Room к этому моменту — D-001 сделал
        // функцию await() своей enqueued команды, контракт "suspend
        // возвращается только после реального применения" теперь сильнее
        // прежнего (раньше это давал прямой вызов DAO в обход writer'а).
        val linksInDb = runBlocking { db.calendarDao().getCalendarItemLinks() }
        assertTrue("восстановленная связь должна быть в Room сразу после return",
            linksInDb.any { it.targetId == contactId && it.calendarItemId == eventId })
    }

    // ── TEST GROUP P (Batch 2D, D-002/§9): legacy tag migration — managed tags
    // и memberships created durably, completion flag only after commit. ──
    @Test
    fun migrateLegacyContactTagsIfNeeded_createsManagedTagsAndMemberships_flagOnlyAfterCommit() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.init(ctx)
        // setup() уже дождался ЗАВЕРШЕНИЯ фонового loadInitialData() (включая
        // его собственный migrateLegacyContactTagsIfNeeded() — над пустым на
        // тот момент состоянием, поэтому он просто взвёл флаг и вышел без
        // изменений) — гонки с ним больше нет. Здесь просто сбрасываем флаг,
        // чтобы поставить СВОЙ контролируемый сценарий (легаси-контакт создан
        // ДО вызова, флаг = false ДО вызова).
        AppSettings.legacyContactTagsMigrated.value = true
        val contactId = "legacytag1"
        AppStateStore.addContact(
            contactWith(contactId, "+6001", "legacytag1@test.test", "St 1").copy(tags = listOf("Электрик", "vip"))
        ).await()
        AppSettings.legacyContactTagsMigrated.value = false

        AppStateStore.migrateLegacyContactTagsIfNeeded()

        assertTrue("флаг должен стать true только после успешной миграции", AppSettings.legacyContactTagsMigrated.value)
        val managedNames = AppStateStore.tagsOfContact(contactId).map { it.name.lowercase() }.toSet()
        assertTrue("управляемый тег 'электрик' должен быть создан и привязан", managedNames.contains("электрик"))
        assertTrue("управляемый тег 'vip' должен быть создан и привязан", managedNames.contains("vip"))
        // Durable: и Tag, и membership реально в Room, не только в памяти.
        val dbMembers = db.contactDao().getContactTagMembers().first().filter { it.contactId == contactId }
        assertEquals(2, dbMembers.size)
        val dbTags = db.contactDao().getTags().first()
        assertTrue(dbTags.any { it.name.equals("Электрик", ignoreCase = true) })
        assertTrue(dbTags.any { it.name.equals("vip", ignoreCase = true) })
    }

    // ── TEST GROUP P2 (Batch 2D §10, migration failure): TEST GAP ──
    // Тот же класс ограничения, что TEST GROUP B/N: нет DI-слоя над DAO,
    // искусственный throw из production-кода запрещён (Batch 2 §11). Формально
    // задокументировано, не тихо пропущено:
    //   ЧТО должно быть проверено: если исключение происходит ВНУТРИ
    //   enqueueWrite{db.withTransaction{...}} миграции (например, между
    //   insertTag и insertContactTagMembers), Room обязан откатить всю
    //   транзакцию, а `.await()` выше по стеку обязан выбросить исключение —
    //   ДО того, как migrateLegacyContactTagsIfNeeded() дойдёт до
    //   `AppSettings.legacyContactTagsMigrated.value = true` (флаг не должен
    //   взводиться при неуспехе).
    //   ПОЧЕМУ уверенность есть без теста: `.await()` на Deferred от
    //   DbWriteQueue.enqueue() уже покрыт DbWriteQueueTest
    //   (awaitOnFailedCommandThrowsTheOriginalException) — падение
    //   пробрасывается к вызывающему, а не проглатывается; строка с флагом в
    //   migrateLegacyContactTagsIfNeeded() физически стоит ПОСЛЕ `.await()` —
    //   при исключении управление до неё не дойдёт (обычный control flow
    //   Kotlin, не требует отдельного теста).

    // ── TEST GROUP Q (Batch 2D, D-003/§11): default tag seed — commit-aware
    // flag, retry-safe (no duplicates on re-run). ──
    @Test
    fun seedDefaultTagsIfNeeded_createsDefaultTags_flagOnlyAfterCommit_andRetrySafe() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.init(ctx)
        // Тот же приём, что и в TEST GROUP P: setup() уже дождался завершения
        // фонового loadInitialData() (включая его собственный
        // seedDefaultTagsIfNeeded() — реально засеял 15 дефолтных тегов над
        // пустой БД). Чистим этот результат и ставим СВОЙ контролируемый прогон.
        AppSettings.defaultTagsSeeded.value = true
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
        AppSettings.defaultTagsSeeded.value = false

        AppStateStore.seedDefaultTagsIfNeeded(ctx)

        assertTrue("флаг должен быть true после успешного сидирования", AppSettings.defaultTagsSeeded.value)
        assertTrue("дефолтные теги должны быть созданы", AppStateStore.tags.isNotEmpty())
        val expectedCount = AppStateStore.tags.size
        val dbTagsAfterFirstRun = db.contactDao().getTags().first()
        assertEquals("в БД должно быть ровно столько же тегов, сколько в памяти — без дублей",
            expectedCount, dbTagsAfterFirstRun.size)

        // Повторный запуск (retry, например после потери флага) не должен
        // плодить дубли — идемпотентность держится на tags.isNotEmpty(), не
        // только на флаге (см. комментарий в production-коде, D-003 §requirement).
        AppStateStore.seedDefaultTagsIfNeeded(ctx)
        assertEquals("повторный запуск не должен создавать дубли в памяти", expectedCount, AppStateStore.tags.size)
        assertEquals("повторный запуск не должен создавать дубли в БД",
            expectedCount, db.contactDao().getTags().first().size)
    }

    // ── TEST GROUP B (transaction rollback): TEST GAP ──
    // Инжектировать реальный сбой ПОСЕРЕДИНЕ транзакции потребовало бы либо
    // подменять конкретную RoomDatabase/DAO реализацию тестовым дублем (в
    // проекте нет DI-слоя над DAO — они конкретные Room-классы, не интерфейсы
    // за абстракцией), либо кидать исключение из production-кода искусственно
    // (Batch 2 §11 явно запрещает и то, и другое ради одного теста). Формально
    // задокументировано как TEST GAP, а не тихо пропущено:
    //   ЧТО должно быть проверено: при исключении ВНУТРИ db.withTransaction
    //   (например, между удалением старых phones/emails и вставкой новых)
    //   Room обязан откатить ВСЮ транзакцию — старый согласованный набор
    //   детей контакта остаётся нетронутым, ничего не потеряно.
    //   ПОЧЕМУ уверенность есть без теста: это гарантия самого Room/SQLite
    //   (`withTransaction` не наш код), и тот же паттерн (delete+insert внутри
    //   одной db.withTransaction) уже используется в mergeContacts()/
    //   mergeCompanies()/convertContactToCompany() до этого batch'а без
    //   отдельного rollback-теста — доверие к транзакционности Room, не к
    //   написанному здесь коду.
}
