package com.aistudio.socialsphere.crmlxb.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val firstName: String,
    val lastName: String,
    val middleName: String? = null,
    val nickname: String? = null,
    val namePrefix: String? = null,
    val nameSuffix: String? = null,
    val phoneticFirstName: String? = null,
    val phoneticLastName: String? = null,
    val photoUri: String?,
    val relationshipType: String,
    val customRelationshipType: String? = null,
    val connectionLevel: String,
    val importanceLevel: String,
    val socialRole: String,
    val communicationRhythm: String,
    @ColumnInfo(defaultValue = "ACTIVE") val contactStatus: String = "ACTIVE",
    val lastContactDate: String? = null,
    val nextStep: String? = null,
    val familyNote: String? = null,
    val profession: String? = null,
    val tags: String? = null,          // JSON array stored as string
    val canHelpWith: String? = null,
    val iCanHelpWith: String? = null,
    val talkingPoints: String? = null,
    val meetContext: String? = null,
    val meetDate: String? = null,
    val deviceContactId: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "companies")
data class CompanyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val logoUri: String?,
    val industry: String,
    val description: String?,
    val website: String?,
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "contact_company_relations")
data class ContactCompanyRelationEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val companyId: String,
    val position: String?,
    val department: String?,
    val role: String?,
    val employmentStatus: String,
    val startDate: String?,
    val endDate: String?,
    val responsibilities: String?,
    val managedAccounts: String?,
    val workNote: String?,
    val officeAddressId: String?,
    val isPrimary: Boolean
)

@Entity(tableName = "contact_phones")
data class ContactPhoneEntity(
    @PrimaryKey val id: String,
    val contactId: String?,
    val companyId: String?,
    val number: String,
    val type: String,
    val isPrimary: Boolean,
    val comment: String?
)

@Entity(tableName = "contact_emails")
data class ContactEmailEntity(
    @PrimaryKey val id: String,
    val contactId: String?,
    val companyId: String?,
    val email: String,
    val type: String,
    val isPrimary: Boolean,
    val comment: String?
)

@Entity(tableName = "messengers")
data class MessengerEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val type: String,
    val value: String,
    val link: String?,
    val isPrimary: Boolean,
    val comment: String?
)

@Entity(tableName = "addresses")
data class AddressEntity(
    @PrimaryKey val id: String,
    val ownerType: String,
    val ownerId: String,
    val addressType: String,
    val addressLine: String,
    val city: String,
    val country: String,
    val comment: String?,
    val latitude: Double?,
    val longitude: Double?,
    val postalCode: String?
)

@Entity(tableName = "calendar_items")
data class CalendarItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val type: String,
    val startDate: String,
    val startTime: String?,
    val endDate: String?,
    val endTime: String?,
    val isAllDay: Boolean,
    val status: String,
    val importance: String,
    val colorKey: String?,
    val recurrenceRule: String?,
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "calendar_item_links")
data class CalendarItemLinkEntity(
    @PrimaryKey val id: String,
    val calendarItemId: String,
    val targetType: String,
    val targetId: String
)

@Entity(tableName = "reminder_rules")
data class ReminderRuleEntity(
    @PrimaryKey val id: String,
    val calendarItemId: String,
    val reminderType: String,
    val offsetValue: Int?,
    val offsetUnit: String?,
    val exactDateTime: String?
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val contactId: String?,
    val companyId: String?,
    val calendarItemId: String?,
    val giftId: String?,
    val type: String,
    val text: String,
    val date: String?,
    val isImportant: Boolean,
    // v14: приватность — отдельно от isImportant (важность = попадает в Обзор/
    // Шпаргалку и красная рамка; isLocked = скрывается блюром под «Защитой
    // записей»). Раньше это было одно и то же поле — заметка "не секретная,
    // но важная" пряталась, а реально приватная без галочки важности видна.
    @ColumnInfo(defaultValue = "0") val isLocked: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "gift_ideas")
data class GiftIdeaEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val title: String,
    val note: String?,
    val link: String?,
    val date: String?,
    val reminderId: String?,
    val status: String
)

@Entity(tableName = "size_infos")
data class SizeInfoEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val clothingSize: String?,
    val shoeSize: String?,
    val ringSize: String?,
    val other: String?
)

@Entity(tableName = "personal_details")
data class PersonalDetailEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val category: String,
    val value: String,
    val note: String?
)

@Entity(tableName = "contact_relations")
data class ContactRelationEntity(
     @PrimaryKey val id: String,
     val firstContactId: String,
     val secondContactId: String,
     val firstRole: String,
     val secondRole: String,
     val note: String?
)

@Entity(tableName = "contact_groups")
data class ContactGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "contact_group_members")
data class ContactGroupMemberEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val contactId: String
)
