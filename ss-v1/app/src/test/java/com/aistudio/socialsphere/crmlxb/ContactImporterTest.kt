package com.aistudio.socialsphere.crmlxb

import com.aistudio.socialsphere.crmlxb.utils.parseCsv
import com.aistudio.socialsphere.crmlxb.utils.parseVCard
import com.aistudio.socialsphere.crmlxb.utils.normalizeBirthday
import org.junit.Assert.*
import org.junit.Test

/**
 * Регрессионные тесты импорта контактов. Кодируют уроки 44–45 из
 * SOCIALSPHERE_RULES.md: профессии не должны становиться компаниями (CSV),
 * vCard ORG = «Компания;Отдел» берётся только первым полем, companyName ≠ jobTitle.
 * Парсеры чистые (без Context) — обычный JUnit, без Robolectric.
 */
class ContactImporterTest {

    // ─── Урок 44: Google CSV — профессия НЕ должна стать компанией ───

    @Test
    fun csv_googleHeaders_titleDoesNotBecomeCompany() {
        // Реальные заголовки экспорта Google Contacts
        val csv = """
            Name,Given Name,Family Name,Organization 1 - Name,Organization 1 - Title,Organization 1 - Department,Phone 1 - Value,E-mail 1 - Value
            Иван Петров,Иван,Петров,Acme Corp,Senior Engineer,R&D,+7 900 111-22-33,ivan@acme.com
        """.trimIndent()

        val res = parseCsv(csv)
        assertEquals(1, res.size)
        val c = res[0]
        assertEquals("Иван", c.firstName)
        assertEquals("Петров", c.lastName)
        // Компания = название организации, НЕ должность
        assertEquals("Acme Corp", c.companyName)
        assertEquals("Senior Engineer", c.jobTitle)
        // Корневой инвариант урока 44/45: companyName ≠ jobTitle
        assertNotEquals(c.companyName, c.jobTitle)
    }

    @Test
    fun csv_exactMatchBeatsSubstring_forCompany() {
        // "Organization 1 - Title" содержит "org", но исключение по "title"
        // не даёт ему стать компанией; "Company" — точное совпадение.
        val csv = """
            First Name,Last Name,Company,Organization 1 - Title,Phone
            Анна,Сидорова,ООО Ромашка,Директор,+7 901 000-00-00
        """.trimIndent()

        val c = parseCsv(csv).single()
        assertEquals("ООО Ромашка", c.companyName)
        assertEquals("Директор", c.jobTitle)
    }

    @Test
    fun csv_quotedFieldWithComma_isOneValue() {
        val csv = """
            First Name,Last Name,Company,Phone
            Олег,"Кузнецов, мл.","Stark, Inc.",+7 902 123-45-67
        """.trimIndent()

        val c = parseCsv(csv).single()
        assertEquals("Кузнецов, мл.", c.lastName)
        assertEquals("Stark, Inc.", c.companyName)
    }

    @Test
    fun csv_rowWithoutNameOrPhone_isSkipped() {
        val csv = """
            First Name,Last Name,Phone
            ,,
            Мария,,+7 903 222-33-44
        """.trimIndent()

        val res = parseCsv(csv)
        assertEquals(1, res.size)
        assertEquals("Мария", res[0].firstName)
    }

    @Test
    fun csv_emptyOrHeaderOnly_returnsEmpty() {
        assertTrue(parseCsv("").isEmpty())
        assertTrue(parseCsv("First Name,Last Name,Phone").isEmpty())
    }

    // ─── Урок 45: vCard ORG — только первое поле, params, ≠ title ───

    @Test
    fun vcard_orgWithDepartment_takesOnlyCompany() {
        val vcf = """
            BEGIN:VCARD
            VERSION:3.0
            N:Петров;Иван;;;
            ORG:Acme Corp;Отдел разработки;Группа K2
            TITLE:Ведущий инженер
            TEL;TYPE=CELL:+7 900 111-22-33
            END:VCARD
        """.trimIndent()

        val c = parseVCard(vcf).single()
        assertEquals("Иван", c.firstName)
        assertEquals("Петров", c.lastName)
        assertEquals("Acme Corp", c.companyName)   // без «;Отдел;Группа»
        assertEquals("Ведущий инженер", c.jobTitle)
        assertNotEquals(c.companyName, c.jobTitle)
    }

    @Test
    fun vcard_orgWithCharsetParam_isParsed() {
        // ORG с параметром: брать значение после ПЕРВОГО ':' и до ';'
        val vcf = """
            BEGIN:VCARD
            N:Сидорова;Анна;;;
            ORG;CHARSET=UTF-8:ООО Ромашка;Бухгалтерия
            EMAIL;TYPE=WORK:anna@romashka.ru
            END:VCARD
        """.trimIndent()

        val c = parseVCard(vcf).single()
        assertEquals("ООО Ромашка", c.companyName)
    }

    @Test
    fun vcard_cardWithoutPhoneOrEmail_isDropped() {
        // Группы/метки телефонной книги приходят как карточки без контактов
        val vcf = """
            BEGIN:VCARD
            N:Семья;;;;
            END:VCARD
            BEGIN:VCARD
            N:Петров;Иван;;;
            TEL:+7 900 111-22-33
            END:VCARD
        """.trimIndent()

        val res = parseVCard(vcf)
        assertEquals(1, res.size)
        assertEquals("Иван", res[0].firstName)
    }

