package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.aistudio.socialsphere.crmlxb.model.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * PHONE / VCF PACK #4 — ЕДИНЫЙ канонический путь чтения vCard.
 *
 *   ACTION_SEND / ACTION_VIEW (Share из Контактов, «Открыть в») ─┐
 *                                                                 ├─► [load] → ContentResolver.openInputStream
 *   ручной выбор файла (Импорт → vCard) ─────────────────────────┘        → БАЙТЫ → [parse] → кандидаты
 *
 * Почему БАЙТЫ. Раньше оба входа делали `openInputStream().bufferedReader().readText()` (жёстко UTF-8) и
 * отдавали строку в построчный разбор, который не знал ни про ENCODING=QUOTED-PRINTABLE, ни про CHARSET=…,
 * ни про UTF-16, ни про RFC-фолдинг: имена превращались в «=D0=90=D0=BB…» или в мусор, длинные строки
 * обрезались, base64-фото порождало «телефоны». Здесь декодирование идёт ПО СВОЙСТВАМ:
 *   1. документ: BOM UTF-8 (снимается) / UTF-16 (BOM или NUL-паттерн) — иначе байты 1:1 в Latin-1 «сырьём»;
 *   2. RFC-разворачивание строк (пробел/таб в начале) ПО БАЙТАМ — перенос может попасть внутрь UTF-8 символа;
 *      QP-мягкие переносы («=» в конце) и продолжения base64 (2.1, без отступа);
 *   3. значение каждого свойства декодируется по ENCODING (QP/BASE64) и CHARSET (по умолчанию UTF-8;
 *      без объявления и не UTF-8 — Latin-1 как lossless-фолбэк, кириллица в cp1251 без CHARSET не угадывается);
 *   4. только потом — интерпретация полей (имя, телефоны, email, ORG/TITLE, ADR, BDAY, NOTE).
 * Двоичные свойства (PHOTO/LOGO/KEY/SOUND) читаются как строки и игнорируются — соседние поля не портятся.
 *
 * Не полный RFC 6350: vCard 2.1/3.0 из Android/Google/Apple + 4.0 `TEL;VALUE=uri:tel:`.
 * Non-BMP (эмодзи) — поддерживается, если они корректно закодированы в UTF-8 (в т.ч. через QP).
 */
object VcfImport {

    /** Защита от OOM на «файле» неожиданного размера (vCard с фото — единицы МБ). */
    const val MAX_BYTES = 32 * 1024 * 1024

    sealed class Load {
        data class Loaded(val candidates: List<ImportContactCandidate>) : Load()
        /** Файл прочитан, но контактов (с телефоном/email) в нём нет. */
        object Empty : Load()
        data class Error(val message: String) : Load()
    }

    /** MIME без параметров (`text/x-vcard; charset=UTF-8`) — vCard ли это. */
    fun isVcardMime(type: String?): Boolean {
        val t = type?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return t == "text/vcard" || t == "text/x-vcard" || t == "text/directory"
    }

    /** URI из Share/«Открыть в». Файловый путь НЕ предполагается — только content://. */
    fun uriOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    /** КАНОНИЧЕСКИЙ вход: URI → байты (ContentResolver) → разбор. Оба входа (Share и ручной выбор) — только сюда. */
    fun load(context: Context, uri: Uri): Load = try {
        val candidates = parse(readBytes(context, uri))
        if (candidates.isEmpty()) Load.Empty else Load.Loaded(candidates)
    } catch (e: Exception) {
        Load.Error(e.localizedMessage ?: e.javaClass.simpleName)
    }

