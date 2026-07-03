package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.R

fun RelationshipType.label(context: android.content.Context): String = when (this) {
    RelationshipType.FAMILY -> context.getString(R.string.lbl_relationship_type_family)
    RelationshipType.FRIEND -> context.getString(R.string.lbl_relationship_type_friend)
    RelationshipType.ACQUAINTANCE -> context.getString(R.string.lbl_relationship_type_acquaintance)
    RelationshipType.COLLEAGUE -> context.getString(R.string.lbl_relationship_type_colleague)
    RelationshipType.CLIENT -> context.getString(R.string.lbl_relationship_type_client)
    RelationshipType.PARTNER -> context.getString(R.string.lbl_relationship_type_partner)
    RelationshipType.SUPPLIER -> context.getString(R.string.lbl_relationship_type_supplier)
    RelationshipType.NEIGHBOR -> context.getString(R.string.lbl_relationship_type_neighbor)
    RelationshipType.DOCTOR -> context.getString(R.string.lbl_relationship_type_doctor)
    RelationshipType.TEACHER -> context.getString(R.string.lbl_relationship_type_teacher)
    RelationshipType.OTHER -> context.getString(R.string.lbl_relationship_type_other)
}

fun RelationshipType.labelKey(): String = when (this) {
    RelationshipType.FAMILY -> "Семья"
    RelationshipType.FRIEND -> "Друг"
    RelationshipType.ACQUAINTANCE -> "Знакомый"
    RelationshipType.COLLEAGUE -> "Коллега"
    RelationshipType.CLIENT -> "Клиент"
    RelationshipType.PARTNER -> "Партнёр"
    RelationshipType.SUPPLIER -> "Поставщик"
    RelationshipType.NEIGHBOR -> "Сосед"
    RelationshipType.DOCTOR -> "Врач"
    RelationshipType.TEACHER -> "Учитель"
    RelationshipType.OTHER -> "Другое"
}

fun ImportanceLevel.label(context: android.content.Context): String = when (this) {
    ImportanceLevel.NORMAL -> context.getString(R.string.lbl_importance_level_normal)
    ImportanceLevel.IMPORTANT -> context.getString(R.string.lbl_importance_level_important)
    ImportanceLevel.KEY -> context.getString(R.string.lbl_importance_level_key)
}

fun ImportanceLevel.labelKey(): String = when (this) {
    ImportanceLevel.NORMAL -> "Обычный"
    ImportanceLevel.IMPORTANT -> "Важный"
    ImportanceLevel.KEY -> "Ключевой"
}

fun ConnectionLevel.label(context: android.content.Context): String = when (this) {
    ConnectionLevel.CLOSE -> context.getString(R.string.lbl_connection_level_close)
    ConnectionLevel.NORMAL -> context.getString(R.string.lbl_connection_level_normal)
    ConnectionLevel.WEAK -> context.getString(R.string.lbl_connection_level_weak)
    ConnectionLevel.NEW -> context.getString(R.string.lbl_connection_level_new)
    ConnectionLevel.ARCHIVED -> context.getString(R.string.lbl_connection_level_archived)
}

fun ConnectionLevel.labelKey(): String = when (this) {
    ConnectionLevel.CLOSE -> "Близкий"
    ConnectionLevel.NORMAL -> "Обычный"
    ConnectionLevel.WEAK -> "Слабый"
    ConnectionLevel.NEW -> "Новый"
    ConnectionLevel.ARCHIVED -> "Архив"
}

fun SocialRole.label(context: android.content.Context): String = when (this) {
    SocialRole.REGULAR -> context.getString(R.string.lbl_social_role_regular)
    SocialRole.CONNECTOR -> context.getString(R.string.lbl_social_role_connector)
    SocialRole.KNOWS_MANY_PEOPLE -> context.getString(R.string.lbl_social_role_knows_many_people)
    SocialRole.CAN_INTRODUCE -> context.getString(R.string.lbl_social_role_can_introduce)
    SocialRole.EXPERT -> context.getString(R.string.lbl_social_role_expert)
    SocialRole.ADVISOR -> context.getString(R.string.lbl_social_role_advisor)
    SocialRole.LOCAL_CONTACT -> context.getString(R.string.lbl_social_role_local_contact)
}

fun SocialRole.labelKey(): String = when (this) {
    SocialRole.REGULAR -> "Обычный"
    SocialRole.CONNECTOR -> "Коннектор"
    SocialRole.KNOWS_MANY_PEOPLE -> "Много знает"
    SocialRole.CAN_INTRODUCE -> "Может познакомить"
    SocialRole.EXPERT -> "Эксперт"
    SocialRole.ADVISOR -> "Советник"
    SocialRole.LOCAL_CONTACT -> "Местный"
}

