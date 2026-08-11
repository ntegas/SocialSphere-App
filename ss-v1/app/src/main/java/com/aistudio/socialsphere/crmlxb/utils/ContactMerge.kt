package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.Contact

/** Как отображать вариант конфликтующего поля в per-field экране слияния. */
enum class MergeFieldKind { ENUM, PHOTO }

data class MergeChoiceOption(val contactId: String, val displayValue: String)

/** Поле, которое физически не может хранить больше одного значения (enum-поля
 *  модели, фото) — здесь пользователь ОБЯЗАН выбрать одно значение как живое
 *  поле контакта. Не выбранные варианты не выбрасываются молча: экран слияния
 *  сохраняет их текстом в авто-заметку контакта (см. MergeResolveScreen). */
data class MergeChoiceField(val key: String, val kind: MergeFieldKind, val options: List<MergeChoiceOption>)

/** Поле, которое МОЖЕТ хранить объединённый текст — по умолчанию все
 *  расходящиеся значения складываются через " / ", пользователь лишь решает,
 *  чьи значения включить. Ничего не пропадает, даже если не трогать выбор:
 *  владелец правит итоговую строку сам после слияния. */
data class MergeTextField(val key: String, val values: List<Pair<String, String>>)

data class MergePreview(
    val contactIds: List<String>,
    val choiceFields: List<MergeChoiceField>,
    val textFields: List<MergeTextField>,
    /** label-ключ → количество записей у каждого контакта по порядку contactIds,
     *  для read-only summary «будет объединено» (точный union считает mergeContacts). */
    val listCounts: Map<String, List<Int>>
)

/** Выбор пользователя. choiceWinners — обязательный per-field выбор для полей
 *  из choiceFields (enum/фото). textIncluded — какие контакты участвуют в
 *  склейке текстовых полей; поля, отсутствующие в картах, разрешаются
 *  автоматически (первое непустое / все значения через " / "). */
data class MergeResolution(
    val choiceWinners: Map<String, String> = emptyMap(),
    val textIncluded: Map<String, Set<String>> = emptyMap()
)

/** Текстовые (комбинируемые) поля — включает структуру имени и дату
 *  знакомства: раньше это были поля «выбери одно», из-за чего при конфликте
 *  (например, у одного контакта только имя, у другого — имя и фамилия из
 *  другого написания) один вариант терялся насовсем. Теперь, как и со
 *  свободным текстом, ничего не пропадает — при расхождении оба значения
 *  остаются рядом через " / ", владелец сам решает при редактировании. */
internal val TEXT_MERGE_FIELDS: List<Pair<String, (Contact) -> String?>> = listOf(
    "firstName" to { it.firstName },
    "lastName" to { it.lastName },
    "middleName" to { it.middleName },
    "namePrefix" to { it.namePrefix },
    "nameSuffix" to { it.nameSuffix },
    "phoneticFirstName" to { it.phoneticFirstName },
    "phoneticMiddleName" to { it.phoneticMiddleName },
    "phoneticLastName" to { it.phoneticLastName },
    "meetDate" to { it.meetDate },
    "nickname" to { it.nickname },
    "profession" to { it.profession },
    "nextStep" to { it.nextStep },
    "canHelpWith" to { it.canHelpWith },
    "iCanHelpWith" to { it.iCanHelpWith },
    "talkingPoints" to { it.talkingPoints },
    "meetContext" to { it.meetContext },
    "familyNote" to { it.familyNote },
    // Размеры (SizeInfo) — раньше выбирался целый объект одного контакта,
    // подполя другого терялись целиком. Теперь каждое подполе — обычное
    // комбинируемое текстовое поле, тем же путём, что и остальной текст.
    "sizeClothing" to { it.sizeInfo?.clothingSize },
    "sizeShoe" to { it.sizeInfo?.shoeSize },
    "sizeRing" to { it.sizeInfo?.ringSize },
    "sizeOther" to { it.sizeInfo?.other }
)

/** Разбирает набор из 2-3 контактов на «что объединится молча» (списки),
 *  «что можно объединить текстом» (имя/фамилия/свободный текст/размеры) и
 *  «что требует явного выбора одного варианта» (enum-поля модели, фото —
 *  контакт физически не может хранить два значения в одном поле).
 *  customRelationshipType и deviceContactId сознательно НЕ включены сюда:
 *  первое — известный план переделки в список типов (см. TODO в Models.kt),
 *  второе — внутренний id синхронизации с телефонной книгой, бессмысленный
 *  для показа человеку как «выберите А или Б».
 */
fun computeMergePreview(contacts: List<Contact>): MergePreview {
    val ids = contacts.map { it.id }
    val choiceFields = mutableListOf<MergeChoiceField>()

    run {
        val opts = contacts.mapNotNull { c ->
            c.photoUri?.takeIf { it.isNotBlank() }?.let { MergeChoiceOption(c.id, it) }
        }
        if (opts.map { it.displayValue }.distinct().size > 1) {
            choiceFields.add(MergeChoiceField("photoUri", MergeFieldKind.PHOTO, opts))
        }
    }

    fun addEnumField(key: String, getter: (Contact) -> Enum<*>) {
        val opts = contacts.map { c -> MergeChoiceOption(c.id, getter(c).name) }
        if (opts.map { it.displayValue }.distinct().size > 1) {
            choiceFields.add(MergeChoiceField(key, MergeFieldKind.ENUM, opts))
        }
    }
    addEnumField("relationshipType") { it.relationshipType }
    addEnumField("importanceLevel") { it.importanceLevel }
    addEnumField("socialRole") { it.socialRole }
    addEnumField("communicationRhythm") { it.communicationRhythm }
    addEnumField("contactStatus") { it.contactStatus }

    val textFields = TEXT_MERGE_FIELDS.mapNotNull { (key, getter) ->
        val vals = contacts.mapNotNull { c ->
            getter(c)?.trim()?.takeIf { it.isNotBlank() }?.let { c.id to it }
        }
        val distinct = vals.map { it.second.trim().lowercase() }.distinct()
        if (distinct.size > 1) MergeTextField(key, vals) else null
    }

    val listCounts = mapOf(
        "phones" to contacts.map { it.phones.size },
        "emails" to contacts.map { it.emails.size },
        "messengers" to contacts.map { it.messengers.size },
        "addresses" to contacts.map { it.addresses.size },
        "companyRelations" to contacts.map { it.companyRelations.size },
        "personalDetails" to contacts.map { it.personalDetails.size },
        "tags" to contacts.map { it.tags.size },
        "notes" to contacts.map { it.notes.size },
        "gifts" to contacts.map { it.gifts.size }
    )

    return MergePreview(ids, choiceFields, textFields, listCounts)
}
