package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import java.text.Normalizer

// ФИКС (2026-07-12, фидбэк владельца: слова с ударением/диакритикой не
// находились поиском — «José» не находился по «jose» и наоборот). Обычный
// .lowercase() не трогает диакритику. NFD раскладывает букву+ударение на
// базовый символ + отдельный combining mark (\p{Mn}), который затем вырезаем —
// используем ВЕЗДЕ в поиске вместо .lowercase() (для сравнения, не для показа).
private val COMBINING_MARKS = Regex("\\p{Mn}+")
fun String.searchNormalize(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

// ─── Unified search result ────────────────────────────────────
sealed class SearchResult {
    data class ContactResult(
        val contact: Contact,
        val matchField: String,   // что именно совпало
        val score: Int
    ) : SearchResult()

    data class CompanyResult(
        val company: Company,
        val matchField: String,
        val score: Int
    ) : SearchResult()
}

// ─── Main search engine ───────────────────────────────────────
object SearchEngine {

    /** Search only contacts with rich field matching. */
    fun searchContacts(query: String): List<SearchResult.ContactResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().searchNormalize()
        return AppStateStore.contacts.mapNotNull { contact ->
            scoreContact(contact, q)
        }.sortedByDescending { it.score }
    }

    /** Search only companies. */
    fun searchCompanies(query: String): List<SearchResult.CompanyResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().searchNormalize()
        return AppStateStore.companies.mapNotNull { company ->
            scoreCompany(company, q)
        }.sortedByDescending { it.score }
    }

    // ── Contact scoring ────────────────────────────────────────
    private fun scoreContact(contact: Contact, q: String): SearchResult.ContactResult? {
        var score = 0
        var matchField = ""

        // Полное имя с отчеством — «Иван Петрович Сидоров» находится по любому слову
        val fullName = listOfNotNull(
            contact.firstName.takeIf { it.isNotBlank() },
            contact.middleName?.takeIf { it.isNotBlank() },
            contact.lastName.takeIf { it.isNotBlank() }
        ).joinToString(" ").searchNormalize()
        if (fullName.startsWith(q))          { score += 100; matchField = "name" }
        else if (fullName.contains(q))       { score += 80;  matchField = "name" }
        else if (contact.firstName.searchNormalize().contains(q)) { score += 75; matchField = "name" }
        else if (contact.lastName.searchNormalize().contains(q))  { score += 75; matchField = "surname" }
        else if (contact.middleName?.searchNormalize()?.contains(q) == true) { score += 70; matchField = "patronymic" }

        // Прозвище — вес как у имени (фидбэк владельца: «любимое» должно находиться)
        if (contact.nickname?.searchNormalize()?.contains(q) == true) { score += 75; matchField = "nickname" }

        // Phone
        contact.phones.forEach { p ->
            if (p.number.contains(q)) { score += 70; matchField = "phone" }
        }
        // Email
        contact.emails.forEach { e ->
            if (e.email.searchNormalize().contains(q)) { score += 65; matchField = "email" }
        }
        // Messenger username — m.type.labelKey() уже возвращает как правило
        // language-neutral имя мессенджера (Telegram/WhatsApp/...), кроме
        // MessengerType.OTHER ("Другое") — известное ограничение, не влияет
        // на реальные мессенджеры, только на «Другое».
        contact.messengers.forEach { m ->
            if (m.value.searchNormalize().contains(q)) { score += 60; matchField = m.type.labelKey() }
        }
        // Company name / position
        contact.companyRelations.forEach { rel ->
            val comp = AppStateStore.getCompany(rel.companyId)
            if (comp?.name?.searchNormalize()?.contains(q) == true) { score += 55; matchField = "company" }
            if (rel.position?.searchNormalize()?.contains(q) == true) { score += 50; matchField = "position" }
            if (rel.department?.searchNormalize()?.contains(q) == true) { score += 40; matchField = "department" }
        }
        // ФИКС (аудит 2026-08-11, жалоба «профессия никуда не переносится»):
        // profession нигде не участвовал в скоринге — введённый текст не находился.
        if (contact.profession?.searchNormalize()?.contains(q) == true) { score += 55; matchField = "profession" }
        // City
        AppStateStore.addresses
            .filter { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
            .forEach { a ->
                if (a.city.searchNormalize().contains(q))    { score += 45; matchField = "city" }
                if (a.country.searchNormalize().contains(q)) { score += 40; matchField = "country" }
                if (a.district?.searchNormalize()?.contains(q) == true) { score += 40; matchField = "district" }
            }
        // Notes
        contact.notes.forEach { n ->
            if (n.text.searchNormalize().contains(q)) { score += 30; matchField = "note" }
        }
        // Personal details (likes/dislikes/interests)
        contact.personalDetails.forEach { pd ->
            if (pd.value.searchNormalize().contains(q)) { score += 25; matchField = pd.category.labelKey() }
        }
        // Gifts
        contact.gifts.forEach { g ->
            if (g.title.searchNormalize().contains(q)) { score += 20; matchField = "gift" }
        }
        // Теги (фидбэк владельца: раньше не искались вообще)
        contact.tags.forEach { t ->
            if (t.searchNormalize().contains(q)) { score += 55; matchField = "tag" }
        }
        // Группы (фидбэк 2026-07-04: «группы должны находиться в поиске»)
        AppStateStore.groupMembers
            .filter { it.contactId == contact.id }
            .forEach { m ->
                val g = AppStateStore.groups.firstOrNull { it.id == m.groupId } ?: return@forEach
                if (g.name.searchNormalize().contains(q)) { score += 55; matchField = "group" }
            }
        // Свой тип отношений / где познакомились / следующий шаг
        if (contact.customRelationshipType?.searchNormalize()?.contains(q) == true) { score += 40; matchField = "type" }
        if (contact.nextStep?.searchNormalize()?.contains(q) == true) { score += 25; matchField = "next_step" }
        // Тип отношений (стандартный лейбл) — главный ИЛИ любой из второстепенных
        // (Contact.secondaryRelationshipTypes, решение владельца 2026-07-23: один
        // главный тип + произвольное число второстепенных).
        if (contact.relationshipType.labelKey().searchNormalize().contains(q)) { score += 15; matchField = "type" }
        if (contact.secondaryRelationshipTypes.any { it.labelKey().searchNormalize().contains(q) }) { score += 10; matchField = "type" }

        // Заметки контакта из общего стора (contact.notes на собранном объекте
        // может быть пустым — заметки живут в AppStateStore.notes)
        if (matchField.isEmpty()) {
            AppStateStore.notes.firstOrNull { it.contactId == contact.id && it.text.searchNormalize().contains(q) }
                ?.let { score += 30; matchField = "note" }
        }

        return if (score > 0) SearchResult.ContactResult(contact, matchField, score) else null
    }

    // ── Company scoring ────────────────────────────────────────
    private fun scoreCompany(company: Company, q: String): SearchResult.CompanyResult? {
        var score = 0
        var matchField = ""

        if (company.name.searchNormalize().startsWith(q))  { score += 100; matchField = "company_name" }
        else if (company.name.searchNormalize().contains(q)){ score += 80;  matchField = "company_name" }

        if (company.industry.labelKey().searchNormalize().contains(q)) { score += 60; matchField = "industry" }
        if (company.description?.searchNormalize()?.contains(q) == true) { score += 40; matchField = "description" }
        if (company.website?.searchNormalize()?.contains(q) == true) { score += 30; matchField = "website" }

        AppStateStore.addresses
            .filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
            .forEach { a ->
                if (a.city.searchNormalize().contains(q))    { score += 45; matchField = "city" }
                if (a.country.searchNormalize().contains(q)) { score += 35; matchField = "country" }
                if (a.district?.searchNormalize()?.contains(q) == true) { score += 35; matchField = "district" }
            }

        // People in the company
        AppStateStore.companyRelations.filter { it.companyId == company.id }.forEach { rel ->
            val c = AppStateStore.getContact(rel.contactId) ?: return@forEach
            val name = "${c.firstName} ${c.lastName}".searchNormalize()
            if (name.contains(q)) { score += 35; matchField = "employee" }
            if (rel.position?.searchNormalize()?.contains(q) == true) { score += 25; matchField = "position" }
        }

        return if (score > 0) SearchResult.CompanyResult(company, matchField, score) else null
    }
}

