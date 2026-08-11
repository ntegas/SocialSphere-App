package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.EmailType
import com.aistudio.socialsphere.crmlxb.model.PhoneType

/** Телефон с распознанным (эвристически) типом — см. [BusinessCardParser]. */
data class ParsedPhone(val number: String, val type: PhoneType)

/** Email с распознанным (эвристически) типом — см. [BusinessCardParser]. */
data class ParsedEmail(val email: String, val type: EmailType)

/** Результат разбора текста визитки/произвольного текста по полям контакта.
 *  Ничего из найденного не отбрасывается: всё, что не попало в структурные
 *  поля, оседает в [unmatched] — вызывающий код обязан довести это до
 *  пользователя (заметкой), а не молча выбросить (фидбэк владельца: «должен
 *  не терять ни фразы»). */
data class ParsedCard(
    val firstName: String = "",
    val lastName: String = "",
    val phones: List<ParsedPhone> = emptyList(),
    val emails: List<ParsedEmail> = emptyList(),
    val website: String? = null,
    val company: String? = null,
    val position: String? = null,
    /** Строки-кандидаты на почтовый адрес — без попытки разложить на
     *  улицу/город/страну (это ненадёжно распознавать по OCR-тексту). */
    val addresses: List<String> = emptyList(),
    /** Непустые строки, которые не были уверенно отнесены ни к одному другому
     *  полю. Сеть безопасности парсера: ничего увиденное камерой не исчезает
     *  бесследно, даже если структурный разбор не справился. */
    val unmatched: List<String> = emptyList()
)

/**
 * Офлайн-разбор распознанного текста визитки по полям. Движок-независим:
 * получает уже готовый текст (от OCR, голоса, вставки) и раскладывает его
 * эвристиками. Email/телефон/сайт — надёжные регулярки; имя/компания/должность —
 * по структуре и маркерам. Кириллица, латиница и греческий поддерживаются.
 */
