package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.data.local.*
import com.aistudio.socialsphere.crmlxb.model.*

/**
 * BACKUP INTEGRITY PACK #3 (2026-09-21): ЕДИНЫЙ механизм чтения persisted-состояния.
 *
 * Полный backup и restore-план обязаны строиться из того, что реально закоммичено в Room, а не из
 * оптимистичной памяти AppStateStore и не из вложенных проекций Contact/Company (они бывают
 * устаревшими: координаты адресов, размеры, подарки, заметки, lastContactDate меняются независимыми
 * писателями). Поэтому:
 *  • [readRoomSnapshot] читает ВСЕ user-data таблицы подряд; вызывающий обязан держать чтение внутри
 *    одной `db.withTransaction`, чтобы снимок был логически согласованным (см. [AppStateStore.readPersistedState]);
 *  • [RoomSnapshot.toPersistedState] собирает доменные объекты и вложенные проекции по persisted id из
 *    ЭТОГО ЖЕ снимка — плоские и вложенные списки не могут разойтись (mixed-version backup исключён);
 *  • тот же построитель использует `reloadFromDb()`, поэтому «память после restore» и «backup» видят
 *    одно и то же состояние, а не две параллельные реализации.
 */
internal class RoomSnapshot(
    val contacts: List<ContactEntity>,
    val companies: List<CompanyEntity>,
    val companyRelations: List<ContactCompanyRelationEntity>,
    val contactRelations: List<ContactRelationEntity>,
    val phones: List<ContactPhoneEntity>,        // строки контактов И компаний (различаются companyId)
    val emails: List<ContactEmailEntity>,        // то же
    val messengers: List<MessengerEntity>,
    val addresses: List<AddressEntity>,          // адреса контактов И компаний (ownerType)
    val calendarItems: List<CalendarItemEntity>,
    val links: List<CalendarItemLinkEntity>,
    val reminders: List<ReminderRuleEntity>,
    val notes: List<NoteEntity>,
    val gifts: List<GiftIdeaEntity>,
    val sizeInfos: List<SizeInfoEntity>,
    val personalDetails: List<PersonalDetailEntity>,
    val groups: List<ContactGroupEntity>,
    val groupMembers: List<ContactGroupMemberEntity>,
    val tags: List<TagEntity>,
    val tagMembers: List<ContactTagMemberEntity>,
    /** EXPLICIT RECOVERY PACK (v22): история намеренных удалений — см. TombstoneEntity. */
    val tombstones: List<TombstoneEntity>,
    val schemaVersion: Int,
)

/** Читает все 20 user-data таблиц. Вызывать ВНУТРИ `db.withTransaction` (согласованность). */
internal suspend fun readRoomSnapshot(db: SocialsphereDatabase): RoomSnapshot = RoomSnapshot(
    contacts = db.contactDao().getAllContacts(),
    companies = db.companyDao().getAllCompanies(),
    companyRelations = db.contactDao().getContactCompanyRelations(),
    contactRelations = db.contactDao().getContactRelations(),
    phones = db.contactDao().getContactPhones(),
    emails = db.contactDao().getContactEmails(),
    messengers = db.contactDao().getMessengers(),
    addresses = db.addressDao().getAllAddresses(),
    calendarItems = db.calendarDao().getAllCalendarItems(),
    links = db.calendarDao().getCalendarItemLinks(),
    reminders = db.calendarDao().getReminderRules(),
    notes = db.noteDao().getAllNotes(),
    gifts = db.giftDao().getAllGifts(),
    sizeInfos = db.contactDao().getSizeInfos(),
    personalDetails = db.contactDao().getPersonalDetails(),
    groups = db.contactDao().getContactGroups(),
    groupMembers = db.contactDao().getContactGroupMembers(),
    tags = db.contactDao().getTagsOnce(),
    tagMembers = db.contactDao().getContactTagMembersOnce(),
    tombstones = db.tombstoneDao().getAll(),
    schemaVersion = db.openHelper.readableDatabase.version,
)

