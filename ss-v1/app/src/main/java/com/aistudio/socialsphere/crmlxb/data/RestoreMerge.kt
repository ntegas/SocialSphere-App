package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*

/**
 * BACKUP INTEGRITY PACK #3 (2026-09-21) — семантика restore.
 *
 * Раньше «restore» означал неявную деструктивную замену: для каждого контакта из backup удалялись ВСЕ
 * текущие дочерние строки (телефоны, email, адреса, размеры, детали…) и вставлялись строки backup;
 * компании, события и группы перезаписывались целиком. Старый backup молча затирал более новые
 * значения (координаты → null, recurrence-поля → значения по умолчанию, свежее имя → старое) и
 * удалял current-only данные.
 *
 * Теперь два явных случая, один алгоритм:
 *  • FRESH  — в Room нет пользовательских данных (контакты/компании/события/заметки/подарки пусты):
 *    backup — источник истины, всё, что в нём есть, добавляется 1:1 (id, значения, даты).
 *  • MERGE  — данные уже есть: restore DATA-SAFE, детерминированный, по устойчивым id:
 *      – сущность/дочерняя строка есть ТОЛЬКО в текущем состоянии → сохраняется (старый backup не
 *        может доказать намеренное удаление: tombstone/истории удалений в формате нет);
 *      – есть ТОЛЬКО в backup → добавляется (с id, значениями и датами backup);
 *      – одинакова → no-op (идемпотентность, повторный restore ничего не меняет);
 *      – одно и то же id, значения расходятся ПОЛЕМ: значение backup «пусто/по умолчанию» (старый
 *        формат не знал поля или оно было не задано) → остаётся текущее; текущее пусто → заполняется из
 *        backup; ОБА заданы и различаются → остаётся ТЕКУЩЕЕ, расхождение попадает в [RestoreResult.conflicts].
 *    Почему не «побеждает более свежий»: надёжных метаданных порядка нет — updatedAt локальное время
 *    без часового пояса, выставляется непоследовательно (часть путей его не обновляет, restore
 *    компаний раньше перештамповывал), у дочерних таблиц (телефоны, подарки, адреса, размеры…) его
 *    нет вовсе. Угадывать победителя нельзя — сохраняем текущее и сообщаем о конфликте.
 *    Исключения-max: lastContactDate и lastCompletedOccurrenceDate монотонны по смыслу («последний»),
 *    берётся более поздняя дата — это не догадка о свежести, а семантика поля.
 *
 * Функции чистые (без БД и без AppStateStore): вход — [PersistedState] (снимок Room) и [RestorePlan],
 * выход — [RestoreWrites] (что записать) и [RestoreResult] (что произошло). Запись — в одной
 * транзакции AppStateStore.restoreBackup.
 *
 * EXPLICIT RECOVERY PACK (2026-09-22): tombstone («это конкретное id было НАМЕРЕННО удалено») перестал быть
 * «доказательства удаления в формате нет» — теперь есть. MERGE делится на явную авторизацию ([RestoreAuthorization]):
 *  • SAFE_MERGE (default, прежнее поведение MERGE минус один баг) — backup-only запись с ЛОКАЛЬНЫМ tombstone
 *    больше НЕ добавляется (не воскресает случайно); без tombstone — как раньше, добавляется;
 *  • SELECTIVE(keys) — то же, что SAFE_MERGE, но для entity, чей (type,id) явно выбран пользователем,
 *    tombstone игнорируется: запись восстанавливается из backup, а её tombstone снимается;
 *  • RESTORE_ALL_DELETED — то же самое, keys = ВСЕ локальные tombstone, для которых запись реально есть в backup.
 * FRESH не меняется: клин-инсталл ничего не защищает, восстанавливается всё, что есть в backup, как раньше;
 * tombstones бэкапа переносятся как есть (деление истории продолжает жить на новом устройстве).
 * Зависимость (Note/Gift → Contact/Company): если выбранный на восстановление ребёнок ссылается на РОДИТЕЛЯ,
 * который тоже tombstoned и НЕ выбран, родитель добавляется в выбор автоматически ([expandForDependencies]) —
 * тем же явным действием пользователя (Restore), а не тихим предположением; сирот не создаётся никогда.
 */
enum class RestoreMode { FRESH, MERGE }

/** Устойчивый идентификатор tombstone-сущности — пара (тип, id), не суррогатный id самого tombstone. */
data class TombstoneKey(val type: TombstoneEntityType, val id: String)

/** Явная авторизация пользователя на то, ЧТО restore вправе вернуть из удалённого. См. шапку файла. */
sealed class RestoreAuthorization {
    /** По умолчанию: current данные и история удалений сохраняются, ничего удалённое не воскресает. */
    object SafeMerge : RestoreAuthorization()
    /** Пользователь явно выбрал конкретные записи для восстановления (может быть дополнен зависимостями). */
    data class Selective(val keys: Set<TombstoneKey>) : RestoreAuthorization()
    /** Пользователь явно авторизовал восстановление ВСЕХ записей backup, помеченных здесь удалёнными. */
    object RestoreAllDeleted : RestoreAuthorization()
}

/** Одна запись из [RecoveryCandidates] — то, что реально можно вернуть: tombstoned локально И есть в backup. */
data class RecoverableRecord(
    val key: TombstoneKey,
    /** Короткая подпись для UI (имя контакта/название подарка/текст заметки…), не бизнес-данные. */
    val label: String,
    val deletedAt: String,
    /** Родитель, которого тоже придётся восстановить (тоже tombstoned), если он есть — см. [expandForDependencies]. */
    val requiredParent: TombstoneKey?,
)

