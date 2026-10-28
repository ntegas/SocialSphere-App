package com.aistudio.socialsphere.crmlxb.ui.screens

import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.utils.effectiveDate

/**
 * Что карточка контакта показывает из Календаря. Жалоба владельца 2026-10-10: «в календаре я важные даты
 * вижу, а в контакте — пустое место; пропадают подарки, которые я ставил».
 *
 * Корень: карточка и календарь читают ОДНИ И ТЕ ЖЕ события (CalendarItem + связь с контактом), но карточка
 * показывала не все. Форма «Добавить важную дату» предлагала тип «Другое», а список выбирал только четыре
 * других типа — событие сохранялось, было видно в календаре и нигде не появлялось на карточке; события типа
 * «Подарок» не имели места на карточке вообще. Теперь и форма, и список берут типы из ОДНОГО списка ниже —
 * расхождение «предлагаем → не показываем» невозможно (тот же класс ошибки, что захардкоженный listOf(enum)).
 */
internal val CONTACT_CARD_DATE_TYPES: List<CalendarItemType> = listOf(
    CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
    CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE,
    CalendarItemType.CUSTOM,
)

/** События про человека: без связи с контактом они видны в Календаре, но не на карточке. */
internal val PERSON_DATE_TYPES: Set<CalendarItemType> = setOf(
    CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
    CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE,
)

/** Нужно ли перед сохранением спросить «чья это дата»: событие про человека, и ни контакта, ни компании не выбрано. */
internal fun dateNeedsContact(type: CalendarItemType, hasContact: Boolean, hasCompany: Boolean): Boolean =
    type in PERSON_DATE_TYPES && !hasContact && !hasCompany

private fun CalendarItem.isLinkedTo(contactId: String) = links.any { it.targetId == contactId }

/** «Важные даты» карточки: все события этих типов, связанные с контактом (любой статус — карточка ничего не прячет). */
internal fun contactCardDates(items: List<CalendarItem>, contactId: String): List<CalendarItem> =
    items.filter { it.type in CONTACT_CARD_DATE_TYPES && it.isLinkedTo(contactId) }

/**
 * «Ближайшие события» карточки (вкладка «Обзор»): активные будущие события контакта, КРОМЕ дат ([CONTACT_CARD_DATE_TYPES]) —
 * даты показывает блок «Важные даты» над ними целиком, поэтому они не занимают места в лимите [limit] и не дублируются.
 * Прежний вариант брал первые 5 из ВСЕХ событий: день рождения вытеснялся звонками/задачами и пропадал с «Обзора».
 */
internal fun contactUpcomingEvents(
    items: List<CalendarItem>,
    contactId: String,
    today: java.time.LocalDate = java.time.LocalDate.now(),
    limit: Int = 5,
): List<CalendarItem> =
    items.filter {
        it.status == CalendarItemStatus.ACTIVE &&
            it.type !in CONTACT_CARD_DATE_TYPES &&
            it.isLinkedTo(contactId) &&
            it.effectiveDate(today) >= today.toString()
    }
        .sortedBy { it.effectiveDate(today) }
        .take(limit)

/** «Подарки в календаре»: события типа «Подарок» (создаются в Календаре), связанные с контактом, по дате. */
internal fun contactCardGiftEvents(items: List<CalendarItem>, contactId: String): List<CalendarItem> =
    items.filter { it.type == CalendarItemType.GIFT && it.isLinkedTo(contactId) }.sortedBy { it.startDate }
