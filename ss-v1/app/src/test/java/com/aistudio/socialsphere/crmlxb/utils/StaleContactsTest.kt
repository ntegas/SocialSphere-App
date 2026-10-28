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

    // ── 2026-10-02: NO_SCHEDULE («Поддерживать связь» ВКЛ, без периодичности) —
    // AC5/AC6. Персистентно отличается от NOT_TRACKED (см. ContactEditScreenV22Test),
    // но намеренно НИКОГДА не просрочен — «без расписания» означает отсутствие
    // фиксированного срока в принципе, не «забытый» срок. StaleContactsReceiver
    // (ежедневное уведомление) и HomeScreen (список «нужно связаться») оба читают
    // ИМЕННО эту функцию — null здесь означает «ни уведомления, ни попадания в
    // список» на обоих уровнях без необходимости отдельно тестировать те экраны.

    @Test
    fun noSchedule_rhythmDays_isNull() {
        // AC5: NO_SCHEDULE никогда не производит интервал просрочки.
        assertNull(StaleContacts.rhythmDays(CommunicationRhythm.NO_SCHEDULE))
    }

    @Test
    fun noSchedule_overdueDays_alwaysNull_evenLongAfterLastContact() {
        // AC5/AC6: даже спустя очень долгое время с последнего контакта — не просрочен,
        // а значит StaleContactsReceiver ничего не напомнит, и HomeScreen это не покажет.
        val last = today.minusDays(4000)
        assertNull(
            StaleContacts.overdueDays(CommunicationRhythm.NO_SCHEDULE, last.toString(), today = today)
        )
    }

    @Test
    fun noSchedule_overdueDays_nullEvenWithoutLastContactDate() {
        assertNull(
            StaleContacts.overdueDays(CommunicationRhythm.NO_SCHEDULE, null, today = today)
        )
    }
}