data class RestoreConflict(
    val entity: String,
    val id: String,
    val field: String,
    /** Значение, которое осталось (текущее). */
    val kept: String?,
    /** Значение из backup, которое НЕ применено. */
    val backup: String?,
)

data class RestoreResult(
    val mode: RestoreMode,
    val contactsInBackup: Int,
    val addedContacts: Int,
    val addedCompanies: Int,
    val addedCalendarItems: Int,
    val addedNotes: Int,
    val addedGifts: Int,
    /** Добавлено дочерних строк существующих сущностей (телефоны, адреса, детали, связи…). */
    val addedChildren: Int,
    /** Пустые текущие поля, заполненные значениями из backup. */
    val filledFields: Int,
    /** Общее число расхождений «оба значения заданы и различаются» (оставлено текущее). */
    val conflictCount: Int,
    /** Первые [RestoreMerger.MAX_REPORTED_CONFLICTS] расхождений (для показа/диагностики). */
    val conflicts: List<RestoreConflict>,
    /** EXPLICIT RECOVERY PACK: сколько backup-only записей корректно ОСТАЛИСЬ удалёнными (tombstoned, не в authorization). */
    val skippedTombstoned: Int = 0,
    /** Сколько записей явно восстановлено из удалённых (SELECTIVE/RESTORE_ALL, включая авто-добавленных родителей). */
    val recoveredFromTombstone: Int = 0,
)

/** Что записать в Room (только изменения; upsert по id, ничего не удаляется). */
internal class RestoreWrites(
    val companies: List<Company>,
    val contacts: List<Contact>,
    val calendarItems: List<CalendarItem>,
    val contactRelations: List<ContactRelation>,
    val groups: List<ContactGroup>,
    val groupMembers: List<ContactGroupMember>,
    val tags: List<Tag>,
    val tagMembers: List<ContactTagMember>,
    val notes: List<Note>,
    val gifts: List<GiftIdea>,
    /** EXPLICIT RECOVERY PACK: финальный набор tombstone-строк (upsert по (type,id)). */
    val tombstonesToUpsert: List<Tombstone> = emptyList(),
    /** Tombstone, которые нужно УДАЛИТЬ (запись явно восстановлена этим restore). */
    val tombstonesToClear: List<TombstoneKey> = emptyList(),
)

