package com.example.model

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
    BIRTHDAY, ANNIVERSARY, IMPORTANT_DATE, MEETING, CALL, MESSAGE, GIFT, TASK, NOTE, COMPANY_EVENT, CUSTOM
}
enum class CalendarItemStatus {
    ACTIVE, COMPLETED, POSTPONED, CANCELLED
}
enum class CalendarTargetType {
    CONTACT, COMPANY, GIFT, NOTE, ADDRESS
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
    IDEA, GIVEN
}
enum class PersonalDetailCategory {
    LIKES, DISLIKES, INTERESTS, FOOD, DRINKS, BRANDS, ALLERGIES, RESTRICTIONS, COMMUNICATION_STYLE, HABITS, OTHER
}
