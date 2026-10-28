package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.Company

/** Слияние дублей КОМПАНИЙ — точный аналог ContactMerge.kt (см. комментарии
 *  там подробно). Переиспользует те же типы решения пользователя
 *  (MergeChoiceField/MergeChoiceOption/MergeTextField/MergeResolution) —
 *  экран разрешения конфликтов может рисовать оба случая одним кодом.
 *  Тот же принцип: списочные поля (телефоны/email/адреса/сотрудники)
 *  объединяются молча (union с дедупом), одиночный свободный текст
 *  (name/description/website) комбинируется через " / " при расхождении,
 *  enum/фото поля (industry/logoUri) физически одноместные — требуют явного
 *  выбора одного значения, что не выбрано — уходит текстом в авто-заметку
 *  (см. mergeCompanies/MergeResolveScreen). Ничего не пропадает молча. */

internal val COMPANY_TEXT_MERGE_FIELDS: List<Pair<String, (Company) -> String?>> = listOf(
    "name" to { it.name },
    "description" to { it.description },
    "website" to { it.website }
)

data class CompanyMergePreview(
    val companyIds: List<String>,
    val choiceFields: List<MergeChoiceField>,
    val textFields: List<MergeTextField>,
    /** label-ключ → количество записей у каждой компании по порядку companyIds,
     *  для read-only summary «будет объединено» (точный union считает mergeCompanies).
     *  "employees" (ContactCompanyRelation) сюда НЕ входит — Company не хранит
     *  их как поле модели (глобальный список по companyId в AppStateStore),
     *  точный подсчёт — на стороне экрана, у которого есть доступ к этому списку. */
    val listCounts: Map<String, List<Int>>
)

/**
 * Разбирает набор из 2-3 компаний на «что объединится молча» (списки),
 * «что можно объединить текстом» (name/description/website) и «что требует
 * явного выбора одного варианта» (industry, logoUri — компания физически не
 * может хранить два значения в одном поле).
 */
fun computeCompanyMergePreview(companies: List<Company>): CompanyMergePreview {
    val ids = companies.map { it.id }
    val choiceFields = mutableListOf<MergeChoiceField>()

    run {
        val opts = companies.mapNotNull { c ->
            c.logoUri?.takeIf { it.isNotBlank() }?.let { MergeChoiceOption(c.id, it) }
        }
        if (opts.map { it.displayValue }.distinct().size > 1) {
            choiceFields.add(MergeChoiceField("logoUri", MergeFieldKind.PHOTO, opts))
        }
    }

    fun addEnumField(key: String, getter: (Company) -> Enum<*>) {
        val opts = companies.map { c -> MergeChoiceOption(c.id, getter(c).name) }
        if (opts.map { it.displayValue }.distinct().size > 1) {
            choiceFields.add(MergeChoiceField(key, MergeFieldKind.ENUM, opts))
        }
    }
    addEnumField("industry") { it.industry }

    val textFields = COMPANY_TEXT_MERGE_FIELDS.mapNotNull { (key, getter) ->
        val vals = companies.mapNotNull { c ->
            getter(c)?.trim()?.takeIf { it.isNotBlank() }?.let { c.id to it }
        }
        val distinct = vals.map { it.second.trim().lowercase() }.distinct()
        if (distinct.size > 1) MergeTextField(key, vals) else null
    }

    val listCounts = mapOf(
        "phones" to companies.map { it.phones.size },
        "emails" to companies.map { it.emails.size },
        "addresses" to companies.map { it.addresses.size }
    )

    return CompanyMergePreview(ids, choiceFields, textFields, listCounts)
}
