package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.PersistedState
import com.aistudio.socialsphere.crmlxb.data.RecoverableRecord
import com.aistudio.socialsphere.crmlxb.data.RestoreAuthorization
import com.aistudio.socialsphere.crmlxb.data.RestoreDiffEngine
import com.aistudio.socialsphere.crmlxb.data.RestorePlan
import com.aistudio.socialsphere.crmlxb.data.RestorePreview
import com.aistudio.socialsphere.crmlxb.data.RestoreResult
import com.aistudio.socialsphere.crmlxb.data.buildApplyPlan
import com.aistudio.socialsphere.crmlxb.data.recoverableCandidates
import com.aistudio.socialsphere.crmlxb.model.*
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Текущая версия формата полного JSON-backup. 7 (Backup Pack #3): снимок из Room + roomSchemaVersion. */
const val BACKUP_FORMAT_VERSION = 7

/** Самая старая версия, которую ещё умеем читать (формат совместим назад через default-значения). */
const val MIN_BACKUP_FORMAT_VERSION = 1

/**
 * Полный снимок данных приложения для бэкапа/восстановления.
 * Контакты сериализуются целиком, со всеми вложенными полями
 * (телефоны/email/мессенджеры/адреса/заметки/подарки/размеры/
 * персональные детали/связи с компаниями). Плюс компании, события
 * календаря и контакт-контакт связи (семья).
 */
@JsonClass(generateAdapter = true)
data class BackupData(
    // v7 (Backup Pack #3): backup строится из согласованного снимка Room (не из памяти) + roomSchemaVersion.
    // Меняем ТОЛЬКО через ExportManager.BACKUP_FORMAT_VERSION.
    val version: Int = BACKUP_FORMAT_VERSION,
    val exportedAt: String = "",
    // Версия схемы Room, из которой снят backup (v7+; 0 у старых backup — «неизвестна»). Backup более
    // новой схемы, чем у открытой БД, отвергается ДО любой записи (см. ExportManager.importJsonBackupDetailed).
    val roomSchemaVersion: Int = 0,
    val contacts: List<Contact> = emptyList(),
    val companies: List<Company> = emptyList(),
    val calendarItems: List<CalendarItem> = emptyList(),
    val contactRelations: List<ContactRelation> = emptyList(),
    // Группы контактов (v12) — добавлены со значением по умолчанию: старые
    // бэкапы без этого поля по-прежнему парсятся (Moshi берёт emptyList()).
    val groups: List<ContactGroup> = emptyList(),
    val groupMembers: List<ContactGroupMember> = emptyList(),
    // Теги контакта — по образцу groups/groupMembers: default-значения,
    // чтобы старые бэкапы без этих полей по-прежнему парсились.
    val tags: List<Tag> = emptyList(),
    val tagMembers: List<ContactTagMember> = emptyList(),
    // ФИКС (аудит 2026-08-11, §44 KNOWLEDGE.md): заметки НИКОГДА не попадали
    // в бэкап отдельным полем — читались только как вложенное Contact.notes,
    // из-за чего заметки компаний (Note.companyId, без contactId) не сохранялись
    // вообще. Плоский список — единственный источник истины (как в AppStateStore),
    // покрывает и контактные, и компанийные заметки разом. Default emptyList()
    // — старые бэкапы (version<5) по-прежнему парсятся, просто без заметок.
    val notes: List<Note> = emptyList(),
    // ФИКС (2026-08-12, тот же класс бага, что и notes/§44, просто не был
    // найден вместе с ним): подарки живут в собственной таблице (giftDao),
    // addContactDb её никогда не писал — restoreContact восстанавливал контакт,
    // но не его подарки, они не переживали переустановку. version 5→6, default
    // emptyList() — старые бэкапы по-прежнему парсятся, просто без подарков.
    val gifts: List<GiftIdea> = emptyList(),
    // EXPLICIT RECOVERY PACK (2026-09-22): история намеренных удалений (Room v22, TombstoneEntity) — путешествует
    // с бэкапом, чтобы SAFE MERGE на любом устройстве знал, что эта запись была удалена НАМЕРЕННО, а не просто
    // отсутствует. default emptyList() — старые бэкапы (version<7 в этом смысле) по-прежнему парсятся, просто без
    // истории удалений (для них SAFE MERGE ведёт себя как раньше: current-only решается по факту отсутствия).
    val tombstones: List<Tombstone> = emptyList()
)

object ExportManager {

    private val ts get() = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))

    /** EXPORT SNAPSHOT CONSISTENCY PACK (2026-09-23) — тестовый шов: вызывается ВНУТРИ exportFullZip
     *  СРАЗУ после захвата единого snapshot, ДО построения ЛЮБОГО из внутренних файлов — модель
     *  «запись, случившаяся во время экспорта» (requirement 11). В проде null. */
    @Volatile internal var exportZipSnapshotCapturedHookForTests: (suspend () -> Unit)? = null

    // Безопасность (skill: insecure-data-storage): экспорт = вся PII в ОТКРЫТОМ
    // виде в cacheDir. Чтобы она там не оставалась навсегда — перед каждым новым
    // экспортом удаляем прошлые наши temp-файлы старше часа (только что созданный
    // не трогаем, он ещё нужен для шаринга).
    // "contact_" добавлен 2026-07-02: vCard одного контакта (шаринг/в телефон)
    // раньше не попадал под уборку и лежал в cache бессрочно.
    private val exportPrefixes = listOf("contacts_", "companies_", "backup_", "socialsphere_backup_", "contact_", "notes_", "calendar_")

    /** Подпапка cache/exports — FileProvider открывает ТОЛЬКО её (аудит 2026-07-02:
     *  раньше file_paths.xml отдавал весь cacheDir). Старые файлы чистятся по TTL. */
    private fun exportsDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun cleanOldExports(context: Context) {
        val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
        try {
            exportsDir(context).listFiles()?.forEach { f ->
                if (exportPrefixes.any { f.name.startsWith(it) } && f.lastModified() < cutoff) f.delete()
            }
        } catch (e: Exception) { /* best-effort */ }
    }

    // ─── Share file via system sheet ───────────────────────────
    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Через безопасный запуск: локализованный контекст — не Activity,
        // прямой startActivity молча ронял share-sheet экспорта
        if (!ExternalActionHandler.startIntentSafely(context, Intent.createChooser(intent, context.getString(R.string.export_share_chooser_title)))) {
            android.widget.Toast.makeText(context, context.getString(R.string.export_share_open_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ─── CSV Contacts ──────────────────────────────────────────
    // EXPORT SNAPSHOT CONSISTENCY PACK (2026-09-23): builder — ЧИСТАЯ функция от явно переданного
    // snapshot, не читает mutable AppStateStore.* сама (requirement 6). Публичная suspend-обёртка
    // захватывает ОДИН канонический snapshot через AppStateStore.readPersistedState() — тот же
    // механизм, что уже использует JSON backup (toBackupData), не второй competing snapshot (requirement 5).
    // exportFullZip передаёt СВОЙ уже захваченный snapshot напрямую в builder, минуя повторный захват.
    suspend fun exportContactsCsv(context: Context): File = withContext(Dispatchers.IO) {
        buildContactsCsvFile(context, AppStateStore.readPersistedState())
    }

    private fun buildContactsCsvFile(context: Context, state: PersistedState): File {
        cleanOldExports(context)
        val file = File(exportsDir(context), "contacts_$ts.csv")
        PrintWriter(FileWriter(file)).use { pw ->
            pw.println(context.getString(R.string.export_csv_contacts_header))
            state.contacts.forEach { c ->
                val phone   = c.phones.find { it.isPrimary }?.number
                    ?: c.phones.firstOrNull()?.number ?: ""
                val email   = c.emails.find { it.isPrimary }?.email
                    ?: c.emails.firstOrNull()?.email ?: ""
                val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                    ?: c.companyRelations.firstOrNull()
                val company  = compRel?.companyId
                    ?.let { id -> state.companies.find { it.id == id }?.name } ?: ""
                val position = compRel?.position ?: ""
                val city     = state.addresses.find {
                    it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT
                }?.city ?: ""
                pw.println(
                    csvRow(c.firstName, c.lastName, phone, email,
                        company, position, city,
                        c.relationshipType.name, c.importanceLevel.name)
                )
            }
        }
        return file
    }

    // ─── CSV Companies ─────────────────────────────────────────
    suspend fun exportCompaniesCsv(context: Context): File = withContext(Dispatchers.IO) {
        buildCompaniesCsvFile(context, AppStateStore.readPersistedState())
    }

    private fun buildCompaniesCsvFile(context: Context, state: PersistedState): File {
        cleanOldExports(context)
        val file = File(exportsDir(context), "companies_$ts.csv")
        PrintWriter(FileWriter(file)).use { pw ->
            pw.println(context.getString(R.string.export_csv_companies_header))
            state.companies.forEach { c ->
                val city = state.addresses.find {
                    it.ownerId == c.id && it.ownerType == AddressOwnerType.COMPANY
                }?.city ?: ""
                val contactCount = state.companyRelations
                    .count { it.companyId == c.id }
                pw.println(
                    csvRow(c.name, c.industry.name, city,
                        c.website ?: "", c.description ?: "",
                        contactCount.toString())
                )
            }
        }
        return file
    }

    // ─── vCard (.vcf) ──────────────────────────────────────────
    // Экранирование значений по RFC 6350/2426: иначе имя/заметка/адрес с
    // запятой, точкой-с-запятой или переносом строки ломали карточку при импорте.
    private fun vEsc(s: String?): String = (s ?: "")
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")

    private fun adrType(t: AddressType): String = when (t) {
        AddressType.HOME -> "HOME"
        AddressType.WORK, AddressType.OFFICE -> "WORK"
        else -> "OTHER"
    }

    /**
     * Один контакт → vCard 3.0 со ВСЕМИ полями. Стандартные поля (N/FN/NICKNAME/
     * ORG/TITLE/TEL/EMAIL/ADR с типом/BDAY/NOTE) читаются телефонной книгой;
     * мессенджеры — X-поля; app-поля (следующий шаг, темы, теги…), которых нет
     * в стандарте, складываем в NOTE читаемым текстом — иначе они бы терялись.
     */
    // N:/FN: — общие для полной и урезанной (share) vCard, вынесено, чтобы
    // правка формата ФИО попадала в обе версии разом (аудит 2026-07-22:
    // до рефакторинга были буквально продублированы между writeVCard/writeShareVCard).
    private fun writeNameFields(pw: PrintWriter, c: Contact) {
        // N: Family;Given;Additional;Prefixes;Suffixes (RFC 2426) — раньше
        // приставка/суффикс не писались вообще (пустые компоненты), хотя модель
        // их уже хранит отдельно (v13, как в Android-контактах).
        pw.println("N:${vEsc(c.lastName)};${vEsc(c.firstName)};${vEsc(c.middleName ?: "")};${vEsc(c.namePrefix ?: "")};${vEsc(c.nameSuffix ?: "")}")
        val fullName = listOfNotNull(
            c.namePrefix?.takeIf { it.isNotBlank() },
            c.firstName.takeIf { it.isNotBlank() },
            c.middleName?.takeIf { it.isNotBlank() },
            c.lastName.takeIf { it.isNotBlank() },
            c.nameSuffix?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        pw.println("FN:${vEsc(fullName)}")
    }

    // ORG/TITLE — общие для полной и урезанной vCard. `state` — тот же snapshot, что вызывающий уже
    // захватил (requirement 7): компания резолвится ИЗ НЕГО, не отдельным live-чтением AppStateStore.
    private fun writeOrgFields(pw: PrintWriter, c: Contact, state: PersistedState) {
        val compRel = c.companyRelations.firstOrNull { it.isPrimary }
            ?: c.companyRelations.firstOrNull()
        val company = compRel?.companyId?.let { id -> state.companies.find { it.id == id }?.name } ?: ""
        if (company.isNotBlank() || !compRel?.position.isNullOrBlank()) {
            pw.println("ORG:${vEsc(company)}")
            if (!compRel?.position.isNullOrBlank())
                pw.println("TITLE:${vEsc(compRel?.position)}")
        }
    }

    // TEL — общие для полной и урезанной vCard.
    private fun writePhoneFields(pw: PrintWriter, c: Contact) {
        c.phones.forEach { p ->
            val type = when (p.type) {
                PhoneType.WORK -> "WORK"
                PhoneType.HOME -> "HOME"
                else           -> "CELL"
            }
            val pref = if (p.isPrimary) ",PREF" else ""
            pw.println("TEL;TYPE=$type$pref:${p.number}")
        }
    }

    // EMAIL — общие для полной и урезанной vCard.
    private fun writeEmailFields(pw: PrintWriter, c: Contact) {
        c.emails.forEach { e ->
            val type = when (e.type) {
                EmailType.WORK -> "WORK"
                else           -> "HOME"
            }
            val pref = if (e.isPrimary) ",PREF" else ""
            pw.println("EMAIL;TYPE=$type$pref:${e.email}")
        }
    }

    // `state` — snapshot, захваченный ОДИН раз вызывающим (requirement 7/8) — ни один lookup внутри
    // (компания/адреса/день рождения/managed-теги) не читает live AppStateStore отдельно, иначе он мог
    // бы отразить состояние ДРУГОГО момента, чем остальная vCard (requirement 8 — «один snapshot должен
    // содержать всё необходимое для resolver»).
    private fun writeVCard(pw: PrintWriter, c: Contact, state: PersistedState) {
        pw.println("BEGIN:VCARD")
        pw.println("VERSION:3.0")
        writeNameFields(pw, c)
        if (!c.nickname.isNullOrBlank()) pw.println("NICKNAME:${vEsc(c.nickname)}")
        // Фонетические имя/фамилия — нестандартное X-поле (нет фиксированного
        // тега в vCard 3.0), но Android умеет читать X-PHONETIC-*.
        if (!c.phoneticFirstName.isNullOrBlank()) pw.println("X-PHONETIC-FIRST-NAME:${vEsc(c.phoneticFirstName)}")
        if (!c.phoneticMiddleName.isNullOrBlank()) pw.println("X-PHONETIC-MIDDLE-NAME:${vEsc(c.phoneticMiddleName)}")
        if (!c.phoneticLastName.isNullOrBlank()) pw.println("X-PHONETIC-LAST-NAME:${vEsc(c.phoneticLastName)}")

        writeOrgFields(pw, c, state)
        writePhoneFields(pw, c)
        writeEmailFields(pw, c)

        c.messengers.forEach { m -> pw.println("X-${m.type.name}:${vEsc(m.value)}") }

        // ВСЕ адреса контакта с типом (раньше уходил только первый и без типа)
        state.addresses
            .filter { it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT }
            .forEach { a ->
                // Компонент 5 (region) vCard ADR — район (раньше всегда пустой).
                pw.println("ADR;TYPE=${adrType(a.addressType)}:;;${vEsc(a.addressLine)};" +
                    "${vEsc(a.city)};${vEsc(a.district)};${vEsc(a.postalCode)};${vEsc(a.country)}")
            }

        val birthday = state.calendarItems.find {
            it.type == CalendarItemType.BIRTHDAY &&
            it.links.any { l -> l.targetId == c.id }
        }?.startDate
        if (!birthday.isNullOrBlank()) {
            // Дата без года хранится «--MM-DD» → vCard-форма «--MMDD»
            // (простой replace("-","") давал бы «MMDD» без маркера отсутствия года)
            if (birthday.startsWith("--"))
                pw.println("BDAY:--${birthday.removePrefix("--").replace("-", "")}")
            else
                pw.println("BDAY:${birthday.replace("-", "")}")
        }

        // NOTE: заметки/личные детали/подарки/следующий шаг и т.д. — нет в
        // стандарте vCard, кладём текстом. Каждая запись помечена меткой
        // (см. ContactNoteCodec), чтобы «Импорт из контактов телефона» умел
        // разложить их обратно по своим местам, а не одной общей заметкой
        // (фидбэк владельца 2026-08-11: «чтобы приложение определяло, что и
        // куда добавить»). R.string.export_vcard_*_prefix больше не читаются
        // здесь — метки теперь фиксированные (не зависят от языка интерфейса
        // на момент экспорта), см. ContactNoteCodec.
        // BATCH 5 (5-004): теги в выгрузку берём из канонической управляемой системы. EXPORT SNAPSHOT
        // CONSISTENCY PACK: из ТОГО ЖЕ snapshot (state.tags/state.tagMembers), не отдельным live-чтением
        // AppStateStore.tagsOfContact() — requirement 8, легаси Contact.tags как источник НЕ используется
        // (canonical — managed, см. Legacy Tags Compatibility Pack).
        val tagNames = state.tagMembers.filter { it.contactId == c.id }
            .mapNotNull { m -> state.tags.find { it.id == m.tagId }?.name }
        ContactNoteCodec.encode(c, tagNames)
            ?.let { pw.println("NOTE:${vEsc(it)}") }

        pw.println("END:VCARD")
        pw.println()
    }

    /**
     * Урезанная vCard для «Поделиться контактом» (в отличие от writeVCard,
     * которая шлёт ВСЁ, включая приватные CRM-поля владельца). Сюда идёт
     * только то, что уместно передать другому человеку как визитку: имя,
     * телефоны, email, компания/должность. Явно НЕ включены: мессенджеры,
     * адрес, день рождения, заметки/nextStep/talkingPoints/canHelpWith/
     * iCanHelpWith/meetContext/tags — они приватны для владельца (владелец
     * подтвердил этот состав явно, 2026-07-22).
     */
    private fun writeShareVCard(pw: PrintWriter, c: Contact, state: PersistedState) {
        pw.println("BEGIN:VCARD")
        pw.println("VERSION:3.0")
        writeNameFields(pw, c)
        writeOrgFields(pw, c, state)
        writePhoneFields(pw, c)
        writeEmailFields(pw, c)
        pw.println("END:VCARD")
        pw.println()
    }

    /** Один контакт → облегчённая .vcf специально для «Поделиться» (см. writeShareVCard). Контакт передан
     *  явно вызывающим (какую карточку шарить решает UI) — snapshot захватывается заново только для
     *  company-lookup внутри writeOrgFields (requirement 7), сам объект contact не перечитывается. */
    suspend fun exportContactShareVCard(context: Context, contact: Contact): File =
        withContext(Dispatchers.IO) {
            val state = AppStateStore.readPersistedState()
            cleanOldExports(context)
            val safe = "${contact.firstName}_${contact.lastName}"
                .replace(Regex("[^A-Za-z0-9_]"), "")
            val file = File(exportsDir(context), "contact_${safe.ifBlank { "card" }}_$ts.vcf")
            PrintWriter(FileWriter(file)).use { pw -> writeShareVCard(pw, contact, state) }
            file
        }

    suspend fun exportVCard(context: Context): File = withContext(Dispatchers.IO) {
        buildContactsVcfFile(context, AppStateStore.readPersistedState())
    }

    private fun buildContactsVcfFile(context: Context, state: PersistedState): File {
        cleanOldExports(context)
        val file = File(exportsDir(context), "contacts_$ts.vcf")
        PrintWriter(FileWriter(file)).use { pw ->
            state.contacts.forEach { writeVCard(pw, it, state) }
        }
        return file
    }

    /** Один контакт → .vcf (кнопка «Сохранить в телефон» на карточке). */
    suspend fun exportContactVCard(context: Context, contact: Contact): File =
        withContext(Dispatchers.IO) {
            val state = AppStateStore.readPersistedState()
            cleanOldExports(context)
            val safe = "${contact.firstName}_${contact.lastName}"
                .replace(Regex("[^A-Za-z0-9_]"), "")
            val file = File(exportsDir(context), "contact_${safe.ifBlank { "card" }}_$ts.vcf")
            PrintWriter(FileWriter(file)).use { pw -> writeVCard(pw, contact, state) }
            file
        }

    /** Открывает .vcf системным импортом в «Контакты» (ACTION_VIEW). В отличие
     *  от shareFile, ведёт прямо в приложение Контакты с предпросмотром импорта. */
    fun openVcfInContacts(context: Context, file: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/x-vcard")
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        return try { context.startActivity(intent); true }
        catch (e: Exception) { false }
    }

    // ─── Full JSON backup (полный, с восстановлением) ──────────
    // internal (не private) — виден round-trip тесту (ExportManagerTest) в том
    // же модуле, чтобы сериализовать BackupData напрямую, без прогона через
    // живой AppStateStore; наружу пакета/модуля видимость не меняется.
    internal val backupAdapter by lazy {
        com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
            .adapter(BackupData::class.java)
            .indent("  ")
    }

    /** BACKUP PACK #3: backup из ОДНОГО согласованного снимка Room — плоские и вложенные списки
     *  происходят из одного состояния и не могут разойтись. Память AppStateStore не читается. */
    private fun toBackupData(state: PersistedState): BackupData = BackupData(
        version = BACKUP_FORMAT_VERSION,
        roomSchemaVersion = state.schemaVersion,
        exportedAt = LocalDateTime.now().toString(),
        contacts = state.contacts,
        companies = state.companies,
        calendarItems = state.calendarItems,
        contactRelations = state.contactRelations,
        groups = state.groups,
        groupMembers = state.groupMembers,
        tags = state.tags,
        tagMembers = state.tagMembers,
        notes = state.notes,
        gifts = state.gifts,
        tombstones = state.tombstones
    )

    /** Порядок: все принятые записи -> барьер очереди -> read-транзакция Room -> сериализация. */
    private suspend fun buildBackup(): BackupData = toBackupData(AppStateStore.readPersistedState())

    // Бэкап как строка JSON — для прямого сохранения в файл через SAF.
    suspend fun backupJsonString(): String = withContext(Dispatchers.IO) {
        backupAdapter.toJson(buildBackup())
    }

    suspend fun exportJsonBackup(context: Context): File = withContext(Dispatchers.IO) {
        buildJsonBackupFile(context, AppStateStore.readPersistedState())
    }

    /** exportFullZip передаёт СЮДА уже захваченный ОБЩИЙ snapshot (requirement 9/10) — toBackupData
     *  уже была чистой функцией снимка, здесь просто не открываем ВТОРОЕ независимое чтение Room. */
    private fun buildJsonBackupFile(context: Context, state: PersistedState): File {
        cleanOldExports(context)
        val file = File(exportsDir(context), "backup_$ts.json")
        file.writeText(backupAdapter.toJson(toBackupData(state)))
        return file
    }

    fun parseJsonBackup(json: String): BackupData? =
        try { backupAdapter.fromJson(json) } catch (e: Exception) { null }

    /** Результат импорта полного backup (BACKUP PACK #3): отказ ДО любой записи либо отчёт о restore. */
    sealed class BackupImportOutcome {
        /** Файл не распознан как backup SocialSphere (не JSON / версия вне поддерживаемого диапазона снизу). */
        object NotBackup : BackupImportOutcome()
        /** Backup создан более новой версией формата или схемы Room — частичный best-effort импорт запрещён. */
        data class TooNew(val backupVersion: Int, val backupSchemaVersion: Int) : BackupImportOutcome()
        data class Restored(val result: RestoreResult) : BackupImportOutcome()
    }

    /** Проверка совместимости — СТРОГО до любой записи. null = совместим. */
    private fun rejection(data: BackupData): BackupImportOutcome? {
        if (data.version < MIN_BACKUP_FORMAT_VERSION) return BackupImportOutcome.NotBackup
        if (data.version > BACKUP_FORMAT_VERSION) return BackupImportOutcome.TooNew(data.version, data.roomSchemaVersion)
        if (data.roomSchemaVersion > 0) {
            val current = AppStateStore.roomSchemaVersion()
            if (current != null && data.roomSchemaVersion > current)
                return BackupImportOutcome.TooNew(data.version, data.roomSchemaVersion)
        }
        return null
    }

    /**
     * Восстановление из полного бэкапа — НЕ-деструктивное (BACKUP INTEGRITY PACK #3). Семантика:
     *  • пустая БД (нет пользовательских данных) — backup воссоздаётся 1:1;
     *  • есть данные — data-safe слияние по устойчивым id: текущее не заменяется старым, не затирается
     *    null/значением по умолчанию, current-only не удаляется, расхождения сообщаются в результате
     *    (см. AppStateStore.restoreBackup / RestoreMerge.kt).
     * Отказ (не backup / более новый формат или схема) происходит ДО единственной транзакции — ноль записей.
     *
     * BATCH 4 (4-001): функция suspend и возвращает управление ТОЛЬКО после реального коммита в Room
     * (одна транзакция на весь бэкап + await + перечитывание памяти); сбой посередине откатывает всё.
     */
    private fun buildRestorePlan(data: BackupData): RestorePlan = RestorePlan(
        companies = data.companies,
        contacts = data.contacts,
        calendarItems = data.calendarItems,
        contactRelations = data.contactRelations,
        groups = data.groups,
        groupMembers = data.groupMembers,
        tags = data.tags,
        tagMembers = data.tagMembers,
        // Плоские top-level notes/gifts (§44 и фикс 2026-08-12): подарки
        // и заметки живут в собственных таблицах, addContactDb их не
        // пишет, поэтому попадают в план явно, а не только вложенными
        // в Contact — restoreBackup объединяет оба источника по id.
        notes = data.notes,
        gifts = data.gifts,
        // EXPLICIT RECOVERY PACK: история удалений бэкапа — переносится в SAFE MERGE/SELECTIVE/RESTORE_ALL
        // одинаково (см. RestoreMerge.kt); авторизация решает, что ДЕЛАТЬ с локальными tombstone, не с этими.
        tombstones = data.tombstones
    )

    suspend fun importJsonBackupDetailed(
        json: String,
        authorization: RestoreAuthorization = RestoreAuthorization.SafeMerge,
    ): BackupImportOutcome {
        val data = parseJsonBackup(json) ?: return BackupImportOutcome.NotBackup
        rejection(data)?.let { return it }
        val result = AppStateStore.restoreBackup(buildRestorePlan(data), authorization = authorization)
        return BackupImportOutcome.Restored(result)
    }

    /** RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22): READ-ONLY анализ — НИ ОДНОЙ мутации Room. Fresh
     *  (действительно пустая установка, включая tombstones — «всё удалено» это НЕ клин-инсталл, см. §68) —
     *  быстрый путь «Восстановить всю резервную копию» (requirement 27), preview для нескольких пустых списков
     *  не несёт ценности. Непустая установка — ВСЕГДА preview, никакого auto-merge сразу после выбора файла
     *  (requirement 28 — ключевое изменение UX этого пакета). */
    sealed class RestoreAnalysisOutcome {
        object NotBackup : RestoreAnalysisOutcome()
        data class TooNew(val backupVersion: Int, val backupSchemaVersion: Int) : RestoreAnalysisOutcome()
        object EmptyDatabase : RestoreAnalysisOutcome()
        data class Preview(val preview: RestorePreview) : RestoreAnalysisOutcome()
    }

    suspend fun analyzeBackupForRestore(json: String): RestoreAnalysisOutcome {
        val data = parseJsonBackup(json) ?: return RestoreAnalysisOutcome.NotBackup
        when (val r = rejection(data)) {
            is BackupImportOutcome.TooNew -> return RestoreAnalysisOutcome.TooNew(r.backupVersion, r.backupSchemaVersion)
            is BackupImportOutcome.NotBackup -> return RestoreAnalysisOutcome.NotBackup
            else -> Unit
        }
        val current = AppStateStore.readPersistedState()
        val trulyEmpty = current.contacts.isEmpty() && current.companies.isEmpty() && current.calendarItems.isEmpty() &&
            current.notes.isEmpty() && current.gifts.isEmpty() && current.tombstones.isEmpty()
        if (trulyEmpty) return RestoreAnalysisOutcome.EmptyDatabase
        return RestoreAnalysisOutcome.Preview(
            RestoreDiffEngine.analyze(current, buildRestorePlan(data))
        )
    }

    /** Применяет выбор пользователя из preview атомарно (requirement 22) — текущее чтение Room, ЧИСТЫЙ план,
     *  одна транзакция AppStateStore.applySelectiveRestore. Возвращает число реально применённых записей. */
    suspend fun applyRestoreSelection(
        preview: RestorePreview,
        selectedKeys: Set<String>,
    ): Int {
        val current = AppStateStore.readPersistedState()
        val plan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys)
        return AppStateStore.applySelectiveRestore(plan)
    }

    /** Совместимая обёртка: число контактов в backup, либо -1 (не backup / более новый формат). */
    suspend fun importJsonBackup(json: String): Int =
        when (val o = importJsonBackupDetailed(json)) {
            is BackupImportOutcome.Restored -> o.result.contactsInBackup
            else -> -1
        }

    /**
     * EXPLICIT RECOVERY PACK: что можно предложить пользователю на выбор для SELECTIVE RECOVERY — записи, которые
     * ТЕКУЩАЯ установка считает удалёнными (tombstone), но реально присутствуют в ДАННЫХ этого backup. Тот же
     * список целиком — это и есть содержимое RESTORE_ALL_DELETED. Отказ (не backup/новее схемы) — как и у обычного
     * restore, до любого чтения текущего состояния.
     */
    suspend fun recoverableRecords(json: String): List<RecoverableRecord>? {
        val data = parseJsonBackup(json) ?: return null
        rejection(data)?.let { return null }
        val current = AppStateStore.readPersistedState()
        val plan = RestorePlan(
            companies = data.companies, contacts = data.contacts, calendarItems = data.calendarItems,
            contactRelations = data.contactRelations, groups = data.groups, groupMembers = data.groupMembers,
            tags = data.tags, tagMembers = data.tagMembers, notes = data.notes, gifts = data.gifts,
            tombstones = data.tombstones,
        )
        return recoverableCandidates(current, plan)
    }
    // полем — импорт трогает СТРОГО одну категорию, даже если в файле случайно
    // окажутся другие (защита от случайной порчи остального при неверном файле).

    suspend fun backupJsonStringNotes(): String = withContext(Dispatchers.IO) {
        backupAdapter.toJson(BackupData(
            version = 5, exportedAt = LocalDateTime.now().toString(),
            notes = AppStateStore.readPersistedState().notes
        ))
    }

    suspend fun exportNotesJson(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "notes_$ts.json")
        file.writeText(backupJsonStringNotes())
        file
    }

    /** Восстанавливает ТОЛЬКО заметки из файла (остальные категории в файле,
     *  если есть, игнорируются). Возвращает число заметок, -1 если файл не бэкап.
     *  BATCH 4 (4-002): suspend + одна транзакция, как importJsonBackup —
     *  возврат означает подтверждённую запись, а не постановку в очередь. */
    suspend fun importNotesJson(json: String): Int {
        val data = parseJsonBackup(json) ?: return -1
        if (rejection(data) != null) return -1
        AppStateStore.restoreBackup(RestorePlan(notes = data.notes))
        return data.notes.size
    }

    suspend fun backupJsonStringCalendar(): String = withContext(Dispatchers.IO) {
        backupAdapter.toJson(BackupData(
            version = 5, exportedAt = LocalDateTime.now().toString(),
            calendarItems = AppStateStore.readPersistedState().calendarItems
        ))
    }

    suspend fun exportCalendarJson(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "calendar_$ts.json")
        file.writeText(backupJsonStringCalendar())
        file
    }

    /** Восстанавливает ТОЛЬКО события календаря (остальные категории игнорируются).
     *  BATCH 4 (4-003): suspend + одна транзакция, как importJsonBackup.
     *  Семантика самих событий (recurrence, напоминания, статус, связи) не
     *  тронута — меняется только момент, когда функция сообщает об успехе. */
    suspend fun importCalendarJson(json: String): Int {
        val data = parseJsonBackup(json) ?: return -1
        if (rejection(data) != null) return -1
        AppStateStore.restoreBackup(RestorePlan(calendarItems = data.calendarItems))
        return data.calendarItems.size
    }

    // ─── Full ZIP backup ───────────────────────────────────────
    // EXPORT SNAPSHOT CONSISTENCY PACK (2026-09-23): ОДИН snapshot для ВСЕХ 4 внутренних файлов
    // (requirement 9) — раньше каждый из 4 exportXxx(context) делал СВОЁ независимое чтение
    // (CSV/CSV/VCF — живую память, JSON — свой отдельный readPersistedState()), поэтому запись,
    // случившаяся МЕЖДУ ними, могла дать архив со смешанным состоянием (T3/T4/T5 задачи). Теперь
    // снимок захватывается ОДИН раз, builder-функции — чистые от снимка, второго чтения Room нет.
    suspend fun exportFullZip(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val zip = File(exportsDir(context), "socialsphere_backup_$ts.zip")
        val state = AppStateStore.readPersistedState()
        exportZipSnapshotCapturedHookForTests?.invoke()

        val csv  = buildContactsCsvFile(context, state)
        val comp = buildCompaniesCsvFile(context, state)
        val vcf  = buildContactsVcfFile(context, state)
        val json = buildJsonBackupFile(context, state)

        ZipOutputStream(zip.outputStream()).use { zos ->
            listOf(csv, comp, vcf, json).forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        // Clean temp files
        listOf(csv, comp, vcf, json).forEach { it.delete() }
        zip
    }

    // ─── Helpers ───────────────────────────────────────────────
    private fun csvRow(vararg fields: String): String =
        fields.joinToString(",") { field ->
            if (field.contains(",") || field.contains("\"") || field.contains("\n"))
                "\"${field.replace("\"", "\"\"")}\""
            else field
        }

    private fun json(s: String?): String =
        if (s == null) "null"
        else "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
