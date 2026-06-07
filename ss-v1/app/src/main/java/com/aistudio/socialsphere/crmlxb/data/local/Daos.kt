package com.aistudio.socialsphere.crmlxb.data.local

import androidx.room.*

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    suspend fun getAllContacts(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :contactId")
    suspend fun deleteContact(contactId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhones(phones: List<ContactPhoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmails(emails: List<ContactEmailEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessengers(messengers: List<MessengerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompanyRelations(relations: List<ContactCompanyRelationEntity>)

    @Query("SELECT * FROM contact_phones")
    suspend fun getContactPhones(): List<ContactPhoneEntity>

    @Query("SELECT * FROM contact_emails")
    suspend fun getContactEmails(): List<ContactEmailEntity>

    @Query("SELECT * FROM messengers")
    suspend fun getMessengers(): List<MessengerEntity>

    @Query("SELECT * FROM contact_company_relations")
    suspend fun getContactCompanyRelations(): List<ContactCompanyRelationEntity>

    @Query("SELECT * FROM contact_relations")
    suspend fun getContactRelations(): List<ContactRelationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContactRelations(relations: List<ContactRelationEntity>)

    @Query("SELECT * FROM size_infos")
    suspend fun getSizeInfos(): List<SizeInfoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSizeInfo(sizeInfo: SizeInfoEntity)

    @Query("SELECT * FROM personal_details")
    suspend fun getPersonalDetails(): List<PersonalDetailEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersonalDetails(details: List<PersonalDetailEntity>)

    @Query("DELETE FROM contact_phones WHERE contactId = :contactId")
    suspend fun deletePhonesForContact(contactId: String)

    @Query("DELETE FROM contact_emails WHERE contactId = :contactId")
    suspend fun deleteEmailsForContact(contactId: String)

    @Query("DELETE FROM messengers WHERE contactId = :contactId")
    suspend fun deleteMessengersForContact(contactId: String)

    @Query("DELETE FROM contact_company_relations WHERE contactId = :contactId")
    suspend fun deleteCompanyRelationsForContact(contactId: String)

    @Query("UPDATE contacts SET lastContactDate = :date, updatedAt = :updatedAt WHERE id = :contactId")
    suspend fun updateLastContactDate(contactId: String, date: String, updatedAt: String)
}

@Dao
interface CompanyDao {
    @Query("SELECT * FROM companies")
    suspend fun getAllCompanies(): List<CompanyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompany(company: CompanyEntity)

    @Query("DELETE FROM companies WHERE id = :companyId")
    suspend fun deleteCompany(companyId: String)

    @Query("DELETE FROM contact_phones WHERE companyId = :companyId")
    suspend fun deletePhonesForCompany(companyId: String)

    @Query("DELETE FROM contact_emails WHERE companyId = :companyId")
    suspend fun deleteEmailsForCompany(companyId: String)
}

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_items")
    suspend fun getAllCalendarItems(): List<CalendarItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarItem(item: CalendarItemEntity)

    @Query("DELETE FROM calendar_items WHERE id = :itemId")
    suspend fun deleteCalendarItem(itemId: String)

    @Query("SELECT * FROM calendar_item_links")
    suspend fun getCalendarItemLinks(): List<CalendarItemLinkEntity>

    @Query("SELECT * FROM reminder_rules")
    suspend fun getReminderRules(): List<ReminderRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarItemLinks(links: List<CalendarItemLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminderRules(rules: List<ReminderRuleEntity>)

    @Query("DELETE FROM calendar_item_links WHERE calendarItemId = :itemId")
    suspend fun deleteLinksForItem(itemId: String)

    @Query("DELETE FROM reminder_rules WHERE calendarItemId = :itemId")
    suspend fun deleteRemindersForItem(itemId: String)
}

@Dao
interface AddressDao {
    @Query("SELECT * FROM addresses")
    suspend fun getAllAddresses(): List<AddressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddresses(addresses: List<AddressEntity>)

    @Query("DELETE FROM addresses WHERE ownerId = :ownerId AND ownerType = :ownerType")
    suspend fun deleteAddressesForOwner(ownerId: String, ownerType: String)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes")
    suspend fun getAllNotes(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<NoteEntity>)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNote(noteId: String)

    @Query("DELETE FROM notes WHERE contactId = :contactId")
    suspend fun deleteNotesForContact(contactId: String)

    @Query("DELETE FROM notes WHERE companyId = :companyId")
    suspend fun deleteNotesForCompany(companyId: String)
}

@Dao
interface GiftDao {
    @Query("SELECT * FROM gift_ideas")
    suspend fun getAllGifts(): List<GiftIdeaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGifts(gifts: List<GiftIdeaEntity>)

    @Query("DELETE FROM gift_ideas WHERE id = :giftId")
    suspend fun deleteGift(giftId: String)

    @Query("DELETE FROM gift_ideas WHERE contactId = :contactId")
    suspend fun deleteGiftsForContact(contactId: String)
}