fun CommunicationRhythm.label(context: android.content.Context): String = when (this) {
    CommunicationRhythm.NOT_TRACKED -> context.getString(R.string.lbl_communication_rhythm_not_tracked)
    CommunicationRhythm.WEEKLY -> context.getString(R.string.lbl_communication_rhythm_weekly)
    CommunicationRhythm.MONTHLY -> context.getString(R.string.lbl_communication_rhythm_monthly)
    CommunicationRhythm.EVERY_3_MONTHS -> context.getString(R.string.lbl_communication_rhythm_every_3_months)
    CommunicationRhythm.EVERY_6_MONTHS -> context.getString(R.string.lbl_communication_rhythm_every_6_months)
    CommunicationRhythm.YEARLY -> context.getString(R.string.lbl_communication_rhythm_yearly)
    CommunicationRhythm.CUSTOM -> context.getString(R.string.lbl_communication_rhythm_custom)
}

fun CommunicationRhythm.labelKey(): String = when (this) {
    CommunicationRhythm.NOT_TRACKED -> "Не отслеживать"
    CommunicationRhythm.WEEKLY -> "Раз в неделю"
    CommunicationRhythm.MONTHLY -> "Раз в месяц"
    CommunicationRhythm.EVERY_3_MONTHS -> "Раз в 3 мес."
    CommunicationRhythm.EVERY_6_MONTHS -> "Раз в 6 мес."
    CommunicationRhythm.YEARLY -> "Раз в год"
    CommunicationRhythm.CUSTOM -> "Настроить"
}

fun EmploymentStatus.label(context: android.content.Context): String = when (this) {
    EmploymentStatus.CURRENT -> context.getString(R.string.lbl_employment_status_current)
    EmploymentStatus.FORMER -> context.getString(R.string.lbl_employment_status_former)
    EmploymentStatus.UNKNOWN -> context.getString(R.string.lbl_employment_status_unknown)
}

fun EmploymentStatus.labelKey(): String = when (this) {
    EmploymentStatus.CURRENT -> "Текущее"
    EmploymentStatus.FORMER -> "Бывшее"
    EmploymentStatus.UNKNOWN -> "Неизвестно"
}

fun CalendarItemType.label(context: android.content.Context): String = when (this) {
    CalendarItemType.BIRTHDAY -> context.getString(R.string.lbl_calendar_item_type_birthday)
    CalendarItemType.ANNIVERSARY -> context.getString(R.string.lbl_calendar_item_type_anniversary)
    CalendarItemType.NAMEDAY -> context.getString(R.string.lbl_calendar_item_type_nameday)
    CalendarItemType.IMPORTANT_DATE -> context.getString(R.string.lbl_calendar_item_type_important_date)
    CalendarItemType.MEETING -> context.getString(R.string.lbl_calendar_item_type_meeting)
    CalendarItemType.CALL -> context.getString(R.string.lbl_calendar_item_type_call)
    CalendarItemType.MESSAGE -> context.getString(R.string.lbl_calendar_item_type_message)
    CalendarItemType.GIFT -> context.getString(R.string.lbl_calendar_item_type_gift)
    CalendarItemType.TASK -> context.getString(R.string.lbl_calendar_item_type_task)
    CalendarItemType.NOTE -> context.getString(R.string.lbl_calendar_item_type_note)
    CalendarItemType.COMPANY_EVENT -> context.getString(R.string.lbl_calendar_item_type_company_event)
    CalendarItemType.CUSTOM -> context.getString(R.string.lbl_calendar_item_type_custom)
}

fun CalendarItemType.labelKey(): String = when (this) {
    CalendarItemType.BIRTHDAY -> "День рождения"
    CalendarItemType.ANNIVERSARY -> "Годовщина"
    CalendarItemType.NAMEDAY -> "Именины"
    CalendarItemType.IMPORTANT_DATE -> "Важная дата"
    CalendarItemType.MEETING -> "Встреча"
    CalendarItemType.CALL -> "Звонок"
    CalendarItemType.MESSAGE -> "Сообщение"
    CalendarItemType.GIFT -> "Подарок"
    CalendarItemType.TASK -> "Задача"
    CalendarItemType.NOTE -> "Заметка"
    CalendarItemType.COMPANY_EVENT -> "Событие компании"
    CalendarItemType.CUSTOM -> "Другое"
}

