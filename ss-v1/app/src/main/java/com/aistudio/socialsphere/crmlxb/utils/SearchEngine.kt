package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*

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

    /** Global search across contacts + companies. Returns top-N results sorted by score. */
    fun globalSearch(query: String, limit: Int = 20): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val results = mutableListOf<SearchResult>()
        results += searchContacts(q)
        results += searchCompanies(q)
        return results.sortedByDescending {
            when (it) {
                is SearchResult.ContactResult -> it.score
                is SearchResult.CompanyResult -> it.score
            }
        }.take(limit)
    }

    /** Search only contacts with rich field matching. */
    fun searchContacts(query: String): List<SearchResult.ContactResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return AppStateStore.contacts.mapNotNull { contact ->
            scoreContact(contact, q)
        }.sortedByDescending { it.score }
    }

    /** Search only companies. */
    fun searchCompanies(query: String): List<SearchResult.CompanyResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
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
        ).joinToString(" ").lowercase()
        if (fullName.startsWith(q))          { score += 100; matchField = "Имя" }
        else if (fullName.contains(q))       { score += 80;  matchField = "Имя" }
        else if (contact.firstName.lowercase().contains(q)) { score += 75; matchField = "Имя" }
        else if (contact.lastName.lowercase().contains(q))  { score += 75; matchField = "Фамилия" }
        else if (contact.middleName?.lowercase()?.contains(q) == true) { score += 70; matchField = "Отчество" }

        // Прозвище — вес как у имени (фидбэк владельца: «любимое» должно находиться)
        if (contact.nickname?.lowercase()?.contains(q) == true) { score += 75; matchField = "Прозвище" }

        // Phone
        contact.phones.forEach { p ->
            if (p.number.contains(q)) { score += 70; matchField = "Телефон" }
        }
        // Email
        contact.emails.forEach { e ->
            if (e.email.lowercase().contains(q)) { score += 65; matchField = "Email" }
        }
        // Messenger username
        contact.messengers.forEach { m ->
            if (m.value.lowercase().contains(q)) { score += 60; matchField = m.type.labelKey() }
        }
        // Company name / position
        contact.companyRelations.forEach { rel ->
            val comp = AppStateStore.getCompany(rel.companyId)
            if (comp?.name?.lowercase()?.contains(q) == true) { score += 55; matchField = "Компания" }
            if (rel.position?.lowercase()?.contains(q) == true) { score += 50; matchField = "Должность" }
            if (rel.department?.lowercase()?.contains(q) == true) { score += 40; matchField = "Отдел" }
        }
        // City
        AppStateStore.addresses
            .filter { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
            .forEach { a ->
                if (a.city.lowercase().contains(q))    { score += 45; matchField = "Город" }
                if (a.country.lowercase().contains(q)) { score += 40; matchField = "Страна" }
            }
        // Notes
        contact.notes.forEach { n ->
            if (n.text.lowercase().contains(q)) { score += 30; matchField = "Заметка" }
        }
        // Personal details (likes/dislikes/interests)
        contact.personalDetails.forEach { pd ->
            if (pd.value.lowercase().contains(q)) { score += 25; matchField = pd.category.labelKey() }
        }
        // Gifts
        contact.gifts.forEach { g ->
            if (g.title.lowercase().contains(q)) { score += 20; matchField = "Подарок" }
        }
        // Теги (фидбэк владельца: раньше не искались вообще)
        contact.tags.forEach { t ->
            if (t.lowercase().contains(q)) { score += 55; matchField = "Тег" }
        }
        // Группы (фидбэк 2026-07-04: «группы должны находиться в поиске»)
        AppStateStore.groupMembers
            .filter { it.contactId == contact.id }
            .forEach { m ->
                val g = AppStateStore.groups.firstOrNull { it.id == m.groupId } ?: return@forEach
                if (g.name.lowercase().contains(q)) { score += 55; matchField = "Группа" }
            }
        // Свой тип отношений / где познакомились / следующий шаг
        if (contact.customRelationshipType?.lowercase()?.contains(q) == true) { score += 40; matchField = "Тип" }
        if (contact.nextStep?.lowercase()?.contains(q) == true) { score += 25; matchField = "След. шаг" }
        // Тип отношений (стандартный лейбл)
        if (contact.relationshipType.labelKey().lowercase().contains(q)) { score += 15; matchField = "Тип" }

        // Заметки контакта из общего стора (contact.notes на собранном объекте
        // может быть пустым — заметки живут в AppStateStore.notes)
        if (matchField.isEmpty()) {
            AppStateStore.notes.firstOrNull { it.contactId == contact.id && it.text.lowercase().contains(q) }
                ?.let { score += 30; matchField = "Заметка" }
        }

        return if (score > 0) SearchResult.ContactResult(contact, matchField, score) else null
    }

    // ── Company scoring ────────────────────────────────────────
    private fun scoreCompany(company: Company, q: String): SearchResult.CompanyResult? {
        var score = 0
        var matchField = ""

        if (company.name.lowercase().startsWith(q))  { score += 100; matchField = "Название" }
        else if (company.name.lowercase().contains(q)){ score += 80;  matchField = "Название" }

        if (company.industry.labelKey().lowercase().contains(q)) { score += 60; matchField = "Индустрия" }
        if (company.description?.lowercase()?.contains(q) == true) { score += 40; matchField = "Описание" }
        if (company.website?.lowercase()?.contains(q) == true) { score += 30; matchField = "Сайт" }

        AppStateStore.addresses
            .filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
            .forEach { a ->
                if (a.city.lowercase().contains(q))    { score += 45; matchField = "Город" }
                if (a.country.lowercase().contains(q)) { score += 35; matchField = "Страна" }
            }

        // People in the company
        AppStateStore.companyRelations.filter { it.companyId == company.id }.forEach { rel ->
            val c = AppStateStore.getContact(rel.contactId) ?: return@forEach
            val name = "${c.firstName} ${c.lastName}".lowercase()
            if (name.contains(q)) { score += 35; matchField = "Сотрудник" }
            if (rel.position?.lowercase()?.contains(q) == true) { score += 25; matchField = "Должность" }
        }

        return if (score > 0) SearchResult.CompanyResult(company, matchField, score) else null
    }
}