internal class RestoreMerger(
    private val seedTagKey: (String) -> String?,
    private val now: String,
) {
    companion object {
        const val MAX_REPORTED_CONFLICTS = 100
    }

    private val conflicts = ArrayList<RestoreConflict>()

    /** Поля, по которым зафиксирован конфликт (для тестов покрытия слияния). */
    internal val conflictFields: List<String> get() = conflicts.map { it.field }
    private var conflictTotal = 0
    private var filled = 0
    private var addedChildren = 0

    // ── отчёт/счётчики ────────────────────────────────────────

    private fun conflict(entity: String, id: String, field: String, kept: Any?, backup: Any?) {
        conflictTotal++
        if (conflicts.size < MAX_REPORTED_CONFLICTS) {
            conflicts += RestoreConflict(entity, id, field, kept?.toString()?.take(200), backup?.toString()?.take(200))
        }
    }

    private fun isEmptyValue(v: Any?): Boolean =
        v == null || (v is CharSequence && v.isBlank()) || (v is Collection<*> && v.isEmpty())

    /**
     * Слияние одного поля (см. шапку файла):
     *  backup == текущее -> текущее; значение backup «пусто» -> текущее; текущее «пусто» -> backup (fill);
     *  иначе -> текущее + конфликт. [bakAbsent]/[curAbsent] переопределяются для полей, у которых «нет
     *  значения» выражается не null/пустой строкой (boolean-флаги, enum со значением по умолчанию).
     */
    private fun <T> field(
        entity: String, id: String, name: String, cur: T, bak: T,
        bakAbsent: (T) -> Boolean = { isEmptyValue(it) },
        curAbsent: (T) -> Boolean = { isEmptyValue(it) },
    ): T {
        if (bak == cur) return cur
        if (bakAbsent(bak)) return cur
        if (curAbsent(cur)) { filled++; return bak }
        conflict(entity, id, name, cur, bak)
        return cur
    }

    private val flagAbsent: (Boolean) -> Boolean = { !it }
    private fun laterIso(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        a >= b -> a
        else -> b
    }

    /** Дочерняя коллекция по устойчивым id: только-текущие сохраняются, только-backup добавляются,
     *  общие сливаются полем. Порядок текущих сохраняется.
     *
     *  Сопоставление «та же запись» — сначала по id; если id нет, то по [valueKey] (для телефонов/email
     *  это номер/адрес): id перегенерируются импортом и слиянием, поэтому тот же номер с другим id —
     *  не «новая запись из backup», а та же самая (иначе restore плодит дубли номеров). Совпадение по id
     *  приоритетнее: правка номера в текущем состоянии по-прежнему даёт конфликт поля, а не вторую запись. */
    private fun <T> mergeChildren(cur: List<T>, bak: List<T>, id: (T) -> String, mergeOne: (T, T) -> T, valueKey: ((T) -> String)? = null): List<T> {
        if (bak.isEmpty()) return cur
        val out = cur.toMutableList()
        val index = HashMap<String, Int>().also { m -> cur.forEachIndexed { i, c -> m[id(c)] = i } }
        val valueIndex = HashMap<String, Int>()
        if (valueKey != null) cur.forEachIndexed { i, c -> valueIndex.putIfAbsent(valueKey(c), i) }
        for (b in bak) {
            val at = index[id(b)] ?: valueKey?.let { valueIndex[it(b)] }
            if (at == null) {
                out += b; index[id(b)] = out.lastIndex; valueKey?.let { valueIndex.putIfAbsent(it(b), out.lastIndex) }; addedChildren++
            } else out[at] = mergeOne(out[at], b)
        }
        return out
    }

    private fun phoneMatchKey(p: ContactPhone): String = ContactChannelDedup.phoneValueKey(p)

    private fun emailMatchKey(e: ContactEmail): String = ContactChannelDedup.emailValueKey(e)

    /** Совпадение по значению при РАЗНЫХ id: форматирование номера («+7 912…» / «8912…») не конфликт —
     *  остаётся текущая запись. При совпадении по id поведение прежнее (правка номера = конфликт поля). */
    private fun mergePhoneByValue(cur: ContactPhone, bak: ContactPhone): ContactPhone =
        mergePhone(cur, if (cur.id != bak.id && phoneMatchKey(cur) == phoneMatchKey(bak)) bak.copy(number = cur.number) else bak)

    private fun mergeEmailByValue(cur: ContactEmail, bak: ContactEmail): ContactEmail =
        mergeEmail(cur, if (cur.id != bak.id && emailMatchKey(cur) == emailMatchKey(bak)) bak.copy(email = cur.email) else bak)

    /** Значение-список без устойчивых id (calendar links/reminders: id перегенерируются при КАЖДОМ
     *  сохранении события, см. CalendarItemEditScreen): пусто в backup -> текущее; пусто текущее ->
     *  backup (id backup сохраняются); оба заданы и по смыслу совпадают -> текущее; иначе -> текущее + конфликт. */
    private fun <T> mergeValueList(entity: String, id: String, name: String, cur: List<T>, bak: List<T>, key: (T) -> Any): List<T> {
        if (bak.isEmpty()) return cur
        if (cur.isEmpty()) { filled++; return bak }
        if (cur.map(key).toSet() == bak.map(key).toSet()) return cur
        conflict(entity, id, name, cur.map(key), bak.map(key))
        return cur
    }

    // ── дочерние сущности контакта/компании ───────────────────

    internal fun mergePhone(cur: ContactPhone, bak: ContactPhone): ContactPhone {
        val e = "phone"; val id = cur.id
        return cur.copy(
            number = field(e, id, "number", cur.number, bak.number),
            type = field(e, id, "type", cur.type, bak.type),
            comment = field(e, id, "comment", cur.comment, bak.comment),
            // isPrimary — расстановка UI, а не значение данных: остаётся текущая (не плодим двух «основных»)
        )
    }

    internal fun mergeEmail(cur: ContactEmail, bak: ContactEmail): ContactEmail {
        val e = "email"; val id = cur.id
        return cur.copy(
            email = field(e, id, "email", cur.email, bak.email),
            type = field(e, id, "type", cur.type, bak.type),
            comment = field(e, id, "comment", cur.comment, bak.comment),
        )
    }

    internal fun mergeMessenger(cur: Messenger, bak: Messenger): Messenger {
        val e = "messenger"; val id = cur.id
        return cur.copy(
            type = field(e, id, "type", cur.type, bak.type),
            value = field(e, id, "value", cur.value, bak.value),
            link = field(e, id, "link", cur.link, bak.link),
            comment = field(e, id, "comment", cur.comment, bak.comment),
        )
    }

    internal fun mergePersonalDetail(cur: PersonalDetail, bak: PersonalDetail): PersonalDetail {
        val e = "personalDetail"; val id = cur.id
        return cur.copy(
            category = field(e, id, "category", cur.category, bak.category),
            value = field(e, id, "value", cur.value, bak.value),
            note = field(e, id, "note", cur.note, bak.note),
        )
    }

    internal fun mergeCompanyRelation(cur: ContactCompanyRelation, bak: ContactCompanyRelation): ContactCompanyRelation {
        val e = "companyRelation"; val id = cur.id
        return cur.copy(
            companyId = field(e, id, "companyId", cur.companyId, bak.companyId),
            position = field(e, id, "position", cur.position, bak.position),
            department = field(e, id, "department", cur.department, bak.department),
            role = field(e, id, "role", cur.role, bak.role),
            employmentStatus = field(e, id, "employmentStatus", cur.employmentStatus, bak.employmentStatus),
            startDate = field(e, id, "startDate", cur.startDate, bak.startDate),
            endDate = field(e, id, "endDate", cur.endDate, bak.endDate),
            responsibilities = field(e, id, "responsibilities", cur.responsibilities, bak.responsibilities),
            managedAccounts = field(e, id, "managedAccounts", cur.managedAccounts, bak.managedAccounts),
            workNote = field(e, id, "workNote", cur.workNote, bak.workNote),
            officeAddressId = field(e, id, "officeAddressId", cur.officeAddressId, bak.officeAddressId),
        )
    }

    private fun sameLocation(a: Address, b: Address) =
        a.addressLine.trim() == b.addressLine.trim() && a.city.trim() == b.city.trim() && a.country.trim() == b.country.trim()

    internal fun mergeAddress(cur: Address, bak: Address): Address {
        val e = "address"; val id = cur.id
        // Координаты принадлежат ТЕКСТУ адреса и идут парой: не переносим координаты backup на другой адрес.
        val coords: Pair<Double?, Double?> =
            if (sameLocation(cur, bak)) {
                val absent: (Pair<Double?, Double?>) -> Boolean = { it.first == null || it.second == null }
                field(e, id, "coordinates", cur.latitude to cur.longitude, bak.latitude to bak.longitude, absent, absent)
            } else cur.latitude to cur.longitude
        return cur.copy(
            addressType = field(e, id, "addressType", cur.addressType, bak.addressType),
            addressLine = field(e, id, "addressLine", cur.addressLine, bak.addressLine),
            city = field(e, id, "city", cur.city, bak.city),
            country = field(e, id, "country", cur.country, bak.country),
            comment = field(e, id, "comment", cur.comment, bak.comment),
            postalCode = field(e, id, "postalCode", cur.postalCode, bak.postalCode),
            district = field(e, id, "district", cur.district, bak.district),
            latitude = coords.first,
            longitude = coords.second,
        )
    }

    internal fun mergeSizeInfo(cur: SizeInfo, bak: SizeInfo): SizeInfo {
        val e = "sizeInfo"; val id = cur.contactId
        return cur.copy(
            clothingSize = field(e, id, "clothingSize", cur.clothingSize, bak.clothingSize),
            shoeSize = field(e, id, "shoeSize", cur.shoeSize, bak.shoeSize),
            ringSize = field(e, id, "ringSize", cur.ringSize, bak.ringSize),
            other = field(e, id, "other", cur.other, bak.other),
        )
    }

    // ── независимые агрегаты ──────────────────────────────────

    internal fun mergeNote(cur: Note, bak: Note): Note {
        val e = "note"; val id = cur.id
        return cur.copy(
            contactId = field(e, id, "contactId", cur.contactId, bak.contactId),
            companyId = field(e, id, "companyId", cur.companyId, bak.companyId),
            calendarItemId = field(e, id, "calendarItemId", cur.calendarItemId, bak.calendarItemId),
            giftId = field(e, id, "giftId", cur.giftId, bak.giftId),
            type = field(e, id, "type", cur.type, bak.type),
            text = field(e, id, "text", cur.text, bak.text),
            date = field(e, id, "date", cur.date, bak.date),
            isImportant = field(e, id, "isImportant", cur.isImportant, bak.isImportant, flagAbsent, flagAbsent),
            isLocked = field(e, id, "isLocked", cur.isLocked, bak.isLocked, flagAbsent, flagAbsent),
        )
    }

    internal fun mergeGift(cur: GiftIdea, bak: GiftIdea): GiftIdea {
        val e = "gift"; val id = cur.id
        return cur.copy(
            contactId = field(e, id, "contactId", cur.contactId, bak.contactId),
            title = field(e, id, "title", cur.title, bak.title),
            note = field(e, id, "note", cur.note, bak.note),
            link = field(e, id, "link", cur.link, bak.link),
            date = field(e, id, "date", cur.date, bak.date),
            status = field(e, id, "status", cur.status, bak.status),
        )
    }

    internal fun mergeContactRelation(cur: ContactRelation, bak: ContactRelation): ContactRelation {
        val e = "contactRelation"; val id = cur.id
        return cur.copy(
            firstContactId = field(e, id, "firstContactId", cur.firstContactId, bak.firstContactId),
            secondContactId = field(e, id, "secondContactId", cur.secondContactId, bak.secondContactId),
            firstRole = field(e, id, "firstRole", cur.firstRole, bak.firstRole),
            secondRole = field(e, id, "secondRole", cur.secondRole, bak.secondRole),
            note = field(e, id, "note", cur.note, bak.note),
        )
    }

    internal fun mergeGroup(cur: ContactGroup, bak: ContactGroup): ContactGroup =
        cur.copy(name = field("group", cur.id, "name", cur.name, bak.name))

    // ── контакт, компания, событие ────────────────────────────

    /** notes/gifts здесь НЕ трогаются — это независимые агрегаты, сливаются плоскими списками. */
    internal fun mergeContact(cur: Contact, bak: Contact): Contact {
        val e = "contact"; val id = cur.id
        val sizeInfo = when {
            bak.sizeInfo == null -> cur.sizeInfo
            cur.sizeInfo == null -> { addedChildren++; bak.sizeInfo }
            else -> mergeSizeInfo(cur.sizeInfo, bak.sizeInfo)   // одна запись на контакт: id ТЕКУЩЕЙ строки остаётся
        }
        val link: Pair<String?, String?> = when {
            // та же связь (тот же id-кэш): LOOKUP_KEY можно добавить, но не подменить
            cur.deviceContactId != null && cur.deviceContactId == bak.deviceContactId ->
                cur.deviceContactId to field(e, id, "deviceLookupKey", cur.deviceLookupKey, bak.deviceLookupKey)
            else -> {
                val absent: (Pair<String?, String?>) -> Boolean = { it.first == null && it.second == null }
                field(e, id, "deviceLink", cur.deviceContactId to cur.deviceLookupKey, bak.deviceContactId to bak.deviceLookupKey, absent, absent)
            }
        }
        return cur.copy(
            firstName = field(e, id, "firstName", cur.firstName, bak.firstName),
            lastName = field(e, id, "lastName", cur.lastName, bak.lastName),
            middleName = field(e, id, "middleName", cur.middleName, bak.middleName),
            nickname = field(e, id, "nickname", cur.nickname, bak.nickname),
            namePrefix = field(e, id, "namePrefix", cur.namePrefix, bak.namePrefix),
            nameSuffix = field(e, id, "nameSuffix", cur.nameSuffix, bak.nameSuffix),
            phoneticFirstName = field(e, id, "phoneticFirstName", cur.phoneticFirstName, bak.phoneticFirstName),
            phoneticMiddleName = field(e, id, "phoneticMiddleName", cur.phoneticMiddleName, bak.phoneticMiddleName),
            phoneticLastName = field(e, id, "phoneticLastName", cur.phoneticLastName, bak.phoneticLastName),
            photoUri = field(e, id, "photoUri", cur.photoUri, bak.photoUri),
            relationshipType = field(e, id, "relationshipType", cur.relationshipType, bak.relationshipType),
            customRelationshipType = field(e, id, "customRelationshipType", cur.customRelationshipType, bak.customRelationshipType),
            secondaryRelationshipTypes = field(e, id, "secondaryRelationshipTypes", cur.secondaryRelationshipTypes, bak.secondaryRelationshipTypes),
            connectionLevel = field(e, id, "connectionLevel", cur.connectionLevel, bak.connectionLevel),
            importanceLevel = field(e, id, "importanceLevel", cur.importanceLevel, bak.importanceLevel),
            socialRole = field(e, id, "socialRole", cur.socialRole, bak.socialRole),
            communicationRhythm = field(e, id, "communicationRhythm", cur.communicationRhythm, bak.communicationRhythm),
            customRhythmDays = field(e, id, "customRhythmDays", cur.customRhythmDays, bak.customRhythmDays),
            // ACTIVE — значение по умолчанию: «нет данных» в backup, а не осмысленное значение
            contactStatus = field(e, id, "contactStatus", cur.contactStatus, bak.contactStatus,
                bakAbsent = { it == ContactStatus.ACTIVE }, curAbsent = { false }),
            lastContactDate = laterIso(cur.lastContactDate, bak.lastContactDate),   // монотонно: «последний»
            nextStep = field(e, id, "nextStep", cur.nextStep, bak.nextStep),
            familyNote = field(e, id, "familyNote", cur.familyNote, bak.familyNote),
            profession = field(e, id, "profession", cur.profession, bak.profession),
            // ФИКС (Data Safety Residual Audit, TAG-2 — Legacy Tags Compatibility & Phone Sync Safety
            // Pack, 2026-09-23): mergeContact() выполняется ТОЛЬКО в MERGE-режиме (существующая
            // непустая БД, curContacts[id] уже найден) — generic scalar field() трактовал легаси-поле
            // как обычный текст, безусловно заполняя пустое current из backup; следующий вызов
            // reconcileLegacyTagsIntoManaged() затем создавал managed membership БЕЗ единого выбора
            // пользователя (Restore Preview для непустой БД — единственный canonical путь выбора).
            // current.tags остаётся как есть — никогда не заполняется из backup здесь.
            tags = cur.tags,
            canHelpWith = field(e, id, "canHelpWith", cur.canHelpWith, bak.canHelpWith),
            iCanHelpWith = field(e, id, "iCanHelpWith", cur.iCanHelpWith, bak.iCanHelpWith),
            talkingPoints = field(e, id, "talkingPoints", cur.talkingPoints, bak.talkingPoints),
            meetContext = field(e, id, "meetContext", cur.meetContext, bak.meetContext),
            meetDate = field(e, id, "meetDate", cur.meetDate, bak.meetDate),
            // Связь с телефонной книгой — ПАРА (id-кэш + LOOKUP_KEY): не смешиваем половинки разных связей.
            deviceContactId = link.first,
            deviceLookupKey = link.second,
            companyRelations = mergeChildren(cur.companyRelations, bak.companyRelations, { it.id }, this::mergeCompanyRelation),
            phones = mergeChildren(cur.phones, bak.phones, { it.id }, this::mergePhoneByValue, this::phoneMatchKey),
            emails = mergeChildren(cur.emails, bak.emails, { it.id }, this::mergeEmailByValue, this::emailMatchKey),
            messengers = mergeChildren(cur.messengers, bak.messengers, { it.id }, this::mergeMessenger),
            addresses = mergeChildren(cur.addresses, bak.addresses, { it.id }, this::mergeAddress),
            sizeInfo = sizeInfo,
            personalDetails = mergeChildren(cur.personalDetails, bak.personalDetails, { it.id }, this::mergePersonalDetail),
            // createdAt: текущая дата создания остаётся; updatedAt выставляет вызывающий, если что-то изменилось
        )
    }

    internal fun mergeCompany(cur: Company, bak: Company): Company {
        val e = "company"; val id = cur.id
        return cur.copy(
            name = field(e, id, "name", cur.name, bak.name),
            logoUri = field(e, id, "logoUri", cur.logoUri, bak.logoUri),
            industry = field(e, id, "industry", cur.industry, bak.industry),
            description = field(e, id, "description", cur.description, bak.description),
            website = field(e, id, "website", cur.website, bak.website),
            phones = mergeChildren(cur.phones, bak.phones, { it.id }, this::mergePhone),
            emails = mergeChildren(cur.emails, bak.emails, { it.id }, this::mergeEmail),
            addresses = mergeChildren(cur.addresses, bak.addresses, { it.id }, this::mergeAddress),
        )
    }

    internal fun mergeCalendarItem(cur: CalendarItem, bak: CalendarItem): CalendarItem {
        val e = "calendarItem"; val id = cur.id
        // Пара postpone (from/to) — единое целое: половинки не смешиваем.
        val pairAbsent: (Pair<String?, String?>) -> Boolean = { it.first == null || it.second == null }
        val postponed = field(e, id, "postponed", cur.postponedFromOccurrenceDate to cur.postponedToDate,
            bak.postponedFromOccurrenceDate to bak.postponedToDate, pairAbsent, pairAbsent)
        return cur.copy(
            title = field(e, id, "title", cur.title, bak.title),
            description = field(e, id, "description", cur.description, bak.description),
            type = field(e, id, "type", cur.type, bak.type),
            startDate = field(e, id, "startDate", cur.startDate, bak.startDate),
            startTime = field(e, id, "startTime", cur.startTime, bak.startTime),
            endDate = field(e, id, "endDate", cur.endDate, bak.endDate),
            endTime = field(e, id, "endTime", cur.endTime, bak.endTime),
            isAllDay = field(e, id, "isAllDay", cur.isAllDay, bak.isAllDay),
            status = field(e, id, "status", cur.status, bak.status),
            importance = field(e, id, "importance", cur.importance, bak.importance),
            colorKey = field(e, id, "colorKey", cur.colorKey, bak.colorKey),
            recurrenceRule = field(e, id, "recurrenceRule", cur.recurrenceRule, bak.recurrenceRule),
            lastCompletedOccurrenceDate = laterIso(cur.lastCompletedOccurrenceDate, bak.lastCompletedOccurrenceDate),
            recurrenceExplicitlySet = field(e, id, "recurrenceExplicitlySet", cur.recurrenceExplicitlySet, bak.recurrenceExplicitlySet, flagAbsent, flagAbsent),
            postponedFromOccurrenceDate = postponed.first,
            postponedToDate = postponed.second,
            links = mergeValueList(e, id, "links", cur.links, bak.links) { it.targetType to it.targetId },
            reminders = mergeValueList(e, id, "reminders", cur.reminders, bak.reminders) {
                listOf(it.reminderType, it.offsetValue, it.offsetUnit, it.exactDateTime)
            },
        )
    }

    // ── план целиком ──────────────────────────────────────────

    fun merge(current: PersistedState, plan: RestorePlan, authorization: RestoreAuthorization = RestoreAuthorization.SafeMerge): Pair<RestoreWrites, RestoreResult> {
        // EXPLICIT RECOVERY PACK: «пусто» для FRESH обязано включать tombstones — иначе установка, где ВСЕ записи
        // были удалены (а не «никогда не заводились»), выглядела бы неотличимой от клин-инсталла: FRESH
        // безусловно восстановил бы всё из backup, полностью обходя намеренно удалённое (см. shouldSkipAsDeleted
        // ниже — она же сама явно бы это разрешила, будь mode == FRESH). История удаления делает установку
        // «не пустой» в этом смысле, даже если ни одной живой записи не осталось.
        val mode = if (current.contacts.isEmpty() && current.companies.isEmpty() && current.calendarItems.isEmpty() &&
            current.notes.isEmpty() && current.gifts.isEmpty() && current.tombstones.isEmpty()) RestoreMode.FRESH else RestoreMode.MERGE

        // EXPLICIT RECOVERY PACK: чьё воскрешение явно авторизовано ЭТИМ restore (дополнено зависимостями —
        // родитель tombstoned ребёнка добавляется автоматически, чтобы не создать сироту). FRESH tombstones
        // не смотрит вовсе (клин-инсталл ничего не защищает) — см. shouldSkipAsDeleted ниже.
        val localTombstoneKeys = current.tombstones.mapTo(HashSet()) { TombstoneKey(it.entityType, it.entityId) }
        val explicitKeys: Set<TombstoneKey> = when (authorization) {
            RestoreAuthorization.SafeMerge -> emptySet()
            is RestoreAuthorization.Selective -> expandForDependencies(authorization.keys, plan, localTombstoneKeys)
            RestoreAuthorization.RestoreAllDeleted ->
                expandForDependencies(recoverableCandidates(current, plan).mapTo(HashSet()) { it.key }, plan, localTombstoneKeys)
        }
        val tombstonesToClear = HashSet<TombstoneKey>()
        var skippedTombstoned = 0

        /**
         * true — backup-only запись (в current её нет) остаётся удалённой, не добавляется.
         * false — можно добавить (не tombstoned, либо явно авторизовано — тогда СОБСТВЕННЫЙ tombstone снимается).
         * [impliedParent] — для Note/Gift: их владелец (Contact/Company) МОГ быть удалён каскадом (deleteContact/
         * deleteCompany), тогда у самой заметки/подарка собственного tombstone нет — но восстанавливать её без
         * восстановления родителя означает создать сироту. Родитель tombstoned -> заметка тоже под защитой
         * SAFE_MERGE; explicitKeys уже содержит родителя, если заметка была явно выбрана (см. expandForDependencies).
         */
        fun shouldSkipAsDeleted(type: TombstoneEntityType, id: String, impliedParent: TombstoneKey? = null): Boolean {
            if (mode == RestoreMode.FRESH) return false
            val key = TombstoneKey(type, id)
            val selfTombstoned = key in localTombstoneKeys
            val parentTombstoned = impliedParent != null && impliedParent in localTombstoneKeys
            if (!selfTombstoned && !parentTombstoned) return false
            // Явно авторизовано: либо сама запись выбрана, либо восстанавливается её РОДИТЕЛЬ целиком —
            // тогда каскадный ребёнок (без собственного tombstone — например заметка удалённого вместе с
            // контактом контакта) едет вместе с ним автоматически, а не остаётся сиротски недоступной для выбора.
            if (key in explicitKeys || (impliedParent != null && impliedParent in explicitKeys)) {
                if (selfTombstoned) tombstonesToClear += key
                return false
            }
            skippedTombstoned++
            return true
        }

        var addedContacts = 0; var addedCompanies = 0; var addedCalendar = 0; var addedNotes = 0; var addedGifts = 0

        val curCompanies = current.companies.associateBy { it.id }
        val companiesOut = ArrayList<Company>()
        for (b in plan.companies) {
            val c = curCompanies[b.id]
            if (c == null) { if (!shouldSkipAsDeleted(TombstoneEntityType.COMPANY, b.id)) { companiesOut += b; addedCompanies++ } }
            else mergeCompany(c, b).let { m -> if (m != c) companiesOut += m.copy(updatedAt = now) }
        }

        val curContacts = current.contacts.associateBy { it.id }
        val contactsOut = ArrayList<Contact>()
        for (b in plan.contacts) {
            val c = curContacts[b.id]
            if (c == null) { if (!shouldSkipAsDeleted(TombstoneEntityType.CONTACT, b.id)) { contactsOut += b; addedContacts++ } }
            else mergeContact(c, b).let { m -> if (m != c) contactsOut += m.copy(updatedAt = now) }
        }

        // Заметки и подарки приходят вложенными в Contact и плоскими списками: объединяем по id
        // (плоский список побеждает вложенный — как раньше), затем сливаем с текущими по id.
        val curNotes = current.notes.associateBy { it.id }
        val notesOut = ArrayList<Note>()
        for (b in (plan.contacts.flatMap { it.notes } + plan.notes).associateBy { it.id }.values) {
            val c = curNotes[b.id]
            if (c == null) {
                val parent = b.contactId?.let { TombstoneKey(TombstoneEntityType.CONTACT, it) }
                    ?: b.companyId?.let { TombstoneKey(TombstoneEntityType.COMPANY, it) }
                if (!shouldSkipAsDeleted(TombstoneEntityType.NOTE, b.id, parent)) { notesOut += b; addedNotes++ }
            }
            else mergeNote(c, b).let { m -> if (m != c) notesOut += m.copy(updatedAt = now) }
        }
        val curGifts = current.gifts.associateBy { it.id }
        val giftsOut = ArrayList<GiftIdea>()
        for (b in (plan.contacts.flatMap { it.gifts } + plan.gifts).associateBy { it.id }.values) {
            val c = curGifts[b.id]
            if (c == null) {
                val parent = TombstoneKey(TombstoneEntityType.CONTACT, b.contactId)
                if (!shouldSkipAsDeleted(TombstoneEntityType.GIFT, b.id, parent)) { giftsOut += b; addedGifts++ }
            }
            else mergeGift(c, b).let { m -> if (m != c) giftsOut += m }
        }

        val curCalendar = current.calendarItems.associateBy { it.id }
        val calendarOut = ArrayList<CalendarItem>()
        for (b in plan.calendarItems) {
            val c = curCalendar[b.id]
            if (c == null) { if (!shouldSkipAsDeleted(TombstoneEntityType.CALENDAR_ITEM, b.id)) { calendarOut += b; addedCalendar++ } }
            else mergeCalendarItem(c, b).let { m -> if (m != c) calendarOut += m.copy(updatedAt = now) }
        }

        val curRelations = current.contactRelations.associateBy { it.id }
        val relationsOut = ArrayList<ContactRelation>()
        for (b in plan.contactRelations) {
            val c = curRelations[b.id]
            if (c == null) { relationsOut += b; addedChildren++ }
            else mergeContactRelation(c, b).let { m -> if (m != c) relationsOut += m }
        }

        val curGroups = current.groups.associateBy { it.id }
        val groupsOut = ArrayList<ContactGroup>()
        for (b in plan.groups) {
            val c = curGroups[b.id]
            if (c == null) { groupsOut += b; addedChildren++ }
            else mergeGroup(c, b).let { m -> if (m != c) groupsOut += m.copy(updatedAt = now) }
        }
        // Членство: по id И по паре (группа, контакт) — join-строка не имеет собственных данных.
        val existingGroupMemberIds = current.groupMembers.map { it.id }.toHashSet()
        val existingGroupPairs = current.groupMembers.map { it.groupId to it.contactId }.toHashSet()
        val groupMembersOut = plan.groupMembers.filter { m ->
            m.id !in existingGroupMemberIds && existingGroupPairs.add(m.groupId to m.contactId)
        }.also { addedChildren += it.size }

        // ── Теги: логическое имя, а не id (Batch 5C/5): тот же тег «Электрик» под двумя id — один тег ──
        val existingTagIdByLowerName = current.tags.associate { it.name.trim().lowercase() to it.id }
        val existingTagIdBySeedKey = current.tags.mapNotNull { t -> seedTagKey(t.name)?.let { key -> key to t.id } }.toMap()
        val existingTagsById = current.tags.associateBy { it.id }
        val tagIdRemap = HashMap<String, String>()
        val tagsOut = ArrayList<Tag>()
        for (backupTag in plan.tags) {
            val existingId = existingTagIdByLowerName[backupTag.name.trim().lowercase()]
                ?: seedTagKey(backupTag.name)?.let { existingTagIdBySeedKey[it] }
            if (existingId != null) {
                tagIdRemap[backupTag.id] = existingId
                val existing = existingTagsById.getValue(existingId)
                // категория: живое значение никогда не перезаписывается молча
                if (existing.category.isNullOrBlank() && !backupTag.category.isNullOrBlank()) {
                    tagsOut += existing.copy(category = backupTag.category); filled++
                } else if (!existing.category.isNullOrBlank() && !backupTag.category.isNullOrBlank() && existing.category != backupTag.category) {
                    conflict("tag", existing.id, "category", existing.category, backupTag.category)
                }
            } else {
                tagIdRemap[backupTag.id] = backupTag.id
                tagsOut += backupTag; addedChildren++
            }
        }
        val existingTagMemberIds = current.tagMembers.map { it.id }.toHashSet()
        val seenPairs = current.tagMembers.map { it.tagId to it.contactId }.toHashSet()
        val tagMembersOut = plan.tagMembers.mapNotNull { m ->
            if (m.id in existingTagMemberIds) return@mapNotNull null
            val effectiveTagId = tagIdRemap[m.tagId] ?: m.tagId
            if (!seenPairs.add(effectiveTagId to m.contactId)) return@mapNotNull null
            if (effectiveTagId == m.tagId) m else m.copy(tagId = effectiveTagId)
        }.also { addedChildren += it.size }

        // Новые tombstone из backup (деление истории продолжает жить дальше), КРОМЕ тех, что этот restore явно
        // снял (recovered): локальные, не упомянутые в backup, не трогаются вовсе — не нуждаются в перезаписи.
        val tombstonesOut = plan.tombstones.filter { TombstoneKey(it.entityType, it.entityId) !in tombstonesToClear }

        val writes = RestoreWrites(
            companies = companiesOut, contacts = contactsOut, calendarItems = calendarOut,
            contactRelations = relationsOut, groups = groupsOut, groupMembers = groupMembersOut,
            tags = tagsOut, tagMembers = tagMembersOut, notes = notesOut, gifts = giftsOut,
            tombstonesToUpsert = tombstonesOut, tombstonesToClear = tombstonesToClear.toList(),
        )
        val result = RestoreResult(
            mode = mode, contactsInBackup = plan.contacts.size,
            addedContacts = addedContacts, addedCompanies = addedCompanies, addedCalendarItems = addedCalendar,
            addedNotes = addedNotes, addedGifts = addedGifts, addedChildren = addedChildren,
            filledFields = filled, conflictCount = conflictTotal, conflicts = conflicts.toList(),
            skippedTombstoned = skippedTombstoned, recoveredFromTombstone = tombstonesToClear.size,
        )
        return writes to result
    }
}

