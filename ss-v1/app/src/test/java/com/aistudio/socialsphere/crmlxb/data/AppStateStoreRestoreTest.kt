package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
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
 * Регрессия (2026-08-12, У61-класс): восстановление контакта из бэкапа
 * (AppStateStore.restoreContact) пересоздаёт строки phones/emails/messengers/
 * companyRelations/personalDetails/sizeInfo через addContactDb(), но notes и
 * gifts живут в СОБСТВЕННЫХ Room-таблицах (noteDao/giftDao), а не как часть
 * contact-строки — addContactDb() их молча пропускал. Владелец переустановил
 * приложение, восстановил JSON-бэкап — заметки и подарки не вернулись.
 *
 * Этот тест гоняет restoreContact() через РЕАЛЬНУЮ in-memory Room БД (не мок)
 * и проверяет, что вложенные Contact.notes/Contact.gifts реально долетают до
 * noteDao/giftDao. На старом коде (до фикса restoreNote/restoreGift-вызовов в
 * конце restoreContact) этот тест падает таймаутом опроса БД.
 *
 * restoreContact/restoreNote/restoreGift пишут в БД асинхронно через
 * AppStateStore.scope (реальный CoroutineScope(Dispatchers.IO), НЕ тестовый
 * TestDispatcher — runTest/advanceUntilIdle тут не применимы, потому что это
 * не тот же диспетчер, что видит корутин-тест). Поэтому результат ждём опросом
 * (polling) реальной БД с таймаутом — так же надёжно ловит регресс (старый код
 * никогда не запишет notes/gifts вообще, опрос гарантированно упрётся в таймаут),
 * но не завязан на кооперативный тестовый шедулинг.
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
        AppStateStore.contacts.clear()
        AppStateStore.notes.clear()
        AppStateStore.gifts.clear()
        AppStateStore.addresses.clear()
        AppStateStore.calendarItems.clear()
        db.close()
    }

    private fun contact(id: String, notes: List<Note>, gifts: List<GiftIdea>) = Contact(
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
        link = null, date = "2026-12-25", reminderId = null, status = GiftStatus.IDEA
    )

    /** Опрашивает БД, пока не появятся ожидаемые записи или не истечёт таймаут —
     *  запись идёт в фоновой корутине AppStateStore.scope (реальный Dispatchers.IO),
     *  не в тестовом диспетчере, поэтому advanceUntilIdle() тут не сработает. */
    private fun <T> pollUntilNotEmpty(timeoutMs: Long = 5000, poll: suspend () -> List<T>): List<T> = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = poll()
        while (result.isEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
            result = poll()
        }
        result
    }

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
        // Пишется в ОТДЕЛЬНОЙ корутине restoreContact (delete* + addContactDb),
        // параллельной restoreNote/restoreGift — тоже ждём опросом, а не одним
        // снимком, иначе тест иногда ловит гонку раньше, чем запись долетит.
        val contactsInDb = pollUntilNotEmpty { db.contactDao().getAllContacts() }
        if (contactsInDb.isEmpty()) fail("Контакт не долетел до contactDao за таймаут")
        assertTrue(contactsInDb.any { it.id == contactId })
    }

    /**
     * Регрессия (2026-08-12, владелец: «импортировал JSON, дни рождения появились
     * в Календаре, но на самой карточке контакта — ни дней рождения, ни важных
     * дат, ничего»). Гипотеза: `ExportManager.importJsonBackup()` полным путём
     * (`AppStateStore.restoreContact` + `restoreCalendarItem`), а не только
     * Moshi-слой (уже покрыт `ExportManagerTest.roundTrip_calendarOnlyBackup...`,
     * там `links` доживают ЧЕРЕЗ JSON-сериализацию честно) — но эта проверка
     * ни разу не гоняла именно связку «контакт + привязанное к нему
     * calendar-событие» через РЕАЛЬНЫЙ `AppStateStore.calendarItems`, откуда
     * `OverviewTab.kt` берёт «Ближайшие события контакта» фильтром
     * `ev.links.any { it.targetId == contact.id }`. Если бэкап (или сам импорт)
     * теряет `CalendarItemLink`, вкладка Календарь всё равно покажет событие
     * (она не завязана на links вообще — просто список по датам), а карточка
     * контакта — нет. Тест целиком воспроизводит эту дорожку.
     */
    @Test
    fun importJsonBackup_birthdayLinkedToContact_visibleOnContactOverview() {
        val contactId = "c2"
        val eventId = "e1"
        val linkId = "l1"
        val contact = contact(contactId, notes = emptyList(), gifts = emptyList())
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
        val backup = com.aistudio.socialsphere.crmlxb.utils.ExportManager.backupAdapter.toJson(
            com.aistudio.socialsphere.crmlxb.utils.BackupData(
                contacts = listOf(contact),
                calendarItems = listOf(birthday)
            )
        )

        val restoredCount = com.aistudio.socialsphere.crmlxb.utils.ExportManager.importJsonBackup(backup)

        assertEquals(1, restoredCount)
        // То самое условие, что использует OverviewTab.kt для «Ближайших событий
        // контакта» — если оно ложно сразу после импорта, баг воспроизведён.
        val restoredItem = AppStateStore.calendarItems.find { it.id == eventId }
        assertTrue("событие должно появиться в AppStateStore.calendarItems после importJsonBackup",
            restoredItem != null)
        assertTrue("у восстановленного события должна быть привязка к контакту $contactId, links=${restoredItem?.links}",
            restoredItem!!.links.any { it.targetId == contactId })

        // И в БД тоже — не только в памяти сразу после синхронного restoreCalendarItem.
        val linksInDb = pollUntilNotEmpty { db.calendarDao().getCalendarItemLinks() }
        if (linksInDb.isEmpty()) fail("CalendarItemLink не долетел до calendarDao за таймаут — связь события с контактом не персистится")
        assertTrue(linksInDb.any { it.targetId == contactId && it.calendarItemId == eventId })

        // Дожидаемся и записи самого контакта (restoreContact пишет её в ОТДЕЛЬНОЙ
        // фоновой корутине) — иначе тест может завершиться раньше, чем эта
        // корутина отработает, и она долетит уже во время db.close()/следующего
        // теста (см. фикс tearDown выше).
        val contactsInDb = pollUntilNotEmpty { db.contactDao().getAllContacts() }
        if (contactsInDb.isEmpty()) fail("Контакт не долетел до contactDao за таймаут")
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

    /**
     * Фича (2026-08-12, владелец: «оно в календаре всё равно напишется, чей это
     * день рождения — почему оно обратно не присоединится к контакту?»).
     * Имя контакта уже лежит в заголовке события («День рождения: Иван Петров»)
     * — раньше это был мёртвый текст, никогда не читавшийся обратно, даже когда
     * реальная связь `CalendarItemLink` на контакт отсутствовала. Тест
     * воспроизводит ИМЕННО это состояние (событие без links, как после старого
     * бэкапа/ручного создания без выбора контакта) и проверяет, что
     * `repairMissingContactLinksFromTitles()` находит контакт по имени из
     * заголовка и восстанавливает связь — САМО событие/контакт при этом не
     * трогает, только добавляет недостающий `CalendarItemLink`.
     */
    @Test
    fun repairMissingContactLinksFromTitles_uniqueNameMatch_relinksOrphanBirthday() {
        val contactId = "c3"
        val eventId = "e2"
        AppStateStore.restoreContact(contact(contactId, notes = emptyList(), gifts = emptyList()))
        AppStateStore.restoreCalendarItem(orphanBirthday(eventId, "День рождения: Иван Петров"))

        runBlocking { AppStateStore.repairMissingContactLinksFromTitles() }

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
        AppStateStore.restoreContact(contact("c4", notes = emptyList(), gifts = emptyList()))
        AppStateStore.restoreContact(contact("c5", notes = emptyList(), gifts = emptyList())) // тот же «Иван Петров»
        AppStateStore.restoreCalendarItem(orphanBirthday(eventId, "День рождения: Иван Петров"))

        runBlocking { AppStateStore.repairMissingContactLinksFromTitles() }

        val stillOrphan = AppStateStore.calendarItems.find { it.id == eventId }
        assertTrue("при неоднозначном имени repair не должен создавать связь, links=${stillOrphan?.links}",
            stillOrphan!!.links.isEmpty())
    }
}