/**
 * Заголовок события для показа. Если в title лежит generic-имя типа
 * (например «День рождения» == BIRTHDAY.labelKey()), показываем
 * локализованную метку типа; пользовательские заголовки (встречи, задачи)
 * остаются как есть. Так авто-события переводятся при смене языка, а
 * введённые пользователем тексты не подменяются.
 */
fun calendarDisplayTitle(
    title: String,
    type: CalendarItemType,
    context: android.content.Context
): String {
    val key = type.labelKey().trim()
    val t = title.trim()
    return when {
        // Заголовок не задан — показываем локализованное название типа.
        t.isEmpty() -> type.label(context)
        // Заголовок = русское название типа («День рождения», «Встреча»...) —
        // переводим целиком.
        t.equals(key, ignoreCase = true) -> type.label(context)
        // Заголовок начинается с названия типа + разделитель/имя
        // («День рождения Иван», «Встреча · Бюджет») — переводим только
        // префикс типа, остальное (имя/детали) оставляем как есть.
        t.startsWith(key, ignoreCase = true) &&
            t.length > key.length &&
            !t[key.length].isLetterOrDigit() ->
            type.label(context) + t.substring(key.length)
        // Пользовательский заголовок — не трогаем.
        else -> title
    }
}

fun com.aistudio.socialsphere.crmlxb.model.CalendarItem.displayTitle(
    context: android.content.Context
): String = calendarDisplayTitle(title, type, context)

fun NoteType.label(context: android.content.Context): String = when (this) {
    NoteType.GENERAL -> context.getString(R.string.lbl_note_type_general)
    NoteType.IMPORTANT_TO_REMEMBER -> context.getString(R.string.lbl_note_type_important_to_remember)
    NoteType.WORK -> context.getString(R.string.lbl_note_type_work)
    NoteType.PERSONAL_DETAIL -> context.getString(R.string.lbl_note_type_personal_detail)
    NoteType.GIFT -> context.getString(R.string.lbl_note_type_gift)
    NoteType.DATE_EVENT -> context.getString(R.string.lbl_note_type_date_event)
}

fun NoteType.labelKey(): String = when (this) {
    NoteType.GENERAL -> "Общая"
    NoteType.IMPORTANT_TO_REMEMBER -> "Важно помнить"
    NoteType.WORK -> "Рабочая"
    NoteType.PERSONAL_DETAIL -> "Личная деталь"
    NoteType.GIFT -> "Подарок"
    NoteType.DATE_EVENT -> "Дата/событие"
}

fun GiftStatus.label(context: android.content.Context): String = when (this) {
    GiftStatus.IDEA -> context.getString(R.string.lbl_gift_status_idea)
    GiftStatus.BOUGHT -> context.getString(R.string.lbl_gift_status_bought)
    GiftStatus.GIVEN -> context.getString(R.string.lbl_gift_status_given)
}

fun GiftStatus.labelKey(): String = when (this) {
    GiftStatus.IDEA -> "Идея"
    GiftStatus.BOUGHT -> "Куплено"
    GiftStatus.GIVEN -> "Подарено"
}

fun GiftStatus.icon(): String = when (this) {
    GiftStatus.IDEA  -> "✨"
    GiftStatus.BOUGHT -> "🛍️"
    GiftStatus.GIVEN -> "✅"
}

fun GiftStatus.next(): GiftStatus = when (this) {
    GiftStatus.IDEA  -> GiftStatus.BOUGHT
    GiftStatus.BOUGHT -> GiftStatus.GIVEN
    GiftStatus.GIVEN -> GiftStatus.GIVEN
}

fun Industry.label(context: android.content.Context): String = when (this) {
    Industry.IT -> context.getString(R.string.lbl_industry_it)
    Industry.AUTO -> context.getString(R.string.lbl_industry_auto)
    Industry.FINANCE -> context.getString(R.string.lbl_industry_finance)
    Industry.MEDICINE -> context.getString(R.string.lbl_industry_medicine)
    Industry.EDUCATION -> context.getString(R.string.lbl_industry_education)
    Industry.REAL_ESTATE -> context.getString(R.string.lbl_industry_real_estate)
    Industry.RETAIL -> context.getString(R.string.lbl_industry_retail)
    Industry.GOVERNMENT -> context.getString(R.string.lbl_industry_government)
    Industry.CONSTRUCTION -> context.getString(R.string.lbl_industry_construction)
    Industry.LOGISTICS -> context.getString(R.string.lbl_industry_logistics)
    Industry.MANUFACTURING -> context.getString(R.string.lbl_industry_manufacturing)
    Industry.HORECA -> context.getString(R.string.lbl_industry_horeca)
    Industry.LEGAL -> context.getString(R.string.lbl_industry_legal)
    Industry.MARKETING -> context.getString(R.string.lbl_industry_marketing)
    Industry.MEDIA -> context.getString(R.string.lbl_industry_media)
    Industry.BEAUTY -> context.getString(R.string.lbl_industry_beauty)
    Industry.SPORT -> context.getString(R.string.lbl_industry_sport)
    Industry.TOURISM -> context.getString(R.string.lbl_industry_tourism)
    Industry.ENERGY -> context.getString(R.string.lbl_industry_energy)
    Industry.AGRICULTURE -> context.getString(R.string.lbl_industry_agriculture)
    Industry.OTHER -> context.getString(R.string.lbl_industry_other)
}

