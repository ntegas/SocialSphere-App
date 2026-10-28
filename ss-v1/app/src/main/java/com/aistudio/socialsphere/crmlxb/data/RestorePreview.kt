package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*

/** PART C (Legacy Tags Compatibility & Phone Sync Safety Pack): createdAt/updatedAt синтетического Tag,
 *  собранного из легаси Contact.tags (реальный момент создания неизвестен — buildApplyPlan умышленно
 *  чистая функция, без чтения часов). */
private const val LEGACY_TAG_TIMESTAMP = "1970-01-01T00:00:00"

/**
 * RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22).
 *
 * Продуктовое решение пакета: backup сам ничего не решает. Открытие backup — READ-ONLY анализ:
 * `CURRENT + BACKUP -> DIFFERENCE ANALYSIS (Same/Missing/Conflict) -> USER SELECTION -> PREVIEW -> ATOMIC APPLY`.
 * Ни одной мутации Room до явного подтверждения (см. [RestoreDiffEngine.analyze] — чистая функция без БД,
 * и [RestoreDiffEngine.buildApplyPlan] — тоже чистая; сам apply делает AppStateStore.applySelectiveRestore).
 *
 * Модель НЕ заменяет [RestoreMerger]/[RestoreAuthorization] (Explicit Recovery Pack) — та инфраструктура
 * остаётся для FRESH restore (клин-инсталл: быстрый путь «восстановить всё» без ручного выбора сотен строк,
 * requirement 27) и для программных вызывающих. Для НЕПУСТОЙ базы — этот, более гранулярный путь: раньше SAFE
 * MERGE молча ДОБАВЛЯЛ отсутствующие дочерние строки/поля без выбора пользователя; теперь на непустой базе
 * абсолютно ничего не применяется без явного выбора — даже «просто добавить недостающее».
 *
 * Матчинг — по устойчивому persisted id backup (та же дисциплина, что у RestoreMerger): НЕ по имени.
 * Tombstone (Explicit Recovery Pack) здесь — уже не абсолютный запрет, а КОНТЕКСТ: tombstoned-и-есть-в-backup
 * запись показывается как MISSING с пометкой [RestoreDifference.previouslyDeleted] — восстанавливать или нет,
 * решает пользователь (requirement 15/19). Явно НЕ расширяем tombstones на phones/emails/addresses/reminders/
 * links/tag membership/group membership (requirement 16) — для них backup — просто источник недостающей
 * информации.
 */

// ── типы разницы ────────────────────────────────────────────────────────────────────────────

enum class RestoreDifferenceType { MISSING, CONFLICT }

/** Что именно за разница — определяет и как её показывать, и как ГРАНУЛЯРНО применять именно её (не всю
 *  сущность целиком): добавить один дочерний ряд, применить одно скалярное поле, восстановить сущность целиком. */
enum class RestoreFieldKind {
    WHOLE_CONTACT, WHOLE_COMPANY, WHOLE_CALENDAR_ITEM, WHOLE_NOTE, WHOLE_GIFT,
    PHONE, EMAIL, MESSENGER, ADDRESS, PERSONAL_DETAIL, COMPANY_RELATION, SIZE_INFO,
    SCALAR,
    /** TAGS BACKUP RECOVERY PACK (2026-09-23): назначение управляемого тега контакту (ContactTagMember).
     *  Всегда MISSING (у тега нет «конфликта значения» — он либо назначен, либо нет). [entityId] — id
     *  backup-строки ContactTagMember (не id самого Tag — тег может уже существовать в current). */
    TAG,
    /** RESTORE PREVIEW REMAINING DATA COVERAGE (2026-09-24): связь между двумя КОНТАКТАМИ (ContactRelation).
     *  Владеет differences firstContactId-стороны (requirement 5 — «учитывать обе стороны связи»: показывается
     *  ТОЛЬКО когда ОБЕ стороны уже существуют в current — иначе пришлось бы либо молча создавать недостающего
     *  человека, либо городить межгрупповую dependency-UI, чего явно просили не делать). [entityId] — id
     *  backup-строки ContactRelation. */
    RELATION,
    /** Членство контакта в группе (ContactGroupMember) — тот же принцип, что TAG: сама Group — dependency,
     *  разрешается в buildApplyPlan по id-с-фолбэком-на-имя (не плодит второй «Клиенты» с другим id).
     *  [entityId] — id backup-строки ContactGroupMember. */
    GROUP_MEMBERSHIP,
    /** Недостающее напоминание СУЩЕСТВУЮЩЕГО CalendarItem (ReminderRule) — НЕ то же самое, что напоминания
     *  whole-missing события (те едут атомарно через addCalendarItemDb). [entityId] — id backup ReminderRule. */
    CALENDAR_REMINDER,
    /** Недостающая связь (CalendarItemLink) СУЩЕСТВУЮЩЕГО CalendarItem — только если её target (контакт/
     *  компания и т.п.) уже существует в current (requirement 5 — без orphan-ссылок). [entityId] — id backup
     *  CalendarItemLink. */
    CALENDAR_LINK,
}

/** Скалярное текстовое поле Contact, за которым признана продуктовая ценность conflict-сравнения (requirement 14).
 *  НЕ все ~30 полей Contact — остальные меняются штатным 3-way merge редактора (Data Persistence Pack #2),
 *  здесь только те, что реально теряются при restore-конфликте и нет другого способа их увидеть. */
enum class ContactScalarField(val get: (Contact) -> String?, val with: (Contact, String?) -> Contact) {
    PROFESSION({ it.profession }, { c, v -> c.copy(profession = v) }),
    FAMILY_NOTE({ it.familyNote }, { c, v -> c.copy(familyNote = v) }),
    NEXT_STEP({ it.nextStep }, { c, v -> c.copy(nextStep = v) }),
    CAN_HELP_WITH({ it.canHelpWith }, { c, v -> c.copy(canHelpWith = v) }),
    I_CAN_HELP_WITH({ it.iCanHelpWith }, { c, v -> c.copy(iCanHelpWith = v) }),
    TALKING_POINTS({ it.talkingPoints }, { c, v -> c.copy(talkingPoints = v) }),
    MEET_CONTEXT({ it.meetContext }, { c, v -> c.copy(meetContext = v) }),
    CUSTOM_RELATIONSHIP_TYPE({ it.customRelationshipType }, { c, v -> c.copy(customRelationshipType = v) }),
}

enum class CompanyScalarField(val get: (Company) -> String?, val with: (Company, String?) -> Company) {
    DESCRIPTION({ it.description }, { c, v -> c.copy(description = v) }),
    WEBSITE({ it.website }, { c, v -> c.copy(website = v) }),
}

enum class CalendarScalarField(val get: (CalendarItem) -> String?, val with: (CalendarItem, String?) -> CalendarItem) {
    TITLE({ it.title }, { c, v -> c.copy(title = v ?: c.title) }),
    DESCRIPTION({ it.description }, { c, v -> c.copy(description = v) }),
}

/**
 * Одна атомарная строка различия. [key] — стабильный уникальный идентификатор внутри ОДНОГО preview (Compose
 * key и ключ selection-набора). Для дочерних рядов (PHONE/EMAIL/…) [entityId] — id самой дочерней строки backup;
 * для SCALAR — имя поля (через [scalarField]); для WHOLE_* — id самой сущности (совпадает с [ownerId]).
 */
