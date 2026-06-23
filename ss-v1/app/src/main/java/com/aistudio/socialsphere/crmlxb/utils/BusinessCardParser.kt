package com.aistudio.socialsphere.crmlxb.utils

/** Результат разбора текста визитки/произвольного текста по полям контакта. */
data class ParsedCard(
    val firstName: String = "",
    val lastName: String = "",
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val website: String? = null,
    val company: String? = null,
    val position: String? = null
)

/**
 * Офлайн-разбор распознанного текста визитки по полям. Движок-независим:
 * получает уже готовый текст (от OCR, голоса, вставки) и раскладывает его
 * эвристиками. Email/телефон/сайт — надёжные регулярки; имя/компания/должность —
 * по структуре и маркерам. Кириллица и латиница поддерживаются.
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
    private val POSITION = Regex(
        "(директор|менеджер|инженер|разработчик|программист|маркетолог|дизайнер|бухгалтер|юрист|консультант|руководитель|основатель|владелец|специалист|аналитик|CEO|CTO|CFO|COO|founder|manager|engineer|developer|designer|director|head of|lead)",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): ParsedCard {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val emails = EMAIL.findAll(text).map { it.value }.distinct().toList()

        val phones = PHONE.findAll(text).map { it.value.trim() }
            .filter { m -> m.count { it.isDigit() } in 7..15 }
            .map { it.replace(Regex("[\\s()\\-.]"), "") }
            .distinct()
            .toList()

        val website = URL.findAll(text).map { it.value.trimEnd('.', ',', ';') }
            .firstOrNull { url -> emails.none { it.contains(url, ignoreCase = true) } }

        val company = lines.firstOrNull { LEGAL.containsMatchIn(it) }
        val position = lines.firstOrNull { POSITION.containsMatchIn(it) }

        // Имя: первая строка из 2–3 слов без цифр/@, не компания/должность/сайт.
        val nameLine = lines.firstOrNull { line ->
            !EMAIL.containsMatchIn(line) && !PHONE.containsMatchIn(line) &&
            !URL.containsMatchIn(line) && !LEGAL.containsMatchIn(line) &&
            !POSITION.containsMatchIn(line) &&
            line.none { it.isDigit() } &&
            line.split(Regex("\\s+")).size in 2..3 &&
            (line.firstOrNull()?.isLetter() == true)
        }
        val parts = nameLine?.split(Regex("\\s+")) ?: emptyList()
        val firstName = parts.getOrNull(0).orEmpty()
        val lastName = parts.drop(1).joinToString(" ")

        return ParsedCard(
            firstName = firstName,
            lastName  = lastName,
            phones    = phones,
            emails    = emails,
            website   = website,
            company   = company,
            position  = position
        )
    }
}