fun Industry.labelKey(): String = when (this) {
    Industry.IT -> "IT"
    Industry.AUTO -> "Автомобили"
    Industry.FINANCE -> "Финансы"
    Industry.MEDICINE -> "Медицина"
    Industry.EDUCATION -> "Образование"
    Industry.REAL_ESTATE -> "Недвижимость"
    Industry.RETAIL -> "Розница"
    Industry.GOVERNMENT -> "Государство"
    Industry.CONSTRUCTION -> "Строительство"
    Industry.LOGISTICS -> "Логистика"
    Industry.MANUFACTURING -> "Производство"
    Industry.HORECA -> "Рестораны и отели"
    Industry.LEGAL -> "Юриспруденция"
    Industry.MARKETING -> "Маркетинг"
    Industry.MEDIA -> "Медиа"
    Industry.BEAUTY -> "Красота и мода"
    Industry.SPORT -> "Спорт"
    Industry.TOURISM -> "Туризм"
    Industry.ENERGY -> "Энергетика"
    Industry.AGRICULTURE -> "Сельское хозяйство"
    Industry.OTHER -> "Другое"
}

fun PersonalDetailCategory.label(context: android.content.Context): String = when (this) {
    PersonalDetailCategory.LIKES -> context.getString(R.string.lbl_personal_detail_category_likes)
    PersonalDetailCategory.DISLIKES -> context.getString(R.string.lbl_personal_detail_category_dislikes)
    PersonalDetailCategory.INTERESTS -> context.getString(R.string.lbl_personal_detail_category_interests)
    PersonalDetailCategory.FOOD -> context.getString(R.string.lbl_personal_detail_category_food)
    PersonalDetailCategory.DRINKS -> context.getString(R.string.lbl_personal_detail_category_drinks)
    PersonalDetailCategory.BRANDS -> context.getString(R.string.lbl_personal_detail_category_brands)
    PersonalDetailCategory.ALLERGIES -> context.getString(R.string.lbl_personal_detail_category_allergies)
    PersonalDetailCategory.RESTRICTIONS -> context.getString(R.string.lbl_personal_detail_category_restrictions)
    PersonalDetailCategory.COMMUNICATION_STYLE -> context.getString(R.string.lbl_personal_detail_category_communication_style)
    PersonalDetailCategory.HABITS -> context.getString(R.string.lbl_personal_detail_category_habits)
    PersonalDetailCategory.OTHER -> context.getString(R.string.lbl_personal_detail_category_other)
}

fun PersonalDetailCategory.labelKey(): String = when (this) {
    PersonalDetailCategory.LIKES -> "Нравится"
    PersonalDetailCategory.DISLIKES -> "Не нравится"
    PersonalDetailCategory.INTERESTS -> "Интересы"
    PersonalDetailCategory.FOOD -> "Еда"
    PersonalDetailCategory.DRINKS -> "Напитки"
    PersonalDetailCategory.BRANDS -> "Бренды"
    PersonalDetailCategory.ALLERGIES -> "Аллергии"
    PersonalDetailCategory.RESTRICTIONS -> "Ограничения"
    PersonalDetailCategory.COMMUNICATION_STYLE -> "Стиль общения"
    PersonalDetailCategory.HABITS -> "Привычки"
    PersonalDetailCategory.OTHER -> "Другое"
}

fun CalendarItemStatus.label(context: android.content.Context): String = when (this) {
    CalendarItemStatus.ACTIVE -> context.getString(R.string.lbl_calendar_item_status_active)
    CalendarItemStatus.COMPLETED -> context.getString(R.string.lbl_calendar_item_status_completed)
    CalendarItemStatus.POSTPONED -> context.getString(R.string.lbl_calendar_item_status_postponed)
    CalendarItemStatus.CANCELLED -> context.getString(R.string.lbl_calendar_item_status_cancelled)
}

