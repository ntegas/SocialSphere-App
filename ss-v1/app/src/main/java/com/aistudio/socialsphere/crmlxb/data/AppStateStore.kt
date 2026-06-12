package com.aistudio.socialsphere.crmlxb.data

import androidx.compose.runtime.mutableStateListOf
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.data.local.*
import kotlinx.coroutines.*
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

    // Безопасный доступ к БД — не крашит если не инициализирована
    private fun db(): SocialsphereDatabase? {
        return database ?: run {
            android.util.Log.e("AppStateStore", "Database not initialized")
            null
        }
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    fun generateId(): String = UUID.randomUUID().toString()

    // ──────────────────────────────────────────────────────────
    //  INITIAL LOAD
    // ──────────────────────────────────────────────────────────
    private suspend fun loadInitialData() {
        val db = db() ?: return
        val contactDao = db.contactDao()
        val existingContacts = contactDao.getAllContacts()
        if (existingContacts.isEmpty()) {
            DemoDataProvider.contacts.forEach      { addContactDb(it) }
            DemoDataProvider.companies.forEach     { addCompanyDb(it) }
            DemoDataProvider.calendarItems.forEach { addCalendarItemDb(it) }
            db.noteDao().insertNotes(DemoDataProvider.notes.map { it.toEntity() })
            db.giftDao().insertGifts(DemoDataProvider.gifts.map { it.toEntity() })
            contactDao.insertCompanyRelations(DemoDataProvider.companyRelations.map { it.toEntity() })
            contactDao.insertContactRelations(DemoDataProvider.contactRelations.map { it.toEntity() })
            db.addressDao().insertAddresses(DemoDataProvider.addresses.map { it.toEntity() })
            DemoDataProvider.sizeInfos.forEach     { contactDao.insertSizeInfo(it.toEntity()) }
            contactDao.insertPersonalDetails(DemoDataProvider.personalDetails.map { it.toEntity() })
        }
        reloadFromDb()
    }

    suspend fun reloadFromDb() {
        val db = db() ?: return
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
        val phoneEntities    = db.contactDao().getContactPhones()
        val emailEntities    = db.contactDao().getContactEmails()
        // Общие таблицы: строки контактов и строки компаний разделяем по companyId
        val phones           = phoneEntities.filter { it.companyId == null }.map { it.toDomain() }
        val emails           = emailEntities.filter { it.companyId == null }.map { it.toDomain() }
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
                    phones    = phoneEntities.filter { it.companyId == c.id }.map { it.toDomain() },
                    emails    = emailEntities.filter { it.companyId == c.id }.map { it.toDomain() },
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
        val db = db() ?: return
        db.contactDao().insertContact(contact.toEntity())
        if (contact.phones.isNotEmpty())          db.contactDao().insertPhones(contact.phones.map { it.toEntity() })
        if (contact.emails.isNotEmpty())          db.contactDao().insertEmails(contact.emails.map { it.toEntity() })
        if (contact.messengers.isNotEmpty())      db.contactDao().insertMessengers(contact.messengers.map { it.toEntity() })
        if (contact.companyRelations.isNotEmpty())db.contactDao().insertCompanyRelations(contact.companyRelations.map { it.toEntity() })
        if (contact.addresses.isNotEmpty())       db.addressDao().insertAddresses(contact.addresses.map { it.toEntity() })
        if (contact.personalDetails.isNotEmpty()) db.contactDao().insertPersonalDetails(contact.personalDetails.map { it.toEntity() })
        contact.sizeInfo?.let { db.contactDao().insertSizeInfo(it.toEntity()) }
    }

    private suspend fun addCompanyDb(company: Company) {
        val db = db() ?: return
        db.companyDao().insertCompany(company.toEntity())
        if (company.addresses.isNotEmpty()) db.addressDao().insertAddresses(company.addresses.map { it.toEntity() })
        if (company.phones.isNotEmpty())    db.contactDao().insertPhones(company.phones.map { it.toCompanyEntity(company.id) })
        if (company.emails.isNotEmpty())    db.contactDao().insertEmails(company.emails.map { it.toCompanyEntity(company.id) })
    }

    private suspend fun addCalendarItemDb(item: CalendarItem) {
        val db = db() ?: return
        db.calendarDao().insertCalendarItem(item.toEntity())
        if (item.links.isNotEmpty())     db.calendarDao().insertCalendarItemLinks(item.links.map { it.toEntity() })
        if (item.reminders.isNotEmpty()) db.calendarDao().insertReminderRules(item.reminders.map { it.toEntity() })
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
                val db = db() ?: return@launch
                db.contactDao().deletePhonesForContact(c.id)
                db.contactDao().deleteEmailsForContact(c.id)
                db.contactDao().deleteMessengersForContact(c.id)
                db.contactDao().deleteCompanyRelationsForContact(c.id)
                db.contactDao().deletePersonalDetailsForContact(c.id)
                db.contactDao().deleteSizeInfoForContact(c.id)
                db.addressDao().deleteAddressesForOwner(c.id, AddressOwnerType.CONTACT.name)
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
            val db = db() ?: return@launch
            db.contactDao().deleteContact(contactId)
            db.contactDao().deletePhonesForContact(contactId)
            db.contactDao().deleteEmailsForContact(contactId)
            db.contactDao().deleteMessengersForContact(contactId)
            db.contactDao().deleteCompanyRelationsForContact(contactId)
            db.addressDao().deleteAddressesForOwner(contactId, AddressOwnerType.CONTACT.name)
            db.noteDao().deleteNotesForContact(contactId)
            db.giftDao().deleteGiftsForContact(contactId)
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
                val db = db() ?: return@launch
                db.addressDao().deleteAddressesForOwner(c.id, AddressOwnerType.COMPANY.name)
                db.companyDao().deletePhonesForCompany(c.id)
                db.companyDao().deleteEmailsForCompany(c.id)
                addCompanyDb(c)
            }
        }
    }

    fun deleteCompany(companyId: String) {
        companies.removeAll { it.id == companyId }
        addresses.removeAll { it.ownerId == companyId && it.ownerType == AddressOwnerType.COMPANY }
        companyRelations.removeAll { it.companyId == companyId }
        scope.launch {
            val db = db() ?: return@launch
            db.companyDao().deleteCompany(companyId)
            db.addressDao().deleteAddressesForOwner(companyId, AddressOwnerType.COMPANY.name)
            db.companyDao().deletePhonesForCompany(companyId)
            db.companyDao().deleteEmailsForCompany(companyId)
            db.noteDao().deleteNotesForCompany(companyId)
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
                val db = db() ?: return@launch
                db.calendarDao().deleteLinksForItem(c.id)
                db.calendarDao().deleteRemindersForItem(c.id)
                addCalendarItemDb(c)
            }
        }
    }

    fun deleteCalendarItem(itemId: String) {
        calendarItems.removeAll { it.id == itemId }
        scope.launch {
            val db = db() ?: return@launch
            db.calendarDao().deleteCalendarItem(itemId)
            db.calendarDao().deleteLinksForItem(itemId)
            db.calendarDao().deleteRemindersForItem(itemId)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  NOTES CRUD
    // ──────────────────────────────────────────────────────────
    /** Полное удаление всех данных пользователя: все таблицы Room +
     *  in-memory списки. После перезапуска засеются демо-данные (чистый старт). */
    fun wipeAllData(onDone: () -> Unit = {}) {
        scope.launch {
            try { db()?.clearAllTables() } catch (e: Exception) { /* БД могла не открыться */ }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                contacts.clear(); companies.clear(); calendarItems.clear()
                notes.clear(); gifts.clear(); companyRelations.clear()
                contactRelations.clear(); addresses.clear()
                sizeInfos.clear(); personalDetails.clear()
                onDone()
            }
        }
    }

    /** Сохранение размеров: глобальный список (его читает вкладка Подарки),
     *  копия в контакте и Room — всё синхронно. */
    fun setSizeInfo(contactId: String, sizeInfo: SizeInfo) {
        val idx = sizeInfos.indexOfFirst { it.contactId == contactId }
        if (idx >= 0) sizeInfos[idx] = sizeInfo else sizeInfos.add(sizeInfo)
        val cidx = contacts.indexOfFirst { it.id == contactId }
        if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(sizeInfo = sizeInfo)
        scope.launch { db()?.contactDao()?.insertSizeInfo(sizeInfo.toEntity()) }
    }

    // ── Связи между контактами (семья и др.) ─────────────────
    fun addContactRelation(relation: ContactRelation) {
        if (contactRelations.any { it.id == relation.id }) return
        contactRelations.add(relation)
        scope.launch { db()?.contactDao()?.insertContactRelations(listOf(relation.toEntity())) }
    }

    fun removeContactRelation(relationId: String) {
        contactRelations.removeAll { it.id == relationId }
        scope.launch { db()?.contactDao()?.deleteContactRelation(relationId) }
    }

    fun addNote(note: Note) {
        val now = nowIso()
        val n = note.copy(createdAt = now, updatedAt = now)
        notes.add(n)
        // Обновляем список заметок в контакте + lastContactDate автоматически
        n.contactId?.let { cid ->
            val idx = contacts.indexOfFirst { it.id == cid }
            if (idx >= 0) {
                contacts[idx] = contacts[idx].copy(
                    notes           = contacts[idx].notes + n,
                    lastContactDate = now.take(10)  // ← автообновление
                )
                // Сохраняем lastContactDate в БД легковесным запросом
                scope.launch {
                    db()?.contactDao()?.updateLastContactDate(cid, now.take(10), now)
                }
            }
        }
        scope.launch { db()?.noteDao()?.insertNotes(listOf(n.toEntity())) }
    }

    fun updateNote(note: Note) {
        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note
        note.contactId?.let { cid ->
            val cidx = contacts.indexOfFirst { it.id == cid }
            if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
                notes = contacts[cidx].notes.map { if (it.id == note.id) note else it }
            )
        }
        // lastContactDate намеренно не трогаем: правка старой записи — не новая активность
        scope.launch { db()?.noteDao()?.insertNotes(listOf(note.toEntity())) }
    }

    fun deleteNote(noteId: String) {
        val n = notes.find { it.id == noteId }
        notes.removeAll { it.id == noteId }
        n?.contactId?.let { cid ->
            val idx = contacts.indexOfFirst { it.id == cid }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(
                notes = contacts[idx].notes.filter { it.id != noteId }
            )
        }
        scope.launch { db()?.noteDao()?.deleteNote(noteId) }
    }

    // ──────────────────────────────────────────────────────────
    //  GIFTS CRUD
    // ──────────────────────────────────────────────────────────
    fun addGift(gift: GiftIdea) {
        gifts.add(gift)
        val idx = contacts.indexOfFirst { it.id == gift.contactId }
        if (idx >= 0) contacts[idx] = contacts[idx].copy(gifts = contacts[idx].gifts + gift)
        scope.launch { db()?.giftDao()?.insertGifts(listOf(gift.toEntity())) }
    }

    fun updateGift(gift: GiftIdea) {
        val idx = gifts.indexOfFirst { it.id == gift.id }
        if (idx >= 0) gifts[idx] = gift
        val cidx = contacts.indexOfFirst { it.id == gift.contactId }
        if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
            gifts = contacts[cidx].gifts.map { if (it.id == gift.id) gift else it }
        )
        scope.launch { db()?.giftDao()?.insertGifts(listOf(gift.toEntity())) }
    }

    fun deleteGift(giftId: String) {
        val g = gifts.find { it.id == giftId }
        gifts.removeAll { it.id == giftId }
        g?.let { gift ->
            val idx = contacts.indexOfFirst { it.id == gift.contactId }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(
                gifts = contacts[idx].gifts.filter { it.id != giftId }
            )
        }
        scope.launch { db()?.giftDao()?.deleteGift(giftId) }
    }
}
