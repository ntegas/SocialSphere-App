package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import java.util.UUID

/**
 * V2.2 CONTACT CREATE/EDIT.
 *
 * Часть A — реальный UI (Robolectric Compose): печатаем имя в настоящем новом
 * экране ContactEditScreen и жмём настоящую кнопку «Готово», подтверждая, что
 * полная перестройка формы (капча-кнопки/шапка/секции/sticky-nav) не сломала
 * базовый путь создания контакта.
 *
 * Часть B — чистая логика записи (без UI): ModalBottomSheet-пикеры (Теги,
 * Важные даты) в этом проекте уже признаны ненадёжными для Robolectric-
 * interaction-тестов — см. ExternalActionSemanticsTest.kt, комментарий у
 * `k_newAnniversaryAndNameday...` («production-путь — Compose ModalBottomSheet,
 * отдельная героика для interaction-теста здесь избыточна»). Поэтому ИНВАРИАНТ,
 * который действительно важно проверить — что buildAndSave() добавляет тег/
 * важную дату/заметку к РЕАЛЬНОМУ id НОВОГО контакта без единой записи-сироты —
 * проверяется зеркалированием ТОЧНО той же последовательности вызовов
 * AppStateStore, что buildAndSave() выполняет, а не кликами по шторке.
 */
@RunWith(RobolectricTestRunner::class)
class ContactEditScreenV22Test {

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
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.notes.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
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

    private fun clickNode(label: String, byContentDescription: Boolean = false) {
        // Форма — длинная verticalScroll(); узел вне текущего viewport'а нужно подвести performScrollTo().
        // Шапка («Готово») с V2.2 вынесена ИЗ скролла (фиксированная над вкладками) — для неё
        // прокручивать нечего, performScrollTo() там бросает исключение, поэтому оно в runCatching.
        val base: SemanticsMatcher = if (byContentDescription) hasContentDescription(label) else hasText(label)
        val clickable = compose.onAllNodes(base and hasClickAction())
        if (clickable.fetchSemanticsNodes().isNotEmpty()) {
            runCatching { clickable[0].performScrollTo() }
            compose.waitForIdle()
            clickable[0].performClick()
        } else {
            val any = compose.onAllNodes(base, useUnmergedTree = true)
            if (any.fetchSemanticsNodes().isEmpty()) fail("на экране нет элемента «$label»")
            runCatching { any[0].performScrollTo() }
            compose.waitForIdle()
            any[0].performClick()
        }
        compose.waitForIdle()
    }

    private fun clickStayInTouchSwitch() {
        // «Поддерживать связь» — Switch (toggleable) в карточке «Связь»; единственный переключатель экрана.
        val sw = compose.onAllNodes(isToggleable())
        if (sw.fetchSemanticsNodes().isEmpty()) fail("на экране нет переключателя «Поддерживать связь»")
        runCatching { sw[0].performScrollTo() }
        compose.waitForIdle()
        sw[0].performClick()
        compose.waitForIdle()
    }

    // ─── A. Реальный UI: базовое создание контакта через перестроенную форму ───

