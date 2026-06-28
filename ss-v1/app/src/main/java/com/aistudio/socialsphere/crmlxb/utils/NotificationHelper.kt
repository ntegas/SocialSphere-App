package com.aistudio.socialsphere.crmlxb.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aistudio.socialsphere.crmlxb.MainActivity
import com.aistudio.socialsphere.crmlxb.R // Important: adjust to project R package

object NotificationHelper {
    const val CHANNEL_ID = "socialsphere_reminders"
    const val CHANNEL_NAME = "Socialsphere reminders"
    const val CHANNEL_DESCRIPTION = "Напоминания Socialsphere"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        targetCalendarItemId: String?,
        phone: String? = null
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (targetCalendarItemId != null) {
                putExtra("calendarItemId", targetCalendarItemId)
            }
        }
        
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder) // better to use our icon but fallback is fine
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Кнопки прямо в уведомлении: «Позвонить» (системный набор, без прав) и
        // «Написать» (SMS). Появляются, если у связанного контакта есть телефон.
        if (!phone.isNullOrBlank()) {
            val callIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone"))
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val callPi = PendingIntent.getActivity(
                context, notificationId * 31 + 1, callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_call, "Позвонить", callPi)

            val smsIntent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$phone"))
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val smsPi = PendingIntent.getActivity(
                context, notificationId * 31 + 2, smsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_dialog_email, "Написать", smsPi)
        }

        // «Отложить» (через сутки) и «Готово» (закрыть) — обрабатывает NotificationReceiver.
        fun actionIntent(action: String) = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("action", action)
            putExtra("notificationId", notificationId)
            putExtra("calendarItemId", targetCalendarItemId)
            putExtra("title", title)
            putExtra("content", content)
            if (!phone.isNullOrBlank()) putExtra("phone", phone)
        }
        val snoozePi = PendingIntent.getBroadcast(
            context, notificationId * 31 + 3, actionIntent("snooze"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_recent_history, "Отложить", snoozePi)
        val donePi = PendingIntent.getBroadcast(
            context, notificationId * 31 + 4, actionIntent("done"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.checkbox_on_background, "Готово", donePi)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }
}