// ─── Contact filter helpers ───────────────────────────────────
enum class ContactSortOrder { NAME_AZ, NAME_ZA, RECENTLY_ADDED, IMPORTANCE }
enum class CompanySortOrder { NAME_AZ, NAME_ZA, MOST_CONTACTS, RECENTLY_ADDED }

// ContactDisplayPreferences (2026-07-11): как в реальном Android-контактах
// (ContactsContract.Preferences) — «сортировать по» и «формат отображения»
// это ДВА независимых измерения, не один флаг «зеркалить оба сразу»:
// SORT_ORDER_PRIMARY/ALTERNATIVE (имя/фамилия ведущее слово при сортировке)
// и DISPLAY_ORDER_PRIMARY/ALTERNATIVE (что показывается первым в строке).
// Можно сортировать по фамилии, но по-прежнему показывать «Имя Фамилия».
enum class ContactSortField { FIRST_NAME, LAST_NAME }
enum class ContactNameFormat { FIRST_NAME_FIRST, LAST_NAME_FIRST }

/** Ключ сортировки контакта по выбранному полю (given-name-primary vs
 *  family-name-primary — как SORT_ORDER_PRIMARY/ALTERNATIVE в AOSP). */
fun contactSortKey(contact: Contact, field: ContactSortField): String = when (field) {
    ContactSortField.FIRST_NAME -> "${contact.firstName} ${contact.lastName}".trim().lowercase()
    ContactSortField.LAST_NAME  -> "${contact.lastName} ${contact.firstName}".trim().lowercase()
}

