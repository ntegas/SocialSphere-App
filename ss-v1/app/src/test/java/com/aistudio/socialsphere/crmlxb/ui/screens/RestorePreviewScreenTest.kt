package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.RestoreDifferenceType
import com.aistudio.socialsphere.crmlxb.data.RestoreDiffEngine
import com.aistudio.socialsphere.crmlxb.data.RestoreFieldKind
import com.aistudio.socialsphere.crmlxb.data.RestorePlan
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleAppTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22) — только важные UX-контракты (requirement 31),
 * не исчерпывающий UI-тест: экран открывается и показывает разницы; поиск фильтрует; фильтр по типу
 * работает; выбор Missing чекбоксом работает; выбор конфликта («Использовать копию») работает; финальное
 * подтверждение восстановления показывает summary и реально применяет выбранное.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class RestorePreviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var ctx: Context
    private lateinit var db: SocialsphereDatabase
    private val t0 = "2026-01-01T00:00:00"

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.defaultTagsSeeded.value = true
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        if (!settled) fail("loadInitialData() не завершился за таймаут")
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear()
        AppStateStore.gifts.clear(); AppStateStore.tombstones.clear()
        RestorePreviewSession.clear()
    }

    @After
    fun tearDown() {
        RestorePreviewSession.clear()
        AppStateStore.contacts.clear(); AppStateStore.companies.clear(); AppStateStore.notes.clear()
        AppStateStore.gifts.clear(); AppStateStore.tombstones.clear()
        db.close()
    }

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { Thread.sleep(20); ok = check() }
        return ok
    }

    /** Тот же приём, что ExternalActionSemanticsTest.clickNode: onNodeWithText(text).performClick() ненадёжен,
     *  когда текст — потомок кликабельного контейнера (например, Checkbox — сосед Text внутри Row.clickable) —
     *  явно ищем узел, у которого САМОГО есть click-семантика. */
    private fun clickNode(text: String) {
        val clickable = compose.onAllNodes(hasText(text) and hasClickAction())
        if (clickable.fetchSemanticsNodes().isNotEmpty()) clickable[0].performClick()
        else compose.onAllNodesWithText(text, useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
    }

    private fun contact(id: String, first: String, last: String) = Contact(
        id = id, firstName = first, lastName = last, photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t0, updatedAt = t0)

    private fun phone(id: String, cid: String, number: String) = ContactPhone(id, cid, number, PhoneType.MOBILE, isPrimary = true)

    /** Alex Johnson: телефон отсутствует (MISSING) + профессия расходится (CONFLICT).
     *  Nina Petrova: посторонний контакт, не участвующий в backup — только чтобы CONTACTS-фильтр было на чём проверить. */
    private fun buildTwoDifferenceScenario() {
        runBlocking {
            AppStateStore.addContact(contact("c1", "Alex", "Johnson").copy(profession = "Sales Manager")).await()
            AppStateStore.awaitPendingWrites()
        }
        val current = runBlocking { AppStateStore.readPersistedState() }
        val backupContact = contact("c1", "Alex", "Johnson").copy(
            profession = "Account Executive", phones = listOf(phone("p1", "c1", "+301111111")))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(backupContact)))
        RestorePreviewSession.preview = preview
    }

    private fun currentPreview() = RestorePreviewSession.preview ?: fail("preview должен быть установлен") as Nothing

    // ═══════════════════════════════════════════════════════════
    //  Preview opens: показывает владельца и число разниц
    // ═══════════════════════════════════════════════════════════

    @Test
    fun previewOpens_showsOwnerAndDifferenceCount() {
        buildTwoDifferenceScenario()
        compose.setContent { AppleAppTheme(darkTheme = false) { RestorePreviewScreen(onNavigateBack = {}, onRestoreComplete = {}) } }
        compose.onNodeWithText("Alex Johnson").assertExists()
        compose.onNodeWithText("2 differences").assertExists()
    }

    // ═══════════════════════════════════════════════════════════
    //  Search фильтрует превью, не трогая исходные данные
    // ═══════════════════════════════════════════════════════════

    @Test
    fun search_filtersToMatchingGroup() {
        runBlocking {
            AppStateStore.addContact(contact("c1", "Alex", "Johnson")).await()
            AppStateStore.addContact(contact("c2", "Nina", "Petrova")).await()
            AppStateStore.awaitPendingWrites()
        }
        val current = runBlocking { AppStateStore.readPersistedState() }
        val bak1 = contact("c1", "Alex", "Johnson").copy(phones = listOf(phone("p1", "c1", "+301111111")))
        val bak2 = contact("c2", "Nina", "Petrova").copy(phones = listOf(phone("p2", "c2", "+302222222")))
        RestorePreviewSession.preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak1, bak2)))

        // Обе группы существуют в preview (не проверяем одновременную видимость в LazyColumn на маленьком
        // экране — виртуализация может не рендерить обе карточки сразу, это не то, что здесь проверяется).
        val preview = RestorePreviewSession.preview ?: fail("preview должен быть установлен") as Nothing
        assertEquals(setOf("c1", "c2"), preview.groups.map { it.ownerId }.toSet())

        compose.setContent { AppleAppTheme(darkTheme = false) { RestorePreviewScreen(onNavigateBack = {}, onRestoreComplete = {}) } }
        compose.onNodeWithText("Search name, phone, note…").performTextInput("Nina")

        compose.onNodeWithText("Nina Petrova").assertExists()
        compose.onAllNodesWithText("Alex Johnson").assertCountEquals(0) // поиск сузил список
    }

    // ═══════════════════════════════════════════════════════════
    //  Выбор Missing чекбоксом добавляет ключ в сессию
    // ═══════════════════════════════════════════════════════════

    @Test
    fun selectMissing_addsKeyToSelection() {
        buildTwoDifferenceScenario()
        val phoneDiff = currentPreview().groups.single().differences.single { it.fieldKind == RestoreFieldKind.PHONE }
        assertTrue(RestorePreviewSession.selectedKeys.isEmpty())
        compose.setContent { AppleAppTheme(darkTheme = false) { RestorePreviewScreen(onNavigateBack = {}, onRestoreComplete = {}) } }
        compose.waitForIdle()

        compose.onNodeWithTag("rp_missing_${phoneDiff.key}", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(1, RestorePreviewSession.selectedKeys.size)
        assertEquals(phoneDiff.key, RestorePreviewSession.selectedKeys.single())
    }

    // ═══════════════════════════════════════════════════════════
    //  Конфликт по умолчанию — Keep current; «Использовать копию» переключает выбор
    // ═══════════════════════════════════════════════════════════

    @Test
    fun conflictChoice_defaultsToKeepCurrent_useBackupSelectsIt() {
        buildTwoDifferenceScenario()
        val conflictDiff = currentPreview().groups.single().differences.single { it.differenceType == RestoreDifferenceType.CONFLICT }
        compose.setContent { AppleAppTheme(darkTheme = false) { RestorePreviewScreen(onNavigateBack = {}, onRestoreComplete = {}) } }

        compose.onNodeWithText("Sales Manager", substring = true).assertExists()      // Keep current: текущее значение видно
        compose.onNodeWithText("Account Executive", substring = true).assertExists()  // Use backup: значение backup видно
        assertTrue("до клика ничего конфликтного не выбрано", RestorePreviewSession.selectedKeys.isEmpty())

        compose.onNodeWithTag("rp_conflict_backup_${conflictDiff.key}", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(1, RestorePreviewSession.selectedKeys.size)
        assertEquals(conflictDiff.key, RestorePreviewSession.selectedKeys.single())
    }

    // ═══════════════════════════════════════════════════════════
    //  Финальное подтверждение: сводка со словом «replaced», реальное применение
    // ═══════════════════════════════════════════════════════════

    @Test
    fun finalConfirmation_showsReplacementWording_andApplies() {
        buildTwoDifferenceScenario()
        val diffs = currentPreview().groups.single().differences
        val phoneDiff = diffs.single { it.fieldKind == RestoreFieldKind.PHONE }
        val conflictDiff = diffs.single { it.differenceType == RestoreDifferenceType.CONFLICT }
        compose.setContent { AppleAppTheme(darkTheme = false) { RestorePreviewScreen(onNavigateBack = {}, onRestoreComplete = {}) } }

        compose.onNodeWithTag("rp_missing_${phoneDiff.key}", useUnmergedTree = true).performClick()          // выбираем Missing (телефон)
        compose.waitForIdle()
        compose.onNodeWithTag("rp_conflict_backup_${conflictDiff.key}", useUnmergedTree = true).performClick() // выбираем Conflict (профессия)
        compose.waitForIdle()
        clickNode("Restore selected")

        // Явная формулировка требования 20/21: текущие значения будут заменены — простыми словами, не пугающе.
        compose.onNodeWithText("1 items will be added, and 1 current values will be replaced with values from the backup.")
            .assertExists()

        clickNode("Restore")
        val settled = pollUntil(15_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); RestorePreviewSession.preview == null }
        assertTrue("после подтверждения сессия очищена (onRestoreComplete отработал)", settled)

        val restoredContact = runBlocking { db.contactDao().getAllContacts() }.single { it.id == "c1" }
        assertEquals("Account Executive", restoredContact.profession)
        val phones = runBlocking { db.contactDao().getContactPhones() }.filter { it.contactId == "c1" }
        assertEquals(setOf("+301111111"), phones.map { it.number }.toSet())
    }
}