    @Test
    fun vcard_fnFallbackWhenNoStructuredName() {
        val vcf = """
            BEGIN:VCARD
            FN:Олег Кузнецов
            TEL:+7 902 123-45-67
            END:VCARD
        """.trimIndent()

        val c = parseVCard(vcf).single()
        assertEquals("Олег", c.firstName)
        assertEquals("Кузнецов", c.lastName)
    }

    // Регрессия (жалоба владельца: «ввёл 3-4 слова в имя — показывает только 2»).
    // FN-фолбэк (без структурированного поля N) обязан разложить 4 слова на
    // имя + середину (все слова между первым и последним) + фамилию, а не
    // отбросить лишние слова.
    @Test
    fun vcard_fnFallback_fourWords_keepsAllMiddleWords() {
        val vcf = """
            BEGIN:VCARD
            FN:Иван Петрович Сергеевич Кузнецов
            TEL:+7 902 123-45-67
            END:VCARD
        """.trimIndent()

        val c = parseVCard(vcf).single()
        assertEquals("Иван", c.firstName)
        assertEquals("Петрович Сергеевич", c.middleName)
        assertEquals("Кузнецов", c.lastName)
    }

    @Test
    fun vcard_duplicatePhonesByDigits_areDeduped() {
        val vcf = """
            BEGIN:VCARD
            N:Петров;Иван;;;
            TEL;TYPE=CELL:+7 900 111-22-33
            TEL;TYPE=HOME:+7 (900) 111-22-33
            END:VCARD
        """.trimIndent()

        val c = parseVCard(vcf).single()
        assertEquals(1, c.phones.size)
    }

    @Test
    fun vcard_note_wasNeverParsed_nowReadAndUnescaped() {
        // ФИКС (2026-08-11): раньше NOTE вообще не читался при импорте .vcf-
        // файла (getDeviceContacts() читал, а этот путь — нет). ExportManager
        // пишет ВЕСЬ блок ОДНОЙ vCard-строкой с эскейпингом (\n → \\n,
        // запятая/точка-с-запятой экранируются) — проверяем, что распаковка
        // (vUnescape) возвращает настоящие переносы строк, готовые для
        // ContactNoteCodec.decode.
        val vcf = """
            BEGIN:VCARD
            N:Петров;Иван;;;
            TEL:+7 900 111-22-33
            NOTE:[Важно помнить] Не любит громкую музыку\n[Личное:Нравится] кофе\, чай и печенье\; всё сразу
            END:VCARD
        """.trimIndent()

        val c = parseVCard(vcf).single()
        assertEquals(
            "[Важно помнить] Не любит громкую музыку\n[Личное:Нравится] кофе, чай и печенье; всё сразу",
            c.notes
        )
    }

    @Test
    fun vcard_noteAbsent_candidateNotesIsNull() {
        val vcf = """
            BEGIN:VCARD
            N:Петров;Иван;;;
            TEL:+7 900 111-22-33
            END:VCARD
        """.trimIndent()

        assertNull(parseVCard(vcf).single().notes)
    }

    // ─── normalizeBirthday: форматы из телефонной книги / vCard ───

    // Поведение изменено (см. док normalizeBirthday): даты БЕЗ года сохраняются
    // как «--MM-DD» — фиктивный 1972 показывал ложный возраст; формат «--MM-DD»
    // понимают parseFlexibleDate/displayEventDate. Тесты обновлены 2026-07-02.
    @Test
    fun birthday_noYearWithDashes_keptWithoutYear() {
        assertEquals("--03-12", normalizeBirthday("--03-12"))
    }

    @Test
    fun birthday_noYearCompact_keptWithoutYear() {
        assertEquals("--03-12", normalizeBirthday("--0312"))
    }

    @Test
    fun birthday_feb29NoYear_keptWithoutYear() {
        // 29 февраля без года не теряется и не искажается
        assertEquals("--02-29", normalizeBirthday("--02-29"))
    }

    @Test
    fun birthday_fullDate_truncatedToTen() {
        assertEquals("1990-03-12", normalizeBirthday("1990-03-12T00:00:00Z"))
    }

    @Test
    fun birthday_invalidOrBlank_returnsNull() {
        assertNull(normalizeBirthday(null))
        assertNull(normalizeBirthday(""))
        assertNull(normalizeBirthday("not-a-date"))
        assertNull(normalizeBirthday("1990-13-40"))
    }

    // Некоторые OEM-синки (Xiaomi/Samsung/Huawei и т.п.) пишут Event.START_DATE
    // не по ISO — раньше такие дни рождения тихо пропускались при импорте
    // (жалоба владельца: «не все дни рождения проимпортировались»).
    @Test
    fun birthday_legacyDotFormat_isParsed() {
        assertEquals("1990-03-12", normalizeBirthday("12.03.1990"))
    }

    @Test
    fun birthday_legacySlashFormat_isParsed() {
        assertEquals("1990-03-12", normalizeBirthday("12/03/1990"))
    }

    @Test
    fun birthday_legacySpaceFormat_isParsed() {
        assertEquals("1990-03-12", normalizeBirthday("12 03 1990"))
    }
}
