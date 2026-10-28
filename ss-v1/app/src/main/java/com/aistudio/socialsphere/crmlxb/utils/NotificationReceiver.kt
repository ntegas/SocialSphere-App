package com.aistudio.socialsphere.crmlxb.utils

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.CalendarTargetType
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // calendarItemId необязателен (§36): у «Пора связаться» и сводки его нет.
        // Какие кнопки вообще есть у какого уведомления — NotificationHelper.actionsFor.
        val calendarItemId = intent.getStringExtra("calendarItemId")
        val title = intent.getStringExtra("title") ?: context.getString(R.string.notif_default_title)
        val content = intent.getStringExtra("content") ?: ""
        val notificationId = intent.getIntExtra("notificationId", (calendarItemId ?: title).hashCode())
        val phone = intent.getStringExtra("phone")

        when (intent.getStringExtra("action")) {
            // INTERACTION SEMANTICS: «Готово» — это завершение события, а не
            // закрытие уведомления. Раньше здесь был только cancel(): событие
            // оставалось ACTIVE, контакт — просроченным. Уведомление закрывается
            // только когда событие действительно в состоянии «выполнено».
            NotificationHelper.ACTION_DONE -> runAsync(context) {
                // occurrenceDate — identity, зафиксированная в момент постановки
                // будильника (см. NotificationScheduler.scheduleReminder), НЕ
                // вычисляется заново на момент нажатия (requirement 6): уведомление
                // могло прозвонить раньше, «Готово» нажато позже.
                val occurrenceDate = intent.getStringExtra("occurrenceDate")
                    ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                if (completeCalendarItem(context, calendarItemId, occurrenceDate)) cancel(context, notificationId)
            }
            // «Связался» у «Пора связаться»: lastContactDate = сегодня у этого
            // контакта тем же путём, что «Отметить: связались» на главной.
            NotificationHelper.ACTION_CONTACTED -> runAsync(context) {
                val contactId = intent.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID)
                if (markContacted(context, contactId)) cancel(context, notificationId)
            }
            NotificationHelper.ACTION_SNOOZE -> {
                cancel(context, notificationId)
                NotificationScheduler.scheduleSnooze(context, intent)
            }
            // Срабатывание будильника (или повтор после «Отложить»): показать.
            // Сюда приходят только напоминания о событиях — ежедневные уведомления
            // шлёт StaleContactsReceiver напрямую. Тип и статус события читаются
            // из Room в момент показа: «Готово» появляется только если экран
            // события сейчас позволил бы его выполнить.
            else -> runAsync(context) {
                // DEFENSE IN DEPTH (master-переключатель уведомлений): физическая
                // отмена будильников при ON→OFF (NotificationScheduler.cancelAllUserDataAlarms)
                // — первый уровень; этот флаг — второй, НА СЛУЧАЙ старого
                // PendingIntent, гонки или системной доставки после отмены. Не
                // полагаемся только на AlarmManager.cancel(). AppSettings.init()
                // идемпотентен и background-safe (тот же паттерн, что уже
                // использует StaleContactsReceiver.onReceive) — receiver не
                // зависит от того, что MainActivity когда-либо запускалась.
                AppSettings.init(context)
                if (!AppSettings.isNotificationsEnabled.value) return@runAsync
                NotificationHelper.createNotificationChannel(context)
                if (calendarItemId != null) {
                    // Напоминание о событии. Будильник — только сигнал: всё, что
                    // показывается, берётся из Room сейчас. title/content/phone в
                    // extras (старые будильники, поставленные до этой версии) НЕ
                    // используются — иначе после смены номера или удаления контакта
                    // напоминание звонило бы на старый номер со старым именем.
                    // occurrenceDate — identity конкретного occurrence (recurring),
                    // переносится в кнопку «Готово» показанного уведомления как есть.
                    val occurrenceDate = intent.getStringExtra("occurrenceDate")
                    val reminder = resolveCalendarReminder(context, calendarItemId)
                        ?: return@runAsync // события нет или оно не ACTIVE — не показывать
                    NotificationHelper.showNotification(
                        context = context,
                        notificationId = notificationId,
                        title = reminder.title,
                        content = reminder.content,
                        targetCalendarItemId = calendarItemId,
                        occurrenceDate = occurrenceDate,
                        phone = reminder.phone,
                        channelId = reminder.channelId,
                        kind = NotificationHelper.Kind.CALENDAR_REMINDER,
                        calendarItemType = reminder.type,
                        calendarItemCompletable = reminder.completable,
                    )
                } else {
                    // Уведомление без события (легаси-«Отложить» у ежедневных
                    // уведомлений прошлой версии): собрать из Room нечего — показываем
                    // перенесённый текст, бизнес-кнопок нет.
                    NotificationHelper.showNotification(
                        context = context,
                        notificationId = notificationId,
                        title = title,
                        content = content,
                        targetCalendarItemId = null,
                        phone = phone,
                        channelId = intent.getStringExtra("channel") ?: NotificationHelper.CHANNEL_ID,
                        kind = NotificationHelper.Kind.CALENDAR_REMINDER,
                    )
                }
            }
        }
    }

    /**
     * Асинхронная работа receiver'а: goAsync() держит процесс живым, finish()
     * вызывается в finally при любом исходе. Сама корутина не «выстрел в
     * пустоту»: receiver не завершён, пока она не закончилась.
     */
    private fun runAsync(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Нужен до ensureLoaded(): первичная загрузка читает настройки.
                AppSettings.init(context)
                block()
            } catch (t: Throwable) {
                android.util.Log.e("NotificationReceiver", "notification action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun cancel(context: Context, notificationId: Int) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId)
    }

    companion object {
        /** Содержимое напоминания о событии, собранное из Room в момент срабатывания. */
        internal data class CalendarReminderContent(
            val title: String,
            val content: String,
            val phone: String?,
            val type: CalendarItemType,
            val completable: Boolean,
            val channelId: String,
        )

        /**
         * INVARIANT: будильник напоминания — только сигнал «пора проверить событие».
         * Authoritative-состояние читается из Room здесь, в момент срабатывания, а не
         * из того, что было известно при планировании:
         *  - события нет → null (не показывать);
         *  - статус не ACTIVE (выполнено, отменено, отложено) → null (не показывать);
         *  - связанные контакты — по актуальным ссылкам события; удалённый контакт
         *    пропадает из текста, и кнопок связи для него нет;
         *  - имя — из текущих данных контакта, телефон — по общему правилу
         *    preferredPhoneNumber() (основной, иначе первый).
         * Работает в холодном процессе: читает Room напрямую, память AppStateStore не нужна.
         */
        internal suspend fun resolveCalendarReminder(context: Context, calendarItemId: String): CalendarReminderContent? {
            val db = AppStateStore.databaseFor(context)
            val item = db.calendarDao().getAllCalendarItems()
                .firstOrNull { it.id == calendarItemId }?.toDomain() ?: return null
            if (item.status != CalendarItemStatus.ACTIVE) return null

            val contactIds = db.calendarDao().getCalendarItemLinks()
                .map { it.toDomain() }
                .filter { it.calendarItemId == calendarItemId && it.targetType == CalendarTargetType.CONTACT }
                .map { it.targetId }
                .distinct()
            val contactsById = if (contactIds.isEmpty()) emptyMap()
                else db.contactDao().getAllContacts().associateBy { it.id }
            val phones = if (contactIds.isEmpty()) emptyList()
                else db.contactDao().getContactPhones()
            val linkedContacts = contactIds.mapNotNull { id ->
                contactsById[id]?.toDomain()?.copy(
                    phones = phones.filter { it.contactId == id }.map { it.toDomain() }
                )
            }

            val title = item.displayTitle(context)
            val names = linkedContacts.joinToString(", ") { "${it.firstName} ${it.lastName}".trim() }
            val detail = listOfNotNull(
                names.takeIf { it.isNotBlank() },
                item.startTime?.takeIf { !item.isAllDay && it.isNotBlank() }
            ).joinToString(" · ")
            return CalendarReminderContent(
                title = title,
                content = detail.ifBlank { context.getString(R.string.notif_reminder_content, title) },
                phone = linkedContacts.firstOrNull()?.preferredPhoneNumber(),
                type = item.type,
                completable = CalendarCompletion.canComplete(item),
                // ДР — на свой канал, остальное — канал событий
                channelId = if (item.type == CalendarItemType.BIRTHDAY)
                    NotificationHelper.CHANNEL_BIRTHDAY else NotificationHelper.CHANNEL_ID,
            )
        }

        /**
         * «Готово» из уведомления. Работает в холодном процессе: ensureLoaded()
         * подключает хранилище и ждёт первичную загрузку, затем событие
         * завершается ТЕМ ЖЕ путём, что кнопка на экране (CalendarCompletion).
         * occurrenceDate — identity, зафиксированная планировщиком в момент
         * постановки будильника (requirement 6); для non-recurring — null,
         * CalendarCompletion сам решает по effective recurrence.
         * .await() на Deferred<Boolean> из complete() уже гарантирует и commit
         * транзакции, и последующий reschedule ДО возврата отсюда (requirement 10);
         * awaitPendingWrites() — дополнительная defensive-безопасность перед
         * finish(), не единственная гарантия порядка.
         * @return true, если событие после вызова завершено/occurrence
         *  зачтено (в т.ч. если уже было — идемпотентно).
         */
        internal suspend fun completeCalendarItem(
            context: Context,
            calendarItemId: String?,
            occurrenceDate: java.time.LocalDate? = null,
        ): Boolean {
            if (calendarItemId.isNullOrBlank()) return false
            AppStateStore.ensureLoaded(context)
            val item = AppStateStore.calendarItems.firstOrNull { it.id == calendarItemId } ?: return false
            if (item.status == com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus.COMPLETED) return true
            if (!CalendarCompletion.canComplete(item)) return false
            val result = CalendarCompletion.complete(context, item, occurrenceDate).await()
            AppStateStore.awaitPendingWrites()
            return result
        }

        /** «Связался» из уведомления «Пора связаться» — тот же путь, что
         *  «Отметить: связались» на главной; холодный процесс — как выше. */
        internal suspend fun markContacted(context: Context, contactId: String?): Boolean {
            if (contactId.isNullOrBlank()) return false
            AppStateStore.ensureLoaded(context)
            if (AppStateStore.getContact(contactId) == null) return false
            AppStateStore.markContactedNow(contactId)
            AppStateStore.awaitPendingWrites()
            return true
        }
    }
}
