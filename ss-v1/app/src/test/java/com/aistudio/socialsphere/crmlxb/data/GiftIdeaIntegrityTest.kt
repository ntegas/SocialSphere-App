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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P0.1 — GiftIdea data-loss regression (2026-09-21).
 *
 * GiftIdea — САМОСТОЯТЕЛЬНЫЙ агрегат (таблица gift_ideas, CRUD addGift/updateGift/
 * deleteGift). Contact.gifts — лишь производная проекция, которая может быть
 * устаревшей. Инвариант: обновление Contact НЕ является операцией удаления
 * (и создания/воскрешения) GiftIdea.
 *
 * Каждый тест проверяет состояние НЕЗАВИСИМО из четырёх мест, читая каждое из
 * собственного источника, а не один и тот же потенциально ошибочный список как
 * actual и expected одновременно:
 *   Room        — db.giftDao().getAllGifts() (после awaitPendingWrites);
 *   память      — AppStateStore.gifts (то, что читает GiftsTab и buildBackup);
 *   вложенное   — AppStateStore.getContact(id).gifts;
 *   JSON        — ExportManager.backupJsonString() -> parseJsonBackup (и плоский
 *                 top-level gifts, и вложенный Contact.gifts).
 *
 * Тот же паттерн обвязки, что AppStateStoreRestoreTest: in-memory Room +
 * AppStateStore singleton; всё, что внутри переходит на Dispatchers.Main
 * (reloadFromDb/restoreBackup), запускается через awaitOffMainThread.
 */
