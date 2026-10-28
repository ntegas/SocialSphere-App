package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import com.aistudio.socialsphere.crmlxb.data.ContactChannelDedup
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
    var matchedContactId: String? = null,
    /** Проверка «уже есть в приложении» для этого кандидата выполнена (см. ImportDuplicateCheck) — второй раз не трогаем. */
    val duplicateChecked: Boolean = false,
    /** PHONE PACK #4: LOOKUP_KEY контакта телефонной книги (только у кандидатов «из книги устройства»). */
    val deviceLookupKey: String? = null
)

object ContactImporter {

    private const val TAG = "ContactImporter"

    /** Явный список колонок Data вместо `null` (= ВСЕ колонки всех строк книги): меньше памяти и не зависит
     *  от того, какие лишние колонки отдаёт конкретный ContactsProvider. StructuredPostal/Organization/
     *  Relation читаются теми же DATA1..DATA10 (STREET=data4, NEIGHBORHOOD=data6, CITY=data7, COUNTRY=data10). */
    internal val DATA_PROJECTION: Array<String> = arrayOf(
        ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.LOOKUP_KEY,
        ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
        ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
        ContactsContract.Data.DATA7, ContactsContract.Data.DATA8, ContactsContract.Data.DATA9,
        ContactsContract.Data.DATA10,
    )

