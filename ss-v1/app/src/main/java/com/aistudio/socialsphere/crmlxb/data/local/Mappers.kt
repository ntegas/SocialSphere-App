package com.aistudio.socialsphere.crmlxb.data.local

import com.aistudio.socialsphere.crmlxb.model.*

// Безопасный разбор enum из строки БД. Если значение отсутствует в enum
// (переименование, кривой импорт CSV/vCard, ручная правка БД) — возвращаем
// дефолт вместо IllegalArgumentException, который ронял весь reloadFromDb().
inline fun <reified T : Enum<T>> safeEnum(value: String?, default: T): T =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

// ФИКС (аудит 2026-08-18): Contact.tags раньше join/split-ился по "," — обычная
// запятая печатается с клавиатуры, так что тег "Boston, MA" молча превращался
// в два тега при следующей загрузке из БД. "###" — маловероятная в обычном
// теге, но безопасная по кодировке (обычный ASCII, не control-символ) замена.
private const val TAG_DELIMITER = "###"

fun Contact.toEntity(): ContactEntity = ContactEntity(
    id                = id,
    firstName         = firstName,
    lastName          = lastName,
    middleName        = middleName,
    nickname          = nickname,
    namePrefix        = namePrefix,
    nameSuffix        = nameSuffix,
    phoneticFirstName = phoneticFirstName,
    phoneticMiddleName = phoneticMiddleName,
    phoneticLastName  = phoneticLastName,
    photoUri          = photoUri,
    relationshipType  = relationshipType.name,
    customRelationshipType = customRelationshipType,
    secondaryRelationshipTypes = if (secondaryRelationshipTypes.isEmpty()) "" else secondaryRelationshipTypes.joinToString(",") { it.name },
    connectionLevel   = connectionLevel.name,
    importanceLevel   = importanceLevel.name,
    socialRole        = socialRole.name,
    communicationRhythm = communicationRhythm.name,
    customRhythmDays  = customRhythmDays,
    contactStatus     = contactStatus.name,
    lastContactDate   = lastContactDate,
    nextStep          = nextStep,
    familyNote        = familyNote,
    profession        = profession,
    tags              = if (tags.isEmpty()) null else tags.joinToString(TAG_DELIMITER),
    canHelpWith       = canHelpWith,
    iCanHelpWith      = iCanHelpWith,
    talkingPoints     = talkingPoints,
    meetContext       = meetContext,
    meetDate          = meetDate,
    deviceContactId   = deviceContactId,
    createdAt         = createdAt,
    updatedAt         = updatedAt
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

// ── Company-варианты: та же таблица, заполняется companyId ──
fun ContactPhone.toCompanyEntity(companyId: String): ContactPhoneEntity = ContactPhoneEntity(
    id = id,
    contactId = null,
    companyId = companyId,
    number = number,
    type = type.name,
    isPrimary = isPrimary,
    comment = comment
)

fun ContactEmail.toCompanyEntity(companyId: String): ContactEmailEntity = ContactEmailEntity(
    id = id,
    contactId = null,
    companyId = companyId,
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

fun ContactGroup.toEntity(): ContactGroupEntity = ContactGroupEntity(
    id = id, name = name, createdAt = createdAt, updatedAt = updatedAt
)

fun ContactGroupMember.toEntity(): ContactGroupMemberEntity = ContactGroupMemberEntity(
    id = id, groupId = groupId, contactId = contactId
)

fun Tag.toEntity(): TagEntity = TagEntity(
    id = id, name = name, category = category, createdAt = createdAt, updatedAt = updatedAt
)

fun ContactTagMember.toEntity(): ContactTagMemberEntity = ContactTagMemberEntity(
    id = id, tagId = tagId, contactId = contactId
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
    longitude = longitude,
    postalCode = postalCode,
    district = district
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
    isLocked = isLocked,
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
    // ФИКС (2026-08-17, задача #113 — мёртвое поле): reminderId никогда не
    // заполнялся ни одним UI (не было экрана, который создавал бы напоминание
    // для подарка и записывал его id сюда) и нигде не читался для отмены
    // будильника при удалении. Убрано из домена GiftIdea целиком — колонка в
    // Entity/БД оставлена как есть (не трогаем схему/миграции ради мёртвого
    // поля, которое и так всегда было null), просто больше не заполняется.
    reminderId = null,
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
    id                  = id,
    firstName           = firstName,
    lastName            = lastName,
    middleName          = middleName,
    nickname            = nickname,
    namePrefix          = namePrefix,
    nameSuffix          = nameSuffix,
    phoneticFirstName   = phoneticFirstName,
    phoneticMiddleName  = phoneticMiddleName,
    phoneticLastName    = phoneticLastName,
    photoUri            = photoUri,
    relationshipType    = safeEnum(relationshipType, RelationshipType.OTHER),
    customRelationshipType = customRelationshipType,
    secondaryRelationshipTypes = secondaryRelationshipTypes
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?.map { safeEnum(it, RelationshipType.OTHER) }
        ?: emptyList(),
    connectionLevel     = safeEnum(connectionLevel, ConnectionLevel.NORMAL),
    importanceLevel     = safeEnum(importanceLevel, ImportanceLevel.NORMAL),
    socialRole          = safeEnum(socialRole, SocialRole.REGULAR),
    communicationRhythm = safeEnum(communicationRhythm, CommunicationRhythm.NOT_TRACKED),
    customRhythmDays    = customRhythmDays,
    contactStatus       = safeEnum(contactStatus, ContactStatus.ACTIVE),
    lastContactDate     = lastContactDate,
    nextStep            = nextStep,
    familyNote          = familyNote,
    profession          = profession,
    // Обратная совместимость: старые записи (до фикса TAG_DELIMITER) сохранены
    // через запятую — если нового разделителя в строке нет, парсим как раньше,
    // иначе существующие контакты с 2+ тегами схлопнулись бы в один тег.
    tags                = when {
        tags.isNullOrBlank() -> emptyList()
        tags.contains(TAG_DELIMITER) -> tags.split(TAG_DELIMITER).filter { it.isNotBlank() }
        else -> tags.split(",").filter { it.isNotBlank() }
    },
    canHelpWith         = canHelpWith,
    iCanHelpWith        = iCanHelpWith,
    talkingPoints       = talkingPoints,
    meetContext         = meetContext,
    meetDate            = meetDate,
    deviceContactId     = deviceContactId,
    createdAt           = createdAt,
    updatedAt           = updatedAt
)

fun CompanyEntity.toDomain(): Company = Company(
    id = id,
    name = name,
    logoUri = logoUri,
    industry = safeEnum(industry, Industry.OTHER),
    description = description,
    website = website,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ContactPhoneEntity.toDomain(): ContactPhone = ContactPhone(
    id = id,
    contactId = contactId ?: "", // or companyId
    number = number,
    type = safeEnum(type, PhoneType.MOBILE),
    isPrimary = isPrimary,
    comment = comment
)

fun ContactEmailEntity.toDomain(): ContactEmail = ContactEmail(
    id = id,
    contactId = contactId ?: "",
    email = email,
    type = safeEnum(type, EmailType.PERSONAL),
    isPrimary = isPrimary,
    comment = comment
)

fun MessengerEntity.toDomain(): Messenger = Messenger(
    id = id,
    contactId = contactId,
    type = safeEnum(type, MessengerType.OTHER),
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
    employmentStatus = safeEnum(employmentStatus, EmploymentStatus.CURRENT),
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

fun ContactGroupEntity.toDomain(): ContactGroup = ContactGroup(
    id = id, name = name, createdAt = createdAt, updatedAt = updatedAt
)

fun ContactGroupMemberEntity.toDomain(): ContactGroupMember = ContactGroupMember(
    id = id, groupId = groupId, contactId = contactId
)

fun TagEntity.toDomain(): Tag = Tag(
    id = id, name = name, category = category, createdAt = createdAt, updatedAt = updatedAt
)

fun ContactTagMemberEntity.toDomain(): ContactTagMember = ContactTagMember(
    id = id, tagId = tagId, contactId = contactId
)

fun AddressEntity.toDomain(): Address = Address(
    id = id,
    ownerType = safeEnum(ownerType, AddressOwnerType.CONTACT),
    ownerId = ownerId,
    addressType = safeEnum(addressType, AddressType.OTHER),
    addressLine = addressLine,
    city = city,
    country = country,
    comment = comment,
    latitude = latitude,
    longitude = longitude,
    postalCode = postalCode,
    district = district
)

fun CalendarItemEntity.toDomain(): CalendarItem = CalendarItem(
    id = id,
    title = title,
    description = description,
    type = safeEnum(type, CalendarItemType.NOTE),
    startDate = startDate,
    startTime = startTime,
    endDate = endDate,
    endTime = endTime,
    isAllDay = isAllDay,
    status = safeEnum(status, CalendarItemStatus.ACTIVE),
    importance = safeEnum(importance, ImportanceLevel.NORMAL),
    colorKey = colorKey,
    recurrenceRule = recurrenceRule,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CalendarItemLinkEntity.toDomain(): CalendarItemLink = CalendarItemLink(
    id = id,
    calendarItemId = calendarItemId,
    targetType = safeEnum(targetType, CalendarTargetType.CONTACT),
    targetId = targetId
)

fun ReminderRuleEntity.toDomain(): ReminderRule = ReminderRule(
    id = id,
    calendarItemId = calendarItemId,
    reminderType = safeEnum(reminderType, ReminderType.NONE),
    offsetValue = offsetValue,
    offsetUnit = offsetUnit?.let { safeEnum(it, ReminderOffsetUnit.MINUTES) },
    exactDateTime = exactDateTime
)

fun NoteEntity.toDomain(): Note = Note(
    id = id,
    contactId = contactId,
    companyId = companyId,
    calendarItemId = calendarItemId,
    giftId = giftId,
    type = safeEnum(type, NoteType.GENERAL),
    text = text,
    date = date,
    isImportant = isImportant,
    isLocked = isLocked,
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
    status = safeEnum(status, GiftStatus.IDEA)
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
    category = safeEnum(category, PersonalDetailCategory.OTHER),
    value = value,
    note = note
)
