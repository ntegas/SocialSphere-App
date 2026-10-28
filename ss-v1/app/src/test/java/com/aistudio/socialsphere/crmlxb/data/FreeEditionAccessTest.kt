package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.ContactPhone
import com.aistudio.socialsphere.crmlxb.model.PhoneType
import com.aistudio.socialsphere.crmlxb.ui.screens.ImportResultStats
import com.aistudio.socialsphere.crmlxb.ui.screens.performImport
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BATCH 3 (free edition conversion) — regression at the actual call site:
 * importing contacts WITH phone numbers, past the old
 * FREE_CONTACT_WITH_PHONE_QUOTA (3/month), must import every candidate.
 * The quota field/methods (AppSettings.contactsWithPhoneRemainingThisMonth/
 * recordContactWithPhoneUsed) and the skip branch in performImport() that
 * consumed them were deleted entirely in this batch — this proves the
 * removal by exercising the production code path, not just by grepping for
 * the absence of a symbol.
 */
@RunWith(RobolectricTestRunner::class)
class FreeEditionAccessTest {

    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(timeoutMs = 5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут в setup()")
        // ImportResultStats — процессно-глобальный объект, и duplicatesSkipped
        // там только инкрементируется, никогда не сбрасывается (в отличие от
        // contactsImported, который performImport перезаписывает присваиванием).
        // Без сброса утверждение «дубликатов не пропущено» проверяло не ЭТОТ
        // импорт, а то, что ни один тест ДО него в том же JVM не пропустил
        // дубликат, — то есть было зелёным случайно и зависело от порядка
        // выполнения классов. Сбрасываем, чтобы assert'ы ниже измеряли ровно
        // свой вызов performImport.
        ImportResultStats.contactsImported = 0
        ImportResultStats.duplicatesSkipped = 0
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear()
        AppStateStore.companies.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.addresses.clear()
        AppStateStore.companyRelations.clear()
        db.close()
    }

    private fun pollUntilTrue(timeoutMs: Long = 5000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
            ok = check()
        }
        ok
    }

    @Test
    fun performImport_moreThanOldPhoneQuota_importsAllWithoutSkipping() {
        // Старый лимит был 3 контакта с телефоном в месяц — берём заведомо больше.
        val candidates = (1..5).map { n ->
            ImportContactCandidate(
                firstName = "Contact$n",
                lastName = "FreeEditionTest",
                phones = listOf(
                    ContactPhone(
                        id = "placeholder",
                        contactId = "placeholder",
                        number = "+7 900 000-00-0$n",
                        type = PhoneType.MOBILE,
                        isPrimary = true
                    )
                )
            )
        }

        // BATCH 5: performImport стала suspend (реконсилирует импортированные
        // теги в управляемую систему). runBlocking здесь безопасен — в её
        // цепочке нет перехода на Dispatchers.Main, только await записи.
        runBlocking { performImport(candidates, ApplicationProvider.getApplicationContext()) }

        assertEquals(5, ImportResultStats.contactsImported)
        assertEquals(0, ImportResultStats.duplicatesSkipped)
        assertEquals(5, AppStateStore.contacts.count { it.lastName == "FreeEditionTest" })
    }
}
