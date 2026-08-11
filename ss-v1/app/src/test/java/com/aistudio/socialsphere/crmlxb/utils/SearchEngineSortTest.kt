package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ContactDisplayPreferences (2026-07-11): sortBy (имя/фамилия) и nameFormat
 * (имя-первое/фамилия-первое) — два независимых измерения, как в реальном
 * Android-контактах (ContactsContract.Preferences SORT_ORDER vs DISPLAY_ORDER).
 * Чистые функции — AppStateStore не трогаем (query/фильтры пустые, значит
 * applyContactFilters вообще не обращается к стору).
 */
class SearchEngineSortTest {

    private fun contact(id: String, firstName: String, lastName: String, middleName: String? = null) = Contact(
        id = id,
        firstName = firstName,
        lastName = lastName,
        middleName = middleName,
        photoUri = null,
        relationshipType = RelationshipType.OTHER,
        connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL,
        socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        createdAt = "2026-01-01T00:00",
        updatedAt = "2026-01-01T00:00"
    )

    @Test
    fun applyContactFilters_sortByFirstName_ordersByGivenNamePrimary() {
        val list = listOf(
            contact("1", "Борис", "Аксёнов"),
            contact("2", "Анна", "Яковлева")
        )
        val sorted = list.applyContactFilters(
            query = "", relationshipTypes = emptySet(), importanceLevels = emptySet(),
            communicationRhythms = emptySet(), cityFilter = "",
            sortOrder = ContactSortOrder.NAME_AZ, nameSortField = ContactSortField.FIRST_NAME
        )
        // По имени: Анна раньше Бориса, несмотря на то что по фамилии Аксёнов раньше Яковлевой.
        assertEquals(listOf("2", "1"), sorted.map { it.id })
    }

    @Test
    fun applyContactFilters_sortByLastName_ordersByFamilyNamePrimary() {
        val list = listOf(
            contact("1", "Борис", "Аксёнов"),
            contact("2", "Анна", "Яковлева")
        )
        val sorted = list.applyContactFilters(
            query = "", relationshipTypes = emptySet(), importanceLevels = emptySet(),
            communicationRhythms = emptySet(), cityFilter = "",
            sortOrder = ContactSortOrder.NAME_AZ, nameSortField = ContactSortField.LAST_NAME
        )
        // По фамилии: Аксёнов раньше Яковлевой — обратный порядок относительно теста выше.
        assertEquals(listOf("1", "2"), sorted.map { it.id })
    }

    @Test
    fun applyContactFilters_defaultsToFirstNameSort_whenFieldOmitted() {
        val list = listOf(
            contact("1", "Борис", "Аксёнов"),
            contact("2", "Анна", "Яковлева")
        )
        // Старые вызовы без nameSortField не должны ломаться и обязаны сохранить
        // прежнее поведение (сортировка по имени).
        val sorted = list.applyContactFilters(
            query = "", relationshipTypes = emptySet(), importanceLevels = emptySet(),
            communicationRhythms = emptySet(), cityFilter = "",
            sortOrder = ContactSortOrder.NAME_AZ
        )
        assertEquals(listOf("2", "1"), sorted.map { it.id })
    }

    @Test
    fun contactSortLetter_usesFieldMatchingSortSetting() {
        val c = contact("1", "Борис", "Аксёнов")
        assertEquals("Б", contactSortLetter(c, ContactSortField.FIRST_NAME))
        assertEquals("А", contactSortLetter(c, ContactSortField.LAST_NAME))
    }

    @Test
    fun contactSortLetter_fallsBackToOtherField_whenPrimaryBlank() {
        val c = contact("1", "", "Аксёнов")
        assertEquals("А", contactSortLetter(c, ContactSortField.FIRST_NAME))
    }

    @Test
    fun formatContactName_firstNameFirst_putsGivenNameFirst() {
        val c = contact("1", "Борис", "Аксёнов")
        assertEquals("Борис Аксёнов", formatContactName(c, ContactNameFormat.FIRST_NAME_FIRST))
    }

    @Test
    fun formatContactName_lastNameFirst_putsFamilyNameFirst() {
        val c = contact("1", "Борис", "Аксёнов")
        assertEquals("Аксёнов Борис", formatContactName(c, ContactNameFormat.LAST_NAME_FIRST))
    }

    // Регрессия (жалоба владельца: «ввёл 3-4 слова в имя — показывает только 2»).
    // Корень был найден в этой самой функции: formatContactName() полностью
    // игнорировала middleName в обеих ветках формата — контакт с корректно
    // сохранённым отчеством всё равно показывал в списке/гриде контактов
    // (ContactsScreen.kt: ContactListCard/ContactGridCard) только имя+фамилию.
    @Test
    fun formatContactName_firstNameFirst_includesMiddleName() {
        val c = contact("1", "Борис", "Аксёнов", middleName = "Иванович")
        assertEquals("Борис Иванович Аксёнов", formatContactName(c, ContactNameFormat.FIRST_NAME_FIRST))
    }

    @Test
    fun formatContactName_lastNameFirst_includesMiddleName() {
        val c = contact("1", "Борис", "Аксёнов", middleName = "Иванович")
        assertEquals("Аксёнов Борис Иванович", formatContactName(c, ContactNameFormat.LAST_NAME_FIRST))
    }

    @Test
    fun formatContactName_blankOrNullMiddleName_isOmittedWithoutExtraSpaces() {
        val c = contact("1", "Борис", "Аксёнов", middleName = "")
        assertEquals("Борис Аксёнов", formatContactName(c, ContactNameFormat.FIRST_NAME_FIRST))
    }
}
