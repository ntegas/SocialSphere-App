package com.aistudio.socialsphere.crmlxb.model

// Объединённый статус (решение владельца 2026-07-02): прежние ContactStatus
// (Новый/Активный/Поддерживать/Архив) + Близкий/Слабый из ConnectionLevel.
// ConnectionLevel как отдельное поле из UI убран (колонка в БД остаётся).
enum class ContactStatus {
    NEW, ACTIVE, MAINTAIN, ARCHIVED, CLOSE, WEAK
}

enum class RelationshipType {
    FAMILY, FRIEND, ACQUAINTANCE, COLLEAGUE, CLIENT, PARTNER, SUPPLIER, NEIGHBOR, DOCTOR, TEACHER, OTHER
}
enum class ConnectionLevel {
    CLOSE, NORMAL, WEAK, NEW, ARCHIVED
}
enum class ImportanceLevel {
    NORMAL, IMPORTANT, KEY
}
enum class SocialRole {
    REGULAR, CONNECTOR, KNOWS_MANY_PEOPLE, CAN_INTRODUCE, EXPERT, ADVISOR, LOCAL_CONTACT
}
/**
 * «Поддерживать связь»: 2026-10-02, найденный пробел верификации V2.2 —
 * NOT_TRACKED (= Выкл) и «Вкл, без расписания» схлопывались в одно и то же
 * persisted-значение. NO_SCHEDULE — минимальное добавление (text-колонка +
 * safeEnum-фолбэк, тот же паттерн, что Industry/MessengerType — миграция не
 * нужна): «поддерживать связь ВКЛЮЧЕНО, но без периодичности» — отличается от
 * NOT_TRACKED по смыслу, но, как и NOT_TRACKED, НЕ имеет интервала просрочки
 * (StaleContacts.rhythmDays возвращает null для обоих).
 */
