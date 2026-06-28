package com.aistudio.socialsphere.crmlxb.utils

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calendarItemId = intent.getStringExtra("calendarItemId") ?: return
        val title = intent.getStringExtra("title") ?: "Напоминание"
        val content = intent.getStringExtra("content") ?: ""
        val notificationId = intent.getIntExtra("notificationId", calendarItemId.hashCode())
        val phone = intent.getStringExtra("phone")

        // Кнопки уведомления: «Готово» — закрыть; «Отложить» — закрыть и
        // переустановить на сутки вперёд. Иначе (срабатывание будильника) — показать.
        when (intent.getStringExtra("action")) {
            "done" -> {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(notificationId)
                return
            }
            "snooze" -> {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(notificationId)
                NotificationScheduler.scheduleSnooze(context, intent)
                return
            }
        }

        NotificationHelper.createNotificationChannel(context)
        NotificationHelper.showNotification(
            context = context,
            notificationId = notificationId,
            title = title,
            content = content,
            targetCalendarItemId = calendarItemId,
            phone = phone,
            channelId = intent.getStringExtra("channel") ?: NotificationHelper.CHANNEL_ID
        )
    }
}
