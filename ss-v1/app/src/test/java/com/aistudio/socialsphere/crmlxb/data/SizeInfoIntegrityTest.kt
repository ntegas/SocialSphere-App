package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import com.aistudio.socialsphere.crmlxb.utils.MergeResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P1 — `size_infos` data loss during Contact update (2026-09-21).
 *
 * SizeInfo — САМОСТОЯТЕЛЬНАЯ persisted сущность (таблица size_infos, независимый
 * писатель AppStateStore.setSizeInfo, каноничный глобальный список
 * AppStateStore.sizeInfos, который читают диалог размеров и вкладка «Подарки»).
 * Contact.sizeInfo — только вложенная проекция. Инвариант: обычный
 * updateContact НЕ удаляет и НЕ перезаписывает размеры; менять/удалять их
 * можно только явной операцией (setSizeInfo / удаление или слияние контакта).
 *
 * Ожидаемые значения берутся из ИСХОДНЫХ fixture-объектов (`SIZE_V1/SIZE_V2`),
 * а не из Contact-объектов, которые могут быть устаревшими. Каждый источник
 * читается независимо: Room (db.contactDao().getSizeInfos()), память
 * (AppStateStore.sizeInfos), вложенный (getContact().sizeInfo), после
 * reloadFromDb и сериализованный JSON-бэкап.
 */
