package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    
    fun scheduleReminder(context: Context, calendarItem: CalendarItem, reminderRule: ReminderRule) {
        if (!AppSettings.isNotificationsEnabled.value) return
        
        NotificationHelper.createNotificationChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val notificationTimeMillis = calculateNotificationTime(calendarItem, reminderRule)
        if (notificationTimeMillis == null || notificationTimeMillis < System.currentTimeMillis()) {
            return // Date is in the past or invalid
        }

        // Телефон связанного контакта — для кнопок «Позвонить»/«Написать» в самом
        // уведомлении. Берём здесь (приложение живо), т.к. на «холодном» будильнике
        // память AppStateStore может быть пустой.
        val linkedContact = calendarItem.links
            .mapNotNull { com.aistudio.socialsphere.crmlxb.data.AppStateStore.getContact(it.targetId) }
            .firstOrNull()
        val phone = linkedContact?.let { c ->
            (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull())?.number
        }

        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("calendarItemId", calendarItem.id)
            putExtra("title", calendarItem.title)
            putExtra("content", "Напоминание: ${calendarItem.title}")
            putExtra("notificationId", getNotificationId(calendarItem.id, reminderRule.id))
            if (!phone.isNullOrBlank()) putExtra("phone", phone)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getNotificationId(calendarItem.id, reminderRule.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                notificationTimeMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            Log.e("NotificationScheduler", "Exact alarm permission missing", e)
            // Fallback to inexact
             alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                notificationTimeMillis,
                pendingIntent
            )
        }
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
            src.getStringExtra("phone")?.let { putExtra("phone", it) }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, nid, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
        }
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

            item.reminders.forEach { rule ->
                scheduleReminder(context, item, rule)
            }
        }
    }

    private fun getNotificationId(calendarItemId: String, reminderRuleId: String): Int {
        return (calendarItemId + "|" + reminderRuleId).hashCode()
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
