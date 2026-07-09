package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Round-trip тест JSON-бэкапа: export → import должен сохранять КАЖДОЕ поле
 * без потерь (структурное равенство data class'ов). Проверяет саму границу
 * сериализации (backupAdapter.toJson → parseJsonBackup), без прогона через
 * живой AppStateStore — сериализуем BackupData напрямую.
 *
 * backupAdapter сделан internal ради этого теста (тот же паттерн, что
 * MIGRATION_x_y/phoneDigits — прод-видимость не меняется, тест в том же модуле).
 */
class ExportManagerTest {

    private fun fullContact(id: String = "c1") = Contact(
        id = id,
        firstName = "Иван",
        lastName = "Петров",
        middleName = "Сергеевич",
        nickname = "Ваня",
        namePrefix = "г-н",
        nameSuffix = "мл.",
        phoneticFirstName = "Ee-VAHN",
        phoneticLastName = "peh-TROV",
        photoUri = "/data/photos/${id}_123.jpg",
        relationshipType = RelationshipType.FRIEND,
        customRelationshipType = "Кум",
        connectionLevel = ConnectionLevel.CLOSE,
        importanceLevel = ImportanceLevel.KEY,
        socialRole = SocialRole.ADVISOR,
        communicationRhythm = CommunicationRhythm.MONTHLY,
        contactStatus = ContactStatus.MAINTAIN,
        lastContactDate = "2026-06-01",
        nextStep = "Позвонить насчёт дачи",
        familyNote = "Сын Петя, 2019 г.р., телефона нет",
        profession = "Электрик",
        tags = listOf("важное", "семья"),
        canHelpWith = "Ремонт",
        iCanHelpWith = "Юр. консультации",
        talkingPoints = "Спросить про отпуск",
        meetContext = "На свадьбе у Кости",
        meetDate = "2015-08-20",
        deviceContactId = "device_contact_$id",
        companyRelations = listOf(
            ContactCompanyRelation(
                id = "cr1", contactId = id, companyId = "co1",
                position = "Инженер", department = "R&D", role = "Lead",
                employmentStatus = EmploymentStatus.CURRENT,
                startDate = "2020-01-01", endDate = null,
                responsibilities = "Бэкенд", managedAccounts = "Acme, Globex",
                workNote = "Гибкий график", officeAddressId = "a2", isPrimary = true
            )
        ),
        phones = listOf(
            ContactPhone(id = "p1", contactId = id, number = "+7 900 111-22-33", type = PhoneType.MOBILE, isPrimary = true, comment = "Основной"),
            ContactPhone(id = "p2", contactId = id, number = "8 (900) 444-55-66", type = PhoneType.WORK, isPrimary = false)
        ),
        emails = listOf(ContactEmail(id = "e1", contactId = id, email = "ivan@example.com", type = EmailType.PERSONAL, isPrimary = true)),
        messengers = listOf(Messenger(id = "m1", contactId = id, type = MessengerType.TELEGRAM, value = "@ivan", link = "https://t.me/ivan", isPrimary = true, comment = null)),
        addresses = listOf(
            Address(id = "a1", ownerType = AddressOwnerType.CONTACT, ownerId = id, addressType = AddressType.HOME,
                addressLine = "ул. Ленина, 1", city = "Афины", country = "Греция",
                comment = "Домофон 12", latitude = 37.9838, longitude = 23.7275, postalCode = "10557")
        ),
        notes = listOf(
            Note(id = "n1", contactId = id, companyId = null, calendarItemId = null, giftId = null,
                type = NoteType.IMPORTANT_TO_REMEMBER, text = "Аллергия на орехи ⚠️", date = "2026-01-01",
                isImportant = true, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        ),
        gifts = listOf(
            GiftIdea(id = "g1", contactId = id, title = "Книга", note = "Фантастика", link = "https://example.com",
                date = "2026-12-25", reminderId = null, status = GiftStatus.IDEA)
        ),
        sizeInfo = SizeInfo(id = "s1", contactId = id, clothingSize = "L", shoeSize = "42", ringSize = null, other = "Кепка 58"),
        personalDetails = listOf(
            PersonalDetail(id = "pd1", contactId = id, category = PersonalDetailCategory.ALLERGIES, value = "Орехи", note = "Тяжёлая реакция")
        ),
        createdAt = "2025-01-01T00:00",
        updatedAt = "2026-06-01T12:34"
    )

    /** Контакт с максимумом null/пустых полей — вторая половина edge-пространства. */
    private fun minimalContact(id: String = "c2") = Contact(
        id = id,
        firstName = "",
        lastName = "",
        photoUri = null,
        relationshipType = RelationshipType.ACQUAINTANCE,
        connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL,
        socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        createdAt = "2026-01-01T00:00",
        updatedAt = "2026-01-01T00:00"
    )

    private fun company(id: String = "co1") = Company(
        id = id, name = "ACME & Co «Ромашка»", logoUri = null, industry = Industry.CONSTRUCTION,
        description = "Строительная компания", website = "https://acme.example",
        phones = listOf(ContactPhone(id = "cp1", contactId = id, number = "+30 210 1234567", type = PhoneType.WORK, isPrimary = true)),
        emails = emptyList(),
        addresses = listOf(Address(id = "ca1", ownerType = AddressOwnerType.COMPANY, ownerId = id, addressType = AddressType.OFFICE,
            addressLine = "Проспект, 5", city = "Салоники", country = "Греция")),
        createdAt = "2024-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun calendarItem(id: String = "e1") = CalendarItem(
        id = id, title = "День рождения: Иван", description = "Не забыть подарок",
        type = CalendarItemType.BIRTHDAY, startDate = "--06-15", startTime = null,
        endDate = null, endTime = null, isAllDay = true, status = CalendarItemStatus.ACTIVE,
        importance = ImportanceLevel.IMPORTANT, colorKey = "gold", recurrenceRule = "FREQ=YEARLY",
        links = listOf(CalendarItemLink(id = "l1", calendarItemId = id, targetType = CalendarTargetType.CONTACT, targetId = "c1")),
        reminders = listOf(ReminderRule(id = "r1", calendarItemId = id, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.DAYS, exactDateTime = null)),
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun relation(id: String = "rel1") = ContactRelation(
        id = id, firstContactId = "c1", secondContactId = "c2",
        firstRole = "Отец", secondRole = "Сын", note = "Со стороны матери"
    )

    private fun group(id: String = "grp1") = ContactGroup(
        id = id, name = "Клиенты «Афины»", createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun groupMember(id: String = "gm1") = ContactGroupMember(id = id, groupId = "grp1", contactId = "c1")

    // ─── Happy path: полный снимок со всеми сущностями и вложенными полями ───

    @Test
    fun roundTrip_fullBackup_preservesEveryField() {
        val original = BackupData(
            version = 4,
            exportedAt = "2026-07-04T10:00",
            contacts = listOf(fullContact("c1"), fullContact("c2")),
            companies = listOf(company("co1")),
            calendarItems = listOf(calendarItem("e1")),
            contactRelations = listOf(relation("rel1")),
            groups = listOf(group("grp1")),
            groupMembers = listOf(groupMember("gm1"))
        )

        val json = ExportManager.backupAdapter.toJson(original)
        val restored = ExportManager.parseJsonBackup(json)

        assertNotNull(restored)
        // Структурное равенство целиком — если бы Moshi/адаптер потерял хоть одно
        // поле (например забыли добавить в data class), assertEquals бы упал.
        assertEquals(original, restored)
    }

    @Test
    fun roundTrip_minimalAndNullHeavyContact_preservesBlanksAndNulls() {
        val original = BackupData(
            contacts = listOf(minimalContact("c2")),
            companies = emptyList(),
            calendarItems = emptyList(),
            contactRelations = emptyList()
        )

        val json = ExportManager.backupAdapter.toJson(original)
        val restored = ExportManager.parseJsonBackup(json)

        assertEquals(original, restored)
        assertNull(restored!!.contacts.single().photoUri)
        assertNull(restored.contacts.single().sizeInfo)
        assertTrue(restored.contacts.single().phones.isEmpty())
        assertEquals("", restored.contacts.single().firstName)
    }

    @Test
    fun roundTrip_unicodeAndSpecialCharacters_areNotMangled() {
        val c = fullContact("c1").copy(
            firstName = "Ξένια",                       // греческий
            lastName = "O'Brien-Müller",                // апостроф, дефис, умляут
            nickname = "😊 «Кекс»",                      // эмодзи + кавычки-ёлочки
            tags = listOf("day\"quote", "line\nbreak", "tab\there")
        )
        val original = BackupData(contacts = listOf(c))

        val json = ExportManager.backupAdapter.toJson(original)
        val restored = ExportManager.parseJsonBackup(json)

        assertEquals(original, restored)
    }

    @Test
    fun roundTrip_emptyBackup_parsesToEmptyLists() {
        val original = BackupData()
        val json = ExportManager.backupAdapter.toJson(original)
        val restored = ExportManager.parseJsonBackup(json)

        assertEquals(original, restored)
        assertTrue(restored!!.contacts.isEmpty())
        assertTrue(restored.groups.isEmpty())
    }

    // ─── Edge cases: битые/чужеродные данные ───

    @Test
    fun parseJsonBackup_malformedJson_returnsNull() {
        assertNull(ExportManager.parseJsonBackup("{not valid json"))
        assertNull(ExportManager.parseJsonBackup(""))
        assertNull(ExportManager.parseJsonBackup("null"))
    }

    @Test
    fun parseJsonBackup_unrelatedJsonObject_doesNotCrash() {
        // Валидный JSON, но не бэкап SocialSphere — Moshi может распарсить
        // частично (все поля возьмут дефолты) или дать null; главное — не падает.
        val foreign = """{"foo": "bar", "baz": 42}"""
        val result = ExportManager.parseJsonBackup(foreign)
        // Либо null, либо пустой BackupData с дефолтной version=4 — оба ок,
        // лишь бы не бросило исключение (проверено самим фактом дойти до assert).
        if (result != null) assertTrue(result.contacts.isEmpty())
    }

    @Test
    fun importJsonBackup_versionOutOfRange_isRejected() {
        val tooNew = BackupData(version = 99, contacts = listOf(minimalContact("c9")))
        val json = ExportManager.backupAdapter.toJson(tooNew)
        assertEquals(-1, ExportManager.importJsonBackup(json))

        val tooOld = BackupData(version = 0, contacts = listOf(minimalContact("c9")))
        val jsonOld = ExportManager.backupAdapter.toJson(tooOld)
        assertEquals(-1, ExportManager.importJsonBackup(jsonOld))
    }
}
