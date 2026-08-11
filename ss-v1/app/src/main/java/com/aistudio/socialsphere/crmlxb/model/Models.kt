package com.aistudio.socialsphere.crmlxb.model

// ФИКС (2026-07-11, найдено на первом релизе в Play Console): бэкап/восстановление
// (ExportManager.kt) сериализует эти классы через Moshi. Раньше — только
// рефлексией (KotlinJsonAdapterFactory): под R8 в release-сборке имена/метаданные
// конструктора иногда теряются, а Moshi-рефлексия под R8 также способна тихо
// подставить null в поле с типом List<T> без "?" вместо значения по умолчанию
// (emptyList()) — импорт падал с NPE на .forEach где-то в глубине дерева контакта,
// без внятной причины. @JsonClass(generateAdapter = true) — генерирует адаптер
// на этапе компиляции (KSP moshi-kotlin-codegen уже подключен в build.gradle.kts),
// не зависит от рефлексии/R8 вообще и корректно проверяет default-значения.
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Contact(
    val id: String,
    val firstName: String,
    val lastName: String,
    /** Отчество / среднее имя (как в телефонной книге) — раньше терялось при импорте. */
    val middleName: String? = null,
    val nickname: String? = null,
    /** Структура имени как в Android-контактах (v13): приставка (Dr./г-н),
     *  суффикс (мл./ст.), фонетические имя/фамилия (для языков вроде японского,
     *  где произношение не следует из письменной формы). Раньше префикс/суффикс
     *  при импорте молча склеивались в middleName — терялась структура. */
    val namePrefix: String? = null,
    val nameSuffix: String? = null,
    val phoneticFirstName: String? = null,
    val phoneticMiddleName: String? = null,
    val phoneticLastName: String? = null,
    val photoUri: String?,
    val relationshipType: RelationshipType,
    /** Свой тип отношений («Кум», «Тренер»…) — если задан, показывается вместо
     *  relationshipType. Стандартный выбор из пикера очищает это поле.
     *  TODO (2026-07-11, план+критика воркфлоу): владелец хочет НЕСКОЛЬКО типов
     *  одновременно (список вместо одного значения) — не взаимоисключающие
     *  отношения («и друг, и коллега»). Спроектировано (relationshipTypes:
     *  List<RelationshipType>, паттерн как tags — без Room-миграции), но
     *  сознательно отложено отдельным заходом: критик нашёл, что mergeContacts()/
     *  renameCustomRelationshipType()/deleteCustomRelationshipType() в
     *  AppStateStore.kt построены вокруг одиночной строки и требуют аккуратной
     *  переработки семантики слияния списков — риск потери данных при спешке. */
    val customRelationshipType: String? = null,
    /** Второстепенные типы отношений (v17, решение владельца 2026-07-23):
     *  relationshipType остаётся ЕДИНСТВЕННЫМ главным типом, это поле —
     *  произвольное число дополнительных, не взаимоисключающих («и друг,
     *  и коллега»). Паттерн сериализации — как у tags (comma-joined string). */
    val secondaryRelationshipTypes: List<RelationshipType> = emptyList(),
    /** LEGACY: уровень связи слит в ContactStatus (CLOSE/WEAK). Поле осталось
     *  в БД/модели ради сохранности данных, UI его больше не показывает. */
    val connectionLevel: ConnectionLevel,
    val importanceLevel: ImportanceLevel,
    val socialRole: SocialRole,
    val communicationRhythm: CommunicationRhythm,
    /** Число дней для CommunicationRhythm.CUSTOM («раз в N дней») — v17,
     *  решение владельца 2026-07-23. Используется только когда
     *  communicationRhythm == CUSTOM; иначе игнорируется. */
    val customRhythmDays: Int? = null,
    val contactStatus: ContactStatus = ContactStatus.ACTIVE,
    val lastContactDate: String? = null,
    val nextStep: String? = null,
    /** Свободный текст о семье БЕЗ карточек контактов («сын Петя, 2019 г.р.») —
     *  показывается в блоке F (Семья) под списком связанных членов семьи. */
    val familyNote: String? = null,
    /** Профессия БЕЗ привязки к компании («электрик», «нотариус») — v12.
     *  Должность в конкретной компании живёт в ContactCompanyRelation.position. */
    val profession: String? = null,
    val tags: List<String> = emptyList(),
    val canHelpWith: String? = null,
    val iCanHelpWith: String? = null,
    val talkingPoints: String? = null,
    val meetContext: String? = null,
    val meetDate: String? = null,
    /** Связь с контактом телефонной книги (id вида device_contact_<CONTACT_ID>)
     *  для синхронизации «обновить из телефона». null — не связан. */
    val deviceContactId: String? = null,
    val companyRelations: List<ContactCompanyRelation> = emptyList(),
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val messengers: List<Messenger> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val notes: List<Note> = emptyList(),
    val gifts: List<GiftIdea> = emptyList(),
    val sizeInfo: SizeInfo? = null,
    val personalDetails: List<PersonalDetail> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class ContactPhone(
    val id: String,
    val contactId: String,
    val number: String,
    val type: PhoneType,
    val isPrimary: Boolean,
    val comment: String? = null
)

@JsonClass(generateAdapter = true)
data class ContactEmail(
    val id: String,
    val contactId: String,
    val email: String,
    val type: EmailType,
    val isPrimary: Boolean,
    val comment: String? = null
)

@JsonClass(generateAdapter = true)
data class Messenger(
    val id: String,
    val contactId: String,
    val type: MessengerType,
    val value: String,
    val link: String? = null,
    val isPrimary: Boolean,
    val comment: String? = null
)

@JsonClass(generateAdapter = true)
data class Company(
    val id: String,
    val name: String,
    val logoUri: String? = null,
    val industry: Industry,
    val description: String? = null,
    val website: String? = null,
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class ContactCompanyRelation(
    val id: String,
    val contactId: String,
    val companyId: String,
    val position: String? = null,
    val department: String? = null,
    val role: String? = null,
    val employmentStatus: EmploymentStatus,
    val startDate: String? = null,
    val endDate: String? = null,
    val responsibilities: String? = null,
    val managedAccounts: String? = null,
    val workNote: String? = null,
    val officeAddressId: String? = null,
    val isPrimary: Boolean
)

@JsonClass(generateAdapter = true)
data class ContactRelation(
    val id: String,
    val firstContactId: String,
    val secondContactId: String,
    val firstRole: String,
    val secondRole: String,
    val note: String? = null
)

/** Группа контактов (как группы в телефонной книге): «Клиенты», «Футбол»… */
@JsonClass(generateAdapter = true)
data class ContactGroup(
    val id: String,
    val name: String,
    val createdAt: String,
    val updatedAt: String
)

/** Членство контакта в группе (многие-ко-многим). */
@JsonClass(generateAdapter = true)
data class ContactGroupMember(
    val id: String,
    val groupId: String,
    val contactId: String
)

/** Тег контакта — плоский управляемый список (в отличие от Contact.tags,
 *  легаси-поля со свободным текстом). Опциональная категория для группировки. */
@JsonClass(generateAdapter = true)
data class Tag(
    val id: String,
    val name: String,
    val category: String? = null,
    val createdAt: String,
    val updatedAt: String
)

/** Членство контакта в теге (многие-ко-многим), как ContactGroupMember. */
@JsonClass(generateAdapter = true)
data class ContactTagMember(
    val id: String,
    val tagId: String,
    val contactId: String
)

@JsonClass(generateAdapter = true)
data class Address(
    val id: String,
    val ownerType: AddressOwnerType,
    val ownerId: String,
    val addressType: AddressType,
    val addressLine: String,
    val city: String,
    val country: String,
    val comment: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val postalCode: String? = null,
    /** Район (2026-07-13) — как NEIGHBORHOOD в Android StructuredPostal /
     *  subLocality в iOS CNPostalAddress, между улицей и городом. */
    val district: String? = null
)

@JsonClass(generateAdapter = true)
data class CalendarItem(
    val id: String,
    val title: String,
    val description: String? = null,
    val type: CalendarItemType,
    val startDate: String,
    val startTime: String? = null,
    val endDate: String? = null,
    val endTime: String? = null,
    val isAllDay: Boolean,
    val status: CalendarItemStatus,
    val importance: ImportanceLevel,
    val colorKey: String? = null,
    val recurrenceRule: String? = null,
    val links: List<CalendarItemLink> = emptyList(),
    val reminders: List<ReminderRule> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class CalendarItemLink(
    val id: String,
    val calendarItemId: String,
    val targetType: CalendarTargetType,
    val targetId: String
)

@JsonClass(generateAdapter = true)
data class ReminderRule(
    val id: String,
    val calendarItemId: String,
    val reminderType: ReminderType,
    val offsetValue: Int? = null,
    val offsetUnit: ReminderOffsetUnit? = null,
    val exactDateTime: String? = null
)

@JsonClass(generateAdapter = true)
data class Note(
    val id: String,
    val contactId: String? = null,
    val companyId: String? = null,
    val calendarItemId: String? = null,
    val giftId: String? = null,
    val type: NoteType,
    val text: String,
    val date: String? = null,
    val isImportant: Boolean,
    // Приватность записи — независимо от isImportant (важность = попадает в
    // Обзор/Шпаргалку; isLocked = скрывается блюром при включённой «Защите
    // записей»). Владелец решает по каждой записи отдельно, как в iOS Notes/
    // WhatsApp Chat Lock — не автоматика по типу/вкладке.
    val isLocked: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class GiftIdea(
    val id: String,
    val contactId: String,
    val title: String,
    val note: String? = null,
    val link: String? = null,
    val date: String? = null,
    val reminderId: String? = null,
    val status: GiftStatus
)

@JsonClass(generateAdapter = true)
data class SizeInfo(
    val id: String,
    val contactId: String,
    val clothingSize: String? = null,
    val shoeSize: String? = null,
    val ringSize: String? = null,
    val other: String? = null
)

@JsonClass(generateAdapter = true)
data class PersonalDetail(
    val id: String,
    val contactId: String,
    val category: PersonalDetailCategory,
    val value: String,
    val note: String? = null
)
