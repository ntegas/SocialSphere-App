package com.aistudio.socialsphere.crmlxb.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.CommunicationRhythm
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ежедневная проверка «пора связаться»: по ритму общения и дате последнего
 * контакта. Читает БД напрямую (cold-safe), показывает уведомления с кнопками
 * «Позвонить»/«Написать» и переустанавливает себя на следующий день.
 */
class StaleContactsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AppSettings.init(context)
        val remindStale = AppSettings.remindStaleContacts.value
        val remindBday  = AppSettings.remindBirthdays.value
        val remindNoStep = AppSettings.remindNoNextStep.value
        if (!AppSettings.isNotificationsEnabled.value ||
            (!remindStale && !remindBday && !remindNoStep)
        ) {
            pending.finish(); return
        }
        val db = SocialsphereDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contacts = db.contactDao().getAllContacts()
                val phones = db.contactDao().getContactPhones()
                fun phoneOf(cid: String) = phones.firstOrNull { it.contactId == cid && it.isPrimary }
                    ?.number ?: phones.firstOrNull { it.contactId == cid }?.number
                NotificationHelper.createNotificationChannel(context)

                // ── Пора связаться (по ритму) ──
                if (remindStale) {
                    contacts.mapNotNull { c ->
                        val rhythm = try { CommunicationRhythm.valueOf(c.communicationRhythm) }
                            catch (e: Exception) { CommunicationRhythm.NOT_TRACKED }
                        val days = StaleContacts.overdueDays(rhythm, c.lastContactDate)
                            ?: return@mapNotNull null
                        Triple(c, days, phoneOf(c.id))
                    }.sortedByDescending { it.second }.take(15).forEach { (c, days, phone) ->
                        val name = "${c.firstName} ${c.lastName}".trim()
                        NotificationHelper.showNotification(
                            context = context,
                            notificationId = ("stale_" + c.id).hashCode(),
                            title = "Пора связаться",
                            content = "$name — давно не общались ($days дн.)",
                            targetCalendarItemId = null,
                            phone = phone,
                            channelId = NotificationHelper.CHANNEL_REACH_OUT
                        )
                    }
                }

                // ── Дни рождения сегодня (независимо от напоминаний событий) ──
                if (remindBday) {
                    val today = java.time.LocalDate.now()
                    val items = db.calendarDao().getAllCalendarItems()
                    val links = db.calendarDao().getCalendarItemLinks()
                    items.filter { it.type == "BIRTHDAY" }.forEach { item ->
                        val d = parseFlexibleDate(item.startDate) ?: return@forEach
                        if (d.monthValue != today.monthValue || d.dayOfMonth != today.dayOfMonth)
                            return@forEach
                        val contactId = links.firstOrNull {
                            it.calendarItemId == item.id && it.targetType == "CONTACT"
                        }?.targetId
                        val c = contactId?.let { id -> contacts.firstOrNull { it.id == id } }
                        val name = c?.let { "${it.firstName} ${it.lastName}".trim() }
                            ?: item.title
                        NotificationHelper.showNotification(
                            context = context,
                            notificationId = ("bday_" + item.id).hashCode(),
                            title = "Сегодня день рождения",
                            content = name,
                            targetCalendarItemId = item.id,
                            phone = contactId?.let { phoneOf(it) },
                            channelId = NotificationHelper.CHANNEL_BIRTHDAY
                        )
                    }
                }

                // ── Сводка «без следующего шага» (одно уведомление) ──
                if (remindNoStep) {
                    val count = contacts.count {
                        it.contactStatus == "ACTIVE" && it.nextStep.isNullOrBlank()
                    }
                    if (count > 0) {
                        NotificationHelper.showNotification(
                            context = context,
                            notificationId = "no_next_step_summary".hashCode(),
                            title = "Без следующего шага",
                            content = "Контактов без задачи: $count",
                            targetCalendarItemId = null,
                            phone = null,
                            channelId = NotificationHelper.CHANNEL_REACH_OUT
                        )
                    }
                }

                NotificationScheduler.scheduleStaleCheck(context) // следующий день
            } finally {
                pending.finish()
            }
        }
    }
}
