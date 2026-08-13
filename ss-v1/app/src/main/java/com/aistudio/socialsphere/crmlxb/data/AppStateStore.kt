package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.data.local.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.MergeResolution
import com.aistudio.socialsphere.crmlxb.utils.searchNormalize
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    // Теги контактов (плоский управляемый список, зеркало групп) + членство
    val tags                = mutableStateListOf<Tag>()
    val tagMembers          = mutableStateListOf<ContactTagMember>()

    private var database: SocialsphereDatabase? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false

    // true пока идёт первичная загрузка из БД. Экраны показывают спиннер вместо
    // «ничего не найдено», иначе на холодном старте пустой экран выглядел как
    // потеря данных.
    var isLoading by mutableStateOf(true)
        private set

    fun initialize(context: Context, db: SocialsphereDatabase) {
        if (isInitialized) return
        database = db
        appContext = context.applicationContext
        isInitialized = true
        scope.launch { loadInitialData() }
    }

    /**
     * Только для тестов — сбрасывает `isInitialized`, чтобы следующий
     * `initialize()` реально переподключился к новой (тестовой) БД.
     *
     * ФИКС (2026-08-12): `isInitialized`-гвард в initialize() верно защищает
     * прод-сценарий (повторный onCreate() Activity не должен пере-грузить всё
     * состояние заново) — но AppStateStore это `object`-синглтон, живущий на
     * весь JVM-процесс. В Robolectric-тестах один и тот же JVM/classloader
     * гоняет НЕСКОЛЬКО тестовых методов подряд — второй `@Test` в том же
     * классе вызывает `initialize(ctx, db2)`, гвард молча возвращает управление
     * без переподключения, и `database` НАВСЕГДА остаётся указывать на `db`
     * ПЕРВОГО теста — уже закрытую его `@After` (`db.close()`) к моменту
     * второго теста. Все фоновые `scope.launch { db()?.let {...} }` второго
     * теста молча падают на закрытой БД, и тест ловит это как ложный таймаут
     * опроса — выглядит как регресс кода, хотя на самом деле баг в тестовой
     * обвязке. Вызывать из `@Before`/`@After` тестов, НЕ из прод-кода.
     */
    internal fun resetForTests() {
        isInitialized = false
        database = null
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
        // ФИКС (2026-07-23, решение владельца): демоданные больше НИКОГДА не
        // заводятся при установке — раньше здесь заполнялась вся демо-БД
        // (DemoDataProvider), если контактов ещё не было. Новая установка
        // теперь стартует с пустого состояния навсегда.
        try {
            db() ?: return
            reloadFromDb()
            // Сидирование дефолтных тегов — ПОСЛЕ реальной загрузки tags из БД
            // выше (reloadFromDb уже наполнил tags), иначе tags.isEmpty()
            // проверился бы раньше, чем список реально загружен, и сидирование
            // срабатывало бы на каждом холодном старте заново.
            appContext?.let { seedDefaultTagsIfNeeded(it) }
            // ФИКС (2026-08-12, §52 KNOWLEDGE.md): самовосстановление связи
            // дата-событие→контакт по имени из заголовка — ПОСЛЕ reloadFromDb,
            // т.к. работает по уже загруженным calendarItems/contacts.
            repairMissingContactLinksFromTitles()
        } finally {
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    private val DATE_EVENT_TYPES_FOR_LINK_REPAIR = setOf(
        CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
        CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE
    )

    /**
     * Самовосстановление связи «дата-событие → контакт» по имени, запечённому
     * в заголовке (владелец, 2026-08-12: «оно в календаре всё равно пишет,
     * чей это день рождения — почему оно обратно не присоединится к
     * контакту?»). До этого фикса имя в заголовке («День рождения: Иван
     * Петров») было мёртвым текстом — нигде не читалось обратно, даже когда
     * реальная связь `CalendarItemLink` на этот контакт отсутствовала (старые
     * бэкапы до появления links, события без выбора контакта при ручном
     * создании и т.п.) — контакт при этом никогда не показывал такое событие
     * в «Ближайших» (см. §52).
     *
     * Работает ТОЛЬКО при ОДНОЗНАЧНОМ совпадении полного имени: 0 или ≥2
     * кандидатов — ничего не делает, не гадает (привязать не того человека
     * хуже, чем оставить как есть — это про чужие личные данные). Идемпотентна:
     * повторный вызов на уже привязанных событиях находит 0 кандидатов.
     */
    internal suspend fun repairMissingContactLinksFromTitles() {
        val ctx = appContext ?: return
        val database = db() ?: return
        val candidates = calendarItems.filter { item ->
            item.type in DATE_EVENT_TYPES_FOR_LINK_REPAIR &&
                item.links.none { it.targetType == CalendarTargetType.CONTACT }
        }
        if (candidates.isEmpty()) return
        val newLinksByItemId = mutableMapOf<String, CalendarItemLink>()
        candidates.forEach { item ->
            val name = com.aistudio.socialsphere.crmlxb.utils.extractNameFromDateTitle(item.title, item.type, ctx)
                ?: return@forEach
            val matches = contacts.filter { "${it.firstName} ${it.lastName}".trim().equals(name, ignoreCase = true) }
            if (matches.size == 1) {
                newLinksByItemId[item.id] = CalendarItemLink(
                    id = generateId(), calendarItemId = item.id,
                    targetType = CalendarTargetType.CONTACT, targetId = matches[0].id
                )
            }
        }
        if (newLinksByItemId.isEmpty()) return
        withContext(Dispatchers.Main) {
            newLinksByItemId.forEach { (itemId, link) ->
                val idx = calendarItems.indexOfFirst { it.id == itemId }
                if (idx >= 0) calendarItems[idx] = calendarItems[idx].copy(links = calendarItems[idx].links + link)
            }
        }
        database.calendarDao().insertCalendarItemLinks(newLinksByItemId.values.map { it.toEntity() })
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
        // getTags()/getContactTagMembers() — Flow (не suspend, в отличие от групп);
        // первичная загрузка забирает текущий снимок через .first(), дальнейшие
        // изменения идут теми же mutableStateListOf-мутациями, что и у групп.
        val tagEntities       = db.contactDao().getTags().first()
        val tagMemberEntities = db.contactDao().getContactTagMembers().first()

        withContext(Dispatchers.Main) {
            groups.clear();          groups.addAll(groupEntities.map { it.toDomain() })
            groupMembers.clear();    groupMembers.addAll(memberEntities.map { it.toDomain() })
            tags.clear();            tags.addAll(tagEntities.map { it.toDomain() })
            tagMembers.clear();      tagMembers.addAll(tagMemberEntities.map { it.toDomain() })
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
        if (item.links.isNotEmpty())     db.calendarDao().insertCalendarItemLinks(item.links.map { it.copy(calendarItemId = item.id).toEntity() })
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
        // ФИКС (аудит 2026-08-12): та же синхронизация нужна и для gifts/
        // personalDetails — раньше синхронизировались только через отдельные
        // addGift()/addNote(), но НЕ когда контакт создаётся СРАЗУ с уже
        // заполненными списками (как делает импорт через ContactNoteCodec) —
        // GiftsTab читает ГЛОБАЛЬНЫЙ AppStateStore.gifts, значит был бы виден
        // в БД, но не на экране до перезапуска. Тот же паттерн, что уже
        // применён в restoreContact() для notes/gifts.
        gifts.addAll(c.gifts)
        personalDetails.addAll(c.personalDetails)
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
            // ФИКС (аудит 2026-08-12): тот же пробел, что и в addContact() —
            // gifts/personalDetails тоже читаются из ГЛОБАЛЬНЫХ списков
            // (GiftsTab: AppStateStore.gifts.filter{...}) и должны
            // синхронизироваться при каждом updateContact(), не только через
            // отдельные addGift()/точечные правки.
            gifts.removeAll { it.contactId == c.id }
            gifts.addAll(c.gifts)
            personalDetails.removeAll { it.contactId == c.id }
            personalDetails.addAll(c.personalDetails)
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
        // ФИКС (2026-08-12, У61-класс: addContactDb никогда не писал notes/gifts —
        // они живут в отдельных таблицах, не в самой contact-строке, addContactDb
        // молча их пропускал так же, как раньше пропускал notes до §44). Здесь же
        // подстраховка на случай СТАРОГО бэкапа без плоских top-level notes/gifts
        // (Contact.notes/gifts вложенные — они были в JSON всегда, раз Contact
        // строится из них при каждой выгрузке): восстанавливаем через те же
        // restoreNote/restoreGift, что и импорт плоского списка — upsert по id,
        // повторный вызов из data.notes/data.gifts ниже безопасен (идемпотентно).
        contact.notes.forEach { restoreNote(it) }
        contact.gifts.forEach { restoreGift(it) }
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

    /** Восстановление тега из бэкапа: upsert по id, сохраняя точные значения
     *  (в отличие от addTag/renameTag, которые генерируют id/updatedAt заново)
     *  — точный аналог restoreGroup. */
    fun restoreTag(tag: Tag) {
        val idx = tags.indexOfFirst { it.id == tag.id }
        if (idx >= 0) tags[idx] = tag else tags.add(tag)
        scope.launch { db()?.contactDao()?.insertTag(tag.toEntity()) }
    }

    /** Восстановление членства в теге из бэкапа: добавляем, только если такой
     *  записи ещё нет — точный аналог restoreGroupMember. */
    fun restoreTagMember(member: ContactTagMember) {
        if (tagMembers.none { it.id == member.id }) {
            tagMembers.add(member)
            scope.launch { db()?.contactDao()?.insertContactTagMembers(listOf(member.toEntity())) }
        }
    }

    /** Восстановление связи (семья и др.) из снапшота отмены слияния: как
     *  restoreGroupMember — добавляем, только если такой записи ещё нет. */
    fun restoreContactRelation(relation: ContactRelation) {
        if (contactRelations.none { it.id == relation.id }) {
            contactRelations.add(relation)
            scope.launch { db()?.contactDao()?.insertContactRelations(listOf(relation.toEntity())) }
        }
    }

    fun deleteContact(contactId: String) {
        // Каскад: собираем id связей ДО удаления из памяти, чтобы потом
        // вычистить их и из БД (иначе оставались сиротами).
        val relationIds = contactRelations
            .filter { it.firstContactId == contactId || it.secondContactId == contactId }
            .map { it.id }
        // Каскад (§28/#80): убираем ссылки календарных событий на удаляемый
        // контакт — тем же путём, что конвертация в компанию (updateCalendarItem
        // чистит и память, и calendar_item_links в БД). Раньше строки оставались
        // сиротами: UI молча скрывал событие через getContact() ?: null.
        calendarItems.filter { ci -> ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId } }
            .toList().forEach { ci ->
                updateCalendarItem(ci.copy(links = ci.links.filterNot {
                    it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId
                }))
            }
        contacts.removeAll { it.id == contactId }
        notes.removeAll { it.contactId == contactId }
        gifts.removeAll { it.contactId == contactId }
        sizeInfos.removeAll { it.contactId == contactId }
        personalDetails.removeAll { it.contactId == contactId }
        addresses.removeAll { it.ownerId == contactId && it.ownerType == AddressOwnerType.CONTACT }
        companyRelations.removeAll { it.contactId == contactId }
        contactRelations.removeAll { it.firstContactId == contactId || it.secondContactId == contactId }
        groupMembers.removeAll { it.contactId == contactId }
        tagMembers.removeAll { it.contactId == contactId }
        scope.launch {
            val db = db() ?: return@launch
            db.contactDao().deleteGroupMembersForContact(contactId)
            db.contactDao().deleteTagMembersForContact(contactId)
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

    /** Расстояние Дамерау-Левенштейна (с учётом транспозиции соседних букв) —
     *  для «похоже написано» при живой проверке на дубли (см. findLiveDuplicateHints).
     *  В кодовой базе не было примитива нечёткого сравнения строк — этот
     *  написан специально под задачу, полный DP без верхней границы (имена —
     *  десятки символов, контактов — сотни, пересчёт при вводе не заметен). */
    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length; val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val d = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) d[i][0] = i
        for (j in 0..lb) d[0][j] = j
        for (i in 1..la) {
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[la][lb]
    }

    /** Подсказка «похоже, уже есть» при вводе черновика — см. findLiveDuplicateHints. */
    data class LiveDuplicateHint(
        val contact: Contact,
        val byPhone: String? = null,
        val byEmail: String? = null,
        val byNameSimilarity: Boolean = false,
    )

    /** Живая (при вводе, ДО сохранения) проверка на дубли — для экрана
     *  создания/редактирования контакта. Дополняет, не заменяет,
     *  findDuplicatePairs(): та ищет постфактум среди уже сохранённых
     *  контактов; эта сравнивает ещё не сохранённый черновик формы против
     *  базы. Телефон/email переиспользуют ровно ту же логику совпадения,
     *  что и findDuplicatePairs (см. коммент там) — только направление
     *  сравнения другое (черновик × база, а не база × база).
     *  excludeId — id самого редактируемого контакта (при правке существующего,
     *  не при создании), чтобы контакт не «находил дубль самого себя».
     */
    fun findLiveDuplicateHints(
        draftFirstName: String,
        draftLastName: String,
        draftPhones: List<String>,
        draftEmails: List<String>,
        excludeId: String? = null,
    ): List<LiveDuplicateHint> {
        val draftPhoneDigits = draftPhones.map { phoneDigits(it) }.filter { it.length >= 7 }.toSet()
        val draftEmailsClean = draftEmails.map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.substringBefore("@") !in genericEmailLocalParts }.toSet()
        // Похожесть имени триггерим только когда заполнены И имя, И фамилия —
        // одно имя («Мария») совпало бы с половиной базы, шума будет больше
        // чем пользы (см. предложение аудита 2026-07-22).
        val nameCheckEligible = draftFirstName.isNotBlank() && draftLastName.isNotBlank()
        val draftFullName = listOf(draftFirstName, draftLastName)
            .filter { it.isNotBlank() }.joinToString(" ").searchNormalize()

        val hints = mutableListOf<LiveDuplicateHint>()
        contacts.forEach { c ->
            if (c.id == excludeId) return@forEach
            val phoneHit = c.phones.map { phoneDigits(it.number) }
                .firstOrNull { it.length >= 7 && it in draftPhoneDigits }
            val emailHit = c.emails.map { it.email.trim().lowercase() }
                .firstOrNull { it.isNotBlank() && it.substringBefore("@") !in genericEmailLocalParts && it in draftEmailsClean }
            var nameSimilar = false
            if (nameCheckEligible) {
                val candidateFullName = listOf(c.firstName, c.lastName)
                    .filter { it.isNotBlank() }.joinToString(" ").searchNormalize()
                if (candidateFullName.isNotBlank()) {
                    val threshold = if (draftFullName.length <= 6) 1 else 2
                    nameSimilar = damerauLevenshtein(draftFullName, candidateFullName) <= threshold
                }
            }
            if (phoneHit != null || emailHit != null || nameSimilar) {
                hints.add(LiveDuplicateHint(c, byPhone = phoneHit, byEmail = emailHit, byNameSimilarity = nameSimilar))
            }
        }
        return hints
    }

    /** «Полнота» контакта — при слиянии по умолчанию keep — самый заполненный. */
    internal fun contactScore(c: Contact): Int =
        c.phones.size + c.emails.size + c.messengers.size + c.addresses.size +
        c.notes.size + c.gifts.size + c.companyRelations.size +
        (if (c.nickname.isNullOrBlank()) 0 else 1)

    /** Свободный текст при слиянии: пусто → другое; совпадают (без учёта
     *  регистра) → одно; иначе — по умолчанию ОБА через " / " (ничего не
     *  теряется молча), если явный выбор пользователя (MergeResolution) не
     *  сузил список включаемых значений. */
    private fun combineChosenText(key: String, values: List<Pair<String, String?>>, included: Set<String>?): String? {
        val nonBlank = values.mapNotNull { (id, v) -> v?.trim()?.takeIf { it.isNotBlank() }?.let { id to it } }
        if (nonBlank.isEmpty()) return null
        val distinct = nonBlank.map { it.second.lowercase() }.distinct()
        if (distinct.size == 1) return nonBlank.first().second
        val chosen = if (included != null) nonBlank.filter { it.first in included } else nonBlank
        // Защита: если пользователь снял все чипы (или ключ ещё не решён иначе) —
        // не терять данные молча, использовать все непустые значения.
        val finalList = chosen.ifEmpty { nonBlank }
        return finalList.joinToString(" / ") { it.second }
    }

    /** Одиночное строковое поле: если у всех выбранных контактов значение
     *  совпадает или пусто у части — авто-подстановка без вопроса. Если
     *  расходится — берётся explicit resolution.choiceWinners[key], иначе
     *  первое непустое по порядку (совместимо с прежним поведением). */
    private fun pickSingle(key: String, values: List<Pair<String, String?>>, winner: String?): String? {
        val nonBlank = values.filter { !it.second.isNullOrBlank() }
        if (nonBlank.isEmpty()) return null
        if (nonBlank.map { it.second }.distinct().size == 1) return nonBlank.first().second
        return nonBlank.firstOrNull { it.first == winner }?.second ?: nonBlank.first().second
    }

    private fun <T> pickEnum(values: List<Pair<String, T>>, winner: String?): T {
        if (values.map { it.second }.distinct().size == 1) return values.first().second
        return values.firstOrNull { it.first == winner }?.second ?: values.first().second
    }

    /** Полный «снимок» состояния до слияния — для отмены (Snackbar «Отменить»
     *  на ~10 сек после операции). Хранится только в памяти вызывающего экрана,
     *  БД не трогает — если экран закрыт/забыт, снимок просто теряется. */
    data class MergeUndoSnapshot(
        val contacts: List<Contact>,
        val notes: List<Note>,
        val gifts: List<GiftIdea>,
        val relations: List<ContactRelation>,
        val calendarItems: List<CalendarItem>,
        val groupMembers: List<ContactGroupMember>,
        val tagMembers: List<ContactTagMember>
    )

    /**
     * Слияние 2-3 контактов в один. Списочные поля (телефоны/email/адреса/
     * заметки/подарки/теги/связи/группы) объединяются честно — union с дедупом,
     * ничего не теряется. Одиночные конфликтующие поля (имя, фамилия, статус,
     * важность, соц.роль, ритм, фото, тип отношений и т.д.) — если у выбранных
     * контактов значения расходятся, побеждает choiceWinners[key] из resolution;
     * если resolution не содержит ключ — первое непустое (старое поведение,
     * гарантирует что кнопка «Слить» может быть нажата и без явного решения
     * по каждому пункту). Свободный текст — combineChosenText (см. выше).
     *
     * Все изменения в БД идут ОДНОЙ Room-транзакцией (db.withTransaction) —
     * либо весь merge применяется, либо (при сбое/убийстве процесса) ни один
     * из участвующих контактов не тронут на диске. Раньше update/delete шли
     * раздельными scope.launch без транзакции — для 2-3 контактов это окно
     * несогласованности на диске умножалось на число удаляемых контактов.
     *
     * Возвращает снимок для отмены (null, если слияние не выполнено —
     * например, id не найдены или их меньше 2/больше 3).
     */
    fun mergeContacts(contactIds: List<String>, resolution: MergeResolution = MergeResolution()): MergeUndoSnapshot? {
        val uniqueIds = contactIds.distinct()
        if (uniqueIds.size < 2 || uniqueIds.size > 3) return null
        val group = uniqueIds.mapNotNull { getContact(it) }
        if (group.size != uniqueIds.size) return null

        val keep = group.maxByOrNull { contactScore(it) } ?: group.first()
        val keepId = keep.id
        val others = group.filter { it.id != keepId }
        val otherIds = others.map { it.id }
        val allPairs = group.map { it.id to it }

        // ── снимок ДО любых мутаций ──
        val snapshotNotes = notes.filter { it.contactId in uniqueIds }.toList()
        val snapshotGifts = gifts.filter { it.contactId in uniqueIds }.toList()
        val snapshotRelations = contactRelations.filter {
            it.firstContactId in uniqueIds || it.secondContactId in uniqueIds
        }.toList()
        val snapshotCalendarItems = calendarItems.filter { ci ->
            ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId in uniqueIds }
        }.toList()
        val snapshotGroupMembers = groupMembers.filter { it.contactId in uniqueIds }.toList()
        val snapshotTagMembers = tagMembers.filter { it.contactId in uniqueIds }.toList()
        val snapshot = MergeUndoSnapshot(
            contacts = group, notes = snapshotNotes, gifts = snapshotGifts,
            relations = snapshotRelations, calendarItems = snapshotCalendarItems,
            groupMembers = snapshotGroupMembers, tagMembers = snapshotTagMembers
        )

        // ── списочные поля: union с дедупом (как раньше, обобщено на N) ──
        val mergedPhones = keep.phones.toMutableList()
        val mergedEmails = keep.emails.toMutableList()
        val mergedMessengers = keep.messengers.toMutableList()
        val mergedCompRels = keep.companyRelations.toMutableList()
        val mergedAddresses = keep.addresses.toMutableList()
        val mergedPd = keep.personalDetails.toMutableList()
        others.forEach { other ->
            other.phones.forEach { p ->
                val d = phoneDigits(p.number)
                if (d.isEmpty() || mergedPhones.none { phoneDigits(it.number) == d })
                    mergedPhones.add(p.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.emails.forEach { e ->
                if (mergedEmails.none { it.email.equals(e.email, true) })
                    mergedEmails.add(e.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.messengers.forEach { m ->
                if (mergedMessengers.none { it.type == m.type && it.value.equals(m.value, true) })
                    mergedMessengers.add(m.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            // Дедуп по ПОЛНОМУ совпадению записи, не только companyId — раньше
            // вторая связь с той же компанией отбрасывалась целиком, даже если
            // должность/отдел/заметка различались (реальная потеря данных).
            other.companyRelations.forEach { r ->
                val dup = mergedCompRels.any {
                    it.companyId == r.companyId && it.position == r.position &&
                    it.department == r.department && it.role == r.role &&
                    it.employmentStatus == r.employmentStatus && it.startDate == r.startDate &&
                    it.endDate == r.endDate && it.responsibilities == r.responsibilities &&
                    it.managedAccounts == r.managedAccounts && it.workNote == r.workNote
                }
                if (!dup) mergedCompRels.add(r.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.addresses.forEach { a ->
                if (mergedAddresses.none { it.addressLine.equals(a.addressLine, true) && it.city.equals(a.city, true) })
                    mergedAddresses.add(a.copy(id = generateId(), ownerId = keepId))
            }
            other.personalDetails.forEach { pd ->
                if (mergedPd.none { it.category == pd.category && it.value.equals(pd.value, true) })
                    mergedPd.add(pd.copy(id = generateId(), contactId = keepId))
            }
        }

        fun winner(key: String) = resolution.choiceWinners[key]
        fun text(key: String, getter: (Contact) -> String?) =
            combineChosenText(key, allPairs.map { it.first to getter(it.second) }, resolution.textIncluded[key])

        // Размеры (SizeInfo) — раньше выбирался целый объект одного контакта,
        // подполя другого терялись целиком. Теперь каждое подполе комбинируется
        // текстом отдельно (см. TEXT_MERGE_FIELDS), собираем объект обратно,
        // только если хоть одно подполе непустое.
        val sizeClothing = text("sizeClothing") { it.sizeInfo?.clothingSize }
        val sizeShoe = text("sizeShoe") { it.sizeInfo?.shoeSize }
        val sizeRing = text("sizeRing") { it.sizeInfo?.ringSize }
        val sizeOther = text("sizeOther") { it.sizeInfo?.other }
        val mergedSizeInfo = if (sizeClothing == null && sizeShoe == null && sizeRing == null && sizeOther == null) null
            else SizeInfo(generateId(), keepId, sizeClothing, sizeShoe, sizeRing, sizeOther)

        val merged = keep.copy(
            // Структура имени и дата знакомства — как остальной свободный текст:
            // при расхождении оба значения остаются рядом через " / ", ничего не
            // пропадает молча (фидбэк владельца 2026-07-13: «если в одном только
            // имя, а в другом имя и фамилия — хочу иметь оба, а не выбор одного»).
            firstName = text("firstName") { it.firstName } ?: keep.firstName,
            lastName  = text("lastName") { it.lastName } ?: keep.lastName,
            middleName = text("middleName") { it.middleName },
            namePrefix = text("namePrefix") { it.namePrefix },
            nameSuffix = text("nameSuffix") { it.nameSuffix },
            phoneticFirstName = text("phoneticFirstName") { it.phoneticFirstName },
            phoneticMiddleName = text("phoneticMiddleName") { it.phoneticMiddleName },
            phoneticLastName = text("phoneticLastName") { it.phoneticLastName },
            meetDate = text("meetDate") { it.meetDate },
            // Фото и enum-поля модели физически одноместные (Contact хранит
            // ровно одно значение) — тут выбор неизбежен. Что не выбрано,
            // MergeResolveScreen сохраняет отдельной заметкой, чтобы информация
            // не пропадала совсем — просто не может остаться "живым" полем.
            photoUri = pickSingle("photoUri", allPairs.map { it.first to it.second.photoUri }, winner("photoUri")),
            relationshipType = pickEnum(allPairs.map { it.first to it.second.relationshipType }, winner("relationshipType")),
            importanceLevel = pickEnum(allPairs.map { it.first to it.second.importanceLevel }, winner("importanceLevel")),
            socialRole = pickEnum(allPairs.map { it.first to it.second.socialRole }, winner("socialRole")),
            communicationRhythm = pickEnum(allPairs.map { it.first to it.second.communicationRhythm }, winner("communicationRhythm")),
            contactStatus = pickEnum(allPairs.map { it.first to it.second.contactStatus }, winner("contactStatus")),
            // LEGACY (UI больше не показывает) и внутренний sync-id — тем же
            // безопасным правилом «первое непустое», что и раньше, без per-field
            // экрана (нечего показывать пользователю осмысленно).
            connectionLevel = keep.connectionLevel,
            deviceContactId = keep.deviceContactId ?: others.firstNotNullOfOrNull { it.deviceContactId },
            // TODO (см. Models.kt): свой тип отношений станет списком — сознательно
            // не включаем в per-field выбор сейчас, чтобы не переписывать дважды.
            customRelationshipType = keep.customRelationshipType ?: others.firstNotNullOfOrNull { it.customRelationshipType },
            nickname = text("nickname") { it.nickname },
            profession = text("profession") { it.profession },
            nextStep = text("nextStep") { it.nextStep },
            canHelpWith = text("canHelpWith") { it.canHelpWith },
            iCanHelpWith = text("iCanHelpWith") { it.iCanHelpWith },
            talkingPoints = text("talkingPoints") { it.talkingPoints },
            meetContext = text("meetContext") { it.meetContext },
            familyNote = text("familyNote") { it.familyNote },
            lastContactDate = allPairs.mapNotNull { it.second.lastContactDate }.maxOrNull(),
            tags = allPairs.flatMap { it.second.tags }.distinct(),
            phones = mergedPhones, emails = mergedEmails, messengers = mergedMessengers,
            companyRelations = mergedCompRels, addresses = mergedAddresses, personalDetails = mergedPd,
            sizeInfo = mergedSizeInfo
        )

        val notesToReassign = snapshotNotes.filter { it.contactId in otherIds }.map { it.copy(contactId = keepId) }
        val giftsToReassign = snapshotGifts.filter { it.contactId in otherIds }.map { it.copy(contactId = keepId) }

        val relationUpdates = mutableListOf<ContactRelation>()
        val relationRemovals = mutableListOf<String>()
        snapshotRelations.forEach { rel ->
            val nf = if (rel.firstContactId in otherIds) keepId else rel.firstContactId
            val ns = if (rel.secondContactId in otherIds) keepId else rel.secondContactId
            if (nf == ns) { relationRemovals.add(rel.id); return@forEach }
            if (nf == rel.firstContactId && ns == rel.secondContactId) return@forEach // не задета слиянием
            val dupExists = snapshotRelations.any {
                it.id != rel.id &&
                ((it.firstContactId == nf && it.secondContactId == ns) || (it.firstContactId == ns && it.secondContactId == nf))
            }
            if (dupExists) relationRemovals.add(rel.id)
            else relationUpdates.add(rel.copy(firstContactId = nf, secondContactId = ns))
        }

        val calendarUpdates = snapshotCalendarItems.map { ci ->
            ci.copy(links = ci.links.map {
                if (it.targetType == CalendarTargetType.CONTACT && it.targetId in otherIds) it.copy(targetId = keepId) else it
            }.distinctBy { it.targetType to it.targetId })
        }

        val mergedGroupIds = snapshotGroupMembers.map { it.groupId }.toSet()
        val mergedTagIds = snapshotTagMembers.map { it.tagId }.toSet()

        // ── применяем в памяти (синхронно, как везде в этом файле) ──
        val keepIdx = contacts.indexOfFirst { it.id == keepId }
        if (keepIdx >= 0) contacts[keepIdx] = merged.copy(updatedAt = nowIso())
        otherIds.forEach { oid -> contacts.removeAll { it.id == oid } }
        addresses.removeAll { it.ownerId == keepId && it.ownerType == AddressOwnerType.CONTACT }
        addresses.addAll(merged.addresses)
        otherIds.forEach { oid -> addresses.removeAll { it.ownerId == oid && it.ownerType == AddressOwnerType.CONTACT } }
        companyRelations.removeAll { it.contactId == keepId }
        companyRelations.addAll(merged.companyRelations)
        otherIds.forEach { oid -> companyRelations.removeAll { it.contactId == oid } }
        notesToReassign.forEach { n -> val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n }
        giftsToReassign.forEach { g -> val i = gifts.indexOfFirst { it.id == g.id }; if (i >= 0) gifts[i] = g }
        relationRemovals.forEach { rid -> contactRelations.removeAll { it.id == rid } }
        relationUpdates.forEach { r -> val i = contactRelations.indexOfFirst { it.id == r.id }; if (i >= 0) contactRelations[i] = r }
        calendarUpdates.forEach { ci -> val i = calendarItems.indexOfFirst { it.id == ci.id }; if (i >= 0) calendarItems[i] = ci }
        setContactGroups(keepId, mergedGroupIds)
        otherIds.forEach { oid -> groupMembers.removeAll { it.contactId == oid } }
        setContactTags(keepId, mergedTagIds)
        otherIds.forEach { oid -> tagMembers.removeAll { it.contactId == oid } }
        sizeInfos.removeAll { it.contactId == keepId || it.contactId in otherIds }
        merged.sizeInfo?.let { sizeInfos.add(it) }
        personalDetails.removeAll { it.contactId == keepId || it.contactId in otherIds }
        personalDetails.addAll(merged.personalDetails)

        // ── одна транзакция на весь merge: либо всё, либо (при сбое) ничего ──
        scope.launch {
            val db = db() ?: return@launch
            db.withTransaction {
                db.contactDao().deletePhonesForContact(keepId)
                db.contactDao().deleteEmailsForContact(keepId)
                db.contactDao().deleteMessengersForContact(keepId)
                db.contactDao().deleteCompanyRelationsForContact(keepId)
                db.contactDao().deletePersonalDetailsForContact(keepId)
                db.contactDao().deleteSizeInfoForContact(keepId)
                db.addressDao().deleteAddressesForOwner(keepId, AddressOwnerType.CONTACT.name)

                db.contactDao().insertContact(merged.toEntity())
                if (merged.phones.isNotEmpty()) db.contactDao().insertPhones(merged.phones.map { it.toEntity() })
                if (merged.emails.isNotEmpty()) db.contactDao().insertEmails(merged.emails.map { it.toEntity() })
                if (merged.messengers.isNotEmpty()) db.contactDao().insertMessengers(merged.messengers.map { it.toEntity() })
                if (merged.companyRelations.isNotEmpty()) db.contactDao().insertCompanyRelations(merged.companyRelations.map { it.toEntity() })
                if (merged.addresses.isNotEmpty()) db.addressDao().insertAddresses(merged.addresses.map { it.toEntity() })
                if (merged.personalDetails.isNotEmpty()) db.contactDao().insertPersonalDetails(merged.personalDetails.map { it.toEntity() })
                merged.sizeInfo?.let { db.contactDao().insertSizeInfo(it.toEntity()) }

                // Реассайн заметок/подарков ДО удаления «других» контактов —
                // иначе последующий deleteNotesForContact(oid) снёс бы их.
                if (notesToReassign.isNotEmpty()) db.noteDao().insertNotes(notesToReassign.map { it.toEntity() })
                if (giftsToReassign.isNotEmpty()) db.giftDao().insertGifts(giftsToReassign.map { it.toEntity() })

                relationRemovals.forEach { db.contactDao().deleteContactRelation(it) }
                if (relationUpdates.isNotEmpty()) db.contactDao().insertContactRelations(relationUpdates.map { it.toEntity() })

                calendarUpdates.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }

                db.contactDao().deleteGroupMembersForContact(keepId)
                if (mergedGroupIds.isNotEmpty()) {
                    db.contactDao().insertContactGroupMembers(
                        mergedGroupIds.map { gid -> ContactGroupMember(generateId(), gid, keepId).toEntity() }
                    )
                }
                db.contactDao().deleteTagMembersForContact(keepId)
                if (mergedTagIds.isNotEmpty()) {
                    db.contactDao().insertContactTagMembers(
                        mergedTagIds.map { tid -> ContactTagMember(generateId(), tid, keepId).toEntity() }
                    )
                }

                otherIds.forEach { oid ->
                    db.contactDao().deleteGroupMembersForContact(oid)
                    db.contactDao().deleteTagMembersForContact(oid)
                    db.contactDao().deletePhonesForContact(oid)
                    db.contactDao().deleteEmailsForContact(oid)
                    db.contactDao().deleteMessengersForContact(oid)
                    db.contactDao().deleteCompanyRelationsForContact(oid)
                    db.contactDao().deletePersonalDetailsForContact(oid)
                    db.contactDao().deleteSizeInfoForContact(oid)
                    db.addressDao().deleteAddressesForOwner(oid, AddressOwnerType.CONTACT.name)
                    db.noteDao().deleteNotesForContact(oid)
                    db.giftDao().deleteGiftsForContact(oid)
                    db.contactDao().deleteContact(oid)
                }
            }
        }

        return snapshot
    }

    /** Отмена слияния по снимку (Snackbar «Объединено · Отменить», ~10 сек).
     *  Переиспользует существующие безопасные методы restoreContact/updateNote
     *  и т.п. — не единая транзакция (это уже путь восстановления, а не
     *  основной путь записи), но каждый вызов идемпотентен и безопасен сам по себе. */
    fun undoMerge(snapshot: MergeUndoSnapshot) {
        snapshot.contacts.forEach { restoreContact(it) }
        snapshot.notes.forEach { n ->
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = n else notes.add(n)
            scope.launch { db()?.noteDao()?.insertNotes(listOf(n.toEntity())) }
        }
        snapshot.gifts.forEach { g ->
            val i = gifts.indexOfFirst { it.id == g.id }
            if (i >= 0) gifts[i] = g else gifts.add(g)
            scope.launch { db()?.giftDao()?.insertGifts(listOf(g.toEntity())) }
        }
        snapshot.relations.forEach { rel ->
            val i = contactRelations.indexOfFirst { it.id == rel.id }
            if (i >= 0) contactRelations[i] = rel else contactRelations.add(rel)
            scope.launch { db()?.contactDao()?.insertContactRelations(listOf(rel.toEntity())) }
        }
        snapshot.calendarItems.forEach { updateCalendarItem(it) }
        val byContact = snapshot.groupMembers.groupBy { it.contactId }
        snapshot.contacts.forEach { c ->
            setContactGroups(c.id, byContact[c.id].orEmpty().map { it.groupId }.toSet())
        }
        // Теги — точно тот же приём, что и группы выше: каждый контакт (и keep,
        // и восстановленные others) возвращается к СВОЕМУ исходному набору тегов
        // из снимка ДО слияния (не просто добавляем недостающее — restoreTagMember
        // здесь не подходит, т.к. не убрал бы у keepId лишние теги, доставшиеся
        // от объединения с others; setContactTags делает честный diff в обе стороны).
        val byContactTags = snapshot.tagMembers.groupBy { it.contactId }
        snapshot.contacts.forEach { c ->
            setContactTags(c.id, byContactTags[c.id].orEmpty().map { it.tagId }.toSet())
        }
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

    // ──────────────────────────────────────────────────────────
    //  ТЕГИ КОНТАКТОВ (плоский управляемый список, зеркало групп выше —
    //  в отличие от Contact.tags/allTags() выше, легаси свободного текста:
    //  тег здесь — Entity со своим id, есть у всех контактов сразу при
    //  переименовании, полноценный CRUD, опциональная категория) ──
    // ──────────────────────────────────────────────────────────

    /** Создать тег; при совпадении имени (без регистра) возвращает существующий —
     *  точный аналог addGroup. */
    fun addTag(name: String, category: String? = null): Tag? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        tags.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        val cleanCategory = category?.trim()?.takeIf { it.isNotBlank() }
        val t = Tag(generateId(), clean, cleanCategory, nowIso(), nowIso())
        tags.add(t)
        scope.launch { db()?.contactDao()?.insertTag(t.toEntity()) }
        return t
    }

    /** Переименовать/сменить категорию тега — правит ОДНУ запись Tag, видно у
     *  всех контактов сразу (связь через id, не копия текста). */
    fun renameTag(id: String, newName: String, newCategory: String?) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        val idx = tags.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cleanCategory = newCategory?.trim()?.takeIf { it.isNotBlank() }
        val t = tags[idx].copy(name = clean, category = cleanCategory, updatedAt = nowIso())
        tags[idx] = t
        scope.launch { db()?.contactDao()?.insertTag(t.toEntity()) }
    }

    /** Удалить тег вместе со всем членством (контакты не трогаются). БЕЗ
     *  предупреждения — вызывающий UI-код сам должен показать диалог с числом
     *  затронутых контактов ДО вызова этой функции (тот же принцип, что deleteGroup). */
    fun deleteTag(id: String) {
        tags.removeAll { it.id == id }
        tagMembers.removeAll { it.tagId == id }
        scope.launch {
            val dao = db()?.contactDao() ?: return@launch
            dao.deleteTagMembersForTag(id)
            dao.deleteTag(id)
        }
    }

    fun tagsOfContact(contactId: String): List<Tag> {
        val ids = tagMembers.filter { it.contactId == contactId }.map { it.tagId }.toSet()
        return tags.filter { it.id in ids }
    }

    fun contactIdsWithTag(tagId: String): Set<String> =
        tagMembers.filter { it.tagId == tagId }.map { it.contactId }.toSet()

    /** Union всех контактов, у которых есть хотя бы один тег данной категории. */
    fun contactIdsWithCategory(category: String): Set<String> {
        val tagIds = tags.filter { it.category == category }.map { it.id }.toSet()
        return tagMembers.filter { it.tagId in tagIds }.map { it.contactId }.toSet()
    }

    /** Полностью задать набор тегов контакта (диалог с чекбоксами) — точный
     *  аналог setContactGroups: diff toAdd/toRemove, повторный вызов с тем же
     *  набором не плодит дубли записей. */
    fun setContactTags(contactId: String, tagIds: Set<String>) {
        val current = tagMembers.filter { it.contactId == contactId }
        val toRemove = current.filter { it.tagId !in tagIds }
        val toAdd = tagIds
            .filter { tid -> current.none { it.tagId == tid } }
            .map { tid -> ContactTagMember(generateId(), tid, contactId) }
        tagMembers.removeAll(toRemove.toSet())
        tagMembers.addAll(toAdd)
        scope.launch {
            val dao = db()?.contactDao() ?: return@launch
            toRemove.forEach { dao.deleteTagMember(it.id) }
            if (toAdd.isNotEmpty()) dao.insertContactTagMembers(toAdd.map { it.toEntity() })
        }
    }

    /** Отсортированный список уникальных непустых категорий среди существующих тегов. */
    fun distinctCategories(): List<String> =
        tags.mapNotNull { it.category?.trim()?.takeIf { s -> s.isNotBlank() } }
            .distinct().sortedBy { it.lowercase() }

    /** Сидирование стандартного набора тегов (v18, 2026-07-28) — ровно один раз
     *  за всю жизнь установки, и только если тегов ещё вообще нет (не «ever
     *  seeded», а «isEmpty сейчас» — то есть если владелец успел создать и
     *  удалить руками все теги до первого прогона этой функции, случайно она
     *  не сработает: AppSettings.defaultTagsSeeded уже true к тому моменту).
     *  Названия/категории — через строковые ресурсы (локализация 3 языков). */
    /** Стартовый набор тегов (2026-07-11, прямой запрос владельца): не «статус
     *  в CRM» (VIP/клиент/партнёр — это уже есть через RelationshipType), а
     *  РОД ЗАНЯТИЙ/УСЛУГА человека — «кто чем занимается» (электрик, продаёт
     *  масло, сдаёт квартиру...), чтобы находить одним словом по фильтру. */
    fun seedDefaultTagsIfNeeded(context: Context) {
        if (AppSettings.defaultTagsSeeded.value) return
        if (tags.isNotEmpty()) { AppSettings.defaultTagsSeeded.value = true; return }
        val catFinance      = context.getString(R.string.seed_category_finance)
        val catIt           = context.getString(R.string.seed_category_it)
        val catHomeServices = context.getString(R.string.seed_category_home_services)
        val catTrade        = context.getString(R.string.seed_category_trade)
        val catRealEstate   = context.getString(R.string.seed_category_real_estate)
        val catHobby        = context.getString(R.string.seed_category_hobby)
        addTag(context.getString(R.string.seed_tag_electrician), catHomeServices)
        addTag(context.getString(R.string.seed_tag_plumber), catHomeServices)
        addTag(context.getString(R.string.seed_tag_handyman), catHomeServices)
        addTag(context.getString(R.string.seed_tag_psychologist), catHomeServices)
        addTag(context.getString(R.string.seed_tag_massage_therapist), catHomeServices)
        addTag(context.getString(R.string.seed_tag_tutor), catHomeServices)
        addTag(context.getString(R.string.seed_tag_driver), catHomeServices)
        addTag(context.getString(R.string.seed_tag_accountant), catFinance)
        addTag(context.getString(R.string.seed_tag_financial_advisor), catFinance)
        addTag(context.getString(R.string.seed_tag_realtor), catRealEstate)
        addTag(context.getString(R.string.seed_tag_landlord), catRealEstate)
        addTag(context.getString(R.string.seed_tag_tenant), catRealEstate)
        addTag(context.getString(R.string.seed_tag_programmer), catIt)
        addTag(context.getString(R.string.seed_tag_seller), catTrade)
        addTag(context.getString(R.string.seed_tag_fishing), catHobby)
        AppSettings.defaultTagsSeeded.value = true
    }

    // ── Свои типы отношений («статусы») — фидбэк владельца 2026-07-05: создаёт
    // свой тип, а он «не входит в фильтры, не ищется, не редактируется, не
    // удаляется» — customRelationshipType был голым текстовым полем без CRUD,
    // в отличие от групп. Добавляем тот же набор операций, что и для групп. ──

    /** Отсортированный список уникальных пользовательских типов отношений в использовании. */
    fun distinctCustomRelationshipTypes(): List<String> =
        contacts.mapNotNull { it.customRelationshipType?.trim()?.takeIf { s -> s.isNotBlank() } }
            .distinct().sortedBy { it.lowercase() }

    /** Все теги, реально используемые хотя бы одним контактом — единое место
     *  истины для автодополнения (раньше каждый экран считал свою локальную
     *  копию, ContactEditScreen её вообще не видел — теги-дубли с опечаткой/
     *  другим регистром заводились незаметно, фидбэк владельца 2026-07-11). */
    fun allTags(): List<String> =
        contacts.flatMap { it.tags }.distinct().sortedBy { it.lowercase() }

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
    // ФИКС (аудит 2026-08-11, жалоба «опять пропали все заметки после
    // обновления»): раньше company-insert / note-reassign / calendar-relink /
    // contact-delete шли ПЯТЬЮ независимыми scope.launch без объединяющей
    // транзакции (через addCompany/updateCompany/updateNote/updateCalendarItem/
    // deleteContact) — если процесс убивался между записью reassign'а заметки
    // и записью удаления контакта (например, ровно во время обновления
    // приложения), deleteNotesForContact(contactId) внутри deleteContact мог
    // снести заметку, которая должна была уже переехать на компанию. Тот же
    // класс гонки, что mergeContacts()/mergeCompanies() уже закрыли
    // db.withTransaction — здесь применяем тот же паттерн: in-memory мутации
    // синхронны как везде, а ВСЯ запись в БД — одна атомарная транзакция.
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
        val reassignedNotes = notes.filter { it.contactId == contactId }.toList()
            .map { it.copy(contactId = null, companyId = companyId) }
        // Ссылки событий: targetType CONTACT/contactId → COMPANY/companyId.
        val calendarUpdates = calendarItems.filter { ci -> ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId } }
            .toList().map { ci ->
                val newLinks = ci.links.map {
                    if (it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId)
                        it.copy(targetType = CalendarTargetType.COMPANY, targetId = companyId)
                    else it
                }.distinctBy { it.targetType to it.targetId }
                ci.copy(links = newLinks)
            }
        // Каскад удаления контакта (как в deleteContact) — id связей считаем
        // ДО мутаций в памяти.
        val relationIds = contactRelations
            .filter { it.firstContactId == contactId || it.secondContactId == contactId }
            .map { it.id }

        // ── применяем в памяти (синхронно, как везде в этом файле) ──
        companies.add(company)
        addresses.addAll(company.addresses)
        reassignedNotes.forEach { n -> val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n }
        calendarUpdates.forEach { ci -> val i = calendarItems.indexOfFirst { it.id == ci.id }; if (i >= 0) calendarItems[i] = ci }
        contacts.removeAll { it.id == contactId }
        gifts.removeAll { it.contactId == contactId }
        sizeInfos.removeAll { it.contactId == contactId }
        personalDetails.removeAll { it.contactId == contactId }
        addresses.removeAll { it.ownerId == contactId && it.ownerType == AddressOwnerType.CONTACT }
        companyRelations.removeAll { it.contactId == contactId }
        contactRelations.removeAll { it.firstContactId == contactId || it.secondContactId == contactId }
        groupMembers.removeAll { it.contactId == contactId }
        tagMembers.removeAll { it.contactId == contactId }

        // ── одна транзакция на всю конвертацию: либо всё, либо (при сбое) ничего ──
        scope.launch {
            val db = db() ?: return@launch
            db.withTransaction {
                db.companyDao().insertCompany(company.toEntity())
                if (company.addresses.isNotEmpty()) db.addressDao().insertAddresses(company.addresses.map { it.toEntity() })
                if (company.phones.isNotEmpty())    db.contactDao().insertPhones(company.phones.map { it.toCompanyEntity(company.id) })
                if (company.emails.isNotEmpty())    db.contactDao().insertEmails(company.emails.map { it.toCompanyEntity(company.id) })

                // Реассайн заметок ДО удаления контакта — иначе deleteNotesForContact
                // ниже снёс бы их (см. комментарий над функцией).
                if (reassignedNotes.isNotEmpty()) db.noteDao().insertNotes(reassignedNotes.map { it.toEntity() })

                calendarUpdates.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }

                db.contactDao().deleteGroupMembersForContact(contactId)
                db.contactDao().deleteTagMembersForContact(contactId)
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
        return companyId
    }

    // ──────────────────────────────────────────────────────────
    //  COMPANIES CRUD
    // ──────────────────────────────────────────────────────────
    fun getCompanyById(id: String): Company? = companies.find { it.id == id }
    fun getCompany(id: String): Company?     = getCompanyById(id)

    // ЕДИНАЯ нормализация имени компании для дедупа (trim + ignoreCase) —
    // раньше три экрана (WorkplaceComponents, ContactEditScreen, ImportScreens)
    // дублировали это сравнение независимо, а CompanyEditScreen его не делал
    // вовсе, из-за чего «Электрик» и «электрик » превращались в две компании.
    fun findCompanyByName(name: String): Company? =
        companies.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

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

    /**
     * Восстановление компании из снапшота отмены слияния (mergeCompanies) БЕЗ
     * перезаписи дат createdAt/updatedAt — аналог restoreContact. Idempotent:
     * чистит детей компании, потом вставляет как есть.
     */
    fun restoreCompany(company: Company) {
        val idx = companies.indexOfFirst { it.id == company.id }
        if (idx >= 0) companies[idx] = company else companies.add(company)
        addresses.removeAll { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
        addresses.addAll(company.addresses)
        scope.launch {
            val db = db() ?: return@launch
            db.companyDao().deletePhonesForCompany(company.id)
            db.companyDao().deleteEmailsForCompany(company.id)
            db.addressDao().deleteAddressesForOwner(company.id, AddressOwnerType.COMPANY.name)
            addCompanyDb(company)
        }
    }

    fun deleteCompany(companyId: String) {
        // Каскад (баг §35, найден повторным аудитом): раньше связи с контактами
        // и ссылки календарных событий на удаляемую компанию оставались сиротами
        // — тем же классом бага, что deleteContact уже чинил для контактов.
        calendarItems.filter { ci -> ci.links.any { it.targetType == CalendarTargetType.COMPANY && it.targetId == companyId } }
            .toList().forEach { ci ->
                updateCalendarItem(ci.copy(links = ci.links.filterNot {
                    it.targetType == CalendarTargetType.COMPANY && it.targetId == companyId
                }))
            }
        companies.removeAll { it.id == companyId }
        addresses.removeAll { it.ownerId == companyId && it.ownerType == AddressOwnerType.COMPANY }
        companyRelations.removeAll { it.companyId == companyId }
        // ФИКС (аудит 2026-08-11): notes удалялись из БД, но не из in-memory
        // списка — «заметка-призрак» в UI до перезапуска приложения.
        notes.removeAll { it.companyId == companyId }
        scope.launch {
            val db = db() ?: return@launch
            db.companyDao().deleteCompany(companyId)
            db.addressDao().deleteAddressesForOwner(companyId, AddressOwnerType.COMPANY.name)
            db.companyDao().deletePhonesForCompany(companyId)
            db.companyDao().deleteEmailsForCompany(companyId)
            db.noteDao().deleteNotesForCompany(companyId)
            db.contactDao().deleteCompanyRelationsForCompany(companyId)
        }
    }

    /**
     * «Это адрес компании» (2026-07-23, решение владельца): контакт помечает
     * свой рабочий адрес как адрес компании, где он работает. Ищем среди уже
     * существующих адресов компании совпадение по нормализованной улице+городу
     * (та же логика, что и дедуп при слиянии компаний, см. mergeCompanies) —
     * если есть, просто возвращаем его id, НЕ плодя копию; если нет, заводим
     * один новый адрес компании (тип OFFICE) и возвращаем его id. Использующий
     * код сохраняет этот id в ContactCompanyRelation.officeAddressId.
     */
    fun linkContactAddressToCompany(companyId: String, address: Address): String {
        val existing = addresses.firstOrNull {
            it.ownerId == companyId && it.ownerType == AddressOwnerType.COMPANY &&
                it.addressLine.trim().equals(address.addressLine.trim(), ignoreCase = true) &&
                it.city.trim().equals(address.city.trim(), ignoreCase = true)
        }
        if (existing != null) return existing.id
        val newAddr = address.copy(
            id = generateId(), ownerType = AddressOwnerType.COMPANY, ownerId = companyId,
            addressType = AddressType.OFFICE
        )
        addresses.add(newAddr)
        // ФИКС: Company.addresses — это снимок, а не производное от глобального
        // addresses (getCompanyById просто ищет по companies) — без этой строки
        // CompanyEditScreen/CompanyDetailScreen не увидели бы новый адрес до
        // перезапуска приложения (reloadFromDb). Тот же паттерн синхронизации,
        // что и в updateCompany/mergeCompanies выше.
        val companyIdx = companies.indexOfFirst { it.id == companyId }
        if (companyIdx >= 0) companies[companyIdx] = companies[companyIdx].copy(addresses = companies[companyIdx].addresses + newAddr)
        scope.launch { db()?.addressDao()?.insertAddresses(listOf(newAddr.toEntity())) }
        return newAddr.id
    }

    // ──────────────────────────────────────────────────────────
    //  ДУБЛИКАТЫ КОМПАНИЙ: поиск и слияние (точный аналог секции
    //  «ДУБЛИКАТЫ» контактов выше — см. комментарии там)
    // ──────────────────────────────────────────────────────────

    /** «Полнота» компании — при слиянии по умолчанию keep — самая заполненная
     *  (аналог contactScore). Считает и сотрудников (companyRelations), т.к.
     *  это не поле модели Company, а глобальный список по companyId. */
    internal fun companyScore(c: Company): Int =
        c.phones.size + c.emails.size + c.addresses.size +
        companyRelations.count { it.companyId == c.id } +
        (if (c.description.isNullOrBlank()) 0 else 1)

    /** Пара возможных дублей компаний + причина совпадения (аналог DuplicateMatch
     *  для контактов): нормализованное название, сайт (без протокола/www/
     *  завершающего слэша), телефон (последние цифры) или email компании. */
    data class CompanyDuplicateMatch(
        val a: Company,
        val b: Company,
        val byName: Boolean = false,
        val byWebsite: String? = null,
        val byPhone: String? = null,
        val byEmail: String? = null
    )

    private fun normalizedWebsite(url: String?): String? {
        val v = url?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return v.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    }

    /** Пары возможных дублей компаний: совпадение по нормализованному названию,
     *  сайту, телефону (≥7 цифр) или email. Без повторов и без пар «сам с собой». */
    fun findCompanyDuplicatePairs(): List<CompanyDuplicateMatch> {
        val list = companies.toList()
        val result = mutableListOf<CompanyDuplicateMatch>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val an = a.name.trim().searchNormalize()
                val bn = b.name.trim().searchNormalize()
                val nameHit = an.isNotBlank() && an == bn
                val aw = normalizedWebsite(a.website)
                val bw = normalizedWebsite(b.website)
                val websiteHit = if (!aw.isNullOrBlank() && aw == bw) aw else null
                val ap = a.phones.map { phoneDigits(it.number) }.filter { it.length >= 7 }
                val bp = b.phones.map { phoneDigits(it.number) }.filter { it.length >= 7 }
                val phoneHit = ap.firstOrNull { it in bp }
                val ae = a.emails.map { it.email.trim().lowercase() }.filter { it.isNotBlank() }
                val be = b.emails.map { it.email.trim().lowercase() }.filter { it.isNotBlank() }
                val emailHit = ae.firstOrNull { it in be }
                if (nameHit || websiteHit != null || phoneHit != null || emailHit != null) {
                    result.add(CompanyDuplicateMatch(a, b, nameHit, websiteHit, phoneHit, emailHit))
                }
            }
        }
        return result
    }

    /** Снимок ДО слияния компаний — для отмены (аналог MergeUndoSnapshot). */
    data class CompanyMergeUndoSnapshot(
        val companies: List<Company>,
        val notes: List<Note>,
        val calendarItems: List<CalendarItem>,
        val companyRelations: List<ContactCompanyRelation>
    )

    /**
     * Слияние 2-3 компаний в одну — точный аналог mergeContacts (см. подробный
     * комментарий там). Списочные поля самой компании (телефоны/email/адреса)
     * объединяются честно — union с дедупом. Сотрудники (ContactCompanyRelation)
     * переносятся со всех сливаемых компаний на итоговую; дедуп — по паре
     * (contactId, position), НЕ по одному companyId (иначе вторая должность
     * того же контакта на другой позиции терялась бы целиком — тот же принцип,
     * что и полный дедуп companyRelations внутри mergeContacts). Заметки и
     * календарные события ретаргетятся на итоговую компанию (тот же паттерн
     * ретаргетинга, что в convertContactToCompany/mergeContacts). Одиночные
     * конфликтующие поля (industry, logoUri) — явный выбор через
     * resolution.choiceWinners, иначе первое непустое; свободный текст
     * (name/description/website) — combineChosenText, как у контактов.
     * Всё — одной Room-транзакцией: либо весь merge применяется, либо (при
     * сбое) ни одна из участвующих компаний не тронута на диске.
     *
     * Возвращает снимок для отмены (null, если слияние не выполнено — id не
     * найдены или их меньше 2/больше 3).
     */
    fun mergeCompanies(companyIds: List<String>, resolution: MergeResolution = MergeResolution()): CompanyMergeUndoSnapshot? {
        val uniqueIds = companyIds.distinct()
        if (uniqueIds.size < 2 || uniqueIds.size > 3) return null
        val group = uniqueIds.mapNotNull { getCompany(it) }
        if (group.size != uniqueIds.size) return null

        val keep = group.maxByOrNull { companyScore(it) } ?: group.first()
        val keepId = keep.id
        val others = group.filter { it.id != keepId }
        val otherIds = others.map { it.id }
        val allPairs = group.map { it.id to it }

        // ── снимок ДО любых мутаций ──
        val snapshotNotes = notes.filter { it.companyId in uniqueIds }.toList()
        val snapshotCalendarItems = calendarItems.filter { ci ->
            ci.links.any { it.targetType == CalendarTargetType.COMPANY && it.targetId in uniqueIds }
        }.toList()
        val snapshotRelations = companyRelations.filter { it.companyId in uniqueIds }.toList()
        val snapshot = CompanyMergeUndoSnapshot(
            companies = group, notes = snapshotNotes,
            calendarItems = snapshotCalendarItems, companyRelations = snapshotRelations
        )

        // ── списочные поля самой компании: union с дедупом (как у контактов) ──
        val mergedPhones = keep.phones.toMutableList()
        val mergedEmails = keep.emails.toMutableList()
        val mergedAddresses = keep.addresses.toMutableList()
        others.forEach { other ->
            other.phones.forEach { p ->
                val d = phoneDigits(p.number)
                if (d.isEmpty() || mergedPhones.none { phoneDigits(it.number) == d })
                    mergedPhones.add(p.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.emails.forEach { e ->
                if (mergedEmails.none { it.email.equals(e.email, true) })
                    mergedEmails.add(e.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.addresses.forEach { a ->
                if (mergedAddresses.none { it.addressLine.equals(a.addressLine, true) && it.city.equals(a.city, true) })
                    mergedAddresses.add(a.copy(id = generateId(), ownerId = keepId))
            }
        }

        fun winner(key: String) = resolution.choiceWinners[key]
        fun text(key: String, getter: (Company) -> String?) =
            combineChosenText(key, allPairs.map { it.first to getter(it.second) }, resolution.textIncluded[key])

        val merged = keep.copy(
            name = text("name") { it.name } ?: keep.name,
            description = text("description") { it.description },
            website = text("website") { it.website },
            // Фото и enum-поле модели физически одноместные — выбор неизбежен,
            // что не выбрано, MergeResolveScreen сохраняет заметкой (см. mergeContacts).
            logoUri = pickSingle("logoUri", allPairs.map { it.first to it.second.logoUri }, winner("logoUri")),
            industry = pickEnum(allPairs.map { it.first to it.second.industry }, winner("industry")),
            phones = mergedPhones, emails = mergedEmails, addresses = mergedAddresses
        )

        // ── сотрудники (ContactCompanyRelation): переносим "чужие" на keepId,
        //    дедуп по (contactId, position) — НЕ по одному companyId ──
        val keepOwnRelations = snapshotRelations.filter { it.companyId == keepId }
        val otherRelations = snapshotRelations.filter { it.companyId in otherIds }
        val mergedRelations = keepOwnRelations.toMutableList()
        val relationsToDelete = mutableListOf<String>()
        val relationsToRetarget = mutableListOf<ContactCompanyRelation>()
        otherRelations.forEach { r ->
            val posKey = r.position?.trim()?.lowercase().orEmpty()
            val dup = mergedRelations.any {
                it.contactId == r.contactId && (it.position?.trim()?.lowercase().orEmpty()) == posKey
            }
            if (dup) {
                relationsToDelete.add(r.id)
            } else {
                val retargeted = r.copy(companyId = keepId, isPrimary = false)
                mergedRelations.add(retargeted)
                relationsToRetarget.add(retargeted)
            }
        }

        val notesToReassign = snapshotNotes.filter { it.companyId in otherIds }.map { it.copy(companyId = keepId) }

        val calendarUpdates = snapshotCalendarItems.map { ci ->
            ci.copy(links = ci.links.map {
                if (it.targetType == CalendarTargetType.COMPANY && it.targetId in otherIds) it.copy(targetId = keepId) else it
            }.distinctBy { it.targetType to it.targetId })
        }

        // ── применяем в памяти (синхронно, как везде в этом файле) ──
        val keepIdx = companies.indexOfFirst { it.id == keepId }
        if (keepIdx >= 0) companies[keepIdx] = merged.copy(updatedAt = nowIso())
        otherIds.forEach { oid -> companies.removeAll { it.id == oid } }
        addresses.removeAll { it.ownerId == keepId && it.ownerType == AddressOwnerType.COMPANY }
        addresses.addAll(merged.addresses)
        otherIds.forEach { oid -> addresses.removeAll { it.ownerId == oid && it.ownerType == AddressOwnerType.COMPANY } }
        relationsToDelete.forEach { rid -> companyRelations.removeAll { it.id == rid } }
        relationsToRetarget.forEach { r -> val i = companyRelations.indexOfFirst { it.id == r.id }; if (i >= 0) companyRelations[i] = r }
        notesToReassign.forEach { n -> val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n }
        calendarUpdates.forEach { ci -> val i = calendarItems.indexOfFirst { it.id == ci.id }; if (i >= 0) calendarItems[i] = ci }

        // ── одна транзакция на весь merge: либо всё, либо (при сбое) ничего ──
        scope.launch {
            val db = db() ?: return@launch
            db.withTransaction {
                db.addressDao().deleteAddressesForOwner(keepId, AddressOwnerType.COMPANY.name)
                db.companyDao().deletePhonesForCompany(keepId)
                db.companyDao().deleteEmailsForCompany(keepId)

                db.companyDao().insertCompany(merged.toEntity())
                if (merged.phones.isNotEmpty()) db.contactDao().insertPhones(merged.phones.map { it.toCompanyEntity(keepId) })
                if (merged.emails.isNotEmpty()) db.contactDao().insertEmails(merged.emails.map { it.toCompanyEntity(keepId) })
                if (merged.addresses.isNotEmpty()) db.addressDao().insertAddresses(merged.addresses.map { it.toEntity() })

                relationsToDelete.forEach { rid -> db.contactDao().deleteCompanyRelation(rid) }
                if (relationsToRetarget.isNotEmpty()) db.contactDao().insertCompanyRelations(relationsToRetarget.map { it.toEntity() })

                // Реассайн заметок ДО удаления «других» компаний — иначе
                // последующий deleteNotesForCompany(oid) снёс бы их.
                if (notesToReassign.isNotEmpty()) db.noteDao().insertNotes(notesToReassign.map { it.toEntity() })

                calendarUpdates.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }

                otherIds.forEach { oid ->
                    db.addressDao().deleteAddressesForOwner(oid, AddressOwnerType.COMPANY.name)
                    db.companyDao().deletePhonesForCompany(oid)
                    db.companyDao().deleteEmailsForCompany(oid)
                    db.noteDao().deleteNotesForCompany(oid)
                    db.companyDao().deleteCompany(oid)
                }
            }
        }

        return snapshot
    }

    /** Отмена слияния компаний по снимку (аналог undoMerge для контактов) —
     *  Snackbar «Объединено · Отменить». Переиспользует restoreCompany/
     *  updateCalendarItem — не единая транзакция (это уже путь восстановления),
     *  но каждый вызов идемпотентен и безопасен сам по себе. */
    fun undoMergeCompanies(snapshot: CompanyMergeUndoSnapshot) {
        snapshot.companies.forEach { restoreCompany(it) }
        snapshot.notes.forEach { n ->
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = n else notes.add(n)
            scope.launch { db()?.noteDao()?.insertNotes(listOf(n.toEntity())) }
        }
        snapshot.calendarItems.forEach { updateCalendarItem(it) }
        snapshot.companyRelations.forEach { r ->
            val i = companyRelations.indexOfFirst { it.id == r.id }
            if (i >= 0) companyRelations[i] = r else companyRelations.add(r)
            scope.launch { db()?.contactDao()?.insertCompanyRelations(listOf(r.toEntity())) }
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
     * Восстановление события из бэкапа БЕЗ перезаписи createdAt/updatedAt —
     * аналог restoreContact. Upsert по id; ссылки/напоминания персистятся в БД
     * тем же путём, что updateCalendarItem (delete-then-reinsert), но исходные
     * даты бэкапа не трогаются.
     */
    fun restoreCalendarItem(item: CalendarItem) {
        val idx = calendarItems.indexOfFirst { it.id == item.id }
        if (idx >= 0) calendarItems[idx] = item else calendarItems.add(item)
        scope.launch {
            val db = db() ?: return@launch
            db.calendarDao().deleteLinksForItem(item.id)
            db.calendarDao().deleteRemindersForItem(item.id)
            addCalendarItemDb(item)
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
            markContactedNow(item.links.filter { it.targetType == CalendarTargetType.CONTACT }.map { it.targetId })
        }
    }

    /** Проставить «последний контакт» = сегодня для одного или нескольких
     *  контактов. Используется быстрыми действиями (звонок/сообщение) и
     *  ручной отметкой «Связался» в списке «Нужно связаться». */
    fun markContactedNow(contactIds: List<String>) {
        val now = nowIso()
        val today = now.take(10)
        contactIds.forEach { id ->
            val idx = contacts.indexOfFirst { it.id == id }
            if (idx >= 0) {
                contacts[idx] = contacts[idx].copy(lastContactDate = today)
                scope.launch { db()?.contactDao()?.updateLastContactDate(id, today, now) }
            }
        }
    }
    fun markContactedNow(contactId: String) = markContactedNow(listOf(contactId))

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
     *  in-memory списки. Демоданные больше не сеются — после перезапуска
     *  приложение остаётся пустым (v14, DemoDataProvider удалён). */
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
                    tags.clear(); tagMembers.clear()
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

    /**
     * Восстановление заметки из бэкапа БЕЗ перезаписи createdAt/updatedAt —
     * аналог restoreContact/restoreGroup/restoreTag. Upsert по id, синхронизирует
     * вложенный Contact.notes (как addNote/updateNote) для contactId-заметок;
     * companyId-заметки живут только в плоском списке (Company их не хранит
     * вложенно — см. §44 SOCIALSPHERE_KNOWLEDGE.md).
     */
    fun restoreNote(note: Note) {
        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note else notes.add(note)
        note.contactId?.let { cid ->
            val cidx = contacts.indexOfFirst { it.id == cid }
            if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
                notes = contacts[cidx].notes.filter { it.id != note.id } + note
            )
        }
        scope.launch { db()?.noteDao()?.insertNotes(listOf(note.toEntity())) }
    }

    // ──────────────────────────────────────────────────────────
    //  GIFTS CRUD
    // ──────────────────────────────────────────────────────────
    /** Восстановление подарка из бэкапа — точный аналог restoreNote (upsert по id,
     *  синхронизирует и плоский список, и вложенное Contact.gifts, и БД). */
    fun restoreGift(gift: GiftIdea) {
        val idx = gifts.indexOfFirst { it.id == gift.id }
        if (idx >= 0) gifts[idx] = gift else gifts.add(gift)
        val cidx = contacts.indexOfFirst { it.id == gift.contactId }
        if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
            gifts = contacts[cidx].gifts.filter { it.id != gift.id } + gift
        )
        scope.launch { db()?.giftDao()?.insertGifts(listOf(gift.toEntity())) }
    }

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