data class RestoreDifference(
    val key: String,
    val ownerId: String,
    val ownerDisplayName: String,
    val ownerType: TombstoneEntityType,
    val entityType: TombstoneEntityType,
    val entityId: String,
    val fieldKind: RestoreFieldKind,
    val scalarField: Enum<*>? = null,
    val currentValue: String?,
    val backupValue: String?,
    val differenceType: RestoreDifferenceType,
    val previouslyDeleted: Boolean = false,
)

/** Группа UI — один человек/компания/событие (requirement 5). */
data class RestorePreviewGroup(
    val ownerId: String,
    val ownerType: TombstoneEntityType,
    val ownerDisplayName: String,
    /** Владельца сейчас нет в Room вовсе (MISSING) — «Restore contact»/«Restore company»/«Restore event». */
    val wholeEntityMissing: Boolean,
    /** Владелец tombstoned (Explicit Recovery Pack) — «Previously deleted» (requirement 15/19). */
    val previouslyDeleted: Boolean,
    /** Для СУЩЕСТВУЮЩЕГО владельца — независимо выбираемые разницы (missing children + conflicts).
     *  Для ОТСУТСТВУЮЩЕГО владельца — пусто: его дети показываются только в [childSummaryLines] и приезжают
     *  атомарно вместе с восстановлением владельца (requirement 11/18 — без orphan, без отдельных чекбоксов). */
    val differences: List<RestoreDifference>,
    /** Информационные строки «+ Phone: +30…», «+ Note: …» для ОТСУТСТВУЮЩЕГО владельца (requirement 5/11). */
    val childSummaryLines: List<String> = emptyList(),
) {
    /** Единственный ключ выбора для missing-владельца целиком ("Restore contact"/"Restore company"/"Restore event"). */
    val wholeKey: String? get() = if (wholeEntityMissing) "${ownerType}|${ownerId}|WHOLE" else null

    internal val searchableText: String by lazy {
        (listOf(ownerDisplayName) + differences.flatMap { listOfNotNull(it.currentValue, it.backupValue) } + childSummaryLines)
            .joinToString(" ").lowercase()
    }
}

data class RestorePreview(val groups: List<RestorePreviewGroup>, internal val plan: RestorePlan) {
    /** Все выбираемые ключи этого preview (для «Select all missing» / валидации). */
    val allMissingKeys: Set<String> by lazy {
        groups.flatMap { g ->
            (if (g.wholeEntityMissing) listOfNotNull(g.wholeKey) else emptyList()) +
                g.differences.filter { it.differenceType == RestoreDifferenceType.MISSING }.map { it.key }
        }.toSet()
    }
}

data class RestorePreviewSummary(
    val additions: Int,
    val replacements: Int,
    val entitiesRestored: Int,
    val previouslyDeletedRestored: Int,
)

// ── UI-фильтры (чистые enum, не завязаны на Compose) ─────────────────────────────────────────

enum class RestorePreviewFilter { ALL, MISSING, CONFLICTS, CONTACTS, COMPANIES, NOTES, GIFTS, CALENDAR, TAGS }

fun RestorePreview.filtered(filter: RestorePreviewFilter, query: String): List<RestorePreviewGroup> {
    val q = query.trim().lowercase()
    return groups.filter { g ->
        val matchesQuery = q.isEmpty() || g.searchableText.contains(q)
        val matchesFilter = when (filter) {
            RestorePreviewFilter.ALL -> true
            RestorePreviewFilter.MISSING -> g.wholeEntityMissing || g.differences.any { it.differenceType == RestoreDifferenceType.MISSING }
            RestorePreviewFilter.CONFLICTS -> g.differences.any { it.differenceType == RestoreDifferenceType.CONFLICT }
            RestorePreviewFilter.CONTACTS -> g.ownerType == TombstoneEntityType.CONTACT
            RestorePreviewFilter.COMPANIES -> g.ownerType == TombstoneEntityType.COMPANY
            RestorePreviewFilter.CALENDAR -> g.ownerType == TombstoneEntityType.CALENDAR_ITEM
            RestorePreviewFilter.NOTES -> g.wholeEntityMissing.not() && g.differences.any { it.fieldKind == RestoreFieldKind.WHOLE_NOTE }
            RestorePreviewFilter.GIFTS -> g.wholeEntityMissing.not() && g.differences.any { it.fieldKind == RestoreFieldKind.WHOLE_GIFT }
            // Тот же паттерн, что NOTES/GIFTS: целиком отсутствующий владелец фильтруется через MISSING,
            // не через TAGS (у него нет отдельных differences — теги едут атомарно вместе с восстановлением).
            RestorePreviewFilter.TAGS -> g.wholeEntityMissing.not() && g.differences.any { it.fieldKind == RestoreFieldKind.TAG }
        }
        matchesQuery && matchesFilter
    }
}

// ── движок анализа (чистый, без БД) ──────────────────────────────────────────────────────────

object RestoreDiffEngine {

    private fun blank(s: String?) = s.isNullOrBlank()

    /** MISSING/CONFLICT/SAME для одного текстового скалярного поля — SAME не порождает разницу вовсе. */
    private fun <T> scalarDiff(
        owner: T, ownerId: String, ownerName: String, ownerType: TombstoneEntityType,
        entityType: TombstoneEntityType, field: Enum<*>, cur: String?, bak: String?,
    ): RestoreDifference? {
        if (cur == bak) return null
        val type = if (blank(cur) && !blank(bak)) RestoreDifferenceType.MISSING
            else if (!blank(cur) && !blank(bak)) RestoreDifferenceType.CONFLICT
            else return null   // backup пусто — нечего предлагать
        return RestoreDifference(
            key = "$entityType|$ownerId|SCALAR|$field", ownerId = ownerId, ownerDisplayName = ownerName, ownerType = ownerType,
            entityType = entityType, entityId = ownerId, fieldKind = RestoreFieldKind.SCALAR, scalarField = field,
            currentValue = cur, backupValue = bak, differenceType = type,
        )
    }

    /** Дочерние ряды по устойчивому id: только backup-only -> MISSING (requirement 13). Совпадающие/current-only
     *  разницы не порождают — «SAME» не показывается (requirement 2), current-only — не разница вовсе (ничего не
     *  теряется, backup о нём просто не знает). */
    private fun <T> missingChildren(
        cur: List<T>, bak: List<T>, id: (T) -> String, label: (T) -> String,
        ownerId: String, ownerName: String, ownerType: TombstoneEntityType, kind: RestoreFieldKind,
        valueKey: ((T) -> String)? = null,
    ): List<RestoreDifference> {
        val curIds = cur.mapTo(HashSet(), id)
        // Телефон/email с другим id, но тем же значением — это НЕ «отсутствует в текущем» (id
        // перегенерируются импортом/слиянием), иначе восстановление добавило бы дубль номера.
        val curValues = valueKey?.let { vk -> cur.mapTo(HashSet(), vk) }
        return bak.filter { id(it) !in curIds && (valueKey == null || valueKey(it) !in curValues.orEmpty()) }.map { b ->
            RestoreDifference(
                key = "$ownerType|$ownerId|$kind|${id(b)}", ownerId = ownerId, ownerDisplayName = ownerName, ownerType = ownerType,
                entityType = ownerType, entityId = id(b), fieldKind = kind, currentValue = null, backupValue = label(b),
                differenceType = RestoreDifferenceType.MISSING,
            )
        }
    }

