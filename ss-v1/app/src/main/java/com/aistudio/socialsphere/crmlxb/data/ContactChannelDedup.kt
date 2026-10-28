package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.Contact
import com.aistudio.socialsphere.crmlxb.model.ContactEmail
import com.aistudio.socialsphere.crmlxb.model.ContactPhone
import com.aistudio.socialsphere.crmlxb.model.EmailType
import com.aistudio.socialsphere.crmlxb.model.PhoneType

/**
 * ПРАВИЛО: у одного контакта один и тот же номер телефона / email не должен ВОЗНИКАТЬ дважды — но
 * приложение само НИЧЕГО не удаляет из уже сохранённых данных (решение владельца 2026-10-10: «сам
 * тупо удалять не надо, пусть дубли не создаются, а удаляю я»). Поэтому здесь два разных слоя:
 *
 *  • ПРОФИЛАКТИКА при вводе — [dedupePhones]/[dedupeEmails] склеивают повторы только во входящем
 *    наборе (строки одного контакта телефонной книги, один vCard, кандидат на импорт), ещё ДО записи;
 *    [phoneAlreadyListed]/[emailAlreadyListed] — проверка при ручном добавлении номера/почты;
 *  • ВИДИМОСТЬ уже сохранённого — [repeatedPhoneIds]/[repeatedEmailIds]/[hasRepeats]: только чтение,
 *    чтобы форма контакта и «Поиск дублей» показали повторы, а решение и удаление остались за человеком.
 *
 * Причина появления (жалоба владельца 2026-10-08: «в каких-то контактах по два раза записаны телефоны,
 * которые я не записал»): контакт телефонной книги Android агрегирует несколько raw-контактов
 * (Google + WhatsApp + Telegram + SIM…), и КАЖДЫЙ несёт тот же номер отдельной строкой Data. Импорт/
 * выбор контакта в форме складывал все строки подряд. Отдельно: Restore сопоставлял дочерние записи по
 * id, а id пересоздаются при импорте/слиянии.
 *
 * Дубль = совпадают нормализованные цифры ([AppStateStore.phoneDigits], тот же критерий, что у всех
 * остальных проверок дублей) либо email целиком без учёта РЕГИСТРА БУКВ (`Ivan@Mail.ru` = `ivan@mail.ru`;
 * `ivan@mail.ru` и `ivan@gmail.com` — разные адреса). При склейке входящего набора остаётся первая запись,
 * к ней переносятся «основной», осмысленный тип (не OTHER) и комментарий; две записи с РАЗНЫМИ непустыми
 * комментариями не склеиваются — комментарий это данные пользователя.
 */
internal object ContactChannelDedup {

    private fun phoneKey(p: ContactPhone): String? =
        AppStateStore.phoneDigits(p.number).takeIf { it.isNotEmpty() }

    private fun emailKey(e: ContactEmail): String? =
        e.email.trim().lowercase().takeIf { it.isNotEmpty() }

    /** Ключ «то же значение» для сопоставления записей с разными id (Restore). Пустое значение
     *  никогда не совпадает с другим: ключ содержит id. */
    fun phoneValueKey(p: ContactPhone): String = phoneKey(p)?.let { "p:$it" } ?: "id:${p.id}"
    fun emailValueKey(e: ContactEmail): String = emailKey(e)?.let { "e:$it" } ?: "id:${e.id}"

    private fun commentsCompatible(a: String?, b: String?): Boolean {
        val x = a?.trim().orEmpty()
        val y = b?.trim().orEmpty()
        return x.isEmpty() || y.isEmpty() || x == y
    }

    // ── ПРОФИЛАКТИКА: входящий набор склеивается до записи ──

    fun dedupePhones(phones: List<ContactPhone>): List<ContactPhone> {
        if (phones.size < 2) return phones
        val out = ArrayList<ContactPhone>(phones.size)
        for (p in phones) {
            val key = phoneKey(p)
            val at = if (key == null) -1 else out.indexOfFirst { phoneKey(it) == key && commentsCompatible(it.comment, p.comment) }
            if (at < 0) { out += p; continue }
            val kept = out[at]
            out[at] = kept.copy(
                isPrimary = kept.isPrimary || p.isPrimary,
                type = if (kept.type == PhoneType.OTHER) p.type else kept.type,
                comment = kept.comment?.takeIf { it.isNotBlank() } ?: p.comment,
            )
        }
        return if (out.size == phones.size) phones else out
    }

    fun dedupeEmails(emails: List<ContactEmail>): List<ContactEmail> {
        if (emails.size < 2) return emails
        val out = ArrayList<ContactEmail>(emails.size)
        for (e in emails) {
            val key = emailKey(e)
            val at = if (key == null) -1 else out.indexOfFirst { emailKey(it) == key && commentsCompatible(it.comment, e.comment) }
            if (at < 0) { out += e; continue }
            val kept = out[at]
            out[at] = kept.copy(
                isPrimary = kept.isPrimary || e.isPrimary,
                type = if (kept.type == EmailType.OTHER) e.type else kept.type,
                comment = kept.comment?.takeIf { it.isNotBlank() } ?: e.comment,
            )
        }
        return if (out.size == emails.size) emails else out
    }

    // ── ПРОФИЛАКТИКА при ручном добавлении: такой номер/почта уже есть в списке ──

    fun phoneAlreadyListed(existing: List<ContactPhone>, number: String): Boolean {
        val key = AppStateStore.phoneDigits(number).takeIf { it.isNotEmpty() } ?: return false
        return existing.any { phoneKey(it) == key }
    }

    fun emailAlreadyListed(existing: List<ContactEmail>, email: String): Boolean {
        val key = email.trim().lowercase().takeIf { it.isNotEmpty() } ?: return false
        return existing.any { emailKey(it) == key }
    }

    // ── ВИДИМОСТЬ уже сохранённых повторов (только чтение) ──

    /** Id записей, повторяющих номер, который уже встречался РАНЬШЕ в списке. */
    fun repeatedPhoneIds(phones: List<ContactPhone>): Set<String> {
        val seen = HashSet<String>()
        val out = LinkedHashSet<String>()
        for (p in phones) {
            val key = phoneKey(p) ?: continue
            if (!seen.add(key)) out += p.id
        }
        return out
    }

    fun repeatedEmailIds(emails: List<ContactEmail>): Set<String> {
        val seen = HashSet<String>()
        val out = LinkedHashSet<String>()
        for (e in emails) {
            val key = emailKey(e) ?: continue
            if (!seen.add(key)) out += e.id
        }
        return out
    }

    fun hasRepeats(c: Contact): Boolean =
        repeatedPhoneIds(c.phones).isNotEmpty() || repeatedEmailIds(c.emails).isNotEmpty()
}
