package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.R
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.ReminderRule
import com.aistudio.socialsphere.crmlxb.model.ReminderType
import com.aistudio.socialsphere.crmlxb.model.ReminderOffsetUnit
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.data.local.toDomain

object NotificationScheduler {

    /**
     * Ставит будильник: точный, если есть разрешение (Android <12 — всегда;
     * 12+ — по canScheduleExactAlarms()), иначе неточный, но переживающий doze
     * (setAndAllowWhileIdle). Проверяем разрешение ЗАРАНЕЕ, чтобы не ловить
     * SecurityException на каждом будильнике (он спамил лог).
     */
    private fun scheduleAlarm(am: AlarmManager, triggerAtMillis: Long, pi: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                return
            } catch (e: SecurityException) {
                Log.w("NotificationScheduler", "Exact alarm denied at runtime, falling back to inexact")
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    fun scheduleReminder(context: Context, calendarItem: CalendarItem, reminderRule: ReminderRule) {
        if (!AppSettings.isNotificationsEnabled.value) return
        
        NotificationHelper.createNotificationChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val notificationTimeMillis = calculateNotificationTime(calendarItem, reminderRule)
        if (notificationTimeMillis == null || notificationTimeMillis < System.currentTimeMillis()) {
            return // Date is in the past or invalid
        }

        // Связанные контакты — для кнопок «Позвонить»/«Написать» и для текста
        // уведомления (имя+повод+время, не просто заголовок дважды). Берём здесь
        // (приложение живо), т.к. на «холодном» будильнике память AppStateStore
        // может быть пустой.
        val linkedContacts = calendarItem.links
            .mapNotNull { com.aistudio.socialsphere.crmlxb.data.AppStateStore.getContact(it.targetId) }
        val linkedContact = linkedContacts.firstOrNull()
        val phone = linkedContact?.let { c ->
            (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull())?.number
        }

        val displayTitle = calendarItem.displayTitle(context)
        val contactNames = linkedContacts.joinToString(", ") { "${it.firstName} ${it.lastName}".trim() }
        val contentDetail = listOfNotNull(
            contactNames.takeIf { it.isNotBlank() },
            calendarItem.startTime?.takeIf { !calendarItem.isAllDay && it.isNotBlank() }
        ).joinToString(" · ")
        val content = contentDetail.ifBlank {
            context.getString(R.string.notif_reminder_content, displayTitle)
        }

        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", calendarItem.id)
            putExtra("title", displayTitle)
            putExtra("content", content)
            putExtra("notificationId", getNotificationId(calendarItem.id, reminderRule.id))
            if (!phone.isNullOrBlank()) putExtra("phone", phone)
            // ДР — на свой канал, остальное — канал событий
            putExtra(
                "channel",
                if (calendarItem.type == com.aistudio.socialsphere.crmlxb.model.CalendarItemType.BIRTHDAY)
                    NotificationHelper.CHANNEL_BIRTHDAY else NotificationHelper.CHANNEL_ID
            )
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getNotificationId(calendarItem.id, reminderRule.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(alarmManager, notificationTimeMillis, pendingIntent)
    }

    private const val STALE_REQUEST_CODE = 770001

    /** Планирует ежедневную проверку «пора связаться» на ближайшие 10:00.
     *  Receiver после показа переустанавливает себя на следующий день. */
    fun scheduleStaleCheck(context: Context) {
        AppSettings.init(context) // идемпотентно; нужно на «холодном» бутe
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, StaleContactsReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, STALE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val anyDaily = AppSettings.remindStaleContacts.value ||
            AppSettings.remindBirthdays.value || AppSettings.remindNoNextStep.value
        if (!AppSettings.isNotificationsEnabled.value || !anyDaily) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(10, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val fireAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        scheduleAlarm(alarmManager, fireAt, pendingIntent)
    }

    /** «Отложить»: переустановить то же уведомление на сутки вперёд. Переносим все
     *  extra из исходного интента (без action), чтобы кнопки звонка/SMS остались. */
    fun scheduleSnooze(context: Context, src: Intent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nid = src.getIntExtra("notificationId", 0)
        val fireAt = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", src.getStringExtra("calendarItemId"))
            putExtra("title", src.getStringExtra("title"))
            putExtra("content", src.getStringExtra("content"))
            putExtra("notificationId", nid)
            // Без этого «Пора связаться»/«День рождения» после «Отложить» переоткрывались
            // бы в дефолтном канале событий вместо своего (баг найден вместе с §36).
            src.getStringExtra("channel")?.let { putExtra("channel", it) }
            src.getStringExtra("phone")?.let { putExtra("phone", it) }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, nid, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleAlarm(alarmManager, fireAt, pendingIntent)
    }

    fun cancelReminder(context: Context, calendarItemId: String, reminderRuleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getNotificationId(calendarItemId, reminderRuleId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun cancelAllRemindersForCalendarItem(context: Context, oldReminders: List<ReminderRule>) {
        oldReminders.forEach {
            cancelReminder(context, it.calendarItemId, it.id)
        }
    }

    fun rescheduleReminders(context: Context, contextOldReminders: List<ReminderRule>, calendarItem: CalendarItem) {
        cancelAllRemindersForCalendarItem(context, contextOldReminders)
        calendarItem.reminders.forEach {
            scheduleReminder(context, calendarItem, it)
        }
    }

    suspend fun rescheduleAll(context: Context, db: SocialsphereDatabase) {
        val calendars = db.calendarDao().getAllCalendarItems()
        val calendarLinks = db.calendarDao().getCalendarItemLinks()
        val reminderRules = db.calendarDao().getReminderRules()

        calendars.forEach { entity ->
            val links = calendarLinks.filter { it.calendarItemId == entity.id }.map { it.toDomain() }
            val reminders = reminderRules.filter { it.calendarItemId == entity.id }.map { it.toDomain() }
            val item = entity.toDomain().copy(links = links, reminders = reminders)
            // Только ACTIVE: иначе завершённые/отменённые/отложенные события
            // ре-армятся при каждой перезагрузке телефона (баг §34).
            if (item.status != com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus.ACTIVE) return@forEach

            item.reminders.forEach { rule ->
                scheduleReminder(context, item, rule)
            }
        }
    }

    private fun getNotificationId(calendarItemId: String, reminderRuleId: String): Int {
        return (calendarItemId + "|" + reminderRuleId).hashCode()
    }

    /**
     * Будет ли это напоминание реально отправлено. ЕДИНЫЙ источник истины с
     * scheduleReminder(): та же calculateNotificationTime + то же сравнение с «сейчас».
     * ФИКС §28: UI раньше сравнивал только ДАТУ события и показывал «Запланировано»
     * для напоминаний, чьё время уже прошло и будильник НЕ ставился (подтверждено
     * dumpsys alarm) — владелец видел «Запланировано» у мёртвого напоминания.
     */
    fun isReminderScheduled(calendarItem: CalendarItem, reminderRule: ReminderRule): Boolean {
        val t = calculateNotificationTime(calendarItem, reminderRule) ?: return false
        return t >= System.currentTimeMillis()
    }

    private fun calculateNotificationTime(calendarItem: CalendarItem, reminderRule: ReminderRule): Long? {
        try {
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

            // Base DateTime. startDate по проекту — ISO, но в данных бывают legacy-формы
            // («15 08 2025», «30 мая»); parseFlexibleDate их разбирает, иначе напоминание
            // тихо терялось (DateTimeParseException). null — форму не распознать → пропуск.
            val baseDate = parseFlexibleDate(calendarItem.startDate) ?: run {
                Log.w("NotificationScheduler", "Unparseable startDate: '${calendarItem.startDate}' (item ${calendarItem.id})")
                return null
            }
            val baseTime = if (calendarItem.isAllDay) {
                // Default all day time is 09:00
                LocalTime.of(9, 0)
            } else if (!calendarItem.startTime.isNullOrEmpty()) {
                LocalTime.parse(calendarItem.startTime, timeFormatter)
            } else {
                LocalTime.of(9, 0)
            }
            
            var targetDateTime = LocalDateTime.of(baseDate, baseTime)

            when (reminderRule.reminderType) {
                ReminderType.AT_TIME -> {
                    // targetDateTime remains unchanged
                }
                ReminderType.BEFORE -> {
                    val value = reminderRule.offsetValue?.toLong() ?: return null
                    targetDateTime = when (reminderRule.offsetUnit) {
                        ReminderOffsetUnit.MINUTES -> targetDateTime.minusMinutes(value)
                        ReminderOffsetUnit.HOURS -> targetDateTime.minusHours(value)
                        ReminderOffsetUnit.DAYS -> targetDateTime.minusDays(value)
                        ReminderOffsetUnit.WEEKS -> targetDateTime.minusWeeks(value)
                        else -> targetDateTime
                    }
                }
                ReminderType.CUSTOM_DATE_TIME -> {
                    if (!reminderRule.exactDateTime.isNullOrEmpty()) {
                         targetDateTime = LocalDateTime.parse(reminderRule.exactDateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    }
                }
                ReminderType.NONE -> {
                    return null
                }
            }

            // Adjust for yearly recurrence if it's in the past (e.g. birthday)
            if (calendarItem.recurrenceRule?.contains("YEARLY") == true) {
                val now = LocalDateTime.now()
                if (targetDateTime.isBefore(now)) {
                    val candidate = try {
                        targetDateTime.withYear(now.year).let { t ->
                            if (t.isBefore(now)) t.withYear(now.year + 1) else t
                        }
                    } catch (e: java.time.DateTimeException) {
                        // Feb 29 on non-leap year — shift to Mar 1
                        targetDateTime
                            .withMonth(3).withDayOfMonth(1)
                            .withYear(now.year).let { t ->
                                if (t.isBefore(now)) t.withYear(now.year + 1) else t
                            }
                    }
                    targetDateTime = candidate
                }
            }

            return targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.e("NotificationScheduler", "Error calculating date", e)
            return null
        }
    }
}