// ── EXPLICIT RECOVERY PACK: чистые функции вне класса — не нуждаются в seedTagKey/now, переиспользуются
//    и AppStateStore (авторизация restore), и будущим UI (список для выбора) ──────────────────────────

/** Родитель ребёнка (Note/Gift) В ДАННЫХ BACKUP — для проверки «родитель тоже tombstoned» и авто-расширения
 *  выбора. null — сущность не ребёнок (Contact/Company/CalendarItem) либо ребёнка нет в этом backup вовсе. */
internal fun parentKeyInBackup(key: TombstoneKey, plan: RestorePlan): TombstoneKey? = when (key.type) {
    TombstoneEntityType.NOTE -> {
        val n = plan.notes.find { it.id == key.id } ?: plan.contacts.asSequence().flatMap { it.notes }.find { it.id == key.id }
        n?.contactId?.let { TombstoneKey(TombstoneEntityType.CONTACT, it) }
            ?: n?.companyId?.let { TombstoneKey(TombstoneEntityType.COMPANY, it) }
    }
    TombstoneEntityType.GIFT -> {
        val g = plan.gifts.find { it.id == key.id } ?: plan.contacts.asSequence().flatMap { it.gifts }.find { it.id == key.id }
        g?.let { TombstoneKey(TombstoneEntityType.CONTACT, it.contactId) }
    }
    else -> null
}

