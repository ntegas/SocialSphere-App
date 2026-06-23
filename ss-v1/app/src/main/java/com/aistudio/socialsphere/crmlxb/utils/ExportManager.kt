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

    suspend fun exportVCard(context: Context): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "contacts_$ts.vcf")
        PrintWriter(FileWriter(file)).use { pw ->
            AppStateStore.contacts.forEach { c ->
                pw.println("BEGIN:VCARD")
                pw.println("VERSION:3.0")
                pw.println("N:${vEsc(c.lastName)};${vEsc(c.firstName)};;;")
                pw.println("FN:${vEsc("${c.firstName} ${c.lastName}".trim())}")

                val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                    ?: c.companyRelations.firstOrNull()
                val company = compRel?.companyId
                    ?.let { AppStateStore.getCompany(it)?.name } ?: ""
                if (company.isNotBlank() || !compRel?.position.isNullOrBlank()) {
                    pw.println("ORG:${vEsc(company)}")
                    if (!compRel?.position.isNullOrBlank())
                        pw.println("TITLE:${vEsc(compRel?.position)}")
                }

                c.phones.forEach { p ->
                    val type = when (p.type) {
                        PhoneType.WORK   -> "WORK"
                        PhoneType.HOME   -> "HOME"
                        else             -> "CELL"
                    }
                    pw.println("TEL;TYPE=$type:${p.number}")
                }

                c.emails.forEach { e ->
                    val type = when (e.type) {
                        EmailType.WORK -> "WORK"
                        else           -> "HOME"
                    }
                    pw.println("EMAIL;TYPE=$type:${e.email}")
                }

                c.messengers.forEach { m ->
                    pw.println("X-${m.type.name}:${vEsc(m.value)}")
                }

                val addr = AppStateStore.addresses.find {
                    it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT
                }
                if (addr != null) {
                    pw.println("ADR:;;${vEsc(addr.addressLine)};${vEsc(addr.city)};;;${vEsc(addr.country)}")
                }

                val birthday = AppStateStore.calendarItems.find {
                    it.type == CalendarItemType.BIRTHDAY &&
                    it.links.any { l -> l.targetId == c.id }
                }?.startDate
                if (!birthday.isNullOrBlank())
                    pw.println("BDAY:${birthday.replace("-", "")}")

                val notes = c.notes.take(3).joinToString(" | ") { it.text }
                if (notes.isNotBlank())
                    pw.println("NOTE:${vEsc(notes)}")

                pw.println("END:VCARD")
                pw.println()
            }
        }
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
