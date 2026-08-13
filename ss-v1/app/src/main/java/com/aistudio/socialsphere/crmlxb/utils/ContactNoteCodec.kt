package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.Contact
import com.aistudio.socialsphere.crmlxb.model.GiftStatus
import com.aistudio.socialsphere.crmlxb.model.NoteType
import com.aistudio.socialsphere.crmlxb.model.PersonalDetailCategory

/**
 * Кодирует/декодирует структурные поля контакта (заметки по типам, личные
 * детали по категориям, идеи подарков, следующий шаг и т.д.) в/из ОДНОГО
 * текстового поля NOTE телефонного контакта — чтобы «Экспорт в контакты
 * телефона» → «Импорт из контактов телефона» переносил данные обратно в
 * правильные места приложения, а не одной общей заметкой-свалкой (фидбэк
 * владельца 2026-08-11: «чтобы приложение определяло, что и куда добавить»).
 *
 * Формат — одна помеченная строка на запись: «[Метка] текст». Метки —
 * ФИКСИРОВАННЫЕ русские строки (тот же приём, что уже применяется в проекте
 * для labelKey()/ролей семьи — см. Translations.kt), НЕ зависят от текущего
 * языка интерфейса: экспорт на английском и импорт на русском (или другое
 * устройство) всё равно распознают друг друга. Любая строка без метки, либо
 * с меткой, которую эта версия не знает (например, заметка, которую владелец
 * написал прямо в Контактах телефона руками) — не теряется, попадает в общий
 * текст fallbackText, как раньше.
 */
object ContactNoteCodec {

    private const val NEXT_STEP_TAG = "Следующий шаг"
    private const val TALKING_POINTS_TAG = "О чём поговорить"
    private const val CAN_HELP_TAG = "Чем я могу помочь"
    private const val I_CAN_HELP_TAG = "Чем мне могут помочь"
    private const val MEET_CONTEXT_TAG = "Как познакомились"
    private const val TAGS_TAG = "Теги"
    private const val PERSONAL_DETAIL_PREFIX = "Личное:"
    private const val GIFT_PREFIX = "Подарок:"
    private const val GIFT_LINK_MARKER = "ссылка:"
    private const val GIFT_DATE_MARKER = "дата:"
    private const val VALUE_NOTE_SEPARATOR = " — "
    // ФИКС (2026-08-12): у заметки есть приватность (Note.isLocked, независимо
    // от «важности») — раньше encode() её никак не отражал, decode() всегда
    // создавал заметку незащищённой, так что «Экспорт в контакты телефона» →
    // «Импорт из контактов» тихо снимал защиту. Суффикс на теге (не в самом
    // тексте!), чтобы не путать с текстом заметки, начинающимся на этот же символ.
    private const val LOCKED_SUFFIX = " · Защищено"

    private val LINE_TAG = Regex("^\\[([^]]+)]\\s*(.*)$")

    data class DecodedNote(val type: NoteType, val text: String, val isLocked: Boolean = false)
    data class DecodedPersonalDetail(
        val category: PersonalDetailCategory,
        val value: String,
        val note: String? = null
    )
    data class DecodedGift(
        val title: String,
        val note: String? = null,
        val link: String? = null,
        val date: String? = null,
        val status: GiftStatus
    )

    data class DecodedExtras(
        val notes: List<DecodedNote> = emptyList(),
        val personalDetails: List<DecodedPersonalDetail> = emptyList(),
        val gifts: List<DecodedGift> = emptyList(),
        val nextStep: String? = null,
        val talkingPoints: String? = null,
        val canHelpWith: String? = null,
        val iCanHelpWith: String? = null,
        val meetContext: String? = null,
        val tags: List<String> = emptyList(),
        /** Нераспознанные строки — как раньше, попадают в одну общую заметку импорта. */
        val fallbackText: String? = null
    )

    /**
     * Собирает многострочный текст для vCard-поля NOTE. Возвращает null, если
     * у контакта нет ни одной заметки/детали/подарка/доп.поля — как раньше,
     * пустое поле NOTE не пишется.
     */
    fun encode(c: Contact): String? {
        val lines = mutableListOf<String>()

        c.notes.forEach { note ->
            val lockSuffix = if (note.isLocked) LOCKED_SUFFIX else ""
            lines.add("[${note.type.labelKey()}$lockSuffix] ${note.text}")
        }
        c.personalDetails.forEach { pd ->
            val body = if (pd.note.isNullOrBlank()) pd.value else pd.value + VALUE_NOTE_SEPARATOR + pd.note
            lines.add("[$PERSONAL_DETAIL_PREFIX${pd.category.labelKey()}] $body")
        }
        c.gifts.forEach { gift ->
            val sb = StringBuilder(gift.title)
            if (!gift.note.isNullOrBlank()) sb.append(VALUE_NOTE_SEPARATOR).append(gift.note)
            if (!gift.link.isNullOrBlank()) sb.append(" ($GIFT_LINK_MARKER ${gift.link})")
            if (!gift.date.isNullOrBlank()) sb.append(" ($GIFT_DATE_MARKER ${gift.date})")
            lines.add("[$GIFT_PREFIX${gift.status.labelKey()}] $sb")
        }
        if (!c.nextStep.isNullOrBlank())      lines.add("[$NEXT_STEP_TAG] ${c.nextStep}")
        if (!c.talkingPoints.isNullOrBlank()) lines.add("[$TALKING_POINTS_TAG] ${c.talkingPoints}")
        if (!c.canHelpWith.isNullOrBlank())   lines.add("[$CAN_HELP_TAG] ${c.canHelpWith}")
        if (!c.iCanHelpWith.isNullOrBlank())  lines.add("[$I_CAN_HELP_TAG] ${c.iCanHelpWith}")
        if (!c.meetContext.isNullOrBlank())   lines.add("[$MEET_CONTEXT_TAG] ${c.meetContext}")
        if (c.tags.isNotEmpty())              lines.add("[$TAGS_TAG] ${c.tags.joinToString(", ")}")

        return lines.joinToString("\n").ifBlank { null }
    }