/**
 * Замыкание выбора зависимостями (requirement пакета: «для удалённого ребёнка с удалённым родителем — объяснить
 * зависимость и восстановить родителя с авторизацией пользователя, либо не выбирать ребёнка; сирот не создавать
 * никогда»). Здесь — первый вариант: родитель добавляется в выбор автоматически, тем же явным действием
 * пользователя (весь Restore целиком), а не тихим предположением где-то ещё. Fixed-point-цикл — граф в этой
 * модели глубиной 1 (Note/Gift -> Contact/Company), но реализация не предполагает эту глубину жёстко.
 */
internal fun expandForDependencies(keys: Set<TombstoneKey>, plan: RestorePlan, localTombstoneKeys: Set<TombstoneKey>): Set<TombstoneKey> {
    val result = keys.toMutableSet()
    var changed = true
    while (changed) {
        changed = false
        for (k in result.toList()) {
            val parent = parentKeyInBackup(k, plan) ?: continue
            if (parent in localTombstoneKeys && result.add(parent)) changed = true
        }
    }
    return result
}

/**
 * Реально восстановимое: tombstoned ЛОКАЛЬНО (текущая установка) И присутствует в ДАННЫХ backup (иначе
 * восстанавливать нечего — сама запись нигде не сохранилась). Основа и для SELECTIVE (что показать пользователю
 * на выбор), и для RESTORE_ALL_DELETED (весь список — это и есть авторизация).
 */
