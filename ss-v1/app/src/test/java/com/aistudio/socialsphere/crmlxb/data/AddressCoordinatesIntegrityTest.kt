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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OWNERSHIP PACK #1 — FRONT 2 (координаты адресов, Contact и Company) и FRONT 6
 * (источник бэкапа) — 2026-09-21.
 *
 * Address — часть сохранённой сущности Contact/Company, но координаты (и адрес
 * офиса компании, созданный linkContactAddressToCompany) пишутся НЕЗАВИСИМО:
 * setAddressCoords обновлял глобальный addresses и Room, а вложенные
 * Contact.addresses/Company.addresses оставались устаревшими. updateContact/
 * updateCompany (delete + insert из вложенного) стирал координаты, mergeContacts
 * брал их из вложенного, бэкап сериализовал вложенное.
 *
 * Expected-значения берутся из ИСХОДНЫХ fixture (GEOCODED_*), а не из
 * потенциально устаревших Contact/Company. Источники читаются независимо:
 * Room (db.addressDao().getAllAddresses()), память (AppStateStore.addresses),
 * вложенная проекция (getContact/getCompany .addresses), reloadFromDb, JSON.
 */
@RunWith(RobolectricTestRunner::class)
class AddressCoordinatesIntegrityTest {

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

    private fun contact(id: String, firstName: String = "Иван", phones: List<ContactPhone> = emptyList(), addresses: List<Address> = emptyList()) = Contact(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, phones = phones, addresses = addresses,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun company(id: String, name: String = "Acme", phones: List<ContactPhone> = emptyList(), addresses: List<Address> = emptyList()) = Company(
        id = id, name = name, industry = Industry.IT, phones = phones, addresses = addresses,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun contactAddr(id: String, owner: String) = Address(
        id, AddressOwnerType.CONTACT, owner, AddressType.HOME, "Тверская 1", "Москва", "Россия",
        comment = "Домофон 12", postalCode = "125009", district = "Тверской"
    )
    private fun companyAddr(id: String, owner: String, line: String = "Арбат 9") = Address(
        id, AddressOwnerType.COMPANY, owner, AddressType.OFFICE, line, "Москва", "Россия", postalCode = "119002"
    )
    private fun phones(owner: String, n: Int) = (1..n).map { ContactPhone("p$owner$it", owner, "+7900000$owner$it", PhoneType.MOBILE, it == 1) }

    private fun addContactAndWait(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun updateContactAndWait(base: Contact, edited: Contact) { runBlocking { AppStateStore.updateContact(base, edited).await() } }
    private fun addCompanyAndWait(c: Company) { runBlocking { AppStateStore.addCompany(c).await() } }
    private fun updateCompanyAndWait(base: Company, edited: Company) { runBlocking { AppStateStore.updateCompany(base, edited).await() } }
    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }
    private fun geocode(id: String, lat: Double = LAT, lng: Double = LNG) { AppStateStore.setAddressCoords(id, lat, lng); settle() }

    private companion object { const val LAT = 55.75; const val LNG = 37.61 }

    private fun roomAddress(id: String): Address? {
        settle()
        return runBlocking { db.addressDao().getAllAddresses().firstOrNull { it.id == id }?.toDomain() }
    }
    private fun roomAddressesOf(owner: String): List<Address> {
        settle()
        return runBlocking { db.addressDao().getAllAddresses().map { it.toDomain() }.filter { it.ownerId == owner } }
    }
    private fun memoryAddress(id: String): Address? = AppStateStore.addresses.firstOrNull { it.id == id }
    private fun backup(): BackupData = runBlocking {
        ExportManager.parseJsonBackup(ExportManager.backupJsonString()) ?: fail("backup JSON не парсится") as Nothing
    }

    /** Room ПЕРВЫМ (на старом коде падает по целевой причине), затем остальные источники. */
    private fun assertAddressEverywhere(expected: Address, why: String) {
        assertEquals("Room: строка адреса поле-в-поле равна исходной fixture — $why", expected, roomAddress(expected.id))
        assertEquals("память (AppStateStore.addresses) — $why", expected, memoryAddress(expected.id))
        val nested = when (expected.ownerType) {
            AddressOwnerType.CONTACT -> AppStateStore.getContact(expected.ownerId)?.addresses
            AddressOwnerType.COMPANY -> AppStateStore.getCompany(expected.ownerId)?.addresses
        }.orEmpty().firstOrNull { it.id == expected.id }
        assertEquals("вложенная проекция владельца — $why", expected, nested)
        val inJson = when (expected.ownerType) {
            AddressOwnerType.CONTACT -> backup().contacts.first { it.id == expected.ownerId }.addresses
            AddressOwnerType.COMPANY -> backup().companies.first { it.id == expected.ownerId }.addresses
        }.firstOrNull { it.id == expected.id }
        assertEquals("JSON-бэкап — $why", expected, inJson)
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb: память — $why", expected, memoryAddress(expected.id))
        assertEquals("после reloadFromDb: Room — $why", expected, roomAddress(expected.id))
    }

    // ═════════════════════════════════════════════════════════════
    //  CONTACT
    // ═════════════════════════════════════════════════════════════

    /** A. Contact: координаты сохранены -> устаревший Contact (адрес без координат) -> Room не откатывается. */
    @Test
    fun a_contactCoords_staleContactUpdate_coordinatesUnchangedInRoom() {
        val original = contactAddr("ca1", "cA")
        addContactAndWait(contact("cA", addresses = listOf(original)))
        val stale = AppStateStore.getContact("cA")!!
        assertNull("предусловие: устаревший снимок без координат", stale.addresses.single().latitude)

        geocode("ca1")
        assertEquals("предусловие: координаты закоммичены", LAT, roomAddress("ca1")?.latitude)

        updateContactAndWait(stale, stale.copy(firstName = "Изменённое"))

        assertAddressEverywhere(original.copy(latitude = LAT, longitude = LNG), "смена имени не стирает координаты")
    }

    /** C. Только имя: вся строка адреса остаётся байт-в-байт (все поля, не только координаты). */
    @Test
    fun c_contactNameOnly_fullAddressRowEquivalent() {
        val original = contactAddr("ca2", "cC")
        addContactAndWait(contact("cC", addresses = listOf(original)))
        val stale = AppStateStore.getContact("cC")!!
        geocode("ca2")

        updateContactAndWait(stale, stale.copy(firstName = "Пётр", lastName = "Иванов"))

        val row = roomAddress("ca2")!!
        assertEquals("Тверская 1", row.addressLine); assertEquals("Москва", row.city); assertEquals("Россия", row.country)
        assertEquals("Домофон 12", row.comment); assertEquals("125009", row.postalCode); assertEquals("Тверской", row.district)
        assertEquals(AddressType.HOME, row.addressType); assertEquals(AddressOwnerType.CONTACT, row.ownerType)
        assertAddressEverywhere(original.copy(latitude = LAT, longitude = LNG), "все поля адреса на месте")
    }

    // ═════════════════════════════════════════════════════════════
    //  COMPANY
    // ═════════════════════════════════════════════════════════════

    /** B. Company: координаты сохранены -> устаревшая Company -> координаты не стёрты. */
    @Test
    fun b_companyCoords_staleCompanyUpdate_coordinatesUnchangedInRoom() {
        val original = companyAddr("co-a1", "co1")
        addCompanyAndWait(company("co1", addresses = listOf(original)))
        val stale = AppStateStore.getCompany("co1")!!
        assertNull("предусловие: устаревший снимок без координат", stale.addresses.single().latitude)

        geocode("co-a1")
        assertEquals("предусловие: координаты закоммичены", LAT, roomAddress("co-a1")?.latitude)

        updateCompanyAndWait(stale, stale.copy(name = "Новое название"))

        assertEquals("Новое название", AppStateStore.getCompany("co1")!!.name)
        assertAddressEverywhere(original.copy(latitude = LAT, longitude = LNG), "смена названия компании не стирает координаты")
    }

    /** D. Только название: вся строка адреса компании эквивалентна. */
    @Test
    fun d_companyTitleOnly_fullAddressRowEquivalent() {
        val original = companyAddr("co-a2", "co2")
        addCompanyAndWait(company("co2", addresses = listOf(original)))
        val stale = AppStateStore.getCompany("co2")!!
        geocode("co-a2")

        updateCompanyAndWait(stale, stale.copy(name = "Только название"))

        val row = roomAddress("co-a2")!!
        assertEquals("Арбат 9", row.addressLine); assertEquals("119002", row.postalCode)
        assertEquals(AddressType.OFFICE, row.addressType); assertEquals(AddressOwnerType.COMPANY, row.ownerType)
        assertAddressEverywhere(original.copy(latitude = LAT, longitude = LNG), "все поля адреса компании на месте")
    }

    /** Адрес компании, созданный независимо (linkContactAddressToCompany) после открытия
     *  редактора, не удаляется устаревшим снимком компании. */
    @Test
    fun h_companyOfficeAddressLinkedIndependently_survivesStaleCompanyUpdate() {
        addCompanyAndWait(company("co3"))
        val stale = AppStateStore.getCompany("co3")!!
        assertTrue("предусловие: у устаревшего снимка нет адресов", stale.addresses.isEmpty())

        val linkedId = AppStateStore.linkContactAddressToCompany("co3", contactAddr("tmp", "cX").copy(addressLine = "Ленина 5"))
        settle()
        assertEquals("предусловие: адрес офиса в Room", 1, roomAddressesOf("co3").size)

        updateCompanyAndWait(stale, stale.copy(name = "Правка"))

        val rows = roomAddressesOf("co3")
        assertEquals("Room: адрес офиса не удалён устаревшим снимком", listOf(linkedId), rows.map { it.id })
        assertEquals("Ленина 5", rows.single().addressLine)
        assertEquals("память", listOf(linkedId), AppStateStore.addresses.filter { it.ownerId == "co3" }.map { it.id })
        assertEquals("вложенная проекция", listOf(linkedId), AppStateStore.getCompany("co3")!!.addresses.map { it.id })
    }

    // ═════════════════════════════════════════════════════════════
    //  E/F. Бэкап и reload — сразу после геокодинга, БЕЗ обновления контакта/компании.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun e_backupAfterGeocoding_containsCanonicalCoordinates_withoutAnyParentUpdate() {
        addContactAndWait(contact("cE", addresses = listOf(contactAddr("ca-e", "cE"))))
        addCompanyAndWait(company("coE", addresses = listOf(companyAddr("co-ae", "coE"))))

        geocode("ca-e", 55.75, 37.61)
        geocode("co-ae", 59.93, 30.33)

        val b = backup()
        // Room — эталон; JSON обязан ему равняться (не наоборот).
        val roomContactAddr = roomAddress("ca-e")!!
        val roomCompanyAddr = roomAddress("co-ae")!!
        assertEquals("Room контакта: координаты записаны", 55.75, roomContactAddr.latitude)
        assertEquals("JSON контакта равен Room", roomContactAddr, b.contacts.single { it.id == "cE" }.addresses.single())
        assertEquals("JSON компании равен Room", roomCompanyAddr, b.companies.single { it.id == "coE" }.addresses.single())
        assertEquals(59.93, b.companies.single { it.id == "coE" }.addresses.single().latitude)
    }

    @Test
    fun f_reloadFromDb_retainsCoordinates_contactAndCompany() {
        addContactAndWait(contact("cF", addresses = listOf(contactAddr("ca-f", "cF"))))
        addCompanyAndWait(company("coF", addresses = listOf(companyAddr("co-af", "coF"))))
        val staleC = AppStateStore.getContact("cF")!!
        val staleCo = AppStateStore.getCompany("coF")!!
        geocode("ca-f"); geocode("co-af", 59.93, 30.33)

        updateContactAndWait(staleC, staleC.copy(firstName = "Правка"))
        updateCompanyAndWait(staleCo, staleCo.copy(name = "Правка"))
        awaitOffMainThread { AppStateStore.reloadFromDb() }

        assertEquals(LAT, roomAddress("ca-f")?.latitude)
        assertEquals(59.93, roomAddress("co-af")?.latitude)
        assertEquals(LAT, AppStateStore.getContact("cF")!!.addresses.single().latitude)
        assertEquals(59.93, AppStateStore.getCompany("coF")!!.addresses.single().latitude)
    }

    // ═════════════════════════════════════════════════════════════
    //  G. Явное обновление адреса продолжает работать (фикс не блокирует намеренную правку).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun g1_explicitAddressTextEdit_resetsCoordinates_likeEditorDoes() {
        addContactAndWait(contact("cG1", addresses = listOf(contactAddr("ca-g1", "cG1"))))
        geocode("ca-g1")
        val current = AppStateStore.getContact("cG1")!!

        // Редактор при смене улицы сбрасывает координаты (AddressComponents: addressChanged -> null).
        val edited = current.addresses.single().copy(addressLine = "Новая улица 7", latitude = null, longitude = null)
        updateContactAndWait(current, current.copy(addresses = listOf(edited)))

        val row = roomAddress("ca-g1")!!
        assertEquals("Новая улица 7", row.addressLine)
        assertNull("текст изменён -> прежние координаты не переносятся на новый адрес", row.latitude)
        assertNull(row.longitude)
    }