    private fun phoneLabel(p: ContactPhone) = p.number
    private fun emailLabel(e: ContactEmail) = e.email
    private fun messengerLabel(m: Messenger) = "${m.type}: ${m.value}"
    private fun addressLabel(a: Address) = a.addressLine
    private fun detailLabel(d: PersonalDetail) = "${d.category}: ${d.value}"
    private fun relationLabel(r: ContactCompanyRelation, companyName: (String) -> String?) =
        companyName(r.companyId) ?: r.position ?: r.companyId
    private fun sizeLabel(s: SizeInfo) = listOfNotNull(s.clothingSize, s.shoeSize, s.ringSize, s.other).joinToString(", ")
    private fun relationLabel(r: ContactRelation, otherName: String) =
        listOfNotNull(otherName, r.firstRole.takeIf { it.isNotBlank() }, r.note?.takeIf { it.isNotBlank() }).joinToString(" · ")
    private fun reminderLabel(r: ReminderRule) =
        r.exactDateTime ?: listOfNotNull(r.reminderType.name, r.offsetValue?.toString(), r.offsetUnit?.name).joinToString(" ")
    private fun linkLabel(l: CalendarItemLink, contactName: (String) -> String?, companyName: (String) -> String?): String {
        val target = when (l.targetType) {
            CalendarTargetType.CONTACT -> contactName(l.targetId)
            CalendarTargetType.COMPANY -> companyName(l.targetId)
            else -> null
        }
        return "${l.targetType}: ${target ?: l.targetId}"
    }
    /** Тот же инвариант, что AppStateStore.calendarLinkTargetExists у обычного редактора — не предлагаем
     *  восстановить связь на цель, которой в current не существует (requirement 5 — без orphan-ссылок). */
    private fun calendarLinkTargetExistsInCurrent(l: CalendarItemLink, current: PersistedState): Boolean = when (l.targetType) {
        CalendarTargetType.CONTACT -> current.contacts.any { it.id == l.targetId }
        CalendarTargetType.COMPANY -> current.companies.any { it.id == l.targetId }
        CalendarTargetType.GIFT -> current.gifts.any { it.id == l.targetId }
        CalendarTargetType.NOTE -> current.notes.any { it.id == l.targetId }
        CalendarTargetType.ADDRESS -> current.addresses.any { it.id == l.targetId }
    }