fun recoverableCandidates(current: PersistedState, plan: RestorePlan): List<RecoverableRecord> {
    val backupContacts = plan.contacts.associateBy { it.id }
    val backupCompanies = plan.companies.associateBy { it.id }
    val backupNotes = (plan.notes + plan.contacts.flatMap { it.notes }).associateBy { it.id }
    val backupGifts = (plan.gifts + plan.contacts.flatMap { it.gifts }).associateBy { it.id }
    val backupCalendar = plan.calendarItems.associateBy { it.id }
    val localTombstoneKeys = current.tombstones.mapTo(HashSet()) { TombstoneKey(it.entityType, it.entityId) }
    return current.tombstones.mapNotNull { t ->
        val key = TombstoneKey(t.entityType, t.entityId)
        val label = when (t.entityType) {
            TombstoneEntityType.CONTACT -> backupContacts[t.entityId]?.let { "${it.firstName} ${it.lastName}".trim() }
            TombstoneEntityType.COMPANY -> backupCompanies[t.entityId]?.name
            TombstoneEntityType.NOTE -> backupNotes[t.entityId]?.text?.take(60)
            TombstoneEntityType.GIFT -> backupGifts[t.entityId]?.title
            TombstoneEntityType.CALENDAR_ITEM -> backupCalendar[t.entityId]?.title
            // RESTORE BATCH DEPENDENCIES & STANDALONE GROUPS MICROFIX (2026-09-24): Group никогда не
            // получает tombstone (осознанно, requirement 16 исходного Restore Preview Pack) — этот
            // case недостижим в реальных данных, добавлен только для исчерпывающего when.
            TombstoneEntityType.GROUP -> null
        } ?: return@mapNotNull null
        RecoverableRecord(key, label, t.deletedAt, parentKeyInBackup(key, plan)?.takeIf { it in localTombstoneKeys })
    }
}
