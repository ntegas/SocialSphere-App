package com.example.utils

import com.example.model.*

fun RelationshipType.label(): String = when (this) {
    RelationshipType.FAMILY       -> "Семья"
    RelationshipType.FRIEND       -> "Друг"
    RelationshipType.ACQUAINTANCE -> "Знакомый"
    RelationshipType.COLLEAGUE    -> "Коллега"
    RelationshipType.CLIENT       -> "Клиент"
    RelationshipType.PARTNER      -> "Партнёр"
    RelationshipType.SUPPLIER     -> "Поставщик"
    RelationshipType.NEIGHBOR     -> "Сосед"
    RelationshipType.DOCTOR       -> "Врач"
    RelationshipType.TEACHER      -> "Учитель"
    RelationshipType.OTHER        -> "Другое"
}

fun ImportanceLevel.label(): String = when (this) {
    ImportanceLevel.NORMAL    -> "Обычный"
    ImportanceLevel.IMPORTANT -> "Важный"
    ImportanceLevel.KEY       -> "Ключевой"
}

fun ConnectionLevel.label(): String = when (this) {
    ConnectionLevel.CLOSE    -> "Близкий"
    ConnectionLevel.NORMAL   -> "Обычный"
    ConnectionLevel.WEAK     -> "Слабый"
    ConnectionLevel.NEW      -> "Новый"
    ConnectionLevel.ARCHIVED -> "Архив"
}

fun SocialRole.label(): String = when (this) {
    SocialRole.REGULAR          -> "Обычный"
    SocialRole.CONNECTOR        -> "Коннектор"
    SocialRole.KNOWS_MANY_PEOPLE -> "Много знает"
    SocialRole.CAN_INTRODUCE    -> "Может познакомить"
    SocialRole.EXPERT           -> "Эксперт"
    SocialRole.ADVISOR          -> "Советник"
    SocialRole.LOCAL_CONTACT    -> "Местный"
}

fun CommunicationRhythm.label(): String = when (this) {
    CommunicationRhythm.NOT_TRACKED      -> "Не отслеживать"
    CommunicationRhythm.WEEKLY          -> "Раз в неделю"
    CommunicationRhythm.MONTHLY         -> "Раз в месяц"
    CommunicationRhythm.EVERY_3_MONTHS  -> "Раз в 3 мес."
    CommunicationRhythm.EVERY_6_MONTHS  -> "Раз в 6 мес."
    CommunicationRhythm.YEARLY          -> "Раз в год"
    CommunicationRhythm.CUSTOM          -> "Настроить"
}

fun EmploymentStatus.label(): String = when (this) {
    EmploymentStatus.CURRENT -> "Текущее"
    EmploymentStatus.FORMER  -> "Бывшее"
    EmploymentStatus.UNKNOWN -> "Неизвестно"
}

fun CalendarItemType.label(): String = when (this) {
    CalendarItemType.BIRTHDAY       -> "День рождения"
    CalendarItemType.ANNIVERSARY    -> "Годовщина"
    CalendarItemType.IMPORTANT_DATE -> "Важная дата"
    CalendarItemType.MEETING        -> "Встреча"
    CalendarItemType.CALL           -> "Звонок"
    CalendarItemType.MESSAGE        -> "Сообщение"
    CalendarItemType.GIFT           -> "Подарок"
    CalendarItemType.TASK           -> "Задача"
    CalendarItemType.NOTE           -> "Заметка"
    CalendarItemType.COMPANY_EVENT  -> "Событие компании"
    CalendarItemType.CUSTOM         -> "Другое"
}

fun NoteType.label(): String = when (this) {
    NoteType.GENERAL               -> "Общая"
    NoteType.IMPORTANT_TO_REMEMBER -> "Важно помнить"
    NoteType.WORK                  -> "Рабочая"
    NoteType.PERSONAL_DETAIL       -> "Личная деталь"
    NoteType.GIFT                  -> "Подарок"
    NoteType.DATE_EVENT            -> "Дата/событие"
}

fun GiftStatus.label(): String = when (this) {
    GiftStatus.IDEA  -> "Идея"
    GiftStatus.GIVEN -> "Подарено"
}

fun Industry.label(): String = when (this) {
    Industry.IT          -> "IT"
    Industry.AUTO        -> "Автомобили"
    Industry.FINANCE     -> "Финансы"
    Industry.MEDICINE    -> "Медицина"
    Industry.EDUCATION   -> "Образование"
    Industry.REAL_ESTATE -> "Недвижимость"
    Industry.RETAIL      -> "Розница"
    Industry.GOVERNMENT  -> "Государство"
    Industry.OTHER       -> "Другое"
}

fun PersonalDetailCategory.label(): String = when (this) {
    PersonalDetailCategory.LIKES               -> "Нравится"
    PersonalDetailCategory.DISLIKES            -> "Не нравится"
    PersonalDetailCategory.INTERESTS           -> "Интересы"
    PersonalDetailCategory.FOOD               -> "Еда"
    PersonalDetailCategory.DRINKS             -> "Напитки"
    PersonalDetailCategory.BRANDS             -> "Бренды"
    PersonalDetailCategory.ALLERGIES          -> "Аллергии"
    PersonalDetailCategory.RESTRICTIONS       -> "Ограничения"
    PersonalDetailCategory.COMMUNICATION_STYLE -> "Стиль общения"
    PersonalDetailCategory.HABITS             -> "Привычки"
    PersonalDetailCategory.OTHER              -> "Другое"
}

fun CalendarItemStatus.label(): String = when (this) {
    CalendarItemStatus.ACTIVE     -> "Активно"
    CalendarItemStatus.COMPLETED  -> "Выполнено"
    CalendarItemStatus.POSTPONED  -> "Отложено"
    CalendarItemStatus.CANCELLED  -> "Отменено"
}

fun MessengerType.label(): String = when (this) {
    MessengerType.TELEGRAM  -> "Telegram"
    MessengerType.WHATSAPP  -> "WhatsApp"
    MessengerType.SIGNAL    -> "Signal"
    MessengerType.VIBER     -> "Viber"
    MessengerType.MESSENGER -> "Messenger"
    MessengerType.OTHER     -> "Другое"
}

fun PhoneType.label(): String = when (this) {
    PhoneType.MOBILE  -> "Мобильный"
    PhoneType.WORK    -> "Рабочий"
    PhoneType.HOME    -> "Домашний"
    PhoneType.OTHER   -> "Другой"
}

fun EmailType.label(): String = when (this) {
    EmailType.PERSONAL -> "Личный"
    EmailType.WORK     -> "Рабочий"
    EmailType.OTHER    -> "Другой"
}
