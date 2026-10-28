package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * Регрессия (2026-08-12, У61-класс): восстановление контакта из бэкапа
 * (AppStateStore.restoreContact) пересоздаёт строки phones/emails/messengers/
 * companyRelations/personalDetails/sizeInfo через addContactDb(), но notes и
 * gifts живут в СОБСТВЕННЫХ Room-таблицах (noteDao/giftDao), а не как часть
 * contact-строки — addContactDb() их молча пропускал. Владелец переустановил
 * приложение, восстановил JSON-бэкап — заметки и подарки не вернулись.
 *
 * BATCH 4 — почему этот класс раньше НЕ ПОПАДАЛ в отчёты о прогоне тестов.
 * Это была не проблема обнаружения: класс компилировался и запускался, но
 * ВИС насмерть, унося с собой всю задачу testDebugUnitTest. Причина доказана
 * thread dump'ом зависшего воркера:
 *
 *     "SDK 36 Main Thread" ... TIMED_WAITING (parking)
 *       parking to wait for <0x...> (a kotlinx.coroutines.BlockingCoroutine)
 *       at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:97)
 *       at ...AppStateStoreRestoreTest
 *              .repairMissingContactLinksFromTitles_uniqueNameMatch_relinksOrphanBirthday
 *
 * Тестовый метод исполняется НА потоке главного looper'а Robolectric и внутри
 * себя вызывал `runBlocking { repairMissingContactLinksFromTitles() }`. Эта
 * прод-функция после await() своей записи делает `withContext(Dispatchers.Main)`
 * (AppStateStore.kt) — совершенно правильный код: она мутирует Compose-состояние,
 * которое обязано меняться на главном потоке. Но под Robolectric в режиме
 * LooperMode.PAUSED (дефолт) главный looper прокручивается только вручную через
 * idle(), а единственный поток, который мог бы это сделать, уже запаркован
 * внутри runBlocking. Круговое ожидание: ни таймаута, ни нагрузки на CPU.
 * (Соседний тест с неоднозначным именем не висел — он возвращается раньше, до
 * withContext, и это A/B-подтверждение: он проходил за 59 с и писал свой XML.)
 *
 * Лечится в ТЕСТЕ, не в проде: suspend-вызовы, которые внутри себя переходят на
 * Dispatchers.Main, запускаются в ОТДЕЛЬНОЙ корутине, а тестовый поток тем
 * временем прокручивает главный looper (см. awaitOffMainThread ниже). Точно тот
 * же приём уже применён в AppStateStoreDataIntegrityTest (TEST GROUP O).
 */
@RunWith(RobolectricTestRunner::class)
class AppStateStoreRestoreTest {

    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // ФИКС (2026-08-12): AppStateStore.initialize() идемпотентен по дизайну
        // (см. её же комментарий) — без явного resetForTests() второй @Test в
        // этом классе получает database, всё ещё указывающий на db ПЕРВОГО
        // теста (initialize() молча ничего не делает при повторном вызове),
        // а та db уже закрыта его @After. Раньше это ловилось как случайный
        // провал СОСЕДНЕГО теста (notes/gifts) при добавлении второго @Test
        // в класс — не регресс кода, баг тестовой обвязки.
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        // BATCH 4: дожидаемся фонового loadInitialData() ЭТОГО теста, прокручивая
        // главный looper. Раньше класс этого не делал — а loadInitialData()
        // внутри себя тоже переходит на Dispatchers.Main (reloadFromDb + сброс
        // isLoading), и без прокрутки эти континуации оставались висеть навсегда,
        // а фоновая перезагрузка могла приземлиться посреди теста и затереть
        // только что восстановленное состояние. Тот же приём, что в
        // AppStateStoreDataIntegrityTest.setup().
        val settled = pollUntilTrue(timeoutMs = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут в setup()")
    }

