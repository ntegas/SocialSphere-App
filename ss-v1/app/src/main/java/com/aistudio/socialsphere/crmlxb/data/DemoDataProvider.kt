package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*

object DemoDataProvider {
    val companies = listOf(
        Company(
            id = "comp1", name = "Aegean Airlines", industry = Industry.OTHER,
            createdAt = "2023-01-01", updatedAt = "2023-01-01"
        ),
        Company(
            id = "comp2", name = "Yandex", industry = Industry.IT,
            createdAt = "2023-01-01", updatedAt = "2023-01-01"
        ),
        Company(
            id = "comp3", name = "Google", industry = Industry.IT,
            createdAt = "2023-01-01", updatedAt = "2023-01-01"
        )
    )

    val companyRelations = listOf(
        ContactCompanyRelation(
            id = "rel1", contactId = "c1", companyId = "comp1", position = "Директор по маркетингу",
            employmentStatus = EmploymentStatus.CURRENT, isPrimary = true
        ),
        ContactCompanyRelation(
            id = "rel2", contactId = "c2", companyId = "comp2", position = "PM",
            employmentStatus = EmploymentStatus.CURRENT, isPrimary = true
        ),
        ContactCompanyRelation(
            id = "rel3", contactId = "c3", companyId = "comp3", position = "HR",
            employmentStatus = EmploymentStatus.CURRENT, isPrimary = true
        )
    )

    val contacts = listOf(
        Contact(
            id = "c1", firstName = "Мария", lastName = "Папас", photoUri = null,
            relationshipType = RelationshipType.CLIENT, connectionLevel = ConnectionLevel.CLOSE,
            importanceLevel = ImportanceLevel.KEY, socialRole = SocialRole.REGULAR, communicationRhythm = CommunicationRhythm.WEEKLY,
            companyRelations = companyRelations.filter { it.contactId == "c1" },
            createdAt = "2023-05-01", updatedAt = "2023-05-01"
        ),
        Contact(
            id = "c2", firstName = "Иван", lastName = "Петров", photoUri = null,
            relationshipType = RelationshipType.COLLEAGUE, connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.IMPORTANT, socialRole = SocialRole.EXPERT, communicationRhythm = CommunicationRhythm.MONTHLY,
            companyRelations = companyRelations.filter { it.contactId == "c2" },
            createdAt = "2023-05-02", updatedAt = "2023-05-05"
        ),
        Contact(
            id = "c3", firstName = "Джейн", lastName = "Купер", photoUri = null,
            relationshipType = RelationshipType.PARTNER, connectionLevel = ConnectionLevel.WEAK,
            importanceLevel = ImportanceLevel.IMPORTANT, socialRole = SocialRole.CONNECTOR, communicationRhythm = CommunicationRhythm.EVERY_3_MONTHS,
            companyRelations = companyRelations.filter { it.contactId == "c3" },
            createdAt = "2023-05-03", updatedAt = "2023-05-06"
        ),
        Contact(
            id = "c4", firstName = "Алекс", lastName = "Ривера", photoUri = null,
            relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.CLOSE,
            importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR, communicationRhythm = CommunicationRhythm.NOT_TRACKED,
            createdAt = "2023-05-04", updatedAt = "2023-05-04"
        ),
        Contact(
            id = "c5", firstName = "Анна", lastName = "Сидорова", photoUri = null,
            relationshipType = RelationshipType.FAMILY, connectionLevel = ConnectionLevel.CLOSE,
            importanceLevel = ImportanceLevel.KEY, socialRole = SocialRole.REGULAR, communicationRhythm = CommunicationRhythm.WEEKLY,
            createdAt = "2023-05-05", updatedAt = "2023-05-07"
        )
    )

    val contactRelations = listOf(
        ContactRelation(id = "cr1", firstContactId = "c2", secondContactId = "c5", firstRole = "Муж", secondRole = "Жена"),
        ContactRelation(id = "cr2", firstContactId = "c1", secondContactId = "c3", firstRole = "Коллега", secondRole = "Коллега"),
        ContactRelation(id = "cr3", firstContactId = "c4", secondContactId = "c1", firstRole = "Друг", secondRole = "Друг")
    )

    val addresses = listOf(
        Address(id = "addr1", ownerType = AddressOwnerType.CONTACT, ownerId = "c1", addressType = AddressType.HOME, addressLine = "Глифада", city = "Афины", country = "Греция", latitude = 0.35, longitude = 0.42),
        Address(id = "addr2", ownerType = AddressOwnerType.CONTACT, ownerId = "c2", addressType = AddressType.WORK, addressLine = "Центр", city = "Белград", country = "Сербия", latitude = 0.72, longitude = 0.28),
        Address(id = "addr3", ownerType = AddressOwnerType.COMPANY, ownerId = "comp1", addressType = AddressType.OFFICE, addressLine = "Коропи", city = "Афины", country = "Греция", latitude = 0.55, longitude = 0.65),
        Address(id = "addr4", ownerType = AddressOwnerType.COMPANY, ownerId = "comp3", addressType = AddressType.OFFICE, addressLine = "Л-Авеню", city = "Маунтин-Вью", country = "США", latitude = 0.2, longitude = 0.78),
        Address(id = "addr5", ownerType = AddressOwnerType.CONTACT, ownerId = "c4", addressType = AddressType.OTHER, addressLine = "Квартира", city = "Лиссабон", country = "Португалия", latitude = 0.8, longitude = 0.8)
    )

