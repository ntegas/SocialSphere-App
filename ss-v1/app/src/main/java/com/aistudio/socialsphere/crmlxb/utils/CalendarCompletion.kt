package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import com.aistudio.socialsphere.crmlxb.model.ReminderRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.LocalDate

/**
 * ЕДИНАЯ бизнес-семантика действия «Готово» у события календаря — non-recurring
 * (вся запись/разовое событие) И recurring occurrence completion (одно занятие
 * серии). Один источник для кнопки на CalendarItemDetailScreen, редактора и
 * кнопки «Готово» в уведомлении — чтобы ни один из них не разошёлся в правилах.
 *
 * Правила самого завершения (какое поле обновляется; lastContactDate = сегодня
 * у связанных контактов только для CALL/MEETING/MESSAGE; одна транзакция) живут
 * в AppStateStore.markCalendarItemCompleted() и здесь НЕ дублируются — второй
 * алгоритм contact-side-effect не заводится.
 */
object CalendarCompletion {

    /** Даты, а не дела: у них нечего «выполнить», поэтому экран прячет «Готово». */
    private val DATE_ONLY_TYPES = setOf(
        CalendarItemType.BIRTHDAY,
        CalendarItemType.ANNIVERSARY,
        CalendarItemType.NAMEDAY,
        CalendarItemType.IMPORTANT_DATE
    )

    fun isDateOnly(type: CalendarItemType): Boolean = type in DATE_ONLY_TYPES

    /** Показывать ли «Готово» вообще (экран прячет кнопку для дат). */
    fun offersCompletion(type: CalendarItemType): Boolean = !isDateOnly(type)

    /** Можно ли завершить прямо сейчас: не дата и ещё не выполнено —
     *  ровно условие видимости + enabled кнопки «Готово» на экране события. */
    fun canComplete(item: CalendarItem): Boolean =
        offersCompletion(item.type) && item.status != CalendarItemStatus.COMPLETED

    /**
     * Против какой серии валидировать target-occurrence identity (requirement 7).
     * Явный тип вместо boolean с неясным смыслом (напр. skipValidation) — два
     * происхождения target'а РАЗЛИЧНЫ по семантике, не просто "включить/выключить
     * проверку".
     *
     * [CurrentSeries] — обычный путь (детальный экран, уведомление): target
     * обязан быть occurrence АКТУАЛЬНОЙ (только что переданной в [complete])
     * серии item'а. Если серию успели изменить и target больше не occurrence
     * этой серии — no-op (stale identity). Это поведение не должно ослабляться
     * ни для одного вызывающего пути, кроме явного [FixedSeries] ниже.
     *
     * [FixedSeries] — используется ТОЛЬКО когда редактор в ОДНОМ сохранении
     * одновременно меняет recurrence и переводит item в COMPLETED: target —
     * зафиксированная identity occurrence ИСХОДНОЙ серии (anchor/mode ДО
     * применения правок), которую пользователь реально видел на экране и решил
     * завершить. Новая (уже применённая к item) серия начинается для occurrence,
     * которые наступят СТРОГО ПОСЛЕ этой завершённой даты.
     */
    sealed class OccurrenceSeries {
        object CurrentSeries : OccurrenceSeries()
        data class FixedSeries(val anchor: LocalDate, val mode: RecurrenceMode) : OccurrenceSeries()
    }