    fun analyze(current: PersistedState, plan: RestorePlan): RestorePreview {
        val curContacts = current.contacts.associateBy { it.id }
        val curCompanies = current.companies.associateBy { it.id }
        val curCalendar = current.calendarItems.associateBy { it.id }
        val bakCompanyName: (String) -> String? = { id -> plan.companies.find { it.id == id }?.name ?: curCompanies[id]?.name }
        val localTombstones = current.tombstones.mapTo(HashSet()) { TombstoneKey(it.entityType, it.entityId) }
        val curNotesByContact = current.notes.filter { it.contactId != null }.groupBy { it.contactId }
        val curNotesByCompany = current.notes.filter { it.companyId != null }.groupBy { it.companyId }
        val curGiftsByContact = current.gifts.groupBy { it.contactId }
        val bakNotesByContact = (plan.notes + plan.contacts.flatMap { it.notes }).distinctBy { it.id }.filter { it.contactId != null }.groupBy { it.contactId }
        val bakNotesByCompany = plan.notes.filter { it.companyId != null }.groupBy { it.companyId }
        val bakGiftsByContact = (plan.gifts + plan.contacts.flatMap { it.gifts }).distinctBy { it.id }.groupBy { it.contactId }

        // TAGS BACKUP RECOVERY PACK (2026-09-23): Tag (определение) и ContactTagMember (назначение) —
        // разные сущности (requirement 3 задачи). Идентичность тега между backup и current — по id, с
        // фолбэком на регистронезависимое имя (тот же resolver, что AppStateStore.addTag использует для
        // дедупликации): иначе один и тот же тег с расходящимся id из старого backup создал бы дубль-Tag.
        val curTagById = current.tags.associateBy { it.id }
        val curTagNamesByContact: Map<String, Set<String>> = current.tagMembers.groupBy { it.contactId }
            .mapValues { (_, members) -> members.mapNotNullTo(HashSet()) { curTagById[it.tagId]?.name?.lowercase() } }
        val bakTagById = plan.tags.associateBy { it.id }
        val bakTagMembersByContact = plan.tagMembers.groupBy { it.contactId }

        // RESTORE PREVIEW REMAINING DATA COVERAGE (2026-09-24): ContactRelation — глобальный список (обе
        // стороны — контакты, не вложен в Contact, в отличие от companyRelations). Показывается ТОЛЬКО когда
        // ОБЕ стороны уже существуют в current (requirement 5/SS-RISK-003 dependency guard) — иначе либо
        // orphan-ссылка, либо пришлось бы городить межгрупповой dependency-UI, чего явно просили не делать.
        // Владеет группа firstContactId-стороны (одна запись — один чекбокс, не дублируется под второй стороной).
        val curContactIds = current.contacts.mapTo(HashSet()) { it.id }
        val curRelationIds = current.contactRelations.mapTo(HashSet()) { it.id }
        val relationDiffsByOwner: Map<String, List<RestoreDifference>> = plan.contactRelations
            .filter { it.id !in curRelationIds && it.firstContactId in curContactIds && it.secondContactId in curContactIds }
            .groupBy { it.firstContactId }
            .mapValues { (ownerId, rels) ->
                val ownerName = curContacts[ownerId]?.let { "${it.firstName} ${it.lastName}".trim() } ?: ownerId
                rels.map { r ->
                    val otherName = curContacts[r.secondContactId]?.let { "${it.firstName} ${it.lastName}".trim() } ?: r.secondContactId
                    RestoreDifference(
                        key = "${TombstoneEntityType.CONTACT}|$ownerId|RELATION|${r.id}", ownerId = ownerId, ownerDisplayName = ownerName,
                        ownerType = TombstoneEntityType.CONTACT, entityType = TombstoneEntityType.CONTACT, entityId = r.id,
                        fieldKind = RestoreFieldKind.RELATION, currentValue = null, backupValue = relationLabel(r, otherName),
                        differenceType = RestoreDifferenceType.MISSING,
                    )
                }
            }

        // Group/ContactGroupMember — тот же id-с-фолбэком-на-имя принцип, что уже использует Tag/ContactTagMember
        // чуть выше (не плодит второй «Клиенты» с другим id).
        val curGroupById = current.groups.associateBy { it.id }
        val curGroupByNameLower = current.groups.associateBy { it.name.lowercase() }
        val curGroupNamesByContact: Map<String, Set<String>> = current.groupMembers.groupBy { it.contactId }
            .mapValues { (_, members) -> members.mapNotNullTo(HashSet()) { curGroupById[it.groupId]?.name?.lowercase() } }
        val bakGroupById = plan.groups.associateBy { it.id }
        val bakGroupMembersByContact = plan.groupMembers.groupBy { it.contactId }

        val groups = ArrayList<RestorePreviewGroup>()

        // RESTORE BATCH DEPENDENCIES & STANDALONE GROUPS MICROFIX (2026-09-24): Group с НУЛЕВЫМ числом
        // backup-memberships раньше никогда не посещалась analyze() вовсе (движок сканировал только
        // plan.groupMembers, группируя ПО КОНТАКТУ — пустая группа не появляется ни в одной такой группировке).
        // Группа с ХОТЯ БЫ одним membership уже покрыта через тот membership-diff (или как его dependency,
        // requirement 7 — не плодим ВТОРОЙ способ восстановить ту же группу).
        val bakGroupMembersByGroup = plan.groupMembers.groupBy { it.groupId }
        for (bakGroup in plan.groups) {
            val alreadyResolvable = curGroupById.containsKey(bakGroup.id) || curGroupByNameLower.containsKey(bakGroup.name.lowercase())
            if (alreadyResolvable) continue
            if (!bakGroupMembersByGroup[bakGroup.id].isNullOrEmpty()) continue
            groups += RestorePreviewGroup(bakGroup.id, TombstoneEntityType.GROUP, bakGroup.name, wholeEntityMissing = true,
                previouslyDeleted = false, differences = emptyList(), childSummaryLines = emptyList())
        }

        // ── Contacts ──
        for (b in plan.contacts) {
            val name = "${b.firstName} ${b.lastName}".trim().ifBlank { b.id }
            val c = curContacts[b.id]
            if (c == null) {
                val tombstoned = TombstoneKey(TombstoneEntityType.CONTACT, b.id) in localTombstones
                val summary = buildList {
                    b.phones.forEach { add("+ ${it.type}: ${it.number}") }
                    b.emails.forEach { add("+ Email: ${it.email}") }
                    (bakNotesByContact[b.id] ?: emptyList()).forEach { add("+ Note: ${it.text.take(40)}") }
                    (bakGiftsByContact[b.id] ?: emptyList()).forEach { add("+ Gift: ${it.title}") }
                    b.companyRelations.forEach { add("+ Company: ${bakCompanyName(it.companyId) ?: it.companyId}") }
                    val managedTagNames = (bakTagMembersByContact[b.id] ?: emptyList()).mapNotNullTo(LinkedHashSet()) { bakTagById[it.tagId]?.name }
                    managedTagNames.forEach { add("+ Tag: $it") }
                    // PART C: легаси-only теги отсутствующего контакта — тоже часть того, что восстановит
                    // единственный выбор «Restore contact» (requirement C4), не дублируем managed-имена.
                    b.tags.map { it.trim() }.filter { it.isNotBlank() }
                        .filter { managedTagNames.none { mn -> mn.equals(it, ignoreCase = true) } }
                        .distinctBy { it.lowercase() }
                        .forEach { add("+ Tag: $it") }
                    // Группа отсутствующего контакта — тем же принципом, что теги: едет атомарно с ним.
                    (bakGroupMembersByContact[b.id] ?: emptyList())
                        .mapNotNullTo(LinkedHashSet()) { bakGroupById[it.groupId]?.name }
                        .forEach { add("+ Group: $it") }
                }
                groups += RestorePreviewGroup(b.id, TombstoneEntityType.CONTACT, name, wholeEntityMissing = true,
                    previouslyDeleted = tombstoned, differences = emptyList(), childSummaryLines = summary)
                continue
            }
            val diffs = ArrayList<RestoreDifference>()
            diffs += missingChildren(c.phones, b.phones, { it.id }, ::phoneLabel, b.id, name, TombstoneEntityType.CONTACT, RestoreFieldKind.PHONE, ContactChannelDedup::phoneValueKey)
            diffs += missingChildren(c.emails, b.emails, { it.id }, ::emailLabel, b.id, name, TombstoneEntityType.CONTACT, RestoreFieldKind.EMAIL, ContactChannelDedup::emailValueKey)
            diffs += missingChildren(c.messengers, b.messengers, { it.id }, ::messengerLabel, b.id, name, TombstoneEntityType.CONTACT, RestoreFieldKind.MESSENGER)
            diffs += missingChildren(c.addresses, b.addresses, { it.id }, ::addressLabel, b.id, name, TombstoneEntityType.CONTACT, RestoreFieldKind.ADDRESS)
            diffs += missingChildren(c.personalDetails, b.personalDetails, { it.id }, ::detailLabel, b.id, name, TombstoneEntityType.CONTACT, RestoreFieldKind.PERSONAL_DETAIL)
            diffs += missingChildren(c.companyRelations, b.companyRelations, { it.id }, { relationLabel(it, bakCompanyName) }, b.id, name, TombstoneEntityType.CONTACT, RestoreFieldKind.COMPANY_RELATION)
            diffs += relationDiffsByOwner[b.id] ?: emptyList()
            if (c.sizeInfo == null && b.sizeInfo != null) {
                diffs += RestoreDifference("${TombstoneEntityType.CONTACT}|${b.id}|SIZE_INFO|${b.sizeInfo.id}", b.id, name, TombstoneEntityType.CONTACT,
                    TombstoneEntityType.CONTACT, b.sizeInfo.id, RestoreFieldKind.SIZE_INFO, currentValue = null, backupValue = sizeLabel(b.sizeInfo),
                    differenceType = RestoreDifferenceType.MISSING)
            }
            ContactScalarField.entries.forEach { f -> scalarDiff(c, b.id, name, TombstoneEntityType.CONTACT, TombstoneEntityType.CONTACT, f, f.get(c), f.get(b))?.let { diffs += it } }
            val curTagNames = curTagNamesByContact[b.id] ?: emptySet()
            val seenTagNames = HashSet<String>()
            (bakTagMembersByContact[b.id] ?: emptyList()).forEach { m ->
                val bakTag = bakTagById[m.tagId] ?: return@forEach   // orphan membership в backup — нечего предложить
                val lname = bakTag.name.lowercase()
                if (lname !in curTagNames && seenTagNames.add(lname)) {
                    diffs += RestoreDifference("${TombstoneEntityType.CONTACT}|${b.id}|TAG|${m.id}", b.id, name, TombstoneEntityType.CONTACT,
                        TombstoneEntityType.CONTACT, m.id, RestoreFieldKind.TAG, currentValue = null, backupValue = bakTag.name,
                        differenceType = RestoreDifferenceType.MISSING)
                }
            }
            // LEGACY TAGS COMPATIBILITY & PHONE SYNC SAFETY PACK (2026-09-23), PART C: backup, чьи теги
            // существуют ТОЛЬКО в легаси Contact.tags (старый формат, до managed-тегов) — не молча
            // теряем, предлагаем как recoverable-кандидат. `seenTagNames` уже накопил managed-имена
            // ЭТОГО контакта выше — не дублируем диф, если managed-путь уже предложил то же имя.
            // Ключ через entityId с префиксом "legacy:" — единственный маркер «нет backing backup
            // ContactTagMember строки», который видит buildApplyPlan (requirement C1: не мутировать
            // Room и не создавать managed Tag на этапе анализа — здесь только RestoreDifference).
            b.tags.forEach { rawName ->
                val clean = rawName.trim()
                if (clean.isBlank()) return@forEach
                val lname = clean.lowercase()
                if (lname !in curTagNames && seenTagNames.add(lname)) {
                    diffs += RestoreDifference("${TombstoneEntityType.CONTACT}|${b.id}|TAG|legacy:$lname", b.id, name, TombstoneEntityType.CONTACT,
                        TombstoneEntityType.CONTACT, "legacy:$lname", RestoreFieldKind.TAG, currentValue = null, backupValue = clean,
                        differenceType = RestoreDifferenceType.MISSING)
                }
            }
            // Group membership — тот же id-с-фолбэком-на-имя принцип, что теги чуть выше.
            val curGroupNames = curGroupNamesByContact[b.id] ?: emptySet()
            val seenGroupNames = HashSet<String>()
            (bakGroupMembersByContact[b.id] ?: emptyList()).forEach { m ->
                val bakGroup = bakGroupById[m.groupId] ?: return@forEach   // orphan membership в backup — нечего предложить
                val lname = bakGroup.name.lowercase()
                if (lname !in curGroupNames && seenGroupNames.add(lname)) {
                    diffs += RestoreDifference("${TombstoneEntityType.CONTACT}|${b.id}|GROUP_MEMBERSHIP|${m.id}", b.id, name, TombstoneEntityType.CONTACT,
                        TombstoneEntityType.CONTACT, m.id, RestoreFieldKind.GROUP_MEMBERSHIP, currentValue = null, backupValue = bakGroup.name,
                        differenceType = RestoreDifferenceType.MISSING)
                }
            }
            val curNoteIds = (curNotesByContact[b.id] ?: emptyList()).mapTo(HashSet()) { it.id }
            (bakNotesByContact[b.id] ?: emptyList()).filter { it.id !in curNoteIds }.forEach { n ->
                val tombstoned = TombstoneKey(TombstoneEntityType.NOTE, n.id) in localTombstones
                diffs += RestoreDifference("${TombstoneEntityType.CONTACT}|${b.id}|WHOLE_NOTE|${n.id}", b.id, name, TombstoneEntityType.CONTACT,
                    TombstoneEntityType.NOTE, n.id, RestoreFieldKind.WHOLE_NOTE, currentValue = null, backupValue = n.text,
                    differenceType = RestoreDifferenceType.MISSING, previouslyDeleted = tombstoned)
            }
            val curGiftIds = (curGiftsByContact[b.id] ?: emptyList()).mapTo(HashSet()) { it.id }
            (bakGiftsByContact[b.id] ?: emptyList()).filter { it.id !in curGiftIds }.forEach { g ->
                val tombstoned = TombstoneKey(TombstoneEntityType.GIFT, g.id) in localTombstones
                diffs += RestoreDifference("${TombstoneEntityType.CONTACT}|${b.id}|WHOLE_GIFT|${g.id}", b.id, name, TombstoneEntityType.CONTACT,
                    TombstoneEntityType.GIFT, g.id, RestoreFieldKind.WHOLE_GIFT, currentValue = null, backupValue = g.title,
                    differenceType = RestoreDifferenceType.MISSING, previouslyDeleted = tombstoned)
            }
            if (diffs.isNotEmpty()) groups += RestorePreviewGroup(b.id, TombstoneEntityType.CONTACT, name, wholeEntityMissing = false, previouslyDeleted = false, differences = diffs)
        }

        // ── Companies ──
        for (b in plan.companies) {
            val c = curCompanies[b.id]
            if (c == null) {
                val tombstoned = TombstoneKey(TombstoneEntityType.COMPANY, b.id) in localTombstones
                val summary = buildList {
                    b.phones.forEach { add("+ ${it.type}: ${it.number}") }
                    b.emails.forEach { add("+ Email: ${it.email}") }
                    (bakNotesByCompany[b.id] ?: emptyList()).forEach { add("+ Note: ${it.text.take(40)}") }
                }
                groups += RestorePreviewGroup(b.id, TombstoneEntityType.COMPANY, b.name, wholeEntityMissing = true,
                    previouslyDeleted = tombstoned, differences = emptyList(), childSummaryLines = summary)
                continue
            }
            val diffs = ArrayList<RestoreDifference>()
            diffs += missingChildren(c.phones, b.phones, { it.id }, ::phoneLabel, b.id, b.name, TombstoneEntityType.COMPANY, RestoreFieldKind.PHONE)
            diffs += missingChildren(c.emails, b.emails, { it.id }, ::emailLabel, b.id, b.name, TombstoneEntityType.COMPANY, RestoreFieldKind.EMAIL)
            diffs += missingChildren(c.addresses, b.addresses, { it.id }, ::addressLabel, b.id, b.name, TombstoneEntityType.COMPANY, RestoreFieldKind.ADDRESS)
            CompanyScalarField.entries.forEach { f -> scalarDiff(c, b.id, b.name, TombstoneEntityType.COMPANY, TombstoneEntityType.COMPANY, f, f.get(c), f.get(b))?.let { diffs += it } }
            val curNoteIds = (curNotesByCompany[b.id] ?: emptyList()).mapTo(HashSet()) { it.id }
            (bakNotesByCompany[b.id] ?: emptyList()).filter { it.id !in curNoteIds }.forEach { n ->
                val tombstoned = TombstoneKey(TombstoneEntityType.NOTE, n.id) in localTombstones
                diffs += RestoreDifference("${TombstoneEntityType.COMPANY}|${b.id}|WHOLE_NOTE|${n.id}", b.id, b.name, TombstoneEntityType.COMPANY,
                    TombstoneEntityType.NOTE, n.id, RestoreFieldKind.WHOLE_NOTE, currentValue = null, backupValue = n.text,
                    differenceType = RestoreDifferenceType.MISSING, previouslyDeleted = tombstoned)
            }
            if (diffs.isNotEmpty()) groups += RestorePreviewGroup(b.id, TombstoneEntityType.COMPANY, b.name, wholeEntityMissing = false, previouslyDeleted = false, differences = diffs)
        }

        // ── Calendar items ──
        for (b in plan.calendarItems) {
            val c = curCalendar[b.id]
            if (c == null) {
                val tombstoned = TombstoneKey(TombstoneEntityType.CALENDAR_ITEM, b.id) in localTombstones
                groups += RestorePreviewGroup(b.id, TombstoneEntityType.CALENDAR_ITEM, b.title, wholeEntityMissing = true,
                    previouslyDeleted = tombstoned, differences = emptyList(), childSummaryLines = listOf(b.startDate))
                continue
            }
            val diffs = ArrayList<RestoreDifference>()
            CalendarScalarField.entries.forEach { f -> scalarDiff(c, b.id, b.title, TombstoneEntityType.CALENDAR_ITEM, TombstoneEntityType.CALENDAR_ITEM, f, f.get(c), f.get(b))?.let { diffs += it } }
            // RESTORE PREVIEW REMAINING DATA COVERAGE (2026-09-24): дети СУЩЕСТВУЮЩЕГО события — НЕ то же
            // самое, что whole-missing restore (requirement 3 задачи — те едут атомарно через addCalendarItemDb,
            // сюда не заходя). Ссылки — только если их target уже существует в current (без orphan-ссылок).
            diffs += missingChildren(c.reminders, b.reminders, { it.id }, ::reminderLabel, b.id, b.title, TombstoneEntityType.CALENDAR_ITEM, RestoreFieldKind.CALENDAR_REMINDER)
            val curContactName: (String) -> String? = { id -> curContacts[id]?.let { "${it.firstName} ${it.lastName}".trim() } }
            diffs += missingChildren(c.links, b.links.filter { calendarLinkTargetExistsInCurrent(it, current) },
                { it.id }, { linkLabel(it, curContactName, bakCompanyName) }, b.id, b.title, TombstoneEntityType.CALENDAR_ITEM, RestoreFieldKind.CALENDAR_LINK)
            if (diffs.isNotEmpty()) groups += RestorePreviewGroup(b.id, TombstoneEntityType.CALENDAR_ITEM, b.title, wholeEntityMissing = false, previouslyDeleted = false, differences = diffs)
        }

        return RestorePreview(groups, plan)
    }