    val calendarItems = listOf(
        CalendarItem(
            id = "cal1", title = "День рождения", type = CalendarItemType.BIRTHDAY, startDate = "30 мая", isAllDay = true, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.KEY,
            links = listOf(CalendarItemLink("lnk1", "cal1", CalendarTargetType.CONTACT, "c1")), createdAt = "2023-01-01", updatedAt = "2023-01-01"
        ),
        CalendarItem(
            id = "cal2", title = "Звонок", type = CalendarItemType.CALL, startDate = "31 мая", startTime = "11:00", isAllDay = false, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink("lnk2", "cal2", CalendarTargetType.CONTACT, "c2")), createdAt = "2023-01-01", updatedAt = "2023-01-01"
        ),
        CalendarItem(
            id = "cal3", title = "Встреча", type = CalendarItemType.MEETING, startDate = "02 июня", startTime = "15:00", isAllDay = false, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.IMPORTANT,
            links = listOf(CalendarItemLink("lnk3", "cal3", CalendarTargetType.CONTACT, "c3")), createdAt = "2023-01-01", updatedAt = "2023-01-01"
        ),
        CalendarItem(
            id = "cal4", title = "Подарок", type = CalendarItemType.GIFT, startDate = "05 июня", isAllDay = true, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink("lnk4", "cal4", CalendarTargetType.CONTACT, "c4")), createdAt = "2023-01-01", updatedAt = "2023-01-01"
        ),
        CalendarItem(
            id = "cal5", title = "Годовщина компании", type = CalendarItemType.COMPANY_EVENT, startDate = "10 июня", isAllDay = true, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
            links = listOf(CalendarItemLink("lnk5", "cal5", CalendarTargetType.COMPANY, "comp1")), createdAt = "2023-01-01", updatedAt = "2023-01-01"
        )
    )

    val notes = listOf(
        Note(id = "n1", contactId = "c1", type = NoteType.IMPORTANT_TO_REMEMBER, text = "Любит кофе", isImportant = true, createdAt = "2023-01-01", updatedAt = "2023-01-01"),
        Note(id = "n2", contactId = "c2", type = NoteType.WORK, text = "Завершает проект интеграции", isImportant = false, createdAt = "2023-01-01", updatedAt = "2023-01-01"),
        Note(id = "n3", companyId = "comp1", type = NoteType.GENERAL, text = "Спонсоры выставки", isImportant = false, createdAt = "2023-01-01", updatedAt = "2023-01-01"),
        Note(id = "n4", contactId = "c3", type = NoteType.PERSONAL_DETAIL, text = "Увлекается яхтингом", isImportant = true, createdAt = "2023-01-01", updatedAt = "2023-01-01"),
        Note(id = "n5", contactId = "c4", type = NoteType.GIFT, text = "Хочет книгу по дизайну", isImportant = false, createdAt = "2023-01-01", updatedAt = "2023-01-01")
    )

    val gifts = listOf(
        GiftIdea(id = "g1", contactId = "c4", title = "Книга по системному дизайну", status = GiftStatus.IDEA),
        GiftIdea(id = "g2", contactId = "c1", title = "Цветы", status = GiftStatus.IDEA),
        GiftIdea(id = "g3", contactId = "c2", title = "Кофемашина", status = GiftStatus.IDEA)
    )

    val sizeInfos = listOf(
        SizeInfo(id = "s1", contactId = "c1", clothingSize = "M", shoeSize = "39"),
        SizeInfo(id = "s2", contactId = "c2", clothingSize = "L", shoeSize = "43"),
        SizeInfo(id = "s3", contactId = "c3", ringSize = "16.5")
    )

    val personalDetails = listOf(
        PersonalDetail(id = "pd1", contactId = "c1", category = PersonalDetailCategory.DRINKS, value = "Кофе без кофеина"),
        PersonalDetail(id = "pd2", contactId = "c2", category = PersonalDetailCategory.INTERESTS, value = "Сноуборд"),
        PersonalDetail(id = "pd3", contactId = "c5", category = PersonalDetailCategory.FOOD, value = "Вегетарианец")
    )

    fun getCompany(id: String): Company? = companies.find { it.id == id }
    fun getContact(id: String): Contact? = contacts.find { it.id == id }
}
