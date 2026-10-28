package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Жалоба владельца 2026-10-08: «не сохранив, я теряю данные». Черновик формы контакта пишется на диск и
 * переживает смерть процесса. Хранилище не должно ронять форму ни при каких условиях (битый файл,
 * устаревший черновик) и не должно оставлять личные данные после «стереть всё».
 */
@RunWith(RobolectricTestRunner::class)
class ContactFormDraftStoreTest {

    private lateinit var ctx: Context

    @Before fun setup() { ctx = ApplicationProvider.getApplicationContext(); ContactFormDraftStore.clearAll(ctx) }
    @After fun tearDown() { ContactFormDraftStore.clearAll(ctx) }

    private fun draft(name: String = "Анна") = ContactFormDraft(
        firstName = name, lastName = "Иванова", middleName = "", namePrefix = "", nameSuffix = "",
        phoneticFirstName = "", phoneticMiddleName = "", phoneticLastName = "",
        phones = listOf(ContactPhone("p1", "c1", "+7 912 345-67-89", PhoneType.WORK, true, "днём")),
        emails = listOf(ContactEmail("e1", "c1", "anna@mail.ru", EmailType.PERSONAL, true)),
        messengers = emptyList(), addresses = emptyList(),
        selectedCompanyId = "", companyPosition = "Директор", companyDept = "", workNote = "",
        linkedOfficeAddressId = null,
        relationshipType = RelationshipType.FRIEND, customRelationType = "Кум",
        secondaryRelationshipTypes = listOf(RelationshipType.COLLEAGUE),
        connectionLevel = ConnectionLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.WEEKLY, customRhythmDaysText = "",
        photoUri = null,
        nickname = "", profession = "Врач", nextStep = "", canHelpWith = "", iCanHelpWith = "", talkingPoints = "",
        meetContext = "", familyNote = "сын Петя", meetDate = "",
        tags = emptyList(), newDates = listOf(DraftDate("День рождения", "1990-03-12", CalendarItemType.BIRTHDAY)),
        newNotes = listOf("Познакомились на конференции"), quickNoteText = "недописанная заметка",
        relations = emptyList(),
    )

    @Test fun roundTrip_preservesEveryTypedField() {
        val d = draft()
        ContactFormDraftStore.save(ctx, "new", d)
        val loaded = ContactFormDraftStore.load(ctx, "new")
        assertNotNull(loaded)
        assertEquals("все введённые поля, включая недописанную заметку и несохранённые даты", d, loaded!!.draft)
    }

    @Test fun keysAreIndependent_newAndExistingContact() {
        ContactFormDraftStore.save(ctx, "new", draft("Новый"))
        ContactFormDraftStore.save(ctx, "c-123", draft("Правка"))
        assertEquals("Новый", ContactFormDraftStore.load(ctx, "new")!!.draft.firstName)
        assertEquals("Правка", ContactFormDraftStore.load(ctx, "c-123")!!.draft.firstName)
        ContactFormDraftStore.clear(ctx, "new")
        assertNull(ContactFormDraftStore.load(ctx, "new"))
        assertNotNull("чистка одного ключа не трогает другой", ContactFormDraftStore.load(ctx, "c-123"))
    }

    @Test fun expiredDraft_isNotOffered_andIsDeleted() {
        val old = System.currentTimeMillis() - ContactFormDraftStore.MAX_AGE_MS - 1_000
        ContactFormDraftStore.save(ctx, "new", draft(), now = old)
        assertNull(ContactFormDraftStore.load(ctx, "new"))
        assertFalse("устаревший файл удалён", File(ctx.filesDir, "form_drafts").listFiles().orEmpty().any { it.name.endsWith(".json") })
    }

    @Test fun corruptedFile_neverThrows_andIsDropped() {
        ContactFormDraftStore.save(ctx, "new", draft())
        val f = File(ctx.filesDir, "form_drafts").listFiles().orEmpty().first { it.name.endsWith(".json") }
        f.writeText("{ это не json")
        assertNull(ContactFormDraftStore.load(ctx, "new"))
        assertFalse(f.exists())
    }

    @Test fun save_skippedWhenFormAlreadyFinished_soDeletedDraftIsNotResurrected() {
        ContactFormDraftStore.save(ctx, "new", draft(), stillWanted = { false })
        assertNull(ContactFormDraftStore.load(ctx, "new"))
    }

    @Test fun clearAll_removesEverything_forWipe() {
        ContactFormDraftStore.save(ctx, "new", draft())
        ContactFormDraftStore.save(ctx, "c-1", draft())
        ContactFormDraftStore.clearAll(ctx)
        assertTrue(ContactFormDraftStore.load(ctx, "new") == null && ContactFormDraftStore.load(ctx, "c-1") == null)
    }
}
