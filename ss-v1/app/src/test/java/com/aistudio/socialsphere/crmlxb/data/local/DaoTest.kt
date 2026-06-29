package com.aistudio.socialsphere.crmlxb.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner

/**
 * CRUD-тесты DAO на in-memory Room. Проверяем insert/read/update(REPLACE)/delete
 * для ContactDao/CompanyDao/CalendarDao. Фабрики Entity — через именованные
 * аргументы (у Entity много NOT NULL полей).
 */
@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: SocialsphereDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var companyDao: CompanyDao
    private lateinit var calendarDao: CalendarDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactDao = db.contactDao()
        companyDao = db.companyDao()
        calendarDao = db.calendarDao()
    }

    @After
    fun tearDown() = db.close()

    // ── Фабрики валидных Entity ──────────────────────────────────────────
    private fun contact(id: String, firstName: String = "Иван") = ContactEntity(
        id = id, firstName = firstName, lastName = "Петров", photoUri = null,
        relationshipType = "OTHER", connectionLevel = "NORMAL", importanceLevel = "NORMAL",
        socialRole = "REGULAR", communicationRhythm = "NOT_TRACKED",
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun phone(id: String, contactId: String) = ContactPhoneEntity(
        id = id, contactId = contactId, companyId = null, number = "+30123",
        type = "MOBILE", isPrimary = true, comment = null
    )

    private fun relation(id: String) = ContactRelationEntity(
        id = id, firstContactId = "c1", secondContactId = "c2",
        firstRole = "Друг", secondRole = "Друг", note = null
    )

    private fun sizeInfo(id: String, contactId: String) = SizeInfoEntity(
        id = id, contactId = contactId, clothingSize = "M", shoeSize = null,
        ringSize = null, other = null
    )

    private fun personalDetail(id: String, contactId: String) = PersonalDetailEntity(
        id = id, contactId = contactId, category = "HOBBY", value = "Шахматы", note = null
    )

    private fun company(id: String, name: String = "ACME") = CompanyEntity(
        id = id, name = name, logoUri = null, industry = "OTHER",
        description = null, website = null,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun calItem(id: String, title: String = "Встреча") = CalendarItemEntity(
        id = id, title = title, description = null, type = "EVENT",
        startDate = "2026-01-01", startTime = null, endDate = null, endTime = null,
        isAllDay = true, status = "ACTIVE", importance = "NORMAL", colorKey = null,
        recurrenceRule = null, createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    private fun link(id: String, itemId: String) = CalendarItemLinkEntity(
        id = id, calendarItemId = itemId, targetType = "CONTACT", targetId = "c1"
    )

    private fun reminder(id: String, itemId: String) = ReminderRuleEntity(
        id = id, calendarItemId = itemId, reminderType = "AT_TIME",
        offsetValue = null, offsetUnit = null, exactDateTime = null
    )

    // ── ContactDao ───────────────────────────────────────────────────────
    @Test
    fun contact_insertReadUpdateDelete() = runTest {
        contactDao.insertContact(contact("c1"))
        assertEquals(1, contactDao.getAllContacts().size)
        assertEquals("Иван", contactDao.getAllContacts().first().firstName)

        // update = REPLACE по тому же PK
        contactDao.insertContact(contact("c1", firstName = "Пётр"))
        assertEquals(1, contactDao.getAllContacts().size)
        assertEquals("Пётр", contactDao.getAllContacts().first().firstName)

        contactDao.deleteContact("c1")
        assertTrue(contactDao.getAllContacts().isEmpty())
    }

    @Test
    fun phones_insertGetDeleteForContact() = runTest {
        contactDao.insertContact(contact("c1"))
        contactDao.insertPhones(listOf(phone("p1", "c1"), phone("p2", "c1")))
        assertEquals(2, contactDao.getContactPhones().size)

        contactDao.deletePhonesForContact("c1")
        assertTrue(contactDao.getContactPhones().isEmpty())
    }

    @Test
    fun contactRelations_insertGetDelete() = runTest {
        contactDao.insertContactRelations(listOf(relation("r1")))
        assertEquals(1, contactDao.getContactRelations().size)
        contactDao.deleteContactRelation("r1")
        assertTrue(contactDao.getContactRelations().isEmpty())
    }

    @Test
    fun sizeInfo_insertGetDelete() = runTest {
        contactDao.insertContact(contact("c1"))
        contactDao.insertSizeInfo(sizeInfo("s1", "c1"))
        assertEquals(1, contactDao.getSizeInfos().size)
        assertEquals("M", contactDao.getSizeInfos().first().clothingSize)
        contactDao.deleteSizeInfoForContact("c1")
        assertTrue(contactDao.getSizeInfos().isEmpty())
    }

    @Test
    fun personalDetails_insertGetDelete() = runTest {
        contactDao.insertContact(contact("c1"))
        contactDao.insertPersonalDetails(listOf(personalDetail("pd1", "c1")))
        assertEquals(1, contactDao.getPersonalDetails().size)
        contactDao.deletePersonalDetailsForContact("c1")
        assertTrue(contactDao.getPersonalDetails().isEmpty())
    }

    @Test
    fun updateLastContactDate_changesOnlyThatField() = runTest {
        contactDao.insertContact(contact("c1"))
        contactDao.updateLastContactDate("c1", "2026-06-28", "2026-06-28T10:00")
        val c = contactDao.getAllContacts().first()
        assertEquals("2026-06-28", c.lastContactDate)
        assertEquals("Иван", c.firstName) // остальное не тронуто
    }

    // ── CompanyDao ───────────────────────────────────────────────────────
    @Test
    fun company_insertReadUpdateDelete() = runTest {
        companyDao.insertCompany(company("co1"))
        assertEquals(1, companyDao.getAllCompanies().size)

        companyDao.insertCompany(company("co1", name = "Globex"))
        assertEquals(1, companyDao.getAllCompanies().size)
        assertEquals("Globex", companyDao.getAllCompanies().first().name)

        companyDao.deleteCompany("co1")
        assertTrue(companyDao.getAllCompanies().isEmpty())
    }

    // ── CalendarDao ──────────────────────────────────────────────────────
    @Test
    fun calendarItem_insertReadDelete() = runTest {
        calendarDao.insertCalendarItem(calItem("e1"))
        assertEquals(1, calendarDao.getAllCalendarItems().size)
        assertEquals("Встреча", calendarDao.getAllCalendarItems().first().title)
        calendarDao.deleteCalendarItem("e1")
        assertTrue(calendarDao.getAllCalendarItems().isEmpty())
    }

    @Test
    fun calendarLinksAndReminders_insertGetDeleteForItem() = runTest {
        calendarDao.insertCalendarItem(calItem("e1"))
        calendarDao.insertCalendarItemLinks(listOf(link("l1", "e1")))
        calendarDao.insertReminderRules(listOf(reminder("rm1", "e1")))
        assertEquals(1, calendarDao.getCalendarItemLinks().size)
        assertEquals(1, calendarDao.getReminderRules().size)

        calendarDao.deleteLinksForItem("e1")
        assertTrue(calendarDao.getCalendarItemLinks().isEmpty())
    }
}