enum class CommunicationRhythm {
    NOT_TRACKED, NO_SCHEDULE, WEEKLY, MONTHLY, EVERY_3_MONTHS, EVERY_6_MONTHS, YEARLY, CUSTOM
}
enum class PhoneType {
    MOBILE, WORK, HOME, OTHER
}
enum class EmailType {
    PERSONAL, WORK, OTHER
}
enum class MessengerType {
    TELEGRAM, WHATSAPP, VIBER, SIGNAL, MESSENGER,
    // Соцсети (2026-07-12, фидбэк владельца: «добавить соцсети» — не были
    // видны как канал связи вообще). Хранится name-строкой в БД, безопасное
    // добавление значений без миграции — тот же паттерн, что у Industry ниже.
    INSTAGRAM, FACEBOOK, VK, LINKEDIN, X,
    OTHER
}
enum class Industry {
    // Расширено 2026-07-02 по фидбэку владельца (хранится name-строкой,
    // safeEnum-фолбэк — добавление значений безопасно, миграция не нужна).
    IT, AUTO, FINANCE, MEDICINE, EDUCATION, REAL_ESTATE, RETAIL, GOVERNMENT,
    CONSTRUCTION, LOGISTICS, MANUFACTURING, HORECA, LEGAL, MARKETING,
    MEDIA, BEAUTY, SPORT, TOURISM, ENERGY, AGRICULTURE,
    OTHER
}
enum class EmploymentStatus {
    CURRENT, FORMER, UNKNOWN
}
enum class AddressOwnerType {
    CONTACT, COMPANY
}
enum class AddressType {
    HOME, WORK, OFFICE, BRANCH, LEGAL, OTHER
}
enum class CalendarItemType {
    BIRTHDAY, ANNIVERSARY, NAMEDAY, IMPORTANT_DATE, MEETING, CALL, MESSAGE, GIFT, TASK, NOTE, COMPANY_EVENT, CUSTOM
}
enum class CalendarItemStatus {
    ACTIVE, COMPLETED, POSTPONED, CANCELLED
}
enum class CalendarTargetType {
    CONTACT, COMPANY, GIFT, NOTE, ADDRESS
}
enum class CalendarViewMode {
    // «Лента» (LIST) / «Неделя» (WEEK) / «Месяц» (MONTH) — по макету Aurelia.
    // Легаси-значение TODAY убрано: миграция в AppSettings.calendarDefaultMode.
    LIST, WEEK, MONTH
}
enum class CalendarEventFilter {
    ALL, BIRTHDAYS, CALLS, MEETINGS, GIFTS, IMPORTANT
}
enum class ReminderTime {
    NONE, AT_EVENT, ON_DAY, MIN_10, MIN_30, HOUR_1, DAY_1, DAY_3, WEEK_1
}
enum class RecurrenceMode {
    NONE, DAILY, WEEKLY, MONTHLY, YEARLY;
    // RRULE для сохранения в CalendarItem.recurrenceRule (null = без повтора)
    fun toRRule(): String? = when (this) {
        NONE -> null
        DAILY -> "FREQ=DAILY"
        WEEKLY -> "FREQ=WEEKLY"
        MONTHLY -> "FREQ=MONTHLY"
        YEARLY -> "FREQ=YEARLY"
    }
    companion object {
        // Разбор сохранённого правила; распознаёт легаси русские строки
        fun fromRule(rule: String?): RecurrenceMode = when (rule) {
            null, "", "Не повторять" -> NONE
            "FREQ=DAILY", "Каждый день" -> DAILY
            "FREQ=WEEKLY", "Каждую неделю" -> WEEKLY
            "FREQ=MONTHLY", "Каждый месяц" -> MONTHLY
            "FREQ=YEARLY", "Каждый год" -> YEARLY
            else -> if (rule.contains("YEARLY")) YEARLY else NONE
        }
    }
}
enum class ReminderType {
    NONE, AT_TIME, BEFORE, CUSTOM_DATE_TIME
}
enum class ReminderOffsetUnit {
    MINUTES, HOURS, DAYS, WEEKS
}
enum class NoteType {
    GENERAL, IMPORTANT_TO_REMEMBER, WORK, PERSONAL_DETAIL, GIFT, DATE_EVENT
}
enum class GiftStatus {
    IDEA, BOUGHT, GIVEN
}
/**
 * EXPLICIT RECOVERY PACK (2026-09-22): типы сущностей, для которых ведётся история удаления (tombstone).
 * Ровно те пять, у которых есть независимое пользовательское действие «Удалить» с устойчивым id и
 * собственным местом в BackupData — контакт/компания/событие календаря top-level, заметка/подарок —
 * явно названные «Notes, Gifts, Contacts и поддерживаемые дочерние сущности» в задаче пакета. Слияние
 * дублей (mergeContacts/mergeCompanies) НЕ тombstonéится — это консолидация данных, не удаление: данные
 * поглощённого контакта живут в контакте-получателе, а не пропадают.
 */
enum class TombstoneEntityType {
    CONTACT, COMPANY, NOTE, GIFT, CALENDAR_ITEM,
    /** RESTORE BATCH DEPENDENCIES & STANDALONE GROUPS MICROFIX (2026-09-24): используется ТОЛЬКО как
     *  RestorePreviewGroup.ownerType/RestoreDifference.entityType для standalone-восстанавливаемой Group
     *  (backup Group без единого membership) — НЕ добавлен в Tombstone-поток (группы, как и раньше,
     *  осознанно вне tombstone-истории, requirement 16 исходного Restore Preview Pack). TEXT-колонка
     *  Room не типизирована по этому enum на уровне схемы — миграция не требуется. */
    GROUP
}
enum class PersonalDetailCategory {
    LIKES, DISLIKES, INTERESTS, FOOD, DRINKS, BRANDS, ALLERGIES, RESTRICTIONS, COMMUNICATION_STYLE, HABITS, OTHER
}
