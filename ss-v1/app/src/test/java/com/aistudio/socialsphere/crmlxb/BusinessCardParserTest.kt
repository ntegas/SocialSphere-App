package com.aistudio.socialsphere.crmlxb

import com.aistudio.socialsphere.crmlxb.model.EmailType
import com.aistudio.socialsphere.crmlxb.model.PhoneType
import com.aistudio.socialsphere.crmlxb.utils.BusinessCardParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessCardParserTest {

    @Test
    fun parsesRussianCard() {
        val text = """
            Иван Петров
            Директор по продажам
            ООО «Тесла»
            +7 (916) 123-45-67
            ivan.petrov@tesla.ru
            www.tesla.ru
        """.trimIndent()

        val card = BusinessCardParser.parse(text)

        assertEquals("Иван", card.firstName)
        assertEquals("Петров", card.lastName)
        assertTrue(card.emails.any { it.email == "ivan.petrov@tesla.ru" })
        assertTrue("телефон нормализован", card.phones.any { it.number.contains("9161234567") })
        assertTrue(card.company?.contains("Тесла") == true)
        assertTrue(card.position?.contains("Директор") == true)
        assertTrue(card.website?.contains("tesla.ru") == true)
    }

    @Test
    fun emailDomainNotMistakenForWebsite() {
        // Если есть только email и нет отдельного сайта — website не должен
        // подцепить домен из email.
        val card = BusinessCardParser.parse("Анна Смирнова\nanna@gmail.com")
        assertEquals("Анна", card.firstName)
        assertEquals("Смирнова", card.lastName)
        assertTrue(card.emails.any { it.email == "anna@gmail.com" })
        assertTrue(card.website == null || !card.website!!.contains("gmail"))
    }

    @Test
    fun latinCardPhonesAndEmail() {
        val text = "John Smith\nAcme Inc\n+1 415 555 0100\njohn@acme.com"
        val card = BusinessCardParser.parse(text)
        assertEquals("John", card.firstName)
        assertEquals("Smith", card.lastName)
        assertTrue(card.emails.any { it.email == "john@acme.com" })
        assertTrue(card.phones.any { it.number.contains("4155550100") })
        assertTrue(card.company?.contains("Acme") == true)
    }

    @Test
    fun emptyTextYieldsEmptyCard() {
        val card = BusinessCardParser.parse("   \n  \n")
        assertEquals("", card.firstName)
        assertTrue(card.phones.isEmpty())
        assertTrue(card.emails.isEmpty())
    }

    // ─── Ниже: тесты защиты от потери данных (фидбэк владельца, 2026-07-28 —
    // «должен не терять ни фразы»). Раньше parser.phones/emails были одним
    // значением, отброшенным ScanCardScreen через firstOrNull(); теперь это
    // полные списки с типом, а всё непонятое уходит в unmatched. ───

    @Test
    fun allPhonesAndEmailsKept_withGuessedTypes() {
        // Раньше только первый телефон/email доходили до пользователя —
        // второй и все следующие пропадали молча (concrete data-loss bug).
        val text = """
            Иван Петров
            Директор
            ООО Ромашка
            Моб.: +7 916 123-45-67
            Раб.: +7 495 000-11-22
            ivan@romashka.ru
            ivan.p@gmail.com
        """.trimIndent()

        val card = BusinessCardParser.parse(text)

        assertEquals(2, card.phones.size)
        assertTrue(card.phones.any { it.number.contains("9161234567") && it.type == PhoneType.MOBILE })
        assertTrue(card.phones.any { it.number.contains("4950001122") && it.type == PhoneType.WORK })

        assertEquals(2, card.emails.size)
        assertTrue(card.emails.any { it.email == "ivan@romashka.ru" && it.type == EmailType.WORK })
        assertTrue(card.emails.any { it.email == "ivan.p@gmail.com" && it.type == EmailType.PERSONAL })
    }

    @Test
    fun faxIsTaggedOther_notWorkOrMobile() {
        // Нет PhoneType.FAX в модели — факс не должен молча стать WORK/MOBILE,
        // это честная потеря точности, а не потеря самого номера.
        val text = """
            Иван Петров
            ООО Ромашка
            Тел.: +7 495 111-22-33
            Факс: +7 495 111-22-34
            ivan@romashka.ru
        """.trimIndent()

        val card = BusinessCardParser.parse(text)

        assertEquals(2, card.phones.size)
        assertTrue(card.phones.any { it.number.contains("4951112233") && it.type == PhoneType.WORK })
        assertTrue(card.phones.any { it.number.contains("4951112234") && it.type == PhoneType.OTHER })
    }

    @Test
    fun companyDetectedWithoutLegalMarker() {
        // Раньше компания находилась ТОЛЬКО при наличии ООО/LLC/Inc и т.п. —
        // «Bright Solutions» без юр.формы молча оставалась null.
        val text = """
            Maria Ivanova
            Bright Solutions
            маркетолог
            +7 999 111-22-33
            maria@brightsolutions.com
        """.trimIndent()

        val card = BusinessCardParser.parse(text)

        assertEquals("Maria", card.firstName)
        assertEquals("Ivanova", card.lastName)
        assertEquals("Bright Solutions", card.company)
        assertTrue(card.position?.contains("маркетолог") == true)
    }

    @Test
    fun addressLineDetected() {
        val text = """
            Иван Петров
            ООО Ромашка
            ул. Ленина, 10, Москва
            +7 916 123-45-67
            ivan@romashka.ru
        """.trimIndent()

        val card = BusinessCardParser.parse(text)

        assertTrue(card.addresses.any { it.contains("Ленина") })
    }

    @Test
    fun unclaimedLineEndsUpInUnmatched_notDropped() {
        // Сеть безопасности парсера: слоган/строка без регекс-сигнала не
        // исчезает бесследно, а попадает в unmatched.
        val text = """
            Иван Петров
            Директор
            ООО Ромашка
            Мы меняем мир
            +7 916 123-45-67
            ivan@romashka.ru
        """.trimIndent()

        val card = BusinessCardParser.parse(text)

        assertTrue(card.unmatched.contains("Мы меняем мир"))
    }
}