fun CalendarItemStatus.labelKey(): String = when (this) {
    CalendarItemStatus.ACTIVE -> "Активно"
    CalendarItemStatus.COMPLETED -> "Выполнено"
    CalendarItemStatus.POSTPONED -> "Отложено"
    CalendarItemStatus.CANCELLED -> "Отменено"
}

fun MessengerType.label(context: android.content.Context): String = when (this) {
    MessengerType.TELEGRAM -> context.getString(R.string.lbl_messenger_type_telegram)
    MessengerType.WHATSAPP -> context.getString(R.string.lbl_messenger_type_whatsapp)
    MessengerType.SIGNAL -> context.getString(R.string.lbl_messenger_type_signal)
    MessengerType.VIBER -> context.getString(R.string.lbl_messenger_type_viber)
    MessengerType.MESSENGER -> context.getString(R.string.lbl_messenger_type_messenger)
    MessengerType.OTHER -> context.getString(R.string.lbl_messenger_type_other)
}

fun MessengerType.labelKey(): String = when (this) {
    MessengerType.TELEGRAM -> "Telegram"
    MessengerType.WHATSAPP -> "WhatsApp"
    MessengerType.SIGNAL -> "Signal"
    MessengerType.VIBER -> "Viber"
    MessengerType.MESSENGER -> "Messenger"
    MessengerType.OTHER -> "Другое"
}

fun PhoneType.label(context: android.content.Context): String = when (this) {
    PhoneType.MOBILE -> context.getString(R.string.lbl_phone_type_mobile)
    PhoneType.WORK -> context.getString(R.string.lbl_phone_type_work)
    PhoneType.HOME -> context.getString(R.string.lbl_phone_type_home)
    PhoneType.OTHER -> context.getString(R.string.lbl_phone_type_other)
}

fun PhoneType.labelKey(): String = when (this) {
    PhoneType.MOBILE -> "Мобильный"
    PhoneType.WORK -> "Рабочий"
    PhoneType.HOME -> "Домашний"
    PhoneType.OTHER -> "Другой"
}

fun EmailType.label(context: android.content.Context): String = when (this) {
    EmailType.PERSONAL -> context.getString(R.string.lbl_email_type_personal)
    EmailType.WORK -> context.getString(R.string.lbl_email_type_work)
    EmailType.OTHER -> context.getString(R.string.lbl_email_type_other)
}

fun EmailType.labelKey(): String = when (this) {
    EmailType.PERSONAL -> "Личный"
    EmailType.WORK -> "Рабочий"
    EmailType.OTHER -> "Другой"
}

fun ContactStatus.label(context: android.content.Context): String = when (this) {
    ContactStatus.NEW -> context.getString(R.string.lbl_contact_status_new)
    ContactStatus.ACTIVE -> context.getString(R.string.lbl_contact_status_active)
    ContactStatus.MAINTAIN -> context.getString(R.string.lbl_contact_status_maintain)
    ContactStatus.ARCHIVED -> context.getString(R.string.lbl_contact_status_archived)
    // Объединённый статус: Близкий/Слабый пришли из ConnectionLevel — строки те же
    ContactStatus.CLOSE -> context.getString(R.string.lbl_connection_level_close)
    ContactStatus.WEAK -> context.getString(R.string.lbl_connection_level_weak)
}

fun ContactStatus.labelKey(): String = when (this) {
    ContactStatus.NEW -> "Новый"
    ContactStatus.ACTIVE -> "Активный"
    ContactStatus.MAINTAIN -> "Поддерживать"
    ContactStatus.ARCHIVED -> "Архив"
    ContactStatus.CLOSE -> "Близкий"
    ContactStatus.WEAK -> "Слабый"
}

fun AddressType.label(context: android.content.Context): String = when (this) {
    AddressType.HOME -> context.getString(R.string.lbl_address_type_home)
    AddressType.WORK -> context.getString(R.string.lbl_address_type_work)
    AddressType.OFFICE -> context.getString(R.string.lbl_address_type_office)
    AddressType.BRANCH -> context.getString(R.string.lbl_address_type_branch)
    AddressType.LEGAL -> context.getString(R.string.lbl_address_type_legal)
    AddressType.OTHER -> context.getString(R.string.lbl_address_type_other)
}

fun AddressType.labelKey(): String = when (this) {
    AddressType.HOME -> "Дом"
    AddressType.WORK -> "Работа"
    AddressType.OFFICE -> "Офис"
    AddressType.BRANCH -> "Филиал"
    AddressType.LEGAL -> "Юр. адрес"
    AddressType.OTHER -> "Другое"
}