    /** Разбирает текст поля NOTE (уже с настоящими переносами строк, БЕЗ vCard-эскейпинга) обратно в структуры. */
    fun decode(raw: String?): DecodedExtras {
        if (raw.isNullOrBlank()) return DecodedExtras()

        val notes = mutableListOf<DecodedNote>()
        val personalDetails = mutableListOf<DecodedPersonalDetail>()
        val gifts = mutableListOf<DecodedGift>()
        var nextStep: String? = null
        var talkingPoints: String? = null
        var canHelpWith: String? = null
        var iCanHelpWith: String? = null
        var meetContext: String? = null
        var tags: List<String> = emptyList()
        val fallback = mutableListOf<String>()

        raw.split("\n").forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val match = LINE_TAG.matchEntire(line)
            if (match == null) {
                fallback.add(line)
                return@forEach
            }
            val tag = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (body.isEmpty()) { fallback.add(line); return@forEach }

            val isLocked = tag.endsWith(LOCKED_SUFFIX)
            val bareTag = if (isLocked) tag.removeSuffix(LOCKED_SUFFIX).trim() else tag
            val noteType = NoteType.values().find { it.labelKey() == bareTag }
            when {
                noteType != null -> notes.add(DecodedNote(noteType, body, isLocked))
                tag == NEXT_STEP_TAG -> nextStep = body
                tag == TALKING_POINTS_TAG -> talkingPoints = body
                tag == CAN_HELP_TAG -> canHelpWith = body
                tag == I_CAN_HELP_TAG -> iCanHelpWith = body
                tag == MEET_CONTEXT_TAG -> meetContext = body
                tag == TAGS_TAG -> tags = body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                tag.startsWith(PERSONAL_DETAIL_PREFIX) -> {
                    val categoryTag = tag.removePrefix(PERSONAL_DETAIL_PREFIX).trim()
                    val category = PersonalDetailCategory.values().find { it.labelKey() == categoryTag }
                    if (category == null) {
                        fallback.add(line)
                    } else {
                        val parts = body.split(VALUE_NOTE_SEPARATOR, limit = 2)
                        personalDetails.add(DecodedPersonalDetail(category, parts[0].trim(), parts.getOrNull(1)?.trim()))
                    }
                }
                tag.startsWith(GIFT_PREFIX) -> {
                    val statusTag = tag.removePrefix(GIFT_PREFIX).trim()
                    val status = GiftStatus.values().find { it.labelKey() == statusTag }
                    if (status == null) {
                        fallback.add(line)
                    } else {
                        gifts.add(parseGiftBody(body, status))
                    }
                }
                else -> fallback.add(line)
            }
        }

        return DecodedExtras(
            notes = notes,
            personalDetails = personalDetails,
            gifts = gifts,
            nextStep = nextStep,
            talkingPoints = talkingPoints,
            canHelpWith = canHelpWith,
            iCanHelpWith = iCanHelpWith,
            meetContext = meetContext,
            tags = tags,
            fallbackText = fallback.joinToString("\n").ifBlank { null }
        )
    }

    private fun parseGiftBody(body: String, status: GiftStatus): DecodedGift {
        var rest = body
        var link: String? = null
        var date: String? = null

        val linkMatch = Regex("\\(\\s*$GIFT_LINK_MARKER\\s*(.*?)\\)").find(rest)
        if (linkMatch != null) {
            link = linkMatch.groupValues[1].trim().ifBlank { null }
            rest = rest.removeRange(linkMatch.range).trim()
        }
        val dateMatch = Regex("\\(\\s*$GIFT_DATE_MARKER\\s*(.*?)\\)").find(rest)
        if (dateMatch != null) {
            date = dateMatch.groupValues[1].trim().ifBlank { null }
            rest = rest.removeRange(dateMatch.range).trim()
        }

        val parts = rest.split(VALUE_NOTE_SEPARATOR, limit = 2)
        val title = parts[0].trim()
        val note = parts.getOrNull(1)?.trim()?.ifBlank { null }
        return DecodedGift(title = title.ifBlank { rest }, note = note, link = link, date = date, status = status)
    }
}
