package com.aistudio.socialsphere.crmlxb.utils

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.addressRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.contact
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.emailRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.nameRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.orgRow
import com.aistudio.socialsphere.crmlxb.utils.FakeContactsProvider.Companion.phoneRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * PHONE / VCF PACK #4 — «Обновить из телефона» (FRONT 1, 2, 4, 5, 6, 7).
 *
 * Провайдер Android Contacts — [FakeContactsProvider] (Robolectric): моделирует Data-запросы, разрешение LOOKUP_KEY,
 * смену _ID при переагрегации, SecurityException без READ_CONTACTS, пустые/null/битые курсоры.
 * Expected-значения — константы и независимые чтения Room (DAO), а не тестируемый объект.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneContactSyncTest {

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase
    private val t0 = "2026-01-01T00:00:00"

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.defaultTagsSeeded.value = true
        FakeContactsProvider.reset()
        Robolectric.buildContentProvider(FakeContactsProvider::class.java).create(ContactsContract.AUTHORITY)
        grant()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.writeGateForTests = null
        AppStateStore.writeFaultForTests = null
        PhoneContactSync.afterReadHookForTests = null
        AppStateStore.initialize(ctx, db)
        if (!pollUntilTrue(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading })
            fail("loadInitialData не завершился")
    }

    @After
    fun tearDown() {
        PhoneContactSync.afterReadHookForTests = null
        AppStateStore.writeFaultForTests = null
        clearMemory(); db.close(); FakeContactsProvider.reset()
    }

    // ─── обвязка ────────────────────────────────────────────────
    private fun grant() = shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.READ_CONTACTS)
    private fun deny() = shadowOf(ctx as android.app.Application).denyPermissions(Manifest.permission.READ_CONTACTS)

    private fun clearMemory() {
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear(); AppStateStore.gifts.clear()
        AppStateStore.addresses.clear(); AppStateStore.calendarItems.clear(); AppStateStore.companyRelations.clear()
        AppStateStore.contactRelations.clear(); AppStateStore.groups.clear(); AppStateStore.groupMembers.clear()
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear(); AppStateStore.personalDetails.clear(); AppStateStore.sizeInfos.clear()
    }

    private fun pollUntilTrue(timeoutMs: Long, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { kotlinx.coroutines.delay(20); ok = check() }
        ok
    }

    private fun <T> awaitOffMain(timeoutMs: Long = 30_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch { try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) } }
        if (!pollUntilTrue(timeoutMs) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); done.isCompleted })
            fail("операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }

    private fun pull(id: String = "c1"): PhoneSyncResult = awaitOffMain { PhoneContactSync.pullFromPhone(ctx, id) }
    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    private fun socialContact(
        id: String = "c1", first: String = "Анна", last: String = "",
        deviceId: String? = "device_contact_10", key: String? = "L10", phones: List<ContactPhone> = emptyList(),
    ) = Contact(
        id = id, firstName = first, lastName = last, photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, deviceContactId = deviceId, deviceLookupKey = key, phones = phones,
        createdAt = t0, updatedAt = t0)

    private fun add(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }

    private fun roomContact(id: String = "c1") = runBlocking { db.contactDao().getAllContacts().first { it.id == id } }
    private fun roomPhones(id: String = "c1"): List<String> = runBlocking { db.contactDao().getContactPhones().filter { it.contactId == id }.map { it.number } }
    private fun roomEmails(id: String = "c1"): List<String> = runBlocking { db.contactDao().getContactEmails().filter { it.contactId == id }.map { it.email } }
    private fun roomAddresses(id: String = "c1") = runBlocking { db.addressDao().getAllAddresses().filter { it.ownerId == id } }

    /** Стандартный Android-контакт из книги. */
    private fun androidAnna(id: Long = 10, key: String = "L10") = contact(id, key,
        nameRow("Анна", "Иванова"), phoneRow("+79001112233"), phoneRow("+79004445566", ContactsContract.CommonDataKinds.Phone.TYPE_WORK),
        emailRow("anna@example.com"), addressRow("Ленина 5", "Москва", "Россия"), orgRow("ООО Ромашка", "Директор"))

    // ═════════ FRONT 1 — сценарии A–H: ни один не падает ═════════

    @Test
    fun A_validLinkedContact_updateSucceeds() {
        add(socialContact(phones = listOf(ContactPhone("p-old", "c1", "+7 900 111-22-33", PhoneType.MOBILE, true))))
        androidAnna()

        val r = pull()

        assertEquals(PhoneSyncResult.Applied(phones = 1, emails = 1, addresses = 1, workplace = true), r)
        assertEquals("уже существующий номер (другой формат) не задвоен", setOf("+7 900 111-22-33", "+79004445566"), roomPhones().toSet())
        assertEquals(listOf("anna@example.com"), roomEmails())
        assertEquals(listOf("Ленина 5"), roomAddresses().map { it.addressLine })
        assertEquals("пустая фамилия заполнена из телефона", "Иванова", roomContact().lastName)
        assertEquals("Анна", roomContact().firstName)
        assertEquals(1, runBlocking { db.contactDao().getContactCompanyRelations() }.size)
    }

    @Test
    fun B_linkedContactWithoutEmail() {
        add(socialContact())
        contact(10, "L10", nameRow("Анна", "Иванова"), phoneRow("+79001112233"))
        assertEquals(PhoneSyncResult.Applied(1, 0, 0, false), pull())
        assertEquals(emptyList<String>(), roomEmails())
    }

    @Test
    fun C_linkedContactWithoutPhone_onlyEmail() {
        add(socialContact())
        contact(10, "L10", nameRow("Анна", "Иванова"), emailRow("anna@example.com"))
        assertEquals(PhoneSyncResult.Applied(0, 1, 0, false), pull())
        assertEquals(listOf("anna@example.com"), roomEmails())
    }

    @Test
    fun D_linkedContactWithoutPostalAddress() {
        add(socialContact())
        contact(10, "L10", nameRow("Анна", "Иванова"), phoneRow("+79001112233"), emailRow("a@x.com"))
        assertEquals(PhoneSyncResult.Applied(1, 1, 0, false), pull())
        assertTrue(roomAddresses().isEmpty())
    }

    @Test
    fun E_multiplePhonesAndEmails_dedupedByDigitsAndCase() {
        add(socialContact())
        contact(10, "L10", nameRow("Анна", "Иванова"),
            phoneRow("+7 900 111-22-33"), phoneRow("89001112233"), phoneRow("+7 900 444-55-66"),         // первые два — один номер
            emailRow("Anna@Example.com"), emailRow("anna@example.com"), emailRow("work@example.com"))
        val r = pull() as PhoneSyncResult.Applied
        assertEquals(2 to 2, r.phones to r.emails)
        assertEquals(2, roomPhones().size); assertEquals(2, roomEmails().size)
        val primaries = runBlocking { db.contactDao().getContactPhones() }.filter { it.contactId == "c1" && it.isPrimary }
        assertEquals("ровно один основной номер", 1, primaries.size)
    }

    @Test
    fun F_androidContactDeleted_isExplicitLinkGone_dataUntouched() {
        add(socialContact(phones = listOf(ContactPhone("p1", "c1", "+79001112233", PhoneType.MOBILE, true))))
        androidAnna().also { FakeContactsProvider.contacts.remove(it) }                       // удалён в книге
        val before = roomContact()
        assertEquals(PhoneSyncResult.LinkGone, pull())
        assertEquals(before, roomContact())
        assertEquals(listOf("+79001112233"), roomPhones())
    }

    @Test
    fun G1_permissionRevoked_isExplicitState_providerNotTouched() {
        add(socialContact()); androidAnna(); deny()
        assertEquals(PhoneSyncResult.PermissionDenied, pull())
        assertTrue("без разрешения провайдер даже не опрашивается", FakeContactsProvider.queries.isEmpty())
    }

    @Test
    fun G2_permissionGrantedButProviderDeniesAccess_autoRevokeRace() {
        add(socialContact()); androidAnna()
        FakeContactsProvider.securityFailure = true          // провайдер бросает SecurityException (автосброс разрешений)
        assertEquals(PhoneSyncResult.PermissionDenied, pull())
    }

    @Test
    fun H_resolverReturnsEmptyCursor_isLinkGone_notCrash() {
        add(socialContact()); androidAnna()
        FakeContactsProvider.emptyCursors = true
        assertEquals(PhoneSyncResult.LinkGone, pull())
    }

    // ── прочие crash-prone условия из задачи ──

    @Test
    fun resolverReturnsNull_isFailedNotGone() {
        add(socialContact()); androidAnna()
        FakeContactsProvider.nullCursors = true
        val r = pull()
        assertTrue("null-курсор = провайдер недоступен, а не «контакт удалён»: $r", r is PhoneSyncResult.Failed)
    }

    @Test
    fun cursorMissingRequiredColumn_isFailedNotCrash() {
        add(socialContact()); androidAnna()
        FakeContactsProvider.dropColumns = setOf("contact_id")
        assertTrue(pull() is PhoneSyncResult.Failed)
    }

    @Test
    fun malformedRows_areSkipped_validRowsStillApplied() {
        add(socialContact()); androidAnna()
        FakeContactsProvider.extraMalformedRows = true
        assertTrue(pull() is PhoneSyncResult.Applied)
        assertTrue(roomPhones().contains("+79004445566"))
    }

    @Test
    fun providerThrowsUnexpectedly_isFailedNotCrash() {
        add(socialContact()); androidAnna()
        FakeContactsProvider.throwOnQuery = IllegalStateException("provider died")
        assertTrue(pull() is PhoneSyncResult.Failed)
    }

    @Test
    fun androidContactWithoutStructuredName_phonesStillApplied_namesUntouched() {
        add(socialContact(first = "Анна", last = "Петрова"))
        contact(10, "L10", phoneRow("+79001112233"))
        assertEquals(PhoneSyncResult.Applied(1, 0, 0, false), pull())
        assertEquals("Анна" to "Петрова", roomContact().let { it.firstName to it.lastName })
    }

    @Test
    fun staleLegacyId_notResolvable_isLinkGone() {
        add(socialContact(deviceId = "device_contact_999", key = null)); androidAnna()
        assertEquals(PhoneSyncResult.LinkGone, pull())
    }

    @Test
    fun notLinkedContact_isNotLinked_noProviderAccess() {
        add(socialContact(deviceId = null, key = null))
        assertEquals(PhoneSyncResult.NotLinked, pull())
        assertTrue(FakeContactsProvider.queries.isEmpty())
    }

    @Test
    fun databaseCommitFailure_isCommitFailed_notCrash_dataNotClaimedSaved() {
        add(socialContact()); androidAnna()
        val failuresBefore = AppStateStore.writeFailureCount
        AppStateStore.writeFaultForTests = java.io.IOException("disk full")
        val r = pull()
        assertEquals(PhoneSyncResult.CommitFailed, r)
        assertEquals("Room не изменился", emptyList<String>(), roomPhones())
        AppStateStore.writeFaultForTests = null
        assertTrue(pollUntilTrue(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); AppStateStore.writeFailureCount > failuresBefore })
        assertTrue("после устранения сбоя тот же pull проходит", pull() is PhoneSyncResult.Applied)
    }

    // ═════════ FRONT 4 — без picker для связанного ═════════

    @Test
    fun linkedAndResolvable_readsOnlyTheLinkedContact_noFullPhonebookScan_noPicker() {
        add(socialContact()); androidAnna()
        contact(11, "L11", nameRow("Борис", "Петров"), phoneRow("+79990000000"))         // другие контакты книги
        val r = pull()
        assertTrue(r is PhoneSyncResult.Applied)
        assertNotEquals("picker для связанного и валидного контакта запрещён", SyncUiAction.OPEN_PICKER, uiActionFor(r))
        val dataQueries = FakeContactsProvider.queries.filter { it.contains("/data") }
        assertTrue("Data читается ТОЛЬКО для связанного контакта (contact_id=?), не вся книга: $dataQueries",
            dataQueries.isNotEmpty() && dataQueries.all { it.contains("contact_id=?") && it.endsWith("| 10") })
        assertFalse(roomPhones().contains("+79990000000"))
        assertTrue("разрешение LOOKUP_KEY использовано", FakeContactsProvider.queries.any { it.contains("/contacts/lookup/L10") })
    }

    @Test
    fun uiActions_pickerOnlyForNotLinked_goneOffersRelink_neverAutoPicker() {
        assertEquals(SyncUiAction.OPEN_PICKER, uiActionFor(PhoneSyncResult.NotLinked))
        assertEquals(SyncUiAction.OFFER_RELINK, uiActionFor(PhoneSyncResult.LinkGone))
        assertEquals(SyncUiAction.REQUEST_PERMISSION, uiActionFor(PhoneSyncResult.PermissionDenied))
        assertEquals(SyncUiAction.SHOW_FAILED, uiActionFor(PhoneSyncResult.Failed("x")))
        assertEquals(SyncUiAction.NONE, uiActionFor(PhoneSyncResult.CommitFailed))
        assertEquals(SyncUiAction.SHOW_NOTHING_NEW, uiActionFor(PhoneSyncResult.NothingNew))
        listOf(PhoneSyncResult.Applied(1, 0, 0, false), PhoneSyncResult.NothingNew, PhoneSyncResult.LinkGone, PhoneSyncResult.PermissionDenied,
            PhoneSyncResult.CommitFailed, PhoneSyncResult.Failed("x")).forEach {
            assertNotEquals("$it не должен открывать picker сам", SyncUiAction.OPEN_PICKER, uiActionFor(it))
        }
    }

    // ═════════ FRONT 2/3/5 — устойчивая связь ═════════

    @Test
    fun I_linkSurvivesRestart_pullResolvesSameAndroidContact_withoutPicker() {
        add(socialContact(deviceId = null, key = null)); androidAnna()
        // выбор в picker → связь парой LOOKUP_KEY + кэш-id
        val picked = ContactImporter.getDeviceContacts(ctx).single()
        assertEquals("L10", picked.deviceLookupKey)
        runBlocking { PhoneContactSync.link(AppStateStore.getContact("c1")!!, picked).await() }
        settle()
        assertEquals("device_contact_10" to "L10", roomContact().let { it.deviceContactId to it.deviceLookupKey })

        // «рестарт»: память потеряна, состояние читается из Room
        clearMemory(); awaitOffMain { AppStateStore.reloadFromDb() }
        assertEquals("L10", AppStateStore.getContact("c1")!!.deviceLookupKey)
        FakeContactsProvider.queries.clear()
        assertTrue(pull() is PhoneSyncResult.Applied)
        assertTrue(FakeContactsProvider.queries.any { it.contains("/contacts/lookup/L10") })
    }

    @Test
    fun K_androidContactRenamed_sameLinkResolves() {
        add(socialContact()); val a = androidAnna()
        a.rows.removeAll { it.mime == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }
        a.rows.add(0, nameRow("Совсем", "Другое"))                                         // переименован в телефоне
        a.rows.add(phoneRow("+79997776655"))
        val r = pull()
        assertTrue("$r", r is PhoneSyncResult.Applied)
        assertTrue(roomPhones().contains("+79997776655"))
        assertEquals("связь осталась той же", "L10", roomContact().deviceLookupKey)
    }

    @Test
    fun L_androidPhoneNumberChanged_sameLinkResolves_newNumberAdded_oldKept() {
        add(socialContact(phones = listOf(ContactPhone("p-old", "c1", "+79001112233", PhoneType.MOBILE, true))))
        val a = androidAnna()
        a.rows.removeAll { it.mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }
        a.rows.add(phoneRow("+79995554433"))                                                // номер сменён в телефоне
        val r = pull()
        assertTrue("$r", r is PhoneSyncResult.Applied)
        assertEquals("аддитивно: старый номер не удалён, новый добавлен", setOf("+79001112233", "+79995554433"), roomPhones().toSet())
    }

    @Test
    fun reaggregation_androidIdChanged_lookupKeyStillResolves_cachedIdUpgraded() {
        add(socialContact()); val a = androidAnna()
        a.id = 77                                                                           // _ID изменился, LOOKUP_KEY тот же
        assertTrue(pull() is PhoneSyncResult.Applied)
        assertEquals("кэш-id обновлён", "device_contact_77" to "L10", roomContact().let { it.deviceContactId to it.deviceLookupKey })
    }

    @Test
    fun legacyLink_onlyId_resolvesById_thenGetsLookupKey() {
        add(socialContact(deviceId = "device_contact_10", key = null)); androidAnna()
        assertNull(roomContact().deviceLookupKey)
        val r = pull()
        assertTrue("$r", r is PhoneSyncResult.Applied)
        assertEquals("при первом успешном resolve заполнен LOOKUP_KEY", "L10", roomContact().deviceLookupKey)
    }

    @Test
    fun legacyLink_nothingNew_stillUpgradesLookupKeySilently() {
        add(socialContact(deviceId = "device_contact_10", key = null, phones = listOf(ContactPhone("p1", "c1", "+79001112233", PhoneType.MOBILE, true))))
        contact(10, "L10", nameRow("Анна", ""), phoneRow("+79001112233"))
        assertEquals(PhoneSyncResult.NothingNew, pull())
        settle()
        assertEquals("L10", roomContact().deviceLookupKey)
    }

    /** Идентичность — не имя и не номер: контакт с тем же именем/номером, но другим LOOKUP_KEY, не подменяет связь. */
    @Test
    fun identityIsLookupKey_notNameOrPhone() {
        add(socialContact(first = "Анна", key = "LY", deviceId = "device_contact_20", phones = listOf(ContactPhone("p1", "c1", "+79001112233", PhoneType.MOBILE, true))))
        contact(10, "LX", nameRow("Анна", "Иванова"), phoneRow("+79001112233"), phoneRow("+70000000001"))        // тот же человек по имени/номеру — НЕ связан
        contact(20, "LY", nameRow("Анна", "Другая"), phoneRow("+79876543210"))
        assertTrue(pull() is PhoneSyncResult.Applied)
        assertEquals(setOf("+79001112233", "+79876543210"), roomPhones().toSet())
        assertFalse(roomPhones().contains("+70000000001"))
    }

    @Test
    fun deviceLookupKey_isPersistedAndRoundTripsThroughRoom() {
        add(socialContact(deviceId = "device_contact_10", key = "L10-стабильный"))
        settle()
        clearMemory(); awaitOffMain { AppStateStore.reloadFromDb() }
        assertEquals("L10-стабильный", AppStateStore.getContact("c1")!!.deviceLookupKey)
    }

    // ═════════ FRONT 6 — патч трогает только поля телефона ═════════

    @Test
    fun phoneSync_doesNotTouchInternalOnlyData() {
        val base = socialContact(phones = listOf(ContactPhone("p1", "c1", "+79001112233", PhoneType.MOBILE, true)))
            .copy(nextStep = "Позвонить в пятницу", familyNote = "Муж и дети", lastContactDate = "2026-08-01",
                canHelpWith = "Договоры", talkingPoints = "Футбол", profession = "Юрист")
        add(base)
        runBlocking {
            AppStateStore.addGift(GiftIdea("g1", "c1", "Книга", null, null, null, GiftStatus.IDEA)).await()
            AppStateStore.addNote(Note("n1", "c1", null, null, null, NoteType.WORK, "Внутренняя заметка", null, true, true, t0, t0)).await()
            AppStateStore.setSizeInfo("c1", SizeInfo("s1", "c1", "M", "39")).await()
            AppStateStore.addCalendarItem(CalendarItem("cal1", "Встреча", null, CalendarItemType.MEETING, "2027-01-01", isAllDay = true,
                status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
                links = listOf(CalendarItemLink("l1", "cal1", CalendarTargetType.CONTACT, "c1")), createdAt = t0, updatedAt = t0)).await()
        }
        val tag = AppStateStore.addTag("Мой-тег")!!; AppStateStore.setContactTags("c1", setOf(tag.id))
        val group = AppStateStore.addGroup("Клиенты")!!; AppStateStore.setContactGroups("c1", setOf(group.id))
        runBlocking { AppStateStore.updateContact(AppStateStore.getContact("c1")!!, AppStateStore.getContact("c1")!!.copy(
            personalDetails = listOf(PersonalDetail("d1", "c1", PersonalDetailCategory.LIKES, "чай")))).await() }
        settle()
        val before = snapshotInternals()
        val contactBefore = roomContact()

        contact(10, "L10", nameRow("Анна", "Иванова"), phoneRow("+79001112233"), phoneRow("+79004445566"), emailRow("anna@example.com"))
        assertTrue(pull() is PhoneSyncResult.Applied)
        settle()

        assertEquals("подарки, заметки, размеры, теги, группы, детали, события, связи — без изменений", before, snapshotInternals())
        val after = roomContact()
        assertEquals(listOf("Позвонить в пятницу", "Муж и дети", "Договоры", "Футбол", "Юрист"),
            listOf(after.nextStep, after.familyNote, after.canHelpWith, after.talkingPoints, after.profession))
        assertEquals("lastContactDate не тронут", contactBefore.lastContactDate, after.lastContactDate)
        assertEquals(contactBefore.relationshipType, after.relationshipType)
    }

    private fun snapshotInternals() = runBlocking {
        listOf(db.giftDao().getAllGifts(), db.noteDao().getAllNotes(), db.contactDao().getSizeInfos(), db.contactDao().getPersonalDetails(),
            db.contactDao().getContactGroupMembers(), db.contactDao().getContactTagMembersOnce(), db.calendarDao().getCalendarItemLinks())
    }

    // ═════════ FRONT 7 — независимое изменение во время чтения Android-контакта ═════════

    @Test
    fun concurrentInternalUpdateDuringAndroidRead_isNotRolledBack_phoneFieldsApplied() {
        add(socialContact()); androidAnna()
        PhoneContactSync.afterReadHookForTests = {
            // между чтением Android-контакта и применением патча пользователь меняет внутренние данные SocialSphere
            val now = AppStateStore.getContact("c1")!!
            AppStateStore.updateContact(now, now.copy(nextStep = "НОВЫЙ шаг", familyNote = "НОВАЯ семья")).await()
            AppStateStore.addGift(GiftIdea("g-new", "c1", "Новый подарок", null, null, null, GiftStatus.IDEA)).await()
            AppStateStore.addNote(Note("n-new", "c1", null, null, null, NoteType.GENERAL, "Новая заметка", null, false, createdAt = t0, updatedAt = t0)).await()
        }
        val r = pull()
        assertTrue("$r", r is PhoneSyncResult.Applied)
        settle()

        val c = roomContact()
        assertEquals("внутреннее поле = SocialSphere NEW (не откатано)", "НОВЫЙ шаг" to "НОВАЯ семья", c.nextStep to c.familyNote)
        assertTrue("phone-owned = Android NEW", roomPhones().containsAll(listOf("+79001112233", "+79004445566")))
        assertEquals(listOf("g-new"), runBlocking { db.giftDao().getAllGifts() }.map { it.id })
        assertEquals(listOf("n-new"), runBlocking { db.noteDao().getAllNotes() }.map { it.id })
    }

    @Test
    fun concurrentPhoneAddedManuallyDuringRead_isNotDuplicated() {
        add(socialContact()); androidAnna()
        PhoneContactSync.afterReadHookForTests = {
            val now = AppStateStore.getContact("c1")!!
            AppStateStore.updateContact(now, now.copy(phones = now.phones + ContactPhone("p-manual", "c1", "8 900 111 22 33", PhoneType.MOBILE, true))).await()
        }
        assertTrue(pull() is PhoneSyncResult.Applied)
        val numbers = roomPhones()
        assertEquals("тот же номер, добавленный вручную во время чтения, не задваивается", 1, numbers.count { it.filter(Char::isDigit).takeLast(10) == "9001112233" })
    }

    // ═════════ слияние дублей: связь остаётся ПАРОЙ ═════════

    @Test
    fun duplicateMerge_keepsLinkAsPair_neverMixesHalves() {
        add(socialContact(id = "cKeep", deviceId = null, key = null))
        add(socialContact(id = "cOther", deviceId = "device_contact_20", key = "LY"))
        assertTrue(AppStateStore.mergeContacts(listOf("cKeep", "cOther")) != null)
        settle()
        assertEquals("связь дубля перенесена парой", "device_contact_20" to "LY", roomContact("cKeep").let { it.deviceContactId to it.deviceLookupKey })

        add(socialContact(id = "cA", deviceId = "device_contact_1", key = "LA"))
        add(socialContact(id = "cB", deviceId = "device_contact_2", key = "LB"))
        assertTrue(AppStateStore.mergeContacts(listOf("cA", "cB")) != null)
        settle()
        assertEquals("у выжившего своя связь остаётся целой", "device_contact_1" to "LA", roomContact("cA").let { it.deviceContactId to it.deviceLookupKey })
    }
}
