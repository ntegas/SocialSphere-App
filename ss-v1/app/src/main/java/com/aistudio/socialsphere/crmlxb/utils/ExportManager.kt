package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
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

/**
 * Полный снимок данных приложения для бэкапа/восстановления.
 * Контакты сериализуются целиком, со всеми вложенными полями
 * (телефоны/email/мессенджеры/адреса/заметки/подарки/размеры/
 * персональные детали/связи с компаниями). Плюс компании, события
 * календаря и контакт-контакт связи (семья).
 */
@JsonClass(generateAdapter = true)
data class BackupData(
    val version: Int = 6,
    val exportedAt: String = "",
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
    val gifts: List<GiftIdea> = emptyList()
)

object ExportManager {

    private val ts get() = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))

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
    suspend fun exportContactsCsv(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "contacts_$ts.csv")
        PrintWriter(FileWriter(file)).use { pw ->
            pw.println(context.getString(R.string.export_csv_contacts_header))
            AppStateStore.contacts.forEach { c ->
                val phone   = c.phones.find { it.isPrimary }?.number
                    ?: c.phones.firstOrNull()?.number ?: ""
                val email   = c.emails.find { it.isPrimary }?.email
                    ?: c.emails.firstOrNull()?.email ?: ""
                val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                    ?: c.companyRelations.firstOrNull()
                val company  = compRel?.companyId
                    ?.let { AppStateStore.getCompany(it)?.name } ?: ""
                val position = compRel?.position ?: ""
                val city     = AppStateStore.addresses.find {
                    it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT
                }?.city ?: ""
                pw.println(
                    csvRow(c.firstName, c.lastName, phone, email,
                        company, position, city,
                        c.relationshipType.name, c.importanceLevel.name)
                )
            }
        }
        file
    }

    // ─── CSV Companies ─────────────────────────────────────────
    suspend fun exportCompaniesCsv(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "companies_$ts.csv")
        PrintWriter(FileWriter(file)).use { pw ->
            pw.println(context.getString(R.string.export_csv_companies_header))
            AppStateStore.companies.forEach { c ->
                val city = AppStateStore.addresses.find {
                    it.ownerId == c.id && it.ownerType == AddressOwnerType.COMPANY
                }?.city ?: ""
                val contactCount = AppStateStore.companyRelations
                    .count { it.companyId == c.id }
                pw.println(
                    csvRow(c.name, c.industry.name, city,
                        c.website ?: "", c.description ?: "",
                        contactCount.toString())
                )
            }
        }
        file
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

    // ORG/TITLE — общие для полной и урезанной vCard.
    private fun writeOrgFields(pw: PrintWriter, c: Contact) {
        val compRel = c.companyRelations.firstOrNull { it.isPrimary }
            ?: c.companyRelations.firstOrNull()
        val company = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
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

    private fun writeVCard(pw: PrintWriter, c: Contact) {
        pw.println("BEGIN:VCARD")
        pw.println("VERSION:3.0")
        writeNameFields(pw, c)
        if (!c.nickname.isNullOrBlank()) pw.println("NICKNAME:${vEsc(c.nickname)}")
        // Фонетические имя/фамилия — нестандартное X-поле (нет фиксированного
        // тега в vCard 3.0), но Android умеет читать X-PHONETIC-*.
        if (!c.phoneticFirstName.isNullOrBlank()) pw.println("X-PHONETIC-FIRST-NAME:${vEsc(c.phoneticFirstName)}")
        if (!c.phoneticMiddleName.isNullOrBlank()) pw.println("X-PHONETIC-MIDDLE-NAME:${vEsc(c.phoneticMiddleName)}")
        if (!c.phoneticLastName.isNullOrBlank()) pw.println("X-PHONETIC-LAST-NAME:${vEsc(c.phoneticLastName)}")

        writeOrgFields(pw, c)
        writePhoneFields(pw, c)
        writeEmailFields(pw, c)

        c.messengers.forEach { m -> pw.println("X-${m.type.name}:${vEsc(m.value)}") }

        // ВСЕ адреса контакта с типом (раньше уходил только первый и без типа)
        AppStateStore.addresses
            .filter { it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT }
            .forEach { a ->
                // Компонент 5 (region) vCard ADR — район (раньше всегда пустой).
                pw.println("ADR;TYPE=${adrType(a.addressType)}:;;${vEsc(a.addressLine)};" +
                    "${vEsc(a.city)};${vEsc(a.district)};${vEsc(a.postalCode)};${vEsc(a.country)}")
            }

        val birthday = AppStateStore.calendarItems.find {
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
        ContactNoteCodec.encode(c)?.let { pw.println("NOTE:${vEsc(it)}") }

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
    private fun writeShareVCard(pw: PrintWriter, c: Contact) {
        pw.println("BEGIN:VCARD")
        pw.println("VERSION:3.0")
        writeNameFields(pw, c)
        writeOrgFields(pw, c)
        writePhoneFields(pw, c)
        writeEmailFields(pw, c)
        pw.println("END:VCARD")
        pw.println()
    }

    /** Один контакт → облегчённая .vcf специально для «Поделиться» (см. writeShareVCard). */
    suspend fun exportContactShareVCard(context: Context, contact: Contact): File =
        withContext(Dispatchers.IO) {
            cleanOldExports(context)
            val safe = "${contact.firstName}_${contact.lastName}"
                .replace(Regex("[^A-Za-z0-9_]"), "")
            val file = File(exportsDir(context), "contact_${safe.ifBlank { "card" }}_$ts.vcf")
            PrintWriter(FileWriter(file)).use { pw -> writeShareVCard(pw, contact) }
            file
        }

    suspend fun exportVCard(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "contacts_$ts.vcf")
        PrintWriter(FileWriter(file)).use { pw ->
            AppStateStore.contacts.forEach { writeVCard(pw, it) }
        }
        file
    }

    /** Один контакт → .vcf (кнопка «Сохранить в телефон» на карточке). */
    suspend fun exportContactVCard(context: Context, contact: Contact): File =
        withContext(Dispatchers.IO) {
            cleanOldExports(context)
            val safe = "${contact.firstName}_${contact.lastName}"
                .replace(Regex("[^A-Za-z0-9_]"), "")
            val file = File(exportsDir(context), "contact_${safe.ifBlank { "card" }}_$ts.vcf")
            PrintWriter(FileWriter(file)).use { pw -> writeVCard(pw, contact) }
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

    private fun buildBackup(): BackupData = BackupData(
        version = 6,
        exportedAt = LocalDateTime.now().toString(),
        contacts = AppStateStore.contacts.toList(),
        companies = AppStateStore.companies.toList(),
        calendarItems = AppStateStore.calendarItems.toList(),
        contactRelations = AppStateStore.contactRelations.toList(),
        groups = AppStateStore.groups.toList(),
        groupMembers = AppStateStore.groupMembers.toList(),
        tags = AppStateStore.tags.toList(),
        tagMembers = AppStateStore.tagMembers.toList(),
        notes = AppStateStore.notes.toList(),
        gifts = AppStateStore.gifts.toList()
    )

    // Бэкап как строка JSON — для прямого сохранения в файл через SAF.
    suspend fun backupJsonString(): String = withContext(Dispatchers.IO) {
        backupAdapter.toJson(buildBackup())
    }

    suspend fun exportJsonBackup(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "backup_$ts.json")
        val data = buildBackup()
        file.writeText(backupAdapter.toJson(data))
        file
    }

    fun parseJsonBackup(json: String): BackupData? =
        try { backupAdapter.fromJson(json) } catch (e: Exception) { null }

    /**
     * Восстановление из полного бэкапа. Upsert по id: существующее
     * обновляется, новое добавляется, НИЧЕГО не удаляется.
     * Возвращает число восстановленных контактов, либо -1 если файл
     * не распознан как бэкап SocialSphere.
     */
    fun importJsonBackup(json: String): Int {
        val data = parseJsonBackup(json) ?: return -1
        // Лёгкая проверка целостности: версия формата в разумных границах.
        // Отсекает мусор/чужой JSON, который Moshi случайно распарсил.
        // (Криптоподпись для локального личного бэкапа намеренно вне рамок.)
        if (data.version < 1 || data.version > 6) return -1
        data.companies.forEach { co ->
            if (AppStateStore.companies.any { it.id == co.id }) AppStateStore.updateCompany(co)
            else AppStateStore.addCompany(co)
        }
        data.contacts.forEach { c ->
            AppStateStore.restoreContact(c)
        }
        // restoreCalendarItem (не updateCalendarItem/addCalendarItem) — те
        // штампуют новый updatedAt/createdAt, затирая исходные даты бэкапа
        // (тот же класс правки, что restoreContact уже применяет для контактов).
        data.calendarItems.forEach { ci -> AppStateStore.restoreCalendarItem(ci) }
        data.contactRelations.forEach { r ->
            if (AppStateStore.contactRelations.none { it.id == r.id }) AppStateStore.addContactRelation(r)
        }
        data.groups.forEach { g -> AppStateStore.restoreGroup(g) }
        data.groupMembers.forEach { m -> AppStateStore.restoreGroupMember(m) }
        data.tags.forEach { t -> AppStateStore.restoreTag(t) }
        data.tagMembers.forEach { m -> AppStateStore.restoreTagMember(m) }
        // ФИКС (§44): раньше заметки восстанавливались ТОЛЬКО как побочный эффект
        // вложенного Contact.notes внутри restoreContact — но restoreContact's
        // DB-запись (addContactDb) не трогает таблицу notes вообще, так что
        // заметки не переживали следующий холодный старт; заметки компаний
        // вообще не попадали в бэкап (Company.notes не существует). Теперь —
        // явно, из плоского top-level списка, с реальной записью в БД.
        data.notes.forEach { n -> AppStateStore.restoreNote(n) }
        // ФИКС (2026-08-12): та же дыра, что была у заметок (§44) — подарки жили
        // только вложенными в Contact, addContactDb/restoreContact их не писали
        // в giftDao, поэтому не переживали переустановку. См. BackupData.gifts.
        data.gifts.forEach { g -> AppStateStore.restoreGift(g) }
        return data.contacts.size
    }

    // ─── Частичное резервное копирование (заметки/календарь по отдельности) ──
    // Владелец: «постоянно страдают заметки и календарь» — вместо того, чтобы
    // каждый раз восстанавливать ВСЮ базу, можно сохранить/восстановить только
    // одну категорию. Тот же JSON-формат (BackupData), но с одним заполненным
    // полем — импорт трогает СТРОГО одну категорию, даже если в файле случайно
    // окажутся другие (защита от случайной порчи остального при неверном файле).

    suspend fun backupJsonStringNotes(): String = withContext(Dispatchers.IO) {
        backupAdapter.toJson(BackupData(
            version = 5, exportedAt = LocalDateTime.now().toString(),
            notes = AppStateStore.notes.toList()
        ))
    }

    suspend fun exportNotesJson(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "notes_$ts.json")
        file.writeText(backupJsonStringNotes())
        file
    }

    /** Восстанавливает ТОЛЬКО заметки из файла (остальные категории в файле,
     *  если есть, игнорируются). Возвращает число заметок, -1 если файл не бэкап. */
    fun importNotesJson(json: String): Int {
        val data = parseJsonBackup(json) ?: return -1
        if (data.version < 1 || data.version > 6) return -1
        data.notes.forEach { n -> AppStateStore.restoreNote(n) }
        return data.notes.size
    }

    suspend fun backupJsonStringCalendar(): String = withContext(Dispatchers.IO) {
        backupAdapter.toJson(BackupData(
            version = 5, exportedAt = LocalDateTime.now().toString(),
            calendarItems = AppStateStore.calendarItems.toList()
        ))
    }

    suspend fun exportCalendarJson(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(exportsDir(context), "calendar_$ts.json")
        file.writeText(backupJsonStringCalendar())
        file
    }

    /** Восстанавливает ТОЛЬКО события календаря (остальные категории игнорируются). */
    fun importCalendarJson(json: String): Int {
        val data = parseJsonBackup(json) ?: return -1
        if (data.version < 1 || data.version > 6) return -1
        data.calendarItems.forEach { ci -> AppStateStore.restoreCalendarItem(ci) }
        return data.calendarItems.size
    }

    // ─── Full ZIP backup ───────────────────────────────────────
    suspend fun exportFullZip(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val zip  = File(exportsDir(context), "socialsphere_backup_$ts.zip")
        val csv  = exportContactsCsv(context)
        val comp = exportCompaniesCsv(context)
        val vcf  = exportVCard(context)
        val json = exportJsonBackup(context)

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
