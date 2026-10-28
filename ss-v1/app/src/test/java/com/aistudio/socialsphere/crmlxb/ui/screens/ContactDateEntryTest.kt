package com.aistudio.socialsphere.crmlxb.ui.screens

import com.aistudio.socialsphere.crmlxb.data.DraftDate
import com.aistudio.socialsphere.crmlxb.model.*
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец 2026-10-28: быстрое добавление даты на карточке и в форме контакта — два разных механизма с разным
 * результатом (в форме: без напоминания, без повтора, дата любой строкой). Теперь событие собирает ОДНА функция.
 */
class ContactDateEntryTest {

    private fun build(d: NewContactDate, label: String = "Тип") =
        buildContactDateItem("c1", d, label, now = "2026-10-28T10:00", itemId = "e1")

    @Test fun birthday_isYearly_key_linkedToTheContact_withAtTimeReminder() {
        val e = build(NewContactDate(CalendarItemType.BIRTHDAY, "ДР Ивана", "1990-04-20"))
        assertEquals("FREQ=YEARLY", e.recurrenceRule)
        assertTrue(e.recurrenceExplicitlySet)
        assertEquals(ImportanceLevel.KEY, e.importance)
        assertEquals(listOf(CalendarTargetType.CONTACT to "c1"), e.links.map { it.targetType to it.targetId })
        assertEquals(listOf(ReminderType.AT_TIME), e.reminders.map { it.reminderType })
        assertTrue(e.isAllDay); assertEquals(CalendarItemStatus.ACTIVE, e.status)
        assertEquals("1990-04-20", e.startDate)
    }

    @Test fun annualTypesRepeat_otherTypesDoNot() {
        listOf(CalendarItemType.ANNIVERSARY, CalendarItemType.NAMEDAY).forEach {
            assertEquals("FREQ=YEARLY", build(NewContactDate(it, "x", "2020-01-01")).recurrenceRule)
        }
        listOf(CalendarItemType.IMPORTANT_DATE, CalendarItemType.CUSTOM).forEach {
            assertNull(build(NewContactDate(it, "x", "2020-01-01")).recurrenceRule)
            assertEquals(ImportanceLevel.NORMAL, build(NewContactDate(it, "x", "2020-01-01")).importance)
        }
    }

    @Test fun reminderChoices() {
        fun rules(r: ReminderTime) = build(NewContactDate(CalendarItemType.IMPORTANT_DATE, "x", "2026-12-01", r)).reminders
        assertTrue("«Нет» — без напоминания", rules(ReminderTime.NONE).isEmpty())
        assertEquals(ReminderType.AT_TIME, rules(ReminderTime.ON_DAY).single().reminderType)
        val d1 = rules(ReminderTime.DAY_1).single()
        assertEquals(ReminderType.BEFORE, d1.reminderType); assertEquals(1, d1.offsetValue); assertEquals(ReminderOffsetUnit.DAYS, d1.offsetUnit)
        val w1 = rules(ReminderTime.WEEK_1).single()
        assertEquals(ReminderType.BEFORE, w1.reminderType); assertEquals(1, w1.offsetValue); assertEquals(ReminderOffsetUnit.WEEKS, w1.offsetUnit)
        assertTrue("правила принадлежат событию", rules(ReminderTime.ON_DAY).all { it.calendarItemId == "e1" })
    }

    @Test fun blankTitleFallsBackToTheTypeLabel_andDateIsTrimmed() {
        val e = build(NewContactDate(CalendarItemType.NAMEDAY, "", " 2026-05-01 "), label = "Именины")
        assertEquals("Именины", e.title)
        assertEquals("2026-05-01", e.startDate)
        assertEquals("введённое название не затирается", "Моё", build(NewContactDate(CalendarItemType.NAMEDAY, "Моё", "2026-05-01")).title)
    }

    @Test fun everyDateTypeOfferedBySheetIsOneTheCardShows() {
        // шит предлагает CONTACT_CARD_DATE_TYPES; событие любого из них потом видно на карточке
        CONTACT_CARD_DATE_TYPES.forEach { t ->
            val e = build(NewContactDate(t, "x", "2026-05-01"))
            assertEquals(listOf(e.id), contactCardDates(listOf(e), "c1").map { it.id })
        }
    }

    @Test fun oldDraftOnDisk_withoutReminderField_loadsWithTheDefault() {
        val adapter = Moshi.Builder().build().adapter(DraftDate::class.java)
        val old = adapter.fromJson("""{"title":"ДР","startDate":"1990-03-12","type":"BIRTHDAY"}""")
        assertNotNull(old)
        assertEquals(ReminderTime.ON_DAY, old!!.remind)
        val round = adapter.fromJson(adapter.toJson(DraftDate("a", "2020-01-01", CalendarItemType.CUSTOM, ReminderTime.WEEK_1)))!!
        assertEquals(ReminderTime.WEEK_1, round.remind)
    }
}
