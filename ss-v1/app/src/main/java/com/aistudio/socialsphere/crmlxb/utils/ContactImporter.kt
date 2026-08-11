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

/** Смежный контакт из телефонной книги: имя + роль (уже по-русски, как ContactRelation). */
data class ImportedRelation(val name: String, val role: String)

/** Android Relation.TYPE (int) → русская роль. Используется как ContactRelation.role
 *  (данные, не локализуются, см. §16 базы знаний) и как читаемый текст заметки,
 *  если контакт с таким именем не найден в приложении. */
private fun androidRelationRole(type: Int, customLabel: String?): String = when (type) {
    // Пол неизвестен из данных Android — "Партнёр" уже в закрытом словаре ролей
    // (§16 базы знаний), не изобретаем новое значение вроде "Супруг(а)"
    ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE,
    ContactsContract.CommonDataKinds.Relation.TYPE_DOMESTIC_PARTNER,
    ContactsContract.CommonDataKinds.Relation.TYPE_PARTNER -> "Партнёр"
    ContactsContract.CommonDataKinds.Relation.TYPE_FATHER -> "Отец"
    ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER -> "Мать"
    ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER -> "Брат"
    ContactsContract.CommonDataKinds.Relation.TYPE_SISTER -> "Сестра"
    ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND -> "Друг"
    ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER,
    ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT -> "Коллега"
    ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM -> customLabel?.trim()?.takeIf { it.isNotBlank() } ?: "Родственник"
    // CHILD/PARENT/RELATIVE/REFERRED_BY — пол/точная роль неизвестны из данных Android
    else -> "Родственник"
}

data class ImportContactCandidate(
    val id: String = UUID.randomUUID().toString(),
    val firstName: String = "",
    val lastName: String = "",
    /** Отчество/среднее имя. */
    val middleName: String = "",
    /** Структура имени как в Android (v13) — раньше приставка/суффикс молча
     *  склеивались в middleName, теряя структуру (фидбэк владельца: «хочу как
     *  в андроид, идентично»). */
    val namePrefix: String = "",
    val nameSuffix: String = "",
    val phoneticFirstName: String = "",
    val phoneticMiddleName: String = "",
    val phoneticLastName: String = "",
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val companyName: String? = null,
    val jobTitle: String? = null,
    val addresses: List<Address> = emptyList(),
    val birthday: String? = null,
    val notes: String? = null,
    /** Названия групп телефонной книги («Семья», «Работа»…) — фидбэк 2026-07-04:
     *  «При импорте ты группой импортируешь телефона?» Системные авто-группы
     *  Google («System Group: My Contacts» и т.п.) отфильтрованы. */
    val groupNames: List<String> = emptyList(),
    /** Смежные контакты/семья (ContactsContract.CommonDataKinds.Relation) —
     *  «жена: Анна», «друг: Олег» и т.п. Раньше не читались вообще (фидбэк
     *  владельца 2026-07-04). role — Русская строка-данные (см. §16
     *  SOCIALSPHERE_KNOWLEDGE.md: роли семьи не локализуются, это данные). */
    val relations: List<ImportedRelation> = emptyList(),
    val source: String = "Телефонная книга",
    var duplicateStatus: DuplicateStatus = DuplicateStatus.NEW,
    var selectedForImport: Boolean = true,
    var matchedContactId: String? = null
)

object ContactImporter {