@RunWith(RobolectricTestRunner::class)
class SizeInfoIntegrityTest {

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
     *  runBlocking на тестовом (main-looper) потоке — см. AppStateStoreRestoreTest. */
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
        emails: List<ContactEmail> = emptyList(),
        addresses: List<Address> = emptyList(),
        sizeInfo: SizeInfo? = null,
    ) = Contact(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = phones, emails = emails, addresses = addresses, sizeInfo = sizeInfo,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun addContactAndWait(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun updateContactAndWait(base: Contact, edited: Contact) { runBlocking { AppStateStore.updateContact(base, edited).await() } }
    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    /** Настоящий production setSizeInfo (fire-and-forget) + barrier «дождаться Room commit». */
    private fun setSizeAndWait(cid: String, s: SizeInfo) { AppStateStore.setSizeInfo(cid, s); settle() }

    // Исходные fixture — единственный источник expected-значений.
    private fun sizeV1(cid: String, id: String = "sz-$cid") = SizeInfo(id, cid, clothingSize = "M", shoeSize = "42", ringSize = "17", other = "рукав 60")
    private fun sizeV2(cid: String, id: String = "sz-$cid") = SizeInfo(id, cid, clothingSize = "L", shoeSize = "44", ringSize = "18", other = "рукав 62")

    // независимые «читатели» — каждый из СВОЕГО источника
    private fun roomRows(cid: String): List<SizeInfo> {
        settle()
        return runBlocking { db.contactDao().getSizeInfos().map { it.toDomain() }.filter { it.contactId == cid } }
    }
    private fun memoryRows(cid: String): List<SizeInfo> = AppStateStore.sizeInfos.filter { it.contactId == cid }
    private fun nested(cid: String): SizeInfo? = AppStateStore.getContact(cid)?.sizeInfo
    private fun backup(): BackupData = runBlocking {
        ExportManager.parseJsonBackup(ExportManager.backupJsonString()) ?: fail("backup JSON не парсится") as Nothing
    }
    private fun backupNested(cid: String): SizeInfo? = backup().contacts.first { it.id == cid }.sizeInfo

    /** Room-проверка ПЕРВОЙ: именно она падает на старом коде по целевой причине (потеря/откат строки). */
    private fun assertRoomExactly(cid: String, expected: SizeInfo, why: String) {
        val rows = roomRows(cid)
        assertEquals("Room: ровно одна строка size_infos у контакта — $why", 1, rows.size)
        assertEquals("Room: строка идентична исходной fixture — $why", expected, rows.single())
    }

    /** Все независимые источники, включая перечитанную из Room память и JSON. */
    private fun assertEverywhere(cid: String, expected: SizeInfo, why: String) {
        assertRoomExactly(cid, expected, why)
        assertEquals("память (AppStateStore.sizeInfos) — $why", listOf(expected), memoryRows(cid))
        assertEquals("вложенный Contact.sizeInfo — $why", expected, nested(cid))
        assertEquals("JSON вложенный Contact.sizeInfo — $why", expected, backupNested(cid))
        awaitOffMainThread { AppStateStore.reloadFromDb() } // рестарт: память заново из Room
        assertEquals("после reloadFromDb: память — $why", listOf(expected), memoryRows(cid))
        assertEquals("после reloadFromDb: вложенный — $why", expected, nested(cid))
        assertRoomExactly(cid, expected, "$why (после reloadFromDb)")
    }

    // ═════════════════════════════════════════════════════════════
    //  A. LOSS: canonical SizeInfo есть, вложенный снимок пуст, updateContact.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun a_lossRegression_staleContactWithNullSize_updateContact_keepsRoomRow() {
        addContactAndWait(contact("cA"))
        val stale = AppStateStore.getContact("cA")!!
        assertNull("предусловие: устаревший снимок без размеров", stale.sizeInfo)

        setSizeAndWait("cA", sizeV1("cA"))
        // Предусловие напрямую через DAO, не через Contact/память.
        assertRoomExactly("cA", sizeV1("cA"), "предусловие: размеры реально закоммичены")

        updateContactAndWait(stale, stale.copy(firstName = "Изменённое"))

        assertEquals("Изменённое", AppStateStore.getContact("cA")!!.firstName)
        assertEverywhere("cA", sizeV1("cA"), "обновление имени не удаляет размеры")
    }

    // ═════════════════════════════════════════════════════════════
    //  B. STALE OVERWRITE: Room=NEW, вложенный=OLD, update телефона -> Room остаётся NEW.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun b_staleOverwrite_nestedOldValues_updatePhone_roomKeepsNewValues() {
        addContactAndWait(contact("cB"))
        setSizeAndWait("cB", sizeV1("cB")) // shoes = 42
        val staleWithOld = AppStateStore.getContact("cB")!!
        assertEquals("предусловие: вложенный снимок держит СТАРЫЕ размеры", "42", staleWithOld.sizeInfo?.shoeSize)

        setSizeAndWait("cB", sizeV2("cB")) // shoes = 44 (тот же id), каноническое НОВОЕ значение
        assertRoomExactly("cB", sizeV2("cB"), "предусловие: в Room уже новые размеры")

        val phone = ContactPhone("ph-B", "cB", "+79005550001", PhoneType.MOBILE, true)
        updateContactAndWait(staleWithOld, staleWithOld.copy(phones = listOf(phone)))

        assertEquals("телефон сохранён", listOf("+79005550001"), AppStateStore.getContact("cB")!!.phones.map { it.number })
        assertEverywhere("cB", sizeV2("cB"), "устаревший вложенный shoes=42 не откатывает shoes=44")
    }

    // ═════════════════════════════════════════════════════════════
    //  C. Phone-style обновление: телефон + адрес + имя через устаревший снимок.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun c_phoneStyleUpdate_phoneAddressName_sizeUnchanged() {
        addContactAndWait(contact("cC"))
        val stale = AppStateStore.getContact("cC")!!
        setSizeAndWait("cC", sizeV1("cC"))

        val phone = ContactPhone("ph-C", "cC", "+79001112233", PhoneType.MOBILE, true)
        val addr = Address("ad-C", AddressOwnerType.CONTACT, "cC", AddressType.HOME, "Тверская 1", "Москва", "Россия")
        updateContactAndWait(stale, stale.copy(firstName = "Пётр", phones = listOf(phone), addresses = listOf(addr)))

        val saved = AppStateStore.getContact("cC")!!
        assertEquals("Пётр", saved.firstName)
        assertEquals(listOf("+79001112233"), saved.phones.map { it.number })
        assertEquals(listOf("Тверская 1"), saved.addresses.map { it.addressLine })
        assertEverywhere("cC", sizeV1("cC"), "телефон/адрес/имя не трогают размеры")
    }

    @Test
    fun c2_emailProfessionCompanyRelation_sizeUnchanged() {
        // Компания обязана существовать: updateContact не сохраняет связь с несуществующей
        // компанией (OWNERSHIP PACK #1 — reconcileCompanyRelations); production всегда
        // создаёт компанию (addCompany) раньше, чем сохраняет связь с ней.
        runBlocking {
            AppStateStore.addCompany(Company(
                id = "co-1", name = "Acme", industry = Industry.IT,
                createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
            )).await()
        }
        addContactAndWait(contact("cC2"))
        val stale = AppStateStore.getContact("cC2")!!
        setSizeAndWait("cC2", sizeV1("cC2"))

        val email = ContactEmail("em-C2", "cC2", "ivan@example.com", EmailType.PERSONAL, true)
        val rel = ContactCompanyRelation(
            id = "rel-C2", contactId = "cC2", companyId = "co-1", position = "Инженер", department = "R&D",
            role = null, employmentStatus = EmploymentStatus.CURRENT, startDate = null, endDate = null,
            responsibilities = null, managedAccounts = null, workNote = null, officeAddressId = null, isPrimary = true
        )
        updateContactAndWait(stale, stale.copy(emails = listOf(email), profession = "Электрик", companyRelations = listOf(rel)))

        val saved = AppStateStore.getContact("cC2")!!
        assertEquals("Электрик", saved.profession)
        assertEquals("Инженер", saved.companyRelations.single().position)
        assertEverywhere("cC2", sizeV1("cC2"), "email/profession/company/position не трогают размеры")
    }

    // ═════════════════════════════════════════════════════════════
    //  D. Явное обновление: setSizeInfo сохраняет НОВЫЕ размеры (фикс не блокирует легальную запись).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun d_explicitSetSizeInfo_savesNewValues_andSurvivesLaterStaleUpdate() {
        addContactAndWait(contact("cD"))
        val stale = AppStateStore.getContact("cD")!!

        // Первое сохранение: id генерируется (как в диалоге, когда existing == null).
        val first = sizeV1("cD", id = AppStateStore.generateId())
        setSizeAndWait("cD", first)
        assertEverywhere("cD", first, "первое явное сохранение размеров")

        // Повторное явное изменение — тот же id (как existing?.id в диалоге).
        val second = sizeV2("cD", id = first.id)
        setSizeAndWait("cD", second)
        assertEverywhere("cD", second, "явное изменение действительно меняет размеры")

        updateContactAndWait(stale, stale.copy(firstName = "После"))
        assertEverywhere("cD", second, "устаревший updateContact не откатывает явное изменение")
    }

    // ═════════════════════════════════════════════════════════════
    //  E. Явное удаление: ОТДЕЛЬНОГО API удаления SizeInfo в приложении НЕТ.
    //  Единственный «сброс» — диалог с пустыми полями -> setSizeInfo(все null):
    //  строка остаётся (с null), а не удаляется. Фиксируем это и то, что
    //  устаревший снимок не «воскрешает» старые значения поверх такого сброса.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun e_clearingAllFieldsViaSetSizeInfo_persistsBlankRow_staleUpdateDoesNotResurrectOldValues() {
        addContactAndWait(contact("cE"))
        setSizeAndWait("cE", sizeV1("cE"))
        val staleWithOld = AppStateStore.getContact("cE")!! // вложенный держит M/42/17

        val blank = SizeInfo("sz-cE", "cE", clothingSize = null, shoeSize = null, ringSize = null, other = null)
        setSizeAndWait("cE", blank)
        assertRoomExactly("cE", blank, "предусловие: явный сброс сохранён как пустая строка")

        updateContactAndWait(staleWithOld, staleWithOld.copy(firstName = "После сброса"))

        assertEverywhere("cE", blank, "старые значения из устаревшего снимка не воскресают поверх явного сброса")
    }

    // ═════════════════════════════════════════════════════════════
    //  F. Reload: старый дефект маскировался памятью до рестарта.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun f_reloadFromDb_afterUnrelatedUpdate_sizeRemains() {
        addContactAndWait(contact("cF"))
        val stale = AppStateStore.getContact("cF")!!
        setSizeAndWait("cF", sizeV1("cF"))

        updateContactAndWait(stale, stale.copy(firstName = "Правка"))
        awaitOffMainThread { AppStateStore.reloadFromDb() }

        // Сначала Room (источник истины после рестарта), затем то, что из него поднялось.
        assertRoomExactly("cF", sizeV1("cF"), "после «рестарта» строка на месте")
        assertEquals(listOf(sizeV1("cF")), memoryRows("cF"))
        assertEquals(sizeV1("cF"), nested("cF"))
    }

    // ═════════════════════════════════════════════════════════════
    //  G. JSON backup: размеры сериализуются вложенно в Contact.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun g_jsonBackup_afterStaleUpdate_serializesSizeInfo() {
        addContactAndWait(contact("cG"))
        val stale = AppStateStore.getContact("cG")!!
        setSizeAndWait("cG", sizeV1("cG"))
        updateContactAndWait(stale, stale.copy(firstName = "Правка"))

        val json = runBlocking { ExportManager.backupJsonString() }
        assertTrue("сериализованный JSON содержит значения размеров", json.contains("\"shoeSize\": \"42\"") && json.contains("рукав 60"))
        assertEquals("разобранный JSON: вложенный SizeInfo 1:1 с fixture", sizeV1("cG"), backupNested("cG"))
    }

    // ═════════════════════════════════════════════════════════════
    //  H. Restore в ПУСТУЮ БД: SizeInfo 1:1 привязан к правильному Contact.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun h_restoreIntoEmptyDb_sizeInfoLinkedToCorrectContact() {
        addContactAndWait(contact("cH1"))
        addContactAndWait(contact("cH2", firstName = "Мария"))
        val stale = AppStateStore.getContact("cH1")!!
        setSizeAndWait("cH1", sizeV1("cH1"))
        setSizeAndWait("cH2", sizeV2("cH2"))
        updateContactAndWait(stale, stale.copy(firstName = "Правка")) // устаревший снимок в цепочке

        val json = runBlocking { ExportManager.backupJsonString() }
        runBlocking { db.clearAllTables() }
        clearMemory()
        assertTrue(roomRows("cH1").isEmpty() && roomRows("cH2").isEmpty())

        val restored = awaitOffMainThread { ExportManager.importJsonBackup(json) }
        assertEquals(2, restored)

        assertEverywhere("cH1", sizeV1("cH1"), "после restore: размеры контакта 1")
        assertEverywhere("cH2", sizeV2("cH2"), "после restore: размеры контакта 2 у СВОЕГО контакта")
    }

    // ═════════════════════════════════════════════════════════════
    //  I. Повторный restore: без дублей и без стирания.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun i_repeatedRestore_noDuplicateRows_notErased() {
        addContactAndWait(contact("cI"))
        setSizeAndWait("cI", sizeV1("cI"))
        val json = runBlocking { ExportManager.backupJsonString() }

        awaitOffMainThread { ExportManager.importJsonBackup(json) }
        awaitOffMainThread { ExportManager.importJsonBackup(json) }

        assertEverywhere("cI", sizeV1("cI"), "после двух restore ровно одна строка, значения на месте")
    }

    // ═════════════════════════════════════════════════════════════
    //  J. Несколько контактов: обновление A не меняет B.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun j_multipleContacts_updateOneDoesNotTouchOther() {
        addContactAndWait(contact("cJA"))
        addContactAndWait(contact("cJB", firstName = "Мария"))
        val staleA = AppStateStore.getContact("cJA")!!
        setSizeAndWait("cJA", sizeV1("cJA"))
        setSizeAndWait("cJB", sizeV2("cJB"))

        updateContactAndWait(staleA, staleA.copy(firstName = "Только A"))

        assertEverywhere("cJA", sizeV1("cJA"), "размеры обновляемого контакта на месте")
        assertEverywhere("cJB", sizeV2("cJB"), "размеры второго контакта не тронуты")
    }

    // ═════════════════════════════════════════════════════════════
    //  K. MERGE — фактическая текущая семантика (product decision уже есть,
    //  фидбэк владельца 2026-07-13: «ничего не пропадает»). Не меняется.
    //   • каждое подполе комбинируется отдельно (combineChosenText): равные
    //     (без учёта регистра) -> одно значение; расходятся -> через " / " в
    //     порядке переданных id; пустые подполя игнорируются;
    //   • resolution.textIncluded["sizeXxx"] сужает набор контактов-источников;
    //   • результат — НОВАЯ строка (новый id) у выжившего контакта; строки
    //     сливаемых удалены; вход — вложенный Contact.sizeInfo группы.
    // ═════════════════════════════════════════════════════════════

    private fun mergeFixture(): Pair<String, String> {
        // keep — больше данных (score): 3 телефона; other — только размеры.
        val keepPhones = (1..3).map { ContactPhone("kp$it", "cKeep", "+7900000000$it", PhoneType.MOBILE, it == 1) }
        addContactAndWait(contact("cKeep", phones = keepPhones))
        addContactAndWait(contact("cOther", firstName = "Иван"))
        setSizeAndWait("cKeep", SizeInfo("sz-keep-old", "cKeep", clothingSize = "M", shoeSize = "42", ringSize = null, other = "Рукав"))
        setSizeAndWait("cOther", SizeInfo("sz-other-old", "cOther", clothingSize = "L", shoeSize = null, ringSize = "17", other = "рукав"))
        return "cKeep" to "cOther"
    }

    @Test
    fun k1_mergeSemantics_perSubfieldCombination_newRowForKeep_othersRowRemoved() {
        val (keepId, otherId) = mergeFixture()

        val snapshot = AppStateStore.mergeContacts(listOf(keepId, otherId))
        assertNotNull("слияние выполнено", snapshot)
        settle()

        val rows = roomRows(keepId)
        assertEquals("Room: у выжившего ровно одна строка", 1, rows.size)
        val merged = rows.single()
        assertEquals("расходящееся подполе — оба значения через \" / \" в порядке id", "M / L", merged.clothingSize)
        assertEquals("значение только у одного контакта переносится как есть", "42", merged.shoeSize)
        assertEquals("значение только у другого контакта переносится как есть", "17", merged.ringSize)
        assertEquals("равные без учёта регистра -> первое значение", "Рукав", merged.other)
        assertNotEquals("новая строка (новый id), а не одна из прежних", "sz-keep-old", merged.id)
        assertTrue("строка сливаемого контакта удалена из Room", roomRows(otherId).isEmpty())
        assertEquals("память согласована с Room", listOf(merged), memoryRows(keepId))
        assertTrue(memoryRows(otherId).isEmpty())
        assertEquals("вложенная проекция выжившего = каноническая строка", merged, nested(keepId))
    }

    @Test
    fun k1b_mergeSemantics_explicitResolutionNarrowsSources() {
        val (keepId, otherId) = mergeFixture()

        AppStateStore.mergeContacts(
            listOf(keepId, otherId),
            MergeResolution(textIncluded = mapOf("sizeClothing" to setOf(keepId)))
        )
        settle()

        assertEquals("explicit resolution: размер одежды только от keep", "M", roomRows(keepId).single().clothingSize)
    }

    /** Merge читает вложенные проекции группы: устаревший updateContact не должен
     *  их портить, иначе слияние теряло бы размеры (на старом коде — теряло). */
    @Test
    fun k2_staleUpdateThenMerge_sizesOfBothContactsSurviveInMergedRow() {
        val (keepId, otherId) = mergeFixture()
        // устаревшие снимки ДО сохранения размеров нельзя взять из mergeFixture — берём «пустые» копии
        val staleKeep = AppStateStore.getContact(keepId)!!.copy(sizeInfo = null)
        val staleOther = AppStateStore.getContact(otherId)!!.copy(sizeInfo = null)
        updateContactAndWait(staleKeep, staleKeep.copy(firstName = "Правка keep"))
        updateContactAndWait(staleOther, staleOther.copy(firstName = "Правка other"))

        AppStateStore.mergeContacts(listOf(keepId, otherId))
        settle()

        val merged = roomRows(keepId).single()
        assertEquals("M / L", merged.clothingSize)
        assertEquals("42", merged.shoeSize)
        assertEquals("17", merged.ringSize)
    }

    // ═════════════════════════════════════════════════════════════
    //  L. Явное удаление родительского контакта — легальный путь удаления SizeInfo.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun l_deleteParentContact_removesItsSize_otherUntouched() {
        addContactAndWait(contact("cL1"))
        addContactAndWait(contact("cL2", firstName = "Мария"))
        setSizeAndWait("cL1", sizeV1("cL1"))
        setSizeAndWait("cL2", sizeV2("cL2"))

        runBlocking { AppStateStore.deleteContact("cL1").await() }

        assertTrue(roomRows("cL1").isEmpty())
        assertTrue(memoryRows("cL1").isEmpty())
        assertRoomExactly("cL2", sizeV2("cL2"), "размеры другого контакта не тронуты")
    }

    // ═════════════════════════════════════════════════════════════
    //  N. Владение зафиксировано: вложенный Contact.sizeInfo в updateContact —
    //  НЕ источник записи (ни один production-вызов не меняет его через
    //  updateContact; единственное обращение — сквозной originalContact?.sizeInfo
    //  в ContactEditScreen). Записать размеры можно только через setSizeInfo.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun n_nestedSizeInfoInUpdateContact_isNotAWriteSource() {
        addContactAndWait(contact("cN"))
        val current = AppStateStore.getContact("cN")!!

        updateContactAndWait(current, current.copy(sizeInfo = sizeV1("cN")))

        assertTrue("Room: updateContact не создаёт строку размеров из вложенного поля", roomRows("cN").isEmpty())
        assertTrue("память: глобальный список не пополнился", memoryRows("cN").isEmpty())
        assertNull("вложенная проекция = каноническое отсутствие", nested("cN"))
    }
}
