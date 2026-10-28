package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.Contact

/**
 * Чей это день рождения/важная дата, если в самом событии связи с контактом нет (создано в Календаре без выбора
 * человека, старый бэкап, контакт пересоздан). Владелец 2026-10-10: «если дата есть в календаре — она должна быть
 * и в контакте».
 *
 * Принцип прежний (§52): только ОДНОЗНАЧНОЕ совпадение — привязать не того человека хуже, чем оставить как есть.
 *  1) имя, выделенное из заголовка «День рождения: Иван Петров» ([extractedName]), равно полному имени ровно одного контакта;
 *  2) иначе — заголовок содержит «Имя Фамилия» (в любом порядке, целыми словами, без учёта регистра) ровно одного
 *     контакта. Контакты с пустой фамилией здесь не участвуют: одно имя («Маша») слишком неоднозначно.
 */
internal object DateLinkMatch {

    fun uniqueContactFor(title: String, extractedName: String?, contacts: List<Contact>): Contact? {
        if (extractedName != null) {
            val exact = contacts.filter { fullName(it).equals(extractedName, ignoreCase = true) }
            if (exact.isNotEmpty()) return exact.singleOrNull()
        }
        val t = norm(title)
        if (t.isEmpty()) return null
        return contacts.filter { c ->
            val f = norm(c.firstName)
            val l = norm(c.lastName)
            f.isNotEmpty() && l.isNotEmpty() && (hasWords(t, "$f $l") || hasWords(t, "$l $f"))
        }.singleOrNull()
    }

    private fun fullName(c: Contact) = "${c.firstName} ${c.lastName}".trim()

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun hasWords(haystack: String, words: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(words) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .containsMatchIn(haystack)
}
