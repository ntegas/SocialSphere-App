package com.aistudio.socialsphere.crmlxb.model

enum class ContactStatus {
    NEW, ACTIVE, MAINTAIN, ARCHIVED
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
enum class CommunicationRhythm {
    NOT_TRACKED, WEEKLY, MONTHLY, EVERY_3_MONTHS, EVERY_6_MONTHS, YEARLY, CUSTOM
}
enum class PhoneType {
    MOBILE, WORK, HOME, OTHER
}
enum class EmailType {
    PERSONAL, WORK, OTHER
}
enum class MessengerType {
    TELEGRAM, WHATSAPP, VIBER, SIGNAL, MESSENGER, OTHER
}
enum class Industry {
    IT, AUTO, FINANCE, MEDICINE, EDUCATION, REAL_ESTATE, RETAIL, GOVERNMENT, OTHER
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
    TODAY, LIST, WEEK, MONTH
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
enum class PersonalDetailCategory {
    LIKES, DISLIKES, INTERESTS, FOOD, DRINKS, BRANDS, ALLERGIES, RESTRICTIONS, COMMUNICATION_STYLE, HABITS, OTHER
}