/**
 * Доменное представление одного снимка Room: плоские списки + вложенные проекции
 * (Contact.phones/emails/messengers/addresses/notes/gifts/sizeInfo/personalDetails/companyRelations,
 * Company.phones/emails/addresses, CalendarItem.links/reminders), собранные по persisted id.
 */
class PersistedState(
    val contacts: List<Contact>,
    val companies: List<Company>,
    val calendarItems: List<CalendarItem>,
    val notes: List<Note>,
    val gifts: List<GiftIdea>,
    val companyRelations: List<ContactCompanyRelation>,
    val contactRelations: List<ContactRelation>,
    val addresses: List<Address>,
    val sizeInfos: List<SizeInfo>,
    val personalDetails: List<PersonalDetail>,
    val groups: List<ContactGroup>,
    val groupMembers: List<ContactGroupMember>,
    val tags: List<Tag>,
    val tagMembers: List<ContactTagMember>,
    /** EXPLICIT RECOVERY PACK (v22): история намеренных удалений. */
    val tombstones: List<Tombstone>,
    /** Версия схемы Room, из которой прочитан снимок (пишется в backup). */
    val schemaVersion: Int,
)

internal fun RoomSnapshot.toPersistedState(): PersistedState {
    val contactPhones = phones.filter { it.companyId == null }.map { it.toDomain() }
    val contactEmails = emails.filter { it.companyId == null }.map { it.toDomain() }
    val messengerList = messengers.map { it.toDomain() }
    val addressList = addresses.map { it.toDomain() }
    val noteList = notes.map { it.toDomain() }
    val giftList = gifts.map { it.toDomain() }
    val sizeList = sizeInfos.map { it.toDomain() }
    val detailList = personalDetails.map { it.toDomain() }
    val relationList = companyRelations.map { it.toDomain() }
    val linkList = links.map { it.toDomain() }
    val reminderList = reminders.map { it.toDomain() }

    val companyList = companies.map { c ->
        c.toDomain().copy(
            phones = phones.filter { it.companyId == c.id }.map { it.toDomain() },
            emails = emails.filter { it.companyId == c.id }.map { it.toDomain() },
            addresses = addressList.filter { it.ownerType == AddressOwnerType.COMPANY && it.ownerId == c.id },
        )
    }
    val calendarList = calendarItems.map { c ->
        c.toDomain().copy(
            links = linkList.filter { it.calendarItemId == c.id },
            reminders = reminderList.filter { it.calendarItemId == c.id },
        )
    }
    val contactList = contacts.map { c ->
        c.toDomain().copy(
            companyRelations = relationList.filter { it.contactId == c.id },
            phones = contactPhones.filter { it.contactId == c.id },
            emails = contactEmails.filter { it.contactId == c.id },
            messengers = messengerList.filter { it.contactId == c.id },
            addresses = addressList.filter { it.ownerType == AddressOwnerType.CONTACT && it.ownerId == c.id },
            notes = noteList.filter { it.contactId == c.id },
            gifts = giftList.filter { it.contactId == c.id },
            sizeInfo = sizeList.find { it.contactId == c.id },
            personalDetails = detailList.filter { it.contactId == c.id },
        )
    }
    return PersistedState(
        contacts = contactList, companies = companyList, calendarItems = calendarList,
        notes = noteList, gifts = giftList, companyRelations = relationList,
        contactRelations = contactRelations.map { it.toDomain() }, addresses = addressList,
        sizeInfos = sizeList, personalDetails = detailList,
        groups = groups.map { it.toDomain() }, groupMembers = groupMembers.map { it.toDomain() },
        tags = tags.map { it.toDomain() }, tagMembers = tagMembers.map { it.toDomain() },
        tombstones = tombstones.map { it.toDomain() },
        schemaVersion = schemaVersion,
    )
}
