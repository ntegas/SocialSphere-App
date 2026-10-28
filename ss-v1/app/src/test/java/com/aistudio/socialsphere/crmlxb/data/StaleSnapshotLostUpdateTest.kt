package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

/**
 * DATA PERSISTENCE PACK #2 — FRONT 1 / FRONT 4 / FRONT 5 / FRONT 14 (2026-09-21).
 *
 * Главный инвариант пакета: подтверждённо сохранённое значение, которое пользователь
 * позже явно не менял и не удалял, не может быть откатано другой операцией, державшей
 * устаревший снимок.
 *
 * Канонический паттерн lost-update:
 *   S1 = снимок контакта (экран открыт) -> НЕЗАВИСИМАЯ операция добавляет/меняет
 *   дочернюю запись (телефон/email/мессенджер/деталь) -> S1 НЕ обновляется ->
 *   через S1 меняется несвязанное скалярное поле -> Save -> и скаляр, и НОВАЯ
 *   дочерняя запись обязаны выжить (Room, память, reloadFromDb).
 *
 * Expected-значения — из явно заданных констант и независимых чтений Room (DAO),
 * а не из тестируемого (возможно устаревшего) объекта.
 */
@RunWith(RobolectricTestRunner::class)
class StaleSnapshotLostUpdateTest {

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
        id: String, firstName: String = "Иван",
        phones: List<ContactPhone> = emptyList(), emails: List<ContactEmail> = emptyList(),
        messengers: List<Messenger> = emptyList(), personalDetails: List<PersonalDetail> = emptyList(),
        lastContactDate: String? = null, nextStep: String? = null,
    ) = Contact(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, lastContactDate = lastContactDate,
        nextStep = nextStep,
        phones = phones, emails = emails, messengers = messengers, personalDetails = personalDetails,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )
    private fun phone(cid: String, id: String, number: String, primary: Boolean = false) =
        ContactPhone(id, cid, number, PhoneType.MOBILE, primary)
    private fun email(cid: String, id: String, value: String) = ContactEmail(id, cid, value, EmailType.PERSONAL, false)
    private fun messenger(cid: String, id: String, value: String) =
        Messenger(id, cid, MessengerType.TELEGRAM, value, null, false)
    private fun detail(cid: String, id: String, value: String) =
        PersonalDetail(id, cid, PersonalDetailCategory.LIKES, value)

    private fun addContactAndWait(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }

    /**
     * ЕДИНСТВЕННАЯ точка вызова тестируемой операции «сохранить правку контакта» —
     * base = снимок, с которого экран начал редактирование; edited = то, что экран
     * хочет сохранить.
     */
    private fun save(base: Contact, edited: Contact) {
        runBlocking { AppStateStore.updateContact(base, edited).await() }
    }

    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    // независимые чтения Room
    private fun roomContact(id: String): Contact { settle(); return runBlocking { db.contactDao().getAllContacts().first { it.id == id }.toDomain() } }
    private fun roomPhones(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getContactPhones().filter { it.contactId == owner }.map { it.number }.toSet() } }
    private fun roomEmails(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getContactEmails().filter { it.contactId == owner }.map { it.email }.toSet() } }
    private fun roomMessengers(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getMessengers().filter { it.contactId == owner }.map { it.value }.toSet() } }
    private fun roomDetails(owner: String): Set<String> { settle(); return runBlocking { db.contactDao().getPersonalDetails().filter { it.contactId == owner }.map { it.value }.toSet() } }
    private fun roomCompany(id: String): Company { settle(); return runBlocking { db.companyDao().getAllCompanies().first { it.id == id }.toDomain() } }
    private fun roomCompanyPhones(companyId: String): Set<String> { settle(); return runBlocking { db.contactDao().getContactPhones().filter { it.companyId == companyId }.map { it.number }.toSet() } }
    private fun roomCompanyEmails(companyId: String): Set<String> { settle(); return runBlocking { db.contactDao().getContactEmails().filter { it.companyId == companyId }.map { it.email }.toSet() } }

    private fun fresh(id: String): Contact = AppStateStore.getContact(id)!!

    // ═════════════════════════════════════════════════════════════
    //  FRONT 1 — Contact lost update: каждый тест обязан ПАДАТЬ на whole-object архитектуре.
    // ═════════════════════════════════════════════════════════════

    /** A: устаревший снимок + правка имени НЕ должны стирать телефон, добавленный независимо. */
    @Test
    fun A_phone_staleSnapshotScalarEdit_keepsIndependentlyAddedPhone() {
        addContactAndWait(contact("cA", phones = listOf(phone("cA", "pA1", "+7900000001", primary = true))))
        val s1 = fresh("cA")                                             // экран редактора открыт

        val other = fresh("cA")                                          // независимая операция (другой писатель)
        save(other, other.copy(phones = other.phones + phone("cA", "pA2", "+7900000002")))
        assertEquals("предусловие: Room уже содержит НОВЫЙ телефон", setOf("+7900000001", "+7900000002"), roomPhones("cA"))

        save(s1, s1.copy(firstName = "Правка"))                          // s1 НЕ обновлялся

        assertEquals("имя изменено", "Правка", roomContact("cA").firstName)
        assertEquals("Room: оба телефона", setOf("+7900000001", "+7900000002"), roomPhones("cA"))
        assertEquals("память: оба телефона", setOf("pA1", "pA2"), fresh("cA").phones.map { it.id }.toSet())
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", setOf("pA1", "pA2"), fresh("cA").phones.map { it.id }.toSet())
        assertEquals("после reloadFromDb имя", "Правка", fresh("cA").firstName)
    }

    /** A': независимо ИЗМЕНЁН существующий телефон (номер), устаревший снимок держит старый номер. */
    @Test
    fun A2_phone_staleSnapshotScalarEdit_keepsIndependentlyChangedNumber() {
        addContactAndWait(contact("cA2", phones = listOf(phone("cA2", "pB1", "+7900000001", primary = true))))
        val s1 = fresh("cA2")
        val other = fresh("cA2")
        save(other, other.copy(phones = other.phones.map { it.copy(number = "+7999999999") }))

        save(s1, s1.copy(firstName = "Правка"))

        assertEquals("Room: номер НОВЫЙ, не откатан", setOf("+7999999999"), roomPhones("cA2"))
        assertEquals("имя", "Правка", roomContact("cA2").firstName)
    }

    /** B: email. */
    @Test
    fun B_email_staleSnapshotScalarEdit_keepsIndependentlyAddedEmail() {
        addContactAndWait(contact("cB", emails = listOf(email("cB", "eB1", "a@x.ru"))))
        val s1 = fresh("cB")
        val other = fresh("cB")
        save(other, other.copy(emails = other.emails + email("cB", "eB2", "new@x.ru")))

        save(s1, s1.copy(firstName = "Правка"))

        assertEquals("Room: оба email", setOf("a@x.ru", "new@x.ru"), roomEmails("cB"))
        assertEquals("имя", "Правка", roomContact("cB").firstName)
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", setOf("eB1", "eB2"), fresh("cB").emails.map { it.id }.toSet())
    }

    /** C: мессенджер. */
    @Test
    fun C_messenger_staleSnapshotScalarEdit_keepsIndependentlyAddedMessenger() {
        addContactAndWait(contact("cC", messengers = listOf(messenger("cC", "mC1", "@old"))))
        val s1 = fresh("cC")
        val other = fresh("cC")
        save(other, other.copy(messengers = other.messengers + messenger("cC", "mC2", "@new")))

        save(s1, s1.copy(firstName = "Правка"))

        assertEquals("Room: оба мессенджера", setOf("@old", "@new"), roomMessengers("cC"))
        assertEquals("имя", "Правка", roomContact("cC").firstName)
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", setOf("mC1", "mC2"), fresh("cC").messengers.map { it.id }.toSet())
    }

    /** D: personalDetails. */
    @Test
    fun D_personalDetails_staleSnapshotScalarEdit_keepsIndependentlyAddedDetail() {
        addContactAndWait(contact("cD", personalDetails = listOf(detail("cD", "dD1", "чай"))))
        val s1 = fresh("cD")
        val other = fresh("cD")
        save(other, other.copy(personalDetails = other.personalDetails + detail("cD", "dD2", "кофе")))

        save(s1, s1.copy(firstName = "Правка"))

        assertEquals("Room: обе детали", setOf("чай", "кофе"), roomDetails("cD"))
        assertEquals("имя", "Правка", roomContact("cD").firstName)
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", setOf("dD1", "dD2"), fresh("cD").personalDetails.map { it.id }.toSet())
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 4 — adversarial interleaving: два устаревших UI-состояния.
    // ═════════════════════════════════════════════════════════════

    /** E: S1 правит скаляр и сохраняет; S2 (тоже открыт ДО этого) позже добавляет телефон. Оба обязаны выжить. */
    @Test
    fun E_scalarVsPhone_bothStaleSnapshots_bothNewValuesSurvive() {
        addContactAndWait(contact("cE", phones = listOf(phone("cE", "pE1", "+7900000001", primary = true))))
        val s1 = fresh("cE")
        val s2 = fresh("cE")

        save(s1, s1.copy(firstName = "Правка"))
        save(s2, s2.copy(phones = s2.phones + phone("cE", "pE2", "+7900000002")))

        assertEquals("скаляр из S1 не откатан снимком S2", "Правка", roomContact("cE").firstName)
        assertEquals("телефон из S2", setOf("+7900000001", "+7900000002"), roomPhones("cE"))
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb имя", "Правка", fresh("cE").firstName)
        assertEquals("после reloadFromDb телефоны", setOf("pE1", "pE2"), fresh("cE").phones.map { it.id }.toSet())
    }

    /** F: скаляр vs email — зеркальный порядок (email-правка первой). */
    @Test
    fun F_scalarVsEmail_bothStaleSnapshots_bothNewValuesSurvive() {
        addContactAndWait(contact("cF", emails = listOf(email("cF", "eF1", "a@x.ru"))))
        val s1 = fresh("cF")
        val s2 = fresh("cF")

        save(s2, s2.copy(emails = s2.emails + email("cF", "eF2", "new@x.ru")))
        save(s1, s1.copy(firstName = "Правка"))

        assertEquals("скаляр из S1", "Правка", roomContact("cF").firstName)
        assertEquals("email из S2 не стёрт устаревшим S1", setOf("a@x.ru", "new@x.ru"), roomEmails("cF"))
    }

    /** G: телефон (из одного устаревшего UI-состояния) и email (из другого) — оба живы. */
    @Test
    fun G_phoneVsEmail_differentStaleStates_bothSurvive() {
        addContactAndWait(contact("cG",
            phones = listOf(phone("cG", "pG1", "+7900000001", primary = true)),
            emails = listOf(email("cG", "eG1", "a@x.ru"))))
        val s1 = fresh("cG")
        val s2 = fresh("cG")

        save(s1, s1.copy(phones = s1.phones + phone("cG", "pG2", "+7900000002")))
        save(s2, s2.copy(emails = s2.emails + email("cG", "eG2", "new@x.ru")))

        assertEquals("телефоны: новый из S1 не стёрт S2", setOf("+7900000001", "+7900000002"), roomPhones("cG"))
        assertEquals("email: новый из S2", setOf("a@x.ru", "new@x.ru"), roomEmails("cG"))
    }

    /** H: personalDetails vs скаляр. */
    @Test
    fun H_personalDetailsVsScalar_bothStaleSnapshots_bothSurvive() {
        addContactAndWait(contact("cH", personalDetails = listOf(detail("cH", "dH1", "чай"))))
        val s1 = fresh("cH")
        val s2 = fresh("cH")

        save(s1, s1.copy(personalDetails = s1.personalDetails + detail("cH", "dH2", "кофе")))
        save(s2, s2.copy(nextStep = "Позвонить"))

        assertEquals("детали из S1 не стёрты S2", setOf("чай", "кофе"), roomDetails("cH"))
        assertEquals("скаляр из S2", "Позвонить", roomContact("cH").nextStep)
    }

    /** I: lastContactDate vs сохранение редактора (Pack #1 guard сохраняется). */
    @Test
    fun I_lastContactDate_vsEditorSave_isNotRolledBack() {
        val today = java.time.LocalDate.now().toString()
        addContactAndWait(contact("cI", lastContactDate = "2025-01-01"))
        val s1 = fresh("cI")
        AppStateStore.markContactedNow("cI"); settle()
        assertEquals("предусловие: Room содержит сегодняшнюю дату", today, roomContact("cI").lastContactDate)

        save(s1, s1.copy(firstName = "Правка"))

        assertEquals("Room: дата не откатилась", today, roomContact("cI").lastContactDate)
        assertEquals("память", today, fresh("cI").lastContactDate)
        assertEquals("имя", "Правка", roomContact("cI").firstName)
    }

    // ═════════════════════════════════════════════════════════════
    //  Обратный контроль: НАМЕРЕННАЯ правка по-прежнему проходит.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun intentional_removeChangeAndClearPhones_stillApplied() {
        addContactAndWait(contact("cK", phones = listOf(
            phone("cK", "pK1", "+7900000001", primary = true), phone("cK", "pK2", "+7900000002"), phone("cK", "pK3", "+7900000003"))))
        val s1 = fresh("cK")
        // удалить pK2, изменить номер pK3
        save(s1, s1.copy(phones = s1.phones.filter { it.id != "pK2" }.map { if (it.id == "pK3") it.copy(number = "+7911111111") else it }))
        assertEquals("удаление и правка применены", setOf("+7900000001", "+7911111111"), roomPhones("cK"))

        // очистить все телефоны: edited.phones пуст, base непуст — намеренное удаление
        val s2 = fresh("cK")
        save(s2, s2.copy(phones = emptyList()))
        assertEquals("очистка списка — намеренное удаление", emptySet<String>(), roomPhones("cK"))
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", emptyList<String>(), fresh("cK").phones.map { it.id })
    }

    /** Удаление телефона из устаревшего снимка не должно ЗАДЕТЬ независимо добавленный другой. */
    @Test
    fun intentional_deleteOnePhoneFromStaleSnapshot_keepsIndependentlyAddedOne() {
        addContactAndWait(contact("cM", phones = listOf(
            phone("cM", "pM1", "+7900000001", primary = true), phone("cM", "pM2", "+7900000002"))))
        val s1 = fresh("cM")
        val other = fresh("cM")
        save(other, other.copy(phones = other.phones + phone("cM", "pM3", "+7900000003")))

        save(s1, s1.copy(phones = s1.phones.filter { it.id != "pM2" }))

        assertEquals("pM2 удалён (явно), pM3 (независимый) и pM1 живы", setOf("+7900000001", "+7900000003"), roomPhones("cM"))
    }

    /** Один и тот же телефон изменён двумя писателями: побеждает тот, кто сохранил позже
     *  (per-item last-writer-wins), остальные записи не страдают. */
    @Test
    fun conflict_sameItemChangedTwice_lastWriterWinsOthersUntouched() {
        addContactAndWait(contact("cX", phones = listOf(
            phone("cX", "pX1", "+7900000001", primary = true), phone("cX", "pX2", "+7900000002"))))
        val s1 = fresh("cX")
        val s2 = fresh("cX")
        save(s1, s1.copy(phones = s1.phones.map { if (it.id == "pX1") it.copy(number = "+7911111111") else it }))
        save(s2, s2.copy(phones = s2.phones.map { if (it.id == "pX1") it.copy(number = "+7922222222") else it }))

        assertEquals(setOf("+7922222222", "+7900000002"), roomPhones("cX"))
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 5 — Update-from-Phone (бизнес-слой): применение данных телефона поверх
    //  независимых внутренних правок не откатывает внутренние данные.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun updateFromPhone_afterInternalChanges_doesNotRollBackInternalData() {
        val company = Company(id = "coP", name = "Acme", industry = Industry.IT,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        runBlocking { AppStateStore.addCompany(company).await() }
        addContactAndWait(contact("cP", phones = listOf(phone("cP", "pP1", "+7900000001", primary = true)),
            lastContactDate = "2025-01-01"))
        val phoneSideSnapshot = fresh("cP")           // снимок, с которым «телефон» начал синхронизацию

        // независимые внутренние изменения после снимка
        AppStateStore.addGift(GiftIdea("gP", "cP", "Книга", null, null, null, GiftStatus.IDEA))
        AppStateStore.addNote(Note("nP", "cP", null, null, null, NoteType.GENERAL, "Аллергия", null, false,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"))
        AppStateStore.setSizeInfo("cP", SizeInfo("sP", "cP", clothingSize = "M"))
        AppStateStore.markContactedNow("cP")
        settle()
        val internal = fresh("cP")
        val detailed = internal.copy(
            personalDetails = internal.personalDetails + detail("cP", "dP1", "чай"),
            companyRelations = listOf(ContactCompanyRelation("rP", "cP", "coP", "Инженер", null, null,
                EmploymentStatus.CURRENT, null, null, null, null, null, null, true))
        )
        save(internal, detailed)                       // внутренняя правка: деталь + связь с компанией
        val today = java.time.LocalDate.now().toString()

        // применение данных телефона: новое имя + телефон + email (то, что делает ImportScreens)
        save(phoneSideSnapshot, phoneSideSnapshot.copy(
            firstName = "ИзТелефона",
            phones = phoneSideSnapshot.phones + phone("cP", "pP2", "+7900000002"),
            emails = phoneSideSnapshot.emails + email("cP", "eP1", "phone@x.ru"),
        ))

        val room = roomContact("cP")
        assertEquals("phone-owned: имя обновлено", "ИзТелефона", room.firstName)
        assertEquals("phone-owned: телефоны", setOf("+7900000001", "+7900000002"), roomPhones("cP"))
        assertEquals("phone-owned: email", setOf("phone@x.ru"), roomEmails("cP"))
        assertEquals("подарок жив", listOf("gP"), runBlocking { db.giftDao().getAllGifts().map { it.id } })
        assertEquals("заметка жива", listOf("nP"), runBlocking { db.noteDao().getAllNotes().map { it.id } })
        assertEquals("размеры живы", listOf("M"), runBlocking { db.contactDao().getSizeInfos().map { it.clothingSize } })
        assertEquals("lastContactDate не откатан", today, room.lastContactDate)
        assertEquals("personalDetails жива", setOf("чай"), roomDetails("cP"))
        assertEquals("связь с компанией жива", listOf("rP"),
            runBlocking { db.contactDao().getContactCompanyRelations().map { it.id } })
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 14 — Company: два устаревших снимка редактора компании.
    // ═════════════════════════════════════════════════════════════

    private fun company(id: String, name: String = "Acme", description: String? = null,
                        phones: List<ContactPhone> = emptyList(), emails: List<ContactEmail> = emptyList()) = Company(
        id = id, name = name, industry = Industry.IT, description = description, phones = phones, emails = emails,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )
    private fun cphone(coId: String, id: String, number: String) = ContactPhone(id, coId, number, PhoneType.WORK, false)
    private fun saveCompany(base: Company, edited: Company) {
        runBlocking { AppStateStore.updateCompany(base, edited).await() }
    }

    /** S1 сохраняет скаляр A, S2 (снимок до этого) позже сохраняет скаляр B — A обязан выжить. */
    @Test
    fun company_twoStaleEditors_scalarA_thenScalarB_bothSurvive() {
        runBlocking { AppStateStore.addCompany(company("co1")).await() }
        val s1 = AppStateStore.getCompany("co1")!!
        val s2 = AppStateStore.getCompany("co1")!!

        saveCompany(s1, s1.copy(name = "Новое имя"))
        saveCompany(s2, s2.copy(description = "Описание B"))

        val room = roomCompany("co1")
        assertEquals("скаляр A не откатан снимком S2", "Новое имя", room.name)
        assertEquals("скаляр B", "Описание B", room.description)
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", "Новое имя", AppStateStore.getCompany("co1")!!.name)
    }

    /** Телефоны/email компании: устаревший снимок скаляра не стирает независимо добавленные. */
    @Test
    fun company_staleSnapshotScalarEdit_keepsIndependentlyAddedPhoneAndEmail() {
        runBlocking { AppStateStore.addCompany(company("co2", phones = listOf(cphone("co2", "cp1", "+7495000001")))).await() }
        val s1 = AppStateStore.getCompany("co2")!!
        val other = AppStateStore.getCompany("co2")!!
        saveCompany(other, other.copy(
            phones = other.phones + cphone("co2", "cp2", "+7495000002"),
            emails = other.emails + ContactEmail("ce1", "co2", "info@acme.ru", EmailType.WORK, false)))

        saveCompany(s1, s1.copy(name = "Правка"))

        assertEquals("имя", "Правка", roomCompany("co2").name)
        assertEquals("телефоны компании", setOf("+7495000001", "+7495000002"), roomCompanyPhones("co2"))
        assertEquals("email компании", setOf("info@acme.ru"), roomCompanyEmails("co2"))
    }

    /** Намеренное удаление телефона/адреса компании из редактора по-прежнему применяется. */
    @Test
    fun company_intentionalRemovalOfPhone_stillApplied() {
        runBlocking { AppStateStore.addCompany(company("co3", phones = listOf(
            cphone("co3", "cp1", "+7495000001"), cphone("co3", "cp2", "+7495000002")))).await() }
        val s1 = AppStateStore.getCompany("co3")!!
        saveCompany(s1, s1.copy(phones = s1.phones.filter { it.id != "cp2" }))
        assertEquals(setOf("+7495000001"), roomCompanyPhones("co3"))
        assertTrue(AppStateStore.getCompany("co3")!!.phones.none { it.id == "cp2" })
    }

    // ═════════════════════════════════════════════════════════════
    //  Защита от «забытого поля» (урок §61.1): у каждого свойства Contact/Company обязано быть
    //  правило слияния. Забытое поле молча деградирует к прежнему «побеждает edited» и снова
    //  откатывает чужую свежую правку — этот тест падает, как только у модели появляется свойство,
    //  которое слияние не учитывает (или свойство неизвестного типа, для которого нет значений).
    // ═════════════════════════════════════════════════════════════

    private fun <T : Any> withProp(instance: T, cls: kotlin.reflect.KClass<T>, name: String, value: Any?): T {
        val copy = cls.memberFunctions.first { it.name == "copy" }
        val self = copy.parameters.first()
        val target = copy.parameters.first { it.name == name }
        @Suppress("UNCHECKED_CAST")
        return copy.callBy(mapOf(self to instance, target to value)) as T
    }

    /** Два различных значения свойства, отличных от текущего [cur]. */
    private fun variantsFor(p: kotlin.reflect.KProperty1<*, *>, cur: Any?): Pair<Any?, Any?> {
        val cls = p.returnType.classifier as kotlin.reflect.KClass<*>
        return when {
            cls == String::class -> "v1" to "v2"
            cls == Int::class -> 11 to 22
            cls.java.isEnum -> { val cs = cls.java.enumConstants.filter { it != cur }; cs[0] to cs[1] }
            cls == List::class -> {
                val arg = p.returnType.arguments[0].type!!.classifier as kotlin.reflect.KClass<*>
                when {
                    arg == String::class -> listOf("a") to listOf("b")
                    arg.java.isEnum -> { val cs = arg.java.enumConstants.toList(); listOf(cs[0]) to listOf(cs[1]) }
                    else -> fail("свойство ${p.name}: нет генератора значений для List<${arg.simpleName}> — добавьте правило слияния и расширьте тест") as Nothing
                }
            }
            else -> fail("свойство ${p.name}: нет генератора значений для ${cls.simpleName} — добавьте правило слияния и расширьте тест") as Nothing
        }
    }

    @Test
    fun mergeContactEdit_hasARuleForEveryContactProperty() {
        // выводимые/пересчитываемые в writeContact или списки с id (покрыты поведенческими тестами выше)
        val skipped = setOf("id", "notes", "gifts", "sizeInfo", "updatedAt",
            "phones", "emails", "messengers", "personalDetails", "addresses", "companyRelations")
        val base = contact("cX")
        for (p in Contact::class.memberProperties.filter { it.name !in skipped }) {
            val (v1, v2) = variantsFor(p, p.get(base))
            val canonical = withProp(base, Contact::class, p.name, v1)   // независимая правка другого писателя
            // (a) поле в форме НЕ менялось (edited == base) -> побеждает каноническое (свежее) значение
            assertEquals("${p.name}: не менявшееся поле обязано остаться каноническим",
                v1, p.get(AppStateStore.mergeContactEdit(canonical, base, base)))
            // (b) поле изменено пользователем -> побеждает edited
            assertEquals("${p.name}: намеренная правка обязана примениться",
                v2, p.get(AppStateStore.mergeContactEdit(canonical, base, withProp(base, Contact::class, p.name, v2))))
        }
    }

    @Test
    fun mergeCompanyEdit_hasARuleForEveryCompanyProperty() {
        val skipped = setOf("id", "updatedAt", "phones", "emails", "addresses")
        val base = company("coX")
        for (p in Company::class.memberProperties.filter { it.name !in skipped }) {
            val (v1, v2) = variantsFor(p, p.get(base))
            val canonical = withProp(base, Company::class, p.name, v1)
            assertEquals("${p.name}: не менявшееся поле обязано остаться каноническим",
                v1, p.get(AppStateStore.mergeCompanyEdit(canonical, emptyList(), base, base)))
            assertEquals("${p.name}: намеренная правка обязана примениться",
                v2, p.get(AppStateStore.mergeCompanyEdit(canonical, emptyList(), base, withProp(base, Company::class, p.name, v2))))
        }
    }
}
