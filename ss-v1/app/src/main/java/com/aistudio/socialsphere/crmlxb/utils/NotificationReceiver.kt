package com.aistudio.socialsphere.crmlxb.utils

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistudio.socialsphere.crmlxb.R

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ФИКС (§36, найдено при подключении «Повторять уведомление о просроченных»):
        // раньше calendarItemId был обязателен (`?: return`) — «Отложить»/«Готово»
        // молча ничего не делали для любого уведомления БЕЗ привязки к событию
        // (targetCalendarItemId = null): «Пора связаться», день рождения без события,
        // сводка «без следующего шага». Кнопки были нарисованы на каждом уведомлении
        // (NotificationHelper.showNotification добавляет их безусловно), но реально
        // работали только для напоминаний календаря — тихий «мёртвый обработчик».
        val calendarItemId = intent.getStringExtra("calendarItemId")
        val title = intent.getStringExtra("title") ?: context.getString(R.string.notif_default_title)
        val content = intent.getStringExtra("content") ?: ""
        val notificationId = intent.getIntExtra("notificationId", (calendarItemId ?: title).hashCode())
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
