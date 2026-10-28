package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toEntity
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * CALENDAR RUNTIME & ALARM LIFECYCLE PACK — AlarmManager обязан отражать persisted (Room) состояние после системных событий.
 *
 * Холодный процесс: память AppStateStore пуста, AppSettings поднимает сам планировщик, данные — только в Room (DAO). Доставка —
 * настоящим приёмником из манифеста с реальным PendingResult (goAsync/finish), результат — в ShadowAlarmManager.
 * Часовой пояс меняется через TimeZone.setDefault (планировщик берёт ZoneId.systemDefault() НА МОМЕНТ reconcile).
 */
@RunWith(RobolectricTestRunner::class)
class AlarmLifecycleTest {

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context
    private val defaultProvider = AppStateStore.databaseProvider
    private val defaultZone: TimeZone = TimeZone.getDefault()
    private val today: LocalDate = LocalDate.now()

    private val actions = listOf(
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    )

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        AppSettings.init(ctx)
        AppSettings.isNotificationsEnabled.value = true
        AppSettings.remindStaleContacts.value = true
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java).allowMainThreadQueries().build()
        AppStateStore.resetForTests()
        AppStateStore.databaseProvider = { db }
        AppStateStore.calendarItems.clear()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultZone)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AppSettings.isNotificationsEnabled.value = true
        AppStateStore.resetForTests()
        AppStateStore.databaseProvider = defaultProvider
        db.close()
    }

    // ─── обвязка ────────────────────────────────────────────────

    private fun am() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun pollUntil(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(20)
        }
        fail("не дождался: $what")
    }

    /** Доставка в приёмник с настоящим PendingResult; возвращается только после PendingResult.finish(). */
    private fun deliverTo(receiver: BroadcastReceiver, intent: Intent) {
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val pending = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
        BroadcastReceiver::class.java.getMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java).invoke(receiver, pending)
        receiver.onReceive(ctx, intent)
        val finished = Shadow.extract<ShadowBroadcastPendingResult>(pending).future
        pollUntil("PendingResult.finish() вызван") { finished.isDone }
    }

    private fun deliver(action: String) = deliverTo(BootReceiver(), Intent(action))

    private fun cancelAll() = shadowOf(am()).scheduledAlarms.toList().forEach { alarm -> alarm.operation?.let { am().cancel(it) } }

    private fun alarmsFor(itemId: String) = shadowOf(am()).scheduledAlarms.filter {
        val saved = shadowOf(it.operation).savedIntent
        saved.component?.className == NotificationReceiver::class.java.name && saved.getStringExtra("calendarItemId") == itemId
    }

    private fun staleCheckAlarms() = shadowOf(am()).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.component?.className == StaleContactsReceiver::class.java.name }

    private fun millis(t: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()) = t.atZone(zone).toInstant().toEpochMilli()

    private val futureStart: LocalDateTime get() = LocalDateTime.now().plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0)

    private fun atEvent(itemId: String, ruleId: String) = ReminderRule(ruleId, itemId, ReminderType.AT_TIME)
    private fun hourBefore(itemId: String, ruleId: String) = ReminderRule(ruleId, itemId, ReminderType.BEFORE, 1, ReminderOffsetUnit.HOURS, null)

    /** Событие и правила — напрямую в Room (DAO), без AppStateStore. */
    private fun seed(
        id: String, start: LocalDateTime = futureStart, rules: List<ReminderRule>, status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        startTime: String = "%02d:%02d".format(start.hour, start.minute), recurrenceRule: String? = null, explicit: Boolean = false,
        pair: Pair<LocalDate, LocalDate>? = null,
    ): CalendarItem {
        val item = CalendarItem(
            id = id, title = "Звонок $id", type = CalendarItemType.CALL, startDate = start.toLocalDate().toString(), startTime = startTime,
            isAllDay = false, status = status, importance = ImportanceLevel.NORMAL, recurrenceRule = recurrenceRule,
            recurrenceExplicitlySet = explicit, postponedFromOccurrenceDate = pair?.first?.toString(), postponedToDate = pair?.second?.toString(),
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00")
        runBlocking {
            db.calendarDao().insertCalendarItem(item.toEntity())
            db.calendarDao().insertReminderRules(rules.map { it.toEntity() })
        }
        return item.copy(reminders = rules)
    }

    // ═════════════════════════════════════════════════════════════
    //  Матрица системных событий
    // ═════════════════════════════════════════════════════════════

    /** Каждое действие зарегистрировано в манифесте на тот же единый приёмник; чужое действие не доставляется. */
    @Test
    fun manifest_routesEveryLifecycleAction_toTheSingleReceiver() {
        actions.forEach { action ->
            val resolved = ctx.packageManager.queryBroadcastReceivers(Intent(action), 0).map { it.activityInfo.name }
            assertTrue("$action обязан быть зарегистрирован на BootReceiver, есть $resolved", BootReceiver::class.java.name in resolved)
        }
        assertEquals("набор действий приёмника == набор манифеста", actions.toSet(), BootReceiver.RECONCILE_ACTIONS)
    }

    /** BOOT / TIMEZONE / TIME_SET / PACKAGE_REPLACED / EXACT_GRANT → reconcile из Room в холодном процессе; ежедневная проверка тоже. */
    @Test
    fun everyLifecycleAction_reconcilesFromRoom_inColdProcess() {
        seed("m1", rules = listOf(atEvent("m1", "r1"), hourBefore("m1", "r2")))
        actions.forEach { action ->
            cancelAll()
            assertEquals("$action: предусловие — будильников нет", 0, alarmsFor("m1").size)
            assertTrue("предусловие: память пуста — источник только Room", AppStateStore.calendarItems.isEmpty())

            deliver(action)

            assertEquals("$action: оба напоминания восстановлены из Room", 2, alarmsFor("m1").size)
            assertEquals("$action: ежедневная проверка запланирована", 1, staleCheckAlarms().size)
        }
    }

    @Test
    fun unknownOrMissingAction_isNoOp() {
        seed("u1", rules = listOf(atEvent("u1", "r1")))
        BootReceiver().onReceive(ctx, Intent("com.example.UNKNOWN_ACTION"))
        BootReceiver().onReceive(ctx, Intent())
        assertEquals("чужое/пустое действие ничего не планирует", 0, shadowOf(am()).scheduledAlarms.size)
    }

    // ═════════════════════════════════════════════════════════════
    //  Часовой пояс и системные часы
    // ═════════════════════════════════════════════════════════════

    /** «09:00 локально» после смены пояса — уже другой epoch: будильник пересчитан от Room, Room не менялась. */
    @Test
    fun timezoneChanged_realignsAlarmToNewLocalTime_roomUntouched() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-3"))                       // UTC+3
        val zoneA = ZoneId.of("Etc/GMT-3"); val zoneB = ZoneId.of("Etc/GMT-2")        // UTC+2
        val local = LocalDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0)
        seed("tz1", start = local, rules = listOf(atEvent("tz1", "r")))
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertEquals("предусловие: 09:00 по UTC+3", millis(local, zoneA), alarmsFor("tz1").single().triggerAtTime)

        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-2"))
        deliver(Intent.ACTION_TIMEZONE_CHANGED)

        assertEquals("09:00 по НОВОМУ поясу = прежний epoch + 1 час", millis(local, zoneB), alarmsFor("tz1").single().triggerAtTime)
        assertEquals(millis(local, zoneA) + 3_600_000, alarmsFor("tz1").single().triggerAtTime)
        val room = runBlocking { db.calendarDao().getAllCalendarItems() }.single { it.id == "tz1" }
        assertEquals("Room хранит ЛОКАЛЬНЫЕ дату/время и не менялась", local.toLocalDate().toString() to "09:00", room.startDate to room.startTime)
    }

    /** После сдвига пояса вперёд событие оказалось в прошлом: будильник, стоявший по старому epoch, СНЯТ (иначе сработал бы не вовремя). */
    @Test
    fun timezoneChanged_eventNowInPast_staleAlarmIsCancelled() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-3"))
        val local = LocalDateTime.now().plusMinutes(45).withSecond(0).withNano(0)
        seed("tz2", start = local, rules = listOf(atEvent("tz2", "r")))
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertEquals("предусловие: будильник стоит", 1, alarmsFor("tz2").size)

        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-5"))                       // часы вперёд на 2 часа: событие уже позади
        deliver(Intent.ACTION_TIMEZONE_CHANGED)

        assertEquals("по новому локальному времени событие прошло — старый будильник снят", 0, alarmsFor("tz2").size)
    }

    /** TIME_SET: неверно стоящий будильник исправлен пересчётом от канонических дат Room (тот же reconcile, не второй планировщик). */
    @Test
    fun timeSet_recomputesFromRoom_correctsWrongAlarm_withoutDuplicate() {
        val start = futureStart
        val real = seed("ts1", start = start, rules = listOf(atEvent("ts1", "r")))
        NotificationScheduler.scheduleReminder(ctx, real.copy(startDate = start.toLocalDate().plusDays(1).toString()), real.reminders.single())
        assertEquals("предусловие: будильник по неверному времени", millis(start.plusDays(1)), alarmsFor("ts1").single().triggerAtTime)

        deliver(Intent.ACTION_TIME_CHANGED)

        assertEquals("один будильник, по каноническому времени Room", listOf(millis(start)), alarmsFor("ts1").map { it.triggerAtTime })
    }

    /** DST: событие — локальные дата/время; epoch считается текущим ZoneId на момент reconcile, в т.ч. в день перехода и в «пропавший» час. */
    @Test
    fun dst_boundary_alarmIsLocalWallClock_notOldOffset_gapTimeStillScheduled() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val ny = ZoneId.of("America/New_York")
        val day = LocalDate.of(2035, 3, 11)                                           // 2-е воскресенье марта: 02:00 → 03:00
        // ВАЖНО: id правила-напоминания — PRIMARY KEY таблицы reminder_rules (REPLACE при совпадении, не составной ключ
        // с calendarItemId) — три события ОБЯЗАНЫ иметь РАЗНЫЕ id правил, иначе поздняя вставка тихо подменяет строку ранней.
        seed("dst-before", start = day.minusDays(1).atTime(9, 0), rules = listOf(atEvent("dst-before", "r-before")))
        seed("dst-after", start = day.atTime(9, 0), rules = listOf(atEvent("dst-after", "r-after")))
        seed("dst-gap", start = day.atTime(2, 30), rules = listOf(atEvent("dst-gap", "r-gap")))

        deliver(Intent.ACTION_TIMEZONE_CHANGED)

        val before = alarmsFor("dst-before").single().triggerAtTime
        val after = alarmsFor("dst-after").single().triggerAtTime
        assertEquals("09:00 локально в день перехода (EDT, −4), а не по прежнему смещению EST (−5)",
            LocalDateTime.of(day, LocalTime.of(9, 0)), Instant.ofEpochMilli(after).atZone(ny).toLocalDateTime())
        assertEquals(-4 * 3600, Instant.ofEpochMilli(after).atZone(ny).offset.totalSeconds)
        assertEquals("между 09:00 соседних суток через переход — 23 часа, не 24", 23 * 3_600_000L, after - before)
        assertEquals("несуществующее локальное 02:30 не пропущено: будильник = канонический ZoneId-разбор",
            millis(day.atTime(2, 30), ny), alarmsFor("dst-gap").single().triggerAtTime)
    }

    // ═════════════════════════════════════════════════════════════
    //  Обновление приложения и master-переключатель
    // ═════════════════════════════════════════════════════════════

    /** MY_PACKAGE_REPLACED: будильник со СТАРОЙ identity (до этого пакета) заменён одним будильником под новой — без дубля. */
    @Test
    fun packageReplaced_replacesLegacyIdentityAlarm_singleAlarmAtCanonicalTime() {
        val start = futureStart
        seed("lg", start = start, rules = listOf(atEvent("lg", "r")))
        val nid = ("lg|r").hashCode()
        val legacy = PendingIntent.getBroadcast(
            ctx, nid, Intent(ctx, NotificationReceiver::class.java).putExtra("calendarItemId", "lg").putExtra("notificationId", nid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(start.minusHours(3)), legacy)
        assertEquals("предусловие: старый будильник по неверному времени", 1, alarmsFor("lg").size)

        deliver(Intent.ACTION_MY_PACKAGE_REPLACED)

        val now = alarmsFor("lg")
        assertEquals("ровно один будильник (старая identity снята)", 1, now.size)
        assertEquals("новая identity", "reminder", shadowOf(now.single().operation).savedIntent.data?.authority)
        assertEquals("по каноническому времени", millis(start), now.single().triggerAtTime)
    }

    /** Master OFF: после BOOT / обновления напоминания и ежедневная проверка не ставятся, ранее стоявшие — сняты. */
    @Test
    fun masterOff_noReminderAlarms_afterBootAndPackageReplaced() {
        seed("mo", rules = listOf(atEvent("mo", "r")))
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertEquals("предусловие (master ON): будильник и проверка есть", 1 to 1, alarmsFor("mo").size to staleCheckAlarms().size)

        AppSettings.isNotificationsEnabled.value = false
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            deliver(action)
            assertEquals("$action: при выключенных уведомлениях будильников напоминаний нет", 0, alarmsFor("mo").size)
            assertEquals("$action: ежедневной проверки нет", 0, staleCheckAlarms().size)
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Exact-alarm permission
    // ═════════════════════════════════════════════════════════════

    /** Разрешение выдано: неточные будильники заменены точными под ТОЙ ЖЕ identity (те же два, без дублей). */
    @Test
    fun exactPermissionGrant_replacesInexactAlarmsWithExact_noDuplicates() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        seed("ex", rules = listOf(atEvent("ex", "r1"), hourBefore("ex", "r2")))
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(2, alarmsFor("ex").size)
        assertTrue("без разрешения — неточные эквиваленты", alarmsFor("ex").all { it.windowLengthMs == ShadowAlarmManager.WINDOW_HEURISTIC })

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        deliver(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)

        assertEquals("те же два будильника, не четыре", 2, alarmsFor("ex").size)
        assertTrue("теперь точные", alarmsFor("ex").all { it.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT })
    }

    /** Отзыв: симметричного broadcast нет; система сняла будильники — следующий reconcile (запуск приложения) ставит неточные эквиваленты. */
    @Test
    fun exactPermissionRevoked_nextReconcileReArmsInexactEquivalents_reminderIsNeverLost() {
        seed("rv", rules = listOf(atEvent("rv", "r1"), hourBefore("rv", "r2")))
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertTrue(alarmsFor("rv").all { it.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT })

        cancelAll()                                                                    // система отозвала разрешение и сняла точные будильники
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(0, alarmsFor("rv").size)

        runBlocking { NotificationScheduler.reconcileAllUserDataAlarms(ctx, db) }      // тот же reconcile, что на старте приложения

        assertEquals("напоминания перепоставлены", 2, alarmsFor("rv").size)
        assertTrue("как неточные (пользователь не остаётся без напоминания)", alarmsFor("rv").all { it.windowLengthMs == ShadowAlarmManager.WINDOW_HEURISTIC })
    }

    // ═════════════════════════════════════════════════════════════
    //  Идемпотентность, изоляция сбоев, postponed identity
    // ═════════════════════════════════════════════════════════════

    /** После каждого lifecycle-действия, повторённого трижды, и после запуска приложения: один будильник на напоминание, одна проверка. */
    @Test
    fun repeatedReconcile_afterEveryAction_neverDuplicates() {
        seed("rp", rules = listOf(atEvent("rp", "r1"), hourBefore("rp", "r2")))
        actions.forEach { action ->
            repeat(3) { deliver(action) }
            assertEquals("$action ×3: по одному будильнику на напоминание", 2, alarmsFor("rp").size)
            assertEquals("$action ×3: одна ежедневная проверка", 1, staleCheckAlarms().size)
        }
        repeat(2) { runBlocking { NotificationScheduler.reconcileAllUserDataAlarms(ctx, db) } }      // запуск приложения
        assertEquals(2, alarmsFor("rp").size)
        assertEquals(1, staleCheckAlarms().size)
    }

    /** Битые правила/событие не останавливают reconcile остальных; PendingResult завершён (deliver возвращается только после finish()). */
    @Test
    fun brokenRulesAndItems_doNotBlockValidReminders() {
        seed("bad-rule", rules = listOf(ReminderRule("rb", "bad-rule", ReminderType.BEFORE, null, null, null),
            ReminderRule("rc", "bad-rule", ReminderType.CUSTOM_DATE_TIME, null, null, "не-дата")))
        seed("bad-time", startTime = "99:99", rules = listOf(atEvent("bad-time", "r-bt")))
        seed("ok", rules = listOf(atEvent("ok", "r-ok")))

        deliver(Intent.ACTION_BOOT_COMPLETED)

        assertEquals("валидное напоминание восстановлено", 1, alarmsFor("ok").size)
        assertEquals(0, alarmsFor("bad-rule").size + alarmsFor("bad-time").size)
        assertEquals("ежедневная проверка тоже", 1, staleCheckAlarms().size)
    }

    /** B4: перенесённое occurrence после BOOT — по эффективному времени переноса, identity — исходная дата серии. */
    @Test
    fun boot_restoresPostponedOccurrence_withEffectiveTimingAndSeriesIdentity() {
        seed("pp", start = today.minusWeeks(4).atTime(10, 0), rules = listOf(ReminderRule("r", "pp", ReminderType.BEFORE, 1, ReminderOffsetUnit.DAYS, null)),
            recurrenceRule = RecurrenceMode.WEEKLY.toRRule(), explicit = true, pair = today to today.plusDays(2))

        deliver(Intent.ACTION_BOOT_COMPLETED)

        val alarm = alarmsFor("pp").single()
        assertEquals("триггер от ЭФФЕКТИВНОЙ даты (today+2 − 1 день)", millis(today.plusDays(1).atTime(10, 0)), alarm.triggerAtTime)
        assertEquals("payload — исходная дата серии", today.toString(), shadowOf(alarm.operation).savedIntent.getStringExtra("occurrenceDate"))
    }

    /** P5: «Готово» из уведомления, пришедшего по fallback-будильнику, завершает ИСХОДНОЕ occurrence серии (а не дату переноса). */
    @Test
    fun P5_completionFromFallbackNotification_completesTheOriginalSeriesOccurrence() {
        // BEFORE «за 3 дня» пропущен, эффективное occurrence (today+2) впереди → fallback-будильник; identity = today
        seed("fb", start = today.minusWeeks(4).atTime(10, 0), rules = listOf(ReminderRule("r", "fb", ReminderType.BEFORE, 3, ReminderOffsetUnit.DAYS, null)),
            recurrenceRule = RecurrenceMode.WEEKLY.toRRule(), explicit = true, pair = today to today.plusDays(2))
        deliver(Intent.ACTION_BOOT_COMPLETED)
        val alarm = alarmsFor("fb").single()
        assertEquals("предусловие: fallback на время occurrence", millis(today.plusDays(2).atTime(10, 0)), alarm.triggerAtTime)
        val alarmIntent = shadowOf(alarm.operation).savedIntent

        deliverTo(NotificationReceiver(), alarmIntent)                                  // будильник сработал → уведомление
        val nid = alarmIntent.getIntExtra("notificationId", 0)
        val notification = shadowOf(nm()).getNotification(nid) ?: fail("уведомление не показано") as Nothing
        val done = (notification.actions ?: emptyArray()).firstOrNull { it.title.toString() == ctx.getString(R.string.notif_action_done) }
            ?: fail("нет кнопки «Готово»") as Nothing
        deliverTo(NotificationReceiver(), shadowOf(done.actionIntent).savedIntent)

        val saved = runBlocking { db.calendarDao().getAllCalendarItems() }.single { it.id == "fb" }
        assertEquals("завершено исходное occurrence серии", today.toString(), saved.lastCompletedOccurrenceDate)
        assertEquals("постпон-пара очищена завершением", null to null, saved.postponedFromOccurrenceDate to saved.postponedToDate)
    }
}
