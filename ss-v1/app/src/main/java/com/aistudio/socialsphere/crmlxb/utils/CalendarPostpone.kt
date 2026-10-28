package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.LocalDate

/**
 * ЕДИНАЯ business-операция «перенести ТЕКУЩЕЕ pending occurrence» для
 * recurring CalendarItem (2026-09-17, requirement 5). Non-recurring (effective
 * NONE) её НЕ использует — «Перенести» для них остаётся старым прямым путём
 * (event.copy(startDate=...) + updateCalendarItem + rescheduleReminders,
 * CalendarItemDetailScreen), т.к. смысл там другой: меняется САМА дата
 * единственного события, а не occurrence recurring-серии (requirement 1).
 */
object CalendarPostpone {

    // Долгоживущий scope — тот же паттерн, что CalendarCompletion.completionScope:
    // не привязан к жизненному циклу вызывающего экрана, гарантирует доказуемый
    // порядок write -> reschedule (requirement 5, «Write → reschedule ordering
    // обязателен, не fire-and-forget между persistence и scheduling») даже если
    // caller не ждёт Deferred сам.
    private val postponeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Переносит ТЕКУЩЕЕ pending occurrence recurring-серии на [targetDate].
     *
     * 1. Перечитывает актуальный CalendarItem (валидирует против ТЕКУЩЕГО
     *    состояния, не переданного снимка — та же дисциплина, что
     *    CalendarCompletion.complete()).
     * 2. status обязан быть ACTIVE.
     * 3. seriesDate определяется ВНУТРИ операции через item.pendingOccurrence()
     *    — anti-drift (requirement 6, re-postpone): если occurrence УЖЕ
     *    postponed, pendingOccurrence() вернёт СУЩЕСТВУЮЩИЙ postponedFrom как
     *    seriesDate (identity не меняется), а не effectiveDate. Recurrence-
     *    математика не переизобретается — через canonical helper'ы
     *    CalendarUtils.
     * 4. targetDate валидируется через isValidPostponeTarget (requirement 3/4
     *    — occurs-on текущей серии, после lastCompletedOccurrenceDate, строго
     *    после seriesDate И строго до следующего regular occurrence).
     * 5-6. Пишутся ТОЛЬКО postponedFromOccurrenceDate/postponedToDate —
     *    startDate/recurrenceRule/lastCompletedOccurrenceDate НЕ трогаются.
     * 7-8. reschedule — ТОЛЬКО после подтверждённого commit'а (.await()
     *    перед NotificationScheduler.rescheduleReminders — тот же паттерн,
     *    что CalendarCompletion.complete()).
     *
     * Не suspend — вызывается как раньше (fire-and-forget) из UI, возвращает
     * Deferred<Boolean> для тех, кому нужен доказуемый итог (тесты).
     */
    fun postponeCurrentOccurrence(
        context: Context,
        item: CalendarItem,
        targetDate: LocalDate,
    ): Deferred<Boolean> =
        postponeScope.async {
            // CALENDAR DATA SAFETY PACK: ВСЁ валидируется и пишется по КАНОНИЧЕСКОМУ состоянию ([current]), а не по
            // переданному снимку [item] (он лишь идентифицирует событие): устаревший экран не переносит уже завершённое
            // occurrence и не откатывает независимо изменённые ссылки/напоминания/recurrence.
            val current = AppStateStore.calendarItems.firstOrNull { it.id == item.id } ?: return@async false
            if (current.status != CalendarItemStatus.ACTIVE) return@async false

            val mode = effectiveRecurrenceMode(current.type, current.recurrenceRule, current.recurrenceExplicitlySet)
            if (mode == RecurrenceMode.NONE) return@async false // non-recurring — не эта операция

            val anchor = parseFlexibleDate(current.startDate) ?: return@async false
            val pending = current.pendingOccurrence() ?: return@async false
            val seriesDate = pending.seriesDate
            val lastCompleted = current.lastCompletedOccurrenceDate?.let { parseFlexibleDate(it) }
            if (!isValidPostponeTarget(anchor, mode, lastCompleted, seriesDate, targetDate)) return@async false

            AppStateStore.postponeCalendarItemOccurrence(current, seriesDate, targetDate).await()
            // Напоминания планируются по ИТОГОВОМУ сохранённому состоянию (после commit), снимаются — те, что стояли до него.
            AppStateStore.getCalendarItemById(item.id)?.let { NotificationScheduler.rescheduleReminders(context, current.reminders, it) }
            true
        }

    /**
     * «Перенести» РАЗОВОГО (non-recurring) события — меняется сама дата (см. шапку объекта). Быстрое действие с экрана события:
     * это ПРАВКА одного поля, а не перезапись снимка — идёт через 3-way `updateCalendarItem(base = снимок экрана, edited)`,
     * поэтому не откатывает независимые изменения статуса/связей/напоминаний. Напоминания переставляются ПОСЛЕ commit по
     * итоговому сохранённому состоянию.
     */
    fun rescheduleSingleEvent(context: Context, item: CalendarItem, newStartDate: String): Deferred<Boolean> =
        postponeScope.async {
            val current = AppStateStore.calendarItems.firstOrNull { it.id == item.id } ?: return@async false
            AppStateStore.updateCalendarItem(item, item.copy(startDate = newStartDate)).await()
            AppStateStore.getCalendarItemById(item.id)?.let { NotificationScheduler.rescheduleReminders(context, current.reminders, it) }
            true
        }
}
