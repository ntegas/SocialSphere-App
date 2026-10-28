package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleAppTheme
import com.aistudio.socialsphere.crmlxb.utils.CalendarCompletion
import com.aistudio.socialsphere.crmlxb.utils.CalendarPostpone
import com.aistudio.socialsphere.crmlxb.utils.NotificationHelper
import com.aistudio.socialsphere.crmlxb.utils.NotificationReceiver
import com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
import com.aistudio.socialsphere.crmlxb.utils.RecurrenceCalculator
import com.aistudio.socialsphere.crmlxb.utils.effectiveDate
import com.aistudio.socialsphere.crmlxb.utils.effectiveRecurrenceMode
import com.aistudio.socialsphere.crmlxb.utils.hasValidPostponePair
import com.aistudio.socialsphere.crmlxb.utils.isValidPostponeTarget
import com.aistudio.socialsphere.crmlxb.utils.label
import com.aistudio.socialsphere.crmlxb.utils.labelKey
import com.aistudio.socialsphere.crmlxb.utils.parseFlexibleDate
import com.aistudio.socialsphere.crmlxb.utils.pendingOccurrence
import com.aistudio.socialsphere.crmlxb.utils.pendingOccurrenceDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * INTERACTION SEMANTICS — внешние действия Call / SMS / Email / Messenger.
 *
 * INVARIANT: открытие внешнего приложения НЕ означает состоявшийся контакт.
 * ACTION_DIAL лишь открывает набор номера, ACTION_SENDTO — черновик; ни то, ни
 * другое не доказывает, что человек позвонил или отправил сообщение. Поэтому
 * ни одна из этих кнопок не меняет lastContactDate. Меняют его только явные
 * действия со смыслом «контакт состоялся»: «Отметить: связались» на главной и
 * завершение события CALL / MEETING / MESSAGE.
 *
 * Тесты нажимают НАСТОЯЩИЕ кнопки продакшн-экранов (Compose UI под
 * Robolectric), а не вызывают вынесенный хелпер: иначе вызов markContactedNow,
 * оставленный прямо в onClick, остался бы незамеченным. Каждый «не меняет»-тест
 * дополнительно проверяет, что клик реально дошёл до обработчика (интент ушёл
 * либо показан отказ), — иначе «дата не изменилась» было бы пустой проверкой.
 * Позитивные контроли (главная, завершение события) доказывают, что сама
 * проверка даты способна увидеть изменение.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalActionSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context

    private val oldDate = "2025-01-01"
    private val contactId = "ext-c1"

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
        drainStartedActivities()
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.notes.clear()
        db.close()
    }

    // ─── обвязка ───────────────────────────────────────────────

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ok = check()
        }
        return ok
    }

    private fun app() = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())

    private fun drainStartedActivities() {
        while (app().nextStartedActivity != null) { /* очистить очередь */ }
    }

    private fun contact(
        phones: List<ContactPhone> = listOf(ContactPhone("p1", contactId, "+10000000001", PhoneType.MOBILE, true)),
        emails: List<ContactEmail> = listOf(ContactEmail("e1", contactId, "ivan@example.com", EmailType.PERSONAL, true)),
        messengers: List<Messenger> = emptyList(),
        rhythm: CommunicationRhythm = CommunicationRhythm.NOT_TRACKED
    ) = Contact(
        id = contactId, firstName = "Ivan", lastName = "Tester", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = rhythm,
        phones = phones, emails = emails, messengers = messengers,
        lastContactDate = oldDate,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    /** Контакт в памяти И в Room — проверка «не изменилось» идёт по обоим. */
    private fun persist(c: Contact) {
        runBlocking { AppStateStore.addContact(c).await() }
    }

    private fun lastContactInMemory(): String? = AppStateStore.getContact(contactId)?.lastContactDate

    private fun lastContactInDb(): String? {
        runBlocking { AppStateStore.awaitPendingWrites() }
        return runBlocking { db.contactDao().getAllContacts() }.firstOrNull { it.id == contactId }?.lastContactDate
    }

    private fun assertLastContactUnchanged(action: String) {
        compose.waitForIdle()
        assertEquals("$action: lastContactDate в памяти не должен меняться", oldDate, lastContactInMemory())
        assertEquals("$action: lastContactDate в Room не должен меняться", oldDate, lastContactInDb())
    }

    private fun nextIntentOrFail(action: String): Intent =
        app().nextStartedActivity ?: fail("$action: клик не дошёл до обработчика — внешний интент не стартовал") as Nothing

    /** Кликает первый узел с текстом (или contentDescription) label; если сам
     *  узел не кликабелен (иконка внутри кликабельного Box), кликает его. */
    private fun clickNode(label: String, byContentDescription: Boolean = false) {
        val base: SemanticsMatcher = if (byContentDescription) hasContentDescription(label) else hasText(label)
        val clickable = compose.onAllNodes(base and hasClickAction())
        if (clickable.fetchSemanticsNodes().isNotEmpty()) {
            clickable[0].performClick()
        } else {
            val any = compose.onAllNodes(base, useUnmergedTree = true)
            if (any.fetchSemanticsNodes().isEmpty()) fail("на экране нет элемента «$label»")
            any[0].performClick()
        }
        compose.waitForIdle()
    }

    private fun contactDetail() {
        compose.setContent { AppleAppTheme(darkTheme = false) { ContactDetailScreen(contactId, {}, {}, {}, {}) } }
    }

    private fun showCommunicationTab(c: Contact) {
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                LazyColumn { communicationTab(c, ctx) }
            }
        }
    }

    // ─── 1–3. Карточка контакта (шапка): Call / Message / Email ───

    @Test
    fun contactDetailHeader_call_opensDialer_doesNotChangeLastContactDate() {
        persist(contact())
        contactDetail()

        clickNode(ctx.getString(R.string.cd_call))

        assertEquals(Intent.ACTION_DIAL, nextIntentOrFail("шапка «Позвонить»").action)
        assertLastContactUnchanged("шапка «Позвонить»")
    }

    @Test
    fun contactDetailHeader_message_opensSmsComposer_doesNotChangeLastContactDate() {
        persist(contact()) // мессенджеров нет → ветка SMS
        contactDetail()

        clickNode(ctx.getString(R.string.cd_write))

        val intent = nextIntentOrFail("шапка «Написать»")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data?.scheme)
        assertLastContactUnchanged("шапка «Написать»")
    }

    @Test
    fun contactDetailHeader_email_opensEmailComposer_doesNotChangeLastContactDate() {
        persist(contact())
        contactDetail()

        clickNode(ctx.getString(R.string.cd_email_action))

        assertEquals("mailto", nextIntentOrFail("шапка «Email»").data?.scheme)
        assertLastContactUnchanged("шапка «Email»")
    }

    // ─── Вкладка «Общение»: Call / SMS / Email / Messenger ───

    @Test
    fun communicationTab_callSmsEmail_openChannels_doNotChangeLastContactDate() {
        val c = contact()
        persist(c)
        showCommunicationTab(c)

        clickNode(ctx.getString(R.string.cd_call), byContentDescription = true)
        assertEquals(Intent.ACTION_DIAL, nextIntentOrFail("вкладка «Позвонить»").action)
        assertLastContactUnchanged("вкладка «Позвонить»")

        clickNode(ctx.getString(R.string.cd_write), byContentDescription = true)
        assertEquals("smsto", nextIntentOrFail("вкладка «SMS»").data?.scheme)
        assertLastContactUnchanged("вкладка «SMS»")

        clickNode(ctx.getString(R.string.cd_email_action), byContentDescription = true)
        assertEquals("mailto", nextIntentOrFail("вкладка «Email»").data?.scheme)
        assertLastContactUnchanged("вкладка «Email»")
    }

    @Test
    fun communicationTab_messenger_opensMessenger_doesNotChangeLastContactDate() {
        val c = contact(
            phones = emptyList(), emails = emptyList(),
            messengers = listOf(Messenger("m1", contactId, MessengerType.TELEGRAM, "ivan_tester", isPrimary = true))
        )
        persist(c)
        showCommunicationTab(c)

        clickNode(ctx.getString(R.string.cd_write), byContentDescription = true)

        assertEquals(Intent.ACTION_VIEW, nextIntentOrFail("вкладка «мессенджер»").action)
        assertLastContactUnchanged("вкладка «мессенджер»")
    }

    // ─── 4. Неуспешный внешний интент ───

    /** Мессенджер «Другое» со значением-не-ссылкой: openMessenger не может
     *  построить ссылку и показывает отказ. Раньше контакт при этом всё равно
     *  отмечался «связались» — прямо под тостом об ошибке. */
    @Test
    fun communicationTab_messengerThatCannotOpen_doesNotChangeLastContactDate() {
        val c = contact(
            phones = emptyList(), emails = emptyList(),
            messengers = listOf(Messenger("m2", contactId, MessengerType.OTHER, "ivan123", isPrimary = true))
        )
        persist(c)
        showCommunicationTab(c)

        clickNode(ctx.getString(R.string.cd_write), byContentDescription = true)

        assertNull("предусловие: интент не должен был стартовать", app().nextStartedActivity)
        assertEquals("клик обязан дойти до обработчика — ожидался тост об отказе",
            ctx.getString(R.string.ext_messenger_open_failed, MessengerType.OTHER.labelKey()),
            ShadowToast.getTextOfLatestToast())
        assertLastContactUnchanged("мессенджер, который не открылся")
    }

    /** Нет приложения для звонка (планшет без звонилки): startActivity бросает
     *  ActivityNotFoundException, пользователь видит «нет приложения». */
    @Test
    fun contactDetailHeader_callWithNoDialerApp_doesNotChangeLastContactDate() {
        persist(contact())
        app().checkActivities(true) // ни одна Activity не разрешает ACTION_DIAL
        contactDetail()

        clickNode(ctx.getString(R.string.cd_call))

        assertEquals("клик обязан дойти до обработчика — ожидался тост «нет приложения»",
            ctx.getString(R.string.ext_no_app_for_action),
            ShadowToast.getTextOfLatestToast())
        assertLastContactUnchanged("звонок без приложения-звонилки")
    }

    // ─── Паритет: Шпаргалка и уведомление ───

    @Test
    fun cheatSheet_callAndMessage_doNotChangeLastContactDate() {
        persist(contact())
        compose.setContent { AppleAppTheme(darkTheme = false) { CheatSheetScreen(contactId) {} } }

        clickNode(ctx.getString(R.string.cs_call))
        assertEquals(Intent.ACTION_DIAL, nextIntentOrFail("шпаргалка «Позвонить»").action)
        assertLastContactUnchanged("шпаргалка «Позвонить»")

        clickNode(ctx.getString(R.string.cs_write))
        assertEquals("smsto", nextIntentOrFail("шпаргалка «Написать»").data?.scheme)
        assertLastContactUnchanged("шпаргалка «Написать»")
    }

    /** Кнопки уведомления — PendingIntent прямо на системную Activity: при
     *  нажатии не выполняется НИКАКОЙ код приложения, поэтому они не могут
     *  изменить данные. Тест закрепляет это устройство: если кто-то заменит их
     *  на broadcast в приложение, тест упадёт и потребует той же семантики. */
    @Test
    fun notificationCallAndMessage_arePureSystemIntents_withoutAppHandler() {
        NotificationHelper.createNotificationChannel(ctx)
        NotificationHelper.showNotification(ctx, 4242, "t", "c", targetCalendarItemId = null, phone = "+10000000001",
            kind = NotificationHelper.Kind.STALE_CONTACT, contactId = contactId)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notification = shadowOf(nm).getNotification(4242) ?: fail("уведомление не показано") as Nothing

        val expected = mapOf(
            ctx.getString(R.string.notif_action_call) to Intent.ACTION_DIAL,
            ctx.getString(R.string.notif_action_message) to Intent.ACTION_SENDTO
        )
        expected.forEach { (title, action) ->
            val a = notification.actions.firstOrNull { it.title == title } ?: fail("нет кнопки «$title»") as Nothing
            val pi = shadowOf(a.actionIntent)
            assertTrue("«$title» обязана открывать Activity, а не код приложения", pi.isActivityIntent)
            assertEquals(action, pi.savedIntent.action)
            assertNull("«$title» не должна адресоваться компоненту приложения", pi.savedIntent.component)
        }
    }

    // ─── 5–6. Позитивные контроли: явные действия ВСЁ ЕЩЁ меняют дату ───

    @Test
    fun homeMarkAsContacted_stillUpdatesLastContactDate() {
        persist(contact(rhythm = CommunicationRhythm.MONTHLY)) // 2025-01-01 → давно просрочен
        compose.setContent { AppleAppTheme(darkTheme = false) { HomeScreen(onNavigateToSettings = {}) } }

        clickNode(ctx.getString(R.string.home_mark_contacted), byContentDescription = true)

        val today = java.time.LocalDate.now().toString()
        assertEquals("«Отметить: связались» обязано обновить дату в памяти", today, lastContactInMemory())
        assertEquals("…и в Room", today, lastContactInDb())
    }

    @Test
    fun completingCallMeetingMessageEvent_stillUpdatesLastContactDate() {
        persist(contact())
        val today = java.time.LocalDate.now().toString()
        listOf(CalendarItemType.CALL, CalendarItemType.MEETING, CalendarItemType.MESSAGE).forEach { type ->
            // Сбрасываем дату, чтобы каждый тип доказывал обновление сам. Сброс — прямая
            // запись КАНОНИЧЕСКОГО состояния (Room + память), а не updateContact: после
            // OWNERSHIP PACK #1 updateContact намеренно не откатывает lastContactDate назад
            // (её независимо пишут markContactedNow/завершение события — см. тест
            // ChildOwnershipIntegrityTest.lastContactDate_staleContactUpdate_...), и ни один
            // production-экран не понижает дату. Раньше этот сброс шёл через updateContact.
            runBlocking {
                AppStateStore.awaitPendingWrites()
                db.contactDao().updateLastContactDate(contactId, oldDate, "2025-01-01T00:00")
            }
            AppStateStore.contacts.indexOfFirst { it.id == contactId }.takeIf { it >= 0 }?.let { i ->
                AppStateStore.contacts[i] = AppStateStore.contacts[i].copy(lastContactDate = oldDate)
            }
            assertEquals("предусловие для $type", oldDate, lastContactInDb())

            val itemId = "ev-$type"
            val item = CalendarItem(
                id = itemId, title = type.name, type = type, startDate = "2025-02-01",
                isAllDay = true, status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
                links = listOf(CalendarItemLink("l-$type", itemId, CalendarTargetType.CONTACT, contactId)),
                createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
            )
            runBlocking { AppStateStore.addCalendarItem(item).await() }

            AppStateStore.markCalendarItemCompleted(item)

            assertEquals("$type: завершение обязано обновить дату в памяти", today, lastContactInMemory())
            assertEquals("$type: …и в Room", today, lastContactInDb())
        }
    }


    // ═════════════════════════════════════════════════════════════
    //  АВТОМАТИЧЕСКИЙ ВЫБОР НОМЕРА (Call / SMS)
    //
    //  INVARIANT: если у контакта есть основной номер (isPrimary), любое
    //  действие, где номер выбирает само приложение, использует его; первый
    //  номер — только когда основного нет. «Позвонить» и запасной SMS в
    //  «Написать» для одного контакта не могут уйти на разные номера. Номер
    //  берётся из реально отправленного интента после нажатия настоящей кнопки.
    //
    //  Здесь, а не отдельным классом: второй Compose UI-класс в том же тестовом
    //  JVM не доходит до idle («Compose did not get idle», все тесты по 60 с) —
    //  см. отчёт; один Compose-класс стабилен.
    // ═════════════════════════════════════════════════════════════

    /** first=+111 (не основной), second=+222 (основной) — основной НЕ первый в списке. */
    private val primarySecond = listOf(
        ContactPhone("p1", contactId, "+111", PhoneType.MOBILE, isPrimary = false),
        ContactPhone("p2", contactId, "+222", PhoneType.WORK, isPrimary = true),
    )

    private val noPrimary = listOf(
        ContactPhone("p1", contactId, "+111", PhoneType.MOBILE, isPrimary = false),
        ContactPhone("p2", contactId, "+222", PhoneType.WORK, isPrimary = false),
    )

    private fun persistWithPhones(phones: List<ContactPhone>, messengers: List<Messenger> = emptyList()): Contact {
        val c = Contact(
            id = contactId, firstName = "Ivan", lastName = "Tester", photoUri = null,
            relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.NOT_TRACKED,
            phones = phones, messengers = messengers,
            createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
        )
        runBlocking { AppStateStore.addContact(c).await() }
        return c
    }

    /** Кликает i-й кликабельный узел с текстом/описанием label. */
    private fun clickAt(label: String, byContentDescription: Boolean = false, index: Int = 0) {
        val base: SemanticsMatcher = if (byContentDescription) hasContentDescription(label) else hasText(label)
        val clickable = compose.onAllNodes(base and hasClickAction())
        val nodes = if (clickable.fetchSemanticsNodes().size > index) clickable
                    else compose.onAllNodes(base, useUnmergedTree = true)
        if (nodes.fetchSemanticsNodes().size <= index) fail("на экране нет элемента «$label» #$index")
        nodes[index].performClick()
        compose.waitForIdle()
    }

    private fun nextIntentPs(what: String): Intent =
        app().nextStartedActivity ?: fail("$what: интент не стартовал") as Nothing

    /** Номер из интента набора (tel:) или SMS (smsto:). */
    private fun Intent.number(): String? = data?.schemeSpecificPart

    private fun showContactDetail() = compose.setContent {
        AppleAppTheme(darkTheme = false) { ContactDetailScreen(contactId, {}, {}, {}, {}) }
    }

    private fun showCheatSheet() = compose.setContent {
        AppleAppTheme(darkTheme = false) { CheatSheetScreen(contactId) {} }
    }

    // ─── A. Карточка контакта: основной номер не первый ───

    @Test
    fun contactDetail_callAndSmsFallback_bothUsePrimaryNumber() {
        persistWithPhones(primarySecond)
        showContactDetail()

        clickAt(ctx.getString(R.string.cd_call))
        val call = nextIntentPs("карточка «Позвонить»")
        assertEquals(Intent.ACTION_DIAL, call.action)
        assertEquals("«Позвонить» — основной номер", "+222", call.number())

        clickAt(ctx.getString(R.string.cd_write))
        val sms = nextIntentPs("карточка «Написать» (SMS)")
        assertEquals("smsto", sms.data?.scheme)
        assertEquals("запасной SMS — тот же основной номер, что у «Позвонить»", "+222", sms.number())
    }

    // ─── B. Шпаргалка: основной номер не первый ───

    @Test
    fun cheatSheet_callAndSmsFallback_bothUsePrimaryNumber() {
        persistWithPhones(primarySecond)
        showCheatSheet()

        clickAt(ctx.getString(R.string.cs_call))
        assertEquals("шпаргалка «Позвонить» — основной номер", "+222", nextIntentPs("шпаргалка «Позвонить»").number())

        clickAt(ctx.getString(R.string.cs_write))
        val sms = nextIntentPs("шпаргалка «Написать» (SMS)")
        assertEquals("smsto", sms.data?.scheme)
        assertEquals("запасной SMS — тот же основной номер", "+222", sms.number())
    }

    // ─── C. Основного нет: оба действия — первый номер ───

    @Test
    fun withoutPrimary_callAndSmsFallback_useFirstNumber_inBothScreens() {
        persistWithPhones(noPrimary)

        showContactDetail()
        clickAt(ctx.getString(R.string.cd_call))
        assertEquals("карточка: «Позвонить» без основного — первый", "+111", nextIntentPs("карточка «Позвонить»").number())
        clickAt(ctx.getString(R.string.cd_write))
        assertEquals("карточка: SMS без основного — первый", "+111", nextIntentPs("карточка SMS").number())
    }

    @Test
    fun withoutPrimary_cheatSheet_callAndSmsFallback_useFirstNumber() {
        persistWithPhones(noPrimary)

        showCheatSheet()
        clickAt(ctx.getString(R.string.cs_call))
        assertEquals("шпаргалка: «Позвонить» без основного — первый", "+111", nextIntentPs("шпаргалка «Позвонить»").number())
        clickAt(ctx.getString(R.string.cs_write))
        assertEquals("шпаргалка: SMS без основного — первый", "+111", nextIntentPs("шпаргалка SMS").number())
    }

    // ─── D. Есть мессенджер: «Написать» открывает его, не SMS ───

    @Test
    fun withPrimaryMessenger_messageOpensMessenger_notSms_inBothScreens() {
        persistWithPhones(
            primarySecond,
            messengers = listOf(Messenger("m1", contactId, MessengerType.TELEGRAM, "ivan_tester", isPrimary = true))
        )

        showContactDetail()
        clickAt(ctx.getString(R.string.cd_write))
        val fromCard = nextIntentPs("карточка «Написать»")
        assertEquals("карточка: мессенджер, не SMS", Intent.ACTION_VIEW, fromCard.action)
        assertEquals("tg", fromCard.data?.scheme)
    }

    @Test
    fun withPrimaryMessenger_cheatSheet_messageOpensMessenger_notSms() {
        persistWithPhones(
            primarySecond,
            messengers = listOf(Messenger("m1", contactId, MessengerType.TELEGRAM, "ivan_tester", isPrimary = true))
        )

        showCheatSheet()
        // При наличии мессенджера кнопка подписана его типом, а не «Написать».
        clickAt(MessengerType.TELEGRAM.label(ctx))
        val fromSheet = nextIntentPs("шпаргалка «Написать»")
        assertEquals("шпаргалка: мессенджер, не SMS", Intent.ACTION_VIEW, fromSheet.action)
        assertEquals("tg", fromSheet.data?.scheme)
    }

    // ─── E. Вкладка «Общение»: нажатый конкретный номер не перенаправляется ───

    @Test
    fun communicationTab_explicitSecondaryNumber_isNotRedirectedToPrimary() {
        val c = persistWithPhones(primarySecond)
        compose.setContent { AppleAppTheme(darkTheme = false) { LazyColumn { communicationTab(c, ctx) } } }

        val callLabel = ctx.getString(R.string.cd_call)
        val smsLabel = ctx.getString(R.string.cd_write)

        // Строки идут в порядке списка телефонов: #0 = +111 (не основной), #1 = +222.
        clickAt(callLabel, byContentDescription = true, index = 0)
        assertEquals("«Позвонить» у строки +111 звонит на +111", "+111", nextIntentPs("строка +111 «Позвонить»").number())
        clickAt(smsLabel, byContentDescription = true, index = 0)
        assertEquals("SMS у строки +111 уходит на +111", "+111", nextIntentPs("строка +111 SMS").number())

        clickAt(callLabel, byContentDescription = true, index = 1)
        assertEquals("«Позвонить» у строки +222 звонит на +222", "+222", nextIntentPs("строка +222 «Позвонить»").number())
    }

    // ═════════════════════════════════════════════════════════════
    //  ЗАВЕРШЕНИЕ CALENDARITEM ЧЕРЕЗ РЕДАКТОР
    //
    //  INVARIANT 1: переход в COMPLETED пользовательским действием — одна
    //  бизнес-семантика независимо от точки входа (кнопка «Готово» на экране
    //  события, «Готово» в уведомлении, редактор). Для CALL/MEETING/MESSAGE —
    //  lastContactDate = сегодня, атомарно с остальными правками того же
    //  сохранения, будильники сняты.
    //  INVARIANT 2: даты (BIRTHDAY/ANNIVERSARY/NAMEDAY/IMPORTANT_DATE) не
    //  переводятся в COMPLETED пользовательским действием — ни через
    //  выпадающий список (не предлагается), ни в обход него.
    //
    //  Здесь же, а не отдельным классом: второй Compose UI-класс в этом
    //  тестовом JVM не доходит до idle (см. отчёт по PhoneSelectionTest).
    // ═════════════════════════════════════════════════════════════

    private val eventStart: java.time.LocalDateTime =
        java.time.LocalDateTime.now().plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0)

    private fun calendarItem(
        id: String,
        type: CalendarItemType,
        status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        title: String = "Original title",
        linkToContact: Boolean = true,
        reminders: List<ReminderRule> = emptyList(),
        recurrenceRule: String? = null,
        recurrenceExplicitlySet: Boolean = false,
        lastCompletedOccurrenceDate: String? = null,
    ) = CalendarItem(
        id = id, title = title, type = type,
        startDate = eventStart.toLocalDate().toString(),
        startTime = "%02d:%02d".format(eventStart.hour, eventStart.minute),
        isAllDay = false, status = status, importance = ImportanceLevel.NORMAL,
        links = if (linkToContact) listOf(CalendarItemLink("$id-l1", id, CalendarTargetType.CONTACT, contactId)) else emptyList(),
        reminders = reminders,
        recurrenceRule = recurrenceRule,
        recurrenceExplicitlySet = recurrenceExplicitlySet,
        lastCompletedOccurrenceDate = lastCompletedOccurrenceDate,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun persistCalendarItem(item: CalendarItem) {
        runBlocking { AppStateStore.addCalendarItem(item).await() }
    }

    private fun itemInMemory(id: String) = AppStateStore.calendarItems.find { it.id == id }

    private fun itemInDb(id: String): CalendarItem? = runBlocking {
        val entity = db.calendarDao().getAllCalendarItems().find { it.id == id } ?: return@runBlocking null
        entity.toDomain()
    }

    private fun showEditor(itemId: String) {
        compose.setContent { AppleAppTheme(darkTheme = false) { CalendarItemEditScreen(calendarItemId = itemId, onNavigateBack = {}) } }
    }

    // ПРИМЕЧАНИЕ по тестам A-G: выбор статуса в редакторе — это Material3
    // DropdownMenu (Popup). Эмпирически подтверждено (диагностика: количество
    // кликабельных узлов в semantics-дереве не меняется до/после открытия ЛЮБОГО
    // DropdownMenu на этом экране, включая нетронутый список важности; 0 текстовых
    // совпадений что в merged, что в unmerged дереве; ни одной ошибки в логе) —
    // содержимое Popup не регистрируется в semantics-дереве под Robolectric в
    // этой конфигурации проекта (Robolectric 4.16.1 / Compose UI test 1.7.3),
    // то есть кликнуть по пункту выпадающего списка в этом тестовом окружении
    // нельзя в принципе — это НЕ баг продакшн-кода. Поэтому developer-facing
    // бизнес-развилка сохранения (persistCalendarItemEdit), список допустимых
    // статусов (selectableCalendarStatusOptions) и defense-in-depth
    // (sanitizeCalendarItemStatusForSave) вынесены в CalendarItemEditScreen.kt
    // как internal-функции: именно ИХ вызывает кнопка «Готово» этого экрана
    // (см. её onClick), и тесты A-G дёргают ровно этот же код напрямую — не
    // дубликат и не мок, а та же самая бизнес-логика, вызванная так же, как её
    // вызывает сама кнопка. Тест H (без выбора статуса) и так проходит через
    // настоящий Compose UI end-to-end и попутно подтверждает, что кнопка
    // «Готово» действительно вызывает persistCalendarItemEdit с ожидаемыми
    // аргументами.

    private fun replaceTitle(oldTitle: String, newTitle: String) {
        compose.onNodeWithText(oldTitle).performTextReplacement(newTitle)
        compose.waitForIdle()
    }

    private fun saveEditor() {
        clickAt(ctx.getString(R.string.common_done))
    }

    // ─── A / B / C. CALL / MEETING / MESSAGE → COMPLETED через редактор ───
    //
    //  pickStatus() через Popup недостижим под Robolectric (см. комментарий
    //  выше showEditor) — вызываем ту же persistCalendarItemEdit(), что и
    //  кнопка «Готово» этого экрана, с newItem, каким его строит сама форма
    //  после правки title + выбора «Выполнено» в том же сохранении.

    private fun interactionCompletionThroughEditor(type: CalendarItemType) {
        persist(contact())
        val itemId = "cie-completion-${type.name}"
        val original = calendarItem(itemId, type = type)
        persistCalendarItem(original)
        assertEquals("предусловие: lastContactDate ещё старый", oldDate, lastContactInDb())

        val edited = original.copy(title = "Edited title", status = CalendarItemStatus.COMPLETED)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        // markCalendarItemCompleted() не возвращает Deferred — запись в Room
        // идёт через fire-and-forget DbWriteQueue (INVARIANT D3, см. AppStateStore),
        // как и в продакшне (кнопка «Готово»/уведомление её тоже не ждут). Тот же
        // pollUntil-паттерн, что и в NotificationActionSemanticsTest.
        assertTrue("$type: запись в Room не дождалась завершения", pollUntil(5_000) { itemInDb(itemId)?.status == CalendarItemStatus.COMPLETED })

        val today = java.time.LocalDate.now().toString()
        assertEquals("$type: статус COMPLETED в памяти", CalendarItemStatus.COMPLETED, itemInMemory(itemId)?.status)
        assertEquals("$type: статус COMPLETED в Room", CalendarItemStatus.COMPLETED, itemInDb(itemId)?.status)
        assertEquals("$type: остальная правка (название) сохранена в памяти", "Edited title", itemInMemory(itemId)?.title)
        assertEquals("$type: остальная правка (название) сохранена в Room", "Edited title", itemInDb(itemId)?.title)
        assertEquals("$type: lastContactDate обновлён в памяти", today, lastContactInMemory())
        assertEquals("$type: lastContactDate обновлён в Room", today, lastContactInDb())
    }

    @Test
    fun a_callThroughEditor_transitionToCompleted_updatesLastContactDateAndKeepsEdits() {
        interactionCompletionThroughEditor(CalendarItemType.CALL)
    }

    @Test
    fun b_meetingThroughEditor_transitionToCompleted_sameCompletionSideEffect() {
        interactionCompletionThroughEditor(CalendarItemType.MEETING)
    }

    @Test
    fun c_messageThroughEditor_transitionToCompleted_sameCompletionSideEffect() {
        interactionCompletionThroughEditor(CalendarItemType.MESSAGE)
    }

    // ─── D. TASK → COMPLETED: статус меняется, lastContactDate — нет ───

    @Test
    fun d_taskThroughEditor_transitionToCompleted_doesNotChangeLastContactDate() {
        persist(contact())
        val itemId = "cie-completion-task"
        val original = calendarItem(itemId, type = CalendarItemType.TASK)
        persistCalendarItem(original)

        val edited = original.copy(status = CalendarItemStatus.COMPLETED)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("TASK: запись в Room не дождалась завершения", pollUntil(5_000) { itemInDb(itemId)?.status == CalendarItemStatus.COMPLETED })

        assertEquals("TASK: статус COMPLETED", CalendarItemStatus.COMPLETED, itemInDb(itemId)?.status)
        assertEquals("TASK: lastContactDate НЕ меняется", oldDate, lastContactInDb())
        assertEquals("TASK: и в памяти", oldDate, lastContactInMemory())
    }

    // ─── E / F. Даты: редактор не предлагает COMPLETED ───
    //
    //  Тот же canonical selectableCalendarStatusOptions()/sanitizeCalendarItemStatusForSave(),
    //  что фильтрует DropdownMenu и защищает save-path в CalendarItemEditScreen —
    //  вызываем их напрямую (см. комментарий у showEditor: сам DropdownMenu
    //  недостижим для клика под Robolectric в этом проекте).

    private fun dateOnlyCannotBeCompletedThroughEditor(type: CalendarItemType) {
        val itemId = "cie-dateonly-${type.name}"
        val original = calendarItem(itemId, type = type, linkToContact = false) // status = ACTIVE
        persistCalendarItem(original)

        assertFalse("$type: выпадающий список не должен предлагать «Выполнено» (edit)",
            selectableCalendarStatusOptions(type, isEditMode = true).contains(CalendarItemStatus.COMPLETED))

        // C. ACTIVE date-only + попытка НОВОГО перехода в COMPLETED — блокируется
        // (rawStatus == COMPLETED отличается от originalItem.status == ACTIVE, значит
        // это настоящая попытка нового transition, а не уже persisted значение).
        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, type, CalendarItemStatus.COMPLETED)
        assertEquals("$type: новый transition в COMPLETED блокируется",
            CalendarItemStatus.ACTIVE, savedStatus)

        val edited = original.copy(status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertEquals("$type: статус остался ACTIVE", CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
    }

    @Test
    fun e_birthday_editorDoesNotOfferCompleted() {
        dateOnlyCannotBeCompletedThroughEditor(CalendarItemType.BIRTHDAY)
    }

    @Test
    fun f_anniversary_editorDoesNotOfferCompleted() {
        dateOnlyCannotBeCompletedThroughEditor(CalendarItemType.ANNIVERSARY)
    }

    @Test
    fun f_nameday_editorDoesNotOfferCompleted() {
        dateOnlyCannotBeCompletedThroughEditor(CalendarItemType.NAMEDAY)
    }

    @Test
    fun f_importantDate_editorDoesNotOfferCompleted() {
        dateOnlyCannotBeCompletedThroughEditor(CalendarItemType.IMPORTANT_DATE)
    }

    // ─── G. Смена правил напоминания в том же сохранении + переход в COMPLETED ───

    @Test
    fun g_reminderRulesChangedDuringSameEdit_plusCompletion_oldAlarmsGoneNoNewOnes() {
        persist(contact())
        val itemId = "cie-completion-reminders"
        val oldRule = ReminderRule(id = "cie-old-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val original = calendarItem(itemId, type = CalendarItemType.CALL, reminders = listOf(oldRule))
        persistCalendarItem(original)
        // Настоящий будильник, как если бы событие было запланировано ДО этого редактирования.
        com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler.scheduleReminder(ctx, original, oldRule)
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        fun eventAlarms() = shadowOf(alarmManager).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.component?.className ==
                com.aistudio.socialsphere.crmlxb.utils.NotificationReceiver::class.java.name
        }
        assertEquals("предусловие: будильник по старому правилу стоит", 1, eventAlarms().size)

        // Напоминания пересобраны с НОВЫМ id (как форма делает при каждом
        // сохранении — newReminderRules в CalendarItemEditScreen) И статус
        // переходит в COMPLETED в том же сохранении.
        val newRule = ReminderRule(id = "cie-new-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val edited = original.copy(status = CalendarItemStatus.COMPLETED, reminders = listOf(newRule))
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        // CalendarCompletion.complete() теперь асинхронна (свой completionScope,
        // Deferred<Boolean>) — persistCalendarItemEdit не ждёт её (fire-and-forget,
        // как и раньше для UI), поэтому проверка отмены будильника — через poll.
        assertTrue("после перехода в COMPLETED будильников на событие нет",
            pollUntil(5_000) { eventAlarms().isEmpty() })
    }

    // ─── H. Обычная правка ACTIVE → ACTIVE: обычный путь, без completion-эффектов ───

    @Test
    fun h_ordinaryActiveToActiveEdit_usesNormalUpdatePath_noCompletionSideEffects() {
        persist(contact())
        val itemId = "cie-active-edit"
        persistCalendarItem(calendarItem(itemId, type = CalendarItemType.CALL))

        showEditor(itemId)
        replaceTitle("Original title", "Renamed while still active")
        saveEditor()
        assertTrue("запись в Room не дождалась завершения", pollUntil(5_000) { itemInDb(itemId)?.title == "Renamed while still active" })

        assertEquals("статус остаётся ACTIVE", CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
        assertEquals("название сохранено", "Renamed while still active", itemInDb(itemId)?.title)
        assertEquals("lastContactDate НЕ тронут — это не завершение", oldDate, lastContactInDb())
    }

    // ─── G. v20 persistence-foundation (2026-09-17): editor save с НЕсвязанной
    // правкой (title) через РЕАЛЬНЫЙ Compose UI end-to-end (та же кнопка
    // «Готово», что и test H выше — без выбора статуса, поэтому Popup не нужен)
    // обязана сохранить postpone-пару целиком. Редактор её пока не показывает
    // и не меняет — проверяет именно ТУ строку CalendarItemEditScreen, которую
    // добавил этот batch (newItem строится с postponedFromOccurrenceDate/
    // postponedToDate = originalItem'а, тот же паттерн, что colorKey/
    // lastCompletedOccurrenceDate). ───

    @Test
    fun g_unrelatedFieldEdit_throughRealEditorUi_preservesPostponePair() {
        persist(contact())
        val itemId = "cie-postpone-preserved"
        val original = calendarItem(itemId, type = CalendarItemType.CALL).copy(
            postponedFromOccurrenceDate = "2026-09-16",
            postponedToDate = "2026-09-18"
        )
        persistCalendarItem(original)

        showEditor(itemId)
        replaceTitle("Original title", "Renamed, postpone untouched")
        saveEditor()
        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Renamed, postpone untouched" })

        val saved = itemInDb(itemId)!!
        assertEquals("Renamed, postpone untouched", saved.title)
        assertEquals("postponedFromOccurrenceDate сохранён целиком при unrelated edit",
            "2026-09-16", saved.postponedFromOccurrenceDate)
        assertEquals("postponedToDate сохранён целиком при unrelated edit",
            "2026-09-18", saved.postponedToDate)
    }

    // ─── I. Кнопка «Готово» и Done в уведомлении используют ту же семантику ───

    @Test
    fun i_detailScreenDoneButton_stillUsesSameCompletionSemantics() {
        persist(contact())
        val itemId = "cie-done-button"
        persistCalendarItem(calendarItem(itemId, type = CalendarItemType.MEETING))
        val item = itemInMemory(itemId)!!

        assertTrue("предусловие: тип допускает завершение", com.aistudio.socialsphere.crmlxb.utils.CalendarCompletion.canComplete(item))
        com.aistudio.socialsphere.crmlxb.utils.CalendarCompletion.complete(ctx, item)
        assertTrue("запись в Room не дождалась завершения", pollUntil(5_000) { itemInDb(itemId)?.status == CalendarItemStatus.COMPLETED })

        val today = java.time.LocalDate.now().toString()
        assertEquals("кнопка «Готово»: статус COMPLETED", CalendarItemStatus.COMPLETED, itemInDb(itemId)?.status)
        assertEquals("кнопка «Готово»: lastContactDate — та же семантика, что и у редактора", today, lastContactInDb())
    }

    // ═════════════════════════════════════════════════════════════
    //  EDGE CASES: создание уже COMPLETED и legacy date-only COMPLETED
    //
    //  1) НОВЫЙ CalendarItem (create) не может сохраниться сразу COMPLETED —
    //     create идёt через addCalendarItem(), а не markCalendarItemCompleted(),
    //     completion semantics обходится. Событие должно сперва существовать
    //     как обычное; завершение — уже существующим единым completion path.
    //  2) Legacy-данные (BIRTHDAY/ANNIVERSARY/NAMEDAY/IMPORTANT_DATE со
    //     status=COMPLETED, сохранённые ДО фикса INVARIANT 2) — обычное
    //     редактирование НЕ должно молча превращать persisted COMPLETED в
    //     ACTIVE, если пользователь статус не менял (Data Safety). Новый
    //     переход ACTIVE date-only → COMPLETED по-прежнему запрещён.
    //
    //  sanitizeCalendarItemStatusForSave() стал transition-aware: учитывает
    //  isEditMode/originalItem.status, а не только rawStatus+type — второй
    //  completion policy не заводим, тот же canonical CalendarCompletion.
    // ═════════════════════════════════════════════════════════════

    // ─── A. Legacy: existing BIRTHDAY/COMPLETED + правка ДРУГОГО поля → остаётся COMPLETED ───
    //  (критический тест — ловит текущую/прошлую реализацию: старый
    //  sanitizeCalendarItemStatusForSave(rawStatus, type) не знал про
    //  originalItem и молча превращал persisted COMPLETED в ACTIVE).

    private fun legacyCompletedDateOnly_editUnrelatedField_dataSafety(type: CalendarItemType) {
        val itemId = "cie-legacy-completed-${type.name}"
        val original = calendarItem(itemId, type = type, status = CalendarItemStatus.COMPLETED, linkToContact = false)
        persistCalendarItem(original)

        // Пользователь статус не трогает — форма инициализирует status из
        // originalItem.status, дата не даёт открыть «Выполнено» в списке
        // (selectableCalendarStatusOptions), но НАЧАЛЬНОЕ значение — уже
        // persisted COMPLETED, и оно остаётся неизменным (rawStatus == original.status).
        val rawStatus = original.status
        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, type, rawStatus)
        assertEquals("$type: legacy COMPLETED сохраняется без изменений (Data Safety)",
            CalendarItemStatus.COMPLETED, savedStatus)

        val edited = original.copy(title = "Edited unrelated field", status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("$type: запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Edited unrelated field" })

        assertEquals("$type: статус остался COMPLETED", CalendarItemStatus.COMPLETED, itemInDb(itemId)?.status)
        assertEquals("$type: правка поля сохранена", "Edited unrelated field", itemInDb(itemId)?.title)
    }

    @Test
    fun a_legacyCompletedBirthday_editTitleOnly_staysCompleted() {
        legacyCompletedDateOnly_editUnrelatedField_dataSafety(CalendarItemType.BIRTHDAY)
    }

    // ─── B. То же для ANNIVERSARY ───

    @Test
    fun b_legacyCompletedAnniversary_editUnrelatedField_staysCompleted() {
        legacyCompletedDateOnly_editUnrelatedField_dataSafety(CalendarItemType.ANNIVERSARY)
    }

    // ─── C. ACTIVE date-only + попытка нового transition в COMPLETED → блокируется ───
    //  Уже покрыто e_birthday_editorDoesNotOfferCompleted / f_*_editorDoesNotOfferCompleted
    //  выше (dateOnlyCannotBeCompletedThroughEditor) — там же обновлён на
    //  новую transition-aware сигнатуру sanitizeCalendarItemStatusForSave().

    // ─── D. Новый date-only → COMPLETED недоступен при create ───

    @Test
    fun d_newBirthday_completedUnavailableAtCreate() {
        assertFalse("новый BIRTHDAY: «Выполнено» не предлагается в статус-опциях",
            selectableCalendarStatusOptions(CalendarItemType.BIRTHDAY, isEditMode = false).contains(CalendarItemStatus.COMPLETED))
        assertEquals("новый BIRTHDAY: save-path не пропускает COMPLETED при create",
            CalendarItemStatus.ACTIVE,
            sanitizeCalendarItemStatusForSave(false, null, CalendarItemType.BIRTHDAY, CalendarItemStatus.COMPLETED))
    }

    // ─── E. Новый CALL/MEETING/MESSAGE → COMPLETED тоже недоступен при create ───
    //  (критический тест — ловит текущую/прошлую реализацию: старый
    //  selectableCalendarStatusOptions(type) не знал про isEditMode и предлагал
    //  «Выполнено» для CALL/MEETING/MESSAGE независимо от create/edit).

    @Test
    fun e_newCallEditor_statusOptionsExcludeCompleted() {
        assertFalse("новый CALL: «Выполнено» не предлагается в статус-опциях",
            selectableCalendarStatusOptions(CalendarItemType.CALL, isEditMode = false).contains(CalendarItemStatus.COMPLETED))
        assertEquals("новый CALL: save-path не пропускает COMPLETED при create",
            CalendarItemStatus.ACTIVE,
            sanitizeCalendarItemStatusForSave(false, null, CalendarItemType.CALL, CalendarItemStatus.COMPLETED))
    }

    /** То же правило должно автоматически работать для ВСЕХ типов через общий
     *  helper — без второго списка «типов, которым нельзя создаваться завершёнными». */
    @Test
    fun e_newItem_everyType_completedUnavailableAtCreate_viaCommonHelper() {
        CalendarItemType.values().forEach { type ->
            assertFalse("новый $type: «Выполнено» не предлагается при create",
                selectableCalendarStatusOptions(type, isEditMode = false).contains(CalendarItemStatus.COMPLETED))
            assertEquals("новый $type: save-path блокирует COMPLETED при create",
                CalendarItemStatus.ACTIVE,
                sanitizeCalendarItemStatusForSave(false, null, type, CalendarItemStatus.COMPLETED))
        }
    }

    /** Полная цепочка create (не только чистые функции): «невалидный» COMPLETED
     *  в форме нового события не переживает save — событие создаётся ACTIVE,
     *  lastContactDate не трогается (create не проходит через completion path). */
    @Test
    fun e_newCall_createAttemptWithCompletedRawStatus_persistsAsActiveNotCompleted() {
        persist(contact())
        val itemId = "cie-new-call-completed-attempt"
        val savedStatus = sanitizeCalendarItemStatusForSave(false, null, CalendarItemType.CALL, CalendarItemStatus.COMPLETED)
        val newItem = calendarItem(itemId, type = CalendarItemType.CALL, status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = false, originalItem = null, newItem = newItem)

        assertTrue("запись в Room не дождалась завершения", pollUntil(5_000) { itemInDb(itemId) != null })
        assertEquals("новый CALL создаётся ACTIVE, не COMPLETED — минуя markCalendarItemCompleted",
            CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
        assertEquals("lastContactDate не трогается при create", oldDate, lastContactInDb())
    }

    // ─── F. Существующий ACTIVE CALL → COMPLETED через editor: общий completion path не сломан ───

    @Test
    fun f_existingActiveCall_transitionToCompleted_stillUsesCommonCompletionPath() {
        persist(contact())
        val itemId = "cie-existing-active-call-edge"
        val original = calendarItem(itemId, type = CalendarItemType.CALL) // ACTIVE
        persistCalendarItem(original)
        assertEquals("предусловие: lastContactDate ещё старый", oldDate, lastContactInDb())

        // Пользователь явно выбрал «Выполнено» — rawStatus отличается от
        // originalItem.status (ACTIVE) — настоящий новый transition, не должен
        // блокироваться transition-aware sanitizer'ом.
        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, CalendarItemType.CALL, CalendarItemStatus.COMPLETED)
        assertEquals("CALL: sanitizer пропускает настоящий новый completion transition",
            CalendarItemStatus.COMPLETED, savedStatus)

        val edited = original.copy(status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.status == CalendarItemStatus.COMPLETED })

        val today = java.time.LocalDate.now().toString()
        assertEquals("CALL: статус COMPLETED в Room", CalendarItemStatus.COMPLETED, itemInDb(itemId)?.status)
        assertEquals("CALL: lastContactDate обновлён — общий completion path сработал", today, lastContactInDb())
    }

    // ═════════════════════════════════════════════════════════════
    //  EDGE CASE: CalendarItem type/status transition — sanitizer стал
    //  type-aware, не только status-aware. rawStatus == originalItem.status
    //  сам по себе недостаточен — тип мог измениться в том же сохранении:
    //   CALL/COMPLETED → BIRTHDAY(status не тронут) должен стать ACTIVE, а
    //   BIRTHDAY(legacy)/COMPLETED → CALL(status не тронут) тоже ACTIVE (иначе
    //   получился бы COMPLETED interaction-event в обход markCalendarItemCompleted).
    //  Матрица: см. sanitizeCalendarItemStatusForSave() в CalendarItemEditScreen.kt.
    // ═════════════════════════════════════════════════════════════

    // ─── A. CALL/COMPLETED → тип сменён на BIRTHDAY, статус не трогали → ACTIVE ───
    //  (критический тест — ловит текущую/прошлую реализацию: старый sanitizer
    //  сравнивал только rawStatus == originalItem.status и не знал про смену типа).

    @Test
    fun typeChange_completedCallToBirthday_statusUntouched_dropsToActive() {
        val itemId = "cie-typechange-call-to-birthday"
        val original = calendarItem(itemId, type = CalendarItemType.CALL, status = CalendarItemStatus.COMPLETED)
        persistCalendarItem(original)

        // Пользователь меняет ТИП на BIRTHDAY, статус явно не трогает — форма
        // несёт исходное значение status = COMPLETED (rawStatus).
        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, CalendarItemType.BIRTHDAY, CalendarItemStatus.COMPLETED)
        assertEquals("CALL/COMPLETED → BIRTHDAY: COMPLETED не переносится на несовместимый тип",
            CalendarItemStatus.ACTIVE, savedStatus)

        val edited = original.copy(type = CalendarItemType.BIRTHDAY, status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.type == CalendarItemType.BIRTHDAY })
        assertEquals("итоговый статус — ACTIVE", CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
    }

    // ─── B. legacy BIRTHDAY/COMPLETED → тип сменён на CALL, статус не трогали → ACTIVE, lastContactDate не меняется ───
    //  (критический тест — та же причина, что и A: смена типа игнорировалась).

    @Test
    fun typeChange_legacyCompletedBirthdayToCall_statusUntouched_dropsToActive_lastContactDateUnchanged() {
        persist(contact())
        val itemId = "cie-typechange-birthday-to-call"
        val original = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, status = CalendarItemStatus.COMPLETED)
        persistCalendarItem(original)
        assertEquals("предусловие: lastContactDate ещё старый", oldDate, lastContactInDb())

        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, CalendarItemType.CALL, CalendarItemStatus.COMPLETED)
        assertEquals("legacy BIRTHDAY/COMPLETED → CALL: не становится «COMPLETED-звонком» в обход markCalendarItemCompleted",
            CalendarItemStatus.ACTIVE, savedStatus)

        val edited = original.copy(type = CalendarItemType.CALL, status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.type == CalendarItemType.CALL })

        assertEquals("итоговый статус — ACTIVE", CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
        assertEquals("lastContactDate НЕ обновлён — это не completion-действие", oldDate, lastContactInDb())
    }

    // ─── C. CALL/COMPLETED → тип сменён на MEETING (оба completable) → COMPLETED сохраняется ───

    @Test
    fun typeChange_completedCallToMeeting_bothOfferCompletion_staysCompleted() {
        val itemId = "cie-typechange-call-to-meeting"
        val original = calendarItem(itemId, type = CalendarItemType.CALL, status = CalendarItemStatus.COMPLETED)
        persistCalendarItem(original)

        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, CalendarItemType.MEETING, CalendarItemStatus.COMPLETED)
        assertEquals("CALL/COMPLETED → MEETING (оба completable): не новое completion-действие, сохраняется",
            CalendarItemStatus.COMPLETED, savedStatus)

        val edited = original.copy(type = CalendarItemType.MEETING, status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.type == CalendarItemType.MEETING })
        assertEquals("итоговый статус — COMPLETED", CalendarItemStatus.COMPLETED, itemInDb(itemId)?.status)
    }

    // ─── D. legacy BIRTHDAY/COMPLETED → правка только title → COMPLETED сохраняется ───
    //  Уже покрыто a_legacyCompletedBirthday_editTitleOnly_staysCompleted /
    //  b_legacyCompletedAnniversary_editUnrelatedField_staysCompleted (тип не
    //  меняется — typeUnchanged-ветка той же матрицы).

    // ─── E. BIRTHDAY/ACTIVE → тип сменён на CALL + пользователь явно выбирает COMPLETED → общий completion path, lastContactDate обновляется ───

    @Test
    fun typeChange_activeBirthdayToCall_explicitCompletion_usesCommonPathAndUpdatesLastContactDate() {
        persist(contact())
        val itemId = "cie-typechange-active-birthday-to-call"
        val original = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, status = CalendarItemStatus.ACTIVE)
        persistCalendarItem(original)
        assertEquals("предусловие: lastContactDate ещё старый", oldDate, lastContactInDb())

        // Настоящий НОВЫЙ переход: originalItem.status(ACTIVE) != COMPLETED,
        // новый тип (CALL) поддерживает completion — разрешено.
        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, CalendarItemType.CALL, CalendarItemStatus.COMPLETED)
        assertEquals("BIRTHDAY/ACTIVE → CALL + явный выбор COMPLETED: новый переход разрешён",
            CalendarItemStatus.COMPLETED, savedStatus)

        val edited = original.copy(type = CalendarItemType.CALL, status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.status == CalendarItemStatus.COMPLETED })

        val today = java.time.LocalDate.now().toString()
        assertEquals("итоговый тип — CALL", CalendarItemType.CALL, itemInDb(itemId)?.type)
        assertEquals("итоговый статус — COMPLETED", CalendarItemStatus.COMPLETED, itemInDb(itemId)?.status)
        assertEquals("lastContactDate обновлён — общий completion path (markCalendarItemCompleted) сработал",
            today, lastContactInDb())
    }

    // ─── F. CALL/ACTIVE → тип сменён на BIRTHDAY + попытка COMPLETED → ACTIVE ───

    @Test
    fun typeChange_activeCallToBirthday_attemptCompletion_blocked() {
        val itemId = "cie-typechange-active-call-to-birthday"
        val original = calendarItem(itemId, type = CalendarItemType.CALL, status = CalendarItemStatus.ACTIVE)
        persistCalendarItem(original)

        val savedStatus = sanitizeCalendarItemStatusForSave(true, original, CalendarItemType.BIRTHDAY, CalendarItemStatus.COMPLETED)
        assertEquals("CALL/ACTIVE → BIRTHDAY + попытка COMPLETED: блокируется, новый тип не поддерживает completion",
            CalendarItemStatus.ACTIVE, savedStatus)

        val edited = original.copy(type = CalendarItemType.BIRTHDAY, status = savedStatus)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)
        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.type == CalendarItemType.BIRTHDAY })
        assertEquals("итоговый статус — ACTIVE", CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
    }

    // ─── Исчерпывающая сверка матрицы: originalType × newType × originalStatus × rawStatus
    //     на границе offersCompletion = true/false (не только 6 именованных тестов выше) ───

    @Test
    fun typeTransitionMatrix_allBoundaryCombinations_matchSpecifiedRules() {
        val completableA = CalendarItemType.CALL      // offersCompletion = true
        val completableB = CalendarItemType.MEETING   // offersCompletion = true, отличается от completableA
        val nonCompletable = CalendarItemType.BIRTHDAY // offersCompletion = false

        fun freshOriginal(type: CalendarItemType, status: CalendarItemStatus) =
            calendarItem("matrix-${type.name}-${status.name}", type = type, status = status, linkToContact = false)

        // rawStatus != COMPLETED — sanitizer прозрачен, независимо от остальных параметров.
        listOf(CalendarItemStatus.ACTIVE, CalendarItemStatus.CANCELLED).forEach { untouched ->
            assertEquals("rawStatus=$untouched (не COMPLETED) — sanitizer не вмешивается",
                untouched,
                sanitizeCalendarItemStatusForSave(true, freshOriginal(completableA, CalendarItemStatus.ACTIVE), nonCompletable, untouched))
        }

        // Правило 1: CREATE + rawStatus=COMPLETED → ACTIVE, для любого типа.
        listOf(completableA, nonCompletable).forEach { type ->
            assertEquals("create $type: COMPLETED всегда блокируется",
                CalendarItemStatus.ACTIVE,
                sanitizeCalendarItemStatusForSave(false, null, type, CalendarItemStatus.COMPLETED))
        }

        // Правила 2/3/4: EDIT, originalItem.status == COMPLETED.
        data class TypePair(val originalType: CalendarItemType, val newType: CalendarItemType, val expected: CalendarItemStatus)
        listOf(
            TypePair(completableA, completableA, CalendarItemStatus.COMPLETED),   // тип не менялся, completable
            TypePair(nonCompletable, nonCompletable, CalendarItemStatus.COMPLETED), // тип не менялся, date-only (legacy Data Safety)
            TypePair(completableA, completableB, CalendarItemStatus.COMPLETED),   // тип сменился, ОБА completable
            TypePair(completableA, nonCompletable, CalendarItemStatus.ACTIVE),    // тип сменился, новый НЕ completable
            TypePair(nonCompletable, completableA, CalendarItemStatus.ACTIVE),    // тип сменился, старый НЕ completable
        ).forEach { pair ->
            val original = freshOriginal(pair.originalType, CalendarItemStatus.COMPLETED)
            assertEquals("originalStatus=COMPLETED, ${pair.originalType} → ${pair.newType}",
                pair.expected,
                sanitizeCalendarItemStatusForSave(true, original, pair.newType, CalendarItemStatus.COMPLETED))
        }

        // Правило 5: EDIT, originalItem.status != COMPLETED — новый transition,
        // решает ТОЛЬКО новый type (старый тип роли не играет).
        listOf(completableA, nonCompletable).forEach { originalType ->
            val original = freshOriginal(originalType, CalendarItemStatus.ACTIVE)
            assertEquals("originalStatus=ACTIVE ($originalType) → новый переход в $completableB: разрешён (новый тип completable)",
                CalendarItemStatus.COMPLETED,
                sanitizeCalendarItemStatusForSave(true, original, completableB, CalendarItemStatus.COMPLETED))
            assertEquals("originalStatus=ACTIVE ($originalType) → новый переход в $nonCompletable: блокируется (новый тип не completable)",
                CalendarItemStatus.ACTIVE,
                sanitizeCalendarItemStatusForSave(true, original, nonCompletable, CalendarItemStatus.COMPLETED))
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  ПОТЕРЯ НЕИЗВЕСТНОГО recurrenceRule ПРИ РЕДАКТИРОВАНИИ
    //
    //  INVARIANT: правка, НЕ относящаяся к recurrence, никогда не должна
    //  менять исходный recurrenceRule. Раньше save всегда писал
    //  recurrenceMode.toRRule() поверх сырого значения — RecurrenceMode.fromRule()
    //  сводит любую нераспознанную строку к NONE, поэтому ЛЮБАЯ правка (даже
    //  одного title) молча превращала неизвестный/будущий RRULE в null.
    //
    //  Тесты A/B/C/F ведут редактор через настоящий Compose UI (showEditor/
    //  replaceTitle/saveEditor — та же обвязка, что и в h_ordinaryActiveToActiveEdit)
    //  и не трогают recurrence-dropdown вовсе. Тесты D/E явно выбирают
    //  recurrence — сам Popup DropdownMenu недостижим для клика под Robolectric
    //  в этом проекте (см. ПРИМЕЧАНИЕ по тестам A-G выше), поэтому они дёргают
    //  sanitizeRecurrenceRuleForSave() напрямую — ту же функцию, что вызывает
    //  кнопка «Готово» этого экрана.
    // ═════════════════════════════════════════════════════════════

    // ─── A. Неизвестный RRULE + правка ТОЛЬКО title → сохраняется побайтово ───
    //  Критический тест: на коде ДО фикса FAILs, т.к. fromRule() даёт NONE и
    //  save пишет null поверх реального значения.

    @Test
    fun recurrenceA_unknownRule_editTitleOnly_preservedByteForByte() {
        val itemId = "cie-recurrence-unknown-a"
        val unknownRule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"
        persistCalendarItem(calendarItem(itemId, type = CalendarItemType.TASK, linkToContact = false, recurrenceRule = unknownRule))

        showEditor(itemId)
        replaceTitle("Original title", "Renamed, recurrence untouched")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Renamed, recurrence untouched" })
        assertEquals("неизвестный recurrenceRule обязан сохраниться побайтово после правки НЕ относящегося поля",
            unknownRule, itemInDb(itemId)?.recurrenceRule)
        assertEquals("…и в памяти", unknownRule, itemInMemory(itemId)?.recurrenceRule)
    }

    // ─── B. Другой неизвестный/будущий RRULE — тот же инвариант ───

    @Test
    fun recurrenceB_anotherUnknownRule_editUnrelatedField_preserved1to1() {
        val itemId = "cie-recurrence-unknown-b"
        val unknownRule = "FREQ=MONTHLY;BYDAY=1MO"
        persistCalendarItem(calendarItem(itemId, type = CalendarItemType.TASK, linkToContact = false, recurrenceRule = unknownRule))

        showEditor(itemId)
        replaceTitle("Original title", "Different unrelated edit")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Different unrelated edit" })
        assertEquals("другая неизвестная строка тоже сохраняется 1:1",
            unknownRule, itemInDb(itemId)?.recurrenceRule)
    }

    // ─── C. Известный WEEKLY, recurrence не трогали → сохраняется корректно ───

    @Test
    fun recurrenceC_knownWeekly_untouched_savesCorrectly() {
        val itemId = "cie-recurrence-known-weekly"
        persistCalendarItem(calendarItem(itemId, type = CalendarItemType.TASK, linkToContact = false, recurrenceRule = "FREQ=WEEKLY"))

        showEditor(itemId)
        replaceTitle("Original title", "Weekly stays weekly")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Weekly stays weekly" })
        assertEquals("известный FREQ=WEEKLY сохраняется как есть", "FREQ=WEEKLY", itemInDb(itemId)?.recurrenceRule)
    }

    // ─── D. Неизвестный → пользователь явно выбирает DAILY → сохраняется FREQ=DAILY ───

    @Test
    fun recurrenceD_unknownRule_explicitDailySelection_savesDaily() {
        val saved = sanitizeRecurrenceRuleForSave(
            recurrenceWasEdited = true,
            originalRecurrenceRule = "FREQ=MONTHLY;BYDAY=1MO",
            recurrenceMode = RecurrenceMode.DAILY
        )
        assertEquals("явный выбор DAILY заменяет неизвестное raw-значение", "FREQ=DAILY", saved)
    }

    // ─── E. Неизвестный → пользователь явно выбирает «Не повторять» → сохраняется null ───

    @Test
    fun recurrenceE_unknownRule_explicitNoneSelection_savesNull() {
        val saved = sanitizeRecurrenceRuleForSave(
            recurrenceWasEdited = true,
            originalRecurrenceRule = "FREQ=MONTHLY;BYDAY=1MO",
            recurrenceMode = RecurrenceMode.NONE
        )
        assertNull("явный выбор NONE после неизвестного значения — легитимный null", saved)
    }

    // ─── F. Backup/Room round-trip неизвестной строки без участия редактора ───

    @Test
    fun recurrenceF_unknownRule_roomRoundTrip_unchangedWithoutEditorInvolved() {
        val itemId = "cie-recurrence-roundtrip"
        val unknownRule = "FREQ=DAILY;COUNT=5;WKST=SU"
        persistCalendarItem(calendarItem(itemId, type = CalendarItemType.TASK, linkToContact = false, recurrenceRule = unknownRule))
        assertEquals("Room round-trip неизвестной строки (без открытия редактора)",
            unknownRule, itemInDb(itemId)?.recurrenceRule)
    }

    // ─── Точечный unit-тест sanitizer'а: не-edited ветка игнорирует parse ───
    //  (fromRule(unknown) сводится к NONE, но raw возвращается как есть —
    //  именно это отличает исправленный sanitizer от старого recurrenceMode.toRRule()).

    @Test
    fun recurrenceSanitizer_notEdited_unknownRule_preservesRawRegardlessOfParse() {
        val unknown = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"
        assertEquals("fromRule(unknown) == NONE, но raw сохраняется, а не toRRule()",
            unknown,
            sanitizeRecurrenceRuleForSave(recurrenceWasEdited = false, originalRecurrenceRule = unknown, recurrenceMode = RecurrenceMode.NONE))
    }

    // ═════════════════════════════════════════════════════════════
    //  BACKWARD COMPATIBILITY: legacy BIRTHDAY без recurrenceRule — редактор
    //  теперь ПОКАЗЫВАЕТ dropdown как «Каждый год» (effectiveRecurrenceMode), но
    //  правка, не тронувшая recurrence, НЕ должна молча записать FREQ=YEARLY —
    //  это compatibility interpretation для чтения, а не silent migration записи.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun recurrenceDataSafety_legacyBirthdayWithoutRule_editTitleOnly_recurrenceRuleStaysNull() {
        val itemId = "cie-legacy-birthday-recurrence"
        persist(contact()) // привязка к контакту: форма не задаёт вопрос «чья дата» (вопрос — в CalendarDateWithoutContactPromptTest)
        val original = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, linkToContact = true)
            .copy(recurrenceRule = null)
        persistCalendarItem(original)

        showEditor(itemId)
        replaceTitle("Original title", "Legacy birthday, recurrence untouched")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Legacy birthday, recurrence untouched" })
        assertNull("legacy ДР без recurrenceRule: правка НЕ относящегося поля не должна молча записать FREQ=YEARLY",
            itemInDb(itemId)?.recurrenceRule)
    }

    // ═════════════════════════════════════════════════════════════
    //  SCHEMA-FOUNDATION v19: recurrenceExplicitlySet + lastCompletedOccurrenceDate
    //  persistence integrity через настоящий editor save-path (sanitizeRecurrence*
    //  функции + persistCalendarItemEdit). Completion advancement НЕ реализуется —
    //  только то, что новые поля correctly проходят через реальный save/read.
    // ═════════════════════════════════════════════════════════════

    // ─── G. legacy Birthday (null+false), правка только title -> остаётся
    // null+false, effective recurrence всё ещё YEARLY (тот же сценарий, что
    // recurrenceDataSafety выше, + explicit проверка флага) ───

    @Test
    fun g_legacyBirthday_editTitleOnly_staysNullAndFalse_effectiveYearly() {
        val itemId = "cie-schema-g-legacy"
        persist(contact()) // привязка к контакту: форма не задаёт вопрос «чья дата» (вопрос — в CalendarDateWithoutContactPromptTest)
        val original = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, linkToContact = true)
            .copy(recurrenceRule = null, recurrenceExplicitlySet = false)
        persistCalendarItem(original)

        showEditor(itemId)
        replaceTitle("Original title", "Legacy birthday, only title changed")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Legacy birthday, only title changed" })
        val saved = itemInDb(itemId)!!
        assertNull("rule остаётся null", saved.recurrenceRule)
        assertFalse("explicit остаётся false — правка title не подтверждает recurrence", saved.recurrenceExplicitlySet)
        assertEquals("effective recurrence всё ещё YEARLY (legacy fallback)",
            RecurrenceMode.YEARLY, effectiveRecurrenceMode(saved.type, saved.recurrenceRule, saved.recurrenceExplicitlySet))
    }

    // ─── D. ГЛАВНЫЙ regression test новой модели: legacy BIRTHDAY (null+false)
    // -> пользователь явно выбирает «Не повторять» -> save -> null+true -> при
    // повторном чтении effective mode = NONE, не YEARLY. DropdownMenu недоступен
    // для клика под Robolectric (см. ПРИМЕЧАНИЕ по тестам A-G выше) — дёргаем ту
    // же sanitizeRecurrenceRuleForSave/sanitizeRecurrenceExplicitFlagForSave и
    // persistCalendarItemEdit, что вызывает сама кнопка «Готово». ───

    @Test
    fun d_legacyBirthday_explicitlyChoosesNone_savesNullTrue_effectiveModeBecomesNone() {
        val itemId = "cie-schema-d-explicit-none"
        persist(contact()) // привязка к контакту: форма не задаёт вопрос «чья дата» (вопрос — в CalendarDateWithoutContactPromptTest)
        val original = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, linkToContact = true)
            .copy(recurrenceRule = null, recurrenceExplicitlySet = false)
        persistCalendarItem(original)
        assertEquals("предусловие: до правки effective = YEARLY (legacy)",
            RecurrenceMode.YEARLY, effectiveRecurrenceMode(original.type, original.recurrenceRule, original.recurrenceExplicitlySet))

        // Пользователь открывает редактор (recurrenceWasEdited=false изначально,
        // dropdown показывает «Каждый год» — effectiveRecurrenceMode), затем
        // ЯВНО выбирает «Не повторять»: recurrenceMode=NONE, recurrenceWasEdited=true.
        val recurrenceWasEdited = true
        val chosenMode = RecurrenceMode.NONE
        val savedRule = sanitizeRecurrenceRuleForSave(recurrenceWasEdited, original.recurrenceRule, chosenMode)
        val savedExplicit = sanitizeRecurrenceExplicitFlagForSave(isEditMode = true, originalItem = original, recurrenceWasEdited = recurrenceWasEdited)
        val edited = original.copy(recurrenceRule = savedRule, recurrenceExplicitlySet = savedExplicit)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.recurrenceExplicitlySet == true })
        val saved = itemInDb(itemId)!!
        assertNull("rule = null (NONE.toRRule())", saved.recurrenceRule)
        assertTrue("explicit = true — пользователь явно подтвердил NONE", saved.recurrenceExplicitlySet)
        assertEquals("effective mode при повторном чтении из Room = NONE, не YEARLY",
            RecurrenceMode.NONE, effectiveRecurrenceMode(saved.type, saved.recurrenceRule, saved.recurrenceExplicitlySet))
    }

    // ─── E. Закрыть/переоткрыть editor: explicit NONE остаётся NONE (dropdown
    // при повторном открытии инициализируется effectiveRecurrenceMode() и не
    // должен снова показать «Каждый год») ───

    @Test
    fun e_explicitNoneBirthday_reopenEditor_stillShowsNone() {
        val itemId = "cie-schema-e-reopen"
        persist(contact()) // привязка к контакту: форма не задаёт вопрос «чья дата» (вопрос — в CalendarDateWithoutContactPromptTest)
        val explicitNone = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, linkToContact = true)
            .copy(recurrenceRule = null, recurrenceExplicitlySet = true)
        persistCalendarItem(explicitNone)

        // "Переоткрытие" — свежее чтение из памяти/Room + повторный расчёт
        // начального recurrenceMode dropdown'а той же функцией, что и реальный
        // Composable (originalItem?.recurrenceRule, originalItem?.recurrenceExplicitlySet).
        val reread = itemInDb(itemId)!!
        val dropdownInitialMode = effectiveRecurrenceMode(reread.type, reread.recurrenceRule, reread.recurrenceExplicitlySet)
        assertEquals("dropdown при переоткрытии показывает NONE, не откатывается к YEARLY fallback",
            RecurrenceMode.NONE, dropdownInitialMode)
    }

    // ─── F. Правка ТОЛЬКО title у уже explicit-NONE Birthday: флаг=true сохраняется ───

    @Test
    fun f_explicitNoneBirthday_editTitleOnly_flagStaysTrue() {
        val itemId = "cie-schema-f-explicit-title-edit"
        persist(contact()) // привязка к контакту: форма не задаёт вопрос «чья дата» (вопрос — в CalendarDateWithoutContactPromptTest)
        val original = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, linkToContact = true)
            .copy(recurrenceRule = null, recurrenceExplicitlySet = true)
        persistCalendarItem(original)

        showEditor(itemId)
        replaceTitle("Original title", "Explicit-NONE birthday, only title changed")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Explicit-NONE birthday, only title changed" })
        val saved = itemInDb(itemId)!!
        assertNull(saved.recurrenceRule)
        assertTrue("explicit=true не сбрасывается правкой НЕ относящегося поля", saved.recurrenceExplicitlySet)
    }

    // ─── H. Неизвестный raw recurrence + новые поля: rule не теряется, не
    // получает annual fallback, explicit не трогается правкой unrelated поля ───

    @Test
    fun h_unknownRawRecurrence_combinedWithNewFields_notLostNotAutoPromoted() {
        val itemId = "cie-schema-h-unknown-rule"
        val unknown = "FREQ=MONTHLY;BYDAY=1MO"
        persist(contact()) // привязка к контакту: форма не задаёт вопрос «чья дата» (вопрос — в CalendarDateWithoutContactPromptTest)
        val original = calendarItem(itemId, type = CalendarItemType.BIRTHDAY, linkToContact = true)
            .copy(recurrenceRule = unknown, recurrenceExplicitlySet = false)
        persistCalendarItem(original)

        showEditor(itemId)
        replaceTitle("Original title", "Unknown rule, unrelated edit")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Unknown rule, unrelated edit" })
        val saved = itemInDb(itemId)!!
        assertEquals("неизвестная строка сохраняется побайтово", unknown, saved.recurrenceRule)
        assertFalse("explicit не трогается правкой unrelated поля", saved.recurrenceExplicitlySet)
        // Правило 1 policy: непустой (пусть и нераспознанный) rule имеет приоритет
        // независимо от explicit — НЕ annual fallback, а fromRule()'s собственный NONE.
        assertEquals(RecurrenceMode.NONE, effectiveRecurrenceMode(saved.type, saved.recurrenceRule, saved.recurrenceExplicitlySet))
    }

    // ─── I. Новый CALL без recurrence (полный UI create-flow) -> null + true ───

    @Test
    fun i_newCall_createdWithoutTouchingRecurrence_savesNullTrue() {
        compose.setContent { AppleAppTheme(darkTheme = false) { CalendarItemEditScreen(calendarItemId = null, onNavigateBack = {}) } }
        // Явно выбираем тип CALL (форма по умолчанию открывается с MEETING) —
        // recurrence-dropdown не трогаем вовсе. startDate уже проставлен на
        // сегодня по умолчанию, title не обязателен (enabled = startDate.isNotBlank()).
        clickAt(CalendarItemType.CALL.label(ctx))
        clickAt(ctx.getString(R.string.common_done))

        assertTrue("новое событие CALL должно появиться в Room",
            pollUntil(5_000) { AppStateStore.calendarItems.any { it.type == CalendarItemType.CALL } })
        val created = AppStateStore.calendarItems.filter { it.type == CalendarItemType.CALL }
            .maxByOrNull { it.createdAt } ?: fail("новый CALL не создан") as Nothing
        assertNull("новый CALL без recurrence -> rule null", created.recurrenceRule)
        assertTrue("modern new row -> explicit=true даже без recurrence", created.recurrenceExplicitlySet)
    }

    // ─── J. Новый BIRTHDAY (полный UI create-flow, тип выбирается явно) ->
    // YEARLY rule + true ───

    @Test
    fun j_newBirthday_pickTypeInEditor_savesYearlyRuleAndExplicitTrue() {
        // Контакт выбран заранее (prefill): форма не задаёт вопрос «чья дата» — он проверяется в
        // CalendarDateWithoutContactPromptTest; здесь проверяются recurrence-данные.
        persist(contact())
        compose.setContent { AppleAppTheme(darkTheme = false) { CalendarItemEditScreen(calendarItemId = null, onNavigateBack = {}, prefillContactId = contactId) } }
        // Тип "День рождения" — пилюля в секции "Основное". Клик по её тексту
        // переключает type и (production-логика, не тронута этим batch'ем)
        // авто-выставляет recurrenceMode=YEARLY, recurrenceWasEdited=true.
        clickAt(CalendarItemType.BIRTHDAY.label(ctx))
        clickAt(ctx.getString(R.string.common_done))

        assertTrue("новое событие должно появиться в Room",
            pollUntil(5_000) { AppStateStore.calendarItems.any { it.type == CalendarItemType.BIRTHDAY } })
        val created = AppStateStore.calendarItems.filter { it.type == CalendarItemType.BIRTHDAY }
            .maxByOrNull { it.createdAt } ?: fail("новый BIRTHDAY не создан") as Nothing
        assertEquals("FREQ=YEARLY", created.recurrenceRule)
        assertTrue("modern new row -> explicit=true", created.recurrenceExplicitlySet)
    }

    // ─── K. Новый ANNIVERSARY/NAMEDAY через GiftsTab-creation path: зеркалируем
    // ТОЧНО ту же логику, что реальный confirm-handler GiftsTab.kt строит (тот
    // production-путь — Compose ModalBottomSheet, отдельная героика для
    // interaction-теста здесь избыточна) — проверяем фактическое сохранённое
    // состояние через ту же persistCalendarItemEdit-независимую запись в Room. ───

    @Test
    fun k_newAnniversaryAndNameday_viaGiftsTabCreationLogic_savesYearlyRuleAndExplicitTrue() {
        listOf(CalendarItemType.ANNIVERSARY, CalendarItemType.NAMEDAY).forEach { dType ->
            val itemId = "cie-schema-k-${dType.name}"
            // Точное зеркало GiftsTab.kt's onConfirm: recurrenceRule = YEARLY для
            // BIRTHDAY/ANNIVERSARY/NAMEDAY, recurrenceExplicitlySet = true (modern row).
            val item = CalendarItem(
                id = itemId, title = dType.name, type = dType,
                startDate = "2026-06-15", isAllDay = true,
                status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
                recurrenceRule = RecurrenceMode.YEARLY.toRRule(),
                recurrenceExplicitlySet = true,
                createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
            )
            persistCalendarItem(item)

            val saved = itemInDb(itemId)!!
            assertEquals("$dType: FREQ=YEARLY", "FREQ=YEARLY", saved.recurrenceRule)
            assertTrue("$dType: explicit=true", saved.recurrenceExplicitlySet)
        }
    }

    // ─── M. lastCompletedOccurrenceDate: правка unrelated поля через НАСТОЯЩИЙ
    // editor UI не должна сбросить его в null (критический regression test —
    // newItem в CalendarItemEditScreen конструируется НЕ через .copy(), а
    // заново; поле не редактируется формой и обязано явно переноситься из
    // originalItem, тот же паттерн, что colorKey — см. У60) ───

    @Test
    fun m_lastCompletedOccurrenceDate_survivesUnrelatedEditThroughRealEditor() {
        val itemId = "cie-schema-m-last-completed"
        val original = calendarItem(itemId, type = CalendarItemType.MEETING, linkToContact = false)
            .copy(lastCompletedOccurrenceDate = "2026-02-10")
        persistCalendarItem(original)
        assertEquals("предусловие", "2026-02-10", itemInDb(itemId)?.lastCompletedOccurrenceDate)

        showEditor(itemId)
        replaceTitle("Original title", "Unrelated edit, completion date must survive")
        saveEditor()

        assertTrue("запись в Room не дождалась завершения",
            pollUntil(5_000) { itemInDb(itemId)?.title == "Unrelated edit, completion date must survive" })
        assertEquals("lastCompletedOccurrenceDate НЕ должен сброситься в null правкой unrelated поля",
            "2026-02-10", itemInDb(itemId)?.lastCompletedOccurrenceDate)
    }

    // ═════════════════════════════════════════════════════════════
    //  END-TO-END RECURRING OCCURRENCE COMPLETION
    //
    //  INVARIANT: для effective recurring (DAILY/WEEKLY/MONTHLY/YEARLY) «Готово»
    //  завершает ОДНО occurrence — status остаётся ACTIVE,
    //  lastCompletedOccurrenceDate = targetOccurrenceDate, будильники текущего
    //  occurrence сняты и переставлены на следующее pending, Upcoming
    //  продвигается. Для effective NONE — прежняя семантика (status=COMPLETED),
    //  не regression (тест K). Единая точка входа — CalendarCompletion.complete()
    //  — используется ОДИНАКОВО кнопкой на экране, редактором и уведомлением.
    //
    //  runBlocking { CalendarCompletion.complete(...).await() } безопасен под
    //  Robolectric здесь: весь путь (markCalendarItemCompleted -> enqueueWrite,
    //  rescheduleReminders -> AlarmManager) идёт по Dispatchers.IO, НЕ
    //  Dispatchers.Main — тот же паттерн, что уже используют persist()/
    //  lastContactInDb() в этом самом файле (runBlocking { ...await() } на
    //  Deferred из enqueueWrite), не тот дедлок-класс, что §57 (withContext(Main)).
    // ═════════════════════════════════════════════════════════════

    private fun recurringItem(
        id: String,
        startDate: java.time.LocalDate,
        recurrenceRule: String?,
        recurrenceExplicitlySet: Boolean = false,
        lastCompletedOccurrenceDate: String? = null,
        type: CalendarItemType = CalendarItemType.CALL,
        status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        reminders: List<ReminderRule> = emptyList(),
        linkToContact: Boolean = true,
    ) = CalendarItem(
        id = id, title = "Original title", type = type,
        startDate = startDate.toString(), startTime = "10:00", isAllDay = false,
        status = status, importance = ImportanceLevel.NORMAL,
        links = if (linkToContact) listOf(CalendarItemLink("$id-l1", id, CalendarTargetType.CONTACT, contactId)) else emptyList(),
        reminders = reminders,
        recurrenceRule = recurrenceRule,
        recurrenceExplicitlySet = recurrenceExplicitlySet,
        lastCompletedOccurrenceDate = lastCompletedOccurrenceDate,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    private fun eventAlarmsFor(itemId: String) =
        shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name &&
                shadowOf(it.operation).savedIntent.getStringExtra("calendarItemId") == itemId
        }

    private fun epochMillisOf(dt: java.time.LocalDateTime): Long =
        dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun completeSync(item: CalendarItem, target: java.time.LocalDate?, oldReminders: List<ReminderRule>? = null): Boolean =
        runBlocking { CalendarCompletion.complete(ctx, item, target, oldReminders).await() }

    // ─── A. WEEKLY completion ───

    @Test
    fun a_weeklyCompletion_statusActive_lastCompletedSet_contactUpdated_effectiveDateAdvances() {
        persist(contact())
        val itemId = "rc-a-weekly"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4) // anchored on today's own weekday
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
        persistCalendarItem(item)
        assertEquals("предусловие: today — occurrence текущей серии", today, item.pendingOccurrenceDate())

        assertTrue("complete() обязан вернуть true", completeSync(item, today))

        val saved = itemInDb(itemId)!!
        assertEquals("status остаётся ACTIVE — серия не завершена целиком", CalendarItemStatus.ACTIVE, saved.status)
        assertEquals("lastCompletedOccurrenceDate == today", today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("contact.lastContactDate == today (CALL — interaction)", today.toString(), lastContactInDb())
        assertEquals("effectiveDate продвинулась на следующее occurrence",
            today.plusWeeks(1).toString(), saved.effectiveDate(today))
    }

    // ─── B. Scheduler advances alarm to next occurrence, not completed one ───

    @Test
    fun b_scheduler_afterCompletion_alarmRefersToNextOccurrence_notCompletedOne() {
        val itemId = "rc-b-scheduler"
        val today = java.time.LocalDate.now()
        // anchor на ЗАВТРАШНЕМ дне недели (не сегодняшнем): «завтра 10:00»
        // гарантированно в будущем относительно «сейчас» НЕЗАВИСИМО от того, в
        // какое время суток реально выполняется тест (в отличие от «сегодня
        // 10:00», которое уже могло пройти к моменту запуска — та же ловушка,
        // что и в RecurrenceReminderSchedulingTest.weeklyA).
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule = ReminderRule(id = "rc-b-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(rule), linkToContact = false)
        persistCalendarItem(item)
        NotificationScheduler.scheduleReminder(ctx, item, rule)
        val alarmBefore = eventAlarmsFor(itemId).singleOrNull()
            ?: fail("предусловие: alarm на текущее occurrence должен стоять") as Nothing
        assertEquals(epochMillisOf(occurrence.atTime(10, 0)), alarmBefore.triggerAtTime)

        assertTrue(completeSync(item, occurrence))

        val alarmsAfter = eventAlarmsFor(itemId)
        assertEquals("ровно один alarm на следующее occurrence", 1, alarmsAfter.size)
        assertEquals("alarm относится к next occurrence, не к завершённому",
            epochMillisOf(occurrence.plusWeeks(1).atTime(10, 0)), alarmsAfter.single().triggerAtTime)
    }

    // ─── C. DOUBLE DONE — CRITICAL adversarial regression test ───

    @Test
    fun c_doubleDone_sameTarget_doesNotDoubleAdvance_secondCallIsNoOp() {
        persist(contact())
        val itemId = "rc-c-double-done"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
        persistCalendarItem(item)

        // Оба вызова несут ОДНУ и ту же target-дату (как «два клика до
        // recomposition», requirement 4/5) — используем ТОТ ЖЕ item-снимок оба раза.
        assertTrue("первый вызов обязан завершить occurrence", completeSync(item, today))
        assertEquals(today.toString(), itemInDb(itemId)?.lastCompletedOccurrenceDate)

        assertTrue("второй вызов — идемпотентный no-op, но НЕ ошибка", completeSync(item, today))

        val saved = itemInDb(itemId)!!
        assertEquals("lastCompletedOccurrenceDate остаётся target — НЕ продвинута дальше (не Sep23)",
            today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("next pending occurrence — ровно следующее занятие, не «ещё следующее»",
            today.plusWeeks(1), saved.pendingOccurrenceDate())
    }

    /** Дополняет [c_doubleDone_sameTarget_doesNotDoubleAdvance_secondCallIsNoOp]:
     *  сам idempotency-guard (requirement 8) — target уже <= lastCompletedOccurrenceDate
     *  у СВЕЖЕ прочитанного item (не старый снимок), симулирует повторную
     *  доставку/гонку ПОСЛЕ того, как запись уже отражает завершение. */
    @Test
    fun c_idempotencyGuard_alreadyCompletedTarget_freshItemRead_stillNoOp() {
        persist(contact())
        val itemId = "rc-c-idempotency-guard"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val alreadyCompleted = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(),
            type = CalendarItemType.CALL, lastCompletedOccurrenceDate = today.toString())
        persistCalendarItem(alreadyCompleted)

        val result = completeSync(alreadyCompleted, today)
        assertTrue("идемпотентный no-op — не ошибка", result)

        val saved = itemInDb(itemId)!!
        assertEquals("lastCompletedOccurrenceDate НЕ продвинута дальше", today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals(CalendarItemStatus.ACTIVE, saved.status)
    }

    // ─── D. Duplicate notification action delivery ───

    @Test
    fun d_duplicateNotificationDelivery_completesOnlyOnce() {
        persist(contact())
        val itemId = "rc-d-duplicate-delivery"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
        persistCalendarItem(item)

        val first = runBlocking { NotificationReceiver.completeCalendarItem(ctx, itemId, today) }
        val second = runBlocking { NotificationReceiver.completeCalendarItem(ctx, itemId, today) }
        assertTrue(first)
        assertTrue(second)

        assertEquals("доставка дважды не продвинула дальше today",
            today.toString(), itemInDb(itemId)?.lastCompletedOccurrenceDate)
    }

    // ─── E. Delayed notification action — uses embedded identity, not recompute ───

    @Test
    fun e_delayedNotificationAction_usesEmbeddedOccurrenceIdentity_notRecomputedAtClickTime() {
        persist(contact())
        val itemId = "rc-e-delayed-action"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        // Симулируем «между показом и кликом что-то ДРУГОЕ уже продвинуло серию»:
        // lastCompletedOccurrenceDate=today уже стоит. Если бы receiver вычислял
        // occurrence ЗАНОВО на момент клика, pendingOccurrenceDate() сейчас дал бы
        // today+7 — «отложенное» уведомление завершило бы НЕ ту дату, на которую
        // оно реально было показано.
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(),
            type = CalendarItemType.CALL, lastCompletedOccurrenceDate = today.toString())
        persistCalendarItem(item)
        assertEquals("sanity: пересчёт СЕЙЧАС дал бы УЖЕ следующее occurrence — не то, что было в уведомлении",
            today.plusWeeks(1), item.pendingOccurrenceDate())

        // «Отложенное» уведомление несёт identity = today (occurrence, для
        // которого оно было реально показано, ДО стороннего завершения).
        val result = runBlocking { NotificationReceiver.completeCalendarItem(ctx, itemId, today) }
        assertTrue("идемпотентный no-op для уже завершённого today — не ошибка", result)

        assertEquals("lastCompletedOccurrenceDate остаётся today — НЕ продвинута на today+7 отложенным действием",
            today.toString(), itemInDb(itemId)?.lastCompletedOccurrenceDate)
    }

    // ─── F. Stale notification after recurrence edit ───

    @Test
    fun f_staleNotificationAfterRecurrenceEdit_targetNoLongerOccurrence_noOp() {
        persist(contact())
        val itemId = "rc-f-stale-after-edit"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
        persistCalendarItem(original)
        assertTrue("предусловие: today — occurrence старой WEEKLY-серии",
            RecurrenceCalculator.occursOn(anchor, RecurrenceMode.WEEKLY, today))

        // Пользователь поменял recurrence на YEARLY с anchor'ом далеко от today —
        // today гарантированно больше не occurrence новой серии (100 дней всегда
        // меняет month/day в пределах того же года).
        val newAnchor = today.plusDays(100)
        val edited = original.copy(recurrenceRule = RecurrenceMode.YEARLY.toRRule(), recurrenceExplicitlySet = true,
            startDate = newAnchor.toString())
        runBlocking { AppStateStore.updateCalendarItem(original, edited).await() }
        assertFalse("предусловие: today НЕ occurrence новой YEARLY-серии",
            RecurrenceCalculator.occursOn(newAnchor, RecurrenceMode.YEARLY, today))

        val result = runBlocking { NotificationReceiver.completeCalendarItem(ctx, itemId, today) }
        assertFalse("stale identity после смены recurrence -> no-op", result)
        assertNull("lastCompletedOccurrenceDate НЕ должен быть установлен", itemInDb(itemId)?.lastCompletedOccurrenceDate)
        assertEquals("status тоже не тронут", CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
    }

    // ─── G. DAILY ───

    @Test
    fun g_dailyCompletion_todayCompleted_nextPendingIsTomorrow() {
        val itemId = "rc-g-daily"
        val today = java.time.LocalDate.now()
        val anchor = today.minusDays(10)
        val item = recurringItem(itemId, anchor, RecurrenceMode.DAILY.toRRule(), type = CalendarItemType.TASK, linkToContact = false)
        persistCalendarItem(item)

        assertTrue(completeSync(item, today))
        val saved = itemInDb(itemId)!!
        assertEquals(today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("next pending = tomorrow", today.plusDays(1), saved.pendingOccurrenceDate())
    }

    // ─── H. MONTHLY 31 anti-drift ───

    @Test
    fun h_monthly31AntiDrift_completeFeb28_nextPendingIsMar31NotMar28() {
        val itemId = "rc-h-monthly31"
        val anchor = java.time.LocalDate.of(2026, 1, 31)
        val feb28 = java.time.LocalDate.of(2026, 2, 28)
        val item = recurringItem(itemId, anchor, RecurrenceMode.MONTHLY.toRRule(), type = CalendarItemType.TASK, linkToContact = false)
        persistCalendarItem(item)
        assertTrue("предусловие: Feb28 — occurrence Jan31-серии", RecurrenceCalculator.occursOn(anchor, RecurrenceMode.MONTHLY, feb28))

        assertTrue(completeSync(item, feb28))
        val saved = itemInDb(itemId)!!
        assertEquals(feb28.toString(), saved.lastCompletedOccurrenceDate)
        val next = saved.pendingOccurrenceDate(feb28.plusDays(1))
        assertEquals("next pending = Mar31, anchor НЕ продрейфовал на Mar28",
            java.time.LocalDate.of(2026, 3, 31), next)
    }

    // ─── I. YEARLY Feb29 anti-drift ───

    @Test
    fun i_yearlyFeb29AntiDrift_completeMar1NonLeapYear_nextOccurrenceCorrect_noDrift() {
        val itemId = "rc-i-yearly-feb29"
        val anchor = java.time.LocalDate.of(2024, 2, 29)
        val mar1_2025 = java.time.LocalDate.of(2025, 3, 1)
        val item = recurringItem(itemId, anchor, RecurrenceMode.YEARLY.toRRule(), type = CalendarItemType.TASK, linkToContact = false)
        persistCalendarItem(item)
        assertTrue(RecurrenceCalculator.occursOn(anchor, RecurrenceMode.YEARLY, mar1_2025))

        assertTrue(completeSync(item, mar1_2025))
        val saved = itemInDb(itemId)!!
        assertEquals(mar1_2025.toString(), saved.lastCompletedOccurrenceDate)

        assertEquals("2026 — не високосный, Mar1", java.time.LocalDate.of(2026, 3, 1),
            saved.pendingOccurrenceDate(mar1_2025.plusDays(1)))
        val afterMar1_2026 = saved.copy(lastCompletedOccurrenceDate = "2026-03-01")
        assertEquals("2027 — не високосный, Mar1", java.time.LocalDate.of(2027, 3, 1),
            afterMar1_2026.pendingOccurrenceDate(java.time.LocalDate.of(2026, 3, 2)))
        val afterMar1_2027 = afterMar1_2026.copy(lastCompletedOccurrenceDate = "2027-03-01")
        assertEquals("2028 — високосный: anchor Feb29 корректно ВОЗВРАЩАЕТСЯ (не застряло на Mar1)",
            java.time.LocalDate.of(2028, 2, 29),
            afterMar1_2027.pendingOccurrenceDate(java.time.LocalDate.of(2027, 3, 2)))
    }

    // ─── J. MULTIPLE REMINDERS ───

    @Test
    fun j_multipleReminders_oldAlarmsCancelled_newOnesForNextOccurrence_noDuplicates() {
        val itemId = "rc-j-multi-reminders"
        val today = java.time.LocalDate.now()
        // anchor на ЗАВТРАШНЕМ дне недели — «завтра 10:00» и «завтра 09:00»
        // (offset -1ч) гарантированно в будущем независимо от реального времени
        // суток запуска теста (та же причина, что и в тесте B).
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule1 = ReminderRule(id = "rc-j-r1", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val rule2 = ReminderRule(id = "rc-j-r2", calendarItemId = itemId, reminderType = ReminderType.BEFORE,
            offsetValue = 1, offsetUnit = ReminderOffsetUnit.HOURS)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(rule1, rule2), linkToContact = false)
        persistCalendarItem(item)
        NotificationScheduler.scheduleReminder(ctx, item, rule1)
        NotificationScheduler.scheduleReminder(ctx, item, rule2)
        assertEquals("предусловие: 2 alarm'а на текущее occurrence", 2, eventAlarmsFor(itemId).size)

        assertTrue(completeSync(item, occurrence))

        val alarmsAfter = eventAlarmsFor(itemId)
        assertEquals("ровно 2 alarm'а на следующее occurrence, без дублей", 2, alarmsAfter.size)
        val nextBase = occurrence.plusWeeks(1).atTime(10, 0)
        val expectedMillis = setOf(epochMillisOf(nextBase), epochMillisOf(nextBase.minusHours(1)))
        assertEquals(expectedMillis, alarmsAfter.map { it.triggerAtTime }.toSet())
    }

    // ─── K. NON-RECURRING REGRESSION ───

    @Test
    fun k_nonRecurringCall_done_statusCompleted_lastContactToday_remindersGone_lastCompletedOccurrenceDateUnused() {
        persist(contact())
        val itemId = "rc-k-none-regression"
        val rule = ReminderRule(id = "rc-k-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val item = recurringItem(itemId, java.time.LocalDate.now().plusDays(5), recurrenceRule = null,
            type = CalendarItemType.CALL, reminders = listOf(rule))
        persistCalendarItem(item)
        NotificationScheduler.scheduleReminder(ctx, item, rule)
        assertEquals(1, eventAlarmsFor(itemId).size)

        assertTrue(completeSync(item, null))

        val saved = itemInDb(itemId)!!
        assertEquals(CalendarItemStatus.COMPLETED, saved.status)
        assertEquals(java.time.LocalDate.now().toString(), lastContactInDb())
        assertTrue("будильники сняты", eventAlarmsFor(itemId).isEmpty())
        assertNull("lastCompletedOccurrenceDate не используется для NONE", saved.lastCompletedOccurrenceDate)
    }

    // ─── L. EXPLICIT NONE ───
    // Задание называет «Birthday», но BIRTHDAY (и весь LEGACY_ANNUAL_TYPES) —
    // DATE_ONLY_TYPES, offersCompletion всегда false (см. CalendarCompletion) —
    // это pre-existing поведение, не относящееся к этой задаче, и completion до
    // такого типа физически не доходит. Тестируем ТУ ЖЕ effective-NONE ветку на
    // completable-типе, не завязываясь на несвязанный UI-гейт.

    @Test
    fun l_explicitNone_completesAsOrdinaryWholeItem_notOccurrenceBased() {
        val itemId = "rc-l-explicit-none"
        val item = recurringItem(itemId, java.time.LocalDate.of(2020, 6, 15), recurrenceRule = null,
            recurrenceExplicitlySet = true, type = CalendarItemType.MEETING, linkToContact = false)
        persistCalendarItem(item)
        assertEquals(RecurrenceMode.NONE, effectiveRecurrenceMode(item.type, item.recurrenceRule, item.recurrenceExplicitlySet))

        assertTrue(completeSync(item, null))

        val saved = itemInDb(itemId)!!
        assertEquals("explicit NONE -> whole-item COMPLETED, не occurrence-based", CalendarItemStatus.COMPLETED, saved.status)
        assertNull("lastCompletedOccurrenceDate не используется для NONE", saved.lastCompletedOccurrenceDate)
    }

    // ─── M. LEGACY BIRTHDAY — см. NEW FINDING в отчёте ───
    // Тот же нюанс, что и L: BIRTHDAY — DATE_ONLY_TYPES, offersCompletion
    // всегда false, НЕЗАВИСИМО от effective recurrence. YEARLY-fallback
    // механизм (effectiveRecurrenceMode) сам по себе корректен (проверено на
    // РЕАЛЬНОМ BIRTHDAY-объекте здесь), но occurrence-completion для date-only
    // типов сегодня недостижима через единую точку входа CalendarCompletion —
    // это pre-existing, намеренное поведение (даты — не «дела»), НЕ regression.

    @Test
    fun m_legacyBirthday_yearlyFallbackMechanismCorrect_butGatedByOffersCompletion_documentedFinding() {
        val itemId = "rc-m-legacy-birthday"
        val anchor = java.time.LocalDate.of(1990, 6, 15)
        val item = recurringItem(itemId, anchor, recurrenceRule = null, recurrenceExplicitlySet = false,
            type = CalendarItemType.BIRTHDAY, linkToContact = false)
        persistCalendarItem(item)

        assertEquals("механизм: legacy BIRTHDAY без rule -> effective YEARLY",
            RecurrenceMode.YEARLY, effectiveRecurrenceMode(item.type, item.recurrenceRule, item.recurrenceExplicitlySet))

        val result = completeSync(item, java.time.LocalDate.now())
        assertFalse("BIRTHDAY не предлагает completion (DATE_ONLY_TYPES) — occurrence-completion недостижима для date-only типов", result)
        assertNull("lastCompletedOccurrenceDate не тронут", itemInDb(itemId)?.lastCompletedOccurrenceDate)
    }

    // ─── N. EDITOR COMPLETION: одновременная правка title/reminder/status ───

    @Test
    fun n_editorCompletion_recurringCall_simultaneousTitleReminderStatusEdit_allSavedCorrectly() {
        persist(contact())
        val itemId = "rc-n-editor-completion"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val oldRule = ReminderRule(id = "rc-n-old-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(oldRule))
        persistCalendarItem(original)
        NotificationScheduler.scheduleReminder(ctx, original, oldRule)
        assertEquals(1, eventAlarmsFor(itemId).size)

        val newRule = ReminderRule(id = "rc-n-new-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val edited = original.copy(title = "New title from editor", reminders = listOf(newRule), status = CalendarItemStatus.COMPLETED)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue("итог не дождались", pollUntil(5_000) { itemInDb(itemId)?.title == "New title from editor" })
        val saved = itemInDb(itemId)!!
        assertEquals("title сохранён", "New title from editor", saved.title)
        assertEquals("series остаётся ACTIVE — recurring, не целиком COMPLETED", CalendarItemStatus.ACTIVE, saved.status)
        assertEquals("target occurrence завершено", today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("contact side effect ровно один раз", today.toString(), lastContactInDb())

        assertTrue("alarms переставлены", pollUntil(5_000) { eventAlarmsFor(itemId).size == 1 })
        val nextAlarm = eventAlarmsFor(itemId).single()
        assertEquals("новый alarm — на следующее occurrence, по новому reminder rule",
            epochMillisOf(today.plusWeeks(1).atTime(10, 0)), nextAlarm.triggerAtTime)
    }

    // ─── O. COMPLETED/CANCELLED SERIES ───

    @Test
    fun o_completedSeries_occurrenceCompletionAction_isNoOp() {
        val itemId = "rc-o-completed-series"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            status = CalendarItemStatus.COMPLETED, linkToContact = false)
        persistCalendarItem(item)

        val result = completeSync(item, today)
        assertTrue("уже COMPLETED — идемпотентно true, дальше состояние не меняется", result)
        assertNull(itemInDb(itemId)?.lastCompletedOccurrenceDate)
    }

    @Test
    fun o_cancelledSeries_occurrenceCompletionAction_isNoOp() {
        val itemId = "rc-o-cancelled-series"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            status = CalendarItemStatus.CANCELLED, linkToContact = false)
        persistCalendarItem(item)

        val result = completeSync(item, today)
        assertFalse("CANCELLED -> no-op, complete() возвращает false", result)
        assertNull(itemInDb(itemId)?.lastCompletedOccurrenceDate)
        assertEquals(CalendarItemStatus.CANCELLED, itemInDb(itemId)?.status)
    }

    // ═════════════════════════════════════════════════════════════
    //  STALE OLDER TARGET — обязательный regression-тест (coverage gap,
    //  найден 2026-09-17 при негативном пробе idempotency-guard'а: удаление
    //  guard'а `target <= lastCompletedOccurrenceDate` НЕ ломало ни
    //  c_doubleDone, ни c_idempotencyGuard — оба используют target ==
    //  lastCompletedOccurrenceDate, и перезапись ТЕМ ЖЕ значением
    //  неотличима от no-op. Разовый scratch-пробe (не сохранён в suite)
    //  подтвердил: target < lastCompletedOccurrenceDate БЕЗ guard'а
    //  откатывает дату назад. Этот тест закрывает пробел постоянно.)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun staleOlderTarget_afterLaterCompletion_doesNotRegressBackward_statusContactSchedulerUnaffected() {
        persist(contact())
        val itemId = "rc-stale-older-target"
        val today = java.time.LocalDate.now()
        // anchor на ЗАВТРАШНЕМ дне недели — та же защита от time-of-day
        // флакинес, что и в тесте B (см. RecurrenceReminderSchedulingTest lesson).
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule = ReminderRule(id = "rc-stale-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(rule))
        persistCalendarItem(item)
        NotificationScheduler.scheduleReminder(ctx, item, rule)

        // Реальное (не устаревшее) завершение occurrence.
        assertTrue("реальное завершение occurrence обязано пройти", completeSync(item, occurrence))
        val afterRealCompletion = itemInDb(itemId)!!
        assertEquals(occurrence.toString(), afterRealCompletion.lastCompletedOccurrenceDate)
        val contactAfterRealCompletion = lastContactInDb()
        val alarmsAfterRealCompletion = eventAlarmsFor(itemId)
        assertEquals(1, alarmsAfterRealCompletion.size)
        assertEquals(epochMillisOf(occurrence.plusWeeks(1).atTime(10, 0)), alarmsAfterRealCompletion.single().triggerAtTime)

        // Задержанное СТАРОЕ notification action: несёт occurrence ЗА НЕДЕЛЮ ДО
        // уже завершённого (доставка "зависла" и пришла уже после того, как
        // более позднее occurrence было завершено другим действием).
        val staleOlderTarget = occurrence.minusWeeks(1)
        val staleResult = completeSync(afterRealCompletion, staleOlderTarget)
        assertTrue("устаревшее действие — идемпотентный no-op, НЕ ошибка", staleResult)

        val saved = itemInDb(itemId)!!
        assertEquals("lastCompletedOccurrenceDate НЕ откатывается назад",
            occurrence.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("status остаётся ACTIVE", CalendarItemStatus.ACTIVE, saved.status)
        assertEquals("contact.lastContactDate не тронут повторно устаревшим действием",
            contactAfterRealCompletion, lastContactInDb())
        val alarmsAfterStale = eventAlarmsFor(itemId)
        assertEquals("scheduler НЕ откатился к более раннему occurrence — тот же один alarm",
            1, alarmsAfterStale.size)
        assertEquals("alarm остаётся на следующем occurrence, не откатывается назад",
            epochMillisOf(occurrence.plusWeeks(1).atTime(10, 0)), alarmsAfterStale.single().triggerAtTime)
        assertEquals("следующий pending occurrence по-прежнему строго ПОСЛЕ завершённой даты",
            occurrence.plusWeeks(1), saved.pendingOccurrenceDate())
    }

    // ═════════════════════════════════════════════════════════════
    //  EDITOR: RECURRENCE CHANGE + COMPLETION В ОДНОМ SAVE (2026-09-17)
    //  Старый баг (аудит перед фиксом): itemForCompletion нёс УЖЕ применённую
    //  новую серию, а occursOn() валидировал target (occurrence СТАРОЙ серии)
    //  против НЕЁ — почти всегда false -> complete() возвращал false ДО
    //  единственной точки записи -> ни completion, ни остальные правки этого
    //  save (title и т.п.) вообще не сохранялись (полная молчаливая потеря
    //  edits, не просто "completion не произошло"). Исправлено через
    //  CalendarCompletion.OccurrenceSeries.FixedSeries — см. отчёт.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun editorRecurrenceChange_weeklyToMonthly_plusCompleted_oldOccurrenceCompletedNewSeriesSaved() {
        persist(contact())
        val itemId = "rc-editor-weekly-to-monthly"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val oldRule = ReminderRule(id = "rc-wtm-old-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(oldRule))
        persistCalendarItem(original)
        NotificationScheduler.scheduleReminder(ctx, original, oldRule)
        assertEquals("предусловие: today — occurrence текущей WEEKLY серии", today, original.pendingOccurrenceDate())

        val newRule = ReminderRule(id = "rc-wtm-new-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val edited = original.copy(
            title = "Edited during recurrence change",
            recurrenceRule = RecurrenceMode.MONTHLY.toRRule(),
            reminders = listOf(newRule),
            status = CalendarItemStatus.COMPLETED
        )
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue("итог не дождались", pollUntil(5_000) { itemInDb(itemId)?.title == "Edited during recurrence change" })
        val saved = itemInDb(itemId)!!
        assertEquals("правки title сохранены", "Edited during recurrence change", saved.title)
        assertEquals("серия остаётся ACTIVE — завершено ОДНО occurrence, не вся серия", CalendarItemStatus.ACTIVE, saved.status)
        assertEquals("старое (WEEKLY) occurrence, которое видел пользователь, завершено",
            today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("recurrence обновлена на MONTHLY", RecurrenceMode.MONTHLY,
            effectiveRecurrenceMode(saved.type, saved.recurrenceRule, saved.recurrenceExplicitlySet))
        assertEquals("contact side effect ровно один раз", today.toString(), lastContactInDb())

        val expectedNext = RecurrenceCalculator.nextOccurrenceOnOrAfter(anchor, RecurrenceMode.MONTHLY, today.plusDays(1))
        assertEquals("следующее pending occurrence — по НОВОЙ (MONTHLY) серии, строго после завершённой даты",
            expectedNext, saved.pendingOccurrenceDate())
        assertTrue(expectedNext!!.isAfter(today))

        assertTrue("alarms переставлены по новой серии", pollUntil(5_000) { eventAlarmsFor(itemId).size == 1 })
        assertEquals("новый alarm — по НОВОМУ reminder rule",
            epochMillisOf(expectedNext.atTime(10, 0)), eventAlarmsFor(itemId).single().triggerAtTime)
    }

    @Test
    fun editorRecurrenceChange_weeklyToDaily_plusCompleted_oldOccurrenceCompletedNewSeriesSaved() {
        persist(contact())
        val itemId = "rc-editor-weekly-to-daily"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.MEETING)
        persistCalendarItem(original)
        assertEquals("предусловие: today — occurrence текущей WEEKLY серии", today, original.pendingOccurrenceDate())

        val edited = original.copy(recurrenceRule = RecurrenceMode.DAILY.toRRule(), status = CalendarItemStatus.COMPLETED)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue("итог не дождались", pollUntil(5_000) { itemInDb(itemId)?.lastCompletedOccurrenceDate == today.toString() })
        val saved = itemInDb(itemId)!!
        assertEquals("серия остаётся ACTIVE", CalendarItemStatus.ACTIVE, saved.status)
        assertEquals("старое WEEKLY occurrence завершено", today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("recurrence обновлена на DAILY", RecurrenceMode.DAILY,
            effectiveRecurrenceMode(saved.type, saved.recurrenceRule, saved.recurrenceExplicitlySet))
        assertEquals("contact side effect ровно один раз (MEETING — тоже interaction)",
            today.toString(), lastContactInDb())
        assertEquals("следующее pending occurrence — по DAILY, строго после завершённой даты",
            today.plusDays(1), saved.pendingOccurrenceDate())
    }

    @Test
    fun editorRecurrenceChange_staleExternalNotificationForOldSeries_stillNoOp_validationNotWeakened() {
        val itemId = "rc-editor-stale-notification-after-change"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            linkToContact = false)
        persistCalendarItem(original)
        assertEquals(today, original.pendingOccurrenceDate())

        // Обычная (НЕ completion) правка recurrence — WEEKLY -> MONTHLY, без
        // одновременного COMPLETED. Идёт по generic AppStateStore.updateCalendarItem
        // пути (isCompletionTransition == false), НЕ через FixedSeries-исключение.
        val editedNotCompleted = original.copy(recurrenceRule = RecurrenceMode.MONTHLY.toRRule())
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = editedNotCompleted)
        assertTrue(pollUntil(5_000) {
            effectiveRecurrenceMode(itemInDb(itemId)!!.type, itemInDb(itemId)!!.recurrenceRule, itemInDb(itemId)!!.recurrenceExplicitlySet) == RecurrenceMode.MONTHLY
        })

        // Предусловие фикстуры: today НЕ должен случайно оказаться MONTHLY-occurrence
        // того же anchor — иначе тест ничего не докажет.
        assertFalse("test fixture assumption", RecurrenceCalculator.occursOn(anchor, RecurrenceMode.MONTHLY, today))

        // Задержанное СТАРОЕ notification action несёт identity WEEKLY-occurrence
        // (today), но текущая серия item'а уже MONTHLY — это ОБЫЧНЫЙ (не editor)
        // completion-путь, occurrenceSeries по умолчанию CurrentSeries.
        val currentItem = itemInDb(itemId)!!
        val result = completeSync(currentItem, today)
        assertFalse("stale target больше не occurrence текущей серии -> no-op", result)
        assertNull("lastCompletedOccurrenceDate не тронут", itemInDb(itemId)?.lastCompletedOccurrenceDate)
        assertEquals("status не тронут", CalendarItemStatus.ACTIVE, itemInDb(itemId)?.status)
    }

    @Test
    fun editorRecurrenceChange_unchangedRecurrence_plusCompleted_behavesSameAsBeforeFix() {
        persist(contact())
        val itemId = "rc-editor-recurrence-unchanged"
        val today = java.time.LocalDate.now()
        val anchor = today.minusWeeks(4)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
        persistCalendarItem(original)

        // recurrenceRule НЕ меняется — FixedSeries(originalAnchor, originalMode)
        // совпадает по значению с CurrentSeries(item.startDate, item.mode).
        val edited = original.copy(title = "Only title changed", status = CalendarItemStatus.COMPLETED)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue(pollUntil(5_000) { itemInDb(itemId)?.lastCompletedOccurrenceDate == today.toString() })
        val saved = itemInDb(itemId)!!
        assertEquals(CalendarItemStatus.ACTIVE, saved.status)
        assertEquals("Only title changed", saved.title)
        assertEquals(today.toString(), lastContactInDb())
        assertEquals(RecurrenceMode.WEEKLY, effectiveRecurrenceMode(saved.type, saved.recurrenceRule, saved.recurrenceExplicitlySet))
    }

    @Test
    fun editorRecurrenceChange_nonRecurring_completed_statusCompletedRemindersGone() {
        persist(contact())
        val itemId = "rc-editor-nonrecurring-completion"
        val rule = ReminderRule(id = "rc-nr-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val original = recurringItem(itemId, java.time.LocalDate.now().plusDays(5), recurrenceRule = null,
            type = CalendarItemType.CALL, reminders = listOf(rule))
        persistCalendarItem(original)
        NotificationScheduler.scheduleReminder(ctx, original, rule)
        assertEquals(1, eventAlarmsFor(itemId).size)

        val edited = original.copy(title = "Edited", status = CalendarItemStatus.COMPLETED)
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue(pollUntil(5_000) { itemInDb(itemId)?.status == CalendarItemStatus.COMPLETED })
        val saved = itemInDb(itemId)!!
        assertEquals("Edited", saved.title)
        assertEquals(java.time.LocalDate.now().toString(), lastContactInDb())
        assertTrue("будильники сняты", pollUntil(5_000) { eventAlarmsFor(itemId).isEmpty() })
        assertNull(saved.lastCompletedOccurrenceDate)
    }

    // ═════════════════════════════════════════════════════════════
    //  POSTPONE RUNTIME SEMANTICS (2026-09-17) — end-to-end business-operation
    //  тесты (CalendarPostpone.postponeCurrentOccurrence). Anchors строятся
    //  ОТНОСИТЕЛЬНО реального today (как и B/J/N-тесты из более ранних задач
    //  этой сессии), потому что CalendarPostpone внутри всегда использует
    //  реальный LocalDate.now() (pendingOccurrence() без явного referenceDate).
    //  Чисто дат-математические anti-drift сценарии (MONTHLY-31, YEARLY-Feb29)
    //  — в CalendarRenderingRecurrenceTest.kt (детерминированные фикс. даты).
    // ═════════════════════════════════════════════════════════════

    private fun postponeSync(item: CalendarItem, target: java.time.LocalDate): Boolean =
        runBlocking { CalendarPostpone.postponeCurrentOccurrence(ctx, item, target).await() }

    // ─── A. WEEKLY basic ───

    @Test
    fun postponeA_weeklyBasic_startDateUnchanged_pairSet_effectiveDateAdvances() {
        val itemId = "rc-postpone-a-weekly"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL, linkToContact = false)
        persistCalendarItem(item)
        assertEquals("предусловие: occurrence — текущий pending", occurrence, item.pendingOccurrence()?.seriesDate)

        val target = occurrence.plusDays(2)
        assertTrue("postpone обязан пройти", postponeSync(item, target))

        val saved = itemInDb(itemId)!!
        assertEquals("startDate НЕ изменён", anchor.toString(), saved.startDate)
        assertEquals("postponedFromOccurrenceDate = occurrence", occurrence.toString(), saved.postponedFromOccurrenceDate)
        assertEquals("postponedToDate = target", target.toString(), saved.postponedToDate)
        assertEquals("upcoming/effective = target", target.toString(), saved.effectiveDate(today))
    }

    // ─── B. Scheduler: trigger от effectiveDate, payload identity = seriesDate ───

    @Test
    fun postponeB_scheduler_alarmRelativeToEffectiveDate_payloadIdentitySeriesDate() {
        val itemId = "rc-postpone-b-scheduler"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule = ReminderRule(id = "rc-pb-rule", calendarItemId = itemId, reminderType = ReminderType.BEFORE,
            offsetValue = 1, offsetUnit = ReminderOffsetUnit.DAYS)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(rule), linkToContact = false)
        persistCalendarItem(item)
        NotificationScheduler.scheduleReminder(ctx, item, rule)

        val target = occurrence.plusDays(2)
        assertTrue(postponeSync(item, target))

        val alarms = eventAlarmsFor(itemId)
        assertEquals(1, alarms.size)
        assertEquals("trigger — за 1 день до EFFECTIVE (target) даты",
            epochMillisOf(target.minusDays(1).atTime(10, 0)), alarms.single().triggerAtTime)
        val identity = shadowOf(alarms.single().operation).savedIntent.getStringExtra("occurrenceDate")
        assertEquals("payload occurrenceDate — SERIES identity, не effectiveDate", occurrence.toString(), identity)
    }

    // ─── C. Completion постпонутого occurrence ───

    @Test
    fun postponeC_completion_clearsPairAdvancesToNextRegular() {
        persist(contact())
        val itemId = "rc-postpone-c-completion"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule = ReminderRule(id = "rc-pc-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(rule))
        persistCalendarItem(item)
        NotificationScheduler.scheduleReminder(ctx, item, rule)

        val target = occurrence.plusDays(2)
        assertTrue(postponeSync(item, target))
        val postponed = itemInDb(itemId)!!
        assertEquals(occurrence.toString(), postponed.postponedFromOccurrenceDate)

        val doneTarget = postponed.pendingOccurrence()?.seriesDate
        assertEquals("Done обязан завершить seriesDate, не дату переноса", occurrence, doneTarget)
        // completeSync получает ОРИГИНАЛЬНЫЙ item (с links) — postponed пришёл
        // через itemInDb(), который (как и toDomain() в целом) НЕ подтягивает
        // links/reminders из отдельных таблиц; contact side-effect читает их
        // именно из переданного item, не из current (см. CalendarCompletion).
        assertTrue(completeSync(item, doneTarget))

        val saved = itemInDb(itemId)!!
        assertEquals("lastCompletedOccurrenceDate = seriesDate", occurrence.toString(), saved.lastCompletedOccurrenceDate)
        assertNull("postponedFromOccurrenceDate очищена", saved.postponedFromOccurrenceDate)
        assertNull("postponedToDate очищена", saved.postponedToDate)
        assertEquals(CalendarItemStatus.ACTIVE, saved.status)
        assertEquals("contact side effect", today.toString(), lastContactInDb())
        assertEquals("следующий pending — regular (occurrence+1неделя), НЕ ещё раз postponedTo",
            occurrence.plusWeeks(1), saved.pendingOccurrenceDate())

        assertTrue("alarm переставлен на next regular", pollUntil(5_000) { eventAlarmsFor(itemId).size == 1 })
        assertEquals(epochMillisOf(occurrence.plusWeeks(1).atTime(10, 0)), eventAlarmsFor(itemId).single().triggerAtTime)
    }

    // ─── C2. Postponed occurrence, у которого seriesDate УЖЕ в прошлом, а дата
    // переноса ещё впереди: «сегодня» перешагнуло seriesDate, поэтому normal
    // (без override) вычисление само прыгнуло бы на следующее regular
    // occurrence. Done обязан завершить именно seriesDate (requirement 10/11),
    // а не next regular — тест НЕ проходит, если pendingOccurrence игнорирует
    // override. ───

    @Test
    fun postponeC2_overdueSeriesDate_futureEffectiveDate_doneCompletesSeriesIdentity_notNextRegular() {
        persist(contact())
        val itemId = "rc-postpone-c2-overdue-series"
        val today = java.time.LocalDate.now()
        val occurrence = today.minusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val target = today.plusDays(1) // строго до occurrence+7 -> валидный перенос
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
            .copy(postponedFromOccurrenceDate = occurrence.toString(), postponedToDate = target.toString())
        persistCalendarItem(item)

        val pending = item.pendingOccurrence(today)
        assertEquals("seriesDate остаётся исходным occurrence, хотя today его перешагнуло",
            occurrence, pending?.seriesDate)
        assertEquals("effectiveDate — дата переноса", target, pending?.effectiveDate)

        assertTrue(completeSync(item, pending?.seriesDate))

        val saved = itemInDb(itemId)!!
        assertEquals("Done завершил seriesDate, не next regular", occurrence.toString(), saved.lastCompletedOccurrenceDate)
        assertNull(saved.postponedFromOccurrenceDate)
        assertNull(saved.postponedToDate)
        assertEquals("следующий pending — regular occurrence+1неделя", occurrence.plusWeeks(1), saved.pendingOccurrenceDate(today))
    }

    // ─── E. RE-POSTPONE — anti-drift identity ───

    @Test
    fun postponeE_rePostpone_seriesIdentityStaysAnchoredAtOriginalOccurrence() {
        val itemId = "rc-postpone-e-repostpone"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL, linkToContact = false)
        persistCalendarItem(item)

        val firstTarget = occurrence.plusDays(2)
        assertTrue(postponeSync(item, firstTarget))
        val afterFirst = itemInDb(itemId)!!
        assertEquals(occurrence.toString(), afterFirst.postponedFromOccurrenceDate)
        assertEquals(firstTarget.toString(), afterFirst.postponedToDate)

        val secondTarget = occurrence.plusDays(4)
        assertTrue(postponeSync(afterFirst, secondTarget))

        val saved = itemInDb(itemId)!!
        assertEquals("from остаётся исходным occurrence — anti-drift identity, НЕ предыдущий effectiveDate",
            occurrence.toString(), saved.postponedFromOccurrenceDate)
        assertEquals("to продвигается на новую дату", secondTarget.toString(), saved.postponedToDate)
    }

    // ─── F. CROSSING NEXT OCCURRENCE — rejected, никакой silent success ───

    @Test
    fun postponeF_crossingNextOccurrence_rejected_nothingChanges() {
        val itemId = "rc-postpone-f-crossing"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule = ReminderRule(id = "rc-pf-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(rule), linkToContact = false)
        persistCalendarItem(item)
        NotificationScheduler.scheduleReminder(ctx, item, rule)
        val alarmBefore = eventAlarmsFor(itemId).single().triggerAtTime

        val nextRegular = occurrence.plusWeeks(1)
        assertFalse("== next regular occurrence -> rejected", postponeSync(item, nextRegular))
        assertFalse("> next regular occurrence -> rejected", postponeSync(item, nextRegular.plusDays(1)))

        val saved = itemInDb(itemId)!!
        assertEquals("startDate не изменён", anchor.toString(), saved.startDate)
        assertNull("pair не создана", saved.postponedFromOccurrenceDate)
        assertNull(saved.postponedToDate)
        assertEquals("alarm не тронут", alarmBefore, eventAlarmsFor(itemId).single().triggerAtTime)
    }

    // ─── G. VALID BOUNDARY — день перед next regular разрешён ───

    @Test
    fun postponeG_validBoundary_oneDayBeforeNextRegular_allowed() {
        val itemId = "rc-postpone-g-boundary"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL, linkToContact = false)
        persistCalendarItem(item)

        val boundaryTarget = occurrence.plusWeeks(1).minusDays(1)
        assertTrue("день перед next regular — валиден", postponeSync(item, boundaryTarget))
        assertEquals(boundaryTarget.toString(), itemInDb(itemId)?.postponedToDate)
    }

    // ─── H. DAILY — недоступен структурно (не отдельный кейс, вытекает из
    // общей формулы isValidPostponeTarget: между seriesDate и seriesDate+1
    // нет другого LocalDate) ───

    @Test
    fun postponeH_daily_rejectedForAnyTarget_noPairCreated() {
        val itemId = "rc-postpone-h-daily"
        val today = java.time.LocalDate.now()
        val anchor = today.minusDays(10)
        val item = recurringItem(itemId, anchor, RecurrenceMode.DAILY.toRRule(), type = CalendarItemType.CALL, linkToContact = false)
        persistCalendarItem(item)
        val pending = item.pendingOccurrence()!!

        assertFalse("DAILY: любой target отклоняется", postponeSync(item, pending.seriesDate.plusDays(1)))
        assertNull(itemInDb(itemId)?.postponedFromOccurrenceDate)
        assertNull(itemInDb(itemId)?.postponedToDate)
    }

    // ─── K. REBOOT/RECONCILE — не зависит от memory snapshot ───

    @Test
    fun postponeK_rebootReconcile_restoresAlarmForEffectiveDate_payloadIdentitySeriesDate() {
        val itemId = "rc-postpone-k-reboot"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule = ReminderRule(id = "rc-pk-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL,
            reminders = listOf(rule), linkToContact = false)
        persistCalendarItem(item)

        val target = occurrence.plusDays(2)
        assertTrue(postponeSync(item, target))

        // «Холодное состояние»: очищаем in-memory И существующие alarms, чтобы
        // доказать, что reconcile реально ВОССТАНАВЛИВАЕТ их из Room, а не
        // просто находит уже стоящие.
        AppStateStore.calendarItems.clear()
        NotificationScheduler.cancelAllRemindersForCalendarItem(ctx, listOf(rule))
        assertTrue(eventAlarmsFor(itemId).isEmpty())

        runBlocking { NotificationScheduler.rescheduleAll(ctx, db) }

        val alarms = eventAlarmsFor(itemId)
        assertEquals(1, alarms.size)
        assertEquals(epochMillisOf(target.atTime(10, 0)), alarms.single().triggerAtTime)
        assertEquals(occurrence.toString(), shadowOf(alarms.single().operation).savedIntent.getStringExtra("occurrenceDate"))
    }

    // ─── L. BACKUP/RESTORE RUNTIME ───

    @Test
    fun postponeL_backupRestore_reconcileRestoresCorrectScheduling() {
        val itemId = "rc-postpone-l-restore"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val rule = ReminderRule(id = "rc-pl-rule", calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
        val target = occurrence.plusDays(2)
        val backedUpItem = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.MEETING,
            reminders = listOf(rule), linkToContact = false).copy(
                postponedFromOccurrenceDate = occurrence.toString(),
                postponedToDate = target.toString(),
            )

        runBlocking { AppStateStore.restoreCalendarItem(backedUpItem).await() }
        runBlocking { NotificationScheduler.reconcileAllUserDataAlarms(ctx, db) }

        val alarms = eventAlarmsFor(itemId)
        assertEquals(1, alarms.size)
        assertEquals(epochMillisOf(target.atTime(10, 0)), alarms.single().triggerAtTime)
        assertEquals(occurrence.toString(), shadowOf(alarms.single().operation).savedIntent.getStringExtra("occurrenceDate"))
    }

    // ─── M. UNRELATED EDITOR EDIT (title-only) — через РЕАЛЬНЫЙ Compose UI
    // end-to-end (тот же паттерн, что g_unrelatedFieldEdit_... из предыдущей
    // задачи) — пара сохраняется ───

    @Test
    fun postponeM_unrelatedTitleEdit_throughRealEditorUi_preservesPair() {
        val itemId = "cie-postpone-title-preserved"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val original = calendarItem(itemId, type = CalendarItemType.CALL, recurrenceRule = RecurrenceMode.WEEKLY.toRRule())
            .copy(startDate = anchor.toString(), postponedFromOccurrenceDate = occurrence.toString(), postponedToDate = occurrence.plusDays(2).toString())
        persistCalendarItem(original)

        showEditor(itemId)
        replaceTitle("Original title", "Renamed, postpone untouched")
        saveEditor()
        assertTrue(pollUntil(5_000) { itemInDb(itemId)?.title == "Renamed, postpone untouched" })

        val saved = itemInDb(itemId)!!
        assertEquals("Renamed, postpone untouched", saved.title)
        assertEquals("postponedFromOccurrenceDate сохранён", occurrence.toString(), saved.postponedFromOccurrenceDate)
        assertEquals("postponedToDate сохранён", occurrence.plusDays(2).toString(), saved.postponedToDate)
    }

    // ─── N. RECURRENCE CHANGE — пара очищается (persistCalendarItemEdit
    // напрямую — recurrence-dropdown недоступен для клика под Robolectric,
    // тот же established паттерн, что editorRecurrenceChange_* тесты) ───

    @Test
    fun postponeN_recurrenceChange_clearsPair() {
        val itemId = "rc-postpone-n-recurrence-change"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL, linkToContact = false)
            .copy(postponedFromOccurrenceDate = occurrence.toString(), postponedToDate = occurrence.plusDays(2).toString())
        persistCalendarItem(original)

        val edited = original.copy(recurrenceRule = RecurrenceMode.MONTHLY.toRRule())
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue(pollUntil(5_000) {
            effectiveRecurrenceMode(itemInDb(itemId)!!.type, itemInDb(itemId)!!.recurrenceRule, itemInDb(itemId)!!.recurrenceExplicitlySet) == RecurrenceMode.MONTHLY
        })
        val saved = itemInDb(itemId)!!
        assertNull("pair очищена при смене recurrence mode", saved.postponedFromOccurrenceDate)
        assertNull(saved.postponedToDate)
    }

    // ─── O. ANCHOR (startDate) CHANGE — пара очищается ───

    @Test
    fun postponeO_anchorDateChange_clearsPair() {
        val itemId = "rc-postpone-o-anchor-change"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val original = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL, linkToContact = false)
            .copy(postponedFromOccurrenceDate = occurrence.toString(), postponedToDate = occurrence.plusDays(2).toString())
        persistCalendarItem(original)

        val edited = original.copy(startDate = anchor.plusDays(1).toString())
        persistCalendarItemEdit(ctx, isEditMode = true, originalItem = original, newItem = edited)

        assertTrue(pollUntil(5_000) { itemInDb(itemId)?.startDate == anchor.plusDays(1).toString() })
        val saved = itemInDb(itemId)!!
        assertNull("pair очищена при смене anchor startDate", saved.postponedFromOccurrenceDate)
        assertNull(saved.postponedToDate)
    }

    // ─── P. DUPLICATE DONE на postponed occurrence — не advance дважды ───

    @Test
    fun postponeP_duplicateDone_onPostponedOccurrence_doesNotDoubleAdvance() {
        persist(contact())
        val itemId = "rc-postpone-p-duplicate-done"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
        persistCalendarItem(item)

        val target = occurrence.plusDays(2)
        assertTrue(postponeSync(item, target))

        // completeSync получает ОРИГИНАЛЬНЫЙ item (с links) — см. postponeC.
        assertTrue("первый Done обязан завершить occurrence", completeSync(item, occurrence))
        assertTrue("второй Done — идемпотентный no-op", completeSync(item, occurrence))

        val saved = itemInDb(itemId)!!
        assertEquals("lastCompletedOccurrenceDate = occurrence, не продвинут дальше",
            occurrence.toString(), saved.lastCompletedOccurrenceDate)
        assertNull(saved.postponedFromOccurrenceDate)
        assertNull(saved.postponedToDate)
        assertEquals("следующий pending — ровно occurrence+1неделя, не «ещё следующее»",
            occurrence.plusWeeks(1), saved.pendingOccurrenceDate())
    }

    // ─── P0.1 (2026-09-21): вкладка «Подарки» показывает подарок после обновления
    // Contact УСТАРЕВШИМ снимком (gifts=[]). GiftsTab читает ГЛОБАЛЬНЫЙ
    // AppStateStore.gifts — на старом коде updateContact выкидывал подарок из него,
    // и в UI пропадала строка. Рендерится настоящий giftsTab, не копия его фильтра. ───

    @Test
    fun giftsUi_showsGift_afterUpdateContactWithStaleSnapshot() {
        persist(contact())
        val stale = AppStateStore.getContact(contactId)!!
        assertTrue("предусловие: устаревший снимок без подарков", stale.gifts.isEmpty())
        AppStateStore.addGift(GiftIdea("ui-g1", contactId, "Подарок для UI-проверки", null, null, null, GiftStatus.IDEA))
        runBlocking { AppStateStore.awaitPendingWrites() }

        runBlocking { AppStateStore.updateContact(stale, stale.copy(firstName = "Изменённое")).await() }

        val current = AppStateStore.getContact(contactId)!!
        compose.setContent {
            AppleAppTheme(darkTheme = false) {
                LazyColumn { giftsTab(current, onNavigateToCalendarItem = {}, ctxLabel = ctx) }
            }
        }
        compose.waitForIdle()
        assertTrue("вкладка «Подарки» показывает подарок",
            compose.onAllNodes(hasText("Подарок для UI-проверки")).fetchSemanticsNodes().isNotEmpty())
    }

    // ─── Q. STALE OLDER DONE — существующий idempotency-guard сохраняется
    // (постпон не ослабляет и не обходит его) ───

    @Test
    fun postponeQ_staleOlderDone_afterPostponedOccurrenceCompleted_stillNoOp() {
        persist(contact())
        val itemId = "rc-postpone-q-stale-older"
        val today = java.time.LocalDate.now()
        val occurrence = today.plusDays(1)
        val anchor = occurrence.minusWeeks(4)
        val item = recurringItem(itemId, anchor, RecurrenceMode.WEEKLY.toRRule(), type = CalendarItemType.CALL)
        persistCalendarItem(item)

        val target = occurrence.plusDays(2)
        assertTrue(postponeSync(item, target))
        // completeSync получает ОРИГИНАЛЬНЫЙ item (с links) — см. postponeC.
        assertTrue(completeSync(item, occurrence))
        val afterCompletion = itemInDb(itemId)!!
        assertEquals(occurrence.toString(), afterCompletion.lastCompletedOccurrenceDate)

        // Устаревшее действие несёт identity occurrence ЗА НЕДЕЛЮ ДО уже
        // завершённого — существующий guard (requirement 8, доказан ранее в
        // staleOlderTarget_...) должен сработать без изменений.
        val staleOlder = occurrence.minusWeeks(1)
        assertTrue("устаревшее действие — идемпотентный no-op, НЕ ошибка", completeSync(afterCompletion, staleOlder))

        val saved = itemInDb(itemId)!!
        assertEquals("lastCompletedOccurrenceDate НЕ откатывается назад", occurrence.toString(), saved.lastCompletedOccurrenceDate)
        assertNull(saved.postponedFromOccurrenceDate)
        assertNull(saved.postponedToDate)
    }
}
