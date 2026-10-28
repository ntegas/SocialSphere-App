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
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleAppTheme
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
 * Жалоба владельца 2026-10-08: «не сохранив, я теряю данные; должно выскакивать сохранить или отменить».
 * Форма компании — тот же общий механизм (rememberFormDirty + rememberLeaveGuard), что у формы события:
 * нетронутая форма уходит молча; изменённая на «Отмена»/системный «Назад» спрашивает; «Продолжить ввод»
 * ничего не теряет; «Не сохранять» не пишет компанию; «Сохранить» сохраняет. Шапка с «Готово» закреплена
 * над прокруткой — главная кнопка всегда на экране.
 *
 * Отдельный класс (один Compose UI-класс на запуск Gradle — §60.11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class CompanyEditUnsavedGuardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        if (!settled) fail("loadInitialData() не завершился за таймаут")
        AppStateStore.companies.clear(); AppStateStore.contacts.clear(); AppStateStore.notes.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.companies.clear(); AppStateStore.contacts.clear(); AppStateStore.notes.clear()
        db.close()
    }

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { Thread.sleep(20); ok = check() }
        return ok
    }

    private var backCalls = 0

    private fun render() {
        backCalls = 0
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                CompanyEditScreen(companyId = null, onNavigateBack = { backCalls++ })
            }
        }
        compose.waitForIdle()
    }

    private fun typeName(text: String) {
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(text)
        compose.waitForIdle()
    }

    private fun clickText(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction())[0].performClick()
        compose.waitForIdle()
    }

    private fun clickTag(tag: String) {
        compose.onNode(hasTestTag(tag)).performClick()
        compose.waitForIdle()
    }

    private fun tagShown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun untouched_form_cancels_silently() {
        render()
        clickText(ctx.getString(R.string.common_cancel))
        assertEquals(1, backCalls)
        assertEquals(false, tagShown("unsaved_discard"))
    }

    @Test
    fun typed_form_cancel_asks_and_keep_editing_keeps_the_text() {
        render()
        typeName("Acme Labs")
        clickText(ctx.getString(R.string.common_cancel))
        assertEquals("изменённая форма не уходит молча", 0, backCalls)
        assertTrue(tagShown("unsaved_discard"))
        clickTag("unsaved_keep")
        assertEquals(0, backCalls)
        assertTrue(compose.onAllNodes(hasText("Acme Labs")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun system_back_on_a_changed_form_asks() {
        render()
        typeName("Acme Labs")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(0, backCalls)
        assertTrue(tagShown("unsaved_discard"))
    }

    @Test
    fun discard_writes_nothing() {
        render()
        typeName("Acme Labs")
        clickText(ctx.getString(R.string.common_cancel))
        clickTag("unsaved_discard")
        assertEquals(1, backCalls)
        assertEquals(0, AppStateStore.companies.size)
    }

    @Test
    fun save_from_the_prompt_creates_the_company() {
        render()
        typeName("Acme Labs")
        clickText(ctx.getString(R.string.common_cancel))
        clickTag("unsaved_save")
        val ok = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); backCalls > 0 }
        if (!ok) fail("сохранение из вопроса не завершилось за таймаут")
        assertEquals(listOf("Acme Labs"), AppStateStore.companies.map { it.name })
    }
}
