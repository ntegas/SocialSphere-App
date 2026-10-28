package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleAppTheme
import com.aistudio.socialsphere.crmlxb.utils.FakeVoicePlatform
import com.aistudio.socialsphere.crmlxb.utils.VoiceLocale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * VOICE PHASE 1 — подключение кнопки «Голос» в реальной форме создания контакта (Robolectric Compose).
 * Подменяется только платформа распознавания ([LocalVoicePlatform]); форма и шторка настоящие.
 * Содержимое ModalBottomSheet Robolectric'ом не читаем (§76), поэтому проверяем наблюдаемые факты:
 * движок создан/запущен/уничтожен, а Contact и поля формы не тронуты.
 */
@RunWith(RobolectricTestRunner::class)
class ContactEditVoiceEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val deadline = System.currentTimeMillis() + 10_000
        while (AppStateStore.isLoading && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        if (AppStateStore.isLoading) fail("loadInitialData() не завершился за таймаут")
        AppStateStore.contacts.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.notes.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear()
        db.close()
    }

    private fun clickVoiceButton() {
        val label = ctx.getString(R.string.ce_capture_voice)
        val node = compose.onAllNodes(hasText(label) and hasClickAction())[0]
        node.performScrollTo()
        compose.waitForIdle()
        node.performClick()
        compose.waitForIdle()
    }

    private fun firstNameFieldText(): String =
        compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    // AC1 + AC4 + AC5 + AC6 + AC9 на уровне реальной формы.
    @Test
    fun voiceButton_listensOnDevice_andNeverTouchesTheContactOrItsFields() {
        val platform = FakeVoicePlatform()
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                CompositionLocalProvider(LocalVoicePlatform provides platform) {
                    ContactEditScreen(contactId = null, onNavigateBack = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals("", firstNameFieldText())

        clickVoiceButton()

        // AC1: кнопка запустила поток доступность → разрешение → on-device слушание на локали приложения.
        assertEquals(1, platform.createCalls)
        assertEquals(
            VoiceLocale.fromLanguageCode(AppSettings.currentLanguage.value.code).tag,
            platform.last.startedTag
        )
        assertEquals(1, platform.liveEngines)

        // AC4/AC5: частичный и финальный результат приходят в шторку…
        platform.last.emitPartial("alpha beta")
        compose.waitForIdle()
        platform.last.emitFinal("alpha beta gamma")
        compose.waitForIdle()

        // …AC6/AC9: форма и данные не тронуты, ничего не сохранено, микрофон освобождён.
        assertEquals("", firstNameFieldText())
        assertTrue("Voice не должен создавать контакты", AppStateStore.contacts.isEmpty())
        assertTrue(AppStateStore.notes.isEmpty())
        assertTrue(AppStateStore.calendarItems.isEmpty())
        assertEquals(0, platform.liveEngines)
    }

    // AC8: уход с экрана при активном слушании уничтожает распознаватель.
    @Test
    fun leavingTheScreen_whileListening_destroysTheRecognizer() {
        val platform = FakeVoicePlatform()
        val visible = mutableStateOf(true)
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                CompositionLocalProvider(LocalVoicePlatform provides platform) {
                    if (visible.value) ContactEditScreen(contactId = null, onNavigateBack = {})
                }
            }
        }
        compose.waitForIdle()
        clickVoiceButton()
        assertEquals(1, platform.liveEngines)
        assertFalse(platform.last.destroyed)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()

        assertTrue(platform.last.destroyed)
        assertEquals(0, platform.liveEngines)
        assertEquals("после ухода экрана колбэки не должны ничего менять", 1, platform.createCalls)
    }

    // AC3 на уровне формы: недоступный on-device — форма остаётся рабочей, движок не создаётся.
    @Test
    fun unavailableOnDevice_keepsTheFormUsable_andCreatesNoEngine() {
        val platform = FakeVoicePlatform(
            sdk = 30,
            availability = com.aistudio.socialsphere.crmlxb.utils.VoiceAvailability.Unavailable(
                com.aistudio.socialsphere.crmlxb.utils.VoiceUnavailableReason.API_TOO_LOW
            )
        )
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                CompositionLocalProvider(LocalVoicePlatform provides platform) {
                    ContactEditScreen(contactId = null, onNavigateBack = {})
                }
            }
        }
        compose.waitForIdle()
        clickVoiceButton()

        assertEquals(0, platform.createCalls)
        assertEquals("", firstNameFieldText())
        assertTrue(AppStateStore.contacts.isEmpty())
    }
}
