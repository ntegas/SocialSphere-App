package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Дедупликация телефонов по последним 10 цифрам: разные форматы одного и того
 * же номера (+7…, 8…, с пробелами/скобками/дефисами) должны схлопываться
 * в один при поиске дублей контактов ([AppStateStore.findDuplicatePairs]).
 *
 * phoneDigits() сделан internal (не private) ради этого теста — прод-видимость
 * снаружи модуля не меняется (тот же паттерн, что миграции в MigrationTest).
 *
 * Чистый JVM-тест: не трогаем DB (AppStateStore.database остаётся null),
 * используем только in-memory contacts-список — findDuplicatePairs() его
 * не персистит, поэтому Robolectric не нужен.
 */
class PhoneDedupeTest {

    @Before
    fun clearContacts() = AppStateStore.contacts.clear()

    @After
    fun cleanup() = AppStateStore.contacts.clear()

    private fun contact(id: String, phone: String?) = Contact(
        id = id, firstName = "Тест$id", lastName = "Тестов", photoUri = null,
        relationshipType = RelationshipType.ACQUAINTANCE, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = phone?.let { listOf(ContactPhone(id = "${id}_p", contactId = id, number = it, type = PhoneType.MOBILE, isPrimary = true)) } ?: emptyList(),
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    // ─── phoneDigits(): нормализация форматов ───

    @Test
    fun phoneDigits_variousFormats_collapseToSameSuffix() {
        val variants = listOf(
            "+7 900 111-22-33",
            "8 900 111 22 33",
            "8(900)111-22-33",
            "+7(900) 111-22-33",
            "89001112233",
            "  +7 900 111 22 33  ",
        )
        val normalized = variants.map { AppStateStore.phoneDigits(it) }.toSet()
        // Последние 10 цифр одинаковы для всех форматов, несмотря на разные
        // префиксы (+7 vs 8), пробелы, скобки, дефисы
        assertEquals("Все варианты должны схлопнуться в один нормализованный номер: $normalized",
            1, normalized.size)
        assertEquals("9001112233", normalized.first())
    }

    @Test
    fun phoneDigits_differentNumbers_stayDistinct() {
        val a = AppStateStore.phoneDigits("+7 900 111-22-33")
        val b = AppStateStore.phoneDigits("+7 900 999-88-77")
        assertNotEquals(a, b)
    }

    @Test
    fun phoneDigits_blankOrLettersOnly_returnsEmpty() {
        assertEquals("", AppStateStore.phoneDigits(""))
        assertEquals("", AppStateStore.phoneDigits("не указан"))
    }

    @Test
    fun phoneDigits_shortNumber_keepsAllDigits() {
        // Меньше 10 цифр — takeLast(10) просто возвращает всё, что есть
        assertEquals("12345", AppStateStore.phoneDigits("123-45"))
    }

    // ─── findDuplicatePairs(): контакты с «одинаковым» номером в разных форматах ───

    @Test
    fun findDuplicatePairs_samePhoneDifferentFormats_isDetected() {
        AppStateStore.contacts.add(contact("c1", "+7 900 111-22-33"))
        AppStateStore.contacts.add(contact("c2", "8 (900) 111-22-33"))

        val pairs = AppStateStore.findDuplicatePairs()

        assertEquals(1, pairs.size)
        val ids = setOf(pairs[0].first.id, pairs[0].second.id)
        assertEquals(setOf("c1", "c2"), ids)
    }

    @Test
    fun findDuplicatePairs_differentPhones_notFlagged() {
        AppStateStore.contacts.add(contact("c1", "+7 900 111-22-33"))
        AppStateStore.contacts.add(contact("c2", "+7 900 999-88-77"))

        assertTrue(AppStateStore.findDuplicatePairs().isEmpty())
    }

    @Test
    fun findDuplicatePairs_noPhoneAtAll_notFlagged() {
        // Контакты без телефона (null) не должны ложно совпадать друг с другом
        // по «пустому» нормализованному номеру
        AppStateStore.contacts.add(contact("c1", null))
        AppStateStore.contacts.add(contact("c2", null))

        assertTrue(AppStateStore.findDuplicatePairs().isEmpty())
    }

    @Test
    fun findDuplicatePairs_shortInvalidNumbers_notFalselyMatched() {
        // Короткие «мусорные» номера (< 7 цифр) не считаются дублями —
        // иначе два контакта с добавочными "12" и "12" ложно совпали бы
        AppStateStore.contacts.add(contact("c1", "12"))
        AppStateStore.contacts.add(contact("c2", "12"))

        assertTrue(AppStateStore.findDuplicatePairs().isEmpty())
    }

    @Test
    fun findDuplicatePairs_threeContactsSamePhone_allPairsDetected() {
        AppStateStore.contacts.add(contact("c1", "+7 900 111-22-33"))
        AppStateStore.contacts.add(contact("c2", "8 900 111 22 33"))
        AppStateStore.contacts.add(contact("c3", "89001112233"))

        val pairs = AppStateStore.findDuplicatePairs()

        // C(3,2) = 3 пары, без повторов и без «сам с собой»
        assertEquals(3, pairs.size)
        pairs.forEach { assertNotEquals(it.first.id, it.second.id) }
    }
}