    @Test
    fun `new contact screen saves a contact via Done button`() {
        var createdId: String? = null
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                ContactEditScreen(
                    contactId = null,
                    onNavigateBack = {},
                    onCreated = { id -> createdId = id }
                )
            }
        }
        compose.waitForIdle()

        // «Имя» — первое редактируемое текстовое поле экрана (BareFieldColumn,
        // над кнопками Скан/Контакты/Голос и фото текстовых полей нет).
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Draftie")
        compose.waitForIdle()

        clickNode(ctx.getString(R.string.common_done))

        val settled = pollUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            createdId != null
        }
        if (!settled) fail("сохранение нового контакта не завершилось за таймаут")

        val saved = AppStateStore.getContact(createdId!!)
        assertTrue("новый контакт должен появиться в памяти", saved != null)
        assertEquals("Draftie", saved!!.firstName)
    }

    // ─── B. Инвариант «без сирот» для Тегов/Важных дат/Заметок на НОВОМ контакте ───

    @Test
    fun `tag, important date and note writes on a brand-new contact leave no orphans`() {
        val vip = AppStateStore.addTag("VIP")
        assertTrue("сид-тег должен создаться", vip != null)

        val newId = UUID.randomUUID().toString()
        val now = "2026-10-01T00:00"
        val newContact = Contact(
            id = newId, firstName = "Draftie", lastName = "", photoUri = null,
            relationshipType = RelationshipType.ACQUAINTANCE, connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.NOT_TRACKED,
            createdAt = now, updatedAt = now
        )

        // Зеркалируем ТОЧНО ту же последовательность, что buildAndSave() выполняет
        // для нового контакта: addContact() СИНХРОННО кладёт контакт в
        // AppStateStore.contacts (Deferred — только про запись в Room), поэтому
        // последующие вызовы setContactTags/addCalendarItem/addNote с newId уже
        // ссылаются на РЕАЛЬНО существующий (в памяти) контакт — не на сироту.
        val writes = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        writes += AppStateStore.addContact(newContact)
        writes += AppStateStore.setContactTags(newId, setOf(vip!!.id))
        val calId = UUID.randomUUID().toString()
        writes += AppStateStore.addCalendarItem(
            CalendarItem(
                id = calId, title = "Важная дата", type = CalendarItemType.BIRTHDAY,
                startDate = "2026-11-20", isAllDay = true,
                status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
                links = listOf(CalendarItemLink(UUID.randomUUID().toString(), calId, CalendarTargetType.CONTACT, newId)),
                createdAt = now, updatedAt = now
            )
        )
        writes += AppStateStore.addNote(
            Note(
                id = UUID.randomUUID().toString(), contactId = newId,
                type = NoteType.GENERAL, text = "Черновик заметки", isImportant = false,
                createdAt = now, updatedAt = now
            )
        )
        writes += AppStateStore.writeBarrier()

        val committed = runBlocking { AppStateStore.awaitDurableCommit(*writes.toTypedArray()) }
        assertTrue("запись должна подтвердиться", committed)

        val saved = AppStateStore.getContact(newId)
        assertTrue("новый контакт должен существовать", saved != null)

        val tagsOfNew = AppStateStore.tagsOfContact(newId)
        assertTrue("тег VIP должен быть привязан к новому контакту", tagsOfNew.any { it.name == "VIP" })
        assertTrue(
            "не должно быть ContactTagMember на contactId, которого нет в AppStateStore.contacts",
            AppStateStore.tagMembers.all { m -> AppStateStore.contacts.any { it.id == m.contactId } }
        )

        val linkedDates = AppStateStore.calendarItems.filter { ci ->
            ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId == newId }
        }
        assertEquals(1, linkedDates.size)
        assertEquals("2026-11-20", linkedDates[0].startDate)

        val note = AppStateStore.notes.find { it.contactId == newId }
        assertTrue("заметка должна быть создана и привязана к новому контакту", note != null)
        assertTrue(
            "заметка должна быть видна в contact.notes сразу, без перезапуска",
            saved!!.notes.any { it.id == note!!.id }
        )

        // Та же проверка на Room — не только на in-memory снимок.
        val dbNoteContactIds = runBlocking { db.noteDao().getAllNotes() }.map { it.contactId }
        assertTrue("заметка в Room должна ссылаться на реальный contactId", newId in dbNoteContactIds)
    }

    // ─── C. Stay in touch — NOT_TRACKED / NO_SCHEDULE / MONTHLY (2026-10-02, V2.2 fluid form) ───
    // С V2.2 (прототип «Fluid Form») восемь пилюль ритма заменены переключателем «Поддерживать связь»:
    // OFF = NOT_TRACKED, ON = NO_SCHEDULE (или последняя выбранная периодичность). Выбор периодичности —
    // в ModalBottomSheet (Robolectric его содержимое не кликает, см. комментарий выше), поэтому здесь
    // реальным кликом проверяется ПЕРЕКЛЮЧАТЕЛЬ и сохранение; значение MONTHLY и т.п. приходит из seed.

    private fun seedContact(
        rhythm: CommunicationRhythm,
        nextStep: String? = "Call them"
    ): String {
        val id = UUID.randomUUID().toString()
        val now = "2026-10-02T00:00"
        val contact = Contact(
            id = id, firstName = "RhythmGuy", lastName = "", photoUri = null,
            relationshipType = RelationshipType.ACQUAINTANCE, connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = rhythm, nextStep = nextStep,
            createdAt = now, updatedAt = now
        )
        runBlocking { AppStateStore.addContact(contact).await() }
        return id
    }

    private fun renderEdit(contactId: String) {
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                ContactEditScreen(contactId = contactId, onNavigateBack = {})
            }
        }
        compose.waitForIdle()
    }

    private fun saveAndAwait(contactId: String, expect: CommunicationRhythm) {
        clickNode(ctx.getString(R.string.common_done))
        val settled = pollUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            AppStateStore.getContact(contactId)?.communicationRhythm == expect
        }
        if (!settled) fail("сохранение контакта (rhythm=$expect) не завершилось за таймаут")
    }

    @Test
    fun `AC1 AC8 switch OFF stores NOT_TRACKED after save and reload, next step untouched`() {
        val id = seedContact(rhythm = CommunicationRhythm.MONTHLY)
        renderEdit(id)
        clickStayInTouchSwitch()
        saveAndAwait(id, CommunicationRhythm.NOT_TRACKED)

        val saved = AppStateStore.getContact(id)!!
        assertEquals(CommunicationRhythm.NOT_TRACKED, saved.communicationRhythm)
        assertEquals("Call them", saved.nextStep) // AC9: Next Step independent
    }

    @Test
    fun `AC2 AC4 switch ON stores NO_SCHEDULE, distinct from NOT_TRACKED`() {
        val id = seedContact(rhythm = CommunicationRhythm.NOT_TRACKED)
        renderEdit(id)
        clickStayInTouchSwitch()
        saveAndAwait(id, CommunicationRhythm.NO_SCHEDULE)

        val saved = AppStateStore.getContact(id)!!
        assertEquals(CommunicationRhythm.NO_SCHEDULE, saved.communicationRhythm)
        assertTrue("NO_SCHEDULE должен отличаться от NOT_TRACKED", saved.communicationRhythm != CommunicationRhythm.NOT_TRACKED)
        assertEquals("Call them", saved.nextStep) // AC9
    }

    @Test
    fun `AC3 AC7 MONTHLY persists unchanged when the form is saved without touching it`() {
        val id = seedContact(rhythm = CommunicationRhythm.MONTHLY)
        renderEdit(id)
        saveAndAwait(id, CommunicationRhythm.MONTHLY)

        val saved = AppStateStore.getContact(id)!!
        assertEquals(CommunicationRhythm.MONTHLY, saved.communicationRhythm)
        assertEquals("Call them", saved.nextStep) // AC9
    }

    @Test
    fun `switch OFF then ON restores the previous periodicity instead of dropping to NO_SCHEDULE`() {
        val id = seedContact(rhythm = CommunicationRhythm.MONTHLY)
        renderEdit(id)
        clickStayInTouchSwitch()   // OFF
        clickStayInTouchSwitch()   // ON again
        saveAndAwait(id, CommunicationRhythm.MONTHLY)
        assertEquals(CommunicationRhythm.MONTHLY, AppStateStore.getContact(id)!!.communicationRhythm)
    }

    // ─── D. Свёрнутые строки-сводки НЕ стирают данные (V2.2: «Ещё» свёрнуто в строки) ───

    @Test
    fun `collapsed summary rows keep their data on save - nothing is wiped just because the row is folded`() {
        val id = UUID.randomUUID().toString()
        val now = "2026-10-03T00:00"
        runBlocking {
            AppStateStore.addContact(
                Contact(
                    id = id, firstName = "Folded", lastName = "", photoUri = null,
                    relationshipType = RelationshipType.FRIEND,
                    secondaryRelationshipTypes = listOf(RelationshipType.CLIENT),
                    connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL,
                    socialRole = SocialRole.REGULAR, communicationRhythm = CommunicationRhythm.WEEKLY,
                    nextStep = "Call them", canHelpWith = "Intro to a lawyer", iCanHelpWith = "Design review",
                    talkingPoints = "Kids; travel", meetContext = "TechSummit 2024", meetDate = "2024-11-15",
                    createdAt = now, updatedAt = now
                )
            ).await()
        }
        renderEdit(id)
        // Ни одна строка-сводка не раскрывалась: в UI их поля скрыты. Сохраняем.
        saveAndAwait(id, CommunicationRhythm.WEEKLY)

        val saved = AppStateStore.getContact(id)!!
        assertEquals("Call them", saved.nextStep)
        assertEquals("Intro to a lawyer", saved.canHelpWith)
        assertEquals("Design review", saved.iCanHelpWith)
        assertEquals("Kids; travel", saved.talkingPoints)
        assertEquals("TechSummit 2024", saved.meetContext)
        assertEquals("2024-11-15", saved.meetDate)
        assertEquals(RelationshipType.FRIEND, saved.relationshipType)
        assertEquals(listOf(RelationshipType.CLIENT), saved.secondaryRelationshipTypes)
    }

    // ─── E. Черновик выбора отношений (RelDraft): та же логика, что была у пилюль ───

    @Test
    fun `RelDraft toggling a new type makes it primary and demotes the old one, never drops it`() {
        var d = RelDraft(RelationshipType.ACQUAINTANCE, "", emptyList())
        d = d.toggled(RelationshipType.FRIEND)
        assertEquals(RelationshipType.FRIEND, d.primary)
        assertEquals(listOf(RelationshipType.ACQUAINTANCE), d.secondary)
        d = d.toggled(RelationshipType.CLIENT)
        assertEquals(RelationshipType.CLIENT, d.primary)
        assertEquals(listOf(RelationshipType.ACQUAINTANCE, RelationshipType.FRIEND), d.secondary)
    }

    @Test
    fun `RelDraft cannot untick the only type, but unticking the primary promotes the next one`() {
        val single = RelDraft(RelationshipType.FRIEND, "", emptyList())
        assertEquals("единственный тип снять нельзя", single, single.toggled(RelationshipType.FRIEND))

        val two = RelDraft(RelationshipType.FRIEND, "", listOf(RelationshipType.CLIENT))
        val afterPrimaryOff = two.toggled(RelationshipType.FRIEND)
        assertEquals(RelationshipType.CLIENT, afterPrimaryOff.primary)
        assertTrue(afterPrimaryOff.secondary.isEmpty())

        val afterSecondaryOff = two.toggled(RelationshipType.CLIENT)
        assertEquals(RelationshipType.FRIEND, afterSecondaryOff.primary)
        assertTrue(afterSecondaryOff.secondary.isEmpty())
    }

    @Test
    fun `RelDraft custom type reuses the existing spelling case-insensitively and OTHER leaves secondary`() {
        val d = RelDraft(RelationshipType.FRIEND, "", listOf(RelationshipType.OTHER, RelationshipType.CLIENT))
        val withCustom = d.withCustom("кум", listOf("Кум", "Тренер"))
        assertEquals("Кум", withCustom.custom)           // не плодим «Кум»/«кум»
        assertEquals(RelationshipType.OTHER, withCustom.primary)
        assertEquals(listOf(RelationshipType.CLIENT), withCustom.secondary) // OTHER не дублируется во второстепенных
    }

    @Test
    fun `RelDraft custom can be unticked only when another type exists`() {
        val onlyCustom = RelDraft(RelationshipType.OTHER, "Кум", emptyList())
        assertEquals(onlyCustom, onlyCustom.customToggled())
        val withOther = RelDraft(RelationshipType.OTHER, "Кум", listOf(RelationshipType.FRIEND))
        val cleared = withOther.customToggled()
        assertEquals("", cleared.custom)
        assertEquals(RelationshipType.FRIEND, cleared.primary)
    }

    @Test
    fun `RelDraft ticking a standard type keeps the custom primary instead of silently dropping it`() {
        val d = RelDraft(RelationshipType.OTHER, "Кум", emptyList())
        val after = d.toggled(RelationshipType.NEIGHBOR)
        assertEquals("Кум", after.custom)
        assertEquals(RelationshipType.OTHER, after.primary)
        assertEquals(listOf(RelationshipType.NEIGHBOR), after.secondary)
        // и снимается обратно, не трогая свой тип
        val back = after.toggled(RelationshipType.NEIGHBOR)
        assertEquals("Кум", back.custom)
        assertTrue(back.secondary.isEmpty())
    }

    // ─── F. «Связь» НОВОГО контакта стартует пустой (прототип V2.2: «Связь — Добавить ›») ───
    // Регрессия (2026-10-05): production предвыбирал «Знакомый» и молча сохранял его. Теперь у НОВОГО контакта
    // ничего не выбрано и при «Готово» ложная связь не записывается; у существующего — хранимое не трогается.

    private fun relLabel(res: Int) = ctx.getString(res)

    private fun renderNewAndSave(name: String): String {
        var createdId: String? = null
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                ContactEditScreen(contactId = null, onNavigateBack = {}, onCreated = { id -> createdId = id })
            }
        }
        compose.waitForIdle()
        // До ввода имени: на экране НЕТ ни одной подписи «Знакомый» (прежний предвыбор) — связь пуста.
        assertEquals(
            "у нового контакта «Знакомый» не должен быть предвыбран",
            0,
            compose.onAllNodes(hasText(relLabel(R.string.lbl_relationship_type_acquaintance)), useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(name)
        compose.waitForIdle()
        clickNode(ctx.getString(R.string.common_done))
        val settled = pollUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            createdId != null
        }
        if (!settled) fail("сохранение нового контакта не завершилось за таймаут")
        return createdId!!
    }

    @Test
    fun `new contact starts with no relationship and Done does not invent Acquaintance`() {
        val id = renderNewAndSave("NoRelation")
        val saved = AppStateStore.getContact(id)!!
        assertTrue("«Знакомый» не должен молча добавиться при сохранении", saved.relationshipType != RelationshipType.ACQUAINTANCE)
        // Contact.relationshipType не-null: «не выбрано» хранится ТЕМ ЖЕ маркером «не указано», что пишет массовый импорт.
        assertEquals(storedRelationshipType(null), saved.relationshipType)
        assertEquals(RelationshipType.OTHER, saved.relationshipType)
        assertEquals(null, saved.customRelationshipType)
        assertTrue("второстепенных типов быть не должно", saved.secondaryRelationshipTypes.isEmpty())
    }

    @Test
    fun `new contact prefilled from scanner or phone draft also starts without relationship`() {
        // ImportContactCandidate вообще не несёт relationshipType, значит draft-путь (Скан/Контакты) не может его
        // «явно задать» — форма обязана остаться пустой, а не подставить «Знакомый».
        ContactDraftSession.pending = com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate(firstName = "Scanned", lastName = "Card")
        try {
            val id = renderNewAndSave("Extra")
            val saved = AppStateStore.getContact(id)!!
            assertTrue(saved.relationshipType != RelationshipType.ACQUAINTANCE)
            assertEquals(RelationshipType.OTHER, saved.relationshipType)
        } finally {
            ContactDraftSession.clear()
        }
    }

    private fun seedWithRelations(
        main: RelationshipType, custom: String?, secondary: List<RelationshipType>
    ): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val seedStamp = "2026-10-02T00:00"
        val contact = Contact(
            id = id, firstName = "Keeper", lastName = "", photoUri = null,
            relationshipType = main, customRelationshipType = custom, secondaryRelationshipTypes = secondary,
            connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.WEEKLY, createdAt = seedStamp, updatedAt = seedStamp
        )
        runBlocking { AppStateStore.addContact(contact).await() }
        return id to seedStamp
    }

    private fun saveEditedAndAwait(id: String, seedStamp: String) {
        clickNode(ctx.getString(R.string.common_done))
        val settled = pollUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            AppStateStore.getContact(id)?.updatedAt != seedStamp
        }
        if (!settled) fail("сохранение существующего контакта не завершилось за таймаут")
    }

    @Test
    fun `existing contact loads its stored relationships and keeps them after save`() {
        val (id, stamp) = seedWithRelations(RelationshipType.FRIEND, null, listOf(RelationshipType.CLIENT, RelationshipType.COLLEAGUE))
        renderEdit(id)
        val expected = listOf(R.string.lbl_relationship_type_friend, R.string.lbl_relationship_type_client, R.string.lbl_relationship_type_colleague)
            .joinToString(" · ") { relLabel(it) }
        assertEquals(
            "форма редактирования должна показать сохранённые связи, а не пустоту/«Знакомый»",
            1, compose.onAllNodes(hasText(expected), useUnmergedTree = true).fetchSemanticsNodes().size
        )
        saveEditedAndAwait(id, stamp)
        val saved = AppStateStore.getContact(id)!!
        assertEquals(RelationshipType.FRIEND, saved.relationshipType)
        assertEquals(listOf(RelationshipType.CLIENT, RelationshipType.COLLEAGUE), saved.secondaryRelationshipTypes)
        assertEquals(null, saved.customRelationshipType)
    }

    @Test
    fun `existing contact with custom relationship is not cleared by edit-save`() {
        val (customId, customStamp) = seedWithRelations(RelationshipType.OTHER, "Кум", listOf(RelationshipType.FRIEND))
        renderEdit(customId)
        saveEditedAndAwait(customId, customStamp)
        val c = AppStateStore.getContact(customId)!!
        assertEquals(RelationshipType.OTHER, c.relationshipType)
        assertEquals("Кум", c.customRelationshipType)
        assertEquals(listOf(RelationshipType.FRIEND), c.secondaryRelationshipTypes)
    }

    @Test
    fun `existing OTHER contact is shown as Other not as empty and stays OTHER after save`() {
        val (id, stamp) = seedWithRelations(RelationshipType.OTHER, null, emptyList())
        renderEdit(id)
        assertEquals(
            "хранимое «Другое» (напр. из импорта) — это значение: форма редактирования показывает его, а не «Добавить»",
            1, compose.onAllNodes(hasText(relLabel(R.string.lbl_relationship_type_other)), useUnmergedTree = true).fetchSemanticsNodes().size
        )
        saveEditedAndAwait(id, stamp)
        assertEquals(RelationshipType.OTHER, AppStateStore.getContact(id)!!.relationshipType)
    }

    // ─── G. «Заметка о семье»: правка переехала из инлайн-режима карточки в единую форму (2026-10-08) ───

    private fun seedWithFamilyNote(note: String?): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val stamp = "2026-10-02T00:00"
        val contact = Contact(
            id = id, firstName = "Family", lastName = "", photoUri = null,
            relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.WEEKLY, familyNote = note,
            createdAt = stamp, updatedAt = stamp
        )
        runBlocking { AppStateStore.addContact(contact).await() }
        return id to stamp
    }

    @Test
    fun `family note survives a save when the family row stays collapsed`() {
        val (id, stamp) = seedWithFamilyNote("сын Петя, телефона нет")
        renderEdit(id)
        saveEditedAndAwait(id, stamp)
        assertEquals("сын Петя, телефона нет", AppStateStore.getContact(id)!!.familyNote)
    }

    @Test
    fun `family note is edited in the unified form and saved`() {
        val (id, stamp) = seedWithFamilyNote("сын Петя")
        renderEdit(id)
        clickNode(ctx.getString(R.string.ce_family_relations))   // раскрыть строку «Семья и связи»
        val field = compose.onAllNodes(hasSetTextAction() and hasText("сын Петя"), useUnmergedTree = true)
        if (field.fetchSemanticsNodes().isEmpty()) fail("в раскрытой строке «Семья и связи» нет поля заметки о семье")
        field[0].performTextReplacement("сын Петя, дочь Аня")
        compose.waitForIdle()
        saveEditedAndAwait(id, stamp)
        assertEquals("сын Петя, дочь Аня", AppStateStore.getContact(id)!!.familyNote)
    }

    @Test
    fun `clearing the family note in the form clears it, a blank note is stored as null`() {
        val (id, stamp) = seedWithFamilyNote("сын Петя")
        renderEdit(id)
        clickNode(ctx.getString(R.string.ce_family_relations))
        compose.onAllNodes(hasSetTextAction() and hasText("сын Петя"), useUnmergedTree = true)[0].performTextReplacement("   ")
        compose.waitForIdle()
        saveEditedAndAwait(id, stamp)
        assertEquals(null, AppStateStore.getContact(id)!!.familyNote)
    }

    @Test
    fun `RelDraft from the empty state - first tick becomes primary without a phantom demotion`() {
        var d = RelDraft(null, "", emptyList())
        d = d.toggled(RelationshipType.FRIEND)
        assertEquals(RelationshipType.FRIEND, d.primary)
        assertTrue("из пустого состояния никто не «понижается» во второстепенные", d.secondary.isEmpty())
        assertEquals("выбранный единственный тип снять нельзя (есть «Отмена»)", d, d.toggled(RelationshipType.FRIEND))
        d = d.toggled(RelationshipType.CLIENT)
        assertEquals(RelationshipType.CLIENT, d.primary)
        assertEquals(listOf(RelationshipType.FRIEND), d.secondary)
    }

    @Test
    fun `RelDraft from the empty state accepts a custom type and storedRelationshipType never invents Acquaintance`() {
        val d = RelDraft(null, "", emptyList()).withCustom("Кум", emptyList())
        assertEquals(RelationshipType.OTHER, d.primary)
        assertEquals("Кум", d.custom)
        assertEquals(RelationshipType.OTHER, storedRelationshipType(null))
        assertEquals(RelationshipType.FRIEND, storedRelationshipType(RelationshipType.FRIEND))
        assertEquals(RelationshipType.ACQUAINTANCE, storedRelationshipType(RelationshipType.ACQUAINTANCE)) // явный выбор уважается
    }
}
