package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.CommunicationRhythm
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * «Пора связаться»: вычисляем просрочку по ритму общения и дате последнего
 * контакта. Без новых полей/миграции — всё уже есть в модели контакта.
 */
object StaleContacts {

    /**
     * Интервал ритма в днях. null — ритм не отслеживается (NOT_TRACKED), ПОДДЕРЖИВАТЬ
     * СВЯЗЬ включено без расписания (NO_SCHEDULE, 2026-10-02 — отличается от
     * NOT_TRACKED в persisted-данных, но намеренно так же не имеет интервала
     * просрочки: «без расписания» значит без фиксированного срока в принципе),
     * либо CUSTOM без заданного customRhythmDays (или ≤0) — ведёт себя как NOT_TRACKED.
     */
    fun rhythmDays(r: CommunicationRhythm, customRhythmDays: Int? = null): Int? = when (r) {
        CommunicationRhythm.WEEKLY         -> 7
        CommunicationRhythm.MONTHLY        -> 30
        CommunicationRhythm.EVERY_3_MONTHS -> 90
        CommunicationRhythm.EVERY_6_MONTHS -> 180
        CommunicationRhythm.YEARLY         -> 365
        CommunicationRhythm.CUSTOM         -> customRhythmDays?.takeIf { it > 0 }
        CommunicationRhythm.NOT_TRACKED, CommunicationRhythm.NO_SCHEDULE -> null
    }

    /**
     * Сколько дней прошло сверх ритма. >=0 → пора связаться; null → не
     * отслеживается, нет даты последнего контакта, или ещё не пора.
     * Для CUSTOM интервал берётся из customRhythmDays (см. rhythmDays).
     */
    fun overdueDays(
        rhythm: CommunicationRhythm,
        lastContactDate: String?,
        customRhythmDays: Int? = null,
        today: LocalDate = LocalDate.now()
    ): Long? {
        val days = rhythmDays(rhythm, customRhythmDays) ?: return null
        val last = parseFlexibleDate(lastContactDate) ?: return null
        val elapsed = ChronoUnit.DAYS.between(last, today)
        return if (elapsed >= days) elapsed else null
    }
}
