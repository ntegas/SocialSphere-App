package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import com.aistudio.socialsphere.crmlxb.model.*
import java.util.UUID

enum class DuplicateStatus {
    NEW,
    POSSIBLE_DUPLICATE,
    WILL_UPDATE,
    SKIPPED
}

data class ImportContactCandidate(
    val id: String = UUID.randomUUID().toString(),
    val firstName: String = "",
    val lastName: String = "",
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val companyName: String? = null,
    val jobTitle: String? = null,
    val addresses: List<Address> = emptyList(),
    val birthday: String? = null,
    val notes: String? = null,
    val source: String = "Телефонная книга",
    var duplicateStatus: DuplicateStatus = DuplicateStatus.NEW,
    var selectedForImport: Boolean = true,
    var matchedContactId: String? = null
)

object ContactImporter {

    fun getDeviceContacts(context: Context): List<ImportContactCandidate> {
        val candidates = mutableMapOf<String, ImportContactCandidate>()

        val contentResolver = context.contentResolver
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            null,
            null,
            null
        )

        cursor?.use {
            val contactIdIdx = it.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimeTypeIdx = it.getColumnIndex(ContactsContract.Data.MIMETYPE)
            
            // Name
            val data1Idx = it.getColumnIndex(ContactsContract.Data.DATA1)
            val data2Idx = it.getColumnIndex(ContactsContract.Data.DATA2)
            val data3Idx = it.getColumnIndex(ContactsContract.Data.DATA3)
            val data4Idx = it.getColumnIndex(ContactsContract.Data.DATA4) // sometimes used
            
            while (it.moveToNext()) {
                val contactId = it.getString(contactIdIdx)
                val mimeType = it.getString(mimeTypeIdx)

                val candidate = candidates.getOrPut(contactId) {
                    ImportContactCandidate(id = "device_contact_$contactId")
                }

                when (mimeType) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        val givenName = it.getString(data2Idx)
                        val familyName = it.getString(data3Idx)
                        val displayName = it.getString(data1Idx)
                        
                        val first = givenName ?: if (!familyName.isNullOrBlank()) "" else displayName ?: ""
                        val last = familyName ?: ""
                        
                        candidates[contactId] = candidate.copy(
                            firstName = candidate.firstName.takeIf { it.isNotBlank() } ?: first.trim(),
                            lastName = candidate.lastName.takeIf { it.isNotBlank() } ?: last.trim()
                        )
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val phoneNumber = it.getString(data1Idx)
                        val type = it.getInt(data2Idx)
                        if (!phoneNumber.isNullOrBlank()) {
                            val phoneType = when (type) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> PhoneType.MOBILE
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> PhoneType.WORK
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> PhoneType.HOME
                                else -> PhoneType.OTHER
                            }
                            val cp = ContactPhone(
                                id = UUID.randomUUID().toString(),
                                contactId = candidate.id,
                                number = phoneNumber,
                                type = phoneType,
                                isPrimary = candidate.phones.isEmpty()
                            )
                            candidates[contactId] = candidate.copy(phones = candidate.phones + cp)
                        }
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val email = it.getString(data1Idx)
                        val type = it.getInt(data2Idx)
                        if (!email.isNullOrBlank()) {
                            val emailType = when (type) {
                                ContactsContract.CommonDataKinds.Email.TYPE_WORK -> EmailType.WORK
                                ContactsContract.CommonDataKinds.Email.TYPE_HOME -> EmailType.PERSONAL
                                else -> EmailType.OTHER
                            }
                            val ce = ContactEmail(
                                id = UUID.randomUUID().toString(),
                                contactId = candidate.id,
                                email = email,
                                type = emailType,
                                isPrimary = candidate.emails.isEmpty()
                            )
                            candidates[contactId] = candidate.copy(emails = candidate.emails + ce)
                        }
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        val company = it.getString(data1Idx)
                        val title = it.getString(data4Idx)
                        candidates[contactId] = candidate.copy(
                            companyName = company,
                            jobTitle = title
                        )
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val streetIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET)
                        val cityIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY)
                        val countryIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY)
                        val formattedAddrIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)

                        val street = if (streetIdx >= 0) it.getString(streetIdx) else null
                        val city = if (cityIdx >= 0) it.getString(cityIdx) else null
                        val country = if (countryIdx >= 0) it.getString(countryIdx) else null
                        val formattedAddress = if (formattedAddrIdx >= 0) it.getString(formattedAddrIdx) else null
                        
                        val addrLine = street ?: formattedAddress ?: ""
                        if (addrLine.isNotBlank() || !city.isNullOrBlank() || !country.isNullOrBlank()) {
                            val addr = Address(
                                id = UUID.randomUUID().toString(),
                                addressLine = addrLine,
                                city = city ?: "",
                                country = country ?: "",
                                ownerId = candidate.id,
                                ownerType = AddressOwnerType.CONTACT,
                                addressType = AddressType.HOME
                            )
                            candidates[contactId] = candidate.copy(addresses = candidate.addresses + addr)
                        }
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val type = it.getInt(data2Idx)
                        if (type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) {
                            val raw = it.getString(data1Idx)
                            // Телефонная книга часто отдаёт «--MM-DD» (без года) —
                            // такой формат ломал парсинг и события исчезали
                            val date = normalizeBirthday(raw)
                            if (date != null) {
                                candidates[contactId] = candidate.copy(birthday = date)
                            }
                        }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        val text = it.getString(data1Idx)
                        if (!text.isNullOrBlank()) {
                            candidates[contactId] = candidate.copy(notes = text)
                        }
                    }
                }
            }
        }
        
        return candidates.values.toList()
    }
}

