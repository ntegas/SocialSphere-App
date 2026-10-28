package com.aistudio.socialsphere.crmlxb.ui.screens

import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.effectiveDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жалоба владельца 2026-10-10: «в календаре важные даты вижу, а в контакте — пустое место; пропадают подарки».
 * Карточка и календарь читают одни и те же события, но карточка показывала не все: форма «Добавить дату»
 * предлагала тип «Другое», а список его не показывал; события «Подарок» не показывались вовсе.
 */
class ContactCardDataTest {

    private fun ev(id: String, type: CalendarItemType, contactId: String?, date: String = "2026-11-15",
                   status: CalendarItemStatus = CalendarItemStatus.ACTIVE) = CalendarItem(
        id = id, title = id, type = type, startDate = date, isAllDay = true, status = status,
        importance = ImportanceLevel.NORMAL,
        links = if (contactId == null) emptyList()
        else listOf(CalendarItemLink("l-$id", id, CalendarTargetType.CONTACT, contactId)),
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    @Test fun everyTypeTheDateFormOffers_isShownOnTheCard() {
        // Форма предлагает ровно CONTACT_CARD_DATE_TYPES (GiftsTab.typeOptions) — каждый из них обязан быть виден.
        CONTACT_CARD_DATE_TYPES.forEach { t ->
            val shown = contactCardDates(listOf(ev("e", t, "c1")), "c1")
            assertEquals("тип $t предложен формой, но не показан на карточке", 1, shown.size)
        }
    }

    @Test fun customDate_isShown_negativeForTheOldFourTypeFilter() {
        val old = setOf(CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY, CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE)
        assertTrue("«Другое» входит в то, что предлагает форма", CalendarItemType.CUSTOM in CONTACT_CARD_DATE_TYPES)
        assertTrue("а прежний фильтр из четырёх типов его терял", CalendarItemType.CUSTOM !in old)
        assertEquals(listOf("x"), contactCardDates(listOf(ev("x", CalendarItemType.CUSTOM, "c1")), "c1").map { it.id })
    }

    @Test fun datesAreOnlyThisContacts_andStatusNeverHidesThem() {
        val items = listOf(
            ev("mine", CalendarItemType.BIRTHDAY, "c1"),
            ev("done", CalendarItemType.IMPORTANT_DATE, "c1", status = CalendarItemStatus.COMPLETED),
            ev("past", CalendarItemType.ANNIVERSARY, "c1", date = "2001-01-01"),
            ev("other", CalendarItemType.BIRTHDAY, "c2"),
            ev("nolink", CalendarItemType.BIRTHDAY, null),
            ev("meeting", CalendarItemType.MEETING, "c1"),
        )
        assertEquals(listOf("mine", "done", "past"), contactCardDates(items, "c1").map { it.id })
    }

    @Test fun personDateWithoutContactOrCompany_asksBeforeSaving() {
        PERSON_DATE_TYPES.forEach { t ->
            assertTrue("$t без контакта и компании — спросить", dateNeedsContact(t, hasContact = false, hasCompany = false))
            assertTrue("$t с контактом — не спрашивать", !dateNeedsContact(t, hasContact = true, hasCompany = false))
            assertTrue("$t с компанией (годовщина компании) — не спрашивать", !dateNeedsContact(t, hasContact = false, hasCompany = true))
        }
        assertTrue("встречи/звонки/задачи не про «чью-то дату»",
            !dateNeedsContact(CalendarItemType.MEETING, hasContact = false, hasCompany = false))
        assertTrue(!dateNeedsContact(CalendarItemType.CUSTOM, hasContact = false, hasCompany = false))
    }

    // ── «Обзор» карточки (владелец 2026-10-28: «оповещение о дне рождения было, а в контакте его нет») ──

    private val today = java.time.LocalDate.of(2026, 10, 28)

    @Test fun overview_birthdayIsNeverCrowdedOutBySoonerEvents() {
        // 6 ближайших событий (звонки/задачи) + день рождения через полгода: прежний отбор «первые 5 из ВСЕХ
        // событий» выбрасывал день рождения с «Обзора»; теперь он в блоке дат, а «Ближайшие» — только остальные.
        val soon = (1..6).map { ev("call$it", CalendarItemType.CALL, "c1", date = "2026-11-0$it") }
        val birthday = ev("bd", CalendarItemType.BIRTHDAY, "c1", date = "1990-04-20")
            .copy(recurrenceRule = "FREQ=YEARLY", recurrenceExplicitlySet = true)
        val items = soon + birthday

        // прежний отбор «Ближайших»: активные будущие события контакта по ближайшей дате, первые 5 — из ВСЕХ типов
        val oldTop5 = items.filter {
            it.links.any { l -> l.targetId == "c1" } && it.status == CalendarItemStatus.ACTIVE &&
                it.effectiveDate(today) >= today.toString()
        }.sortedBy { it.effectiveDate(today) }.take(5)
        assertTrue("негативная проба: прежний отбор терял день рождения", oldTop5.none { it.id == "bd" })

        assertEquals(listOf("bd"), contactCardDates(items, "c1").map { it.id })
        val upcoming = contactUpcomingEvents(items, "c1", today)
        assertEquals("даты не занимают места в «Ближайших»", listOf("call1", "call2", "call3", "call4", "call5"), upcoming.map { it.id })
    }

    @Test fun overview_datesAreShown_evenWhenPastOrCompleted_butUpcomingKeepsOnlyActiveFuture() {
        val items = listOf(
            ev("pastBirthday", CalendarItemType.BIRTHDAY, "c1", date = "2020-01-01"),
            ev("doneAnniv", CalendarItemType.ANNIVERSARY, "c1", status = CalendarItemStatus.COMPLETED),
            ev("pastMeeting", CalendarItemType.MEETING, "c1", date = "2026-01-01"),
            ev("doneCall", CalendarItemType.CALL, "c1", status = CalendarItemStatus.COMPLETED),
            ev("futureMeeting", CalendarItemType.MEETING, "c1", date = "2026-12-01"),
            ev("otherContact", CalendarItemType.MEETING, "c2", date = "2026-12-02"),
        )
        assertEquals(setOf("pastBirthday", "doneAnniv"), contactCardDates(items, "c1").map { it.id }.toSet())
        assertEquals(listOf("futureMeeting"), contactUpcomingEvents(items, "c1", today).map { it.id })
    }

    @Test fun giftEvents_areShownOnTheCard_sortedByDate_allStatuses() {
        val items = listOf(
            ev("late", CalendarItemType.GIFT, "c1", date = "2026-12-24"),
            ev("early", CalendarItemType.GIFT, "c1", date = "2026-11-01", status = CalendarItemStatus.COMPLETED),
            ev("other", CalendarItemType.GIFT, "c2"),
            ev("birthday", CalendarItemType.BIRTHDAY, "c1"),
        )
        assertEquals(listOf("early", "late"), contactCardGiftEvents(items, "c1").map { it.id })
    }
}