    private fun Cursor.str(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)
    private fun Cursor.int(i: Int): Int = if (i < 0 || isNull(i)) 0 else getInt(i)

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
            if (idIdx < 0 || titleIdx < 0) return map           // курсор без нужных колонок — группы просто не читаем
            while (c.moveToNext()) {
                val title = c.str(titleIdx)?.trim() ?: continue
                val isAuto = autoIdx >= 0 && c.int(autoIdx) != 0
                val isFav = favIdx >= 0 && c.int(favIdx) != 0
                if (title.isBlank() || title.startsWith("System Group:") || isAuto || isFav) continue
                map[c.getLong(idIdx)] = title
            }
        }
        return map
    }

    /**
     * Вся телефонная книга (массовый импорт и выбор контакта для связи).
     * Бросает [SecurityException], если READ_CONTACTS не выдано/отозвано — вызывающий обязан показать
     * состояние «нужно разрешение», а не падать. Всё остальное (курсор без колонки, null-поля, битые
     * строки) — читается безопасно, ничего наружу не бросает.
     */
    fun getDeviceContacts(context: Context): List<ImportContactCandidate> {
        val groupTitles = readGroupTitles(context)
        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, DATA_PROJECTION, null, null, null
        )
        val candidates = cursor?.use { readDataRows(it, groupTitles) } ?: emptyMap()

        // ФИКС (аудит 2026-08-11, жалоба владельца: «постоянно добавляются какие-то
        // контакты типа групп/компаний, непонятно что»): getDeviceContacts() отдавал ВСЕ строки
        // ContactsContract.Data, включая ярлыки групп синхронизации, WhatsApp/другие сервис-аккаунты
        // и прочие непользовательские записи без единого телефона/email. Тот же фильтр, что уже в
        // parseVCard/parseCsv.
        return candidates.values.filter { it.phones.isNotEmpty() || it.emails.isNotEmpty() }
    }

    /**
     * Общий разбор строк Data → кандидаты по CONTACT_ID (массовый импорт И чтение ОДНОГО связанного
     * контакта). Устойчив к битым данным: нет колонки CONTACT_ID/MIMETYPE — пустой результат; строка
     * без contact_id/mimetype или с исключением при чтении — пропускается, остальные читаются.
     */
    internal fun readDataRows(it: Cursor, groupTitles: Map<Long, String>): MutableMap<String, ImportContactCandidate> {
        val candidates = mutableMapOf<String, ImportContactCandidate>()
        val contactIdIdx = it.getColumnIndex(ContactsContract.Data.CONTACT_ID)
        val mimeTypeIdx = it.getColumnIndex(ContactsContract.Data.MIMETYPE)
        if (contactIdIdx < 0 || mimeTypeIdx < 0) {
            android.util.Log.w(TAG, "Data cursor without contact_id/mimetype columns — nothing to read")
            return candidates
        }
        val lookupIdx = it.getColumnIndex(ContactsContract.Data.LOOKUP_KEY)
        val data1Idx = it.getColumnIndex(ContactsContract.Data.DATA1)
        val data2Idx = it.getColumnIndex(ContactsContract.Data.DATA2)
        val data3Idx = it.getColumnIndex(ContactsContract.Data.DATA3)
        val data4Idx = it.getColumnIndex(ContactsContract.Data.DATA4)
        val data5Idx = it.getColumnIndex(ContactsContract.Data.DATA5)
        val data6Idx = it.getColumnIndex(ContactsContract.Data.DATA6)
        val data7Idx = it.getColumnIndex(ContactsContract.Data.DATA7)
        val data8Idx = it.getColumnIndex(ContactsContract.Data.DATA8)
        val data9Idx = it.getColumnIndex(ContactsContract.Data.DATA9)
        val data10Idx = it.getColumnIndex(ContactsContract.Data.DATA10)

        while (it.moveToNext()) {
            try {
                val contactId = it.str(contactIdIdx) ?: continue
                val mimeType = it.str(mimeTypeIdx) ?: continue
                val lookup = it.str(lookupIdx)?.takeIf { k -> k.isNotBlank() }

                var candidate = candidates.getOrPut(contactId) {
                    ImportContactCandidate(id = "device_contact_$contactId", deviceLookupKey = lookup)
                }
                if (candidate.deviceLookupKey == null && lookup != null) {
                    candidate = candidate.copy(deviceLookupKey = lookup); candidates[contactId] = candidate
                }

                when (mimeType) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        // Полная структура StructuredName, как в Android-контактах:
                        // DATA2=имя, DATA3=фамилия, DATA4=приставка, DATA5=отчество,
                        // DATA6=суффикс, DATA7=фонетическое имя, DATA9=фонетическая фамилия.
                        val givenName  = it.str(data2Idx)
                        val familyName = it.str(data3Idx)
                        val displayName = it.str(data1Idx)
                        val middle = it.str(data5Idx) ?: ""
                        val prefix = it.str(data4Idx) ?: ""
                        val suffix = it.str(data6Idx) ?: ""
                        val phoneticFirst = it.str(data7Idx) ?: ""
                        val phoneticMiddle = it.str(data8Idx) ?: ""
                        val phoneticLast  = it.str(data9Idx) ?: ""

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
                            firstName  = candidate.firstName.takeIf { n -> n.isNotBlank() } ?: first,
                            lastName   = candidate.lastName.takeIf { n -> n.isNotBlank() } ?: last,
                            middleName = candidate.middleName.takeIf { n -> n.isNotBlank() } ?: mid,
                            namePrefix = candidate.namePrefix.takeIf { n -> n.isNotBlank() } ?: prefix.trim(),
                            nameSuffix = candidate.nameSuffix.takeIf { n -> n.isNotBlank() } ?: suffix.trim(),
                            phoneticFirstName = candidate.phoneticFirstName.takeIf { n -> n.isNotBlank() } ?: phoneticFirst.trim(),
                            phoneticMiddleName = candidate.phoneticMiddleName.takeIf { n -> n.isNotBlank() } ?: phoneticMiddle.trim(),
                            phoneticLastName  = candidate.phoneticLastName.takeIf { n -> n.isNotBlank() } ?: phoneticLast.trim()
                        )
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val phoneNumber = it.str(data1Idx)
                        val type = it.int(data2Idx)
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
                            // Контакт книги = несколько raw-контактов (Google/WhatsApp/SIM…), каждый со
                            // своей строкой Data с ТЕМ ЖЕ номером -> без схлопывания номер дублируется.
                            candidates[contactId] = candidate.copy(
                                phones = ContactChannelDedup.dedupePhones(candidate.phones + cp)
                            )
                        }
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val email = it.str(data1Idx)
                        val type = it.int(data2Idx)
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
                            candidates[contactId] = candidate.copy(
                                emails = ContactChannelDedup.dedupeEmails(candidate.emails + ce)
                            )
                        }
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        candidates[contactId] = candidate.copy(
                            companyName = it.str(data1Idx),
                            jobTitle = it.str(data4Idx)
                        )
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val street = it.str(data4Idx)
                        val city = it.str(data7Idx)
                        val country = it.str(data10Idx)
                        val formattedAddress = it.str(data1Idx)
                        // Район (2026-07-13) — StructuredPostal.NEIGHBORHOOD (data6)
                        val neighborhood = it.str(data6Idx)

                        val addrLine = street ?: formattedAddress ?: ""
                        if (addrLine.isNotBlank() || !city.isNullOrBlank() || !country.isNullOrBlank()) {
                            // Тип адреса — реальный TYPE_HOME/TYPE_WORK/TYPE_OTHER (DATA2), не хардкод HOME
                            val addressType = when (it.int(data2Idx)) {
                                ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> AddressType.WORK
                                ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> AddressType.HOME
                                else -> AddressType.OTHER
                            }
                            val addr = Address(
                                id = UUID.randomUUID().toString(),
                                addressLine = addrLine,
                                district = neighborhood?.takeIf { n -> n.isNotBlank() },
                                city = city ?: "",
                                country = country ?: "",
                                ownerId = candidate.id,
                                ownerType = AddressOwnerType.CONTACT,
                                addressType = addressType
                            )
                            candidates[contactId] = candidate.copy(addresses = candidate.addresses + addr)
                        }
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        if (it.int(data2Idx) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) {
                            // Телефонная книга часто отдаёт «--MM-DD» (без года)
                            val date = normalizeBirthday(it.str(data1Idx))
                            if (date != null) candidates[contactId] = candidate.copy(birthday = date)
                        }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        val text = it.str(data1Idx)
                        if (!text.isNullOrBlank()) candidates[contactId] = candidate.copy(notes = text)
                    }
                    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> {
                        // DATA1 = имя смежного контакта, DATA2 = TYPE (int), DATA3 = свой label
                        val relName = it.str(data1Idx)?.trim()
                        if (!relName.isNullOrBlank()) {
                            val role = androidRelationRole(it.int(data2Idx), it.str(data3Idx))
                            candidates[contactId] = candidate.copy(relations = candidate.relations + ImportedRelation(relName, role))
                        }
                    }
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                        // DATA1 = GROUP_ROW_ID (ссылка на ContactsContract.Groups._ID)
                        val title = it.str(data1Idx)?.toLongOrNull()?.let { gid -> groupTitles[gid] }
                        if (title != null && title !in candidate.groupNames) {
                            candidates[contactId] = candidate.copy(groupNames = candidate.groupNames + title)
                        }
                    }
                }
            } catch (e: RuntimeException) {
                // Одна битая строка не должна ронять чтение всей книги/связанного контакта
                android.util.Log.w(TAG, "skipping malformed Data row", e)
            }
        }
        return candidates
    }
}

// ─── vCard (.vcf) parser ──────────────────────────────────────
/**
 * Разбор УЖЕ ДЕКОДИРОВАННОГО текста vCard. PHONE / VCF PACK #4: вся логика (RFC-разворачивание, QP, CHARSET,
 * base64, группы `item1.`, ADR…) живёт в [VcfImport]; входы приложения (Share и ручной выбор файла) читают БАЙТЫ
 * через [VcfImport.load]/[VcfImport.parse] и сюда не ходят. Эта функция остаётся для текстового ввода и тестов.
 */
fun parseVCard(content: String): List<ImportContactCandidate> = VcfImport.parseText(content)

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
