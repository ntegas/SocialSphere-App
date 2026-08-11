package com.aistudio.socialsphere.crmlxb.data.local

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip Entity↔domain для скалярных полей + проверка, что:
 *  - tags сериализуются в CSV-строку и обратно,
 *  - битая enum-строка в БД не роняет toDomain (safeEnum → default),
 *  - deviceContactId (поле v8) переносится.
 * Чистые функции — Room/Robolectric не нужны.
 */
class MapperTest {

    private fun domainContact() = Contact(
        id = "c1",
        firstName = "Иван",
        lastName = "Петров",
        photoUri = null,
        relationshipType = RelationshipType.OTHER,
        connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL,
        socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        nextStep = "Позвонить",
        tags = listOf("vip", "друг"),
        deviceContactId = "device_contact_42",
        createdAt = "2026-01-01T00:00",
        updatedAt = "2026-01-02T00:00"
    )

    @Test
    fun contact_roundTrip_preservesScalarsTagsAndDeviceId() {
        val back = domainContact().toEntity().toDomain()
        assertEquals("Иван", back.firstName)
        assertEquals("Петров", back.lastName)
        assertEquals(RelationshipType.OTHER, back.relationshipType)
        assertEquals(CommunicationRhythm.NOT_TRACKED, back.communicationRhythm)
        assertEquals("Позвонить", back.nextStep)
        assertEquals(listOf("vip", "друг"), back.tags) // CSV round-trip
        assertEquals("device_contact_42", back.deviceContactId)
        assertEquals("2026-01-02T00:00", back.updatedAt)
    }

    @Test
    fun contact_emptyTags_roundTripsToEmptyList() {
        val entity = domainContact().copy(tags = emptyList()).toEntity()
        assertEquals(null, entity.tags)                 // пусто → null в БД
        assertEquals(emptyList<String>(), entity.toDomain().tags)
    }

    // Регрессия (жалоба владельца: «ввёл 3-4 слова в имя — показывает только 2,
    // и не ищется»). Расследование показало: middleName сохраняется в БД верно
    // на всех путях (ручной ввод + все 3 импортёра) — данные НЕ теряются на
    // уровне persistence, теряются только при отображении в списке/гриде
    // контактов (см. SearchEngineSortTest/ContactImporterTest). Этот тест
    // закрепляет часть контракта, за которую отвечают Entities.kt/Mappers.kt:
    // третье слово имени обязано пережить round-trip Entity↔domain.
    @Test
    fun contact_roundTrip_preservesMiddleName() {
        val back = domainContact().copy(middleName = "Иванович").toEntity().toDomain()
        assertEquals("Иванович", back.middleName)
    }

    @Test
    fun contact_corruptEnumString_fallsBackToDefault() {
        // Имитация битых данных в БД (переименование enum / кривой импорт)
        val corrupt = domainContact().toEntity().copy(relationshipType = "NONEXISTENT_ENUM")
        assertEquals(RelationshipType.OTHER, corrupt.toDomain().relationshipType)
    }

    @Test
    fun company_roundTrip_preservesScalars() {
        val company = Company(
            id = "co1",
            name = "ACME",
            logoUri = null,
            industry = Industry.OTHER,
            description = "desc",
            website = "https://acme.test",
            createdAt = "2026-01-01T00:00",
            updatedAt = "2026-01-01T00:00"
        )
        val back = company.toEntity().toDomain()
        assertEquals("ACME", back.name)
        assertEquals(Industry.OTHER, back.industry)
        assertEquals("https://acme.test", back.website)
    }

    @Test
    fun calendarItem_roundTrip_andCorruptEnumFallsBack() {
        val item = CalendarItem(
            id = "e1",
            title = "Встреча",
            description = null,
            // EVENT из старого enum удалён — «Встреча» = MEETING (фикс 2026-07-02:
            // тест не компилировался и валил сборку IDE, которая собирает тест-классы)
            type = CalendarItemType.MEETING,
            startDate = "2026-01-01",
            startTime = null,
            endDate = null,
            endTime = null,
            isAllDay = true,
            status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL,
            colorKey = null,
            recurrenceRule = null,
            createdAt = "2026-01-01T00:00",
            updatedAt = "2026-01-01T00:00"
        )
        val back = item.toEntity().toDomain()
        assertEquals("Встреча", back.title)
        assertEquals(CalendarItemType.MEETING, back.type)
        assertEquals(true, back.isAllDay)

        val corrupt = item.toEntity().copy(type = "BROKEN")
        assertEquals(CalendarItemType.NOTE, corrupt.toDomain().type) // default из маппера
    }
}
