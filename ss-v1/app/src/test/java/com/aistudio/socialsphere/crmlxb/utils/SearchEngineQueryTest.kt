package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Регрессия (жалоба владельца: «ввёл 3-4 слова в имя — показывает только 2,
 * и не ищется»). Расследование: SearchEngine.scoreContact() middleName
 * УЧИТЫВАЕТ (и в общем fullName, и отдельной веткой) — основной поиск на
 * ContactsScreen находит контакт по отчеству корректно. Баг «не ищется» был
 * найден в ДРУГОМ месте (локальный предикат пикера в CommunicationTab.kt),
 * не в главном поиске. Тест ниже закрепляет СЕГОДНЯШНЕЕ верное поведение
 * главного поиска как baseline — если middleName-ветка когда-нибудь исчезнет
 * из scoreContact(), тест обязан упасть.
 *
 * Чистый JVM-тест: AppStateStore.database остаётся null, используем только
 * in-memory AppStateStore.contacts (тот же паттерн, что PhoneDedupeTest).
 */
class SearchEngineQueryTest {

    @Before
    fun clearContacts() = AppStateStore.contacts.clear()

    @After
    fun cleanup() = AppStateStore.contacts.clear()

    private fun contact(id: String, firstName: String, lastName: String, middleName: String?) = Contact(
        id = id, firstName = firstName, lastName = lastName, middleName = middleName,
        photoUri = null, relationshipType = RelationshipType.OTHER,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL,
        socialRole = SocialRole.REGULAR, communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    @Test
    fun searchContacts_findsContactByMiddleNameWord() {
        AppStateStore.contacts.add(contact("c1", "Иван", "Сидоров", middleName = "Петрович"))

        val results = SearchEngine.searchContacts("Петрович")

        assertEquals(1, results.size)
        assertEquals("c1", results.single().contact.id)
    }

    @Test
    fun applyContactFilters_textQuery_findsContactByMiddleNameWord() {
        AppStateStore.contacts.add(contact("c1", "Иван", "Сидоров", middleName = "Петрович"))
        val list = listOf(AppStateStore.contacts.single())

        val filtered = list.applyContactFilters(
            query = "Петрович", relationshipTypes = emptySet(), importanceLevels = emptySet(),
            communicationRhythms = emptySet(), cityFilter = "",
            sortOrder = ContactSortOrder.NAME_AZ
        )

        assertEquals(listOf("c1"), filtered.map { it.id })
    }

    @Test
    fun searchContacts_middleNameOfOtherContact_doesNotMatch() {
        AppStateStore.contacts.add(contact("c1", "Иван", "Сидоров", middleName = "Петрович"))
        AppStateStore.contacts.add(contact("c2", "Олег", "Кузнецов", middleName = null))

        val results = SearchEngine.searchContacts("Петрович")

        assertEquals(listOf("c1"), results.map { it.contact.id })
    }
}
