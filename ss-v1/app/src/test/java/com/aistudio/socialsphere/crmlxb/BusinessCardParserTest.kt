package com.aistudio.socialsphere.crmlxb

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
        assertTrue(card.emails.contains("ivan.petrov@tesla.ru"))
        assertTrue("телефон нормализован", card.phones.any { it.contains("9161234567") })
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
        assertTrue(card.emails.contains("anna@gmail.com"))
        assertTrue(card.website == null || !card.website!!.contains("gmail"))
    }

    @Test
    fun latinCardPhonesAndEmail() {
        val text = "John Smith\nAcme Inc\n+1 415 555 0100\njohn@acme.com"
        val card = BusinessCardParser.parse(text)
        assertEquals("John", card.firstName)
        assertEquals("Smith", card.lastName)
        assertTrue(card.emails.contains("john@acme.com"))
        assertTrue(card.phones.any { it.contains("4155550100") })
        assertTrue(card.company?.contains("Acme") == true)
    }

    @Test
    fun emptyTextYieldsEmptyCard() {
        val card = BusinessCardParser.parse("   \n  \n")
        assertEquals("", card.firstName)
        assertTrue(card.phones.isEmpty())
        assertTrue(card.emails.isEmpty())
    }
}
