package com.aistudio.socialsphere.crmlxb.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

/**
 * PHONE / VCF PACK #4 — FRONT 11/12: входы Share и ручной выбор файла.
 *
 * Оба входа приложения обязаны звать ОДИН канонический путь ([VcfImport.load] → ContentResolver → байты →
 * декодирование → разбор). Здесь ОДНА и та же фикстура (QP + греческий + кириллица + base64-фото) проходит через
 * оба входа и даёт идентичный результат; файловый путь не предполагается (только content://).
 */
@RunWith(RobolectricTestRunner::class)
class VcfEntryPointsTest {

    private lateinit var ctx: Context
    private val uri: Uri = Uri.parse("content://com.android.contacts/contacts/as_vcard/0r1-ABC")

    @Before
    fun setup() { ctx = ApplicationProvider.getApplicationContext() }

    /** Реальная фикстура «как из Android Contacts»: vCard 2.1, QP + CHARSET, греческая и русская части, фото. */
    private val fixtureBytes: ByteArray = VcfFixtures.concat(
        VcfFixtures.bytes(VcfFixtures.lines("BEGIN:VCARD", "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("Деденко")};${VcfFixtures.qp("Алексей")};;;",
            "ORG;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:${VcfFixtures.qp("Acme, Ltd")}",
            "PHOTO;ENCODING=BASE64;JPEG:/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsK",
            "TELabcd1234567890+/", "", "TEL;CELL:+7 900 111-22-33",
            "ADR;HOME;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;;${VcfFixtures.qp("ул. Ленина 5")};${VcfFixtures.qp("Москва")};;;${VcfFixtures.qp("Россия")}",
            "END:VCARD")),
        VcfFixtures.bytes(VcfFixtures.lines("BEGIN:VCARD", "VERSION:3.0", "N:Παπαδόπουλος;Νίκος;;;", "TEL:+306912345678",
            "ADR;TYPE=WORK:;;Ερμού 10;Αθήνα;;10563;Ελλάδα", "END:VCARD")),
    )

    private fun serve(bytes: ByteArray = fixtureBytes) {
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    private fun norm(c: ImportContactCandidate) = listOf(
        c.firstName, c.lastName, c.companyName, c.phones.map { it.number },
        c.addresses.map { listOf(it.addressLine, it.city, it.country) },
    )

    private fun shareIntent(type: String = "text/x-vcard") = Intent(Intent.ACTION_SEND).apply {
        this.type = type; putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun candidates(load: VcfImport.Load?): List<ImportContactCandidate> =
        (load as? VcfImport.Load.Loaded)?.candidates ?: throw AssertionError("ожидалось Loaded, получено $load")

    /** Ключевой тест FRONT 12: одна фикстура — два входа — идентичный результат и без mojibake. */
    @Test
    fun sameFixture_throughShareAndManualPicker_yieldsIdenticalCandidates() {
        serve()
        val viaShare = candidates(VcfImport.loadFromIntent(ctx, shareIntent()))
        serve()
        val viaManual = candidates(VcfImport.load(ctx, uri))          // ImportContactsScreen.vcfLauncher

        assertEquals(viaShare.map { norm(it) }, viaManual.map { norm(it) })
        assertEquals(2, viaShare.size)
        assertEquals(listOf("Алексей", "Деденко", "Acme, Ltd", listOf("+7 900 111-22-33")), viaShare[0].let { norm(it).take(4) })
        assertEquals(listOf(listOf("ул. Ленина 5", "Москва", "Россия")), norm(viaShare[0])[4])
        assertEquals("Νίκος", viaShare[1].firstName)
        assertEquals("фото не стало телефоном", 1, viaShare[0].phones.size)
    }

    // ── Share-интенты: все формы, которые манифест реально принимает ──

    @Test
    fun share_textVcard_textXVcard_andMimeParameters_areAccepted() {
        listOf("text/vcard", "text/x-vcard", "text/x-vcard; charset=UTF-8", "TEXT/VCARD").forEach { mime ->
            serve()
            assertEquals("MIME $mime", 2, candidates(VcfImport.loadFromIntent(ctx, shareIntent(mime))).size)
        }
    }

    @Test
    fun view_actionView_withData_isAccepted() {
        serve()
        val view = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "text/x-vcard") }
        assertEquals(2, candidates(VcfImport.loadFromIntent(ctx, view)).size)
    }

    @Test
    fun share_uriInClipDataOnly_isAccepted() {
        serve()
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/vcard"; clipData = ClipData.newRawUri("vcf", uri) }
        assertEquals(2, candidates(VcfImport.loadFromIntent(ctx, intent)).size)
    }

    @Test
    fun nonVcardIntents_areIgnored_notAnError() {
        assertNull(VcfImport.loadFromIntent(ctx, Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri) }))
        assertNull(VcfImport.loadFromIntent(ctx, Intent(Intent.ACTION_MAIN)))
        assertNull(VcfImport.loadFromIntent(ctx, Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "text/x-vcard" }))
        assertTrue(VcfImport.isVcardMime("text/vcard") && VcfImport.isVcardMime("text/x-vcard; charset=utf-8"))
        assertFalse(VcfImport.isVcardMime("text/plain") || VcfImport.isVcardMime(null))
    }

    // ── восстановимые сбои чтения — явное состояние, не исключение и не «тихо ничего» ──

    @Test
    fun shareWithoutStream_isError_notCrash() {
        val load = VcfImport.loadFromIntent(ctx, Intent(Intent.ACTION_SEND).apply { type = "text/x-vcard" })
        assertTrue("$load", load is VcfImport.Load.Error)
    }

    @Test
    fun unreadableUri_isError_notCrash() {
        // ничего не зарегистрировано — ContentResolver отдаёт FileNotFoundException/null stream
        val load = VcfImport.load(ctx, Uri.parse("content://nobody.home/missing.vcf"))
        assertTrue("$load", load is VcfImport.Load.Error)
    }

    @Test
    fun fileWithoutContacts_isEmpty_notError() {
        serve(VcfFixtures.bytes(VcfFixtures.lines("BEGIN:VCARD", "N:Семья;;;;", "END:VCARD")))
        assertEquals(VcfImport.Load.Empty, VcfImport.load(ctx, uri))
    }

    /**
     * Guard «один канонический путь»: ни один production-файл, кроме самого VcfImport/ContactImporter, не разбирает
     * vCard сам (parseVCard(...) на строке из bufferedReader() — прежний дублирующийся путь MainActivity/ImportScreens).
     */
    @Test
    fun noProductionFileParsesVcardOutsideTheCanonicalPath() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory } ?: return   // нет исходников рядом — пропуск
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "VcfImport.kt" && it.name != "ContactImporter.kt" }
            .filter { f -> f.readText().let { "parseVCard(" in it } }
            .map { it.name }.toList()
        assertEquals("vCard разбирается только через VcfImport", emptyList<String>(), offenders)
    }
}
