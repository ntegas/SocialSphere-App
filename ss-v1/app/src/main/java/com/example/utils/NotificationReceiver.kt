package com.example.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calendarItemId = intent.getStringExtra("calendarItemId") ?: return
        val title = intent.getStringExtra("title") ?: "Напоминание"
        val content = intent.getStringExtra("content") ?: ""
        val notificationId = intent.getIntExtra("notificationId", calendarItemId.hashCode())

        NotificationHelper.createNotificationChannel(context)
        NotificationHelper.showNotification(
            context = context,
            notificationId = notificationId,
            title = title,
            content = content,
            targetCalendarItemId = calendarItemId
        )
    }
}
