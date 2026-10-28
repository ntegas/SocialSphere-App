package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.AddressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * PHONE / VCF PACK #4 — канонический разбор vCard из БАЙТОВ ([VcfImport.parse]).
 * Фикстуры M–U из задачи + кодировки/формы, которые реально выдают Android/Google/Apple/Windows.
 * Чистый JUnit (без Robolectric): парсер не зависит от Android.
 */
class VcfImportParserTest {

    private fun lines(vararg l: String) = VcfFixtures.lines(*l)
    private fun b(text: String, cs: Charset = Charsets.UTF_8) = VcfFixtures.bytes(text, cs)

    /** Сравнимое представление кандидата (id случайны). */
    private fun norm(c: ImportContactCandidate) = listOf(
        c.firstName, c.lastName, c.middleName, c.namePrefix, c.nameSuffix, c.companyName, c.jobTitle, c.notes, c.birthday,
        c.phones.map { it.number to it.type }, c.emails.map { it.email to it.type },
        c.addresses.map { listOf(it.addressLine, it.city, it.country, it.district, it.postalCode, it.addressType) },
    )

    private fun parseOne(bytes: ByteArray): ImportContactCandidate = VcfImport.parse(bytes).single()

    // ═════ M. Русский UTF-8 (имя, компания, адрес) ═════

    private val russian = lines(
        "BEGIN:VCARD", "VERSION:3.0", "N:Деденко;Алексей;;;", "FN:Алексей Деденко", "ORG:ООО «Ромашка»", "TITLE:Директор",
        "TEL;TYPE=CELL:+7 900 111-22-33", "EMAIL;TYPE=INTERNET:alexey@example.ru",
        "ADR;TYPE=HOME:;;ул. Ленина\\, д. 5;Москва;Центральный;101000;Россия", "END:VCARD",
    )

    @Test
    fun M_russianUtf8_noMojibake() {
        val c = parseOne(b(russian))
        assertEquals(listOf("Алексей", "Деденко", "ООО «Ромашка»", "Директор"), listOf(c.firstName, c.lastName, c.companyName, c.jobTitle))
        val a = c.addresses.single()
        assertEquals(listOf("ул. Ленина, д. 5", "Москва", "Россия", "Центральный", "101000", AddressType.HOME),
            listOf(a.addressLine, a.city, a.country, a.district, a.postalCode, a.addressType))
        assertEquals(listOf("+7 900 111-22-33"), c.phones.map { it.number })
    }

