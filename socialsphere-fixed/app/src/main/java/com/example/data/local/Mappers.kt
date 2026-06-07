package com.example.data.local

import com.example.model.*

fun Contact.toEntity(): ContactEntity = ContactEntity(
    id = id,
    firstName = firstName,
    lastName = lastName,
    photoUri = photoUri,
    relationshipType = relationshipType.name,
    connectionLevel = connectionLevel.name,
    importanceLevel = importanceLevel.name,
    socialRole = socialRole.name,
    communicationRhythm = communicationRhythm.name,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ContactPhone.toEntity(): ContactPhoneEntity = ContactPhoneEntity(
    id = id,
    contactId = contactId,
    companyId = null,
    number = number,
    type = type.name,
    isPrimary = isPrimary,
    comment = comment
)

fun ContactEmail.toEntity(): ContactEmailEntity = ContactEmailEntity(
    id = id,
    contactId = contactId,
    companyId = null,
    email = email,
    type = type.name,
    isPrimary = isPrimary,
    comment = comment
)

fun Messenger.toEntity(): MessengerEntity = MessengerEntity(
    id = id,
    contactId = contactId,
    type = type.name,
    value = value,
    link = link,
    isPrimary = isPrimary,
    comment = comment
)

fun Company.toEntity(): CompanyEntity = CompanyEntity(
    id = id,
    name = name,
    logoUri = logoUri,
    industry = industry.name,
    description = description,
    website = website,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ContactCompanyRelation.toEntity(): ContactCompanyRelationEntity = ContactCompanyRelationEntity(
    id = id,
    contactId = contactId,
    companyId = companyId,
    position = position,
    department = department,
    role = role,
    employmentStatus = employmentStatus.name,
    startDate = startDate,
    endDate = endDate,
    responsibilities = responsibilities,
    managedAccounts = managedAccounts,
    workNote = workNote,
    officeAddressId = officeAddressId,
    isPrimary = isPrimary
)

fun ContactRelation.toEntity(): ContactRelationEntity = ContactRelationEntity(
    id = id,
    firstContactId = firstContactId,
    secondContactId = secondContactId,
    firstRole = firstRole,
    secondRole = secondRole,
    note = note
)

fun Address.toEntity(): AddressEntity = AddressEntity(
    id = id,
    ownerType = ownerType.name,
    ownerId = ownerId,
    addressType = addressType.name,
    addressLine = addressLine,
    city = city,
    country = country,
    comment = comment,
    latitude = latitude,
    longitude = longitude
)

fun CalendarItem.toEntity(): CalendarItemEntity = CalendarItemEntity(
    id = id,
    title = title,
    description = description,
    type = type.name,
    startDate = startDate,
    startTime = startTime,
    endDate = endDate,
    endTime = endTime,
    isAllDay = isAllDay,
    status = status.name,
    importance = importance.name,
    colorKey = colorKey,
    recurrenceRule = recurrenceRule,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CalendarItemLink.toEntity(): CalendarItemLinkEntity = CalendarItemLinkEntity(
    id = id,
    calendarItemId = calendarItemId,
    targetType = targetType.name,
    targetId = targetId
)

fun ReminderRule.toEntity(): ReminderRuleEntity = ReminderRuleEntity(
    id = id,
    calendarItemId = calendarItemId,
    reminderType = reminderType.name,
    offsetValue = offsetValue,
    offsetUnit = offsetUnit?.name,
    exactDateTime = exactDateTime
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    contactId = contactId,
    companyId = companyId,
    calendarItemId = calendarItemId,
    giftId = giftId,
    type = type.name,
    text = text,
    date = date,
    isImportant = isImportant,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun GiftIdea.toEntity(): GiftIdeaEntity = GiftIdeaEntity(
    id = id,
    contactId = contactId,
    title = title,
    note = note,
    link = link,
    date = date,
    reminderId = reminderId,
    status = status.name
)

fun SizeInfo.toEntity(): SizeInfoEntity = SizeInfoEntity(
    id = id,
    contactId = contactId,
    clothingSize = clothingSize,
    shoeSize = shoeSize,
    ringSize = ringSize,
    other = other
)

fun PersonalDetail.toEntity(): PersonalDetailEntity = PersonalDetailEntity(
    id = id,
    contactId = contactId,
    category = category.name,
    value = value,
    note = note
)

fun ContactEntity.toDomain(): Contact = Contact(
    id = id,
    firstName = firstName,
    lastName = lastName,
    photoUri = photoUri,
    relationshipType = RelationshipType.valueOf(relationshipType),
    connectionLevel = ConnectionLevel.valueOf(connectionLevel),
    importanceLevel = ImportanceLevel.valueOf(importanceLevel),
    socialRole = SocialRole.valueOf(socialRole),
    communicationRhythm = CommunicationRhythm.valueOf(communicationRhythm),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CompanyEntity.toDomain(): Company = Company(
    id = id,
    name = name,
    logoUri = logoUri,
    industry = Industry.valueOf(industry),
    description = description,
    website = website,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ContactPhoneEntity.toDomain(): ContactPhone = ContactPhone(
    id = id,
    contactId = contactId ?: "", // or companyId
    number = number,
    type = PhoneType.valueOf(type),
    isPrimary = isPrimary,
    comment = comment
)

fun ContactEmailEntity.toDomain(): ContactEmail = ContactEmail(
    id = id,
    contactId = contactId ?: "",
    email = email,
    type = EmailType.valueOf(type),
    isPrimary = isPrimary,
    comment = comment
)

fun MessengerEntity.toDomain(): Messenger = Messenger(
    id = id,
    contactId = contactId,
    type = MessengerType.valueOf(type),
    value = value,
    link = link,
    isPrimary = isPrimary,
    comment = comment
)

fun ContactCompanyRelationEntity.toDomain(): ContactCompanyRelation = ContactCompanyRelation(
    id = id,
    contactId = contactId,
    companyId = companyId,
    position = position,
    department = department,
    role = role,
    employmentStatus = EmploymentStatus.valueOf(employmentStatus),
    startDate = startDate,
    endDate = endDate,
    responsibilities = responsibilities,
    managedAccounts = managedAccounts,
    workNote = workNote,
    officeAddressId = officeAddressId,
    isPrimary = isPrimary
)

fun ContactRelationEntity.toDomain(): ContactRelation = ContactRelation(
    id = id,
    firstContactId = firstContactId,
    secondContactId = secondContactId,
    firstRole = firstRole,
    secondRole = secondRole,
    note = note
)

fun AddressEntity.toDomain(): Address = Address(
    id = id,
    ownerType = AddressOwnerType.valueOf(ownerType),
    ownerId = ownerId,
    addressType = AddressType.valueOf(addressType),
    addressLine = addressLine,
    city = city,
    country = country,
    comment = comment,
    latitude = latitude,
    longitude = longitude
)

fun CalendarItemEntity.toDomain(): CalendarItem = CalendarItem(
    id = id,
    title = title,
    description = description,
    type = CalendarItemType.valueOf(type),
    startDate = startDate,
    startTime = startTime,
    endDate = endDate,
    endTime = endTime,
    isAllDay = isAllDay,
    status = CalendarItemStatus.valueOf(status),
    importance = ImportanceLevel.valueOf(importance),
    colorKey = colorKey,
    recurrenceRule = recurrenceRule,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CalendarItemLinkEntity.toDomain(): CalendarItemLink = CalendarItemLink(
    id = id,
    calendarItemId = calendarItemId,
    targetType = CalendarTargetType.valueOf(targetType),
    targetId = targetId
)

fun ReminderRuleEntity.toDomain(): ReminderRule = ReminderRule(
    id = id,
    calendarItemId = calendarItemId,
    reminderType = ReminderType.valueOf(reminderType),
    offsetValue = offsetValue,
    offsetUnit = offsetUnit?.let { ReminderOffsetUnit.valueOf(it) },
    exactDateTime = exactDateTime
)

fun NoteEntity.toDomain(): Note = Note(
    id = id,
    contactId = contactId,
    companyId = companyId,
    calendarItemId = calendarItemId,
    giftId = giftId,
    type = NoteType.valueOf(type),
    text = text,
    date = date,
    isImportant = isImportant,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun GiftIdeaEntity.toDomain(): GiftIdea = GiftIdea(
    id = id,
    contactId = contactId,
    title = title,
    note = note,
    link = link,
    date = date,
    reminderId = reminderId,
    status = GiftStatus.valueOf(status)
)

fun SizeInfoEntity.toDomain(): SizeInfo = SizeInfo(
    id = id,
    contactId = contactId,
    clothingSize = clothingSize,
    shoeSize = shoeSize,
    ringSize = ringSize,
    other = other
)

fun PersonalDetailEntity.toDomain(): PersonalDetail = PersonalDetail(
    id = id,
    contactId = contactId,
    category = PersonalDetailCategory.valueOf(category),
    value = value,
    note = note
)