@RunWith(RobolectricTestRunner::class)
class GiftIdeaIntegrityTest {

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
        AppStateStore.contacts.clear()
        AppStateStore.companies.clear()
        AppStateStore.notes.clear()
        AppStateStore.gifts.clear()
        AppStateStore.addresses.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.companyRelations.clear()
        AppStateStore.contactRelations.clear()
        AppStateStore.groups.clear()
        AppStateStore.groupMembers.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
        AppStateStore.personalDetails.clear()
        AppStateStore.sizeInfos.clear()
    }

    private fun pollUntilTrue(timeoutMs: Long = 10_000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
            ok = check()
        }
        ok
    }

    /** Suspend-блок с внутренним withContext(Dispatchers.Main) нельзя гонять
     *  runBlocking на тестовом (main-looper) потоке — см. шапку AppStateStoreRestoreTest. */
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
        id: String,
        firstName: String = "Иван",
        phones: List<ContactPhone> = emptyList(),
        addresses: List<Address> = emptyList(),
        gifts: List<GiftIdea> = emptyList(),
    ) = Contact(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = phones, addresses = addresses, gifts = gifts,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun gift(id: String, contactId: String, title: String = "Книга $id") = GiftIdea(
        id = id, contactId = contactId, title = title, note = "Заметка $id",
        link = "https://example.com/$id", date = "2026-12-25", status = GiftStatus.IDEA
    )

    private fun addContactAndWait(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun updateContactAndWait(base: Contact, edited: Contact) { runBlocking { AppStateStore.updateContact(base, edited).await() } }
    private fun updateFreshAndWait(id: String, edit: (Contact) -> Contact) { val cur = AppStateStore.getContact(id)!!; updateContactAndWait(cur, edit(cur)) }
    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    /** addGift — fire-and-forget; «дождаться фактического Room commit» = barrier. */
    private fun addGiftAndWait(g: GiftIdea) { AppStateStore.addGift(g); settle() }
    private fun deleteGiftAndWait(id: String) { AppStateStore.deleteGift(id); settle() }

    // независимые «читатели» — каждый из СВОЕГО источника
    private fun roomGifts(): List<GiftIdea> {
        settle()
        return runBlocking { db.giftDao().getAllGifts().map { it.toDomain() } }
    }
    private fun memoryGifts(): List<GiftIdea> = AppStateStore.gifts.toList()
    private fun nestedGifts(contactId: String): List<GiftIdea> = AppStateStore.getContact(contactId)?.gifts.orEmpty()
    private fun backup(): BackupData = runBlocking {
        ExportManager.parseJsonBackup(ExportManager.backupJsonString()) ?: fail("backup JSON не парсится") as Nothing
    }
    private fun backupFlatIds(): Set<String> = backup().gifts.map { it.id }.toSet()
    private fun backupNestedIds(contactId: String): Set<String> =
        backup().contacts.first { it.id == contactId }.gifts.map { it.id }.toSet()

    /** Все четыре источника обязаны содержать РОВНО этот набор id подарков контакта. */
    private fun assertGiftIds(contactId: String, expected: Set<String>, why: String) {
        assertEquals("Room: $why", expected, roomGifts().filter { it.contactId == contactId }.map { it.id }.toSet())
        assertEquals("память (AppStateStore.gifts): $why", expected, memoryGifts().filter { it.contactId == contactId }.map { it.id }.toSet())
        assertEquals("вложенный Contact.gifts: $why", expected, nestedGifts(contactId).map { it.id }.toSet())
        assertEquals("JSON плоский gifts: $why", expected, backup().gifts.filter { it.contactId == contactId }.map { it.id }.toSet())
        assertEquals("JSON вложенный Contact.gifts: $why", expected, backupNestedIds(contactId))
    }

    // ═════════════════════════════════════════════════════════════
    //  A. Добавить GiftIdea -> изменить имя контакта (СВЕЖИЙ снимок) -> сохраняется.
    //  Базовая линия: проходит и на старом коде (вложенный список не устарел).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun a_addGift_thenRenameContactWithFreshSnapshot_giftSurvives() {
        addContactAndWait(contact("cA"))
        addGiftAndWait(gift("g1", "cA"))

        val fresh = AppStateStore.getContact("cA")!!
        updateContactAndWait(fresh, fresh.copy(firstName = "Пётр"))

        assertEquals("Пётр", AppStateStore.getContact("cA")!!.firstName)
        assertGiftIds("cA", setOf("g1"), "переименование контакта не удаляет подарок")
    }

    // ═════════════════════════════════════════════════════════════
    //  B. ПОДТВЕРЖДЁННЫЙ ДЕФЕКТ: обновление Contact с УСТАРЕВШИМ gifts=[].
    //  Сценарий из задачи: Contact A, GiftIdea G через production addGift, ждём
    //  Room commit, устаревший Contact A с тем же id и gifts=emptyList(), меняем
    //  ТОЛЬКО firstName, передаём в production updateContact().
    // ═════════════════════════════════════════════════════════════

    @Test
    fun b_staleContactWithEmptyGifts_updateContact_doesNotDeleteGift() {
        addContactAndWait(contact("cB"))
        // Снимок устаревшего Contact — ДО добавления подарка (gifts = []).
        val stale = AppStateStore.getContact("cB")!!
        assertTrue("предусловие: устаревший снимок без подарков", stale.gifts.isEmpty())

        addGiftAndWait(gift("gB", "cB", title = "Подарок B"))
        assertTrue("предусловие: подарок реально закоммичен в Room",
            roomGifts().any { it.id == "gB" && it.contactId == "cB" })

        updateContactAndWait(stale, stale.copy(firstName = "Изменённое"))

        assertEquals("Изменённое", AppStateStore.getContact("cB")!!.firstName)
        // Room — обновление Contact вообще не должно его трогать.
        assertTrue("Room: giftDao всё ещё содержит G",
            roomGifts().any { it.id == "gB" && it.contactId == "cB" && it.title == "Подарок B" })
        assertGiftIds("cB", setOf("gB"), "устаревший Contact.gifts=[] не удаляет самостоятельный GiftIdea")
    }

    // ═════════════════════════════════════════════════════════════
    //  C. Phone-style обновление: устаревший снимок + изменение телефона и адреса.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun c_phoneStyleUpdate_staleSnapshot_phoneAndAddressSaved_giftSurvives() {
        addContactAndWait(contact("cC"))
        val stale = AppStateStore.getContact("cC")!!
        addGiftAndWait(gift("gC", "cC"))

        val phone = ContactPhone("ph1", "cC", "+79001112233", PhoneType.MOBILE, true)
        val addr = Address("ad1", AddressOwnerType.CONTACT, "cC", AddressType.HOME, "Тверская 1", "Москва", "Россия")
        updateContactAndWait(stale, stale.copy(phones = listOf(phone), addresses = listOf(addr)))

        val saved = AppStateStore.getContact("cC")!!
        assertEquals("телефон обновлён", listOf("+79001112233"), saved.phones.map { it.number })
        assertEquals("адрес обновлён", listOf("Тверская 1"), saved.addresses.map { it.addressLine })
        assertGiftIds("cC", setOf("gC"), "телефон/адрес не удаляют подарок")
    }

    // ═════════════════════════════════════════════════════════════
    //  D. deleteGift() — явная бизнес-операция: подарок удалён из Room, памяти,
    //  вложенного списка и следующего бэкапа; второй подарок остался.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun d_deleteGift_removesFromRoomMemoryNestedAndNextBackup_otherGiftKept() {
        addContactAndWait(contact("cD"))
        addGiftAndWait(gift("d1", "cD"))
        addGiftAndWait(gift("d2", "cD"))
        assertGiftIds("cD", setOf("d1", "d2"), "предусловие: оба подарка на месте")

        deleteGiftAndWait("d1")

        assertGiftIds("cD", setOf("d2"), "удалён только d1 — во ВСЕХ источниках")
    }

    /** Воскрешение: намеренно удалённый подарок не должен вернуться из устаревшего
     *  снимка Contact (снимок, снятый ДО deleteGift, ещё держит его во вложенном списке). */
    @Test
    fun d2_deletedGift_isNotResurrectedByStaleContactUpdate() {
        addContactAndWait(contact("cD2"))
        addGiftAndWait(gift("keep", "cD2"))
        addGiftAndWait(gift("gone", "cD2"))
        val staleWithGone = AppStateStore.getContact("cD2")!! // вложенный: keep + gone
        assertEquals(setOf("keep", "gone"), staleWithGone.gifts.map { it.id }.toSet())

        deleteGiftAndWait("gone")
        updateContactAndWait(staleWithGone, staleWithGone.copy(firstName = "После удаления"))

        assertGiftIds("cD2", setOf("keep"), "удалённый подарок не воскресает из устаревшего Contact.gifts")
    }

    // ═════════════════════════════════════════════════════════════
    //  E. Restart / reloadFromDb — подарок остаётся связанным с правильным Contact ID.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun e_reloadFromDb_giftStaysLinkedToCorrectContact() {
        addContactAndWait(contact("cE1"))
        addContactAndWait(contact("cE2"))
        addGiftAndWait(gift("e1", "cE1"))
        addGiftAndWait(gift("e2", "cE2"))
        val stale = AppStateStore.getContact("cE1")!!.copy(gifts = emptyList())
        updateContactAndWait(stale, stale.copy(firstName = "После правки"))

        awaitOffMainThread { AppStateStore.reloadFromDb() } // «перезапуск»: память заново из Room

        assertGiftIds("cE1", setOf("e1"), "после reloadFromDb подарок у своего контакта")
        assertGiftIds("cE2", setOf("e2"), "после reloadFromDb подарок второго контакта на месте")
        assertEquals("cE1", nestedGifts("cE1").single().contactId)
    }

    // ═════════════════════════════════════════════════════════════
    //  F. Полный JSON export -> restore в ПУСТУЮ БД: все поля GiftIdea и связь.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun f_fullJsonExport_restoreIntoEmptyDb_allFieldsAndLinkPreserved() {
        addContactAndWait(contact("cF"))
        val stale = AppStateStore.getContact("cF")!!
        val original = GiftIdea("gF", "cF", "Часы", "Швейцарские", "https://shop.example/w", "2026-11-30", GiftStatus.BOUGHT)
        addGiftAndWait(original)
        updateContactAndWait(stale, stale.copy(firstName = "Правка")) // устаревший снимок в цепочке

        val json = runBlocking { ExportManager.backupJsonString() }
        assertTrue("предусловие: подарок попал в экспортируемый JSON", json.contains("Часы"))

        // Пустая БД + пустая память.
        runBlocking { db.clearAllTables() }
        clearMemory()
        assertTrue(roomGifts().isEmpty())

        val restored = awaitOffMainThread { ExportManager.importJsonBackup(json) }
        assertEquals("восстановлен один контакт", 1, restored)

        val inRoom = roomGifts().single()
        assertEquals("gF", inRoom.id)
        assertEquals("cF", inRoom.contactId)
        assertEquals("Часы", inRoom.title)
        assertEquals("Швейцарские", inRoom.note)
        assertEquals("https://shop.example/w", inRoom.link)
        assertEquals("2026-11-30", inRoom.date)
        assertEquals(GiftStatus.BOUGHT, inRoom.status)
        assertEquals(original, inRoom)
        assertGiftIds("cF", setOf("gF"), "после restore в пустую БД")
    }

    // ═════════════════════════════════════════════════════════════
    //  G. Повторный restore не создаёт дубликатов GiftIdea.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun g_repeatedRestore_doesNotDuplicateGifts() {
        addContactAndWait(contact("cG"))
        addGiftAndWait(gift("g1", "cG"))
        addGiftAndWait(gift("g2", "cG"))
        val json = runBlocking { ExportManager.backupJsonString() }

        awaitOffMainThread { ExportManager.importJsonBackup(json) }
        awaitOffMainThread { ExportManager.importJsonBackup(json) }

        val inRoom = roomGifts()
        assertEquals("в Room ровно два подарка, без дублей", 2, inRoom.size)
        assertEquals(setOf("g1", "g2"), inRoom.map { it.id }.toSet())
        assertEquals("в памяти ровно два подарка", 2, memoryGifts().size)
        assertEquals("во вложенном списке ровно два", 2, nestedGifts("cG").size)
        assertEquals("в JSON плоском ровно два", 2, backup().gifts.size)
    }

    // ═════════════════════════════════════════════════════════════
    //  H. Несколько Gift Ideas у одного контакта — изменение не теряет ни одной.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun h_multipleGiftsOneContact_staleUpdateLosesNone() {
        addContactAndWait(contact("cH"))
        val stale = AppStateStore.getContact("cH")!!
        addGiftAndWait(gift("h1", "cH"))
        addGiftAndWait(gift("h2", "cH"))
        addGiftAndWait(gift("h3", "cH"))

        updateContactAndWait(stale, stale.copy(firstName = "Много подарков"))
        updateFreshAndWait("cH") { it.copy(lastName = "Иванов") } // повторное сохранение

        assertGiftIds("cH", setOf("h1", "h2", "h3"), "ни один из трёх подарков не потерян")
    }

    // ═════════════════════════════════════════════════════════════
    //  I. Gift Ideas двух контактов — обновление одного не меняет данные другого.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun i_twoContacts_updateOneDoesNotTouchOthersGifts() {
        addContactAndWait(contact("cI1"))
        addContactAndWait(contact("cI2"))
        val staleI1 = AppStateStore.getContact("cI1")!!
        addGiftAndWait(gift("i1a", "cI1"))
        addGiftAndWait(gift("i2a", "cI2", title = "Не менять"))
        addGiftAndWait(gift("i2b", "cI2"))
        val beforeI2 = memoryGifts().filter { it.contactId == "cI2" }.sortedBy { it.id }

        updateContactAndWait(staleI1, staleI1.copy(firstName = "Только первый"))

        assertGiftIds("cI1", setOf("i1a"), "подарок обновляемого контакта на месте")
        assertGiftIds("cI2", setOf("i2a", "i2b"), "подарки второго контакта не тронуты")
        assertEquals("поля подарков второго контакта не изменились", beforeI2,
            memoryGifts().filter { it.contactId == "cI2" }.sortedBy { it.id })
    }

    // ═════════════════════════════════════════════════════════════
    //  J. Реалистичный триггер устаревшего снимка: слияние дублей. keep.gifts
    //  (вложенный) не получает перенесённые подарки, хотя глобальный список их
    //  переназначил. Обычное редактирование keep после слияния не должно их терять.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun j_mergeThenEditKeptContact_reassignedGiftSurvives() {
        // keep — больше данных (score), other — с подарком.
        val keepPhones = (1..3).map { ContactPhone("kp$it", "cKeep", "+7900000000$it", PhoneType.MOBILE, it == 1) }
        addContactAndWait(contact("cKeep", phones = keepPhones))
        addContactAndWait(contact("cOther"))
        addGiftAndWait(gift("gm", "cOther", title = "Подарок дубля"))

        val snapshot = AppStateStore.mergeContacts(listOf("cKeep", "cOther"))
        assertTrue("слияние выполнено", snapshot != null)
        settle()
        assertEquals("предусловие: подарок переназначен на keep в глобальном списке и Room",
            "cKeep", roomGifts().single { it.id == "gm" }.contactId)

        updateFreshAndWait("cKeep") { it.copy(firstName = "После слияния") }

        assertGiftIds("cKeep", setOf("gm"), "перенесённый слиянием подарок не теряется при обычной правке")
    }

    // ═════════════════════════════════════════════════════════════
    //  K. Судьба записи в Room: устаревшее обновление + последующее слияние.
    //  updateContact сам не пишет в gift_ideas, но рассинхрон памяти отравляет
    //  mergeContacts: giftsToReassign строится из ПАМЯТИ, а транзакция потом
    //  вызывает deleteGiftsForContact(oid) — подарок терялся уже из Room.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun k_staleUpdateThenMerge_giftNotDestroyedInRoom() {
        // cLow — владелец подарка, но с меньшим score; cHigh (3 телефона) станет keep.
        addContactAndWait(contact("cLow"))
        val staleLow = AppStateStore.getContact("cLow")!!
        addGiftAndWait(gift("gk", "cLow", title = "Подарок под угрозой"))
        val highPhones = (1..3).map { ContactPhone("hp$it", "cHigh", "+7911000000$it", PhoneType.MOBILE, it == 1) }
        addContactAndWait(contact("cHigh", phones = highPhones))

        updateContactAndWait(staleLow, staleLow.copy(firstName = "Устаревшая правка"))
        AppStateStore.mergeContacts(listOf("cLow", "cHigh"))
        settle()

        val inRoom = roomGifts().filter { it.id == "gk" }
        assertEquals("Room: подарок пережил слияние (не удалён deleteGiftsForContact)", 1, inRoom.size)
        assertEquals("и переназначен на выжившего контакта", "cHigh", inRoom.single().contactId)
        assertGiftIds("cHigh", setOf("gk"), "после слияния подарок у keep во всех источниках")
    }

    // ═════════════════════════════════════════════════════════════
    //  Контрольные: удаление родительского контакта — явная операция и удаляет подарки.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun z_deleteParentContact_removesItsGifts_othersUntouched() {
        addContactAndWait(contact("cZ1"))
        addContactAndWait(contact("cZ2"))
        addGiftAndWait(gift("z1", "cZ1"))
        addGiftAndWait(gift("z2", "cZ2"))

        runBlocking { AppStateStore.deleteContact("cZ1").await() }

        assertEquals(setOf("z2"), roomGifts().map { it.id }.toSet())
        assertEquals(setOf("z2"), memoryGifts().map { it.id }.toSet())
        assertFalse(backupFlatIds().contains("z1"))
    }
}
