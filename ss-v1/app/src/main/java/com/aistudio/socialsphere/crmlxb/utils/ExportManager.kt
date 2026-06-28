package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
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
data class BackupData(
    val version: Int = 4,
    val exportedAt: String = "",
    val contacts: List<Contact> = emptyList(),
    val companies: List<Company> = emptyList(),
    val calendarItems: List<CalendarItem> = emptyList(),
    val contactRelations: List<ContactRelation> = emptyList()
)

object ExportManager {

    private val ts get() = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))

    // Безопасность (skill: insecure-data-storage): экспорт = вся PII в ОТКРЫТОМ
    // виде в cacheDir. Чтобы она там не оставалась навсегда — перед каждым новым
    // экспортом удаляем прошлые наши temp-файлы старше часа (только что созданный
    // не трогаем, он ещё нужен для шаринга).
    private val exportPrefixes = listOf("contacts_", "companies_", "backup_", "socialsphere_backup_")
    private fun cleanOldExports(context: Context) {
        val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
        try {
            context.cacheDir.listFiles()?.forEach { f ->
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
        if (!ExternalActionHandler.startIntentSafely(context, Intent.createChooser(intent, "Поделиться файлом"))) {
            android.widget.Toast.makeText(context, "Не удалось открыть меню «Поделиться»", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ─── CSV Contacts ──────────────────────────────────────────
    suspend fun exportContactsCsv(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(context.cacheDir, "contacts_$ts.csv")
        PrintWriter(FileWriter(file)).use { pw ->
            pw.println("Имя,Фамилия,Телефон,Email,Компания,Должность,Город,Тип,Важность")
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
        val file = File(context.cacheDir, "companies_$ts.csv")
        PrintWriter(FileWriter(file)).use { pw ->
            pw.println("Название,Индустрия,Город,Сайт,Описание,Количество контактов")
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
    private fun writeVCard(pw: PrintWriter, c: Contact) {
        pw.println("BEGIN:VCARD")
        pw.println("VERSION:3.0")
        pw.println("N:${vEsc(c.lastName)};${vEsc(c.firstName)};;;")
        pw.println("FN:${vEsc("${c.firstName} ${c.lastName}".trim())}")
        if (!c.nickname.isNullOrBlank()) pw.println("NICKNAME:${vEsc(c.nickname)}")

        val compRel = c.companyRelations.firstOrNull { it.isPrimary }
            ?: c.companyRelations.firstOrNull()
        val company = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
        if (company.isNotBlank() || !compRel?.position.isNullOrBlank()) {
            pw.println("ORG:${vEsc(company)}")
            if (!compRel?.position.isNullOrBlank())
                pw.println("TITLE:${vEsc(compRel?.position)}")
        }

        c.phones.forEach { p ->
            val type = when (p.type) {
                PhoneType.WORK -> "WORK"
                PhoneType.HOME -> "HOME"
                else           -> "CELL"
            }
            val pref = if (p.isPrimary) ",PREF" else ""
            pw.println("TEL;TYPE=$type$pref:${p.number}")
        }

        c.emails.forEach { e ->
            val type = when (e.type) {
                EmailType.WORK -> "WORK"
                else           -> "HOME"
            }
            val pref = if (e.isPrimary) ",PREF" else ""
            pw.println("EMAIL;TYPE=$type$pref:${e.email}")
        }

        c.messengers.forEach { m -> pw.println("X-${m.type.name}:${vEsc(m.value)}") }

        // ВСЕ адреса контакта с типом (раньше уходил только первый и без типа)
        AppStateStore.addresses
            .filter { it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT }
            .forEach { a ->
                pw.println("ADR;TYPE=${adrType(a.addressType)}:;;${vEsc(a.addressLine)};" +
                    "${vEsc(a.city)};;${vEsc(a.postalCode)};${vEsc(a.country)}")
            }

        val birthday = AppStateStore.calendarItems.find {
            it.type == CalendarItemType.BIRTHDAY &&
            it.links.any { l -> l.targetId == c.id }
        }?.startDate
        if (!birthday.isNullOrBlank())
            pw.println("BDAY:${birthday.replace("-", "")}")

        // NOTE: все заметки + app-поля (нет в стандарте vCard — кладём текстом)
        val noteParts = mutableListOf<String>()
        c.notes.forEach { noteParts.add(it.text) }
        if (!c.nextStep.isNullOrBlank())      noteParts.add("Следующий шаг: ${c.nextStep}")
        if (!c.talkingPoints.isNullOrBlank()) noteParts.add("Темы для разговора: ${c.talkingPoints}")
        if (!c.canHelpWith.isNullOrBlank())   noteParts.add("Может помочь: ${c.canHelpWith}")
        if (!c.iCanHelpWith.isNullOrBlank())  noteParts.add("Я могу помочь: ${c.iCanHelpWith}")
        if (!c.meetContext.isNullOrBlank())   noteParts.add("Где познакомились: ${c.meetContext}")
        if (c.tags.isNotEmpty())              noteParts.add("Теги: ${c.tags.joinToString(", ")}")
        if (noteParts.isNotEmpty())
            pw.println("NOTE:${vEsc(noteParts.joinToString("\n"))}")

        pw.println("END:VCARD")
        pw.println()
    }

    suspend fun exportVCard(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(context.cacheDir, "contacts_$ts.vcf")
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
            val file = File(context.cacheDir, "contact_${safe.ifBlank { "card" }}_$ts.vcf")
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
    private val backupAdapter by lazy {
        com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
            .adapter(BackupData::class.java)
            .indent("  ")
    }

    private fun buildBackup(): BackupData = BackupData(
        version = 4,
        exportedAt = LocalDateTime.now().toString(),
        contacts = AppStateStore.contacts.toList(),
        companies = AppStateStore.companies.toList(),
        calendarItems = AppStateStore.calendarItems.toList(),
        contactRelations = AppStateStore.contactRelations.toList()
    )

    // Бэкап как строка JSON — для прямого сохранения в файл через SAF.
    suspend fun backupJsonString(): String = withContext(Dispatchers.IO) {
        backupAdapter.toJson(buildBackup())
    }

    suspend fun exportJsonBackup(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val file = File(context.cacheDir, "backup_$ts.json")
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
        if (data.version < 1 || data.version > 5) return -1
        data.companies.forEach { co ->
            if (AppStateStore.companies.any { it.id == co.id }) AppStateStore.updateCompany(co)
            else AppStateStore.addCompany(co)
        }
        data.contacts.forEach { c ->
            AppStateStore.restoreContact(c)
        }
        data.calendarItems.forEach { ci ->
            if (AppStateStore.calendarItems.any { it.id == ci.id }) AppStateStore.updateCalendarItem(ci)
            else AppStateStore.addCalendarItem(ci)
        }
        data.contactRelations.forEach { r ->
            if (AppStateStore.contactRelations.none { it.id == r.id }) AppStateStore.addContactRelation(r)
        }
        return data.contacts.size
    }

    // ─── Full ZIP backup ───────────────────────────────────────
    suspend fun exportFullZip(context: Context): File = withContext(Dispatchers.IO) {
        cleanOldExports(context)
        val zip  = File(context.cacheDir, "socialsphere_backup_$ts.zip")
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
