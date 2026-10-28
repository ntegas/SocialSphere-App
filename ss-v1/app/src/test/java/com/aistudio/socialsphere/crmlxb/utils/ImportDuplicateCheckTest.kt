package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Баг эмулятора (Contact UX Baseline): предпросмотр импорта показывал устаревшие галочки/чипы. Вторая половина корня —
 * проверка «уже есть в приложении» запускалась при каждом заходе на экран и затирала решения пользователя
 * (после «Поиска дублей» статусы WILL_UPDATE/SKIPPED и снятые вручную галочки возвращались к исходным).
 */
@RunWith(RobolectricTestRunner::class)
class ImportDuplicateCheckTest {

    private fun existing(id: String, first: String, last: String, phone: String? = null, email: String? = null,
                         lookup: String? = null) = Contact(
        id = id, firstName = first, lastName = last, photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = listOfNotNull(phone?.let { ContactPhone("p-$id", id, it, PhoneType.MOBILE, true) }),
        emails = listOfNotNull(email?.let { ContactEmail("e-$id", id, it, EmailType.PERSONAL, true) }),
        deviceLookupKey = lookup,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    private fun cand(id: String, first: String, last: String = "", phone: String? = null, email: String? = null,
                     lookup: String? = null) = ImportContactCandidate(
        id = id, firstName = first, lastName = last,
        phones = listOfNotNull(phone?.let { ContactPhone("cp-$id", "", it, PhoneType.MOBILE, true) }),
        emails = listOfNotNull(email?.let { ContactEmail("ce-$id", "", it, EmailType.PERSONAL, true) }),
        deviceLookupKey = lookup,
    )

    private val book = listOf(
        existing("c1", "Иван", "Петров", phone = "+7 912 345-67-89"),
        existing("c2", "Мария", "Сидорова", email = "Maria@Mail.ru"),
        existing("c3", "Олег", "Орлов", lookup = "lk-oleg"),
    )

    @Test fun firstRun_marksNewAsSelected_andDuplicatesAsUnselectedWithMatch() {
        val out = ImportDuplicateCheck.apply(listOf(
            cand("n", "Новый", "Человек", phone = "+7 900 000-00-01"),
            cand("byPhone", "Ваня", "Другой", phone = "89123456789"),
            cand("byEmail", "Маша", "Иная", email = "maria@mail.ru"),
            cand("byLookup", "Олежка", "Орлов", lookup = "lk-oleg"),
            cand("byName", "Иван", "Петров"),
        ), book).associateBy { it.id }

        assertEquals(DuplicateStatus.NEW, out.getValue("n").duplicateStatus); assertTrue(out.getValue("n").selectedForImport)
        assertEquals("c1", out.getValue("byPhone").matchedContactId)
        assertEquals("c2", out.getValue("byEmail").matchedContactId)
        assertEquals("c3", out.getValue("byLookup").matchedContactId)
        assertEquals("c1", out.getValue("byName").matchedContactId)
        listOf("byPhone", "byEmail", "byLookup", "byName").forEach {
            assertEquals(DuplicateStatus.POSSIBLE_DUPLICATE, out.getValue(it).duplicateStatus)
            assertFalse("дубль по умолчанию не отмечен: $it", out.getValue(it).selectedForImport)
        }
        assertTrue(out.values.all { it.duplicateChecked })
    }

    @Test fun secondRun_neverOverridesTheUsersDecisions() {
        val first = ImportDuplicateCheck.apply(listOf(
            cand("n1", "Новый", phone = "+7 900 000-00-01"),
            cand("n2", "Ещё", phone = "+7 900 000-00-02"),
            cand("d1", "Ваня", phone = "89123456789"),
            cand("d2", "Маша", email = "maria@mail.ru"),
        ), book)
        // решения пользователя: снял галочку у нового; в «Поиске дублей» дубль 1 → «обновить», дубль 2 → «пропустить»
        val decided = first.map {
            when (it.id) {
                "n1" -> it.copy(selectedForImport = false)
                "d1" -> it.copy(duplicateStatus = DuplicateStatus.WILL_UPDATE, selectedForImport = true)
                "d2" -> it.copy(duplicateStatus = DuplicateStatus.SKIPPED, selectedForImport = false)
                else -> it
            }
        }
        // возврат на экран предпросмотра = повторный запуск проверки (раньше это затирало всё выше)
        val again = ImportDuplicateCheck.apply(decided, book)
        assertEquals("повторный запуск ничего не меняет", decided, again)
        assertFalse(again.first { it.id == "n1" }.selectedForImport)
        assertEquals(DuplicateStatus.WILL_UPDATE, again.first { it.id == "d1" }.duplicateStatus)
        assertEquals(DuplicateStatus.SKIPPED, again.first { it.id == "d2" }.duplicateStatus)
    }

    @Test fun noMatchWhenNothingCoincides_andShortNumbersDoNotMatch() {
        assertNull(ImportDuplicateCheck.matchFor(cand("x", "Никто", "Нигде", phone = "+7 999 111-22-33"), book))
        val shortBook = listOf(existing("s", "Кто", "То", phone = "12345"))
        assertNull("номера короче 7 цифр совпадением не считаются",
            ImportDuplicateCheck.matchFor(cand("y", "Другой", "Человек", phone = "12345"), shortBook))
    }
}