    // Долгоживущий scope — НЕ привязан к жизненному циклу вызывающего экрана
    // (Compose composition, BroadcastReceiver'а). requirement 10 (write ->
    // reschedule): и коммит транзакции, и последующий reschedule обязаны
    // произойти доказуемо, даже если UI уже ушёл с экрана сразу после клика
    // (onNavigateBack() вызывается немедленно, как и раньше — complete() не
    // suspend, поэтому caller'ы не меняются, только awaitят Deferred, если он
    // им реально нужен).
    private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ЕДИНАЯ точка входа для ЛЮБОГО completion.
     *
     * Non-recurring (effective recurrence == NONE): status -> COMPLETED,
     * будильники сняты — прежняя семантика, не изменена.
     *
     * Recurring (DAILY/WEEKLY/MONTHLY/YEARLY): завершается ОДНО occurrence —
     * status остаётся ACTIVE, lastCompletedOccurrenceDate = targetOccurrenceDate,
     * будильники текущего occurrence сняты и переставлены на следующее pending.
     *
     * targetOccurrenceDate — occurrence identity (requirement 4/6). Если известен
     * заранее (UI/notification уже зафиксировали его ДО клика/действия) —
     * caller обязан передать именно его: эта функция НЕ вычисляет occurrence
     * заново для уже переданной identity, поэтому два подряд вызова с ОДНОЙ и
     * той же датой не могут продвинуть серию дважды (double-Done, requirement 8).
     * Если null — вычисляется один раз здесь через item.pendingOccurrenceDate().
     *
     * oldReminders — правила, чьи АКТУАЛЬНО поставленные будильники нужно снять
     * (requirement J/9): по умолчанию null -> используется item.reminders (кнопка
     * на экране/уведомление не меняют reminders в этом же действии, старое и
     * новое совпадает). Редактор передаёт СЮДА originalItem.reminders явно: если
     * в ТОМ ЖЕ сохранении reminders тоже отредактированы, снимать нужно старые
     * ID (item.reminders в этом случае уже НОВЫЕ, с другими ID — снятие по ним
     * было бы no-op'ом и оставило бы старый alarm висеть).
     *
     * Не suspend — вызывается как раньше (fire-and-forget) отовсюду, кто не
     * обязан знать результат. Возвращает Deferred<Boolean> для тех, кому нужен
     * доказуемый порядок и итог (NotificationReceiver awaits перед cancel
     * уведомления — requirement 10).
     */
    fun complete(
        context: Context,
        item: CalendarItem,
        targetOccurrenceDate: LocalDate? = null,
        oldReminders: List<ReminderRule>? = null,
        occurrenceSeries: OccurrenceSeries = OccurrenceSeries.CurrentSeries,
        editBase: CalendarItem? = null,
    ): Deferred<Boolean> =
        completionScope.async {
            // requirement 7: перечитать актуальный CalendarItem перед completion.
            // CALENDAR DATA SAFETY PACK: ВСЁ, что определяет исход (статус, серия, lastCompleted, напоминания), читается из
            // КАНОНИЧЕСКОГО состояния ([current]) либо из слияния правок редактора с ним ([view]) — не из снимка вызывающего.
            // [editBase] != null — completion В ТОМ ЖЕ сохранении редактора ([item] = edited): правки применяются 3-way
            // (см. AppStateStore.updateCalendarItem) и не теряются, даже если само completion не состоится.
            val current = AppStateStore.calendarItems.firstOrNull { it.id == item.id } ?: return@async false
            val view = if (editBase != null) AppStateStore.previewCalendarEdit(editBase, item) ?: current else current

            /** Completion не состоялось (уже завершено / не ACTIVE / stale identity): правки редактора всё равно сохраняются. */
            suspend fun withoutCompletion(result: Boolean): Boolean {
                if (editBase != null) {
                    AppStateStore.updateCalendarItem(editBase, item.copy(status = editBase.status)).await()
                    AppStateStore.getCalendarItemById(item.id)?.let {
                        NotificationScheduler.rescheduleReminders(context, oldReminders ?: current.reminders, it)
                    }
                }
                return result
            }

            if (current.status == CalendarItemStatus.COMPLETED) return@async withoutCompletion(true) // уже завершено — идемпотентно true
            if (current.status != CalendarItemStatus.ACTIVE) return@async withoutCompletion(false) // CANCELLED и т.п. -> no-op
            if (!offersCompletion(view.type)) return@async withoutCompletion(false)

            // Снимать нужно будильники, которые РЕАЛЬНО стоят — по текущим правилам события (правила редактора после слияния
            // сохраняют id неизменённых напоминаний, а изменённые/удалённые отсюда снимаются).
            val remindersToCancel = oldReminders ?: current.reminders
            val mode = effectiveRecurrenceMode(view.type, view.recurrenceRule, view.recurrenceExplicitlySet)
            if (mode == RecurrenceMode.NONE) {
                NotificationScheduler.cancelAllRemindersForCalendarItem(context, remindersToCancel)
                AppStateStore.markCalendarItemCompleted(item, null, editBase).await()
                return@async true
            }

            // Recurring: одно occurrence, серия остаётся ACTIVE. Фолбэк, если
            // caller не передал target явно, — через pendingOccurrence().seriesDate
            // (2026-09-17, postpone runtime semantics), НЕ старый pendingOccurrenceDate():
            // если occurrence сейчас active postponed, seriesDate — это identity
            // occurrence (Sep16), а не дата переноса (Sep18) — requirement 10.
            val target = targetOccurrenceDate ?: view.pendingOccurrence()?.seriesDate ?: return@async withoutCompletion(false)
            // requirement 7: target реально относится к серии, против которой его
            // нужно валидировать. Обычно это АКТУАЛЬНАЯ серия item'а (CurrentSeries)
            // — если recurrence изменили ПОСЛЕ того, как было создано старое
            // notification/дата зафиксирована на экране, и target больше не
            // occurrence — no-op (stale identity, а не молчаливая порча данных).
            // Editor-переход "recurrence изменена И завершено в одном save"
            // передаёт FixedSeries(originalAnchor, originalMode) явно — target
            // валидируется против ИСХОДНОЙ серии, которую пользователь видел, а
            // НЕ против уже применённой к item новой (иначе target почти никогда
            // не occurrence новой серии -> occursOn false -> ни completion, ни
            // остальные правки этого save не сохранились бы вовсе).
            val (validationAnchor, validationMode) = when (occurrenceSeries) {
                OccurrenceSeries.CurrentSeries -> (parseFlexibleDate(view.startDate) ?: return@async withoutCompletion(false)) to mode
                is OccurrenceSeries.FixedSeries -> occurrenceSeries.anchor to occurrenceSeries.mode
            }
            if (!RecurrenceCalculator.occursOn(validationAnchor, validationMode, target)) return@async withoutCompletion(false)
            // requirement 8: идемпотентность — target уже завершён (<=), no-op. lastCompleted — КАНОНИЧЕСКИЙ (view несёт
            // текущее значение): устаревший снимок с lastCompleted=null не позволит завершить occurrence повторно/откатить дату.
            val lastCompleted = view.lastCompletedOccurrenceDate?.let { parseFlexibleDate(it) }
            if (lastCompleted != null && !target.isAfter(lastCompleted)) return@async withoutCompletion(true)

            // requirement 10: reschedule только ПОСЛЕ подтверждённого commit'а — .await() гарантирует доказуемый порядок
            // write -> reschedule. CALENDAR DATA SAFETY PACK: планируем по ИТОГОВОМУ сохранённому состоянию события
            // (слитая серия/напоминания, lastCompleted, постпон), а не по снимку/объекту редактора.
            AppStateStore.markCalendarItemCompleted(item, target, editBase).await()
            AppStateStore.getCalendarItemById(item.id)?.let { NotificationScheduler.rescheduleReminders(context, remindersToCancel, it) }
            true
        }
}
