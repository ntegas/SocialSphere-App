package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.ContactFormDraft
import com.aistudio.socialsphere.crmlxb.data.ContactFormDraftStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleAppTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Жалоба владельца 2026-10-08: «когда я что-то записываю, оно сбивается — Назад, чтобы убрать клавиатуру,
 * и т.п. — я, не сохранив, теряю данные. Должно выскакивать сообщение: сохранить или отменить».
 *
 * Форма контакта (единая для «Новый» и «Правка»):
 *  • нетронутая форма уходит молча; изменённая на «Отмена»/«Назад» спрашивает Сохранить / Не сохранять / Продолжить;
 *  • «Продолжить ввод» ничего не теряет; «Не сохранять» не пишет контакт и удаляет черновик; «Сохранить» сохраняет;
 *  • изменённая форма автосохраняется на диск (переживает смерть процесса) и предлагается при следующем открытии.
 *
 * Отдельный класс (один Compose UI-класс на запуск Gradle — §60.11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class ContactEditUnsavedGuardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        ContactFormDraftStore.clearAll(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        if (!settled) fail("loadInitialData() не завершился за таймаут")
        AppStateStore.contacts.clear(); AppStateStore.calendarItems.clear(); AppStateStore.notes.clear()
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
    }

    @After
    fun tearDown() {
        ContactFormDraftStore.clearAll(ctx)
        AppStateStore.contacts.clear(); AppStateStore.calendarItems.clear(); AppStateStore.notes.clear()
        AppStateStore.tags.clear(); AppStateStore.tagMembers.clear()
        db.close()
    }

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { Thread.sleep(20); ok = check() }
        return ok
    }

    private var backCalls = 0
    private var createdId: String? = null

    private fun render(contactId: String? = null) {
        backCalls = 0; createdId = null
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                ContactEditScreen(contactId = contactId, onNavigateBack = { backCalls++ }, onCreated = { createdId = it })
            }
        }
        compose.waitForIdle()
    }

    private fun typeName(name: String) {
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(name)
        compose.waitForIdle()
    }

    private fun clickText(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction())[0].performClick()
        compose.waitForIdle()
    }

    private fun clickTag(tag: String) {
        compose.onNodeWithTagCompat(tag).performClick()
        compose.waitForIdle()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTagCompat(tag: String) = onNode(hasTestTag(tag))

    private fun tagShown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun seedContact(first: String = "Старый"): String {
        val id = UUID.randomUUID().toString(); val now = "2026-10-08T00:00"
        runBlocking {
            AppStateStore.addContact(Contact(
                id = id, firstName = first, lastName = "Контакт", photoUri = null,
                relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
                importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
                communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = now, updatedAt = now
            )).await()
        }
        return id
    }

    private fun storedDraftCount() = java.io.File(ctx.filesDir, "form_drafts").listFiles().orEmpty().count { it.name.endsWith(".json") }

    // ─── уход с формы ───

    @Test
    fun untouched_new_form_cancels_silently() {
        render()
        clickText(ctx.getString(R.string.common_cancel))
        assertEquals("нетронутая форма уходит сразу, без вопроса", 1, backCalls)
        assertEquals(false, tagShown("unsaved_discard"))
    }

    @Test
    fun typed_form_cancel_asks_and_keep_editing_loses_nothing() {
        render()
        typeName("Анна")
        clickText(ctx.getString(R.string.common_cancel))
        assertEquals("изменённая форма НЕ уходит молча", 0, backCalls)
        assertTrue("показан вопрос «Сохранить / Не сохранять / Продолжить»", tagShown("unsaved_discard"))
        assertTrue(tagShown("unsaved_save"))

        clickTag("unsaved_keep")
        assertEquals(false, tagShown("unsaved_discard"))
        assertEquals(0, backCalls)
        assertTrue("введённое имя на месте", compose.onAllNodes(hasText("Анна")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun discard_leaves_writes_nothing_and_removes_the_draft() {
        render()
        typeName("Борис")
        // автосохранение (600 мс виртуального времени + запись на IO)
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("черновик появился на диске", pollUntil(5_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); storedDraftCount() == 1 })

        clickText(ctx.getString(R.string.common_cancel))
        clickTag("unsaved_discard")
        assertEquals(1, backCalls)
        assertEquals("«Не сохранять» не создаёт контакт", 0, AppStateStore.contacts.size)
        assertEquals("осознанный отказ удаляет черновик", 0, storedDraftCount())
    }

    @Test
    fun save_from_the_prompt_creates_the_contact_and_removes_the_draft() {
        render()
        typeName("Вера")
        clickText(ctx.getString(R.string.common_cancel))
        clickTag("unsaved_save")
        val ok = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); createdId != null }
        if (!ok) fail("сохранение из вопроса не завершилось за таймаут")
        assertEquals("Вера", AppStateStore.getContact(createdId!!)!!.firstName)
        assertEquals(0, storedDraftCount())
    }

    @Test
    fun system_back_on_a_changed_form_asks_instead_of_leaving() {
        render()
        typeName("Глеб")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(0, backCalls)
        assertTrue("системный «Назад» тоже спрашивает", tagShown("unsaved_discard"))
    }

    @Test
    fun existing_contact_edit_asks_only_after_a_real_change() {
        val id = seedContact("Дарья")
        render(id)
        clickText(ctx.getString(R.string.common_cancel))
        assertEquals("в режиме правки без изменений — уходит сразу", 1, backCalls)
        assertNull(ContactFormDraftStore.load(ctx, id))
    }

    // ─── черновик на диске ───

    @Test
    fun stored_draft_is_offered_and_continue_restores_it() {
        ContactFormDraftStore.save(ctx, "new", ContactFormDraft(
            firstName = "Елена", lastName = "Из черновика", middleName = "", namePrefix = "", nameSuffix = "",
            phoneticFirstName = "", phoneticMiddleName = "", phoneticLastName = "",
            phones = listOf(ContactPhone("p1", "x", "+30 690 111 2222", PhoneType.MOBILE, true)),
            emails = emptyList(), messengers = emptyList(), addresses = emptyList(),
            selectedCompanyId = "", companyPosition = "", companyDept = "", workNote = "", linkedOfficeAddressId = null,
            relationshipType = null, customRelationType = "", secondaryRelationshipTypes = emptyList(),
            connectionLevel = ConnectionLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.NOT_TRACKED, customRhythmDaysText = "", photoUri = null,
            nickname = "", profession = "", nextStep = "", canHelpWith = "", iCanHelpWith = "", talkingPoints = "",
            meetContext = "", familyNote = "", meetDate = "",
            tags = emptyList(), newDates = emptyList(), newNotes = emptyList(), quickNoteText = "", relations = emptyList(),
        ))
        render()
        // Диалог читает черновик с IO-потока — ждём его появления.
        assertTrue("предложено продолжить с черновика", pollUntil(5_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); tagShown("draft_continue")
        })
        clickTag("draft_continue")
        assertTrue("имя из черновика вернулось в форму", compose.onAllNodes(hasText("Елена")).fetchSemanticsNodes().isNotEmpty())
        assertTrue("телефон из черновика вернулся в форму", compose.onAllNodes(hasText("+30 690 111 2222")).fetchSemanticsNodes().isNotEmpty())
        assertEquals("восстановленная форма считается изменённой — «Отмена» спросит", false, backCalls > 0)
        clickText(ctx.getString(R.string.common_cancel))
        assertTrue(tagShown("unsaved_discard"))
    }

    @Test
    fun stored_draft_can_be_deleted_from_the_offer() {
        ContactFormDraftStore.save(ctx, "new", ContactFormDraft(
            firstName = "Зоя", lastName = "", middleName = "", namePrefix = "", nameSuffix = "",
            phoneticFirstName = "", phoneticMiddleName = "", phoneticLastName = "",
            phones = emptyList(), emails = emptyList(), messengers = emptyList(), addresses = emptyList(),
            selectedCompanyId = "", companyPosition = "", companyDept = "", workNote = "", linkedOfficeAddressId = null,
            relationshipType = null, customRelationType = "", secondaryRelationshipTypes = emptyList(),
            connectionLevel = ConnectionLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.NOT_TRACKED, customRhythmDaysText = "", photoUri = null,
            nickname = "", profession = "", nextStep = "", canHelpWith = "", iCanHelpWith = "", talkingPoints = "",
            meetContext = "", familyNote = "", meetDate = "",
            tags = emptyList(), newDates = emptyList(), newNotes = emptyList(), quickNoteText = "", relations = emptyList(),
        ))
        render()
        assertTrue(pollUntil(5_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); tagShown("draft_discard") })
        clickTag("draft_discard")
        assertEquals(0, storedDraftCount())
        assertEquals("после удаления черновика форма пустая — Отмена уходит молча", false, compose.onAllNodes(hasText("Зоя")).fetchSemanticsNodes().isNotEmpty())
        clickText(ctx.getString(R.string.common_cancel))
        assertEquals(1, backCalls)
    }
}