    fun summarize(preview: RestorePreview, selectedKeys: Set<String>): RestorePreviewSummary {
        var additions = 0; var replacements = 0; var entitiesRestored = 0; var previouslyDeletedRestored = 0
        for (g in preview.groups) {
            if (g.wholeEntityMissing) {
                if (g.wholeKey in selectedKeys) {
                    entitiesRestored++
                    if (g.previouslyDeleted) previouslyDeletedRestored++
                    // дети едут вместе с владельцем — считаем по числу информационных строк как «добавления»
                    additions += g.childSummaryLines.size + 1
                }
                continue
            }
            for (d in g.differences) {
                if (d.key !in selectedKeys) continue
                when (d.differenceType) {
                    RestoreDifferenceType.MISSING -> {
                        additions++
                        if (d.fieldKind == RestoreFieldKind.WHOLE_NOTE || d.fieldKind == RestoreFieldKind.WHOLE_GIFT) {
                            entitiesRestored++
                            if (d.previouslyDeleted) previouslyDeletedRestored++
                        }
                    }
                    RestoreDifferenceType.CONFLICT -> replacements++
                }
            }
        }
        return RestorePreviewSummary(additions, replacements, entitiesRestored, previouslyDeletedRestored)
    }
}

// ── план применения (чистый) — исполняется AppStateStore.applySelectiveRestore в одной транзакции ──────────