// ─── Contact filter helpers ───────────────────────────────────
enum class ContactSortOrder { NAME_AZ, NAME_ZA, RECENTLY_ADDED, IMPORTANCE }
enum class CompanySortOrder { NAME_AZ, NAME_ZA, MOST_CONTACTS, RECENTLY_ADDED }

fun List<Contact>.applyContactFilters(
    query: String,
    relationshipTypes: Set<RelationshipType>,
    importanceLevels: Set<ImportanceLevel>,
    connectionLevels: Set<ConnectionLevel>,
    communicationRhythms: Set<CommunicationRhythm>,
    contactStatuses: Set<ContactStatus> = emptySet(),
    cityFilter: String,
    tagFilter: String = "",
    groupIds: Set<String> = emptySet(),
    sortOrder: ContactSortOrder
): List<Contact> {
    var list = this

    // Text search via engine
    if (query.isNotBlank()) {
        val matchIds = SearchEngine.searchContacts(query).map { it.contact.id }.toSet()
        list = list.filter { it.id in matchIds }
    }

    if (relationshipTypes.isNotEmpty())
        list = list.filter { it.relationshipType in relationshipTypes }
    if (importanceLevels.isNotEmpty())
        list = list.filter { it.importanceLevel in importanceLevels }
    if (connectionLevels.isNotEmpty())
        list = list.filter { it.connectionLevel in connectionLevels }
    if (communicationRhythms.isNotEmpty())
        list = list.filter { it.communicationRhythm in communicationRhythms }
    if (contactStatuses.isNotEmpty())
        list = list.filter { it.contactStatus in contactStatuses }
    if (cityFilter.isNotBlank()) {
        list = list.filter { contact ->
            AppStateStore.addresses.any {
                it.ownerId == contact.id &&
                it.ownerType == AddressOwnerType.CONTACT &&
                it.city.contains(cityFilter, ignoreCase = true)
            }
        }
    }
    if (tagFilter.isNotBlank()) {
        list = list.filter { contact ->
            contact.tags.any { it.contains(tagFilter, ignoreCase = true) }
        }
    }
    if (groupIds.isNotEmpty()) {
        val memberIds = AppStateStore.groupMembers
            .filter { it.groupId in groupIds }
            .map { it.contactId }
            .toSet()
        list = list.filter { it.id in memberIds }
    }

    return when (sortOrder) {
        ContactSortOrder.NAME_AZ        -> list.sortedBy { "${it.firstName} ${it.lastName}".lowercase() }
        ContactSortOrder.NAME_ZA        -> list.sortedByDescending { "${it.firstName} ${it.lastName}".lowercase() }
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
