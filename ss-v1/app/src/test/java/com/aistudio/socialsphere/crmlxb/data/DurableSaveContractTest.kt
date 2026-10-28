package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
 * DATA PERSISTENCE PACK #2 — FRONT 6–13 и 15 (2026-09-21): контракт «сохранено».
 *
 * Инвариант: операция, подтверждённая как сохранённая (Deferred записи завершился успешно),
 * переживает перечитывание из Room / перезапуск; до подтверждения Room вправе содержать
 * старое значение, но экран не имеет права считать данные сохранёнными.
 *
 * «Process death» моделируется управляемым писателем, а не убийством JVM:
 *  • [AppStateStore.writeGateForTests] — writer-команда не начинает выполняться, пока гейт не
 *    открыт (Room ещё НЕ закоммитил); проверки Room в этот момент — прямыми DAO-чтениями, НЕ
 *    через settle()/awaitPendingWrites() (барьер сам стоит в той же очереди за гейтом);
 *  • [AppStateStore.writeFaultForTests] — writer-команда падает (сбой БД/диска).
 * Ни одного delay() как доказательства синхронизации — только возвращаемые Deferred.
 */
@RunWith(RobolectricTestRunner::class)
class DurableSaveContractTest {

    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppStateStore.resetForTests()
        AppStateStore.writeGateForTests = null
        AppStateStore.writeFaultForTests = null
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(timeoutMs = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут в setup()")
    }

