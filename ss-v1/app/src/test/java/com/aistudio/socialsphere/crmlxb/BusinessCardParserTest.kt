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

    @Test
    fun greekDoctorCard_realDeviceOcrText_2026_08_11() {
        // Сырой OCR-текст с реальной визитки (живая проверка на устройстве,
        // 2026-08-11) — до фикса: firstName/lastName оставались пустыми
        // (мусорная строка "Ан го" перехватывала место имени раньше настоящей
        // строки с именем, а сама строка с именем не проходила фильтр слов
        // из-за приписанных степеней MD, MSc), position не находился вообще
        // (в словаре не было ни одного медицинского термина), company был
        // "ssi." (обрывок нечитаемого логотипа).
        val text = """
            ssi.
            /
            4
            Ан го
            C linic
            Στέργιος М. Λάλλος MD, MSc
            Ορθοπαιδικό
            ς Χειρουργός
            Αν. Διευθυντής Ορθοπαιδικής
            Κλινική
            Χειρουρ
            γική Ισχίου, [όνατος & Αθ
            ς Metropolitan General
            т. Επιμελητής Sale
            т Spital Bern, Ελβετία - АТ
            λητικών Κακώσεων
            OS Klinik Heidelberg, Γερμανία
            Βαλτετσίου 4, 153 43 Ay. Nap
            ασκευή
            Т: 215 5405 400 К: 6977 584 820
            Е: info@arthroclinic.gr -
            www.arthroclinic.gr
            ==
            ell
            м,
        """.trimIndent()

        val card = BusinessCardParser.parse(text)

        assertEquals("Στέργιος", card.firstName)
        assertTrue("фамилия должна содержать Λάλλος, была: ${card.lastName}",
            card.lastName.contains("Λάλλος"))
        assertTrue("должность должна найти «хирург», была: ${card.position}",
            card.position?.contains("Χειρουργός") == true)
        // "ssi." — мусор с нечитаемого логотипа, не должен уверенно попадать в company
        assertTrue("company не должен быть мусором 'ssi.', был: ${card.company}",
            card.company != "ssi.")
        assertTrue(card.phones.any { it.number.contains("2155405400") })
        assertTrue(card.phones.any { it.number.contains("6977584820") })
        assertTrue(card.emails.any { it.email == "info@arthroclinic.gr" })
        assertTrue(card.website?.contains("arthroclinic.gr") == true)
    }
}