object BusinessCardParser {

    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("\\+?\\d[\\d()\\-.\\s]{5,}\\d")
    private val URL = Regex(
        "(https?://\\S+|www\\.[^\\s]+|\\b[A-Za-z0-9\\-]+\\.(?:com|ru|org|net|io|co|me|biz|info|dev|app|gr|de|uk)\\b)",
        RegexOption.IGNORE_CASE
    )
    // Unicode-границы (?<![\p{L}])…(?![\p{L}]) вместо \b: \b в Java-regex не
    // работает с кириллицей (\w = только латиница), и «ООО» не распознавалось.
    private val LEGAL = Regex(
        "(?<![\\p{L}])(ООО|ОАО|ЗАО|ПАО|АО|ИП|LLC|Inc|Ltd|GmbH|LLP|Corp)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )
    // РАСШИРЕНИЕ (2026-08-11, живая визитка врача — «хирург, ортопед» полностью
    // пропускался): список раньше был чисто офисным (директор/менеджер/CEO),
    // ни одного медицинского/врачебного термина ни на одном из 3 языков —
    // визитка врача, юриста, преподавателя и т.п. не находила position вообще,
    // хотя реальная должность на карточке была прямо написана.
    private val POSITION = Regex(
        "(директор|менеджер|инженер|разработчик|программист|маркетолог|дизайнер|бухгалтер|юрист|" +
        "консультант|руководитель|основатель|владелец|специалист|аналитик|администратор|координатор|" +
        "ассистент|секретарь|президент|заместитель|представитель|врач|хирург|терапевт|стоматолог|" +
        "педиатр|кардиолог|невролог|психиатр|психолог|фармацевт|ветеринар|профессор|преподаватель|" +
        "адвокат|нотариус|архитектор|дизайнерка|" +
        "CEO|CTO|CFO|COO|VP|founder|manager|" +
        "engineer|developer|designer|director|president|partner|coordinator|administrator|assistant|" +
        "representative|officer|head of|lead|doctor|surgeon|physician|dentist|pediatrician|" +
        "cardiologist|neurologist|psychiatrist|psychologist|pharmacist|veterinarian|professor|lecturer|" +
        "attorney|lawyer|notary|architect|consultant|" +
        "διευθυντής|διευθύντρια|πρόεδρος|υπεύθυνος|διαχειριστής|σύμβουλος|" +
        "ιατρός|χειρουργός|οδοντίατρος|παιδίατρος|καρδιολόγος|νευρολόγος|ψυχίατρος|ψυχολόγος|" +
        "φαρμακοποιός|κτηνίατρος|καθηγητής|καθηγήτρια|δικηγόρος|συμβολαιογράφος|αρχιτέκτονας)",
        RegexOption.IGNORE_CASE
    )
    private val ADDRESS_MARKER = Regex(
        "(?<![\\p{L}])(ул\\.?|улица|проспект|пр-?кт|переулок|наб\\.?|шоссе|г\\.|город|дом|кв\\.?|офис|" +
        "индекс|street|st\\.|ave\\.?|avenue|road|rd\\.|suite|floor|bldg|building|blvd|οδός|λεωφ)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    // Дипломы/титулы после имени («Στέργιος Λάλλος MD, MSc») — раньше делали
    // строку 5-словной, она вылетала из фильтра «2-3 слова», и настоящее имя
    // на живой визитке (диагностика 2026-08-11) пропускалось мимо кандидатов
    // на nameLine целиком.
    private val CREDENTIAL_SUFFIX = Regex(
        "(?<![\\p{L}])(MD|PhD|MSc|BSc|MBA|LLM|DDS|DVM|CPA|CFA|Dr\\.?|Prof\\.?|Mr\\.?|Mrs\\.?|Ms\\.?)(?![\\p{L}])\\.?,?",
        RegexOption.IGNORE_CASE
    )
    private fun stripCredentials(line: String): String =
        line.replace(CREDENTIAL_SUFFIX, "").trim().trimEnd(',', '.').trim().replace(Regex("\\s+"), " ")

    private val FAX_MARK = Regex("(?<![\\p{L}])(fax|факс)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val MOBILE_MARK = Regex("(?<![\\p{L}])(mob|cell|моб|сот|κιν)", RegexOption.IGNORE_CASE)
    private val HOME_MARK = Regex("(?<![\\p{L}])(home|дом|οικ)", RegexOption.IGNORE_CASE)
    private val WORK_MARK = Regex(
        "(?<![\\p{L}])(work|office|tel|тел|раб|офис|γραφ)",
        RegexOption.IGNORE_CASE
    )

    private val PERSONAL_EMAIL_DOMAINS = setOf(
        "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "icloud.com", "me.com",
        "mail.ru", "yandex.ru", "yandex.com", "bk.ru", "inbox.ru", "list.ru", "rambler.ru",
        "protonmail.com", "aol.com", "live.com", "gmx.com"
    )

    fun parse(rawTextIn: String): ParsedCard {
        // ФИКС (2026-08-11, живая проверка: одна и та же визитка при повторном
        // сканировании то находила должность, то нет). Причина — Unicode:
        // Tesseract не всегда отдаёт греческие буквы с ударением (тонос) одной
        // и той же формой — «ό» может прийти уже готовым символом (NFC) или
        // как «о» + отдельный символ ударения (NFD). Визуально неотличимо, но
        // литеральное сравнение/regex («χειρουργός» в POSITION) считает их
        // РАЗНЫМИ строками без предварительной нормализации — отсюда «через раз
        // находит, через раз нет» на одной и той же карточке. Нормализуем к NFC
        // один раз здесь, до всех регулярок.
        val rawText = java.text.Normalizer.normalize(rawTextIn, java.text.Normalizer.Form.NFC)
        // ФИКС (2026-07-11, реальный тест владельца): OCR иногда читает значок
        // конверта перед адресом как пробел сразу после «@» — «name@ gmail.com».
        // EMAIL-регулярка не допускает пробелов внутри адреса, из-за чего email
        // целиком пропадал. Схлопываем «@ » → «@» один раз для всего текста —
        // остальные регулярки (PHONE/URL/LEGAL/POSITION) от пробелов после @ не
        // зависят, так что это безопасно для них.
        val text = rawText.replace(Regex("@\\s+"), "@")
        // Схлопываем внутренние пробелы (не только края) — OCR со сканов
        // визиток часто даёт двойные/тройные пробелы («Acme  Corp»), из-за
        // чего company/position, разобранные из этой строки, не совпадали
        // бы при .trim()+ignoreCase сравнении с той же компанией, введённой
        // вручную с одинарным пробелом — и плодили дубль Company. Схлопываем
        // один раз здесь, для всех производных полей сразу, а не только для
        // company: split(Regex("\\s+")) в имени и так устойчив к этому, так
        // что для остальных полей это безопасно.
        val lines = text.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }

        val emails = EMAIL.findAll(text).map { it.value }.distinct()
            .map { ParsedEmail(it, emailType(it)) }
            .toList()

        val phones = PHONE.findAll(text)
            .filter { m -> m.value.count { it.isDigit() } in 7..15 }
            .map { m -> m.value.replace(Regex("[\\s()\\-.]"), "") to phoneTypeNear(text, m.range) }
            .distinctBy { it.first }
            .map { (number, type) -> ParsedPhone(number, type) }
            .toList()

        val website = URL.findAll(text).map { it.value.trimEnd('.', ',', ';') }
            .firstOrNull { url -> emails.none { it.email.contains(url, ignoreCase = true) } }

        val legalCompany = lines.firstOrNull { LEGAL.containsMatchIn(it) }
        val keywordPosition = lines.firstOrNull { POSITION.containsMatchIn(it) }

        // Имя: из строк-кандидатов (2-4 «слова» ПОСЛЕ вычитания титулов/степеней,
        // без цифр/@, не компания/должность/сайт) берём не ПЕРВУЮ попавшуюся
        // (реальный OCR-шум типа обрывка логотипа сверху кадра легко проходил
        // этот же фильтр и перехватывал место настоящего имени — диагностика
        // 2026-08-11), а с наибольшей долей слов с заглавной буквы — имена
        // собственные почти всегда Title Case, обрывки OCR-мусора обычно нет.
        val nameCandidates = lines.mapNotNull { rawLine ->
            val stripped = stripCredentials(rawLine)
            val words = stripped.split(Regex("\\s+")).filter { it.isNotBlank() }
            val valid = stripped.isNotBlank() &&
                !EMAIL.containsMatchIn(stripped) && !PHONE.containsMatchIn(stripped) &&
                !URL.containsMatchIn(stripped) && !LEGAL.containsMatchIn(stripped) &&
                !POSITION.containsMatchIn(stripped) &&
                stripped.none { it.isDigit() } &&
                words.size in 2..4 &&
                (stripped.firstOrNull()?.isLetter() == true)
            if (!valid) return@mapNotNull null
            val capRatio = words.count { it.firstOrNull()?.isUpperCase() == true }.toDouble() / words.size
            Triple(rawLine, words, capRatio)
        }
        val bestNameCandidate = nameCandidates.maxByOrNull { it.third }
        // Сырая строка (не «вычищенная») — нужна дальше для сравнений line != nameLine
        // при поиске компании/должности/unmatched, чтобы не задублировать одну и ту же
        // строку одновременно как имя и как «нераспознанное».
        val nameLine = bestNameCandidate?.first
        val nameWords = bestNameCandidate?.second ?: emptyList()

        // РАСШИРЕНИЕ: компания без юр.маркера (ООО/LLC/Inc) — раньше молча
        // пропускалась. Кандидат: первая ещё не занятая строка (не имя, не
        // должность, не контакт, не адрес), без цифр, из ≤5 слов — низкая
        // уверенность, но лучше, чем всегда null.
        // ФИКС (2026-08-11, живая визитка со сложным графическим логотипом):
        // логотип-картинка нечитаем для OCR и даёт короткий строчный мусор
        // («ssi.») — тот же фильтр без доп. проверки уверенно подставлял его
        // как компанию. Тот же сигнал «имя собственное», что и у nameLine —
        // компания почти всегда начинается с заглавной буквы, OCR-мусор с
        // логотипа обычно нет. Не убирает проблему нечитаемого логотипа (это
        // физически невозможно), но не подсовывает вместо честной пустой
        // строки уверенный неверный ответ.
        val fallbackCompany = legalCompany ?: lines.firstOrNull { line ->
            line != nameLine && line != keywordPosition &&
            !EMAIL.containsMatchIn(line) && !PHONE.containsMatchIn(line) &&
            !URL.containsMatchIn(line) && !POSITION.containsMatchIn(line) &&
            !ADDRESS_MARKER.containsMatchIn(line) &&
            line.none { it.isDigit() } &&
            line.split(Regex("\\s+")).size in 1..5 &&
            (line.firstOrNull()?.isUpperCase() == true)
        }
        val company = legalCompany ?: fallbackCompany

        // РАСШИРЕНИЕ: должность без слова из фиксированного списка — строка
        // сразу после имени (частая раскладка «Имя / Должность / Компания»),
        // если она не занята компанией/контактами/адресом.
        val fallbackPosition = if (keywordPosition == null && nameLine != null) {
            val idx = lines.indexOf(nameLine)
            lines.getOrNull(idx + 1)?.takeIf { line ->
                line != company &&
                !EMAIL.containsMatchIn(line) && !PHONE.containsMatchIn(line) &&
                !URL.containsMatchIn(line) && !LEGAL.containsMatchIn(line) &&
                !ADDRESS_MARKER.containsMatchIn(line) &&
                line.none { it.isDigit() } &&
                line.split(Regex("\\s+")).size in 1..4
            }
        } else null
        val position = keywordPosition ?: fallbackPosition

        val addresses = lines.filter { ADDRESS_MARKER.containsMatchIn(it) }.distinct()

        val firstName = nameWords.getOrNull(0).orEmpty()
        val lastName = nameWords.drop(1).joinToString(" ")

        // Сеть безопасности: всё, что не ушло ни в одно структурное поле, —
        // в unmatched, чтобы вызывающий код мог сохранить это заметкой.
        val unmatched = lines.filter { line ->
            line != nameLine && line != company && line != position &&
            !EMAIL.containsMatchIn(line) && !PHONE.containsMatchIn(line) &&
            !URL.containsMatchIn(line) && !ADDRESS_MARKER.containsMatchIn(line)
        }

        return ParsedCard(
            firstName = firstName,
            lastName  = lastName,
            phones    = phones,
            emails    = emails,
            website   = website,
            company   = company,
            position  = position,
            addresses = addresses,
            unmatched = unmatched
        )
    }

    private fun emailType(email: String): EmailType {
        val domain = email.substringAfter('@', "").lowercase()
        return if (domain in PERSONAL_EMAIL_DOMAINS) EmailType.PERSONAL else EmailType.WORK
    }

    /** Тип телефона по маркеру-подсказке рядом с числом (та же строка, до и
     *  после совпадения) — «Моб.: +7…», «+1 415… (fax)» и т.п. Без маркера —
     *  OTHER (честно неизвестно, не выдумываем MOBILE по умолчанию). */
    private fun phoneTypeNear(text: String, range: IntRange): PhoneType {
        val lineStart = text.lastIndexOf('\n', (range.first - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val lineEndExclusive = text.indexOf('\n', range.last + 1)
            .let { if (it < 0) text.length else it }
        val before = text.substring(lineStart, range.first)
        val after = text.substring((range.last + 1).coerceAtMost(text.length), lineEndExclusive)
        val context = "$before $after"
        return when {
            FAX_MARK.containsMatchIn(context) -> PhoneType.OTHER
            MOBILE_MARK.containsMatchIn(context) -> PhoneType.MOBILE
            HOME_MARK.containsMatchIn(context) -> PhoneType.HOME
            WORK_MARK.containsMatchIn(context) -> PhoneType.WORK
            else -> PhoneType.OTHER
        }
    }
}
