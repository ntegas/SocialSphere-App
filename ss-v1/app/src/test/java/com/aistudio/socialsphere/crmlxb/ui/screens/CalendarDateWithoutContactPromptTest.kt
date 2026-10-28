package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleAppTheme
import com.aistudio.socialsphere.crmlxb.utils.label
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
 * Владелец 2026-10-10: «день рождения/важная дата есть в календаре — она должна быть и на контакте».
 * Без связи с контактом дата видна только в Календаре. Поэтому форма события про человека (день рождения, годовщина,
 * именины, важная дата) без контакта и компании перед сохранением СПРАШИВАЕТ: выбрать контакт / сохранить без него.
 * Тап мимо и «Назад» ничего не сохраняют; остальные типы (встреча, звонок…) не спрашивают.
 *
 * Отдельный класс (один Compose UI-класс на запуск Gradle — §60.11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class CalendarDateWithoutContactPromptTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context
    private var backCalls = 0

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        if (!settled) fail("loadInitialData() не завершился за таймаут")
        AppStateStore.contacts.clear(); AppStateStore.calendarItems.clear()
        backCalls = 0
        compose.setContent { AppleAppTheme(darkTheme = false) { CalendarItemEditScreen(calendarItemId = null, onNavigateBack = { backCalls++ }) } }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear(); AppStateStore.calendarItems.clear()
        db.close()
    }

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) { Thread.sleep(20); ok = check() }
        return ok
    }

    private fun clickText(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction())[0].performClick()
        compose.waitForIdle()
    }

    private fun clickTag(tag: String) {
        compose.onNode(hasTestTag(tag)).performClick()
        compose.waitForIdle()
    }

    private fun promptShown() = compose.onAllNodes(hasTestTag("date_save_anyway")).fetchSemanticsNodes().isNotEmpty()

    private fun pressDone() = clickText(ctx.getString(R.string.common_done))

    private fun saved(type: CalendarItemType) = AppStateStore.calendarItems.filter { it.type == type }

    @Test
    fun birthdayWithoutContact_asksBeforeSaving_andSavesNothingYet() {
        clickText(CalendarItemType.BIRTHDAY.label(ctx))
        pressDone()
        assertTrue("вопрос «Дата без контакта» показан", promptShown())
        assertEquals("до ответа ничего не сохранено", 0, saved(CalendarItemType.BIRTHDAY).size)
        assertEquals(0, backCalls)
    }

    @Test
    fun saveWithoutContact_savesTheBirthdayUnlinked_andLeaves() {
        clickText(CalendarItemType.BIRTHDAY.label(ctx))
        pressDone()
        clickTag("date_save_anyway")
        val ok = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); backCalls > 0 }
        if (!ok) fail("сохранение без контакта не завершилось за таймаут")
        val b = saved(CalendarItemType.BIRTHDAY).single()
        assertTrue("сохранено именно без связи — как выбрал пользователь", b.links.isEmpty())
    }

    @Test
    fun pickContact_closesThePrompt_andStillSavesNothing() {
        clickText(CalendarItemType.BIRTHDAY.label(ctx))
        pressDone()
        clickTag("date_pick_contact")
        assertTrue("вопрос закрыт", !promptShown())
        assertEquals("форма осталась открытой, ничего не сохранено", 0, backCalls)
        assertEquals(0, saved(CalendarItemType.BIRTHDAY).size)
    }

    @Test
    fun systemBackOnThePrompt_savesNothing_andKeepsTheForm() {
        clickText(CalendarItemType.BIRTHDAY.label(ctx))
        pressDone()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(0, saved(CalendarItemType.BIRTHDAY).size)
        assertEquals(0, backCalls)
    }

    @Test
    fun meetingWithoutContact_savesRightAway_noQuestion() {
        // форма по умолчанию — «Встреча»: не «чья-то дата», вопроса нет
        pressDone()
        assertTrue("вопроса нет", !promptShown())
        val ok = pollUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); backCalls > 0 }
        if (!ok) fail("сохранение встречи не завершилось за таймаут")
        assertEquals(1, saved(CalendarItemType.MEETING).size)
    }
}