    /** Вход Share / «Открыть в»: null — интент не про vCard (ничего не делаем). */
    fun loadFromIntent(context: Context, intent: Intent): Load? {
        val isShare = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW
        if (!isShare || !isVcardMime(intent.type)) return null
        val uri = uriOf(intent) ?: return Load.Error("no uri in intent")
        return load(context, uri)
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("provider returned no stream")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("vCard file is too large")
            }
            return out.toByteArray()
        }
    }

    // ── разбор ───────────────────────────────────────────────

    /** Байты файла → кандидаты. */
    fun parse(bytes: ByteArray): List<ImportContactCandidate> = parseDocument(decodeDocument(bytes))

    /** Уже декодированный текст (legacy `parseVCard(String)`): побайтовое декодирование значений не нужно. */
    internal fun parseText(content: String): List<ImportContactCandidate> = parseDocument(Doc(content, rawBytes = false))

    private class Doc(val text: String, val rawBytes: Boolean)

    private fun decodeDocument(b: ByteArray): Doc {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size >= 2 && u(0) == 0xFF && u(1) == 0xFE) return Doc(String(b, 2, b.size - 2, Charsets.UTF_16LE), false)
        if (b.size >= 2 && u(0) == 0xFE && u(1) == 0xFF) return Doc(String(b, 2, b.size - 2, Charsets.UTF_16BE), false)
        if (b.size >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF)
            return Doc(String(b, 3, b.size - 3, Charsets.ISO_8859_1), true)
        // UTF-16 без BOM: в UTF-8/Latin-1 vCard нулевых байт не бывает вовсе, а в UTF-16 ASCII-часть («BEGIN:VCARD…»)
        // даёт нулевой байт в каждой паре — в начале файла (первые 256 байт) выбираем порядок байт по их положению.
        // (Порог по доле пар нельзя: текст с ранней кириллицей/греческим имеет ненулевые оба байта пары.)
        val probe = minOf(b.size, 256)
        if (probe >= 16) {
            var oddNul = 0; var evenNul = 0
            for (i in 0 until probe) if (u(i) == 0) { if (i % 2 == 1) oddNul++ else evenNul++ }
            if (oddNul >= 8 && evenNul * 4 <= oddNul) return Doc(String(b, Charsets.UTF_16LE), false)
            if (evenNul >= 8 && oddNul * 4 <= evenNul) return Doc(String(b, Charsets.UTF_16BE), false)
        }
        return Doc(String(b, Charsets.ISO_8859_1), true)   // байты 1:1: декодируем ПО СВОЙСТВАМ ниже
    }

    private fun isQp(line: String) = headOf(line).uppercase().contains("QUOTED-PRINTABLE")

    /** Физические строки → логические: QP-мягкие переносы, RFC-разворачивание, продолжения base64.
     *  Порядок важен: продолжение QP берётся БУКВАЛЬНО (оно может начинаться с пробела — это данные, а не фолдинг). */
    private fun logicalLines(text: String): List<String> {
        val physical = text.split(Regex("\r\n|\n|\r"))
        val merged = ArrayList<String>(physical.size)
        var qpOpen = false            // последняя логическая строка — QP и оканчивается «=» → следующая физическая её продолжает
        for (p in physical) {
            if (qpOpen) {
                val joined = merged.removeAt(merged.lastIndex).dropLast(1) + p
                merged += joined
                qpOpen = joined.endsWith("=")
            } else if (p.isNotEmpty() && (p[0] == ' ' || p[0] == '\t') && merged.isNotEmpty()) {
                val joined = merged.removeAt(merged.lastIndex) + p.substring(1)
                merged += joined
                qpOpen = isQp(joined) && joined.endsWith("=")
            } else {
                merged += p
                qpOpen = isQp(p) && p.endsWith("=")
            }
        }
        val out = ArrayList<String>(merged.size)
        var i = 0
        while (i < merged.size) {
            var line = merged[i]; i++
            val head = headOf(line).uppercase()
            if (head.contains("BASE64") || head.contains("ENCODING=B")) {
                // vCard 2.1: строки base64 могут идти БЕЗ отступа до пустой строки; в них нет ':'
                while (i < merged.size && merged[i].isNotEmpty() && !merged[i].contains(':')) { line += merged[i]; i++ }
            }
            out += line
        }
        return out
    }

    /** Часть строки до первого ':' вне кавычек (имя + параметры). */
    private fun headOf(line: String): String {
        val c = colonIndex(line)
        return if (c < 0) line else line.substring(0, c)
    }

    private fun colonIndex(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') quoted = !quoted
            else if (ch == ':' && !quoted) return i
        }
        return -1
    }

    private class Prop(val name: String, val types: String, val hasParam: (String) -> Boolean, val value: String)

    private val TEXT_PROPS = setOf("N", "FN", "ORG", "TITLE", "TEL", "EMAIL", "ADR", "NOTE", "BDAY",
        "X-PHONETIC-FIRST-NAME", "X-PHONETIC-MIDDLE-NAME", "X-PHONETIC-LAST-NAME")

    private fun parseProp(line: String, rawBytes: Boolean): Prop? {
        val colon = colonIndex(line)
        if (colon <= 0) return null
        val headParts = line.substring(0, colon).split(';')
        val name = headParts[0].trim().uppercase().substringAfterLast('.')     // группа: item1.TEL
        if (name.isBlank()) return null
        val kv = HashMap<String, String>()
        val bare = ArrayList<String>()
        val types = StringBuilder()
        for (p in headParts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) {
                val k = p.substring(0, eq).trim().uppercase()
                val v = p.substring(eq + 1).trim().trim('"').uppercase()
                kv[k] = if (k == "TYPE" && kv.containsKey(k)) kv[k] + "," + v else v
                if (k == "TYPE") types.append(v).append(',')
            } else if (p.isNotBlank()) {
                val t = p.trim().uppercase(); bare += t; types.append(t).append(',')
            }
        }
        val encoding = kv["ENCODING"] ?: bare.firstOrNull { it == "QUOTED-PRINTABLE" || it == "BASE64" || it == "B" || it == "Q" }
        val charset = kv["CHARSET"] ?: bare.firstOrNull { it.startsWith("CHARSET=") }?.substringAfter('=')
        val raw = line.substring(colon + 1)
        val value = when (encoding) {
            "QUOTED-PRINTABLE", "Q" -> decodeBytes(qpDecode(raw), charset)
            "BASE64", "B" -> if (name in TEXT_PROPS) decodeBytes(base64Decode(raw), charset) else raw
            else -> if (rawBytes) decodeBytes(raw.toByteArray(Charsets.ISO_8859_1), charset) else raw
        }
        return Prop(name, types.toString(), { key -> kv.containsKey(key) }, value)
    }

    /** Объявленный CHARSET — доверяем (замена невалидного); без объявления — строгий UTF-8, иначе Latin-1. */
    private fun decodeBytes(b: ByteArray, declared: String?): String {
        if (!declared.isNullOrBlank()) {
            try { return String(b, Charset.forName(declared.trim())) } catch (_: Exception) { /* неизвестное имя — как без объявления */ }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {
            String(b, Charsets.ISO_8859_1)
        }
    }

    private fun qpDecode(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hi = if (c == '=' && i + 2 < s.length) s[i + 1].digitToIntOrNull(16) else null
            val lo = if (hi != null) s[i + 2].digitToIntOrNull(16) else null
            when {
                hi != null && lo != null -> { out.write(hi * 16 + lo); i += 3 }
                c == '=' -> i++                       // хвостовой «=» / остаток мягкого переноса
                c.code < 256 -> { out.write(c.code); i++ }
                else -> { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
            }
        }
        return out.toByteArray()
    }

    /** Свой base64 (java.util.Base64 — только API 26+, а minSdk ниже). Игнорирует пробелы/переносы/мусор. */
    private fun base64Decode(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length * 3 / 4)
        var buffer = 0; var bits = 0
        for (ch in s) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'; in 'a'..'z' -> ch - 'a' + 26; in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62; '/', '_' -> 63
                else -> continue
            }
            buffer = (buffer shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((buffer shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }

    // ── интерпретация полей ──────────────────────────────────

    /** Обратная операция к ExportManager.vEsc. Порядок важен: одиночный «\\» — последним. */
    private fun vUnescape(s: String): String = s
        .replace(Regex("\\\\[nN]"), "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    /** Делит по [delim], НЕ режа по экранированному «\;». */
    private fun vSplit(s: String, delim: Char): List<String> {
        val out = mutableListOf(StringBuilder())
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { out.last().append(c).append(s[i + 1]); i += 2 }
            else if (c == delim) { out.add(StringBuilder()); i++ }
            else { out.last().append(c); i++ }
        }
        return out.map { it.toString() }
    }

    private class Card {
        val id: String = UUID.randomUUID().toString()
        var firstName = ""; var lastName = ""; var middleName = ""; var namePrefix = ""; var nameSuffix = ""
        var phoneticFirstName = ""; var phoneticMiddleName = ""; var phoneticLastName = ""
        val phones = mutableListOf<ContactPhone>()
        val emails = mutableListOf<ContactEmail>()
        val addresses = mutableListOf<Address>()
        var company = ""; var title = ""; var birthday = ""; var notes = ""
    }

    private fun parseDocument(doc: Doc): List<ImportContactCandidate> {
        val results = mutableListOf<ImportContactCandidate>()
        var card: Card? = null
        for (line in logicalLines(doc.text)) {
            val prop = parseProp(line, doc.rawBytes) ?: continue
            val c = card
            if (prop.name == "BEGIN") { if (prop.value.trim().equals("VCARD", ignoreCase = true)) card = Card(); continue }
            if (prop.name == "END") {
                if (c != null && prop.value.trim().equals("VCARD", ignoreCase = true)) toCandidate(c)?.let { results += it }
                card = null; continue
            }
            if (c == null) continue
            applyProp(c, prop)
        }
        return results
    }

    private fun applyProp(c: Card, p: Prop) {
        when (p.name) {
            // N:Фамилия;Имя;Отчество;Префикс;Суффикс (RFC 2426, 5 частей)
            "N" -> {
                val parts = vSplit(p.value, ';').map { vUnescape(it) }
                c.lastName = parts.getOrElse(0) { "" }.trim()
                c.firstName = parts.getOrElse(1) { "" }.trim()
                c.middleName = parts.getOrElse(2) { "" }.trim()
                c.namePrefix = parts.getOrElse(3) { "" }.trim()
                c.nameSuffix = parts.getOrElse(4) { "" }.trim()
            }
            "X-PHONETIC-FIRST-NAME" -> c.phoneticFirstName = vUnescape(p.value).trim()
            "X-PHONETIC-MIDDLE-NAME" -> c.phoneticMiddleName = vUnescape(p.value).trim()
            "X-PHONETIC-LAST-NAME" -> c.phoneticLastName = vUnescape(p.value).trim()
            // FN — запасной источник имени, если N не было (3+ слова → имя + середина + фамилия)
            "FN" -> if (c.firstName.isBlank() && c.lastName.isBlank()) {
                val full = vUnescape(p.value).trim().split(Regex("\\s+"))
                c.firstName = full.firstOrNull() ?: ""
                c.lastName = if (full.size >= 2) full.last() else ""
                if (full.size >= 3) c.middleName = full.subList(1, full.size - 1).joinToString(" ")
            }
            "TEL" -> {
                val number = p.value.trim().removePrefix("tel:").removePrefix("TEL:").trim()
                // Тот же критерий дубля, что у остальных путей: 10 последних цифр (+7912…/8912… — один номер).
                if (number.isNotBlank() && c.phones.none { com.aistudio.socialsphere.crmlxb.data.AppStateStore.phoneDigits(it.number) == com.aistudio.socialsphere.crmlxb.data.AppStateStore.phoneDigits(number) }) {
                    val type = when {
                        p.types.contains("WORK") -> PhoneType.WORK
                        p.types.contains("HOME") -> PhoneType.HOME
                        else -> PhoneType.MOBILE
                    }
                    c.phones += ContactPhone(UUID.randomUUID().toString(), c.id, number, type, c.phones.isEmpty())
                }
            }
            "EMAIL" -> {
                val addr = p.value.trim().removePrefix("mailto:").trim()
                if (addr.isNotBlank() && c.emails.none { it.email.equals(addr, ignoreCase = true) }) {
                    val type = if (p.types.contains("WORK")) EmailType.WORK else EmailType.PERSONAL
                    c.emails += ContactEmail(UUID.randomUUID().toString(), c.id, addr, type, c.emails.isEmpty())
                }
            }
            // Урок 45: ORG = «Компания;Отдел;…» — только первое поле, с раскрытием «\,» и «\;»
            "ORG" -> c.company = vUnescape(vSplit(p.value, ';').first()).trim()
            "TITLE" -> c.title = vUnescape(p.value).trim()
            // BDAY: 19900312 / 1990-03-12 / --0312; X-APPLE-OMIT-YEAR: год в дате фиктивный (1604)
            "BDAY" -> {
                val raw = p.value.trim()
                val pre = if (raw.length == 8 && !raw.contains("-") && !raw.startsWith("--"))
                    "${raw.take(4)}-${raw.substring(4, 6)}-${raw.takeLast(2)}" else raw
                val normalized = normalizeBirthday(pre)
                c.birthday = when {
                    normalized == null -> ""
                    p.hasParam("X-APPLE-OMIT-YEAR") && !normalized.startsWith("--") && normalized.length >= 10 ->
                        "--" + normalized.substring(5, 10)
                    else -> normalized
                }
            }
            "NOTE" -> c.notes = vUnescape(p.value)
            // ADR: pobox;ext;street;locality;region;postal;country (район — в region, как пишет ExportManager)
            "ADR" -> {
                val a = vSplit(p.value, ';').map { vUnescape(it).trim() }
                fun part(i: Int) = a.getOrElse(i) { "" }
                val line = listOf(part(0), part(1), part(2)).filter { it.isNotBlank() }.joinToString(", ")
                if (line.isNotBlank() || part(3).isNotBlank() || part(6).isNotBlank()) {
                    val type = when {
                        p.types.contains("WORK") || p.types.contains("OFFICE") -> AddressType.WORK
                        p.types.contains("HOME") -> AddressType.HOME
                        else -> AddressType.OTHER
                    }
                    c.addresses += Address(
                        id = UUID.randomUUID().toString(), ownerType = AddressOwnerType.CONTACT, ownerId = c.id,
                        addressType = type, addressLine = line, city = part(3), country = part(6),
                        postalCode = part(5).ifBlank { null }, district = part(4).ifBlank { null },
                    )
                }
            }
        }
    }

    private fun toCandidate(c: Card): ImportContactCandidate? {
        // Группы/метки телефонной книги приходят как карточки с именем, но без телефона и email — отсекаем.
        if (c.phones.isEmpty() && c.emails.isEmpty()) return null
        return ImportContactCandidate(
            id = c.id, firstName = c.firstName, lastName = c.lastName, middleName = c.middleName,
            namePrefix = c.namePrefix, nameSuffix = c.nameSuffix,
            phoneticFirstName = c.phoneticFirstName, phoneticMiddleName = c.phoneticMiddleName, phoneticLastName = c.phoneticLastName,
            phones = c.phones, emails = c.emails, addresses = c.addresses,
            companyName = c.company.ifBlank { null }, jobTitle = c.title.ifBlank { null },
            birthday = c.birthday.ifBlank { null }, notes = c.notes.ifBlank { null },
            source = "vCard",
        )
    }
}
