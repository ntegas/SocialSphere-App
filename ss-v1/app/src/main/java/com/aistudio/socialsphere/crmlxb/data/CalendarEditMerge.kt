package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemLink
import com.aistudio.socialsphere.crmlxb.model.ReminderRule
import com.aistudio.socialsphere.crmlxb.utils.effectiveRecurrenceMode

/**
 * CALENDAR DATA SAFETY PACK — 3-way слияние правки события: `current + diff(base → edited)`.
 *
 * Раньше любое сохранение редактора было командой «замени ВСЁ»: `updateCalendarItem(edited)` писал объект целиком и
 * пересоздавал links/reminders из устаревшего снимка, поэтому правка названия откатывала завершение (status, lastCompleted),
 * перенос (postpone-пара), чужую смену recurrence и воскрешала удалённые связи/напоминания.
 *
 * Теперь вызывающий передаёт [base] (снимок, ОТ КОТОРОГО начата правка) и [edited] (что хочет пользователь); поверх
 * канонического [current] ложится только то, что пользователь РЕАЛЬНО изменил. Владение частями события:
 *
 *  • скаляры (title, description, type, startDate, start/endTime, endDate, isAllDay, importance, colorKey, createdAt, status):
 *    edited == base -> current; иначе edited;
 *  • recurrence — ОДИН aggregate (recurrenceRule + recurrenceExplicitlySet): любая из двух половин изменена пользователем ->
 *    берётся edited-ПАРА целиком, иначе current-пара целиком (нельзя получить rule=null + explicit=false, если выбрано NONE);
 *  • lastCompletedOccurrenceDate и postponed-пара — владеют операции completion/postpone, редактор их НЕ пишет: всегда current.
 *    Единственное исключение — постпон-пара сбрасывается, если ИТОГОВАЯ серия (effective mode + anchor startDate) отличается от
 *    серии current (пользователь сменил recurrence/дату — старый override нельзя перенести в новую серию). Пара всегда
 *    целиком: одна половина без другой невозможна;
 *  • links — независимый aggregate с SEMANTIC identity (targetType, targetId): редактор перегенерирует id при каждом сохранении,
 *    по id сопоставлять нельзя. Удалено = было в base и нет в edited; добавлено = есть в edited, не было в base и нет в current;
 *    остальное — current. Добавляемая связь обязана указывать на существующую сущность ([linkTargetExists]) — сирот не создаём;
 *  • reminders — то же, identity = (reminderType, offsetValue, offsetUnit, exactDateTime); неизменённое напоминание остаётся
 *    ТЕКУЩЕЙ строкой (id стабилен, будильник по нему остаётся тем же).
 *
 * Поле, забытое здесь, деградирует к «побеждает edited» — тест `mergeRule_existsForEveryCalendarItemProperty` ловит забытое.
 */
internal object CalendarEditMerge {

    fun merge(
        current: CalendarItem,
        base: CalendarItem,
        edited: CalendarItem,
        linkTargetExists: (CalendarItemLink) -> Boolean = { true },
    ): CalendarItem {
        fun <T> pick(b: T, e: T, c: T): T = if (e == b) c else e

        val recurrenceEdited = edited.recurrenceRule != base.recurrenceRule ||
            edited.recurrenceExplicitlySet != base.recurrenceExplicitlySet
        val rule = if (recurrenceEdited) edited.recurrenceRule else current.recurrenceRule
        val explicit = if (recurrenceEdited) edited.recurrenceExplicitlySet else current.recurrenceExplicitlySet

        val type = pick(base.type, edited.type, current.type)
        val startDate = pick(base.startDate, edited.startDate, current.startDate)

        val seriesChanged = effectiveRecurrenceMode(type, rule, explicit) !=
            effectiveRecurrenceMode(current.type, current.recurrenceRule, current.recurrenceExplicitlySet) ||
            startDate != current.startDate
        val pairIsValid = current.postponedFromOccurrenceDate != null && current.postponedToDate != null
        val keepPair = pairIsValid && !seriesChanged

        return edited.copy(
            title = pick(base.title, edited.title, current.title),
            description = pick(base.description, edited.description, current.description),
            type = type,
            startDate = startDate,
            startTime = pick(base.startTime, edited.startTime, current.startTime),
            endDate = pick(base.endDate, edited.endDate, current.endDate),
            endTime = pick(base.endTime, edited.endTime, current.endTime),
            isAllDay = pick(base.isAllDay, edited.isAllDay, current.isAllDay),
            status = pick(base.status, edited.status, current.status),
            importance = pick(base.importance, edited.importance, current.importance),
            colorKey = pick(base.colorKey, edited.colorKey, current.colorKey),
            recurrenceRule = rule,
            recurrenceExplicitlySet = explicit,
            lastCompletedOccurrenceDate = current.lastCompletedOccurrenceDate,
            postponedFromOccurrenceDate = if (keepPair) current.postponedFromOccurrenceDate else null,
            postponedToDate = if (keepPair) current.postponedToDate else null,
            links = mergeKeyed(current.links, base.links, edited.links, ::linkKey, linkTargetExists),
            reminders = mergeKeyed(current.reminders, base.reminders, edited.reminders, ::reminderKey),
            createdAt = pick(base.createdAt, edited.createdAt, current.createdAt),
        )
    }

    /** Semantic identity связи: id перегенерируется при каждом сохранении редактора. */
    fun linkKey(l: CalendarItemLink): Any = l.targetType to l.targetId

    /** Semantic identity напоминания. */
    fun reminderKey(r: ReminderRule): Any = listOf(r.reminderType, r.offsetValue, r.offsetUnit, r.exactDateTime)

    /** current − (base − edited) + (edited − base − current): diff применяется к текущему составу, а не подменяет его. */
    private fun <T> mergeKeyed(
        current: List<T>, base: List<T>, edited: List<T>,
        key: (T) -> Any, accept: (T) -> Boolean = { true },
    ): List<T> {
        val baseKeys = base.mapTo(HashSet(), key)
        val editedKeys = edited.mapTo(HashSet(), key)
        val currentKeys = current.mapTo(HashSet(), key)
        val removed = baseKeys - editedKeys
        val kept = current.filter { key(it) !in removed }
        val added = edited.filter { key(it) !in baseKeys && key(it) !in currentKeys && accept(it) }.distinctBy(key)
        return kept + added
    }
}
