package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.CommunicationRhythm
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * «Пора связаться»: вычисляем просрочку по ритму общения и дате последнего
 * контакта. Без новых полей/миграции — всё уже есть в модели контакта.
 */
object StaleContacts {

    /** Интервал ритма в днях. null — ритм не отслеживается (NOT_TRACKED/CUSTOM). */
    fun rhythmDays(r: CommunicationRhythm): Int? = when (r) {
        CommunicationRhythm.WEEKLY         -> 7
        CommunicationRhythm.MONTHLY        -> 30
        CommunicationRhythm.EVERY_3_MONTHS -> 90
        CommunicationRhythm.EVERY_6_MONTHS -> 180
        CommunicationRhythm.YEARLY         -> 365
        else                               -> null
    }

    /**
     * Сколько дней прошло сверх ритма. >=0 → пора связаться; null → не
     * отслеживается, нет даты последнего контакта, или ещё не пора.
     */
    fun overdueDays(
        rhythm: CommunicationRhythm,
        lastContactDate: String?,
        today: LocalDate = LocalDate.now()
    ): Long? {
        val days = rhythmDays(rhythm) ?: return null
        val last = parseFlexibleDate(lastContactDate) ?: return null
        val elapsed = ChronoUnit.DAYS.between(last, today)
        return if (elapsed >= days) elapsed else null
    }
}
