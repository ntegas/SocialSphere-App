package com.aistudio.socialsphere.crmlxb.model

data class Contact(
    val id: String,
    val firstName: String,
    val lastName: String,
    val nickname: String? = null,
    val photoUri: String?,
    val relationshipType: RelationshipType,
    val connectionLevel: ConnectionLevel,
    val importanceLevel: ImportanceLevel,
    val socialRole: SocialRole,
    val communicationRhythm: CommunicationRhythm,
    val contactStatus: ContactStatus = ContactStatus.ACTIVE,
    val lastContactDate: String? = null,
    val nextStep: String? = null,
    val tags: List<String> = emptyList(),
    val canHelpWith: String? = null,
    val iCanHelpWith: String? = null,
    val talkingPoints: String? = null,
    val meetContext: String? = null,
    val meetDate: String? = null,
    val companyRelations: List<ContactCompanyRelation> = emptyList(),
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val messengers: List<Messenger> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val notes: List<Note> = emptyList(),
    val gifts: List<GiftIdea> = emptyList(),
    val sizeInfo: SizeInfo? = null,
    val personalDetails: List<PersonalDetail> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

data class ContactPhone(
    val id: String,
    val contactId: String,
    val number: String,
    val type: PhoneType,
    val isPrimary: Boolean,
    val comment: String? = null
)

data class ContactEmail(
    val id: String,
    val contactId: String,
    val email: String,
    val type: EmailType,
    val isPrimary: Boolean,
    val comment: String? = null
)

data class Messenger(
    val id: String,
    val contactId: String,
    val type: MessengerType,
    val value: String,
    val link: String? = null,
    val isPrimary: Boolean,
    val comment: String? = null
)

data class Company(
    val id: String,
    val name: String,
    val logoUri: String? = null,
    val industry: Industry,
    val description: String? = null,
    val website: String? = null,
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

data class ContactCompanyRelation(
    val id: String,
    val contactId: String,
    val companyId: String,
    val position: String? = null,
    val department: String? = null,
    val role: String? = null,
    val employmentStatus: EmploymentStatus,
    val startDate: String? = null,
    val endDate: String? = null,
    val responsibilities: String? = null,
    val managedAccounts: String? = null,
    val workNote: String? = null,
    val officeAddressId: String? = null,
    val isPrimary: Boolean
)

data class ContactRelation(
    val id: String,
    val firstContactId: String,
    val secondContactId: String,
    val firstRole: String,
    val secondRole: String,
    val note: String? = null
)

data class Address(
    val id: String,
    val ownerType: AddressOwnerType,
    val ownerId: String,
    val addressType: AddressType,
    val addressLine: String,
    val city: String,
    val country: String,
    val comment: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val postalCode: String? = null
)

data class CalendarItem(
    val id: String,
    val title: String,
    val description: String? = null,
    val type: CalendarItemType,
    val startDate: String,
    val startTime: String? = null,
    val endDate: String? = null,
    val endTime: String? = null,
    val isAllDay: Boolean,
    val status: CalendarItemStatus,
    val importance: ImportanceLevel,
    val colorKey: String? = null,
    val recurrenceRule: String? = null,
    val links: List<CalendarItemLink> = emptyList(),
    val reminders: List<ReminderRule> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

data class CalendarItemLink(
    val id: String,
    val calendarItemId: String,
    val targetType: CalendarTargetType,
    val targetId: String
)

data class ReminderRule(
    val id: String,
    val calendarItemId: String,
    val reminderType: ReminderType,
    val offsetValue: Int? = null,
    val offsetUnit: ReminderOffsetUnit? = null,
    val exactDateTime: String? = null
)

data class Note(
    val id: String,
    val contactId: String? = null,
    val companyId: String? = null,
    val calendarItemId: String? = null,
    val giftId: String? = null,
    val type: NoteType,
    val text: String,
    val date: String? = null,
    val isImportant: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class GiftIdea(
    val id: String,
    val contactId: String,
    val title: String,
    val note: String? = null,
    val link: String? = null,
    val date: String? = null,
    val reminderId: String? = null,
    val status: GiftStatus
)

data class SizeInfo(
    val id: String,
    val contactId: String,
    val clothingSize: String? = null,
    val shoeSize: String? = null,
    val ringSize: String? = null,
    val other: String? = null
)

data class PersonalDetail(
    val id: String,
    val contactId: String,
    val category: PersonalDetailCategory,
    val value: String,
    val note: String? = null
)
