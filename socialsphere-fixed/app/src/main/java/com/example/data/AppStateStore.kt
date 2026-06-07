package com.example.data

import androidx.compose.runtime.mutableStateListOf
import com.example.model.*
import com.example.data.local.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object AppStateStore {
    val contacts        = mutableStateListOf<Contact>()
    val companies       = mutableStateListOf<Company>()
    val calendarItems   = mutableStateListOf<CalendarItem>()
    val notes           = mutableStateListOf<Note>()
    val gifts           = mutableStateListOf<GiftIdea>()
    val companyRelations    = mutableStateListOf<ContactCompanyRelation>()
    val contactRelations    = mutableStateListOf<ContactRelation>()
    val addresses           = mutableStateListOf<Address>()
    val sizeInfos           = mutableStateListOf<SizeInfo>()
    val personalDetails     = mutableStateListOf<PersonalDetail>()

    private var database: SocialsphereDatabase? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false

    fun initialize(db: SocialsphereDatabase) {
        if (isInitialized) return
        database = db
        isInitialized = true
        scope.launch { loadInitialData() }
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    fun generateId(): String = UUID.randomUUID().toString()

    // ──────────────────────────────────────────────────────────
    //  INITIAL LOAD
    // ──────────────────────────────────────────────────────────
    private suspend fun loadInitialData() {
        val contactDao = database!!.contactDao()
        val existingContacts = contactDao.getAllContacts()
        if (existingContacts.isEmpty()) {
            DemoDataProvider.contacts.forEach         { addContactDb(it) }
            DemoDataProvider.companies.forEach        { addCompanyDb(it) }
            DemoDataProvider.calendarItems.forEach    { addCalendarItemDb(it) }
            database!!.noteDao().insertNotes(DemoDataProvider.notes.map { it.toEntity() })
            database!!.giftDao().insertGifts(DemoDataProvider.gifts.map { it.toEntity() })
            contactDao.insertCompanyRelations(DemoDataProvider.companyRelations.map { it.toEntity() })
            contactDao.insertContactRelations(DemoDataProvider.contactRelations.map { it.toEntity() })
            database!!.addressDao().insertAddresses(DemoDataProvider.addresses.map { it.toEntity() })
            DemoDataProvider.sizeInfos.forEach        { contactDao.insertSizeInfo(it.toEntity()) }
            contactDao.insertPersonalDetails(DemoDataProvider.personalDetails.map { it.toEntity() })
        }
        reloadFromDb()
    }

    suspend fun reloadFromDb() {
        val db = database ?: return
        val contactEntities  = db.contactDao().getAllContacts()
        val companyEntities  = db.companyDao().getAllCompanies()
        val calendarEntities = db.calendarDao().getAllCalendarItems()
        val noteEntities     = db.noteDao().getAllNotes()
        val giftEntities     = db.giftDao().getAllGifts()
        val compRelEntities  = db.contactDao().getContactCompanyRelations()
        val contRelEntities  = db.contactDao().getContactRelations()
        val addrEntities     = db.addressDao().getAllAddresses()
        val sizeEntities     = db.contactDao().getSizeInfos()
        val pdEntities       = db.contactDao().getPersonalDetails()
        val phones           = db.contactDao().getContactPhones().map { it.toDomain() }
        val emails           = db.contactDao().getContactEmails().map { it.toDomain() }
        val messengers       = db.contactDao().getMessengers().map { it.toDomain() }
        val links            = db.calendarDao().getCalendarItemLinks().map { it.toDomain() }
        val reminders        = db.calendarDao().getReminderRules().map { it.toDomain() }

        withContext(Dispatchers.Main) {
            notes.clear();           notes.addAll(noteEntities.map { it.toDomain() })
            gifts.clear();           gifts.addAll(giftEntities.map { it.toDomain() })
            companyRelations.clear();companyRelations.addAll(compRelEntities.map { it.toDomain() })
            contactRelations.clear();contactRelations.addAll(contRelEntities.map { it.toDomain() })
            addresses.clear();       addresses.addAll(addrEntities.map { it.toDomain() })
            sizeInfos.clear();       sizeInfos.addAll(sizeEntities.map { it.toDomain() })
            personalDetails.clear(); personalDetails.addAll(pdEntities.map { it.toDomain() })

            companies.clear()
            companies.addAll(companyEntities.map { c ->
                c.toDomain().copy(
                    phones    = emptyList(),
                    emails    = emptyList(),
                    addresses = addresses.filter { it.ownerType == AddressOwnerType.COMPANY && it.ownerId == c.id }
                )
            })

            calendarItems.clear()
            calendarItems.addAll(calendarEntities.map { c ->
                c.toDomain().copy(
                    links     = links.filter     { it.calendarItemId == c.id },
                    reminders = reminders.filter { it.calendarItemId == c.id }
                )
            })

            contacts.clear()
            contacts.addAll(contactEntities.map { c ->
                c.toDomain().copy(
                    companyRelations = companyRelations.filter { it.contactId == c.id },
                    phones           = phones.filter     { it.contactId == c.id },
                    emails           = emails.filter     { it.contactId == c.id },
                    messengers       = messengers.filter { it.contactId == c.id },
                    addresses        = addresses.filter  { it.ownerType == AddressOwnerType.CONTACT && it.ownerId == c.id },
                    notes            = notes.filter      { it.contactId == c.id },
                    gifts            = gifts.filter      { it.contactId == c.id },
                    sizeInfo         = sizeInfos.find    { it.contactId == c.id },
                    personalDetails  = personalDetails.filter { it.contactId == c.id }
                )
            })
        }
    }

    // ──────────────────────────────────────────────────────────
    //  DB WRITE HELPERS
    // ──────────────────────────────────────────────────────────
    private suspend fun addContactDb(contact: Contact) {
        database!!.contactDao().insertContact(contact.toEntity())
        if (contact.phones.isNotEmpty())     database!!.contactDao().insertPhones(contact.phones.map { it.toEntity() })
        if (contact.emails.isNotEmpty())     database!!.contactDao().insertEmails(contact.emails.map { it.toEntity() })
        if (contact.messengers.isNotEmpty()) database!!.contactDao().insertMessengers(contact.messengers.map { it.toEntity() })
        if (contact.companyRelations.isNotEmpty()) database!!.contactDao().insertCompanyRelations(contact.companyRelations.map { it.toEntity() })
        if (contact.addresses.isNotEmpty())  database!!.addressDao().insertAddresses(contact.addresses.map { it.toEntity() })
    }

    private suspend fun addCompanyDb(company: Company) {
        database!!.companyDao().insertCompany(company.toEntity())
        if (company.addresses.isNotEmpty()) database!!.addressDao().insertAddresses(company.addresses.map { it.toEntity() })
    }

    private suspend fun addCalendarItemDb(item: CalendarItem) {
        database!!.calendarDao().insertCalendarItem(item.toEntity())
        if (item.links.isNotEmpty())     database!!.calendarDao().insertCalendarItemLinks(item.links.map { it.toEntity() })
        if (item.reminders.isNotEmpty()) database!!.calendarDao().insertReminderRules(item.reminders.map { it.toEntity() })
    }

    // ──────────────────────────────────────────────────────────
    //  CONTACTS CRUD
    // ──────────────────────────────────────────────────────────
    fun getContactById(id: String): Contact? = contacts.find { it.id == id }
    fun getContact(id: String): Contact?     = getContactById(id)

    fun addContact(contact: Contact) {
        val c = contact.copy(createdAt = nowIso(), updatedAt = nowIso())
        contacts.add(c)
        scope.launch { addContactDb(c) }
    }

    fun updateContact(contact: Contact) {
        val c = contact.copy(updatedAt = nowIso())
        val idx = contacts.indexOfFirst { it.id == c.id }
        if (idx >= 0) {
            contacts[idx] = c
            scope.launch {
                database!!.contactDao().deletePhonesForContact(c.id)
                database!!.contactDao().deleteEmailsForContact(c.id)
                database!!.contactDao().deleteMessengersForContact(c.id)
                database!!.contactDao().deleteCompanyRelationsForContact(c.id)
                database!!.addressDao().deleteAddressesForOwner(c.id, AddressOwnerType.CONTACT.name)
                addContactDb(c)
            }
        }
    }

    fun deleteContact(contactId: String) {
        contacts.removeAll { it.id == contactId }
        notes.removeAll { it.contactId == contactId }
        gifts.removeAll { it.contactId == contactId }
        sizeInfos.removeAll { it.contactId == contactId }
        personalDetails.removeAll { it.contactId == contactId }
        addresses.removeAll { it.ownerId == contactId && it.ownerType == AddressOwnerType.CONTACT }
        companyRelations.removeAll { it.contactId == contactId }
        contactRelations.removeAll { it.firstContactId == contactId || it.secondContactId == contactId }
        scope.launch {
            database!!.contactDao().deleteContact(contactId)
            database!!.contactDao().deletePhonesForContact(contactId)
            database!!.contactDao().deleteEmailsForContact(contactId)
            database!!.contactDao().deleteMessengersForContact(contactId)
            database!!.contactDao().deleteCompanyRelationsForContact(contactId)
            database!!.addressDao().deleteAddressesForOwner(contactId, AddressOwnerType.CONTACT.name)
            database!!.noteDao().deleteNotesForContact(contactId)
            database!!.giftDao().deleteGiftsForContact(contactId)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  COMPANIES CRUD
    // ──────────────────────────────────────────────────────────
    fun getCompanyById(id: String): Company? = companies.find { it.id == id }
    fun getCompany(id: String): Company?     = getCompanyById(id)

    fun addCompany(company: Company) {
        val c = company.copy(createdAt = nowIso(), updatedAt = nowIso())
        companies.add(c)
        scope.launch { addCompanyDb(c) }
    }

    fun updateCompany(company: Company) {
        val c = company.copy(updatedAt = nowIso())
        val idx = companies.indexOfFirst { it.id == c.id }
        if (idx >= 0) {
            companies[idx] = c
            scope.launch {
                database!!.addressDao().deleteAddressesForOwner(c.id, AddressOwnerType.COMPANY.name)
                addCompanyDb(c)
            }
        }
    }

    fun deleteCompany(companyId: String) {
        companies.removeAll { it.id == companyId }
        addresses.removeAll { it.ownerId == companyId && it.ownerType == AddressOwnerType.COMPANY }
        companyRelations.removeAll { it.companyId == companyId }
        scope.launch {
            database!!.companyDao().deleteCompany(companyId)
            database!!.addressDao().deleteAddressesForOwner(companyId, AddressOwnerType.COMPANY.name)
            database!!.noteDao().deleteNotesForCompany(companyId)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  CALENDAR CRUD
    // ──────────────────────────────────────────────────────────
    fun getCalendarItemById(id: String): CalendarItem? = calendarItems.find { it.id == id }

    fun addCalendarItem(item: CalendarItem) {
        val c = item.copy(createdAt = nowIso(), updatedAt = nowIso())
        calendarItems.add(c)
        scope.launch { addCalendarItemDb(c) }
    }

    fun updateCalendarItem(item: CalendarItem) {
        val c = item.copy(updatedAt = nowIso())
        val idx = calendarItems.indexOfFirst { it.id == c.id }
        if (idx >= 0) {
            calendarItems[idx] = c
            scope.launch {
                database!!.calendarDao().deleteLinksForItem(c.id)
                database!!.calendarDao().deleteRemindersForItem(c.id)
                addCalendarItemDb(c)
            }
        }
    }

    fun deleteCalendarItem(itemId: String) {
        calendarItems.removeAll { it.id == itemId }
        scope.launch {
            database!!.calendarDao().deleteCalendarItem(itemId)
            database!!.calendarDao().deleteLinksForItem(itemId)
            database!!.calendarDao().deleteRemindersForItem(itemId)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  NOTES CRUD
    // ──────────────────────────────────────────────────────────
    fun addNote(note: Note) {
        val n = note.copy(createdAt = nowIso(), updatedAt = nowIso())
        notes.add(n)
        // also update contact's embedded notes list
        n.contactId?.let { cid ->
            val idx = contacts.indexOfFirst { it.id == cid }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(notes = contacts[idx].notes + n)
        }
        scope.launch { database!!.noteDao().insertNotes(listOf(n.toEntity())) }
    }

    fun deleteNote(noteId: String) {
        val n = notes.find { it.id == noteId }
        notes.removeAll { it.id == noteId }
        n?.contactId?.let { cid ->
            val idx = contacts.indexOfFirst { it.id == cid }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(notes = contacts[idx].notes.filter { it.id != noteId })
        }
        scope.launch { database!!.noteDao().deleteNote(noteId) }
    }

    // ──────────────────────────────────────────────────────────
    //  GIFTS CRUD
    // ──────────────────────────────────────────────────────────
    fun addGift(gift: GiftIdea) {
        gifts.add(gift)
        val idx = contacts.indexOfFirst { it.id == gift.contactId }
        if (idx >= 0) contacts[idx] = contacts[idx].copy(gifts = contacts[idx].gifts + gift)
        scope.launch { database!!.giftDao().insertGifts(listOf(gift.toEntity())) }
    }

    fun updateGift(gift: GiftIdea) {
        val idx = gifts.indexOfFirst { it.id == gift.id }
        if (idx >= 0) gifts[idx] = gift
        val cidx = contacts.indexOfFirst { it.id == gift.contactId }
        if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(gifts = contacts[cidx].gifts.map { if (it.id == gift.id) gift else it })
        scope.launch { database!!.giftDao().insertGifts(listOf(gift.toEntity())) }
    }

    fun deleteGift(giftId: String) {
        val g = gifts.find { it.id == giftId }
        gifts.removeAll { it.id == giftId }
        g?.let { gift ->
            val idx = contacts.indexOfFirst { it.id == gift.contactId }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(gifts = contacts[idx].gifts.filter { it.id != giftId })
        }
        scope.launch { database!!.giftDao().deleteGift(giftId) }
    }
}