data class SelectiveApplyPlan(
    val wholeContacts: List<Contact>,
    val wholeCompanies: List<Company>,
    val wholeCalendarItems: List<CalendarItem>,
    /** STANDALONE GROUPS MICROFIX (2026-09-24): явный пользовательский выбор «Restore group» для backup-группы
     *  без единого membership — считается в [totalSelected] (в отличие от [groupDefinitions] ниже — те
     *  dependency-only, автоматический побочный эффект выбранного membership, requirement 11 Tags Backup
     *  Recovery Pack, тот же принцип). */
    val wholeGroups: List<ContactGroup> = emptyList(),
    val standaloneNotes: List<Note>,
    val standaloneGifts: List<GiftIdea>,
    val childPhones: List<ContactPhone>,
    val childEmails: List<ContactEmail>,
    val childMessengers: List<Messenger>,
    val childAddresses: List<Address>,
    val childPersonalDetails: List<PersonalDetail>,
    val childCompanyRelations: List<ContactCompanyRelation>,
    val childSizeInfos: List<SizeInfo>,
    val scalarContactUpdates: List<Contact>,
    val scalarCompanyUpdates: List<Company>,
    val scalarCalendarUpdates: List<CalendarItem>,
    val tombstonesToClear: List<TombstoneKey>,
    val touchesCalendar: Boolean,
    /** TAGS BACKUP RECOVERY PACK: Tag-определения, нужные ТОЛЬКО как зависимость выбранного membership
     *  (тег ещё не существует в current ни по id, ни по имени) — НЕ пользовательское назначение само по
     *  себе, поэтому намеренно НЕ входит в [totalSelected]/счётчики (requirement 11 — не считать dependency
     *  вторым назначением тега). */
    val tagDefinitions: List<Tag> = emptyList(),
    /** Реальные выбранные назначения тега контакту — то, что пользователь увидел и отметил в preview. */
    val tagMemberships: List<ContactTagMember> = emptyList(),
    /** RESTORE PREVIEW REMAINING DATA COVERAGE (2026-09-24): связи между контактами, выбранные пользователем. */
    val contactRelationsToAdd: List<ContactRelation> = emptyList(),
    /** Group-определения, нужные ТОЛЬКО как зависимость выбранного membership (тот же принцип, что tagDefinitions) —
     *  НЕ входит в [totalSelected]. */
    val groupDefinitions: List<ContactGroup> = emptyList(),
    /** Реальные выбранные членства в группе. */
    val groupMemberships: List<ContactGroupMember> = emptyList(),
    /** Недостающие напоминания/связи СУЩЕСТВУЮЩЕГО CalendarItem (НЕ whole-missing — те едут своим путём). */
    val childCalendarReminders: List<ReminderRule> = emptyList(),
    val childCalendarLinks: List<CalendarItemLink> = emptyList(),
) {
    val totalSelected: Int get() = wholeContacts.size + wholeCompanies.size + wholeCalendarItems.size + wholeGroups.size +
        standaloneNotes.size + standaloneGifts.size + childPhones.size + childEmails.size + childMessengers.size + childAddresses.size +
        childPersonalDetails.size + childCompanyRelations.size + childSizeInfos.size + scalarContactUpdates.size +
        scalarCompanyUpdates.size + scalarCalendarUpdates.size + tagMemberships.size + contactRelationsToAdd.size +
        groupMemberships.size + childCalendarReminders.size + childCalendarLinks.size
}

