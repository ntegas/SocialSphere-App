package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Round-trip тест ContactNoteCodec: encode(Contact) → decode(String) должен
 * восстанавливать заметки/личные детали/подарки/доп.поля по своим местам
 * (фидбэк владельца 2026-08-11: «чтобы приложение определяло, что и куда
 * добавить» при обратном импорте из контактов телефона), а не одной
 * generic-заметкой. decode() работает с уже «настоящим» текстом (реальные
 * переносы строк, без vCard-эскейпинга) — как раз то, что отдаёт
 * getDeviceContacts()/распакованный parseVCard().
 */
class ContactNoteCodecTest {

    private fun contactWith(
        notes: List<Note> = emptyList(),
        personalDetails: List<PersonalDetail> = emptyList(),
        gifts: List<GiftIdea> = emptyList(),
        nextStep: String? = null,
        talkingPoints: String? = null,
        canHelpWith: String? = null,
        iCanHelpWith: String? = null,
        meetContext: String? = null,
        tags: List<String> = emptyList()
    ) = Contact(
        id = "c1", firstName = "Иван", lastName = "Петров",
        photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL,
        socialRole = SocialRole.REGULAR, communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        notes = notes, personalDetails = personalDetails, gifts = gifts,
        nextStep = nextStep, talkingPoints = talkingPoints, canHelpWith = canHelpWith,
        iCanHelpWith = iCanHelpWith, meetContext = meetContext, tags = tags,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    @Test
    fun encode_contactWithNothing_returnsNull() {
        assertNull(ContactNoteCodec.encode(contactWith()))
    }

    @Test
    fun decode_nullOrBlank_returnsAllEmpty() {
        val d1 = ContactNoteCodec.decode(null)
        val d2 = ContactNoteCodec.decode("   ")
        listOf(d1, d2).forEach {
            assertTrue(it.notes.isEmpty())
            assertTrue(it.personalDetails.isEmpty())
            assertTrue(it.gifts.isEmpty())
            assertNull(it.nextStep)
            assertNull(it.fallbackText)
        }
    }

    @Test
    fun roundTrip_allNoteTypes_preserveTypeAndText() {
        val notes = NoteType.values().mapIndexed { i, type ->
            Note(id = "n$i", contactId = "c1", type = type, text = "Текст $i — с тире",
                isImportant = false, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        }
        val encoded = ContactNoteCodec.encode(contactWith(notes = notes))!!
        val decoded = ContactNoteCodec.decode(encoded)

        assertEquals(notes.size, decoded.notes.size)
        notes.forEach { original ->
            assertTrue(
                "Не нашли заметку типа ${original.type} с текстом «${original.text}»",
                decoded.notes.any { it.type == original.type && it.text == original.text }
            )
        }
        assertNull(decoded.fallbackText)
    }

    // ФИКС (2026-08-12): раньше isLocked никак не отражался в vCard NOTE —
    // «Экспорт в контакты телефона» → «Импорт из контактов» тихо снимал защиту
    // с заметки. Теперь суффикс на теге переживает round-trip.
    @Test
    fun roundTrip_lockedNote_preservesIsLocked() {
        val notes = listOf(
            Note(id = "n1", contactId = "c1", type = NoteType.IMPORTANT_TO_REMEMBER,
                text = "Секретная деталь", isImportant = false, isLocked = true,
                createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"),
            Note(id = "n2", contactId = "c1", type = NoteType.GENERAL,
                text = "Обычная деталь", isImportant = false, isLocked = false,
                createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        )
        val encoded = ContactNoteCodec.encode(contactWith(notes = notes))!!
        val decoded = ContactNoteCodec.decode(encoded)

        assertEquals(2, decoded.notes.size)
        val locked = decoded.notes.find { it.text == "Секретная деталь" }!!
        assertTrue(locked.isLocked)
        assertEquals(NoteType.IMPORTANT_TO_REMEMBER, locked.type)
        val unlocked = decoded.notes.find { it.text == "Обычная деталь" }!!
        assertFalse(unlocked.isLocked)
        assertNull(decoded.fallbackText)
    }

    @Test
    fun roundTrip_personalDetail_withAndWithoutNote() {
        val pd = listOf(
            PersonalDetail(id = "pd1", contactId = "c1", category = PersonalDetailCategory.LIKES, value = "Кофе", note = "Без сахара"),
            PersonalDetail(id = "pd2", contactId = "c1", category = PersonalDetailCategory.ALLERGIES, value = "Орехи", note = null)
        )
        val encoded = ContactNoteCodec.encode(contactWith(personalDetails = pd))!!
        val decoded = ContactNoteCodec.decode(encoded)

        assertEquals(2, decoded.personalDetails.size)
        val likes = decoded.personalDetails.find { it.category == PersonalDetailCategory.LIKES }!!
        assertEquals("Кофе", likes.value)
        assertEquals("Без сахара", likes.note)
        val allergies = decoded.personalDetails.find { it.category == PersonalDetailCategory.ALLERGIES }!!
        assertEquals("Орехи", allergies.value)
        assertNull(allergies.note)
    }

    @Test
    fun roundTrip_gift_fullAndMinimal() {
        val gifts = listOf(
            GiftIdea(id = "g1", contactId = "c1", title = "Книга по шахматам", note = "Видел в магазине",
                link = "https://example.com/book", date = "2026-09-01", status = GiftStatus.IDEA),
            GiftIdea(id = "g2", contactId = "c1", title = "Шарф", note = null, link = null, date = null, status = GiftStatus.GIVEN)
        )
        val encoded = ContactNoteCodec.encode(contactWith(gifts = gifts))!!
        val decoded = ContactNoteCodec.decode(encoded)

        assertEquals(2, decoded.gifts.size)
        val book = decoded.gifts.find { it.status == GiftStatus.IDEA }!!
        assertEquals("Книга по шахматам", book.title)
        assertEquals("Видел в магазине", book.note)
        assertEquals("https://example.com/book", book.link)
        assertEquals("2026-09-01", book.date)

        val scarf = decoded.gifts.find { it.status == GiftStatus.GIVEN }!!
        assertEquals("Шарф", scarf.title)
        assertNull(scarf.note)
        assertNull(scarf.link)
        assertNull(scarf.date)
    }

    @Test
    fun roundTrip_scalarFieldsAndTags() {
        val c = contactWith(
            nextStep = "Позвонить насчёт дачи",
            talkingPoints = "Спросить про отпуск",
            canHelpWith = "Ремонт",
            iCanHelpWith = "Юр. консультации",
            meetContext = "На свадьбе у Кости",
            tags = listOf("важное", "семья")
        )
        val encoded = ContactNoteCodec.encode(c)!!
        val decoded = ContactNoteCodec.decode(encoded)

        assertEquals("Позвонить насчёт дачи", decoded.nextStep)
        assertEquals("Спросить про отпуск", decoded.talkingPoints)
        assertEquals("Ремонт", decoded.canHelpWith)
        assertEquals("Юр. консультации", decoded.iCanHelpWith)
        assertEquals("На свадьбе у Кости", decoded.meetContext)
        assertEquals(listOf("важное", "семья"), decoded.tags)
    }

    @Test
    fun decode_freeTextWrittenDirectlyInPhoneContacts_goesToFallback_notLost() {
        // Заметка, которую владелец мог написать прямо в приложении «Контакты»
        // телефона (не через наш экспорт) — без меток, обычный текст.
        val raw = "Купил ему подарок на день рождения, не забыть"
        val decoded = ContactNoteCodec.decode(raw)

        assertTrue(decoded.notes.isEmpty())
        assertEquals(raw, decoded.fallbackText)
    }

    @Test
    fun decode_mixOfTaggedAndFreeText_bothPreserved() {
        val raw = "[Важно помнить] Не любит громкую музыку\nОбычная заметка без метки"
        val decoded = ContactNoteCodec.decode(raw)

        assertEquals(1, decoded.notes.size)
        assertEquals(NoteType.IMPORTANT_TO_REMEMBER, decoded.notes[0].type)
        assertEquals("Не любит громкую музыку", decoded.notes[0].text)
        assertEquals("Обычная заметка без метки", decoded.fallbackText)
    }

    @Test
    fun decode_unknownCategoryOrStatusTag_fallsBackGracefully_noCrash() {
        // Метка похожа на формат, но категория/статус — не из текущего enum
        // (например, бэкап из будущей версии приложения с новой категорией).
        val raw = "[Личное:Будущая категория] значение\n[Подарок:Будущий статус] что-то"
        val decoded = ContactNoteCodec.decode(raw)

        assertTrue(decoded.personalDetails.isEmpty())
        assertTrue(decoded.gifts.isEmpty())
        assertNotNull(decoded.fallbackText)
        assertTrue(decoded.fallbackText!!.contains("Личное:Будущая категория"))
        assertTrue(decoded.fallbackText!!.contains("Подарок:Будущий статус"))
    }

    @Test
    fun roundTrip_fullContact_everythingTogether() {
        val c = contactWith(
            notes = listOf(
                Note(id = "n1", contactId = "c1", type = NoteType.WORK, text = "Обсудили проект",
                    isImportant = false, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
            ),
            personalDetails = listOf(
                PersonalDetail(id = "pd1", contactId = "c1", category = PersonalDetailCategory.INTERESTS, value = "Шахматы")
            ),
            gifts = listOf(
                GiftIdea(id = "g1", contactId = "c1", title = "Часы", note = null, link = null, date = null, status = GiftStatus.BOUGHT)
            ),
            nextStep = "Написать письмо",
            tags = listOf("клиент")
        )
        val encoded = ContactNoteCodec.encode(c)!!
        val decoded = ContactNoteCodec.decode(encoded)

        assertEquals(1, decoded.notes.size)
        assertEquals(1, decoded.personalDetails.size)
        assertEquals(1, decoded.gifts.size)
        assertEquals("Написать письмо", decoded.nextStep)
        assertEquals(listOf("клиент"), decoded.tags)
        assertNull(decoded.fallbackText)
    }
}