/** Буква для алфавитной группировки/индекса — по тому же полю, что и сортировка. */
fun contactSortLetter(contact: Contact, field: ContactSortField): String {
    val primary = when (field) {
        ContactSortField.FIRST_NAME -> contact.firstName.ifEmpty { contact.lastName }
        ContactSortField.LAST_NAME  -> contact.lastName.ifEmpty { contact.firstName }
    }
    return primary.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
}

/** Полное имя контакта в выбранном формате отображения (DISPLAY_ORDER),
 *  включая отчество (middleName) — как в ContactHeader (ContactDetailScreen.kt). */
fun formatContactName(contact: Contact, format: ContactNameFormat): String = when (format) {
    ContactNameFormat.FIRST_NAME_FIRST -> listOfNotNull(
        contact.firstName.takeIf { it.isNotBlank() },
        contact.middleName?.takeIf { it.isNotBlank() },
        contact.lastName.takeIf { it.isNotBlank() }
    )
    ContactNameFormat.LAST_NAME_FIRST -> listOfNotNull(
        contact.lastName.takeIf { it.isNotBlank() },
        contact.firstName.takeIf { it.isNotBlank() },
        contact.middleName?.takeIf { it.isNotBlank() }
    )
}.joinToString(" ")

// ФИКС (аудит 2026-07-06): раньше был параметр connectionLevels — фильтр по
// LEGACY-полю Contact.connectionLevel (слито в ContactStatus, см. Enums.kt).
// В UI (ContactsScreen.kt) для него никогда не было чипов/секции — всегда
// пустой Set, реального эффекта не имел. Само поле в модели/БД не трогаем
// (сохранность данных), убран только неиспользуемый параметр фильтра.
fun List<Contact>.applyContactFilters(
    query: String,
    relationshipTypes: Set<RelationshipType>,
    importanceLevels: Set<ImportanceLevel>,
    communicationRhythms: Set<CommunicationRhythm>,
    contactStatuses: Set<ContactStatus> = emptySet(),
    cityFilter: String,
    tagFilter: String = "",
    groupIds: Set<String> = emptySet(),
    // Свои типы отношений («статусы») — раньше не участвовали в фильтре вообще,
    // т.к. relationshipType у таких контактов = OTHER, и OTHER не было чипом
    // (фидбэк владельца: «создал свой статус — не входит в фильтры»).
    customRelTypes: Set<String> = emptySet(),
    // Новая управляемая система тегов (Entity Tag с id/category, см. AppStateStore) —
    // НЕ путать с tagFilter выше (легаси свободный текст Contact.tags).
    tagIds: Set<String> = emptySet(),
    tagCategories: Set<String> = emptySet(),
    sortOrder: ContactSortOrder,
    nameSortField: ContactSortField = ContactSortField.FIRST_NAME
): List<Contact> {
    var list = this

    // Text search via engine
    if (query.isNotBlank()) {
        val matchIds = SearchEngine.searchContacts(query).map { it.contact.id }.toSet()
        list = list.filter { it.id in matchIds }
    }

    // Стандартный тип ИЛИ свой тип — если активен хотя бы один список, матчим
    // по любому из них (иначе выбор своего типа + стандартного одновременно
    // исключил бы всех контактов вместо объединения выборок).
    // Главный тип ИЛИ любой из второстепенных (Contact.secondaryRelationshipTypes,
    // решение владельца 2026-07-23: контакт проходит фильтр по типу отношений,
    // если фильтр совпадает с главным типом или с любым второстепенным).
    if (relationshipTypes.isNotEmpty() || customRelTypes.isNotEmpty()) {
        list = list.filter { c ->
            (relationshipTypes.isNotEmpty() &&
                (c.relationshipType in relationshipTypes || c.secondaryRelationshipTypes.any { it in relationshipTypes })) ||
            (customRelTypes.isNotEmpty() && c.customRelationshipType in customRelTypes)
        }
    }
    if (importanceLevels.isNotEmpty())
        list = list.filter { it.importanceLevel in importanceLevels }
    if (communicationRhythms.isNotEmpty())
        list = list.filter { it.communicationRhythm in communicationRhythms }
    if (contactStatuses.isNotEmpty())
        list = list.filter { it.contactStatus in contactStatuses }
    if (cityFilter.isNotBlank()) {
        val qCity = cityFilter.searchNormalize()
        list = list.filter { contact ->
            AppStateStore.addresses.any {
                it.ownerId == contact.id &&
                it.ownerType == AddressOwnerType.CONTACT &&
                it.city.searchNormalize().contains(qCity)
            }
        }
    }
    if (tagFilter.isNotBlank()) {
        val qTag = tagFilter.searchNormalize()
        list = list.filter { contact ->
            contact.tags.any { it.searchNormalize().contains(qTag) }
        }
    }
    if (groupIds.isNotEmpty()) {
        val memberIds = AppStateStore.groupMembers
            .filter { it.groupId in groupIds }
            .map { it.contactId }
            .toSet()
        list = list.filter { it.id in memberIds }
    }
    // Новая система тегов: контакт проходит, если (tagIds пуст ИЛИ есть хотя бы
    // один тег из tagIds) И (tagCategories пуст ИЛИ есть тег из хотя бы одной
    // выбранной категории) — union внутри каждого набора, AND между наборами.
    if (tagIds.isNotEmpty()) {
        val memberIds = tagIds.flatMap { AppStateStore.contactIdsWithTag(it) }.toSet()
        list = list.filter { it.id in memberIds }
    }
    if (tagCategories.isNotEmpty()) {
        val memberIds = tagCategories.flatMap { AppStateStore.contactIdsWithCategory(it) }.toSet()
        list = list.filter { it.id in memberIds }
    }

    return when (sortOrder) {
        ContactSortOrder.NAME_AZ        -> list.sortedBy { contactSortKey(it, nameSortField) }
        ContactSortOrder.NAME_ZA        -> list.sortedByDescending { contactSortKey(it, nameSortField) }
        ContactSortOrder.RECENTLY_ADDED -> list.sortedByDescending { it.createdAt }
        ContactSortOrder.IMPORTANCE     -> list.sortedByDescending {
            when (it.importanceLevel) { ImportanceLevel.KEY -> 2; ImportanceLevel.IMPORTANT -> 1; else -> 0 }
        }
    }
}

fun List<Company>.applyCompanyFilters(
    query: String,
    industries: Set<Industry>,
    cityFilter: String,
    sortOrder: CompanySortOrder
): List<Company> {
    var list = this

    if (query.isNotBlank()) {
        val matchIds = SearchEngine.searchCompanies(query).map { it.company.id }.toSet()
        list = list.filter { it.id in matchIds }
    }

    if (industries.isNotEmpty())
        list = list.filter { it.industry in industries }

    if (cityFilter.isNotBlank()) {
        list = list.filter { company ->
            AppStateStore.addresses.any {
                it.ownerId == company.id &&
                it.ownerType == AddressOwnerType.COMPANY &&
                it.city.contains(cityFilter, ignoreCase = true)
            }
        }
    }

    return when (sortOrder) {
        CompanySortOrder.NAME_AZ        -> list.sortedBy { it.name.lowercase() }
        CompanySortOrder.NAME_ZA        -> list.sortedByDescending { it.name.lowercase() }
        CompanySortOrder.MOST_CONTACTS  -> list.sortedByDescending {
            AppStateStore.companyRelations.count { r -> r.companyId == it.id }
        }
        CompanySortOrder.RECENTLY_ADDED -> list.sortedByDescending { it.createdAt }
    }
}