    @Test
    fun g2_explicitManualCoordinates_winOverCanonical() {
        addContactAndWait(contact("cG2", addresses = listOf(contactAddr("ca-g2", "cG2"))))
        geocode("ca-g2")
        val current = AppStateStore.getContact("cG2")!!

        updateContactAndWait(current, current.copy(addresses = listOf(current.addresses.single().copy(latitude = 10.5, longitude = 20.5))))

        assertEquals(10.5, roomAddress("ca-g2")!!.latitude)
        assertEquals(20.5, roomAddress("ca-g2")!!.longitude)
    }

    @Test
    fun g3_explicitContactAddressRemoval_stillDeletes() {
        addContactAndWait(contact("cG3", addresses = listOf(contactAddr("ca-g3", "cG3"), contactAddr("ca-g3b", "cG3").copy(addressLine = "Вторая 2"))))
        val current = AppStateStore.getContact("cG3")!!

        updateContactAndWait(current, current.copy(addresses = current.addresses.filter { it.id != "ca-g3" }))

        assertEquals(listOf("ca-g3b"), roomAddressesOf("cG3").map { it.id })
        assertEquals(listOf("ca-g3b"), AppStateStore.getContact("cG3")!!.addresses.map { it.id })
    }

    @Test
    fun g4_explicitCompanyAddressRemoval_isBaseMinusEdited() {
        addCompanyAndWait(company("coG4", addresses = listOf(companyAddr("co-g4a", "coG4"), companyAddr("co-g4b", "coG4", "Вторая 2"))))
        val current = AppStateStore.getCompany("coG4")!!

        // PERSISTENCE PACK #2: явное намерение удаления — «был в base, нет в edited»
        // (в Pack #1 передавалось набором removedAddressIds). «Не было в base» (адрес,
        // созданный независимо) удалением не считается — см. h_… ниже по файлу.
        updateCompanyAndWait(current, current.copy(addresses = current.addresses.filter { it.id != "co-g4a" }))

        assertEquals(listOf("co-g4b"), roomAddressesOf("coG4").map { it.id })
        assertEquals(listOf("co-g4b"), AppStateStore.getCompany("coG4")!!.addresses.map { it.id })
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 5. Merge берёт данные из вложенных проекций — они должны быть каноническими.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun i_mergeContacts_preservesGeocodedCoordinates() {
        val addr = contactAddr("ca-i", "cKeep")
        addContactAndWait(contact("cKeep", phones = phones("K", 3), addresses = listOf(addr)))
        addContactAndWait(contact("cOther"))
        geocode("ca-i")

        AppStateStore.mergeContacts(listOf("cKeep", "cOther")); settle()

        assertAddressEverywhere(addr.copy(latitude = LAT, longitude = LNG), "слияние контактов не стирает координаты")
    }

    @Test
    fun j_mergeCompanies_preservesGeocodedCoordinates() {
        val addr = companyAddr("co-j", "coKeep")
        addCompanyAndWait(company("coKeep", phones = phones("K", 3), addresses = listOf(addr)))
        addCompanyAndWait(company("coOther", addresses = listOf(companyAddr("co-j2", "coOther", "Другая 3"))))
        geocode("co-j")

        AppStateStore.mergeCompanies(listOf("coKeep", "coOther")); settle()

        assertEquals("Room: координаты выжившей компании на месте", LAT, roomAddress("co-j")?.latitude)
        assertEquals(addr.copy(latitude = LAT, longitude = LNG), roomAddress("co-j"))
        assertEquals(LAT, AppStateStore.getCompany("coKeep")!!.addresses.first { it.id == "co-j" }.latitude)
    }
}