// ─── vCard (.vcf) parser ──────────────────────────────────────
fun parseVCard(content: String): List<ImportContactCandidate> {
    val results = mutableListOf<ImportContactCandidate>()
    // Split on BEGIN:VCARD
    val cards = content.split("BEGIN:VCARD").drop(1)
    cards.forEach { card ->
        var firstName = ""
        var lastName  = ""
        val phones    = mutableListOf<ContactPhone>()
        val emails    = mutableListOf<ContactEmail>()
        var company   = ""
        var title     = ""
        var birthday  = ""
        val id        = UUID.randomUUID().toString()

        card.lines().forEach { raw ->
            val line = raw.trim()
            when {
                // N:LastName;FirstName;;;
                line.startsWith("N:") -> {
                    val parts = line.removePrefix("N:").split(";")
                    lastName  = parts.getOrElse(0) { "" }.trim()
                    firstName = parts.getOrElse(1) { "" }.trim()
                }
                // FN: full name fallback
                line.startsWith("FN:") && firstName.isBlank() && lastName.isBlank() -> {
                    val full = line.removePrefix("FN:").trim().split(" ")
                    firstName = full.firstOrNull() ?: ""
                    lastName  = full.drop(1).joinToString(" ")
                }
                // TEL
                line.startsWith("TEL") -> {
                    val number = line.substringAfter(":").trim()
                    if (number.isNotBlank()) {
                        val phoneType = when {
                            line.contains("WORK", ignoreCase = true)   -> PhoneType.WORK
                            line.contains("HOME", ignoreCase = true)   -> PhoneType.HOME
                            else                                        -> PhoneType.MOBILE
                        }
                        phones.add(ContactPhone(
                            id        = UUID.randomUUID().toString(),
                            contactId = id,
                            number    = number,
                            type      = phoneType,
                            isPrimary = phones.isEmpty()
                        ))
                    }
                }
                // EMAIL
                line.startsWith("EMAIL") -> {
                    val addr = line.substringAfter(":").trim()
                    if (addr.isNotBlank()) {
                        val emailType = when {
                            line.contains("WORK", ignoreCase = true) -> EmailType.WORK
                            else                                      -> EmailType.PERSONAL
                        }
                        emails.add(ContactEmail(
                            id        = UUID.randomUUID().toString(),
                            contactId = id,
                            email     = addr,
                            type      = emailType,
                            isPrimary = emails.isEmpty()
                        ))
                    }
                }
                // ORG
                line.startsWith("ORG:")   -> company = line.removePrefix("ORG:").trim()
                // TITLE
                line.startsWith("TITLE:") -> title = line.removePrefix("TITLE:").trim()
                // BDAY: 19900312 or 1990-03-12
                line.startsWith("BDAY:") -> {
                    val raw = line.removePrefix("BDAY:").trim()
                    val pre = when {
                        raw.length == 8 && !raw.contains("-") ->
                            "${raw.take(4)}-${raw.substring(4, 6)}-${raw.takeLast(2)}"
                        else -> raw
                    }
                    birthday = normalizeBirthday(pre) ?: ""
                }
            }
        }

        // Only add if we have at least a name or phone
        if (firstName.isNotBlank() || lastName.isNotBlank() || phones.isNotEmpty()) {
            results.add(ImportContactCandidate(
                id          = id,
                firstName   = firstName,
                lastName    = lastName,
                phones      = phones,
                emails      = emails,
                companyName = company.ifBlank { null },
                jobTitle    = title.ifBlank { null },
                birthday    = birthday.ifBlank { null },
                source      = "vCard"
            ))
        }
    }
    return results
}

