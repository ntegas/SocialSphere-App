package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.contactCardDates
import com.aistudio.socialsphere.crmlxb.ui.screens.contactCardGiftEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.fail
import org.robolectric.RobolectricTestRunner

/**
 * Жалоба владельца 2026-10-10: «из контакта пропадают важные даты и подарки». Инвариант: что поставлено на контакт
 * (подарок, дата, подарок-событие календаря), остаётся на нём после ЛЮБОЙ операции над контактом — правки формой по
 * устаревшему снимку, перечитывания из Room (запуск), слияния дублей и его отмены, завершения события.
 * «Видно» = тем же отбором, что использует карточка (ContactCardData.kt) и список подарков вкладки.
 */
@RunWith(RobolectricTestRunner::class)
class ContactCardSurvivalTest {

    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("loadInitialData() не завершился за таймаут в setup()")
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear(); AppStateStore.companies.clear()
        db.close()
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

    private fun contact(id: String, first: String = "Иван") = Contact(
        id = id, firstName = first, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    private fun event(id: String, type: CalendarItemType, contactId: String) = CalendarItem(
        id = id, title = id, type = type, startDate = "2026-11-15", isAllDay = true,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
        links = listOf(CalendarItemLink("l-$id", id, CalendarTargetType.CONTACT, contactId)),
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    private fun settle() = awaitOffMainThread { AppStateStore.writeBarrier().await() }

    /** Контакт c1 с подарком, двумя датами (в т.ч. «Другое») и подарком-событием календаря. */
    private fun seed(id: String = "c1", first: String = "Иван") {
        awaitOffMainThread { AppStateStore.addContact(contact(id, first)).await() }
        awaitOffMainThread { AppStateStore.addGift(GiftIdea("g-$id", id, "Книга-$id", status = GiftStatus.IDEA)).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(event("bd-$id", CalendarItemType.BIRTHDAY, id)).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(event("cu-$id", CalendarItemType.CUSTOM, id)).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(event("ge-$id", CalendarItemType.GIFT, id)).await() }
    }

    private fun assertEverythingOn(id: String, step: String) {
        assertEquals("$step: подарок", listOf("g-$id"), AppStateStore.gifts.filter { it.contactId == id }.map { it.id })
        assertEquals("$step: даты", setOf("bd-$id", "cu-$id"),
            contactCardDates(AppStateStore.calendarItems, id).map { it.id }.toSet())
        assertEquals("$step: подарок-событие", listOf("ge-$id"),
            contactCardGiftEvents(AppStateStore.calendarItems, id).map { it.id })
    }

    private fun assertInRoom(id: String, step: String) {
        val gifts = awaitOffMainThread { db.giftDao().getAllGifts() }.filter { it.contactId == id }.map { it.id }
        assertEquals("$step: подарок в Room", listOf("g-$id"), gifts)
        val links = awaitOffMainThread { db.calendarDao().getCalendarItemLinks() }.filter { it.targetId == id }.map { it.calendarItemId }.toSet()
        assertEquals("$step: связи дат в Room", setOf("bd-$id", "cu-$id", "ge-$id"), links)
    }

    @Test fun editByStaleFormSnapshot_keepsGiftsAndDates() {
        awaitOffMainThread { AppStateStore.addContact(contact("c1")).await() }
        val staleBase = AppStateStore.contacts.single { it.id == "c1" }   // форма открыта ДО появления подарка/дат
        seed2("c1")
        val edited = staleBase.copy(firstName = "Пётр")
        awaitOffMainThread { AppStateStore.updateContact(staleBase, edited).await() }
        assertEquals("Пётр", AppStateStore.contacts.single { it.id == "c1" }.firstName)
        assertEverythingOn("c1", "после правки формой по устаревшему снимку")
        assertInRoom("c1", "после правки формой по устаревшему снимку")
    }

    /** seed без повторного добавления контакта. */
    private fun seed2(id: String) {
        awaitOffMainThread { AppStateStore.addGift(GiftIdea("g-$id", id, "Книга-$id", status = GiftStatus.IDEA)).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(event("bd-$id", CalendarItemType.BIRTHDAY, id)).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(event("cu-$id", CalendarItemType.CUSTOM, id)).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(event("ge-$id", CalendarItemType.GIFT, id)).await() }
    }

    @Test fun restartFromRoom_keepsGiftsAndDates() {
        seed()
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEverythingOn("c1", "после перечитывания из Room (запуск)")
    }

    @Test fun completingAnEvent_doesNotHideItFromTheCard() {
        seed()
        val bd = AppStateStore.calendarItems.single { it.id == "bd-c1" }
        awaitOffMainThread { AppStateStore.markCalendarItemCompleted(bd).await() }
        awaitOffMainThread { AppStateStore.markCalendarItemCompleted(AppStateStore.calendarItems.single { it.id == "ge-c1" }).await() }
        assertEverythingOn("c1", "после завершения событий")
    }

    // ── Обратное направление (владелец 2026-10-10): дата есть в календаре — она должна быть и на контакте ──

    private fun looseBirthday(id: String, title: String, links: List<CalendarItemLink> = emptyList()) = CalendarItem(
        id = id, title = title, type = CalendarItemType.BIRTHDAY, startDate = "1990-06-15", isAllDay = true,
        status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.KEY, links = links,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    @Test fun calendarBirthdayWithoutContact_nameInFreeTitle_appearsOnTheContactCard() {
        awaitOffMainThread { AppStateStore.addContact(contact("c1")).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(looseBirthday("e1", "ДР Иван Петров")).await() }
        assertEquals("до самолечения на карточке пусто (в календаре событие есть)",
            emptyList<String>(), contactCardDates(AppStateStore.calendarItems, "c1").map { it.id })

        awaitOffMainThread { AppStateStore.repairMissingContactLinksFromTitles() }

        assertEquals(listOf("e1"), contactCardDates(AppStateStore.calendarItems, "c1").map { it.id })
        val inRoom = awaitOffMainThread { db.calendarDao().getCalendarItemLinks() }.filter { it.calendarItemId == "e1" }
        assertEquals(listOf("c1"), inRoom.map { it.targetId })
    }

    @Test fun calendarBirthdayLinkedToVanishedContact_isRelinkedToTheSameNamedOne() {
        awaitOffMainThread { AppStateStore.addContact(contact("c1")).await() }
        val dangling = listOf(CalendarItemLink("l1", "e2", CalendarTargetType.CONTACT, "gone-contact"))
        awaitOffMainThread { AppStateStore.addCalendarItem(looseBirthday("e2", "Петров Иван — день рождения", dangling)).await() }

        awaitOffMainThread { AppStateStore.repairMissingContactLinksFromTitles() }

        assertEquals(listOf("e2"), contactCardDates(AppStateStore.calendarItems, "c1").map { it.id })
    }

    @Test fun calendarBirthday_noNameOrAmbiguousName_staysUnlinked_noGuessing() {
        awaitOffMainThread { AppStateStore.addContact(contact("c1")).await() }
        awaitOffMainThread { AppStateStore.addContact(contact("c2")).await() }   // второй «Иван Петров»
        awaitOffMainThread { AppStateStore.addCalendarItem(looseBirthday("e3", "ДР Иван Петров")).await() }
        awaitOffMainThread { AppStateStore.addCalendarItem(looseBirthday("e4", "День рождения")).await() }

        awaitOffMainThread { AppStateStore.repairMissingContactLinksFromTitles() }

        assertEquals(emptyList<String>(), contactCardDates(AppStateStore.calendarItems, "c1").map { it.id })
        assertEquals(emptyList<String>(), contactCardDates(AppStateStore.calendarItems, "c2").map { it.id })
    }

    @Test fun mergeDuplicates_thenUndo_keepsEverythingOnTheRightContact() {
        seed("c1", "Иван"); seed("c2", "Иван2")
        val snapshot = AppStateStore.mergeContacts(listOf("c1", "c2"))
        assertNotNull(snapshot)
        settle()
        val survivor = listOf("c1", "c2").single { id -> AppStateStore.contacts.any { it.id == id } }
        assertEquals(setOf("g-c1", "g-c2"), AppStateStore.gifts.filter { it.contactId == survivor }.map { it.id }.toSet())
        assertEquals(setOf("bd-c1", "cu-c1", "bd-c2", "cu-c2"),
            contactCardDates(AppStateStore.calendarItems, survivor).map { it.id }.toSet())
        assertEquals(setOf("ge-c1", "ge-c2"),
            contactCardGiftEvents(AppStateStore.calendarItems, survivor).map { it.id }.toSet())

        awaitOffMainThread { AppStateStore.undoMerge(snapshot!!).await() }
        assertEverythingOn("c1", "после отмены слияния (c1)")
        assertEverythingOn("c2", "после отмены слияния (c2)")
        assertInRoom("c1", "после отмены слияния (c1)")
        assertInRoom("c2", "после отмены слияния (c2)")
    }
}
