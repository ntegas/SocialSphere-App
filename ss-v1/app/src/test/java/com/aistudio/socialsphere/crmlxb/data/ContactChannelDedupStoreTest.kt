package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import android.database.MatrixCursor
import android.provider.ContactsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toEntity
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.ContactImporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Жалоба владельца 2026-10-08: «по два раза записаны телефоны, которые я не записал».
 * РЕШЕНИЕ ВЛАДЕЛЬЦА 2026-10-10: «сам тупо удалять не надо — пусть дубли не создаются, а удаляю я».
 *  • ПРОФИЛАКТИКА на входе: импорт из книги телефона (контакт агрегирует raw-контакты Google/WhatsApp/SIM с
 *    одним номером) приносит номер один раз;
 *  • НИЧЕГО не удаляется молча: уже сохранённые повторы остаются в Room и в памяти после запуска/записи,
 *    а экран «Поиск дублей» получает их список ([AppStateStore.contactsWithRepeatedChannels]) только для показа.
 */
@RunWith(RobolectricTestRunner::class)
class ContactChannelDedupStoreTest {

    private lateinit var db: SocialsphereDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("loadInitialData() не завершился за таймаут в setup()")
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear(); AppStateStore.companies.clear()
        db.close()
    }

    private fun pollUntilTrue(timeoutMs: Long = 10_000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { kotlinx.coroutines.delay(20); ok = check() }
        ok
    }

    private fun <T> awaitOffMainThread(timeoutMs: Long = 15_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch {
            try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        val settled = pollUntilTrue(timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            done.isCompleted
        }
        if (!settled) fail("suspend-операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }

    private fun phone(id: String, number: String, owner: String = "c1", primary: Boolean = false) =
        ContactPhone(id, owner, number, PhoneType.MOBILE, primary)

    private fun contact(id: String = "c1", phones: List<ContactPhone> = emptyList(), emails: List<ContactEmail> = emptyList()) = Contact(
        id = id, firstName = "Иван", lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        phones = phones, emails = emails, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    private fun roomPhones(contactId: String) =
        awaitOffMainThread { db.contactDao().getContactPhones().filter { it.contactId == contactId } }

    // ── импорт из книги телефона ──

    @Test fun deviceBook_aggregatedRawContactsWithSameNumber_yieldOnePhone() {
        val cols = ContactImporter.DATA_PROJECTION
        val cursor = MatrixCursor(cols)
        val phoneMime = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        val emailMime = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        fun row(mime: String, d1: String, d2: Int) = cursor.addRow(cols.map { c ->
            when (c) {
                ContactsContract.Data.CONTACT_ID -> "77"
                ContactsContract.Data.MIMETYPE -> mime
                ContactsContract.Data.LOOKUP_KEY -> "lk"
                ContactsContract.Data.DATA1 -> d1
                ContactsContract.Data.DATA2 -> d2
                else -> null
            }
        })
        // Google + WhatsApp + SIM: один и тот же человек, один и тот же номер, три raw-контакта.
        row(phoneMime, "+7 912 345-67-89", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        row(phoneMime, "89123456789", ContactsContract.CommonDataKinds.Phone.TYPE_OTHER)
        row(phoneMime, "+79123456789", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        row(phoneMime, "+7 900 111-22-33", ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
        row(emailMime, "ivan@mail.ru", ContactsContract.CommonDataKinds.Email.TYPE_HOME)
        row(emailMime, "IVAN@mail.ru", ContactsContract.CommonDataKinds.Email.TYPE_HOME)
        val candidate = ContactImporter.readDataRows(cursor, emptyMap()).values.single()
        assertEquals("тот же номер из трёх raw-контактов — одна запись, второй номер остаётся", 2, candidate.phones.size)
        assertEquals(listOf("+7 912 345-67-89", "+7 900 111-22-33"), candidate.phones.map { it.number })
        assertTrue("первая запись — основная", candidate.phones[0].isPrimary)
        assertEquals(1, candidate.emails.size)
    }

    // ── НЕТ молчаливого удаления (решение владельца 2026-10-10) ──

    @Test fun addContact_keepsEverythingTheUserTyped_noSilentRemoval() {
        val c = contact(phones = listOf(phone("a", "+7 912 345-67-89", primary = true), phone("b", "89123456789")))
        awaitOffMainThread { AppStateStore.addContact(c).await() }
        assertEquals("запись не удаляется молча", 2, AppStateStore.contacts.single { it.id == "c1" }.phones.size)
        assertEquals(2, roomPhones("c1").size)
    }

    @Test fun updateContact_keepsBothNumbers_noSilentRemoval() {
        val base = contact(phones = listOf(phone("a", "+7 912 345-67-89", primary = true)))
        awaitOffMainThread { AppStateStore.addContact(base).await() }
        val canonical = AppStateStore.contacts.single { it.id == "c1" }
        val edited = canonical.copy(phones = canonical.phones + phone("b", "8 (912) 345-67-89"))
        awaitOffMainThread { AppStateStore.updateContact(canonical, edited).await() }
        assertEquals(2, AppStateStore.contacts.single { it.id == "c1" }.phones.size)
        assertEquals(2, roomPhones("c1").size)
    }

    @Test fun storedDuplicates_surviveStartup_areListedForTheOwner_andNothingIsRemoved() {
        // Данные «как у владельца»: повторы уже лежат в Room (записаны прежними версиями).
        awaitOffMainThread {
            db.contactDao().insertContact(contact().toEntity())
            db.contactDao().insertPhones(listOf(
                phone("a", "+7 912 345-67-89", primary = true).toEntity(),
                phone("b", "89123456789").toEntity(),
                phone("c", "+7 900 111-22-33").toEntity(),
            ))
            db.contactDao().insertContact(contact(id = "c2").toEntity())
            db.contactDao().insertPhones(listOf(phone("d", "+7 700 000-00-01", owner = "c2").toEntity()))
            AppStateStore.reloadFromDb()
        }
        assertEquals("повтор виден в памяти", 3, AppStateStore.contacts.single { it.id == "c1" }.phones.size)
        assertEquals("и остаётся в Room", 3, roomPhones("c1").size)
        assertEquals("в список для показа попал только контакт с повтором",
            listOf("c1"), AppStateStore.contactsWithRepeatedChannels().map { it.id })
        // Повторный «запуск» (перечитывание) тоже ничего не удаляет.
        awaitOffMainThread { AppStateStore.reloadFromDb() }
        assertEquals(3, roomPhones("c1").size)
    }
}