    @Test
    fun M2_utf8Bom_andMixedLineEndings_crlfLfCr() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\nN:Деденко;Алексей;;;\rTEL:+79001112233\r\nEND:VCARD\n"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val c = parseOne(VcfFixtures.concat(bom, b(text)))
        assertEquals(listOf("Алексей", "Деденко"), listOf(c.firstName, c.lastName))
    }

    // ═════ N. Греческий ═════

    private val greek = lines(
        "BEGIN:VCARD", "VERSION:3.0", "N:Παπαδόπουλος;Νίκος;;;", "ORG:Ελληνική Εταιρεία", "TEL:+306912345678",
        "ADR;TYPE=WORK:;;Ερμού 10;Αθήνα;;10563;Ελλάδα", "END:VCARD",
    )

    @Test
    fun N_greekUtf8() {
        val c = parseOne(b(greek))
        assertEquals(listOf("Νίκος", "Παπαδόπουλος", "Ελληνική Εταιρεία"), listOf(c.firstName, c.lastName, c.companyName))
        assertEquals(listOf("Ερμού 10", "Αθήνα", "Ελλάδα"), c.addresses.single().let { listOf(it.addressLine, it.city, it.country) })
    }

    @Test
    fun N2_greekLegacyIso88597_declaredCharset() {
        val cs = Charset.forName("ISO-8859-7")
        val vcf = lines("BEGIN:VCARD", "VERSION:2.1", "N;CHARSET=ISO-8859-7:Παπαδόπουλος;Νίκος;;;", "ORG;CHARSET=ISO-8859-7:Ελληνική Εταιρεία",
            "TEL:+306912345678", "ADR;WORK;CHARSET=ISO-8859-7:;;Ερμού 10;Αθήνα;;10563;Ελλάδα", "END:VCARD")
        val c = parseOne(b(vcf, cs))
        assertEquals(listOf("Νίκος", "Παπαδόπουλος", "Ελληνική Εταιρεία"), listOf(c.firstName, c.lastName, c.companyName))
        assertEquals("Αθήνα", c.addresses.single().city)
    }

    // ═════ O. Латиница; P. смешанные алфавиты; Q. эмодзи ═════

    @Test
    fun O_latinBaseline() {
        val c = parseOne(b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Smith;John;Q;Dr.;Jr.", "ORG:Acme Ltd", "TEL;TYPE=WORK:+15551234567",
            "EMAIL;TYPE=WORK:john@acme.example", "ADR;TYPE=WORK:;;5 Main St;Springfield;IL;62701;USA", "END:VCARD")))
        assertEquals(listOf("John", "Smith", "Q", "Dr.", "Jr.", "Acme Ltd"),
            listOf(c.firstName, c.lastName, c.middleName, c.namePrefix, c.nameSuffix, c.companyName))
        assertEquals(listOf("5 Main St", "Springfield", "USA"), c.addresses.single().let { listOf(it.addressLine, it.city, it.country) })
    }

    @Test
    fun P_mixedScript_greekNameLatinCompany() {
        val c = parseOne(b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Παπαδόπουλος;Νίκος;;;", "ORG:Acme Ltd", "TEL:+306912345678", "END:VCARD")))
        assertEquals(listOf("Νίκος", "Παπαδόπουλος", "Acme Ltd"), listOf(c.firstName, c.lastName, c.companyName))
    }

    @Test
    fun Q_emoji_nonBmp_rawUtf8_andQuotedPrintable() {
        val raw = parseOne(b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Smith;Anna 😀;;;", "ORG:🚀 Rocket Co.", "TEL:+15550001111", "END:VCARD")))
        assertEquals("Anna 😀", raw.firstName); assertEquals("🚀 Rocket Co.", raw.companyName)
        val qp = parseOne(b(lines("BEGIN:VCARD", "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Smith;${VcfFixtures.qp("Anna 😀")};;;",
            "ORG;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("🚀 Rocket Co.")}", "TEL:+15550001111", "END:VCARD")))
        assertEquals("Anna 😀", qp.firstName); assertEquals("🚀 Rocket Co.", qp.companyName)
    }

    // ═════ R. quoted-printable (vCard 2.1, как пишут Android-контакты) ═════

    @Test
    fun R_quotedPrintable_nameAndAddress_withSoftLineBreaks() {
        val street = "ул. Ленина, д. 5, кв. 3"
        val vcf = lines("BEGIN:VCARD", "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("Деденко")};${VcfFixtures.qp("Алексей")};;;",
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("Алексей Деденко")}",
            "TEL;CELL:+79001112233",
            "ADR;HOME;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;;${VcfFixtures.qp(street, softWrapAt = 30)};${VcfFixtures.qp("Москва")};;101000;${VcfFixtures.qp("Россия")}",
            "END:VCARD")
        val c = parseOne(b(vcf))
        assertEquals(listOf("Алексей", "Деденко"), listOf(c.firstName, c.lastName))
        val a = c.addresses.single()
        // «\,» в QP-значении экранирования нет — запятые внутри адреса остаются как есть (raw «,» разделителем не является)
        assertEquals(listOf(street, "Москва", "Россия"), listOf(a.addressLine, a.city, a.country))
        assertEquals(AddressType.HOME, a.addressType)
    }

    // ═════ S. свёрнутая (folded) длинная строка, перенос ВНУТРИ многобайтового символа ═════

    @Test
    fun S_foldedLongLine_splitInsideMultibyteChars() {
        val note = "Заметка: " + "очень длинная строка ".repeat(8)
        val org = "Общество с ограниченной ответственностью «Ромашка и партнёры»"
        val bytes = VcfFixtures.concat(
            b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Петров;Иван;;;", "TEL:+79001112233")),
            VcfFixtures.foldBytes("ORG:$org", 37), VcfFixtures.foldBytes("NOTE:$note", 41), b(lines("END:VCARD")),
        )
        val c = parseOne(bytes)
        assertEquals(org, c.companyName); assertEquals(note, c.notes)
    }

    // ═════ T. экранированные запятая/точка с запятой в ORG и ADR ═════

    @Test
    fun T_escapedCommaAndSemicolon_inOrganizationAndAddress() {
        val c = parseOne(b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Петров;Иван;;;", "ORG:Acme\\, Inc.;Отдел\\;1", "TITLE:Head\\, Sales",
            "TEL:+79001112233", "ADR:;;Ленина 5\\, кв. 3;Москва\\; центр;;;РФ", "END:VCARD")))
        assertEquals("Acme, Inc.", c.companyName); assertEquals("Head, Sales", c.jobTitle)
        val a = c.addresses.single()
        assertEquals(listOf("Ленина 5, кв. 3", "Москва; центр", "РФ"), listOf(a.addressLine, a.city, a.country))
    }

    // ═════ U. base64 PHOTO не портит соседние текстовые поля ═════

    private val photoB64 = "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/"
    // куски base64, начинающиеся с имён свойств — старый разбор принимал их за TEL/N/FN
    private val trapLines = listOf("TELabcd1234567890+/TELabcd1234567890+/", "NAME+abcdEFGH0123456789+/==", "FNxyz/+0123456789abcdefgh")

    @Test
    fun U_base64Photo_v30_foldedWithIndent_neighboursIntact() {
        val vcf = lines("BEGIN:VCARD", "VERSION:3.0", "N:Smith;John;;;", "PHOTO;ENCODING=b;TYPE=JPEG:$photoB64",
            *trapLines.map { " $it" }.toTypedArray(), "TEL:+15551234567", "EMAIL:john@example.com", "ORG:Acme Ltd", "END:VCARD")
        val c = parseOne(b(vcf))
        assertEquals(listOf("John", "Smith", "Acme Ltd"), listOf(c.firstName, c.lastName, c.companyName))
        assertEquals("фото не превращается в телефоны", listOf("+15551234567"), c.phones.map { it.number })
        assertEquals(listOf("john@example.com"), c.emails.map { it.email })
    }

    @Test
    fun U2_base64Photo_v21_unindentedContinuation_endsAtBlankLine() {
        val vcf = lines("BEGIN:VCARD", "VERSION:2.1", "N:Smith;John;;;", "PHOTO;ENCODING=BASE64;JPEG:$photoB64", *trapLines.toTypedArray(),
            "", "TEL:+15551234567", "EMAIL:john@example.com", "END:VCARD")
        val c = parseOne(b(vcf))
        assertEquals(listOf("John", "Smith"), listOf(c.firstName, c.lastName))
        assertEquals(listOf("+15551234567"), c.phones.map { it.number })
        assertEquals(listOf("john@example.com"), c.emails.map { it.email })
    }

    // ═════ прочие реальные формы ═════

    @Test
    fun windows1251_declaredCharset() {
        val cp1251 = Charset.forName("windows-1251")
        val c = parseOne(b(lines("BEGIN:VCARD", "VERSION:2.1", "N;CHARSET=WINDOWS-1251:Деденко;Алексей;;;", "ORG;CHARSET=WINDOWS-1251:ООО Ромашка",
            "TEL:+79001112233", "END:VCARD"), cp1251))
        assertEquals(listOf("Алексей", "Деденко", "ООО Ромашка"), listOf(c.firstName, c.lastName, c.companyName))
    }

    @Test
    fun utf16_withBom_leAndBe_andWithoutBom() {
        val text = lines("BEGIN:VCARD", "VERSION:3.0", "N:Παπαδόπουλος;Νίκος;;;", "TEL:+306912345678", "END:VCARD")
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        listOf(le, be, text.toByteArray(Charsets.UTF_16LE), text.toByteArray(Charsets.UTF_16BE)).forEachIndexed { i, bytes ->
            val c = parseOne(bytes)
            assertEquals("вариант UTF-16 #$i", listOf("Νίκος", "Παπαδόπουλος"), listOf(c.firstName, c.lastName))
        }
    }

    @Test
    fun groupPrefix_lowercaseNames_andTelUri() {
        val c = parseOne(b(lines("begin:vcard", "version:3.0", "n:Петров;Иван;;;", "item1.TEL;type=pref;type=CELL:+79001112233",
            "item2.TEL;VALUE=uri:tel:+7-900-555-66-77", "item1.EMAIL;TYPE=INTERNET:ivan@example.ru", "item1.X-ABLabel:_\$!<Other>!\$_", "end:vcard")))
        assertEquals(listOf("Иван", "Петров"), listOf(c.firstName, c.lastName))
        assertEquals(listOf("+79001112233", "+7-900-555-66-77"), c.phones.map { it.number })
        assertEquals(listOf("ivan@example.ru"), c.emails.map { it.email })
    }

    @Test
    fun base64EncodedTextProperty_isDecoded() {
        val name = java.util.Base64.getEncoder().encodeToString("Деденко;Алексей;;;".toByteArray(Charsets.UTF_8))
        val c = parseOne(b(lines("BEGIN:VCARD", "VERSION:3.0", "N;ENCODING=b;CHARSET=UTF-8:$name", "TEL:+79001112233", "END:VCARD")))
        assertEquals(listOf("Алексей", "Деденко"), listOf(c.firstName, c.lastName))
    }

    @Test
    fun severalCards_mixedEncodings_inOneFile() {
        val bytes = VcfFixtures.concat(
            b(lines("BEGIN:VCARD", "VERSION:2.1", "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("Иванов")};${VcfFixtures.qp("Иван")};;;", "TEL:+79001110001", "END:VCARD")),
            b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Παπαδόπουλος;Νίκος;;;", "TEL:+306900000002", "END:VCARD")),
            b(lines("BEGIN:VCARD", "N:Семья;;;;", "END:VCARD")),                                  // группа без телефона/email — отсекается
            b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Smith;John;;;", "EMAIL:john@example.com", "END:VCARD")),
        )
        val res = VcfImport.parse(bytes)
        assertEquals(listOf("Иван", "Νίκος", "John"), res.map { it.firstName })
    }

    @Test
    fun unknownCharsetName_fallsBackToUtf8() {
        val c = parseOne(b(lines("BEGIN:VCARD", "VERSION:2.1", "N;CHARSET=X-NO-SUCH-CHARSET:Деденко;Алексей;;;", "TEL:+79001112233", "END:VCARD")))
        assertEquals(listOf("Алексей", "Деденко"), listOf(c.firstName, c.lastName))
    }

    /** ДОКУМЕНТИРУЕМОЕ ограничение: кириллица в cp1251 БЕЗ объявления CHARSET не угадывается — не падаем и не теряем поля. */
    @Test
    fun undeclaredNonUtf8_doesNotCrash_fallsBackToLatin1_otherFieldsIntact() {
        val cp1251 = Charset.forName("windows-1251")
        val bytes = b(lines("BEGIN:VCARD", "VERSION:2.1", "N:Деденко;Алексей;;;", "TEL:+79001112233", "END:VCARD"), cp1251)
        val c = parseOne(bytes)
        assertEquals(String("Алексей".toByteArray(cp1251), Charsets.ISO_8859_1), c.firstName)
        assertEquals(listOf("+79001112233"), c.phones.map { it.number })
    }

    @Test
    fun legacyParseVCardString_isTheSameParserAsBytes() {
        val fromBytes = VcfImport.parse(b(russian)).map { norm(it) }
        val fromText = parseVCard(String(b(russian), Charsets.UTF_8)).map { norm(it) }
        assertEquals(fromBytes, fromText)
    }

    /** Round-trip с экспортом приложения: ADR, который раньше не читался вовсе, теперь возвращается. */
    @Test
    fun exportFormatAdr_isParsed_district_postal_type() {
        val c = parseOne(b(lines("BEGIN:VCARD", "VERSION:3.0", "N:Каноничная;Анна;;;", "TEL;TYPE=CELL,PREF:+79000000001",
            "ADR;TYPE=WORK:;;Тверская 1;Москва;Тверской;101000;РФ", "ADR;TYPE=OTHER:;;Невский 2;Санкт-Петербург;;;РФ", "END:VCARD")))
        assertEquals(2, c.addresses.size)
        assertEquals(listOf("Тверская 1", "Москва", "РФ", "Тверской", "101000", AddressType.WORK),
            c.addresses[0].let { listOf(it.addressLine, it.city, it.country, it.district, it.postalCode, it.addressType) })
        assertEquals(AddressType.OTHER, c.addresses[1].addressType)
    }

    @Test
    fun emptyOrGarbageInput_returnsEmptyWithoutCrash() {
        assertTrue(VcfImport.parse(ByteArray(0)).isEmpty())
        assertTrue(VcfImport.parse(byteArrayOf(0, 1, 2, 3, 127, -1, -2)).isEmpty())
        assertTrue(VcfImport.parse(b("just some text\nwithout vcard")).isEmpty())
    }
}
