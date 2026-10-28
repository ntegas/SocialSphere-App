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

    /**
     * Регрессия (аудит фильтрации 2026-08-30): scoreContact() искал теги ТОЛЬКО
     * через легаси Contact.tags (свободный текст) — форма редактирования
     * контакта не пишет в это поле с 2026-08-18 (BATCH 5C: сама функция
     * AppStateStore.allTags() с тех пор удалена вместе с легаси-секцией
     * фильтра), все новые теги идут только через управляемую систему
     * Tag/tagMembers. Тег, добавленный актуальным UI «+ Тег», был виден в
     * фильтре (ContactFilterSheet.tagIds), но не находился ПОИСКОМ вообще —
     * тот же класс бага, что §55 SOCIALSPHERE_KNOWLEDGE.md, всплывший в поиске.
     */
    @Test
    fun searchContacts_findsContactByManagedTagName() {
        AppStateStore.contacts.add(contact("c1", "Иван", "Сидоров", middleName = null))
        val tag = Tag(id = "t1", name = "ВИП-клиент", category = null, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        AppStateStore.tags.add(tag)
        AppStateStore.tagMembers.add(ContactTagMember(id = "tm1", tagId = tag.id, contactId = "c1"))

        val results = SearchEngine.searchContacts("ВИП-клиент")

        assertEquals(listOf("c1"), results.map { it.contact.id })

        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
    }

    /**
     * BATCH 5C (5C-001, regression A). Управляемый тег обязан фильтровать
     * контакт даже когда Contact.tags пуст — до этого батча фильтр уже не
     * зависел от легаси-поля (contactIdsWithTag читает только tagMembers), но
     * это не было закреплено тестом на самой applyContactFilters().
     */
    @Test
    fun applyContactFilters_managedTagId_matchesContact_withEmptyLegacyTags() {
        AppStateStore.contacts.add(contact("c1", "Иван", "Сидоров", middleName = null))
        val tag = Tag(id = "t1", name = "VIP", category = null, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        AppStateStore.tags.add(tag)
        AppStateStore.tagMembers.add(ContactTagMember(id = "tm1", tagId = tag.id, contactId = "c1"))

        val filtered = AppStateStore.contacts.applyContactFilters(
            query = "", relationshipTypes = emptySet(), importanceLevels = emptySet(),
            communicationRhythms = emptySet(), cityFilter = "",
            tagIds = setOf(tag.id),
            sortOrder = ContactSortOrder.NAME_AZ
        )

        assertEquals(listOf("c1"), filtered.map { it.id })
        assertTrue("предусловие теста: легаси-поле контакта пусто", AppStateStore.contacts.single().tags.isEmpty())
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
    }

    /**
     * BATCH 5C (5C-001, regression B). Не должно остаться независимого legacy
     * tag-filtering пути: контакт, у которого «VIP» есть ТОЛЬКО в легаси
     * Contact.tags (не реконсилировано, нет ContactTagMember), не обязан
     * совпадать с фильтром по управляемому тегу с тем же именем — единственный
     * способ пройти фильтр теперь managed membership, текстовое совпадение по
     * Contact.tags параметра tagFilter в applyContactFilters больше не
     * существует вовсе (удалён вместе с сигнатурой).
     */
    @Test
    fun applyContactFilters_managedTagId_ignoresUnreconciledLegacyContactTags() {
        AppStateStore.contacts.add(
            contact("c1", "Иван", "Сидоров", middleName = null).copy(tags = listOf("VIP"))
        )
        val tag = Tag(id = "t1", name = "VIP", category = null, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        AppStateStore.tags.add(tag) // управляемый тег существует, но у c1 нет membership

        val filtered = AppStateStore.contacts.applyContactFilters(
            query = "", relationshipTypes = emptySet(), importanceLevels = emptySet(),
            communicationRhythms = emptySet(), cityFilter = "",
            tagIds = setOf(tag.id),
            sortOrder = ContactSortOrder.NAME_AZ
        )

        assertTrue("легаси-поле не должно давать контакту пройти managed-фильтр", filtered.isEmpty())
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
    }

    /**
     * Регрессия (аудит фильтрации 2026-08-30): applyCompanyFilters() не
     * нормализовал диакритику в фильтре по городу — тот же фикс «José не
     * находится по jose» (2026-07-12), уже применённый к applyContactFilters,
     * пропущен для компаний без задокументированной причины.
     */
    @Test
    fun applyCompanyFilters_cityFilter_ignoresDiacritics() {
        val company = Company(
            id = "co1", name = "Test Co", industry = Industry.OTHER,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        AppStateStore.companies.add(company)
        AppStateStore.addresses.add(
            Address(
                id = "a1", ownerType = AddressOwnerType.COMPANY, ownerId = "co1",
                addressType = AddressType.OFFICE, addressLine = "Calle 1",
                city = "São Paulo", country = "Brazil"
            )
        )

        val filtered = listOf(company).applyCompanyFilters(
            query = "", industries = emptySet(), cityFilter = "sao paulo",
            sortOrder = CompanySortOrder.NAME_AZ
        )

        assertEquals(listOf("co1"), filtered.map { it.id })

        AppStateStore.companies.clear(); AppStateStore.addresses.clear()
    }
}
