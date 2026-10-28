package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
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
import java.util.UUID

/**
 * РЕШЕНИЕ ВЛАДЕЛЬЦА 2026-10-08: «редактировать контакт» — ОДИН механизм, ОДИН код.
 *
 * Регрессия: карандаш в шапке карточки включал СВОЙ инлайн-режим правки по вкладкам (старый экран),
 * а «⋯ → Редактировать» вёл в новую форму ContactEditScreen — два редактора одних и тех же данных.
 * Теперь ОБА входа вызывают ОДИН и тот же onNavigateToEdit (в MainActivity — маршрут contact_edit/{id}
 * → ContactEditScreen, тот же composable, что у «Новый контакт»), а своего режима правки у карточки нет.
 *
 * Отдельный класс (один Compose UI-класс на запуск Gradle — §60.11); `@Config(qualifiers)` даёт окну
 * реальный размер (без него Scaffold/LazyColumn меряются в 0×0 и клики не долетают — §69).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class ContactEditEntryPointTest {

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
        val settled = pollUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("loadInitialData() не завершился за таймаут")
        AppStateStore.contacts.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.notes.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.notes.clear()
        db.close()
    }

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ok = check()
        }
        return ok
    }

    private fun seedContact(): String {
        val id = UUID.randomUUID().toString()
        val now = "2026-10-08T00:00"
        val c = Contact(
            id = id, firstName = "Entry", lastName = "Point", photoUri = null,
            phones = listOf(ContactPhone(UUID.randomUUID().toString(), id, "+30 690 000 0001", PhoneType.MOBILE, true)),
            relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = now, updatedAt = now
        )
        runBlocking { AppStateStore.addContact(c).await() }
        return id
    }

    private var editCalls = 0

    private fun renderDetail(contactId: String) {
        editCalls = 0
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                ContactDetailScreen(
                    contactId = contactId,
                    onNavigateBack = {},
                    onNavigateToCalendarItem = {},
                    onNavigateToEdit = { editCalls++ },
                    onNavigateToCreateCalendarItem = {}
                )
            }
        }
        compose.waitForIdle()
    }

    private fun count(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `header pencil opens the unified editor - the same callback as the actions menu - not an inline mode`() {
        val id = seedContact()
        renderDetail(id)
        val editDesc = ctx.getString(R.string.common_edit)
        val doneDesc = ctx.getString(R.string.common_done)

        assertEquals("в шапке ровно одна кнопка «Редактировать»", 1, count(hasContentDescription(editDesc)))
        assertEquals(0, count(hasContentDescription(doneDesc)))

        compose.onAllNodes(hasContentDescription(editDesc) and hasClickAction())[0].performClick()
        compose.waitForIdle()

        assertEquals("карандаш обязан вызвать единый вход в ContactEditScreen", 1, editCalls)
        // Прежний баг: после тапа карандаш превращался в «✓ Готово» (включался инлайн-режим карточки).
        assertEquals("инлайн-режима правки больше нет — кнопка не превращается в «Готово»", 0, count(hasContentDescription(doneDesc)))
        assertEquals("кнопка осталась «Редактировать»", 1, count(hasContentDescription(editDesc)))
    }

    // «⋯ → Редактировать» в этом классе НЕ проверяется: шторка действий — ModalBottomSheet, его содержимое
    // живёт в отдельном окне и недоступно Robolectric (известное ограничение, §69). Эта строка вызывает ТОТ ЖЕ
    // onNavigateToEdit (ContactDetailScreen.kt, ActionSheetRow(Edit)) — проверено на эмуляторе (UI-прогон задачи).

    @Test
    fun `contact tab has no inline edit or remove controls after pressing the pencil`() {
        val id = seedContact()
        renderDetail(id)
        // Вкладка «Связь» (3-я): раньше после тапа по карандашу у каждой строки появлялась «−» (удалить)
        compose.onAllNodes(hasText(ctx.getString(R.string.cd_tab_comm)) and hasClickAction())[0].performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription(ctx.getString(R.string.common_edit)) and hasClickAction())[0].performClick()
        compose.waitForIdle()
        assertEquals(1, editCalls)
        assertEquals(
            "строки телефона/email/адреса не должны получать кнопки удаления — правка только в форме",
            0, count(hasContentDescription(ctx.getString(R.string.common_delete)))
        )
        assertTrue("номер по-прежнему показан", count(hasText("+30 690 000 0001")) > 0)
    }
}
