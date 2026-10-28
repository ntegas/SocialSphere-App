package com.aistudio.socialsphere.crmlxb.utils

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ЕДИНЫЙ системный lifecycle-приёмник будильников (имя историческое — раньше слушал только BOOT_COMPLETED).
 *
 * AlarmManager не переживает ряд системных событий, а события календаря хранят ЛОКАЛЬНЫЕ дату/время (не epoch), поэтому
 * после каждого из них будильники приводятся к каноническому состоянию из Room — ОДНИМ и тем же путём
 * [NotificationScheduler.reconcileAllUserDataAlarms] (отдельной реализации планирования на каждое действие нет):
 *  • BOOT_COMPLETED — перезагрузка: будильники сняты системой;
 *  • TIMEZONE_CHANGED — сменился часовой пояс: «09:00 локально» — это уже другой epoch;
 *  • TIME_SET — пользователь/сеть перевели системные часы: пересчёт от канонических дат Room;
 *  • MY_PACKAGE_REPLACED — обновление приложения: система снимает будильники, новая семантика планировщика
 *    начинает действовать сразу, master-флаг соблюдается;
 *  • SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED — выдано разрешение на точные будильники: неточные перепоставляются как
 *    точные под той же identity (замена, а не дубль). Симметричного «отозвано» нет: при отзыве система сама снимает точные
 *    будильники и перезапускает процесс — восстановление идёт со следующего запуска приложения (MainActivity.onCreate) или
 *    любого из событий выше; scheduler при отсутствии разрешения ставит НЕточные эквиваленты.
 *
 * Выполняется в холодном процессе: MainActivity не запускалась, AppStateStore и AppSettings не инициализированы.
 * Всё, что нужно планировщику, он инициализирует сам (NotificationScheduler.settings); каноничный источник — Room, не память.
 * Любое иное действие — no-op.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in RECONCILE_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reconcileForSystemEvent(context, action)
            } catch (t: Throwable) {
                Log.e("BootReceiver", "alarm reconcile after $action failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal val RECONCILE_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )

        /** Единый reconcile для всех системных событий. Та же БД, что у AppStateStore и остальных приёмников (в проде —
         *  общий синглтон). reconcileAllUserDataAlarms сам изолирует rescheduleAll/scheduleStaleCheck каждый в своём
         *  try/catch — сбой одного не отменяет другой. */
        internal suspend fun reconcileForSystemEvent(context: Context, action: String) {
            Log.i("BootReceiver", "reconcile alarms: $action")
            NotificationScheduler.reconcileAllUserDataAlarms(context, AppStateStore.databaseFor(context))
        }
    }
}