// ─── CSV parser ───────────────────────────────────────────────
// Expected columns (flexible, detects by header):
// Имя / FirstName, Фамилия / LastName, Телефон / Phone, Email, Компания / Company, Должность / Title
fun parseCsv(content: String): List<ImportContactCandidate> {
    val results = mutableListOf<ImportContactCandidate>()
    val lines   = content.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return results

    // Parse header
    val headers = splitCsvLine(lines[0]).map { it.trim().lowercase() }

    fun colIdx(vararg names: String): Int =
        names.firstNotNullOfOrNull { name ->
            headers.indexOfFirst { it.contains(name) }.takeIf { it >= 0 }
        } ?: -1

    val idxFirst   = colIdx("имя", "firstname", "first name", "name", "название")
    val idxLast    = colIdx("фамилия", "lastname", "last name", "surname")
    val idxPhone   = colIdx("телефон", "phone", "mobile", "tel")
    val idxEmail   = colIdx("email", "почта", "e-mail")
    val idxCompany = colIdx("компания", "company", "организация", "org")
    val idxTitle   = colIdx("должность", "title", "position", "job")
    val idxCity    = colIdx("город", "city")

    lines.drop(1).forEach { line ->
        val cols = splitCsvLine(line)
        if (cols.isEmpty()) return@forEach

        fun col(idx: Int) = if (idx >= 0 && idx < cols.size) cols[idx].trim() else ""

        val firstName = col(idxFirst)
        val lastName  = col(idxLast)
        val phone     = col(idxPhone)
        val email     = col(idxEmail)
        val company   = col(idxCompany)
        val title     = col(idxTitle)

        if (firstName.isBlank() && lastName.isBlank() && phone.isBlank()) return@forEach

        val id = UUID.randomUUID().toString()
        val phones = if (phone.isNotBlank()) listOf(
            ContactPhone(UUID.randomUUID().toString(), id, phone, PhoneType.MOBILE, true)
        ) else emptyList()
        val emails = if (email.isNotBlank()) listOf(
            ContactEmail(UUID.randomUUID().toString(), id, email, EmailType.PERSONAL, true)
        ) else emptyList()

        results.add(ImportContactCandidate(
            id          = id,
            firstName   = firstName,
            lastName    = lastName,
            phones      = phones,
            emails      = emails,
            companyName = company.ifBlank { null },
            jobTitle    = title.ifBlank { null },
            source      = "CSV"
        ))
    }
    return results
}

private fun splitCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && !inQuotes -> inQuotes = true
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                current.append('"'); i++ // escaped quote
            }
            c == '"' && inQuotes  -> inQuotes = false
            c == ',' && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
            else -> current.append(c)
        }
        i++
    }
    result.add(current.toString())
    return result

}

/** Нормализация дня рождения из телефонной книги / vCard.
 *  «--MM-DD» и «--MMDD» (без года) → «1972-MM-DD» (1972 високосный —
 *  29 февраля валидно); «yyyy-MM-dd…» → первые 10 символов; иначе null. */
internal fun normalizeBirthday(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val candidate = when {
        raw.startsWith("--") && raw.length >= 7 && raw[4] == '-' ->
            "1972-" + raw.substring(2, 7)                       // --MM-DD
        raw.startsWith("--") && raw.length >= 6 ->
            "1972-" + raw.substring(2, 4) + "-" + raw.substring(4, 6) // --MMDD
        raw.length >= 10 -> raw.take(10)
        else -> return null
    }
    return try {
        java.time.LocalDate.parse(candidate); candidate
    } catch (e: Exception) { null }
}
