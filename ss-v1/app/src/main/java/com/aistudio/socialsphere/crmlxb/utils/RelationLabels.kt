package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.R

// ФИКС (2026-07-12, фидбэк владельца: формулировка «Кто я для него» буквально
// неверна, когда владелец ведёт карточки не про себя, а про третьих лиц —
// например у клиента есть жена, обе карточки чужие). Раньше здесь было 2-3
// расходящиеся копии (OverviewTab.kt дважды, ContactEditScreen.kt отдельно, без
// локализации) с разным поведением — теперь один источник для подписи роли,
// авто-инверсии и формулировки шага выбора роли.

/** Локализованная подпись роли родства/связи; неизвестный (легаси/будущий)
 *  ключ показываем как есть — не роняем экран на незнакомой строке. */
val ROLE_LABEL_RES: Map<String, Int> = mapOf(
    "Жена" to R.string.role_wife,
    "Муж" to R.string.role_husband,
    "Партнёр" to R.string.lbl_relationship_type_partner,
    "Мать" to R.string.role_mother,
    "Отец" to R.string.role_father,
    "Сын" to R.string.role_son,
    "Дочь" to R.string.role_daughter,
    "Брат" to R.string.role_brother,
    "Сестра" to R.string.role_sister,
    "Родственник" to R.string.role_relative,
    "Друг" to R.string.lbl_relationship_type_friend,
    "Подруга" to R.string.role_girlfriend,
    "Коллега" to R.string.lbl_relationship_type_colleague,
    "Знакомый" to R.string.lbl_relationship_type_acquaintance,
    "Сосед" to R.string.lbl_relationship_type_neighbor,
    "Одноклассник" to R.string.role_classmate,
    "Однокурсник" to R.string.role_coursemate,
    "Партнёр по бизнесу" to R.string.role_business_partner,
    "Наставник" to R.string.role_mentor,
    "Клиент" to R.string.lbl_relationship_type_client
)

fun relationRoleLabel(context: android.content.Context, role: String): String =
    ROLE_LABEL_RES[role]?.let { context.getString(it) } ?: role

/** Обратная роль по умолчанию — только предзаполнение, пользователь сразу
 *  видит оба поля и может поправить вручную (в отличие от импорта, см.
 *  ImportScreens.kt:inverseImportedRole — там намеренно консервативнее, потому
 *  что там нет немедленного шанса на правку). */
fun inverseRelationRole(role: String): String = when (role) {
    "Жена" -> "Муж"
    "Муж" -> "Жена"
    "Партнёр", "Друг", "Коллега", "Подруга", "Знакомый", "Сосед",
    "Одноклассник", "Однокурсник", "Партнёр по бизнесу", "Наставник", "Клиент" -> role
    else -> "Родственник"
}

/** Подпись шага выбора роли с явными именами обоих контактов вместо
 *  двусмысленного «я» — однозначна независимо от того, ведёт ли владелец
 *  карточку про себя или про третьих лиц. [subject] — тот, чья роль
 *  выбирается, [relativeTo] — по отношению к кому. */
fun roleOfLabel(context: android.content.Context, subject: String, relativeTo: String): String =
    context.getString(R.string.ce_who_is_to, subject, relativeTo)