    /** Карта GROUP_ROW_ID → название группы, с фильтром авто-групп Google
     *  («System Group: My Contacts», «Starred in Android» и т.п.). */
    private fun readGroupTitles(context: Context): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE, ContactsContract.Groups.AUTO_ADD, ContactsContract.Groups.FAVORITES),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.Groups._ID)
            val titleIdx = c.getColumnIndex(ContactsContract.Groups.TITLE)
            val autoIdx = c.getColumnIndex(ContactsContract.Groups.AUTO_ADD)
            val favIdx = c.getColumnIndex(ContactsContract.Groups.FAVORITES)
            while (c.moveToNext()) {
                val title = c.getString(titleIdx)?.trim() ?: continue
                val isAuto = autoIdx >= 0 && c.getInt(autoIdx) != 0
                val isFav = favIdx >= 0 && c.getInt(favIdx) != 0
                if (title.isBlank() || title.startsWith("System Group:") || isAuto || isFav) continue
                map[c.getLong(idIdx)] = title
            }
        }
        return map
    }

    fun getDeviceContacts(context: Context): List<ImportContactCandidate> {
        val candidates = mutableMapOf<String, ImportContactCandidate>()
        val groupTitles = readGroupTitles(context)

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
                        // Полная структура StructuredName, как в Android-контактах:
                        // DATA2=имя, DATA3=фамилия, DATA4=приставка, DATA5=отчество,
                        // DATA6=суффикс, DATA7=фонетическое имя, DATA9=фонетическая
                        // фамилия. ФИКС (2026-07-04): раньше приставка/суффикс молча
                        // склеивались в middleName — теряя структуру. Теперь каждое
                        // поле хранится отдельно (владелец: «хочу как в андроид,
                        // идентично»).
                        val givenName  = it.getString(data2Idx)
                        val familyName = it.getString(data3Idx)
                        val displayName = it.getString(data1Idx)
                        val data5Idx = it.getColumnIndex(ContactsContract.Data.DATA5)
                        val data6Idx = it.getColumnIndex(ContactsContract.Data.DATA6)
                        val data7Idx = it.getColumnIndex(ContactsContract.Data.DATA7)
                        val data8Idx = it.getColumnIndex(ContactsContract.Data.DATA8)
                        val data9Idx = it.getColumnIndex(ContactsContract.Data.DATA9)
                        val middle = if (data5Idx >= 0) it.getString(data5Idx) ?: "" else ""
                        val prefix = if (data4Idx >= 0) it.getString(data4Idx) ?: "" else ""
                        val suffix = if (data6Idx >= 0) it.getString(data6Idx) ?: "" else ""
                        val phoneticFirst = if (data7Idx >= 0) it.getString(data7Idx) ?: "" else ""
                        val phoneticMiddle = if (data8Idx >= 0) it.getString(data8Idx) ?: "" else ""
                        val phoneticLast  = if (data9Idx >= 0) it.getString(data9Idx) ?: "" else ""

                        var first  = (givenName ?: "").trim()
                        var last   = (familyName ?: "").trim()
                        var mid    = middle.trim()
                        // Структурных полей нет — раскладываем displayName без потерь:
                        // 1 слово → имя; 2 → имя+фамилия; 3+ → имя + середина + фамилия
                        if (first.isBlank() && last.isBlank() && !displayName.isNullOrBlank()) {
                            val words = displayName.trim().split(Regex("\\s+"))
                            first = words.first()
                            last  = if (words.size >= 2) words.last() else ""
                            if (mid.isBlank() && words.size >= 3)
                                mid = words.subList(1, words.size - 1).joinToString(" ")
                        }
                        candidates[contactId] = candidate.copy(
                            firstName  = candidate.firstName.takeIf { it.isNotBlank() } ?: first,
                            lastName   = candidate.lastName.takeIf { it.isNotBlank() } ?: last,
                            middleName = candidate.middleName.takeIf { it.isNotBlank() } ?: mid,
                            namePrefix = candidate.namePrefix.takeIf { it.isNotBlank() } ?: prefix.trim(),
                            nameSuffix = candidate.nameSuffix.takeIf { it.isNotBlank() } ?: suffix.trim(),
                            phoneticFirstName = candidate.phoneticFirstName.takeIf { it.isNotBlank() } ?: phoneticFirst.trim(),
                            phoneticMiddleName = candidate.phoneticMiddleName.takeIf { it.isNotBlank() } ?: phoneticMiddle.trim(),
                            phoneticLastName  = candidate.phoneticLastName.takeIf { it.isNotBlank() } ?: phoneticLast.trim()
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
                        // Район (2026-07-13) — StructuredPostal.NEIGHBORHOOD, раньше терялся
                        // при импорте (читались только STREET/CITY/COUNTRY).
                        val neighborhoodIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD)

                        val street = if (streetIdx >= 0) it.getString(streetIdx) else null
                        val city = if (cityIdx >= 0) it.getString(cityIdx) else null
                        val country = if (countryIdx >= 0) it.getString(countryIdx) else null
                        val formattedAddress = if (formattedAddrIdx >= 0) it.getString(formattedAddrIdx) else null
                        val neighborhood = if (neighborhoodIdx >= 0) it.getString(neighborhoodIdx) else null

                        val addrLine = street ?: formattedAddress ?: ""
                        if (addrLine.isNotBlank() || !city.isNullOrBlank() || !country.isNullOrBlank()) {
                            val addr = Address(
                                id = UUID.randomUUID().toString(),
                                addressLine = addrLine,
                                district = neighborhood?.takeIf { n -> n.isNotBlank() },
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
                    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> {
                        // DATA1 = имя смежного контакта, DATA2 = TYPE (int), DATA3 = свой label
                        val relName = it.getString(data1Idx)?.trim()
                        if (!relName.isNullOrBlank()) {
                            val relType = it.getInt(data2Idx)
                            val customLabel = if (data3Idx >= 0) it.getString(data3Idx) else null
                            val role = androidRelationRole(relType, customLabel)
                            candidates[contactId] = candidate.copy(relations = candidate.relations + ImportedRelation(relName, role))
                        }
                    }
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                        // DATA1 = GROUP_ROW_ID (ссылка на ContactsContract.Groups._ID)
                        val groupRowId = it.getLong(data1Idx)
                        val title = groupTitles[groupRowId]
                        if (title != null && title !in candidate.groupNames) {
                            candidates[contactId] = candidate.copy(groupNames = candidate.groupNames + title)
                        }
                    }
                }
            }
        }
        
        // ФИКС (аудит 2026-08-11, жалоба владельца: «постоянно добавляются какие-то
        // контакты типа групп/компаний, непонятно что» — «мы это уже решали, и всё
        // равно появляется»): этот фильтр УЖЕ применён в parseVCard/parseCsv («Группы/
        // метки телефонной книги приходят как карточки без контактов» — Урок,
        // регресс-тесты vcard_cardWithoutPhoneOrEmail_isDropped/
        // csv_rowWithoutNameOrPhone_isSkipped), но никогда не был применён здесь —
        // getDeviceContacts() отдавал АБСОЛЮТНО ВСЕ строки ContactsContract.Data,
        // включая ярлыки групп синхронизации, WhatsApp/другие сервис-аккаунты и
        // прочие непользовательские записи без единого телефона/email. Тот же
        // класс бага, что уже чинили — просто в третьем (и главном) из трёх путей
        // импорта, который не был затронут прошлым фиксом.
        return candidates.values.filter { it.phones.isNotEmpty() || it.emails.isNotEmpty() }
    }
}

