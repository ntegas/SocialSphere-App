package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жалоба владельца 2026-10-08: «в каких-то контактах по два раза записаны телефоны, которые я не
 * записал». Инвариант: у одного контакта один номер/email не хранится дважды. Чистая логика
 * ([ContactChannelDedup]) и сопоставление в Restore ([RestoreMerger.mergeContact]) — без БД.
 */
class ContactChannelDedupTest {

    private fun phone(id: String, number: String, type: PhoneType = PhoneType.MOBILE, primary: Boolean = false, comment: String? = null) =
        ContactPhone(id, "c1", number, type, primary, comment)

    private fun email(id: String, address: String, type: EmailType = EmailType.PERSONAL, primary: Boolean = false, comment: String? = null) =
        ContactEmail(id, "c1", address, type, primary, comment)

    private fun contact(phones: List<ContactPhone> = emptyList(), emails: List<ContactEmail> = emptyList()) = Contact(
        id = "c1", firstName = "Иван", lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = phones, emails = emails, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    // ── телефоны ──

    @Test fun sameNumberInDifferentFormats_collapsesToOne() {
        val out = ContactChannelDedup.dedupePhones(listOf(
            phone("a", "+7 912 345-67-89", primary = true),
            phone("b", "89123456789"),
            phone("c", "+7(912)3456789"),
        ))
        assertEquals(1, out.size)
        assertEquals("первая запись остаётся (порядок и id)", "a", out[0].id)
        assertEquals("+7 912 345-67-89", out[0].number)
    }

    @Test fun differentNumbers_stayDistinct() {
        val input = listOf(phone("a", "+7 912 345-67-89"), phone("b", "+7 900 111-22-33"))
        assertSame("нет дублей — тот же экземпляр списка", input, ContactChannelDedup.dedupePhones(input))
    }

    @Test fun survivor_inheritsPrimaryMeaningfulTypeAndComment() {
        val out = ContactChannelDedup.dedupePhones(listOf(
            phone("a", "89123456789", type = PhoneType.OTHER, primary = false, comment = null),
            phone("b", "+7 912 345 67 89", type = PhoneType.WORK, primary = true, comment = "рабочий"),
        ))
        assertEquals(1, out.size)
        assertEquals("a", out[0].id)
        assertTrue("флаг «основной» не теряется", out[0].isPrimary)
        assertEquals("осмысленный тип вытесняет OTHER", PhoneType.WORK, out[0].type)
        assertEquals("комментарий не теряется", "рабочий", out[0].comment)
    }

    @Test fun sameNumberWithDifferentNonBlankComments_isNotCollapsed_noDataLost() {
        val input = listOf(
            phone("a", "+7 912 345-67-89", comment = "днём"),
            phone("b", "89123456789", comment = "ночью"),
        )
        assertEquals("комментарий — данные пользователя, обе записи сохраняются", 2, ContactChannelDedup.dedupePhones(input).size)
    }

    @Test fun phonesWithoutDigits_areNeverCollapsed() {
        val input = listOf(phone("a", "добавочный"), phone("b", "добавочный"))
        assertEquals(2, ContactChannelDedup.dedupePhones(input).size)
    }

    @Test fun dedupe_isIdempotent() {
        val once = ContactChannelDedup.dedupePhones(listOf(phone("a", "89123456789"), phone("b", "+79123456789"), phone("c", "89001112233")))
        assertEquals(2, once.size)
        assertSame(once, ContactChannelDedup.dedupePhones(once))
    }

    // ── email ──

    @Test fun sameEmailDifferentCase_collapses() {
        val out = ContactChannelDedup.dedupeEmails(listOf(email("a", "Ivan@Mail.ru"), email("b", " ivan@mail.ru ", EmailType.WORK, primary = true)))
        assertEquals(1, out.size)
        assertEquals("a", out[0].id)
        assertTrue(out[0].isPrimary)
        assertEquals(EmailType.PERSONAL, out[0].type)
    }

    @Test fun differentEmails_areNeverTreatedAsDuplicates() {
        // «Без учёта регистра» — только регистр букв: разные имя до @ или домен = разные адреса.
        val input = listOf(
            email("a", "ivan@mail.ru"), email("b", "ivan@gmail.com"),
            email("c", "ivan+work@gmail.com"), email("d", "ivan.petrov@mail.ru"),
        )
        assertSame(input, ContactChannelDedup.dedupeEmails(input))
        assertEquals(false, ContactChannelDedup.emailAlreadyListed(input, "ivan@yahoo.com"))
        assertEquals(false, ContactChannelDedup.emailAlreadyListed(input, "petrov@mail.ru"))
    }

    // ── профилактика при ручном добавлении ──

    @Test fun phoneAlreadyListed_matchesAnyFormatOfTheSameNumber() {
        val existing = listOf(phone("a", "+7 912 345-67-89"))
        assertTrue(ContactChannelDedup.phoneAlreadyListed(existing, "89123456789"))
        assertTrue(ContactChannelDedup.phoneAlreadyListed(existing, " +7(912)3456789 "))
        assertFalse(ContactChannelDedup.phoneAlreadyListed(existing, "+7 900 111-22-33"))
        assertFalse("пустой ввод не считается повтором", ContactChannelDedup.phoneAlreadyListed(existing, ""))
        assertFalse("текст без цифр не считается повтором", ContactChannelDedup.phoneAlreadyListed(existing, "добавочный"))
    }

    @Test fun emailAlreadyListed_ignoresOnlyLetterCase() {
        val existing = listOf(email("a", "Ivan@Mail.ru"))
        assertTrue(ContactChannelDedup.emailAlreadyListed(existing, " ivan@mail.ru "))
        assertFalse(ContactChannelDedup.emailAlreadyListed(existing, "ivan@mail.com"))
    }

    // ── видимость уже сохранённых повторов: ТОЛЬКО чтение, ничего не удаляется ──

    @Test fun repeatedIds_pointAtTheLaterCopies_andInputIsNotModified() {
        val phones = listOf(phone("a", "+7 912 345-67-89"), phone("b", "+7 900 111-22-33"), phone("c", "89123456789"))
        val copy = phones.toList()
        assertEquals(setOf("c"), ContactChannelDedup.repeatedPhoneIds(phones))
        assertEquals("список не изменён", copy, phones)
        assertEquals(setOf("e2"), ContactChannelDedup.repeatedEmailIds(listOf(email("e1", "a@b.ru"), email("e2", "A@B.RU"))))
    }

    @Test fun hasRepeats_isFalseForCleanContact_trueWhenSomethingRepeats() {
        assertFalse(ContactChannelDedup.hasRepeats(contact(phones = listOf(phone("a", "89123456789")), emails = listOf(email("e", "a@b.ru")))))
        assertTrue(ContactChannelDedup.hasRepeats(contact(phones = listOf(phone("a", "89123456789"), phone("b", "+7 912 345 67 89")))))
        assertTrue(ContactChannelDedup.hasRepeats(contact(emails = listOf(email("e1", "a@b.ru"), email("e2", "A@B.RU")))))
    }

    // ── Restore: тот же номер с другим id — не «новая запись из backup» ──

    private fun merge(cur: Contact, bak: Contact): Pair<Contact, RestoreMerger> {
        val m = RestoreMerger({ null }, "now")
        return m.mergeContact(cur, bak) to m
    }

    @Test fun restore_sameNumberDifferentId_isNotAddedAgain() {
        val cur = contact(phones = listOf(phone("new-id", "+7 912 345-67-89", primary = true)))
        val bak = contact(phones = listOf(phone("old-id", "89123456789", primary = true)))
        val (merged, m) = merge(cur, bak)
        assertEquals("тот же номер не дублируется при восстановлении", 1, merged.phones.size)
        assertEquals("new-id", merged.phones[0].id)
        assertFalse("разница лишь в форматировании номера — не конфликт", m.conflictFields.contains("number"))
    }

    @Test fun restore_genuinelyDifferentNumber_isStillRestored() {
        val cur = contact(phones = listOf(phone("a", "+7 912 345-67-89")))
        val bak = contact(phones = listOf(phone("a", "+7 912 345-67-89"), phone("b", "+7 900 111-22-33")))
        val (merged, _) = merge(cur, bak)
        assertEquals("недостающий реальный номер из backup по-прежнему добавляется", 2, merged.phones.size)
    }

    @Test fun restore_sameIdEditedNumber_isStillAFieldConflict_notASecondRow() {
        val cur = contact(phones = listOf(phone("a", "+7 900 111-22-33")))
        val bak = contact(phones = listOf(phone("a", "+7 912 345-67-89")))
        val (merged, m) = merge(cur, bak)
        assertEquals(1, merged.phones.size)
        assertEquals("правка в текущем состоянии побеждает", "+7 900 111-22-33", merged.phones[0].number)
        assertTrue(m.conflictFields.contains("number"))
    }

    @Test fun restore_sameEmailDifferentId_isNotAddedAgain() {
        val cur = contact(emails = listOf(email("x", "Ivan@Mail.ru")))
        val bak = contact(emails = listOf(email("y", "ivan@mail.ru")))
        assertEquals(1, merge(cur, bak).first.emails.size)
    }
}