    @After
    fun tearDown() {
        // Не оставляем тестовые данные в общем singleton-состоянии AppStateStore
        // между тестами, разделяющими classloader (тот же паттерн, что
        // PhoneDedupeTest/CompanyDedupeTest для contacts/companies).
        // ФИКС (2026-08-12): calendarItems сюда не попадал изначально (класс
        // писался до calendar-item-теста ниже) — без очистки событие из ОДНОГО
        // теста оставалось в общем списке для СЛЕДУЮЩЕГО, а его фоновая корутина
        // restoreCalendarItem ещё могла писать в уже закрываемую db() этого
        // теста ровно в момент, когда следующий @Before подменяет db —
        // ловилось как случайный провал СОСЕДНЕГО теста (notes/gifts), не своего.
        // BATCH 4: список расширен — тесты полного бэкапа населяют и компании,
        // и группы/теги с членством, и связи между контактами.
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
        db.close()
    }

    // ─────────────────────────────────────────────────────────────
    //  Тестовая обвязка
    // ─────────────────────────────────────────────────────────────

    /** Опрашивает условие, прокручивая при этом главный looper (см. вызовы). */
    private fun pollUntilTrue(timeoutMs: Long = 10_000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
            ok = check()
        }
        ok
    }

    /**
     * Выполняет suspend-блок ВНЕ тестового потока и прокручивает главный looper,
     * пока блок не завершится; возвращает его результат либо пробрасывает его
     * исключение. Обязательно для всего, что внутри себя делает
     * withContext(Dispatchers.Main) — прямой runBlocking на таком вызове
     * гарантированно вешает весь класс (см. thread dump в шапке файла).
     */
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
        // Уже завершён — await() здесь не приостанавливается, только достаёт
        // результат либо перебрасывает исключение.
        return runBlocking { done.await() }
    }

    /** Опрашивает БД, пока не появятся ожидаемые записи или не истечёт таймаут —
     *  для СТАРЫХ fire-and-forget путей (restoreContact и т.п.), которые пишут
     *  в фоновой корутине AppStateStore.scope. Новый restoreBackup() durable
     *  на возврате, ему опрос не нужен. */
    private fun <T> pollUntilNotEmpty(timeoutMs: Long = 5000, poll: suspend () -> List<T>): List<T> = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = poll()
        while (result.isEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
            result = poll()
        }
        result
    }

    private fun contact(id: String, notes: List<Note> = emptyList(), gifts: List<GiftIdea> = emptyList()) = Contact(
        id = id, firstName = "Иван", lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        notes = notes, gifts = gifts,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun note(id: String, contactId: String) = Note(
        id = id, contactId = contactId, companyId = null, calendarItemId = null, giftId = null,
        type = NoteType.IMPORTANT_TO_REMEMBER, text = "Аллергия на орехи", date = "2026-01-01",
        isImportant = true, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun gift(id: String, contactId: String) = GiftIdea(
        id = id, contactId = contactId, title = "Книга", note = "Фантастика",
        link = null, date = "2026-12-25", status = GiftStatus.IDEA
    )

    private fun company(id: String) = Company(
        id = id, name = "Acme Corp", industry = Industry.IT,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun backupJson(data: BackupData): String = ExportManager.backupAdapter.toJson(data)

    // ─────────────────────────────────────────────────────────────
    //  Существующие регрессии (пути до Batch 4)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun restoreContact_persistsNestedNotesAndGiftsToDatabase() {
        val contactId = "c1"
        val restoredNote = note("n1", contactId)
        val restoredGift = gift("g1", contactId)
        val contact = contact(contactId, notes = listOf(restoredNote), gifts = listOf(restoredGift))

        AppStateStore.restoreContact(contact)

        val notesInDb = pollUntilNotEmpty { db.noteDao().getAllNotes() }
        val giftsInDb = pollUntilNotEmpty { db.giftDao().getAllGifts() }

        if (notesInDb.isEmpty()) fail("Заметка контакта не долетела до noteDao за таймаут — restoreContact не персистит вложенные notes")
        if (giftsInDb.isEmpty()) fail("Подарок контакта не долетел до giftDao за таймаут — restoreContact не персистит вложенные gifts")

        assertEquals(1, notesInDb.size)
        assertEquals("n1", notesInDb.single().id)
        assertEquals(contactId, notesInDb.single().contactId)
        assertEquals("Аллергия на орехи", notesInDb.single().text)

        assertEquals(1, giftsInDb.size)
        assertEquals("g1", giftsInDb.single().id)
        assertEquals(contactId, giftsInDb.single().contactId)
        assertEquals("Книга", giftsInDb.single().title)

        // Контакт сам тоже персистится (addContactDb) — не только его дети.
        val contactsInDb = pollUntilNotEmpty { db.contactDao().getAllContacts() }
        if (contactsInDb.isEmpty()) fail("Контакт не долетел до contactDao за таймаут")
        assertTrue(contactsInDb.any { it.id == contactId })
    }

    /**
     * Регрессия (2026-08-12, владелец: «импортировал JSON, дни рождения появились
     * в Календаре, но на самой карточке контакта — ни дней рождения, ни важных
     * дат, ничего»). Проверяет полную дорожку importJsonBackup: контакт +
     * привязанное к нему событие должны дожить до AppStateStore.calendarItems,
     * откуда OverviewTab.kt берёт «Ближайшие события контакта» фильтром
     * `ev.links.any { it.targetId == contact.id }`.
     *
     * BATCH 4: DB-проверки больше НЕ через опрос — importJsonBackup теперь
     * возвращается только после коммита, поэтому строки обязаны быть в Room
     * сразу же. Опрос здесь скрыл бы именно тот дефект, который чинит Batch 4.
     */
    @Test
    fun importJsonBackup_birthdayLinkedToContact_visibleOnContactOverview() {
        val contactId = "c2"
        val eventId = "e1"
        val linkId = "l1"
        val contact = contact(contactId)
        val birthday = CalendarItem(
            id = eventId, title = "День рождения", description = null,
            type = CalendarItemType.BIRTHDAY, startDate = "1990-06-15",
            startTime = null, endDate = null, endTime = null, isAllDay = true,
            status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.KEY,
            colorKey = null, recurrenceRule = "FREQ=YEARLY",
            links = listOf(CalendarItemLink(
                id = linkId, calendarItemId = eventId,
                targetType = CalendarTargetType.CONTACT, targetId = contactId
            )),
            reminders = emptyList(),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        val backup = backupJson(BackupData(contacts = listOf(contact), calendarItems = listOf(birthday)))

        val restoredCount = awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        assertEquals(1, restoredCount)
        val restoredItem = AppStateStore.calendarItems.find { it.id == eventId }
        assertNotNull("событие должно появиться в AppStateStore.calendarItems после importJsonBackup", restoredItem)
        assertTrue("у восстановленного события должна быть привязка к контакту $contactId, links=${restoredItem?.links}",
            restoredItem!!.links.any { it.targetId == contactId })

        val linksInDb = runBlocking { db.calendarDao().getCalendarItemLinks() }
        assertTrue("CalendarItemLink обязан быть в Room сразу после возврата importJsonBackup",
            linksInDb.any { it.targetId == contactId && it.calendarItemId == eventId })
        val contactsInDb = runBlocking { db.contactDao().getAllContacts() }
        assertTrue("контакт обязан быть в Room сразу после возврата importJsonBackup",
            contactsInDb.any { it.id == contactId })
    }

    /**
     * Фича (2026-08-12, владелец: «оно в календаре всё равно напишется, чей это
     * день рождения — почему оно обратно не присоединится к контакту?»).
     * Событие без links (как после старого бэкапа) должно получить связь по
     * имени из заголовка.
     */
    @Test
    fun repairMissingContactLinksFromTitles_uniqueNameMatch_relinksOrphanBirthday() {
        val contactId = "c3"
        val eventId = "e2"
        AppStateStore.restoreContact(contact(contactId))
        AppStateStore.restoreCalendarItem(orphanBirthday(eventId, "День рождения: Иван Петров"))

        // BATCH 4: именно эта строка вешала весь класс (был runBlocking{}).
        awaitOffMainThread { AppStateStore.repairMissingContactLinksFromTitles() }

        val repaired = AppStateStore.calendarItems.find { it.id == eventId }
        assertTrue("после repair у события должна появиться связь с $contactId, links=${repaired?.links}",
            repaired!!.links.any { it.targetId == contactId })

        val linksInDb = pollUntilNotEmpty { db.calendarDao().getCalendarItemLinks() }
        if (linksInDb.isEmpty()) fail("Восстановленная связь не долетела до calendarDao за таймаут")
        assertTrue(linksInDb.any { it.targetId == contactId && it.calendarItemId == eventId })
    }

    /**
     * Защита от гадания: два контакта с одинаковым полным именем — repair НЕ
     * должен привязывать событие ни к одному из них (привязать не того
     * человека хуже, чем оставить как есть — это чужие личные данные).
     */
    @Test
    fun repairMissingContactLinksFromTitles_ambiguousName_doesNotLink() {
        val eventId = "e3"
        AppStateStore.restoreContact(contact("c4"))
        AppStateStore.restoreContact(contact("c5")) // тот же «Иван Петров»
        AppStateStore.restoreCalendarItem(orphanBirthday(eventId, "День рождения: Иван Петров"))

        awaitOffMainThread { AppStateStore.repairMissingContactLinksFromTitles() }

        val stillOrphan = AppStateStore.calendarItems.find { it.id == eventId }
        assertTrue("при неоднозначном имени repair не должен создавать связь, links=${stillOrphan?.links}",
            stillOrphan!!.links.isEmpty())
    }

    private fun orphanBirthday(id: String, title: String) = CalendarItem(
        id = id, title = title, description = null,
        type = CalendarItemType.BIRTHDAY, startDate = "1990-06-15",
        startTime = null, endDate = null, endTime = null, isAllDay = true,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.KEY,
        colorKey = null, recurrenceRule = "FREQ=YEARLY",
        links = emptyList(), reminders = emptyList(),
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    // ─────────────────────────────────────────────────────────────
    //  BATCH 4 — durable completion + атомарность восстановления
    // ─────────────────────────────────────────────────────────────

    /**
     * BATCH 4 TEST A+B — полный бэкап долговечен УЖЕ НА ВОЗВРАТЕ.
     *
     * Ключевая деталь: ни одной опросной проверки. Все запросы к Room идут
     * сразу после возврата importJsonBackup(). На старом коде (сотни отдельных
     * fire-and-forget команд, возврат на этапе enqueue) этот тест падал бы —
     * данных в БД в момент возврата ещё нет. Опрос с таймаутом маскировал бы
     * ровно тот дефект, который чинит Batch 4, поэтому его здесь нет намеренно.
     */
    @Test
    fun importJsonBackup_fullBackup_isDurableInRoomImmediatelyOnReturn() {
        val contactId = "b1c"
        val backup = backupJson(BackupData(
            companies = listOf(company("b1co")),
            contacts = listOf(contact(contactId, notes = listOf(note("b1n", contactId)), gifts = listOf(gift("b1g", contactId)))),
            calendarItems = listOf(orphanBirthday("b1e", "День рождения")),
            groups = listOf(ContactGroup("b1gr", "Клиенты", "2026-01-01T00:00", "2026-01-01T00:00")),
            groupMembers = listOf(ContactGroupMember("b1gm", "b1gr", contactId)),
            tags = listOf(Tag("b1t", "VIP", null, "2026-01-01T00:00", "2026-01-01T00:00")),
            tagMembers = listOf(ContactTagMember("b1tm", "b1t", contactId)),
            notes = listOf(note("b1n2", contactId)),
            gifts = listOf(gift("b1g2", contactId))
        ))

        val restoredCount = awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        assertEquals("возвращаемое число — контакты бэкапа", 1, restoredCount)

        runBlocking {
            assertTrue("контакт", db.contactDao().getAllContacts().any { it.id == contactId })
            assertTrue("компания", db.companyDao().getAllCompanies().any { it.id == "b1co" })
            assertTrue("событие", db.calendarDao().getAllCalendarItems().any { it.id == "b1e" })
            assertTrue("группа", db.contactDao().getContactGroups().any { it.id == "b1gr" })
            assertTrue("членство в группе", db.contactDao().getContactGroupMembers().any { it.id == "b1gm" })
            val tagsInDb = db.contactDao().getTags().first()
            assertTrue("тег", tagsInDb.any { it.id == "b1t" })
            val tagMembersInDb = db.contactDao().getContactTagMembers().first()
            assertTrue("членство в теге", tagMembersInDb.any { it.id == "b1tm" })
            val notesInDb = db.noteDao().getAllNotes()
            assertTrue("вложенная заметка контакта", notesInDb.any { it.id == "b1n" })
            assertTrue("плоская заметка", notesInDb.any { it.id == "b1n2" })
            val giftsInDb = db.giftDao().getAllGifts()
            assertTrue("вложенный подарок контакта", giftsInDb.any { it.id == "b1g" })
            assertTrue("плоский подарок", giftsInDb.any { it.id == "b1g2" })
        }

        // И память согласована с БД (обновляется только после коммита).
        assertTrue(AppStateStore.contacts.any { it.id == contactId })
        assertTrue(AppStateStore.companies.any { it.id == "b1co" })
        assertTrue(AppStateStore.notes.any { it.id == "b1n2" })
    }

    /**
     * BATCH 4 TEST C — граф связей переживает восстановление: contact↔company
     * (через вложенный ContactCompanyRelation) и contact↔tag (через членство).
     */
    @Test
    fun importJsonBackup_restoresContactCompanyAndTagRelations() {
        val contactId = "b2c"
        val companyId = "b2co"
        val withEmployer = contact(contactId).copy(
            companyRelations = listOf(ContactCompanyRelation(
                id = "b2rel", contactId = contactId, companyId = companyId,
                position = "Инженер", employmentStatus = EmploymentStatus.CURRENT, isPrimary = true
            ))
        )
        val backup = backupJson(BackupData(
            companies = listOf(company(companyId)),
            contacts = listOf(withEmployer),
            tags = listOf(Tag("b2t", "VIP", null, "2026-01-01T00:00", "2026-01-01T00:00")),
            tagMembers = listOf(ContactTagMember("b2tm", "b2t", contactId))
        ))

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        runBlocking {
            val rel = db.contactDao().getContactCompanyRelations().find { it.id == "b2rel" }
            assertNotNull("связь контакт↔компания должна быть в Room сразу после возврата", rel)
            assertEquals(contactId, rel!!.contactId)
            assertEquals(companyId, rel.companyId)
            val tagMembers = db.contactDao().getContactTagMembers().first()
            val tm = tagMembers.find { it.id == "b2tm" }
            assertNotNull("членство контакт↔тег должно быть в Room сразу после возврата", tm)
            assertEquals(contactId, tm!!.contactId)
            assertEquals("b2t", tm.tagId)
        }
        // Тот же граф виден и в памяти — карточка компании читает companyRelations.
        assertTrue(AppStateStore.companyRelations.any { it.contactId == contactId && it.companyId == companyId })
    }

    /**
     * BATCH 4 TEST D — битый JSON: отказ, и ни одной записи в БД.
     * Разбор идёт целиком до первой записи, поэтому частичного применения быть
     * не может в принципе.
     */
    @Test
    fun importJsonBackup_malformedJson_returnsMinusOneAndLeavesDbUntouched() {
        val contactsBefore = runBlocking { db.contactDao().getAllContacts().size }

        val result = awaitOffMainThread { ExportManager.importJsonBackup("{not valid json") }

        assertEquals(-1, result)
        val contactsAfter = runBlocking { db.contactDao().getAllContacts() }
        assertEquals("битый файл не должен писать в БД", contactsBefore, contactsAfter.size)
        assertTrue("битый файл не должен менять память", AppStateStore.contacts.isEmpty())
    }

    /**
     * BATCH 4 TEST E — сбой БД пробрасывается наверх и НЕ двигает память вперёд.
     *
     * Инъекция сбоя без архитектурных швов: приводим хранилище в состояние
     * «БД недоступна на момент записи» (resetForTests обнуляет database) — в
     * проде на этот случай стоит явный throw внутри enqueueWrite. Команда
     * писателя падает, её Deferred завершается исключением, await() в
     * restoreBackup его перебрасывает, и reloadFromDb() (обновление памяти) не
     * выполняется вовсе.
     *
     * Тест различает старое и новое поведение по существу: раньше каждая
     * restore*-функция мутировала память СИНХРОННО, ещё до постановки записи в
     * очередь, поэтому контакт оказался бы в памяти даже при полностью
     * провалившейся записи. Теперь память не двигается вперёд провала.
     *
     * Замечание по методике: первая попытка инъекции — закрыть Room-БД перед
     * восстановлением — оказалась негодной. Room переоткрывает закрытую
     * in-memory базу на следующем же обращении к DAO (пустой), запись при этом
     * успешно проходит, и тест ничего бы не проверял.
     *
     * TEST GAP: настоящий откат ЧАСТИЧНО применённой транзакции (упасть на
     * середине записей) здесь не воспроизводится — все поля моделей non-null,
     * внешних ключей в схеме нет, а подменить DAO нечем без введения слоя
     * внедрения зависимостей поверх конкретных Room-DAO. Сам откат при этом
     * гарантирован Room/SQLite: весь restore выполняется в ОДНОЙ
     * db.withTransaction, и выброшенное исключение отменяет её целиком.
     */
    @Test
    fun importJsonBackup_dbFailure_propagatesAndDoesNotAdvanceMemory() {
        val backup = backupJson(BackupData(contacts = listOf(contact("b3c"))))
        AppStateStore.resetForTests() // БД недоступна — запись обязана упасть

        var thrown: Throwable? = null
        try {
            awaitOffMainThread { ExportManager.importJsonBackup(backup) }
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull("сбой записи обязан дойти до вызывающего, а не быть проглоченным", thrown)
        assertTrue("память не должна показывать восстановленный контакт после провала транзакции",
            AppStateStore.contacts.none { it.id == "b3c" })
        val contactsInDb = runBlocking { db.contactDao().getAllContacts() }
        assertTrue("в БД тоже не должно остаться следов провалившегося восстановления",
            contactsInDb.none { it.id == "b3c" })
    }

    /** BATCH 4 TEST F — заметки долговечны на возврате importNotesJson(). */
    @Test
    fun importNotesJson_isDurableInRoomOnReturn() {
        val backup = backupJson(BackupData(version = 5, notes = listOf(note("b4n", "b4c"))))

        val count = awaitOffMainThread { ExportManager.importNotesJson(backup) }

        assertEquals(1, count)
        val notesInDb = runBlocking { db.noteDao().getAllNotes() }
        assertTrue("заметка обязана быть в Room сразу после возврата importNotesJson",
            notesInDb.any { it.id == "b4n" })
        assertTrue(AppStateStore.notes.any { it.id == "b4n" })
    }

    /** BATCH 4 TEST G — события долговечны на возврате importCalendarJson(). */
    @Test
    fun importCalendarJson_isDurableInRoomOnReturn() {
        val backup = backupJson(BackupData(
            version = 5,
            calendarItems = listOf(orphanBirthday("b5e", "День рождения"))
        ))

        val count = awaitOffMainThread { ExportManager.importCalendarJson(backup) }

        assertEquals(1, count)
        val itemsInDb = runBlocking { db.calendarDao().getAllCalendarItems() }
        assertTrue("событие обязано быть в Room сразу после возврата importCalendarJson",
            itemsInDb.any { it.id == "b5e" })
        assertTrue(AppStateStore.calendarItems.any { it.id == "b5e" })
        // Правило рекуррентности не тронуто восстановлением.
        assertEquals("FREQ=YEARLY", itemsInDb.first { it.id == "b5e" }.recurrenceRule)
    }

    /** BATCH 4 — пустой, но валидный бэкап: 0, без записей и без зависания писателя. */
    @Test
    fun importJsonBackup_emptyButValidBackup_returnsZeroAndDoesNotHang() {
        val backup = backupJson(BackupData())

        val count = awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        assertEquals(0, count)
        assertTrue(AppStateStore.contacts.isEmpty())
    }
}
