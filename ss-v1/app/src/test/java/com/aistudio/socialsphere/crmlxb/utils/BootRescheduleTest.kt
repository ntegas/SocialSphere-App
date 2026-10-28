package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toEntity
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * INVARIANT: после перезагрузки устройства восстановлены все будущие напоминания
 * ACTIVE-событий (каждое правило — отдельно), у COMPLETED/CANCELLED и у прошедших
 * одноразовых будильников нет, повторный BOOT_COMPLETED дублей не создаёт,
 * ежедневная проверка «пора связаться» тоже запланирована, и ничто из этого не
 * зависит от запуска MainActivity.
 *
 * Отдельная SDK (35) — отдельная песочница Robolectric со СВЕЖИМ static-состоянием:
 * только так AppSettings действительно не инициализирован, как в процессе,
 * который система подняла ради BOOT_COMPLETED. Тест A идёт первым
 * (NAME_ASCENDING) и сам проверяет это предусловие, чтобы не пройти вхолостую.
 *
 * Путь настоящий: приёмник, зарегистрированный в манифесте на BOOT_COMPLETED,
 * получает этот интент с реальным PendingResult (goAsync()/finish()), результат
 * проверяется в ShadowAlarmManager, данные лежат только в Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BootRescheduleTest {

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context
    private val defaultProvider = AppStateStore.databaseProvider

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Холодный процесс: хранилище не подключено, память пуста, данные только в Room.
        AppStateStore.resetForTests()
        AppStateStore.databaseProvider = { db }
    }

    @After
    fun tearDown() {
        AppStateStore.resetForTests()
        AppStateStore.databaseProvider = defaultProvider
        db.close()
    }

    // ─── обвязка ───────────────────────────────────────────────

    private fun alarmManager() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun appSettingsInitialized(): Boolean =
        AppSettings::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(AppSettings) != null

    private fun pollUntil(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(20)
        }
        fail("не дождался: $what")
    }

    /** BOOT_COMPLETED в зарегистрированный приёмник с настоящим PendingResult;
     *  возвращается только после PendingResult.finish(). */
    private fun deliverBootCompleted() {
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED)
        val resolved = ctx.packageManager.queryBroadcastReceivers(boot, 0)
            .map { it.activityInfo.name }
        assertTrue("BootReceiver обязан быть зарегистрирован в манифесте на BOOT_COMPLETED, есть $resolved",
            BootReceiver::class.java.name in resolved)

        val receiver = BootReceiver()
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val pending = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
        BroadcastReceiver::class.java
            .getMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, pending)

        receiver.onReceive(ctx, boot)

        val finished = Shadow.extract<ShadowBroadcastPendingResult>(pending).future
        pollUntil("BootReceiver: PendingResult.finish() вызван") { finished.isDone }
    }

    private fun reminderAlarms() = shadowOf(alarmManager()).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.component?.className == NotificationReceiver::class.java.name }

    private fun alarmsFor(itemId: String) = reminderAlarms()
        .filter { shadowOf(it.operation).savedIntent.getStringExtra("calendarItemId") == itemId }

    private fun staleCheckAlarms() = shadowOf(alarmManager()).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.component?.className == StaleContactsReceiver::class.java.name }

    private val eventStart: LocalDateTime = LocalDateTime.now().plusDays(10).withHour(15).withMinute(0).withSecond(0).withNano(0)

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Событие и правила — напрямую в Room (DAO), без AppStateStore и без AppSettings. */
    private fun seed(
        id: String,
        status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        start: LocalDateTime = eventStart,
        rules: List<ReminderRule>,
    ) {
        val item = CalendarItem(
            id = id, title = "Звонок $id", type = CalendarItemType.CALL,
            startDate = start.toLocalDate().toString(), startTime = "%02d:%02d".format(start.hour, start.minute),
            isAllDay = false, status = status, importance = ImportanceLevel.NORMAL,
            createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
        )
        runBlocking {
            db.calendarDao().insertCalendarItem(item.toEntity())
            db.calendarDao().insertReminderRules(rules.map { it.toEntity() })
        }
    }

    private fun atEvent(itemId: String, ruleId: String) =
        ReminderRule(id = ruleId, calendarItemId = itemId, reminderType = ReminderType.AT_TIME)

    private fun hourBefore(itemId: String, ruleId: String) =
        ReminderRule(id = ruleId, calendarItemId = itemId, reminderType = ReminderType.BEFORE,
            offsetValue = 1, offsetUnit = ReminderOffsetUnit.HOURS)

    // ─── A (+G). Холодный бут: AppSettings не инициализирован ───

    @Test
    fun a_coldBoot_withoutAppSettingsInit_restoresFutureActiveReminder() {
        assertTrue("предусловие: AppSettings НЕ инициализирован, как в процессе, поднятом ради BOOT_COMPLETED",
            !appSettingsInitialized())
        seed("ev-a", rules = listOf(atEvent("ev-a", "r-a")))

        deliverBootCompleted()

        val alarms = alarmsFor("ev-a")
        assertEquals("будущее напоминание ACTIVE-события обязано быть восстановлено", 1, alarms.size)
        assertEquals("время срабатывания — время события", millis(eventStart), alarms.single().triggerAtTime)
    }

    // ─── B / C. Выполненные и отменённые события будильников не получают ───

    @Test
    fun b_completedEvent_getsNoAlarmAfterBoot() {
        seed("ev-b", status = CalendarItemStatus.COMPLETED, rules = listOf(atEvent("ev-b", "r-b")))
        seed("ev-b-active", rules = listOf(atEvent("ev-b-active", "r-b-active"))) // позитивный контроль

        deliverBootCompleted()

        assertEquals("у выполненного события будильников нет", 0, alarmsFor("ev-b").size)
        assertEquals("контроль: активное событие восстановлено", 1, alarmsFor("ev-b-active").size)
    }

    @Test
    fun c_cancelledEvent_getsNoAlarmAfterBoot() {
        seed("ev-c", status = CalendarItemStatus.CANCELLED, rules = listOf(atEvent("ev-c", "r-c")))
        seed("ev-c-active", rules = listOf(atEvent("ev-c-active", "r-c-active")))

        deliverBootCompleted()

        assertEquals("у отменённого события будильников нет", 0, alarmsFor("ev-c").size)
        assertEquals("контроль: активное событие восстановлено", 1, alarmsFor("ev-c-active").size)
    }

    // ─── D. Несколько правил — независимые будильники ───

    @Test
    fun d_multipleRules_restoreTwoIndependentAlarms() {
        seed("ev-d", rules = listOf(atEvent("ev-d", "r-d1"), hourBefore("ev-d", "r-d2")))

        deliverBootCompleted()

        val alarms = alarmsFor("ev-d")
        assertEquals("два правила — два будильника", 2, alarms.size)
        assertEquals("разные времена срабатывания",
            setOf(millis(eventStart), millis(eventStart.minusHours(1))), alarms.map { it.triggerAtTime }.toSet())
        assertEquals("разные PendingIntent (одно правило не перезаписало другое)",
            2, alarms.map { it.operation }.distinct().size)
    }

    // ─── E. Повторный BOOT_COMPLETED не создаёт дублей ───

    @Test
    fun e_bootCompletedTwice_isIdempotent() {
        seed("ev-e", rules = listOf(atEvent("ev-e", "r-e1"), hourBefore("ev-e", "r-e2")))

        deliverBootCompleted()
        val afterFirst = reminderAlarms().size
        val staleAfterFirst = staleCheckAlarms().size
        deliverBootCompleted()

        assertEquals("после первого бута — два будильника события", 2, afterFirst)
        assertEquals("второй бут не добавил будильников напоминаний", afterFirst, reminderAlarms().size)
        assertEquals("второй бут не задвоил ежедневную проверку", staleAfterFirst, staleCheckAlarms().size)
    }

    // ─── F. Ежедневная проверка «пора связаться» ───

    @Test
    fun f_bootSchedulesDailyStaleContactCheck() {
        seed("ev-f", rules = listOf(atEvent("ev-f", "r-f")))

        deliverBootCompleted()

        assertEquals("после бута запланирована ровно одна ежедневная проверка", 1, staleCheckAlarms().size)
    }

    // ─── G. Ошибка не оставляет PendingResult незавершённым ───

    @Test
    fun g_failureDuringReschedule_stillFinishesAndSchedulesDailyCheck() {
        seed("ev-g", rules = listOf(atEvent("ev-g", "r-g")))
        db.close() // чтение Room бросит — rescheduleAll упадёт

        deliverBootCompleted() // сам проверяет PendingResult.finish()

        assertEquals("упавшее восстановление не должно отменять ежедневную проверку", 1, staleCheckAlarms().size)
        // tearDown закрывает БД повторно — для Room это безопасно.
    }

    // ─── Прошедшие одноразовые напоминания не восстанавливаются ───

    @Test
    fun h_pastOneShotReminder_isNotRestored() {
        val past = LocalDateTime.now().minusDays(2).withHour(15).withMinute(0).withSecond(0).withNano(0)
        seed("ev-h", start = past, rules = listOf(atEvent("ev-h", "r-h")))
        seed("ev-h-future", rules = listOf(atEvent("ev-h-future", "r-h-future")))

        deliverBootCompleted()

        assertNull("прошедшее одноразовое напоминание не восстанавливается", alarmsFor("ev-h").firstOrNull())
        assertEquals("контроль: будущее восстановлено", 1, alarmsFor("ev-h-future").size)
    }
}
