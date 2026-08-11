package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.model.CommunicationRhythm
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * Просрочка «пора связаться» по ритму общения, включая CUSTOM (v17): интервал
 * берётся из customRhythmDays, а не хардкода. null/≤0 → как NOT_TRACKED (не крашить,
 * не считать просроченным).
 */
class StaleContactsTest {

    private val today = LocalDate.of(2026, 7, 23)

    @Test
    fun standardRhythm_overdueDaysComputed() {
        val last = today.minusDays(40)
        // overdueDays возвращает дни с последнего контакта (не «сверх интервала»);
        // MONTHLY = 30 дней, elapsed = 40 >= 30 → просрочен, значение = 40
        assertEquals(
            40L,
            StaleContacts.overdueDays(CommunicationRhythm.MONTHLY, last.toString(), today = today)
        )
    }

    @Test
    fun custom_usesCustomRhythmDays() {
        val last = today.minusDays(20)
        // Ритм «раз в 15 дней», elapsed = 20 >= 15 → просрочен, значение = 20
        assertEquals(
            20L,
            StaleContacts.overdueDays(
                CommunicationRhythm.CUSTOM, last.toString(), customRhythmDays = 15, today = today
            )
        )
    }

    @Test
    fun custom_notYetDue_returnsNull() {
        val last = today.minusDays(5)
        assertNull(
            StaleContacts.overdueDays(
                CommunicationRhythm.CUSTOM, last.toString(), customRhythmDays = 15, today = today
            )
        )
    }

    @Test
    fun custom_nullCustomRhythmDays_behavesLikeNotTracked() {
        val last = today.minusDays(400)
        assertNull(
            StaleContacts.overdueDays(
                CommunicationRhythm.CUSTOM, last.toString(), customRhythmDays = null, today = today
            )
        )
    }

    @Test
    fun custom_nonPositiveCustomRhythmDays_behavesLikeNotTracked() {
        val last = today.minusDays(400)
        assertNull(
            StaleContacts.overdueDays(
                CommunicationRhythm.CUSTOM, last.toString(), customRhythmDays = 0, today = today
            )
        )
        assertNull(
            StaleContacts.overdueDays(
                CommunicationRhythm.CUSTOM, last.toString(), customRhythmDays = -5, today = today
            )
        )
    }

    @Test
    fun notTracked_alwaysNull() {
        val last = today.minusDays(400)
        assertNull(
            StaleContacts.overdueDays(CommunicationRhythm.NOT_TRACKED, last.toString(), today = today)
        )
    }
}