/** Переводит выбор пользователя в исполняемый план. Чистая функция — саму запись делает AppStateStore. */
fun RestoreDiffEngine.buildApplyPlan(preview: RestorePreview, current: PersistedState, selectedKeys: Set<String>): SelectiveApplyPlan {
    val plan = preview.plan
    val curContacts = current.contacts.associateBy { it.id }
    val curCompanies = current.companies.associateBy { it.id }
    val curCalendar = current.calendarItems.associateBy { it.id }
    val bakContacts = plan.contacts.associateBy { it.id }
    val bakCompanies = plan.companies.associateBy { it.id }
    val bakCalendar = plan.calendarItems.associateBy { it.id }
    val bakNotesById = (plan.notes + plan.contacts.flatMap { it.notes }).associateBy { it.id }
    val bakNotesByCompany = plan.notes.filter { it.companyId != null }.groupBy { it.companyId }
    val bakGiftsById = (plan.gifts + plan.contacts.flatMap { it.gifts }).associateBy { it.id }
    val bakPhonesById = (plan.contacts.flatMap { it.phones } + plan.companies.flatMap { it.phones }).associateBy { it.id }
    val bakEmailsById = (plan.contacts.flatMap { it.emails } + plan.companies.flatMap { it.emails }).associateBy { it.id }
    val bakMessengersById = plan.contacts.flatMap { it.messengers }.associateBy { it.id }
    val bakAddressesById = (plan.contacts.flatMap { it.addresses } + plan.companies.flatMap { it.addresses }).associateBy { it.id }
    val bakDetailsById = plan.contacts.flatMap { it.personalDetails }.associateBy { it.id }
    val bakRelationsById = plan.contacts.flatMap { it.companyRelations }.associateBy { it.id }
    val bakSizeById = plan.contacts.mapNotNull { it.sizeInfo }.associateBy { it.id }

    // TAGS BACKUP RECOVERY PACK (2026-09-23): та же id-с-фолбэком-на-имя идентичность, что analyze()
    // использует для строить diff'ы — buildApplyPlan обязан резолвить ОДИНАКОВО, иначе membership может
    // указать на tagId, которого нет ни в current, ни среди только что запланированных к вставке (orphan).
    val bakTagById = plan.tags.associateBy { it.id }
    val bakTagMembersByContact = plan.tagMembers.groupBy { it.contactId }
    val bakTagMembersById = plan.tagMembers.associateBy { it.id }
    val curTagById = current.tags.associateBy { it.id }
    val curTagByNameLower = current.tags.associateBy { it.name.lowercase() }
    val tagDefsToAdd = LinkedHashMap<String, Tag>()
    val tagMembershipsToAdd = ArrayList<ContactTagMember>()
    /** Тег уже существует (по id или по регистронезависимому имени, requirement 3/9 — не плодить второй
     *  «VIP» с другим id) -> используем ЕГО id; иначе планируем вставку backup-определения как зависимость
     *  (requirement 7/8) и используем backup-id (после вставки он будет существовать). */
    fun resolveTagId(bakTag: Tag): String {
        curTagById[bakTag.id]?.let { return it.id }
        curTagByNameLower[bakTag.name.lowercase()]?.let { return it.id }
        tagDefsToAdd.putIfAbsent(bakTag.id, bakTag)
        return bakTag.id
    }
    /** PART C (Legacy Tags Compatibility & Phone Sync Safety Pack, 2026-09-23): легаси-only кандидат —
     *  backup-объекта Tag нет вовсе, только свободное имя из Contact.tags (C1). Тот же id-или-имя резолвер
     *  по духу, но по имени напрямую; при отсутствии — ДЕТЕРМИНИРОВАННЫЙ (не random) синтетический id —
     *  повторный apply того же выбора обязан вернуть ТОТ ЖЕ id (T10, идемпотентность без опоры только на
     *  «current уже содержит», см. legacyMembershipId ниже). */
    fun resolveOrQueueTagByName(rawName: String): String {
        val clean = rawName.trim()
        curTagByNameLower[clean.lowercase()]?.let { return it.id }
        tagDefsToAdd.values.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.id }
        val syntheticId = "legacy-tag:${clean.lowercase()}"
        tagDefsToAdd.putIfAbsent(syntheticId, Tag(id = syntheticId, name = clean, createdAt = LEGACY_TAG_TIMESTAMP, updatedAt = LEGACY_TAG_TIMESTAMP))
        return syntheticId
    }
    fun legacyMembershipId(contactId: String, tagId: String) = "legacy-tag-member:$contactId:$tagId"

    // RESTORE PREVIEW REMAINING DATA COVERAGE (2026-09-24): тот же id-с-фолбэком-на-имя резолвер, что тег
    // выше, для Group — buildApplyPlan обязан резолвить ОДИНАКОВО с analyze() (curGroupNamesByContact там).
    val bakRelationsGlobalById = plan.contactRelations.associateBy { it.id }
    val bakGroupById = plan.groups.associateBy { it.id }
    val bakGroupMembersById = plan.groupMembers.associateBy { it.id }
    val curGroupById = current.groups.associateBy { it.id }
    val curGroupByNameLower = current.groups.associateBy { it.name.lowercase() }
    val groupDefsToAdd = LinkedHashMap<String, ContactGroup>()
    val groupMembershipsToAdd = ArrayList<ContactGroupMember>()
    fun resolveGroupId(bakGroup: ContactGroup): String {
        curGroupById[bakGroup.id]?.let { return it.id }
        curGroupByNameLower[bakGroup.name.lowercase()]?.let { return it.id }
        groupDefsToAdd.putIfAbsent(bakGroup.id, bakGroup)
        return bakGroup.id
    }
    val bakRemindersById = plan.calendarItems.flatMap { it.reminders }.associateBy { it.id }
    val bakLinksById = plan.calendarItems.flatMap { it.links }.associateBy { it.id }

    val wholeContacts = ArrayList<Contact>(); val wholeCompanies = ArrayList<Company>(); val wholeCalendarItems = ArrayList<CalendarItem>()
    val wholeGroups = ArrayList<ContactGroup>()
    val tombstonesToClear = ArrayList<TombstoneKey>()
    val standaloneNotes = ArrayList<Note>()
    val contactRelationsToAdd = ArrayList<ContactRelation>()
    var touchesCalendar = false

    // RESTORE BATCH DEPENDENCIES & STANDALONE GROUPS MICROFIX (2026-09-24): множество id контактов, которые
    // БУДУТ существовать после ЭТОГО apply — текущие ПЛЮС те whole-missing, что пользователь выбрал В ЭТОМ ЖЕ
    // batch. Известно ЦЕЛИКОМ заранее (selectedKeys передан целиком), независимо от порядка обхода preview.groups
    // ниже — связь между ДВУМЯ одновременно восстанавливаемыми контактами иначе никогда бы не резолвилась
    // (curContacts на момент analyze() не знает про ещё не сделанный выбор пользователя).
    val contactsInThisBatch: Set<String> = curContacts.keys + preview.groups
        .filter { it.ownerType == TombstoneEntityType.CONTACT && it.wholeEntityMissing && it.wholeKey in selectedKeys }
        .map { it.ownerId }

    for (g in preview.groups) {
        if (g.wholeEntityMissing && g.wholeKey in selectedKeys) {
            when (g.ownerType) {
                TombstoneEntityType.CONTACT -> {
                    bakContacts[g.ownerId]?.let { wholeContacts += it }
                    // Whole-contact restore приносит ВСЕ его backup-теги атомарно, одним выбором
                    // (requirement 7) — не отдельными чекбоксами, тем же принципом, что notes/gifts.
                    val includedTagNames = HashSet<String>()
                    (bakTagMembersByContact[g.ownerId] ?: emptyList()).forEach { m ->
                        bakTagById[m.tagId]?.let { bakTag ->
                            tagMembershipsToAdd += m.copy(tagId = resolveTagId(bakTag))
                            includedTagNames.add(bakTag.name.lowercase())
                        }
                    }
                    // PART C: легаси-only теги отсутствующего контакта — тоже едут вместе с ним, тем же
                    // единственным выбором (requirement C4); не дублируем имя, уже принесённое managed-путём.
                    (bakContacts[g.ownerId]?.tags ?: emptyList()).forEach { rawName ->
                        val clean = rawName.trim()
                        if (clean.isBlank() || !includedTagNames.add(clean.lowercase())) return@forEach
                        val resolvedTagId = resolveOrQueueTagByName(clean)
                        tagMembershipsToAdd += ContactTagMember(id = legacyMembershipId(g.ownerId, resolvedTagId), tagId = resolvedTagId, contactId = g.ownerId)
                    }
                    // Группы отсутствующего контакта — тем же единственным выбором, тем же принципом, что теги.
                    plan.groupMembers.filter { it.contactId == g.ownerId }.forEach { m ->
                        bakGroupById[m.groupId]?.let { bakGroup -> groupMembershipsToAdd += m.copy(groupId = resolveGroupId(bakGroup)) }
                    }
                    // Связи — дозволены, если другая сторона УЖЕ существует в current ИЛИ САМА тоже whole-missing
                    // и ВЫБРАНА в этом же batch (in-flight dependency resolution, requirement 4 микрофикса) —
                    // сторона, которая сейчас whole-missing и НЕ выбрана в ЭТОМ apply, не тянет связь молча
                    // (requirement 5 — не восстанавливать relation только потому, что ОДНА сторона выбрана).
                    plan.contactRelations.filter { it.firstContactId == g.ownerId && it.secondContactId in contactsInThisBatch }
                        .forEach { contactRelationsToAdd += it }
                    plan.contactRelations.filter { it.secondContactId == g.ownerId && it.firstContactId in contactsInThisBatch }
                        .forEach { contactRelationsToAdd += it }
                }
                // ФИКС (finding F1, Data Safety Residual Audit 2026-09-23): Company, в отличие от Contact,
                // не несёт notes как вложенное поле (их связь — отдельная плоская таблица по companyId) —
                // preview честно ОБЕЩАЕТ заметки компании в childSummaryLines/summarize(), но без этой строки
                // buildApplyPlan их молча терял. applySelectiveRestore уже безусловно вставляет standaloneNotes
                // в ТОЙ ЖЕ транзакции (AppStateStore.kt) — изменений там не требуется.
                TombstoneEntityType.COMPANY -> {
                    bakCompanies[g.ownerId]?.let { wholeCompanies += it }
                    bakNotesByCompany[g.ownerId]?.let { standaloneNotes += it }
                }
                TombstoneEntityType.CALENDAR_ITEM -> { bakCalendar[g.ownerId]?.let { wholeCalendarItems += it }; touchesCalendar = true }
                // STANDALONE GROUPS MICROFIX: явный выбор пользователя ("Restore group") — СЧИТАЕТСЯ в
                // totalSelected (в отличие от groupDefinitions/tagDefinitions — те dependency-only).
                TombstoneEntityType.GROUP -> bakGroupById[g.ownerId]?.let { wholeGroups += it }
                else -> Unit
            }
            if (g.previouslyDeleted) tombstonesToClear += TombstoneKey(g.ownerType, g.ownerId)
        }
    }

    val standaloneGifts = ArrayList<GiftIdea>()
    val childPhones = ArrayList<ContactPhone>(); val childEmails = ArrayList<ContactEmail>(); val childMessengers = ArrayList<Messenger>()
    val childAddresses = ArrayList<Address>(); val childPersonalDetails = ArrayList<PersonalDetail>(); val childCompanyRelations = ArrayList<ContactCompanyRelation>()
    val childSizeInfos = ArrayList<SizeInfo>()
    val childCalendarReminders = ArrayList<ReminderRule>(); val childCalendarLinks = ArrayList<CalendarItemLink>()
    var scalarContactUpdatesMap = HashMap<String, Contact>()
    var scalarCompanyUpdatesMap = HashMap<String, Company>()
    var scalarCalendarUpdatesMap = HashMap<String, CalendarItem>()

    for (g in preview.groups) {
        if (g.wholeEntityMissing) continue
        for (d in g.differences) {
            if (d.key !in selectedKeys) continue
            when (d.fieldKind) {
                RestoreFieldKind.PHONE -> bakPhonesById[d.entityId]?.let { childPhones += it }
                RestoreFieldKind.EMAIL -> bakEmailsById[d.entityId]?.let { childEmails += it }
                RestoreFieldKind.MESSENGER -> bakMessengersById[d.entityId]?.let { childMessengers += it }
                RestoreFieldKind.ADDRESS -> bakAddressesById[d.entityId]?.let { childAddresses += it }
                RestoreFieldKind.PERSONAL_DETAIL -> bakDetailsById[d.entityId]?.let { childPersonalDetails += it }
                RestoreFieldKind.COMPANY_RELATION -> bakRelationsById[d.entityId]?.let { childCompanyRelations += it }
                RestoreFieldKind.SIZE_INFO -> bakSizeById[d.entityId]?.let { childSizeInfos += it }
                RestoreFieldKind.WHOLE_NOTE -> {
                    bakNotesById[d.entityId]?.let { standaloneNotes += it }
                    if (d.previouslyDeleted) tombstonesToClear += TombstoneKey(TombstoneEntityType.NOTE, d.entityId)
                }
                RestoreFieldKind.WHOLE_GIFT -> {
                    bakGiftsById[d.entityId]?.let { standaloneGifts += it }
                    if (d.previouslyDeleted) tombstonesToClear += TombstoneKey(TombstoneEntityType.GIFT, d.entityId)
                }
                RestoreFieldKind.TAG -> {
                    val realMember = bakTagMembersById[d.entityId]
                    if (realMember != null) {
                        bakTagById[realMember.tagId]?.let { bakTag -> tagMembershipsToAdd += realMember.copy(tagId = resolveTagId(bakTag)) }
                    } else {
                        // PART C: легаси-only кандидат (analyze() пометил entityId префиксом "legacy:") —
                        // нет backing backup ContactTagMember строки, имя тега лежит в backupValue.
                        d.backupValue?.let { legacyName ->
                            val resolvedTagId = resolveOrQueueTagByName(legacyName)
                            tagMembershipsToAdd += ContactTagMember(id = legacyMembershipId(g.ownerId, resolvedTagId), tagId = resolvedTagId, contactId = g.ownerId)
                        }
                    }
                }
                RestoreFieldKind.RELATION -> bakRelationsGlobalById[d.entityId]?.let { contactRelationsToAdd += it }
                RestoreFieldKind.GROUP_MEMBERSHIP -> {
                    val realMember = bakGroupMembersById[d.entityId]
                    realMember?.let { m -> bakGroupById[m.groupId]?.let { bakGroup -> groupMembershipsToAdd += m.copy(groupId = resolveGroupId(bakGroup)) } }
                }
                RestoreFieldKind.CALENDAR_REMINDER -> bakRemindersById[d.entityId]?.let { childCalendarReminders += it; touchesCalendar = true }
                RestoreFieldKind.CALENDAR_LINK -> bakLinksById[d.entityId]?.let { childCalendarLinks += it; touchesCalendar = true }
                RestoreFieldKind.SCALAR -> when (g.ownerType) {
                    TombstoneEntityType.CONTACT -> {
                        val base = scalarContactUpdatesMap[g.ownerId] ?: curContacts[g.ownerId] ?: continue
                        scalarContactUpdatesMap[g.ownerId] = (d.scalarField as ContactScalarField).with(base, d.backupValue)
                    }
                    TombstoneEntityType.COMPANY -> {
                        val base = scalarCompanyUpdatesMap[g.ownerId] ?: curCompanies[g.ownerId] ?: continue
                        scalarCompanyUpdatesMap[g.ownerId] = (d.scalarField as CompanyScalarField).with(base, d.backupValue)
                    }
                    TombstoneEntityType.CALENDAR_ITEM -> {
                        val base = scalarCalendarUpdatesMap[g.ownerId] ?: curCalendar[g.ownerId] ?: continue
                        scalarCalendarUpdatesMap[g.ownerId] = (d.scalarField as CalendarScalarField).with(base, d.backupValue)
                        touchesCalendar = true
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
    }

    return SelectiveApplyPlan(
        wholeContacts = wholeContacts, wholeCompanies = wholeCompanies, wholeCalendarItems = wholeCalendarItems, wholeGroups = wholeGroups,
        standaloneNotes = standaloneNotes, standaloneGifts = standaloneGifts,
        childPhones = childPhones, childEmails = childEmails, childMessengers = childMessengers, childAddresses = childAddresses,
        childPersonalDetails = childPersonalDetails, childCompanyRelations = childCompanyRelations, childSizeInfos = childSizeInfos,
        scalarContactUpdates = scalarContactUpdatesMap.values.toList(), scalarCompanyUpdates = scalarCompanyUpdatesMap.values.toList(),
        scalarCalendarUpdates = scalarCalendarUpdatesMap.values.toList(),
        tombstonesToClear = tombstonesToClear.distinct(), touchesCalendar = touchesCalendar,
        tagDefinitions = tagDefsToAdd.values.toList(), tagMemberships = tagMembershipsToAdd,
        contactRelationsToAdd = contactRelationsToAdd.distinctBy { it.id },
        groupDefinitions = groupDefsToAdd.values.toList(), groupMemberships = groupMembershipsToAdd,
        childCalendarReminders = childCalendarReminders, childCalendarLinks = childCalendarLinks,
    )
}