// ─── vCard (.vcf) parser ──────────────────────────────────────
fun parseVCard(content: String): List<ImportContactCandidate> {
    val results = mutableListOf<ImportContactCandidate>()
    // Split on BEGIN:VCARD
    val cards = content.split("BEGIN:VCARD").drop(1)
    cards.forEach { card ->
        var firstName  = ""
        var lastName   = ""
        var middleName = ""
        var namePrefix = ""
        var nameSuffix = ""
        var phoneticFirstName = ""
        var phoneticMiddleName = ""
        var phoneticLastName  = ""
        val phones    = mutableListOf<ContactPhone>()
        val emails    = mutableListOf<ContactEmail>()
        var company   = ""
        var title     = ""
        var birthday  = ""
        val id        = UUID.randomUUID().toString()

        card.lines().forEach { raw ->
            val line = raw.trim()
            when {
                // N:Фамилия;Имя;Отчество;Префикс;Суффикс (RFC 2426, 5 частей) —
                // раньше префикс/суффикс молча склеивались в middleName, теряя
                // структуру (v13: хранятся отдельно, как в Android-контактах).
                // Матчим и «N;CHARSET=…:».
                line.startsWith("N:") || line.startsWith("N;") -> {
                    val parts = line.substringAfter(":", "").split(";")
                    lastName  = parts.getOrElse(0) { "" }.trim()
                    firstName = parts.getOrElse(1) { "" }.trim()
                    middleName = parts.getOrElse(2) { "" }.trim()
                    namePrefix = parts.getOrElse(3) { "" }.trim()
                    nameSuffix = parts.getOrElse(4) { "" }.trim()
                }
                line.startsWith("X-PHONETIC-FIRST-NAME") ->
                    phoneticFirstName = line.substringAfter(":", "").trim()
                line.startsWith("X-PHONETIC-MIDDLE-NAME") ->
                    phoneticMiddleName = line.substringAfter(":", "").trim()
                line.startsWith("X-PHONETIC-LAST-NAME") ->
                    phoneticLastName = line.substringAfter(":", "").trim()
                // FN: полное имя (fallback, если N не было) — без потери слов:
                // 3+ слова → имя + середина(отчество) + фамилия
                (line.startsWith("FN:") || line.startsWith("FN;")) &&
                    firstName.isBlank() && lastName.isBlank() -> {
                    val full = line.substringAfter(":", "").trim().split(Regex("\\s+"))
                    firstName = full.firstOrNull() ?: ""
                    lastName  = if (full.size >= 2) full.last() else ""
                    if (full.size >= 3)
                        middleName = full.subList(1, full.size - 1).joinToString(" ")
                }
                // TEL
                line.startsWith("TEL") -> {
                    val number = line.substringAfter(":").trim()
                    if (number.isNotBlank() &&
                        phones.none { it.number.filter(Char::isDigit) == number.filter(Char::isDigit) }) {
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
                    if (addr.isNotBlank() && emails.none { it.email.equals(addr, ignoreCase = true) }) {
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
                // Урок 45: vCard ORG = «Компания;Отдел;…» — берём только первое поле;
                // плюс поддержка параметров вида ORG;CHARSET=UTF-8:
                line.startsWith("ORG:") || line.startsWith("ORG;") ->
                    company = line.substringAfter(":", "").substringBefore(";").trim()
                line.startsWith("TITLE:") || line.startsWith("TITLE;") ->
                    title = line.substringAfter(":", "").trim()
                // BDAY: 19900312 / 1990-03-12 / --0312 (без года).
                // ВАЖНО: матчим и «BDAY;…:» — телефоны экспортируют с параметрами
                // (BDAY;VALUE=DATE:…, эппловский BDAY;X-APPLE-OMIT-YEAR=1604:…) —
                // раньше такие строки пропускались и часть ДР не импортировалась.
                line.startsWith("BDAY:") || line.startsWith("BDAY;") -> {
                    val raw = line.substringAfter(":", "").trim()
                    val pre = when {
                        raw.length == 8 && !raw.contains("-") && !raw.startsWith("--") ->
                            "${raw.take(4)}-${raw.substring(4, 6)}-${raw.takeLast(2)}"
                        else -> raw
                    }
                    val normalized = normalizeBirthday(pre)
                    // X-APPLE-OMIT-YEAR: год в дате фиктивный (1604) — убираем его
                    birthday = when {
                        normalized == null -> ""
                        line.contains("X-APPLE-OMIT-YEAR", ignoreCase = true) &&
                            !normalized.startsWith("--") && normalized.length >= 10 ->
                            "--" + normalized.substring(5, 10) // yyyy-MM-dd → --MM-DD
                        else -> normalized
                    }
                }
            }
        }

        // Группы/метки телефонной книги («Семья», «Друзья», «Бизнес») приходят
        // как карточки с именем, но без телефона и email — отсекаем их.
        // Берём запись, только если есть хотя бы телефон или email.
        if (phones.isNotEmpty() || emails.isNotEmpty()) {
            results.add(ImportContactCandidate(
                id          = id,
                firstName   = firstName,
                lastName    = lastName,
                middleName  = middleName,
                namePrefix  = namePrefix,
                nameSuffix  = nameSuffix,
                phoneticFirstName = phoneticFirstName,
                phoneticMiddleName = phoneticMiddleName,
                phoneticLastName  = phoneticLastName,
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

    // Урок 44: contains-матчинг цеплял «Organization 1 - Title/Type» как компанию —
    // профессии становились компаниями. Точное совпадение приоритетнее подстроки,
    // плюс исключения: заголовок с title/type/должн не может быть компанией.
    fun colIdx(vararg names: String, exclude: List<String> = emptyList()): Int {
        fun ok(h: String) = exclude.none { h.contains(it) }
        // 1. точное совпадение
        names.forEach { name ->
            val i = headers.indexOfFirst { it == name && ok(it) }
            if (i >= 0) return i
        }
        // 2. подстрока — только если заголовок не исключён
        names.forEach { name ->
            val i = headers.indexOfFirst { it.contains(name) && ok(it) }
            if (i >= 0) return i
        }
        return -1
    }

    val idxFirst   = colIdx("имя", "firstname", "first name", "given name", "name", "название",
                            exclude = listOf("org", "company", "файл", "middle"))
    val idxLast    = colIdx("фамилия", "lastname", "last name", "family name", "surname")
    val idxMiddle  = colIdx("отчество", "middle name", "middlename", "additional name")
    val idxPhone   = colIdx("телефон", "phone", "mobile", "tel")
    val idxEmail   = colIdx("email", "почта", "e-mail")
    val idxCompany = colIdx("компания", "company", "организация", "org",
                            exclude = listOf("title", "type", "должн", "depart", "symbol", "location"))
    val idxTitle   = colIdx("должность", "title", "position", "job")
    val idxCity    = colIdx("город", "city")

    lines.drop(1).forEach { line ->
        val cols = splitCsvLine(line)
        if (cols.isEmpty()) return@forEach

        fun col(idx: Int) = if (idx >= 0 && idx < cols.size) cols[idx].trim() else ""

        val firstName = col(idxFirst)
        val lastName  = col(idxLast)
        val middle    = col(idxMiddle)
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
            middleName  = middle,
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
 *  «--MM-DD» и «--MMDD» (без года) сохраняются КАК «--MM-DD» — год неизвестен,
 *  и раньше подставлявшийся фиктивный 1972 показывал ложный возраст. Формат
 *  «--MM-DD» понимают parseFlexibleDate/displayEventDate (CalendarUtils).
 *  «yyyy-MM-dd…» → первые 10 символов; иначе пробуем нестрогий числовой
 *  dd<sep>MM<sep>yyyy — некоторые OEM-синки (Xiaomi/Samsung/Huawei и т.п.)
 *  пишут Event.START_DATE не по ISO, тот же класс формата, что уже учтён в
 *  parseFlexibleDate (CalendarUtils.kt) для отображения, но раньше не был учтён
 *  здесь — из-за строгого LocalDate.parse(ISO) такие дни рождения тихо
 *  пропускались при импорте (баг, найден по жалобе владельца: «не все дни
 *  рождения проимпортировались»). */
internal fun normalizeBirthday(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    val yearless = when {
        s.startsWith("--") && s.length >= 7 && s[4] == '-' ->
            s.substring(2, 7)                                   // --MM-DD → MM-DD
        s.startsWith("--") && s.length >= 6 ->
            s.substring(2, 4) + "-" + s.substring(4, 6)         // --MMDD → MM-DD
        else -> null
    }
    if (yearless != null) {
        // Валидация через високосный год (29 февраля — валидная дата без года)
        return try {
            java.time.LocalDate.parse("1972-$yearless"); "--$yearless"
        } catch (e: Exception) { null }
    }
    if (s.length >= 10) {
        val candidate = s.take(10)
        try {
            java.time.LocalDate.parse(candidate)
            return candidate
        } catch (e: Exception) { /* falls through to legacy numeric formats */ }
    }
    Regex("""^(\d{1,2})[ ./](\d{1,2})[ ./](\d{4})$""").find(s)?.let { m ->
        val (d, mo, y) = m.destructured
        return try {
            java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).toString()
        } catch (e: Exception) { null }
    }
    return null
}
