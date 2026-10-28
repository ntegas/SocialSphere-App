package com.aistudio.socialsphere.crmlxb.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aistudio.socialsphere.crmlxb.MainActivity
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.R // Important: adjust to project R package

object NotificationHelper {
    // Раздельные каналы — чтобы пользователь управлял звуком/важностью каждого
    // типа из системных настроек по отдельности.
    const val CHANNEL_ID = "socialsphere_reminders"        // события календаря
    const val CHANNEL_REACH_OUT = "socialsphere_reach_out" // «пора связаться»
    const val CHANNEL_BIRTHDAY  = "socialsphere_birthday"  // дни рождения

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val high = NotificationManager.IMPORTANCE_HIGH
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_events_name), high).apply {
                    description = context.getString(R.string.notif_channel_events_desc)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REACH_OUT, context.getString(R.string.notif_reach_out_title), high).apply {
                    description = context.getString(R.string.notif_channel_reach_out_desc)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_BIRTHDAY, context.getString(R.string.notif_channel_birthday_name), high).apply {
                    description = context.getString(R.string.notif_channel_birthday_desc)
                }
            )
        }
    }

    /** Тип уведомления. Задаётся ЯВНО каждым отправителем (параметр без
     *  значения по умолчанию): набор кнопок следует из смысла уведомления,
     *  а не из того, есть ли у него calendarItemId или телефон. */
    enum class Kind {
        /** Напоминание о событии календаря (будильник NotificationScheduler). */
        CALENDAR_REMINDER,
        /** Ежедневное «сегодня день рождения» (StaleContactsReceiver). */
        BIRTHDAY_TODAY,
        /** Ежедневное «пора связаться» по ритму общения (StaleContactsReceiver). */
        STALE_CONTACT,
        /** Ежедневная сводка «без следующего шага» (StaleContactsReceiver). */
        NO_NEXT_STEP_SUMMARY,
    }

    enum class Action { CALL, MESSAGE, SNOOZE, DONE, CONTACTED }

    /** Android показывает не больше трёх кнопок; лишние молча отбрасываются. */
    const val MAX_ACTIONS = 3

    const val ACTION_SNOOZE = "snooze"
    const val ACTION_DONE = "done"
    const val ACTION_CONTACTED = "contacted"
    const val EXTRA_CONTACT_ID = "contactId"

    /**
     * INTERACTION SEMANTICS: матрица «тип уведомления → кнопки». Каждая кнопка
     * означает ровно то, что выполняется:
     *  - DONE — завершает событие (CalendarCompletion); есть только у событий,
     *    которые экран позволяет выполнить. У дат (ДР/годовщина/именины/важная
     *    дата) и у уже выполненных событий кнопки нет;
     *  - CONTACTED — «Связался»: lastContactDate = сегодня у этого контакта;
     *  - CALL / MESSAGE — только открывают внешний канал, данные не меняют;
     *  - SNOOZE — повтор через сутки; только у разовых напоминаний о событиях.
     *    Ежедневные уведомления и так переприсылает ежедневная проверка, а
     *    «отложенный» «сегодня день рождения» показал бы «сегодня» не в тот день.
     * У информационных уведомлений (день рождения, сводка) бизнес-действия нет —
     * «Готово» там было бы простым закрытием под видом действия.
     */
    fun actionsFor(
        kind: Kind,
        hasPhone: Boolean,
        calendarItemType: CalendarItemType? = null,
        calendarItemCompletable: Boolean = false,
        hasContactId: Boolean = false,
    ): List<Action> = when (kind) {
        Kind.CALENDAR_REMINDER ->
            if (calendarItemCompletable) buildList {
                // Три слота: «Готово» и «Отложить» — бизнес-действия, третий —
                // один канал связи, соответствующий событию.
                if (hasPhone) add(if (calendarItemType == CalendarItemType.MESSAGE) Action.MESSAGE else Action.CALL)
                add(Action.SNOOZE)
                add(Action.DONE)
            } else buildList {
                if (hasPhone) { add(Action.CALL); add(Action.MESSAGE) }
                add(Action.SNOOZE)
            }
        Kind.BIRTHDAY_TODAY ->
            if (hasPhone) listOf(Action.CALL, Action.MESSAGE) else emptyList()
        Kind.STALE_CONTACT -> buildList {
            if (hasPhone) { add(Action.CALL); add(Action.MESSAGE) }
            if (hasContactId) add(Action.CONTACTED)
        }
        Kind.NO_NEXT_STEP_SUMMARY -> emptyList()
    }

    fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        targetCalendarItemId: String?,
        occurrenceDate: String? = null,
        phone: String? = null,
        channelId: String = CHANNEL_ID,
        kind: Kind,
        calendarItemType: CalendarItemType? = null,
        calendarItemCompletable: Boolean = false,
        contactId: String? = null,
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

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder) // better to use our icon but fallback is fine
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Кнопки, обрабатываемые NotificationReceiver'ом. Extras — те же, что у
        // будильника, чтобы «Отложить» переустановило то же уведомление.
        fun receiverIntent(action: String) = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("action", action)
            putExtra("notificationId", notificationId)
            putExtra("calendarItemId", targetCalendarItemId)
            // occurrenceDate — technical identity конкретного occurrence, для
            // которого посчитан этот alarm (requirement 6). Не snapshot
            // имени/телефона: title/contact/phone по-прежнему читаются из Room
            // в момент срабатывания действия (resolveCalendarReminder).
            if (!occurrenceDate.isNullOrBlank()) putExtra("occurrenceDate", occurrenceDate)
            putExtra("title", title)
            putExtra("content", content)
            putExtra("channel", channelId)
            if (!phone.isNullOrBlank()) putExtra("phone", phone)
            if (!contactId.isNullOrBlank()) putExtra(EXTRA_CONTACT_ID, contactId)
        }
        fun receiverPi(action: String, slot: Int) = PendingIntent.getBroadcast(
            context, notificationId * 31 + slot, receiverIntent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actions = actionsFor(
            kind = kind,
            hasPhone = !phone.isNullOrBlank(),
            calendarItemType = calendarItemType,
            calendarItemCompletable = calendarItemCompletable,
            hasContactId = !contactId.isNullOrBlank(),
        )
        check(actions.size <= MAX_ACTIONS) { "notification $kind: ${actions.size} actions > $MAX_ACTIONS" }

        actions.forEach { action ->
            when (action) {
                // «Позвонить»/«Написать» — PendingIntent прямо на системную Activity:
                // код приложения не выполняется, данные не меняются (Batch 6.1).
                Action.CALL -> {
                    val callIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone"))
                        .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    val callPi = PendingIntent.getActivity(
                        context, notificationId * 31 + 1, callIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(android.R.drawable.ic_menu_call, context.getString(R.string.notif_action_call), callPi)
                }
                Action.MESSAGE -> {
                    val smsIntent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$phone"))
                        .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    val smsPi = PendingIntent.getActivity(
                        context, notificationId * 31 + 2, smsIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(android.R.drawable.ic_dialog_email, context.getString(R.string.notif_action_message), smsPi)
                }
                Action.SNOOZE -> builder.addAction(
                    android.R.drawable.ic_menu_recent_history,
                    context.getString(R.string.notif_action_snooze), receiverPi(ACTION_SNOOZE, 3)
                )
                Action.DONE -> builder.addAction(
                    android.R.drawable.checkbox_on_background,
                    context.getString(R.string.notif_action_done), receiverPi(ACTION_DONE, 4)
                )
                Action.CONTACTED -> builder.addAction(
                    android.R.drawable.checkbox_on_background,
                    context.getString(R.string.notif_action_contacted), receiverPi(ACTION_CONTACTED, 5)
                )
            }
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Снять ВСЕ уже показанные уведомления SocialSphere — используется
     * AppStateStore.wipeAllData() как часть canonical cleanup path полного
     * удаления данных: после того, как удалены все пользовательские данные,
     * ни одно старое уведомление приложения (с запечённым в него именем/
     * текстом уже удалённого контакта или события) больше не актуально.
     * cancelAll() — системный NotificationManager, не наш собственный список
     * notificationId: перечислять их вручную не нужно и незачем (единственная
     * альтернатива — cancel(id) по каждому известному id, но откуда взять
     * ПОЛНЫЙ список id уже показанных уведомлений после того, как данные,
     * из которых они считались, уже удалены).
     */
    fun cancelAll(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }
}