    @After
    fun tearDown() {
        // Гейт/сбой обязаны быть сняты ДО очистки — иначе хвост очереди повиснет.
        AppStateStore.writeFaultForTests = null
        AppStateStore.writeGateForTests?.complete(Unit)
        AppStateStore.writeGateForTests = null
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

    private fun contact(id: String, firstName: String = "Иван", phones: List<ContactPhone> = emptyList()) = Contact(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, phones = phones,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )
    private fun phone(cid: String, id: String, number: String, primary: Boolean = false) =
        ContactPhone(id, cid, number, PhoneType.MOBILE, primary)
    private fun note(id: String, cid: String, text: String) = Note(id, cid, null, null, null, NoteType.GENERAL, text, null, false,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
    private fun gift(id: String, cid: String, title: String = "Книга") = GiftIdea(id, cid, title, null, null, null, GiftStatus.IDEA)

    private fun fresh(id: String): Contact = AppStateStore.getContact(id)!!
    private fun addContactAndWait(c: Contact) { runBlocking { AppStateStore.addContact(c).await() } }
    private fun settle() { runBlocking { AppStateStore.awaitPendingWrites() } }

    /** Прямые чтения Room БЕЗ барьера очереди — годятся и при закрытом гейте. */
    private fun rawRoomFirstName(id: String): String = runBlocking { db.contactDao().getAllContacts().first { it.id == id }.firstName }
    private fun rawRoomPhones(owner: String): Set<String> = runBlocking { db.contactDao().getContactPhones().filter { it.contactId == owner }.map { it.number }.toSet() }

    private fun closeGate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { AppStateStore.writeGateForTests = it }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 10 — J / K / L: порядок «Save -> коммит -> подтверждение».
    // ═════════════════════════════════════════════════════════════

    /** J: операция НЕ завершена, пока writer не закоммитил; завершается после. */
    @Test
    fun J_saveDoesNotResolveBeforeWriterCommits() {
        addContactAndWait(contact("cJ"))
        val s = fresh("cJ")
        val gate = closeGate()

        val save = AppStateStore.updateContact(s, s.copy(firstName = "Новое"))

        assertFalse("Save не может завершиться, пока Room не закоммитил (писатель заблокирован)", save.isCompleted)
        assertEquals("Room ещё содержит СТАРОЕ значение", "Иван", rawRoomFirstName("cJ"))

        gate.complete(Unit)
        runBlocking { save.await() }
        assertTrue(save.isCompleted)
        assertEquals("после подтверждения Room содержит НОВОЕ значение", "Новое", rawRoomFirstName("cJ"))
    }

    /** K: до коммита память может быть оптимистичной, а Room — старым, пока Save не подтверждён. */
    @Test
    fun K_beforeCommit_memoryOptimistic_roomOld_saveUnacknowledged() {
        addContactAndWait(contact("cK"))
        val s = fresh("cK")
        val gate = closeGate()

        val save = AppStateStore.updateContact(s, s.copy(firstName = "Новое"))

        assertEquals("оптимистичная память", "Новое", fresh("cK").firstName)
        assertEquals("Room — старое", "Иван", rawRoomFirstName("cK"))
        assertFalse("Save не подтверждён — экран не вправе считать данные сохранёнными", save.isCompleted)
        gate.complete(Unit); runBlocking { save.await() }
    }

    /** L: после подтверждённого Save и перечитывания из Room (= рестарт процесса) — НОВОЕ значение. */
    @Test
    fun L_afterAcknowledgedSave_newValueSurvivesReloadFromRoom() {
        addContactAndWait(contact("cL", phones = listOf(phone("cL", "pL1", "+7900000001", primary = true))))
        val s = fresh("cL")
        val gate = closeGate()
        val save = AppStateStore.updateContact(s, s.copy(
            firstName = "Новое", phones = s.phones + phone("cL", "pL2", "+7900000002")))
        // Инвариант «подтверждено ⇒ в Room»: состояние Room фиксируется В МОМЕНТ подтверждения
        // (не после — иначе гонка с писателем маскирует преждевременное подтверждение).
        // Обработчик invokeOnCompletion идёт ПАРАЛЛЕЛЬНО с возобновлением await() — результат
        // обязательно ждём через CompletableDeferred, а не читаем переменную сразу после await().
        val roomAtAcknowledgement = CompletableDeferred<Pair<String, Set<String>>>()
        save.invokeOnCompletion { roomAtAcknowledgement.complete(rawRoomFirstName("cL") to rawRoomPhones("cL")) }
        gate.complete(Unit)
        runBlocking { save.await() }                      // подтверждено
        assertEquals("в момент подтверждения Room УЖЕ содержит НОВОЕ значение",
            "Новое" to setOf("+7900000001", "+7900000002"), runBlocking { roomAtAcknowledgement.await() })

        // «Процесс умер» — память потеряна; при следующем запуске состояние читается из Room.
        clearMemory()
        awaitOffMainThread { AppStateStore.reloadFromDb() }

        assertEquals("Новое", fresh("cL").firstName)
        assertEquals(setOf("pL1", "pL2"), fresh("cL").phones.map { it.id }.toSet())
        assertEquals(setOf("+7900000001", "+7900000002"), rawRoomPhones("cL"))
    }

    /** Отмена ОЖИДАНИЯ (экран закрыт/Activity пересоздан) не отменяет саму запись. */
    @Test
    fun uiAwaitCancellation_doesNotCancelTheDurableWrite() {
        addContactAndWait(contact("cC"))
        val s = fresh("cC")
        val gate = closeGate()
        val save = AppStateStore.updateContact(s, s.copy(firstName = "Новое"))

        val waiter = CoroutineScope(Dispatchers.Default).launch { AppStateStore.awaitDurableCommit(save) }
        waiter.cancel()
        runBlocking { waiter.join() }

        assertFalse("запись НЕ отменена вместе с ожидающим", save.isCancelled)
        gate.complete(Unit)
        runBlocking { save.await() }
        assertEquals("запись дошла до Room", "Новое", rawRoomFirstName("cC"))
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 9 — сбой БД: «сохранено» не объявляется, память не остаётся единственной правдой.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun failedCommit_isVisibleToCaller_memoryResyncedFromRoom_retrySucceeds() {
        addContactAndWait(contact("cE"))
        val s = fresh("cE")
        val failuresBefore = AppStateStore.writeFailureCount
        AppStateStore.writeFaultForTests = java.io.IOException("disk full")

        val save = AppStateStore.updateContact(s, s.copy(firstName = "Новое"))
        val acknowledged = runBlocking { AppStateStore.awaitDurableCommit(save) }

        assertFalse("сбой виден вызывающему: «сохранено» НЕ подтверждено", acknowledged)
        assertTrue("Deferred завершён ошибкой", save.isCompleted && save.isCancelled)

        // память пересобирается из Room, а не остаётся оптимистичной «правдой»; счётчик растёт
        val resynced = pollUntilTrue {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            AppStateStore.writeFailureCount > failuresBefore
        }
        assertTrue("после сбоя выполнена пересборка и выставлен признак для корневого экрана", resynced)
        assertEquals("память = то, что реально в Room", "Иван", fresh("cE").firstName)
        assertEquals("Room не изменился", "Иван", rawRoomFirstName("cE"))

        // повторное сохранение (экран остался открытым с теми же данными) проходит
        AppStateStore.writeFaultForTests = null
        val retry = AppStateStore.updateContact(s, s.copy(firstName = "Новое"))
        assertTrue("повтор подтверждён", runBlocking { AppStateStore.awaitDurableCommit(retry) })
        assertEquals("Новое", rawRoomFirstName("cE"))
    }

    /** Сбой fire-and-forget-писателя (никто не ждёт Deferred) — память тоже не остаётся оптимистичной. */
    @Test
    fun failedCommit_ofNonAwaitedWriter_stillResyncsMemory() {
        addContactAndWait(contact("cG"))
        val failuresBefore = AppStateStore.writeFailureCount
        AppStateStore.writeFaultForTests = java.io.IOException("disk full")

        AppStateStore.addGift(gift("gF", "cG"))            // Deferred намеренно игнорируется
        assertEquals("оптимистично: подарок виден сразу", listOf("gF"), AppStateStore.gifts.map { it.id })

        val resynced = pollUntilTrue {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            AppStateStore.writeFailureCount > failuresBefore
        }
        assertTrue(resynced)
        assertTrue("подарок, которого нет в Room, убран из памяти", AppStateStore.gifts.isEmpty())
        assertTrue(runBlocking { db.giftDao().getAllGifts() }.isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 11 — быстрые записи: M / N / O.
    // ═════════════════════════════════════════════════════════════

    /** M: Save A, затем Save B без ожидания — единый писатель, финальный Room = B. */
    @Test
    fun M_rapidSaveAThenB_singleWriterOrder_finalRoomIsB() {
        addContactAndWait(contact("cM"))
        val gate = closeGate()                                         // обе записи стоят в очереди одновременно
        val a = fresh("cM")
        val saveA = AppStateStore.updateContact(a, a.copy(firstName = "A1"))
        val b = fresh("cM")                                            // экран видит оптимистичное A1
        val saveB = AppStateStore.updateContact(b, b.copy(firstName = "B1"))
        assertEquals("Room ещё старый", "Иван", rawRoomFirstName("cM"))

        gate.complete(Unit)
        runBlocking { saveA.await(); saveB.await() }

        assertEquals("финал = B", "B1", rawRoomFirstName("cM"))
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals("после reloadFromDb", "B1", fresh("cM").firstName)
    }

    @Test
    fun M2_manyRapidSaves_convergeOnLast() {
        addContactAndWait(contact("cM2"))
        val writes = (1..25).map { i ->
            val cur = fresh("cM2")
            AppStateStore.updateContact(cur, cur.copy(firstName = "V$i"))
        }
        runBlocking { writes.forEach { it.await() } }
        assertEquals("V25", rawRoomFirstName("cM2"))
    }

    /** N: обновление телефона и правка скаляра из РАЗНЫХ устаревших снимков, обе в очереди, без ожидания. */
    @Test
    fun N_rapidPhoneUpdateAndScalarEdit_fromStaleSnapshots_loseNothing() {
        addContactAndWait(contact("cN", phones = listOf(phone("cN", "pN1", "+7900000001", primary = true))))
        val s1 = fresh("cN")
        val s2 = fresh("cN")
        val gate = closeGate()

        val d1 = AppStateStore.updateContact(s1, s1.copy(firstName = "Правка"))
        val d2 = AppStateStore.updateContact(s2, s2.copy(phones = s2.phones + phone("cN", "pN2", "+7900000002")))
        gate.complete(Unit)
        runBlocking { d1.await(); d2.await() }

        assertEquals("Правка", rawRoomFirstName("cN"))
        assertEquals(setOf("+7900000001", "+7900000002"), rawRoomPhones("cN"))
    }

    /** O: быстрые заметки/подарки без ожидания между вызовами; после подтверждения обоих — всё в Room. */
    @Test
    fun O_quickSuccessiveNotesAndGifts_allCommitted_lastUpdateWins() {
        addContactAndWait(contact("cO"))
        val gate = closeGate()
        val writes = mutableListOf<Deferred<Unit>>()
        writes += AppStateStore.addNote(note("n1", "cO", "первая"))
        writes += AppStateStore.addNote(note("n2", "cO", "вторая"))
        writes += AppStateStore.addGift(gift("g1", "cO", "Книга"))
        writes += AppStateStore.addGift(gift("g2", "cO", "Шарф"))
        writes += AppStateStore.updateNote(AppStateStore.notes.first { it.id == "n1" }.copy(text = "первая v2"))
        writes += AppStateStore.updateNote(AppStateStore.notes.first { it.id == "n1" }.copy(text = "первая v3"))
        writes += AppStateStore.updateGift(AppStateStore.gifts.first { it.id == "g2" }.copy(title = "Шарф v2"))
        assertTrue("до открытия гейта Room пуст", runBlocking { db.noteDao().getAllNotes() }.isEmpty())

        gate.complete(Unit)
        assertTrue("все подтверждены", runBlocking { AppStateStore.awaitDurableCommit(*writes.toTypedArray()) })

        val notes = runBlocking { db.noteDao().getAllNotes() }.associate { it.id to it.text }
        assertEquals(mapOf("n1" to "первая v3", "n2" to "вторая"), notes)
        val gifts = runBlocking { db.giftDao().getAllGifts() }.associate { it.id to it.title }
        assertEquals(mapOf("g1" to "Книга", "g2" to "Шарф v2"), gifts)
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 6/7 — каждый критичный писатель отдаёт настоящий (несработавший заранее) Deferred.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun criticalWriters_returnDeferredThatIsPendingUntilWriterCommits() {
        addContactAndWait(contact("cW"))
        val company = Company("coW", "Acme", industry = Industry.IT, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        runBlocking { AppStateStore.addCompany(company).await() }
        val item = CalendarItem(id = "ciW", title = "Встреча", type = CalendarItemType.MEETING, startDate = "2026-12-01",
            isAllDay = true, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        runBlocking { AppStateStore.addCalendarItem(item).await() }
        val c = fresh("cW")
        val co = AppStateStore.getCompany("coW")!!
        val gate = closeGate()

        val writes = mapOf(
            "updateContact" to AppStateStore.updateContact(c, c.copy(firstName = "X")),
            "updateCompany" to AppStateStore.updateCompany(co, co.copy(name = "Y")),
            "updateCalendarItem" to AppStateStore.updateCalendarItem(item, item.copy(title = "Z")),
            "addGift" to AppStateStore.addGift(gift("gW", "cW")),
            "updateGift" to AppStateStore.updateGift(gift("gW", "cW", "Другое")),
            "deleteGift" to AppStateStore.deleteGift("gW"),
            "addNote" to AppStateStore.addNote(note("nW", "cW", "т")),
            "updateNote" to AppStateStore.updateNote(note("nW", "cW", "т2")),
            "deleteNote" to AppStateStore.deleteNote("nW"),
            "setSizeInfo" to AppStateStore.setSizeInfo("cW", SizeInfo("sW", "cW", clothingSize = "M")),
            "markContactedNow" to AppStateStore.markContactedNow("cW"),
            "setContactTags" to AppStateStore.setContactTags("cW", emptySet()),
            "setContactGroups" to AppStateStore.setContactGroups("cW", emptySet()),
        )
        writes.forEach { (name, d) -> assertFalse("$name: Deferred не может быть готов до коммита писателя", d.isCompleted) }

        gate.complete(Unit)
        assertTrue("все подтверждены", runBlocking { AppStateStore.awaitDurableCommit(*writes.values.toTypedArray()) })
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 13 — офлайн/сеть: запись в Room от сети не зависит; координаты переживают сбой геокодера.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun offline_geocoderFailure_unrelatedSaves_keepExistingCoordinates() {
        val addr = Address("aOff", AddressOwnerType.CONTACT, "cOff", AddressType.HOME, "Ленина 5", "Москва", "РФ")
        addContactAndWait(contact("cOff").copy(addresses = listOf(addr)))
        AppStateStore.setAddressCoords("aOff", 55.75, 37.61); settle()
        val s = fresh("cOff")                                      // держит адрес С координатами

        // «Сеть недоступна / геокодер упал»: геокодер просто не вызывает setAddressCoords вовсе
        // (для несуществующего id — заведомо ничего не делает). Дальше — несвязанные сохранения
        // по ВСЕМ агрегатам; ни одно не зависит от сети.
        assertTrue(runBlocking { AppStateStore.setAddressCoords("нет-такого", 1.0, 2.0).await(); true })
        val writes = listOf(
            AppStateStore.updateContact(s, s.copy(firstName = "Правка")),
            AppStateStore.addNote(note("nOff", "cOff", "оффлайн-заметка")),
            AppStateStore.addGift(gift("gOff", "cOff")),
            AppStateStore.setSizeInfo("cOff", SizeInfo("sOff", "cOff", shoeSize = "42")),
        )
        assertTrue("все несвязанные сохранения подтверждены без сети", runBlocking { AppStateStore.awaitDurableCommit(*writes.toTypedArray()) })

        val row = runBlocking { db.addressDao().getAllAddresses() }.single { it.id == "aOff" }
        assertEquals("координаты на месте", 55.75, row.latitude)
        assertEquals(37.61, row.longitude)
        assertEquals("Правка", rawRoomFirstName("cOff"))
        assertEquals(listOf("оффлайн-заметка"), runBlocking { db.noteDao().getAllNotes().map { it.text } })
    }

    // ═════════════════════════════════════════════════════════════
    //  FRONT 15 → CALENDAR DATA SAFETY PACK: бывшие характеризационные тесты (calendar_KNOWN_RISK_*) — теперь постоянные
    //  регрессионные: устаревший снимок редактора не откатывает завершение и не воскрешает удалённую ссылку
    //  (updateCalendarItem(base, edited) — 3-way, см. CalendarEditMerge). Полный набор сценариев — CalendarStaleEditorSafetyTest.
    // ═════════════════════════════════════════════════════════════

    private fun calendarItem(id: String, links: List<CalendarItemLink> = emptyList()) = CalendarItem(
        id = id, title = "Встреча", type = CalendarItemType.MEETING, startDate = "2026-12-01", isAllDay = true,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL, links = links,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")

    private fun roomCalendarItem(id: String): CalendarItem = runBlocking {
        val e = db.calendarDao().getAllCalendarItems().first { it.id == id }
        val links = db.calendarDao().getCalendarItemLinks().filter { it.calendarItemId == id }.map { it.toDomain() }
        e.toDomain().copy(links = links)
    }

    /** Было RISK-1: редактор держит ACTIVE-снимок; завершение (уведомление) пишет COMPLETED; сохранение редактора
     *  обязано применить ТОЛЬКО правку названия и не откатывать статус. */
    @Test
    fun calendar_staleEditor_doesNotRevertCompletion() {
        runBlocking { AppStateStore.addCalendarItem(calendarItem("ci1")).await() }
        val editorSnapshot = AppStateStore.getCalendarItemById("ci1")!!
        runBlocking { AppStateStore.markCalendarItemCompleted(editorSnapshot).await() }
        settle()
        assertEquals("предусловие: Room COMPLETED", CalendarItemStatus.COMPLETED, roomCalendarItem("ci1").status)

        runBlocking { AppStateStore.updateCalendarItem(editorSnapshot, editorSnapshot.copy(title = "Переименовано")).await() }

        assertEquals("статус НЕ откатился", CalendarItemStatus.COMPLETED, roomCalendarItem("ci1").status)
        assertEquals("правка названия применена", "Переименовано", roomCalendarItem("ci1").title)
        assertEquals("память = Room", CalendarItemStatus.COMPLETED, AppStateStore.getCalendarItemById("ci1")!!.status)
    }

    /** Было RISK-2: устаревший снимок воскрешал ссылку на удалённый контакт (delete→reinsert links). */
    @Test
    fun calendar_staleEditor_doesNotRestoreRemovedLink() {
        addContactAndWait(contact("cDel"))
        runBlocking {
            AppStateStore.addCalendarItem(calendarItem("ci2", links = listOf(
                CalendarItemLink("l1", "ci2", CalendarTargetType.CONTACT, "cDel")))).await()
        }
        val editorSnapshot = AppStateStore.getCalendarItemById("ci2")!!
        runBlocking { AppStateStore.deleteContact("cDel").await() }
        assertTrue("предусловие: каскад удалил ссылку из Room", roomCalendarItem("ci2").links.isEmpty())

        runBlocking { AppStateStore.updateCalendarItem(editorSnapshot, editorSnapshot.copy(title = "Переименовано")).await() }

        assertEquals("ссылка на удалённый контакт НЕ вернулась", emptyList<String>(), roomCalendarItem("ci2").links.map { it.id })
        assertEquals("правка названия применена", "Переименовано", roomCalendarItem("ci2").title)
        assertTrue("и в памяти сирот нет", AppStateStore.getCalendarItemById("ci2")!!.links.isEmpty())
    }
}
