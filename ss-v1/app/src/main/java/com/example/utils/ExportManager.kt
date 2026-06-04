package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.AppStateStore
import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
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
    suspend fun exportVCard(context: Context): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "contacts_$ts.vcf")
        PrintWriter(FileWriter(file)).use { pw ->
            AppStateStore.contacts.forEach { c ->
                pw.println("BEGIN:VCARD")
                pw.println("VERSION:3.0")
                pw.println("N:${c.lastName};${c.firstName};;;")
                pw.println("FN:${c.firstName} ${c.lastName}".trim())

                val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                    ?: c.companyRelations.firstOrNull()
                val company = compRel?.companyId
                    ?.let { AppStateStore.getCompany(it)?.name } ?: ""
                if (company.isNotBlank() || !compRel?.position.isNullOrBlank()) {
                    pw.println("ORG:${company}")
                    if (!compRel?.position.isNullOrBlank())
                        pw.println("TITLE:${compRel?.position}")
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
                    pw.println("X-${m.type.name}:${m.value}")
                }

                val addr = AppStateStore.addresses.find {
                    it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT
                }
                if (addr != null) {
                    pw.println("ADR:;;${addr.addressLine};${addr.city};;;${addr.country}")
                }

                val birthday = AppStateStore.calendarItems.find {
                    it.type == CalendarItemType.BIRTHDAY &&
                    it.links.any { l -> l.targetId == c.id }
                }?.startDate
                if (!birthday.isNullOrBlank())
                    pw.println("BDAY:${birthday.replace("-", "")}")

                val notes = c.notes.take(3).joinToString(" | ") { it.text }
                if (notes.isNotBlank())
                    pw.println("NOTE:${notes.replace("\n", " ")}")

                pw.println("END:VCARD")
                pw.println()
            }
        }
        file
    }

    // ─── Full JSON backup ──────────────────────────────────────
    suspend fun exportJsonBackup(context: Context): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "backup_$ts.json")
        FileWriter(file).use { fw ->
            fw.write("{\n")
            fw.write("  \"version\": 1,\n")
            fw.write("  \"exportedAt\": \"${LocalDateTime.now()}\",\n")
            fw.write("  \"contacts\": [\n")
            AppStateStore.contacts.forEachIndexed { i, c ->
                fw.write("    {\n")
                fw.write("      \"id\": ${json(c.id)},\n")
                fw.write("      \"firstName\": ${json(c.firstName)},\n")
                fw.write("      \"lastName\": ${json(c.lastName)},\n")
                fw.write("      \"relationshipType\": ${json(c.relationshipType.name)},\n")
                fw.write("      \"importanceLevel\": ${json(c.importanceLevel.name)},\n")
                fw.write("      \"phones\": [${c.phones.joinToString { json(it.number) }}],\n")
                fw.write("      \"emails\": [${c.emails.joinToString { json(it.email) }}],\n")
                fw.write("      \"createdAt\": ${json(c.createdAt)}\n")
                fw.write("    }${if (i < AppStateStore.contacts.lastIndex) "," else ""}\n")
            }
            fw.write("  ],\n")
            fw.write("  \"companies\": [\n")
            AppStateStore.companies.forEachIndexed { i, c ->
                fw.write("    {\n")
                fw.write("      \"id\": ${json(c.id)},\n")
                fw.write("      \"name\": ${json(c.name)},\n")
                fw.write("      \"industry\": ${json(c.industry.name)}\n")
                fw.write("    }${if (i < AppStateStore.companies.lastIndex) "," else ""}\n")
            }
            fw.write("  ],\n")
            fw.write("  \"contactsCount\": ${AppStateStore.contacts.size},\n")
            fw.write("  \"companiesCount\": ${AppStateStore.companies.size}\n")
            fw.write("}\n")
        }
        file
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
