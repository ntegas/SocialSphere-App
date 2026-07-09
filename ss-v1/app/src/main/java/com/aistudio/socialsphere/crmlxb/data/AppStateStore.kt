package com.aistudio.socialsphere.crmlxb.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    // Группы контактов (как в телефонной книге) + членство
    val groups              = mutableStateListOf<ContactGroup>()
    val groupMembers        = mutableStateListOf<ContactGroupMember>()

    private var database: SocialsphereDatabase? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false

    // true пока идёт первичная загрузка из БД. Экраны показывают спиннер вместо
    // «ничего не найдено», иначе на холодном старте пустой экран выглядел как
    // потеря данных.
    var isLoading by mutableStateOf(true)
        private set

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
        try {
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
        } finally {
            withContext(Dispatchers.Main) { isLoading = false }
        }
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

        val groupEntities  = db.contactDao().getContactGroups()
        val memberEntities = db.contactDao().getContactGroupMembers()

        withContext(Dispatchers.Main) {
            groups.clear();          groups.addAll(groupEntities.map { it.toDomain() })
            groupMembers.clear();    groupMembers.addAll(memberEntities.map { it.toDomain() })
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
        // Карта/карточка компании читают ГЛОБАЛЬНЫЕ addresses/companyRelations —
        // синхронизируем сразу, иначе связь контакт↔компания и адреса на карте
        // не видны до перезапуска приложения (аналогично updateContact).
        addresses.addAll(c.addresses)
        companyRelations.addAll(c.companyRelations)
        scope.launch { addContactDb(c) }
    }

    fun updateContact(contact: Contact) {
        val c = contact.copy(updatedAt = nowIso())
        val idx = contacts.indexOfFirst { it.id == c.id }
        if (idx >= 0) {
            contacts[idx] = c
            // Карта читает ГЛОБАЛЬНЫЙ addresses — синхронизируем, иначе
            // новые/изменённые адреса невидимы на карте до перезапуска
            addresses.removeAll { it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT }
            addresses.addAll(c.addresses)
            // Карточка компании читает ГЛОБАЛЬНЫЙ companyRelations — синхронизируем,
            // иначе связанный контакт не виден в компании до перезапуска
            companyRelations.removeAll { it.contactId == c.id }
            companyRelations.addAll(c.companyRelations)
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

    /**
     * Восстановление контакта из бэкапа БЕЗ перезаписи дат createdAt/updatedAt
     * (в отличие от addContact/updateContact). Idempotent: сначала чистит детей,
     * потом вставляет как есть — повторный импорт того же файла не плодит дубли.
     */
    fun restoreContact(contact: Contact) {
        val idx = contacts.indexOfFirst { it.id == contact.id }
        if (idx >= 0) contacts[idx] = contact else contacts.add(contact)
        addresses.removeAll { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
        addresses.addAll(contact.addresses)
        scope.launch {
            val db = db() ?: return@launch
            db.contactDao().deletePhonesForContact(contact.id)
            db.contactDao().deleteEmailsForContact(contact.id)
            db.contactDao().deleteMessengersForContact(contact.id)
            db.contactDao().deleteCompanyRelationsForContact(contact.id)
            db.contactDao().deletePersonalDetailsForContact(contact.id)
            db.contactDao().deleteSizeInfoForContact(contact.id)
            db.addressDao().deleteAddressesForOwner(contact.id, AddressOwnerType.CONTACT.name)
            addContactDb(contact)
        }
    }

    /** Восстановление группы из бэкапа: upsert по id, сохраняя точные значения
     *  (в отличие от renameGroup/addGroup, которые генерируют id/updatedAt заново). */
    fun restoreGroup(group: ContactGroup) {
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) groups[idx] = group else groups.add(group)
        scope.launch { db()?.contactDao()?.insertContactGroup(group.toEntity()) }
    }

    /** Восстановление членства в группе из бэкапа: добавляем, только если такой
     *  записи ещё нет (как contactRelations — join-строка неизменяема). */
    fun restoreGroupMember(member: ContactGroupMember) {
        if (groupMembers.none { it.id == member.id }) {
            groupMembers.add(member)
            scope.launch { db()?.contactDao()?.insertContactGroupMembers(listOf(member.toEntity())) }
        }
    }

    fun deleteContact(contactId: String) {
        // Каскад: собираем id связей ДО удаления из памяти, чтобы потом
        // вычистить их и из БД (иначе оставались сиротами).
        val relationIds = contactRelations
            .filter { it.firstContactId == contactId || it.secondContactId == contactId }
            .map { it.id }
        contacts.removeAll { it.id == contactId }
        notes.removeAll { it.contactId == contactId }
        gifts.removeAll { it.contactId == contactId }
        sizeInfos.removeAll { it.contactId == contactId }
        personalDetails.removeAll { it.contactId == contactId }
        addresses.removeAll { it.ownerId == contactId && it.ownerType == AddressOwnerType.CONTACT }
        companyRelations.removeAll { it.contactId == contactId }
        contactRelations.removeAll { it.firstContactId == contactId || it.secondContactId == contactId }
        groupMembers.removeAll { it.contactId == contactId }
        scope.launch {
            val db = db() ?: return@launch
            db.contactDao().deleteGroupMembersForContact(contactId)
            db.contactDao().deleteContact(contactId)
            db.contactDao().deletePhonesForContact(contactId)
            db.contactDao().deleteEmailsForContact(contactId)
            db.contactDao().deleteMessengersForContact(contactId)
            db.contactDao().deleteCompanyRelationsForContact(contactId)
            db.contactDao().deletePersonalDetailsForContact(contactId)
            db.contactDao().deleteSizeInfoForContact(contactId)
            relationIds.forEach { db.contactDao().deleteContactRelation(it) }
            db.addressDao().deleteAddressesForOwner(contactId, AddressOwnerType.CONTACT.name)
            db.noteDao().deleteNotesForContact(contactId)
            db.giftDao().deleteGiftsForContact(contactId)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  ДУБЛИКАТЫ: поиск и слияние
    // ──────────────────────────────────────────────────────────
    // internal (не private) — виден юнит-тестам дедупликации в том же модуле
    // (PhoneDedupeTest), прод-логика/видимость снаружи модуля не меняется.
    internal fun phoneDigits(s: String): String = s.filter { it.isDigit() }.takeLast(10)

    /** Пара возможных дублей + ПРИЧИНА совпадения (фидбэк 2026-07-04: «непонятно
     *  чем связаны» — совпадал канал, не показанный в превью). */
    data class DuplicateMatch(
        val a: Contact,
        val b: Contact,
        val byPhone: String? = null, // совпавший номер (последние цифры)
        val byEmail: String? = null, // совпавший email
    )

    // Общие ящики офисов — совпадение по ним НЕ признак дубля людей
    private val genericEmailLocalParts = setOf(
        "info", "office", "sales", "support", "contact", "hello", "mail", "admin", "hr"
    )

    /** Пары возможных дублей: совпадение по нормализованному телефону (≥7 цифр,
     *  НЕ общий рабочий у обоих) или по email (НЕ generic-ящик типа info@).
     *  Без повторов и без пар «сам с собой». */
    fun findDuplicatePairs(): List<DuplicateMatch> {
        val list = contacts.toList()
        val result = mutableListOf<DuplicateMatch>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                // Телефон: пары (цифры, тип); общий РАБОЧИЙ у обоих — не дубль
                val ap = a.phones.map { phoneDigits(it.number) to it.type }.filter { it.first.length >= 7 }
                val bp = b.phones.map { phoneDigits(it.number) to it.type }.filter { it.first.length >= 7 }
                val phoneHit = ap.firstNotNullOfOrNull { (d, at) ->
                    bp.firstOrNull { (bd, bt) ->
                        bd == d && !(at == PhoneType.WORK && bt == PhoneType.WORK)
                    }?.let { d }
                }
                // Email: generic-ящики (info@, office@…) пропускаем
                fun cleanEmails(c: Contact) = c.emails
                    .map { it.email.trim().lowercase() }
                    .filter { it.isNotBlank() && it.substringBefore("@") !in genericEmailLocalParts }
                val emailHit = cleanEmails(a).firstOrNull { it in cleanEmails(b) }
                if (phoneHit != null || emailHit != null) {
                    result.add(DuplicateMatch(a, b, byPhone = phoneHit, byEmail = emailHit))
                }
            }
        }
        return result
    }

    /** Свободный текст двух контактов при слиянии: пусто → другое; совпадают
     *  (без учёта регистра) → одно; иначе — ОБА через " / ", ничего не теряется
     *  молча (владелец потом сам решает, что оставить, в редактировании). */
    private fun combineText(a: String?, b: String?): String? {
        val ca = a?.trim()?.takeIf { it.isNotBlank() }
        val cb = b?.trim()?.takeIf { it.isNotBlank() }
        return when {
            ca == null -> cb
            cb == null -> ca
            ca.equals(cb, ignoreCase = true) -> ca
            else -> "$ca / $cb"
        }
    }

    /**
     * Слияние контакта other в keep. Под-данные объединяются (новые id у
     * перенесённых, чтобы не было коллизий PK), заметки/подарки/связи/ссылки
     * событий пере-привязываются на keep, затем other удаляется каскадом.
     * Переиспользует существующие безопасные методы (updateContact/deleteContact/
     * updateNote/updateGift/updateCalendarItem) — без сырых DAO-вызовов.
     */
    fun mergeContacts(keepId: String, otherId: String) {
        if (keepId == otherId) return
        val keep = getContact(keepId) ?: return
        val other = getContact(otherId) ?: return

        val mergedPhones = keep.phones.toMutableList()
        other.phones.forEach { p ->
            val d = phoneDigits(p.number)
            if (d.isEmpty() || mergedPhones.none { phoneDigits(it.number) == d })
                mergedPhones.add(p.copy(id = generateId(), contactId = keepId, isPrimary = false))
        }
        val mergedEmails = keep.emails.toMutableList()
        other.emails.forEach { e ->
            if (mergedEmails.none { it.email.equals(e.email, true) })
                mergedEmails.add(e.copy(id = generateId(), contactId = keepId, isPrimary = false))
        }
        val mergedMessengers = keep.messengers.toMutableList()
        other.messengers.forEach { m ->
            if (mergedMessengers.none { it.type == m.type && it.value.equals(m.value, true) })
                mergedMessengers.add(m.copy(id = generateId(), contactId = keepId, isPrimary = false))
        }
        val mergedCompRels = keep.companyRelations.toMutableList()
        other.companyRelations.forEach { r ->
            if (mergedCompRels.none { it.companyId == r.companyId })
                mergedCompRels.add(r.copy(id = generateId(), contactId = keepId, isPrimary = false))
        }
        val mergedAddresses = keep.addresses.toMutableList()
        other.addresses.forEach { a ->
            if (mergedAddresses.none { it.addressLine.equals(a.addressLine, true) && it.city.equals(a.city, true) })
                mergedAddresses.add(a.copy(id = generateId(), ownerId = keepId))
        }
        val mergedPd = keep.personalDetails.toMutableList()
        other.personalDetails.forEach { pd ->
            if (mergedPd.none { it.category == pd.category && it.value.equals(pd.value, true) })
                mergedPd.add(pd.copy(id = generateId(), contactId = keepId))
        }

        val merged = keep.copy(
            // Свободный текст: если оба контакта несут РАЗНУЮ информацию, она
            // раньше терялась НАВСЕГДА (побеждало непустое значение keep, other
            // просто отбрасывался, второй контакт удалялся). Фидбэк владельца
            // 2026-07-05: «мне нужна вся информация, и потом я её редактирую» —
            // теперь при расхождении оба значения сохраняются рядом (через
            // combineText), ничего не решается за владельца молча; он потом
            // сам подчищает объединённую строку в редактировании.
            nickname        = combineText(keep.nickname, other.nickname),
            photoUri        = keep.photoUri ?: other.photoUri,
            profession      = combineText(keep.profession, other.profession),
            nextStep        = combineText(keep.nextStep, other.nextStep),
            canHelpWith     = combineText(keep.canHelpWith, other.canHelpWith),
            iCanHelpWith    = combineText(keep.iCanHelpWith, other.iCanHelpWith),
            talkingPoints   = combineText(keep.talkingPoints, other.talkingPoints),
            meetContext     = combineText(keep.meetContext, other.meetContext),
            meetDate        = keep.meetDate ?: other.meetDate,
            familyNote      = combineText(keep.familyNote, other.familyNote),
            // Структура имени (v13) — раньше не участвовала в слиянии вообще,
            // отчество/приставка/суффикс/фонетика другого контакта терялись.
            middleName        = keep.middleName ?: other.middleName,
            namePrefix         = keep.namePrefix ?: other.namePrefix,
            nameSuffix         = keep.nameSuffix ?: other.nameSuffix,
            phoneticFirstName  = keep.phoneticFirstName ?: other.phoneticFirstName,
            phoneticLastName   = keep.phoneticLastName ?: other.phoneticLastName,
            customRelationshipType = keep.customRelationshipType ?: other.customRelationshipType,
            lastContactDate = listOfNotNull(keep.lastContactDate, other.lastContactDate).maxOrNull(),
            tags            = (keep.tags + other.tags).distinct(),
            phones          = mergedPhones,
            emails          = mergedEmails,
            messengers      = mergedMessengers,
            companyRelations= mergedCompRels,
            addresses       = mergedAddresses,
            personalDetails = mergedPd,
            sizeInfo        = keep.sizeInfo ?: other.sizeInfo?.copy(id = generateId(), contactId = keepId)
        )

        // Заметки и подарки: пере-привязка (тот же id, меняем contactId).
        notes.filter { it.contactId == otherId }.toList().forEach { updateNote(it.copy(contactId = keepId)) }
        gifts.filter { it.contactId == otherId }.toList().forEach { updateGift(it.copy(contactId = keepId)) }

        // Связи (семья): пере-привязка без само-связей и дублей.
        contactRelations.filter { it.firstContactId == otherId || it.secondContactId == otherId }.toList().forEach { rel ->
            val nf = if (rel.firstContactId == otherId) keepId else rel.firstContactId
            val ns = if (rel.secondContactId == otherId) keepId else rel.secondContactId
            removeContactRelation(rel.id)
            if (nf != ns && contactRelations.none {
                    (it.firstContactId == nf && it.secondContactId == ns) ||
                    (it.firstContactId == ns && it.secondContactId == nf)
                }) {
                addContactRelation(rel.copy(id = generateId(), firstContactId = nf, secondContactId = ns))
            }
        }

        // Ссылки событий: targetId other → keep.
        calendarItems.filter { ci -> ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId == otherId } }
            .toList().forEach { ci ->
                val newLinks = ci.links.map {
                    if (it.targetType == CalendarTargetType.CONTACT && it.targetId == otherId) it.copy(targetId = keepId) else it
                }.distinctBy { it.targetType to it.targetId }
                updateCalendarItem(ci.copy(links = newLinks))
            }

        // Группы: членство other переносится на keep (без дублей);
        // членство самого other вычистит каскад deleteContact.
        val mergedGroupIds =
            groupMembers.filter { it.contactId == keepId }.map { it.groupId }.toSet() +
            groupMembers.filter { it.contactId == otherId }.map { it.groupId }.toSet()
        setContactGroups(keepId, mergedGroupIds)

        updateContact(merged)
        deleteContact(otherId)
    }

    // ──────────────────────────────────────────────────────────
    //  ГРУППЫ КОНТАКТОВ (как в телефонной книге)
    // ──────────────────────────────────────────────────────────

    /** Создать группу; при совпадении имени (без регистра) возвращает существующую. */
    fun addGroup(name: String): ContactGroup? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        groups.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        val g = ContactGroup(generateId(), clean, nowIso(), nowIso())
        groups.add(g)
        scope.launch { db()?.contactDao()?.insertContactGroup(g.toEntity()) }
        return g
    }

    fun renameGroup(groupId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        val idx = groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return
        val g = groups[idx].copy(name = clean, updatedAt = nowIso())
        groups[idx] = g
        scope.launch { db()?.contactDao()?.insertContactGroup(g.toEntity()) }
    }

    /** Удалить группу вместе с членством (контакты не трогаются). */
    fun deleteGroup(groupId: String) {
        groups.removeAll { it.id == groupId }
        groupMembers.removeAll { it.groupId == groupId }
        scope.launch {
            val dao = db()?.contactDao() ?: return@launch
            dao.deleteGroupMembersForGroup(groupId)
            dao.deleteContactGroup(groupId)
        }
    }

    fun groupsOfContact(contactId: String): List<ContactGroup> {
        val ids = groupMembers.filter { it.contactId == contactId }.map { it.groupId }.toSet()
        return groups.filter { it.id in ids }
    }

    fun contactIdsInGroup(groupId: String): Set<String> =
        groupMembers.filter { it.groupId == groupId }.map { it.contactId }.toSet()

    /** Полностью задать набор групп контакта (диалог с чекбоксами). */
    fun setContactGroups(contactId: String, groupIds: Set<String>) {
        val current = groupMembers.filter { it.contactId == contactId }
        val toRemove = current.filter { it.groupId !in groupIds }
        val toAdd = groupIds
            .filter { gid -> current.none { it.groupId == gid } }
            .map { gid -> ContactGroupMember(generateId(), gid, contactId) }
        groupMembers.removeAll(toRemove.toSet())
        groupMembers.addAll(toAdd)
        scope.launch {
            val dao = db()?.contactDao() ?: return@launch
            toRemove.forEach { dao.deleteGroupMember(it.id) }
            if (toAdd.isNotEmpty()) dao.insertContactGroupMembers(toAdd.map { it.toEntity() })
        }
    }

    // ── Свои типы отношений («статусы») — фидбэк владельца 2026-07-05: создаёт
    // свой тип, а он «не входит в фильтры, не ищется, не редактируется, не
    // удаляется» — customRelationshipType был голым текстовым полем без CRUD,
    // в отличие от групп. Добавляем тот же набор операций, что и для групп. ──

    /** Отсортированный список уникальных пользовательских типов отношений в использовании. */
    fun distinctCustomRelationshipTypes(): List<String> =
        contacts.mapNotNull { it.customRelationshipType?.trim()?.takeIf { s -> s.isNotBlank() } }
            .distinct().sortedBy { it.lowercase() }

    /** Переименовать свой тип отношений ВЕЗДЕ, где он используется (все контакты разом). */
    fun renameCustomRelationshipType(oldName: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank() || clean == oldName) return
        contacts.filter { it.customRelationshipType == oldName }.forEach {
            updateContact(it.copy(customRelationshipType = clean))
        }
    }

    /** Удалить свой тип отношений: у всех контактов с этим значением он сбрасывается
     *  (relationshipType остаётся как есть — обычно OTHER, контакт не теряется). */
    fun deleteCustomRelationshipType(name: String) {
        contacts.filter { it.customRelationshipType == name }.forEach {
            updateContact(it.copy(customRelationshipType = null))
        }
    }

    /**
     * Преобразование контакта в компанию (фидбэк владельца: «много контактов,
     * которые на самом деле компании»). Телефоны/email/адреса переезжают в
     * компанию (новые id — чужие PK не переиспользуем), заметки пере-привязываются
     * ДО deleteContact (его каскад чистит notes по contactId). Контакт удаляется.
     * @return id созданной компании или null (контакт не найден / пустое имя).
     */
    // ФИКС (аудит 2026-07-06): раньше переносились ТОЛЬКО phones/emails/addresses/
    // notes, а фото, привязанные события календаря и т.д. молча пропадали вместе
    // с удалённым контактом — диалог подтверждения предупреждал не обо всём.
    // Company НЕ имеет полей под messengers/gifts/personalDetails/sizeInfo/tags/
    // familyNote/profession/contactRelations/группы (это личные, не деловые
    // понятия — добавлять их значило бы превращать Company в клон Contact), так
    // что эти поля переносить некуда — они по-прежнему теряются, но теперь
    // честно и полностью перечислены в тексте подтверждения (cd_convert_company_text).
    // Перенесены дополнительно: фото → логотип компании (поле уже существовало,
    // просто не заполнялось), ссылки календарных событий на контакт → на компанию
    // (тот же паттерн ретаргетинга, что при слиянии дублей, см. mergeContacts).
    fun convertContactToCompany(contactId: String): String? {
        val c = getContact(contactId) ?: return null
        val name = listOfNotNull(c.firstName, c.middleName, c.lastName)
            .joinToString(" ").trim()
            .ifBlank { c.nickname?.trim().orEmpty() }
        if (name.isBlank()) return null
        val companyId = generateId()
        val company = Company(
            id = companyId,
            name = name,
            logoUri = c.photoUri,
            industry = Industry.OTHER,
            phones = c.phones.map { it.copy(id = generateId(), contactId = companyId, isPrimary = false) },
            emails = c.emails.map { it.copy(id = generateId(), contactId = companyId, isPrimary = false) },
            addresses = c.addresses.map {
                it.copy(id = generateId(), ownerId = companyId, ownerType = AddressOwnerType.COMPANY)
            },
            createdAt = nowIso(), updatedAt = nowIso()
        )
        addCompany(company)
        // updateCompany синхронизирует ГЛОБАЛЬНЫЙ addresses (карта) — addCompany этого не делает
        updateCompany(company)
        notes.filter { it.contactId == contactId }.toList().forEach {
            updateNote(it.copy(contactId = null, companyId = companyId))
        }
        // Ссылки событий: targetType CONTACT/contactId → COMPANY/companyId.
        calendarItems.filter { ci -> ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId } }
            .toList().forEach { ci ->
                val newLinks = ci.links.map {
                    if (it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId)
                        it.copy(targetType = CalendarTargetType.COMPANY, targetId = companyId)
                    else it
                }.distinctBy { it.targetType to it.targetId }
                updateCalendarItem(ci.copy(links = newLinks))
            }
        deleteContact(contactId)
        return companyId
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
            // Симметрично контактам: глобальный addresses для карты
            addresses.removeAll { it.ownerId == c.id && it.ownerType == AddressOwnerType.COMPANY }
            addresses.addAll(c.addresses)
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

    /**
     * Отметить событие выполненным. Если это реальное общение
     * (звонок / встреча / сообщение), у связанных контактов обновляется
     * «последний контакт» = сегодня.
     */
    fun markCalendarItemCompleted(item: CalendarItem) {
        val now = nowIso()
        updateCalendarItem(item.copy(status = CalendarItemStatus.COMPLETED, updatedAt = now))
        val isInteraction = item.type == CalendarItemType.CALL ||
            item.type == CalendarItemType.MEETING ||
            item.type == CalendarItemType.MESSAGE
        if (isInteraction) {
            val today = now.take(10)
            item.links.filter { it.targetType == CalendarTargetType.CONTACT }.forEach { link ->
                val idx = contacts.indexOfFirst { it.id == link.targetId }
                if (idx >= 0) {
                    contacts[idx] = contacts[idx].copy(lastContactDate = today)
                    scope.launch { db()?.contactDao()?.updateLastContactDate(link.targetId, today, now) }
                }
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
    fun wipeAllData(onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            // Сначала пытаемся очистить БД. Если упало — in-memory НЕ трогаем,
            // иначе состояние разойдётся с диском и при перезапуске данные
            // «вернутся», а пользователь думал, что всё стёр.
            val dbOk = try { db()?.clearAllTables(); true } catch (e: Exception) { false }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (dbOk) {
                    contacts.clear(); companies.clear(); calendarItems.clear()
                    notes.clear(); gifts.clear(); companyRelations.clear()
                    contactRelations.clear(); addresses.clear()
                    sizeInfos.clear(); personalDetails.clear()
                    groups.clear(); groupMembers.clear()
                }
                onDone(dbOk)
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

    /** Сохраняет вычисленные геокодером координаты адреса в память и БД, чтобы
     *  точка на карте не пропадала и не геокодилась заново при каждом открытии. */
    fun setAddressCoords(addressId: String, lat: Double, lng: Double) {
        val i = addresses.indexOfFirst { it.id == addressId }
        if (i < 0) return
        if (addresses[i].latitude != null && addresses[i].longitude != null) return
        val updated = addresses[i].copy(latitude = lat, longitude = lng)
        addresses[i] = updated
        scope.launch { db()?.addressDao()?.insertAddresses(listOf(updated.toEntity())) }
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
                // Заметка всегда добавляется в контакт. «Последний контакт»
                // обновляем только для заметок-общения, НЕ для структурной
                // информации (подарок / личная деталь / дата-событие).
                val countsAsContact = n.type != NoteType.GIFT &&
                    n.type != NoteType.PERSONAL_DETAIL &&
                    n.type != NoteType.DATE_EVENT
                contacts[idx] = contacts[idx].copy(
                    notes           = contacts[idx].notes + n,
                    lastContactDate = if (countsAsContact) now.take(10) else contacts[idx].lastContactDate
                )
                if (countsAsContact) {
                    scope.launch {
                        db()?.contactDao()?.updateLastContactDate(cid, now.take(10), now)
                    }
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
