package com.aistudio.socialsphere.crmlxb.ui.screens

import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.ImportanceLevel
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.data.local.toEntity
import com.aistudio.socialsphere.crmlxb.utils.RecurrenceCalculator
import com.aistudio.socialsphere.crmlxb.utils.effectiveDate
import com.aistudio.socialsphere.crmlxb.utils.effectiveRecurrenceMode
import com.aistudio.socialsphere.crmlxb.utils.isValidPostponeTarget
import com.aistudio.socialsphere.crmlxb.utils.pendingOccurrence
import com.aistudio.socialsphere.crmlxb.utils.pendingOccurrenceDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Плоский JUnit4 (без Robolectric) — effectiveDate() и calendarEventOccursOn()
 * не трогают Android, только java.time + canonical RecurrenceCalculator.
 *
 * Проверяет ПОДКЛЮЧЕНИЕ RecurrenceCalculator к отображению CalendarItem:
 * CalendarUtils.effectiveDate() (upcoming-проекция) и
 * CalendarScreen.calendarEventOccursOn() (day-cell членство в MonthGridView/
 * WeekStripView). Арифметика самого калькулятора уже исчерпывающе доказана в
 * RecurrenceCalculatorTest — здесь калькулятор местами используется как
 * доверенный oracle (тест M), чтобы проверить именно WIRING этих двух UI-точек,
 * не переизобретая формулы заново.
 */
class CalendarRenderingRecurrenceTest {

    private fun item(
        startDate: LocalDate,
        recurrenceRule: String?,
        status: CalendarItemStatus = CalendarItemStatus.ACTIVE,
        id: String = "cri-1",
        type: CalendarItemType = CalendarItemType.TASK,
        recurrenceExplicitlySet: Boolean = false,
        lastCompletedOccurrenceDate: String? = null,
    ) = CalendarItem(
        id = id, title = "t", type = type,
        startDate = startDate.toString(), isAllDay = true,
        status = status, importance = ImportanceLevel.NORMAL,
        recurrenceRule = recurrenceRule,
        recurrenceExplicitlySet = recurrenceExplicitlySet,
        lastCompletedOccurrenceDate = lastCompletedOccurrenceDate,
        createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
    )

    // ─── A. UPCOMING WEEKLY — OLD BUG PROOF ───
    // startDate две недели назад, WEEKLY, referenceDate сегодня — если анкер
    // на том же дне недели, сегодня САМО occurrence. Старый код (effectiveDate
    // возвращала startDate как есть для не-YEARLY) вернул бы дату 2-недельной
    // давности — этот тест на нём FAILs.

    @Test
    fun a_upcomingWeekly_oldBugProof_referenceIsOnCycle_returnsReference() {
        val today = LocalDate.now()
        val start = today.minusWeeks(2) // тот же день недели, что и today
        val ev = item(start, RecurrenceMode.WEEKLY.toRRule())
        assertEquals("weekly upcoming обязан спроецироваться на сегодня, не остаться в прошлом",
            today.toString(), ev.effectiveDate(today))
    }

    // ─── B. UPCOMING DAILY ───

    @Test
    fun b_upcomingDaily_startInPast_returnsReference() {
        val today = LocalDate.now()
        val start = today.minusDays(5)
        val ev = item(start, RecurrenceMode.DAILY.toRRule())
        assertEquals(today.toString(), ev.effectiveDate(today))
    }

    // ─── C. UPCOMING MONTHLY ───

    @Test
    fun c_upcomingMonthly_jan15_referenceFeb1_returnsFeb15() {
        val ev = item(LocalDate.of(2026, 1, 15), RecurrenceMode.MONTHLY.toRRule())
        assertEquals("2026-02-15", ev.effectiveDate(LocalDate.of(2026, 2, 1)))
    }

    // ─── D. MONTHLY 31 — явное отсутствие drift ───

    @Test
    fun d_upcomingMonthly31_feb_thenMar_noDrift() {
        val ev = item(LocalDate.of(2026, 1, 31), RecurrenceMode.MONTHLY.toRRule())
        assertEquals("2026-02-28", ev.effectiveDate(LocalDate.of(2026, 2, 1)))
        val mar = ev.effectiveDate(LocalDate.of(2026, 3, 1))
        assertEquals("2026-03-31", mar)
        assertFalse("не должно продрейфовать в 28 — anchor фиксирован на 31", mar == "2026-03-28")
    }

    // ─── E. YEARLY normal ───

    @Test
    fun e_upcomingYearlyNormal_correctSemanticsPreserved() {
        val ev = item(LocalDate.of(2020, 9, 16), RecurrenceMode.YEARLY.toRRule())
        assertEquals("2021-09-16", ev.effectiveDate(LocalDate.of(2021, 1, 1)))
    }

    // ─── F. YEARLY FEB29 — устраняет старое расхождение UI vs scheduler ───

    @Test
    fun f_upcomingYearlyFeb29_2025_march1_2028_feb29() {
        val ev = item(LocalDate.of(2024, 2, 29), RecurrenceMode.YEARLY.toRRule())
        assertEquals("2025-03-01", ev.effectiveDate(LocalDate.of(2025, 1, 1)))
        assertEquals("2028-02-29", ev.effectiveDate(LocalDate.of(2028, 1, 1)))
    }

    // ─── G. MONTH GRID WEEKLY: один item виден на каждом корректном дне ───

    @Test
    fun g_monthGridWeekly_visibleOnEveryMatchingDayOfMonth() {
        val anchor = LocalDate.of(2026, 9, 2)
        val ev = item(anchor, RecurrenceMode.WEEKLY.toRRule())
        val expectedDays = setOf(2, 9, 16, 23, 30)
        for (d in 1..30) {
            val day = LocalDate.of(2026, 9, d)
            assertEquals("Sep$d membership", d in expectedDays, calendarEventOccursOn(ev, day))
        }
    }

    // ─── H. MONTH GRID DAILY: каждый день начиная со startDate, не раньше ───

    @Test
    fun h_monthGridDaily_everyDayFromStart_notBefore() {
        val start = LocalDate.of(2026, 9, 10)
        val ev = item(start, RecurrenceMode.DAILY.toRRule())
        for (d in 1..30) {
            val day = LocalDate.of(2026, 9, d)
            assertEquals("Sep$d membership", d >= 10, calendarEventOccursOn(ev, day))
        }
    }

    // ─── I. MONTH GRID MONTHLY: Jan31-серия — Feb только 28/29, Mar только 31 ───

    @Test
    fun i_monthGridMonthly_jan31series_febOnly28_marOnly31() {
        val ev = item(LocalDate.of(2026, 1, 31), RecurrenceMode.MONTHLY.toRRule())
        for (d in 1..28) {
            val day = LocalDate.of(2026, 2, d)
            assertEquals("Feb$d membership", d == 28, calendarEventOccursOn(ev, day))
        }
        for (d in 1..31) {
            val day = LocalDate.of(2026, 3, d)
            assertEquals("Mar$d membership", d == 31, calendarEventOccursOn(ev, day))
        }
    }

    // ─── J. NONE: одноразовое событие видно только на original startDate ───

    @Test
    fun j_none_visibleOnlyOnOriginalStartDate() {
        val start = LocalDate.of(2026, 9, 10)
        val ev = item(start, recurrenceRule = null)
        for (d in 1..30) {
            val day = LocalDate.of(2026, 9, d)
            assertEquals("Sep$d membership", d == 10, calendarEventOccursOn(ev, day))
        }
    }

    // ─── K. START BOUNDARY: recurring item не виден до original startDate ───

    @Test
    fun k_startBoundary_recurringItemNotVisibleBeforeStart() {
        val start = LocalDate.of(2026, 9, 16) // Wednesday
        val ev = item(start, RecurrenceMode.WEEKLY.toRRule())
        // Sep2/Sep9 — тот же день недели, что и start, но ДО него — не должны совпасть.
        assertFalse(calendarEventOccursOn(ev, LocalDate.of(2026, 9, 2)))
        assertFalse(calendarEventOccursOn(ev, LocalDate.of(2026, 9, 9)))
        assertTrue(calendarEventOccursOn(ev, LocalDate.of(2026, 9, 16)))
    }

    // ─── L. STATUS: date-membership функции статус-агностичны — исключение
    // COMPLETED/CANCELLED остаётся ВЫШЕ по стеку (CalendarScreen.visibleEvents,
    // не тронуто в этом batch), а не внутри calendarEventOccursOn/effectiveDate.
    // Этот тест документирует границу ответственности: если бы статус влиял на
    // date-математику здесь, это была бы вторая policy вместо одной canonical. ───

    @Test
    fun l_statusIsNotDateFilteringConcern_handledUpstreamNotHere() {
        val start = LocalDate.now()
        val completed = item(start, RecurrenceMode.WEEKLY.toRRule(), status = CalendarItemStatus.COMPLETED)
        val active = item(start, RecurrenceMode.WEEKLY.toRRule(), status = CalendarItemStatus.ACTIVE)
        // Сами по себе date-функции не знают о статусе — обе ведут себя одинаково;
        // статус-фильтрация (visibleEvents) остаётся отдельным, не тронутым шагом.
        assertEquals(calendarEventOccursOn(active, start), calendarEventOccursOn(completed, start))
    }

    // ─── M. CONSISTENCY: UI-дата совпадает с canonical calculator, без
    // переизобретения формул — представительные режимы DAILY/WEEKLY/MONTHLY/YEARLY ───

    @Test
    fun m_consistency_effectiveDateMatchesCalculatorOracle_forRepresentativeModes() {
        val today = LocalDate.now()
        val cases = listOf(
            LocalDate.of(2026, 3, 1) to RecurrenceMode.DAILY,
            LocalDate.of(2026, 3, 1) to RecurrenceMode.WEEKLY,
            LocalDate.of(2026, 1, 31) to RecurrenceMode.MONTHLY,
            LocalDate.of(2000, 2, 29) to RecurrenceMode.YEARLY,
        )
        cases.forEach { (anchor, mode) ->
            val ev = item(anchor, mode.toRRule())
            val oracle = RecurrenceCalculator.nextOccurrenceOnOrAfter(anchor, mode, today)
            assertEquals("$mode: UI effectiveDate обязана совпасть с canonical calculator",
                oracle?.toString(), ev.effectiveDate(today))

            // day-cell membership тоже сверяем напрямую с occursOn — тот же anchor.
            assertEquals("$mode: calendarEventOccursOn обязана совпасть с canonical occursOn",
                RecurrenceCalculator.occursOn(anchor, mode, today), calendarEventOccursOn(ev, today))
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  BACKWARD COMPATIBILITY — legacy annual-date CalendarItem
    //  (BIRTHDAY/ANNIVERSARY/NAMEDAY с recurrenceRule == null)
    //
    //  INVARIANT: после перехода на RecurrenceMode.fromRule() такие записи не
    //  должны трактоваться как NONE и переставать ежегодно проецироваться.
    //  effectiveRecurrenceMode(type, recurrenceRule) — единая policy: явный
    //  rule приоритетнее; отсутствующий rule + исторически annual type -> YEARLY;
    //  IMPORTANT_DATE НЕ входит (ни один production creation path не назначает
    //  ему YEARLY автоматически); неизвестный НЕПУСТОЙ rule не подменяется на
    //  YEARLY этой policy.
    // ═════════════════════════════════════════════════════════════

    // ─── Таблица политики: N. effectiveRecurrenceMode() напрямую ───

    @Test
    fun n_policy_legacyAnnualTypesWithoutRule_resolveToYearly() {
        listOf(CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY, CalendarItemType.NAMEDAY).forEach { type ->
            assertEquals("$type + null recurrenceRule + legacy(false) -> YEARLY (legacy compatibility)",
                RecurrenceMode.YEARLY, effectiveRecurrenceMode(type, null, recurrenceExplicitlySet = false))
            assertEquals("$type + пустая строка recurrenceRule + legacy(false) -> тоже YEARLY (blank == absent)",
                RecurrenceMode.YEARLY, effectiveRecurrenceMode(type, "", recurrenceExplicitlySet = false))
        }
    }

    @Test
    fun n_policy_importantDate_withoutRule_staysNone_notAutoPromotedToYearly() {
        // Explicit по заданию: IMPORTANT_DATE не считать annual без подтверждения
        // из production creation paths — ни один из них не назначает ему YEARLY.
        assertEquals(RecurrenceMode.NONE, effectiveRecurrenceMode(CalendarItemType.IMPORTANT_DATE, null, recurrenceExplicitlySet = false))
    }

    @Test
    fun n_policy_nonAnnualTypes_withoutRule_stayNone() {
        listOf(CalendarItemType.MEETING, CalendarItemType.CALL, CalendarItemType.TASK, CalendarItemType.MESSAGE).forEach { type ->
            assertEquals("$type + null recurrenceRule -> NONE (не annual-тип)",
                RecurrenceMode.NONE, effectiveRecurrenceMode(type, null, recurrenceExplicitlySet = false))
        }
    }

    // ПРИМЕЧАНИЕ: "явный NONE" здесь НЕ тестируется как отдельный случай —
    // RecurrenceMode.NONE.toRRule() == null, то есть "пользователь осознанно
    // выбрал NONE" и "recurrenceRule никогда не задавался" неразличимы на
    // уровне сырого значения (оба — null). Это существующая особенность модели
    // данных (не введена этим фиксом, не в скоупе — раздел "Room schema" не
    // трогаем), см. new finding в отчёте. Проверяем только то, что реально
    // различимо: известный НЕПУСТОЙ rule (WEEKLY/MONTHLY/...) побеждает
    // type-based fallback.
    @Test
    fun n_policy_explicitKnownRule_alwaysWinsOverTypeDefault() {
        assertEquals("BIRTHDAY с явным WEEKLY не переопределяется в YEARLY",
            RecurrenceMode.WEEKLY, effectiveRecurrenceMode(CalendarItemType.BIRTHDAY, RecurrenceMode.WEEKLY.toRRule(), recurrenceExplicitlySet = false))
        assertEquals("ANNIVERSARY с явным MONTHLY не переопределяется в YEARLY",
            RecurrenceMode.MONTHLY, effectiveRecurrenceMode(CalendarItemType.ANNIVERSARY, RecurrenceMode.MONTHLY.toRRule(), recurrenceExplicitlySet = false))
    }

    @Test
    fun n_policy_unknownNonEmptyRule_notAutoPromotedToYearly_evenForAnnualType() {
        // Неизвестный/будущий RRULE у annual-типа — НЕ силой в YEARLY этой policy;
        // уходит в RecurrenceMode.fromRule() как есть (её собственный NONE-fallback).
        val unknown = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"
        assertEquals(RecurrenceMode.NONE, effectiveRecurrenceMode(CalendarItemType.BIRTHDAY, unknown, recurrenceExplicitlySet = false))
    }

    // ─── O. Downstream: effectiveDate() проецирует legacy annual-типы вперёд ───
    // Тот самый сценарий из ПРОБЛЕМЫ: BIRTHDAY с recurrenceRule=null и давно
    // прошедшим startDate ("1990-06-15") должен вести себя как YEARLY, не NONE.

    @Test
    fun o_effectiveDate_legacyBirthdayWithoutRule_stillProjectsToNearestAnniversary() {
        val ev = item(LocalDate.of(1990, 6, 15), recurrenceRule = null, type = CalendarItemType.BIRTHDAY)
        assertEquals("legacy ДР без recurrenceRule обязан проецироваться на ближайшее наступление",
            "2026-06-15", ev.effectiveDate(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun o_effectiveDate_legacyAnniversaryAndNameday_alsoProjectForward() {
        listOf(CalendarItemType.ANNIVERSARY, CalendarItemType.NAMEDAY).forEach { type ->
            val ev = item(LocalDate.of(2018, 3, 10), recurrenceRule = null, type = type)
            assertEquals("$type без recurrenceRule обязан проецироваться на ближайшее наступление",
                "2026-03-10", ev.effectiveDate(LocalDate.of(2026, 1, 1)))
        }
    }

    @Test
    fun o_effectiveDate_legacyImportantDateWithoutRule_staysOneShot_notProjected() {
        val ev = item(LocalDate.of(2018, 3, 10), recurrenceRule = null, type = CalendarItemType.IMPORTANT_DATE)
        assertEquals("IMPORTANT_DATE без recurrenceRule остаётся one-shot — не annual по product semantics",
            "2018-03-10", ev.effectiveDate(LocalDate.of(2026, 1, 1)))
    }

    // ─── P. Downstream: calendarEventOccursOn() — legacy ДР видна в месячной сетке ───

    @Test
    fun p_calendarEventOccursOn_legacyBirthdayWithoutRule_visibleInMonthGrid() {
        val ev = item(LocalDate.of(1990, 6, 15), recurrenceRule = null, type = CalendarItemType.BIRTHDAY)
        assertTrue("legacy ДР обязана быть видна в сетке в годовщину",
            calendarEventOccursOn(ev, LocalDate.of(2026, 6, 15)))
        assertFalse("и НЕ видна в произвольный другой день",
            calendarEventOccursOn(ev, LocalDate.of(2026, 6, 16)))
    }

    // ═════════════════════════════════════════════════════════════
    //  SCHEMA-FOUNDATION: explicit NONE (recurrenceExplicitlySet == true)
    //  и lastCompletedOccurrenceDate — persistence integrity только, БЕЗ
    //  completion advancement (не реализуется в этом batch).
    // ═════════════════════════════════════════════════════════════

    // ─── R. explicit NONE: recurrenceRule==null && explicitlySet==true -> NONE,
    // даже для исторически annual типа (побеждает legacy YEARLY-fallback) ───

    @Test
    fun r_policy_explicitNone_winsOverLegacyAnnualFallback() {
        listOf(CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY, CalendarItemType.NAMEDAY).forEach { type ->
            assertEquals("$type + null rule + explicit=true -> NONE, не YEARLY-fallback",
                RecurrenceMode.NONE, effectiveRecurrenceMode(type, null, recurrenceExplicitlySet = true))
        }
    }

    @Test
    fun r_policy_explicitFalse_stillGetsLegacyAnnualFallback() {
        // Контрольная пара к предыдущему тесту: explicit=false — тот же старый
        // fallback, никакой регрессии от добавления explicit=true ветки.
        assertEquals(RecurrenceMode.YEARLY,
            effectiveRecurrenceMode(CalendarItemType.BIRTHDAY, null, recurrenceExplicitlySet = false))
    }

    @Test
    fun r_effectiveDate_explicitNoneBirthday_staysOneShot_notProjected() {
        val ev = item(LocalDate.of(1990, 6, 15), recurrenceRule = null, type = CalendarItemType.BIRTHDAY,
            recurrenceExplicitlySet = true)
        assertEquals("explicit-NONE ДР не проецируется на ближайшую годовщину — настоящий NONE",
            "1990-06-15", ev.effectiveDate(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun r_calendarEventOccursOn_explicitNoneBirthday_notVisibleOnAnniversary() {
        val ev = item(LocalDate.of(1990, 6, 15), recurrenceRule = null, type = CalendarItemType.BIRTHDAY,
            recurrenceExplicitlySet = true)
        assertFalse("explicit-NONE ДР не должна виртуально появляться на годовщине в сетке",
            calendarEventOccursOn(ev, LocalDate.of(2026, 6, 15)))
        assertTrue("но видна на своём original startDate (NONE = one-shot)",
            calendarEventOccursOn(ev, LocalDate.of(1990, 6, 15)))
    }

    // ─── S. lastCompletedOccurrenceDate: чистый persistence round-trip
    // (Entity <-> Domain), completion advancement НЕ реализуется ───

    @Test
    fun s_lastCompletedOccurrenceDate_entityDomainRoundTrip_exactValue() {
        val ev = item(LocalDate.of(2026, 1, 1), recurrenceRule = RecurrenceMode.WEEKLY.toRRule(),
            lastCompletedOccurrenceDate = "2026-03-10")
        val roundTripped = ev.toEntity().toDomain()
        assertEquals("2026-03-10", roundTripped.lastCompletedOccurrenceDate)
        assertEquals(ev, roundTripped)
    }

    @Test
    fun s_lastCompletedOccurrenceDate_absent_roundTripsAsNull() {
        val ev = item(LocalDate.of(2026, 1, 1), recurrenceRule = null)
        assertNull(ev.lastCompletedOccurrenceDate)
        assertNull(ev.toEntity().toDomain().lastCompletedOccurrenceDate)
    }

    @Test
    fun s_recurrenceExplicitlySet_entityDomainRoundTrip_bothValues() {
        val explicitItem = item(LocalDate.of(2026, 1, 1), recurrenceRule = null, recurrenceExplicitlySet = true)
        assertTrue(explicitItem.toEntity().toDomain().recurrenceExplicitlySet)

        val legacyItem = item(LocalDate.of(2026, 1, 1), recurrenceRule = null, recurrenceExplicitlySet = false)
        assertFalse(legacyItem.toEntity().toDomain().recurrenceExplicitlySet)
    }

    // ─── T. Новые поля по умолчанию — CalendarItem, сконструированный без их
    // явного указания (как делали ВСЕ create-пути ДО этого batch'а), получает
    // безопасные legacy-значения, а не случайные/ломающие ───

    @Test
    fun t_newCalendarItem_constructedWithoutNewFields_defaultsToLegacySafeValues() {
        val ev = CalendarItem(
            id = "no-new-fields", title = "t", type = CalendarItemType.CALL,
            startDate = "2026-01-01", isAllDay = true,
            status = CalendarItemStatus.ACTIVE, importance = ImportanceLevel.NORMAL,
            createdAt = "2025-01-01T00:00", updatedAt = "2025-01-01T00:00"
        )
        assertNull(ev.lastCompletedOccurrenceDate)
        assertFalse(ev.recurrenceExplicitlySet)
    }

    // ═════════════════════════════════════════════════════════════
    //  POSTPONE RUNTIME SEMANTICS (2026-09-17) — pure/deterministic tests:
    //  isValidPostponeTarget()/pendingOccurrence() с явным referenceDate,
    //  дат-математика MONTHLY-31/YEARLY-Feb29 требует ФИКСИРОВАННЫХ
    //  календарных дат (не «today»-относительных — anti-drift сценарии по
    //  своей природе привязаны к конкретным месяцам/годам), поэтому эти
    //  тесты идут здесь, не через CalendarPostpone (который внутри всегда
    //  использует реальный LocalDate.now() для pendingOccurrence()).
    // ═════════════════════════════════════════════════════════════

    // ─── D. CALENDAR RENDER: postponed occurrence — seriesDate day-cell
    // больше не pending, effectiveDate day-cell — pending; остальные
    // occurrence серии (в т.ч. следующий regular) не затронуты ───

    @Test
    fun postponeD_calendarRender_seriesDateHidden_effectiveDateShown_othersUnaffected() {
        val anchor = LocalDate.of(2026, 9, 2) // Wednesday
        val ev = item(anchor, RecurrenceMode.WEEKLY.toRRule()).copy(
            postponedFromOccurrenceDate = "2026-09-16",
            postponedToDate = "2026-09-18",
        )
        // series: Sep2, Sep9, Sep16, Sep23, Sep30 — postpone Sep16 -> Sep18.
        val expectedVisible = setOf(2, 9, 18, 23, 30)
        for (d in 1..30) {
            val day = LocalDate.of(2026, 9, d)
            assertEquals("Sep$d membership (postponed Sep16->Sep18)",
                d in expectedVisible, calendarEventOccursOn(ev, day))
        }
        assertFalse("исходный день occurrence (Sep16) больше не pending", calendarEventOccursOn(ev, LocalDate.of(2026, 9, 16)))
        assertTrue("день переноса (Sep18) — pending", calendarEventOccursOn(ev, LocalDate.of(2026, 9, 18)))
    }

    // ─── MONTHLY 31 anti-drift: postpone Feb28, valid up to (excl.) Mar31,
    // completion не дрейфует anchor — следующий canonical Mar31, не Mar28 ───

    @Test
    fun postponeMonthly31_feb28_validUntilMar31_completionAntiDrift_nextIsMar31() {
        val anchor = LocalDate.of(2026, 1, 31)
        val feb28 = LocalDate.of(2026, 2, 28)
        val mar31 = LocalDate.of(2026, 3, 31)

        assertTrue("Mar2 строго до next regular (Mar31) — валиден",
            isValidPostponeTarget(anchor, RecurrenceMode.MONTHLY, null, feb28, LocalDate.of(2026, 3, 2)))
        assertFalse("Mar31 сам — next regular occurrence, crossing запрещён",
            isValidPostponeTarget(anchor, RecurrenceMode.MONTHLY, null, feb28, mar31))
        assertFalse("после Mar31 — тем более запрещён",
            isValidPostponeTarget(anchor, RecurrenceMode.MONTHLY, null, feb28, mar31.plusDays(1)))

        val postponed = item(anchor, RecurrenceMode.MONTHLY.toRRule()).copy(
            postponedFromOccurrenceDate = feb28.toString(), postponedToDate = "2026-03-02"
        )
        val pending = postponed.pendingOccurrence(LocalDate.of(2026, 2, 15))
        assertEquals("seriesDate — identity occurrence, не дата переноса", feb28, pending?.seriesDate)
        assertEquals(LocalDate.of(2026, 3, 2), pending?.effectiveDate)

        // После завершения перенесённого occurrence (lastCompleted=Feb28) —
        // следующее canonical occurrence Mar31, НЕ Mar28 (anchor всегда Jan31,
        // никогда не lastCompleted/postponedFrom — anti-drift инвариант).
        val afterDone = item(anchor, RecurrenceMode.MONTHLY.toRRule(), lastCompletedOccurrenceDate = feb28.toString())
        assertEquals(mar31, afterDone.pendingOccurrenceDate(LocalDate.of(2026, 3, 1)))
    }

    // ─── YEARLY Feb29 anti-drift: postpone Mar1 (non-leap effective occurrence
    // Feb29-anchor), completion не дрейфует anchor — в leap year снова Feb29 ───

    @Test
    fun postponeYearlyFeb29_mar1NonLeapYear_seriesIdentityStaysMar1_originalAnchorPreservedAfterDone() {
        val anchor = LocalDate.of(2024, 2, 29) // leap-year anchor
        val mar1_2025 = LocalDate.of(2025, 3, 1) // non-leap-year effective occurrence
        val mar1_2026 = LocalDate.of(2026, 3, 1) // следующий regular (2026 тоже не leap)

        assertTrue("до next regular occurrence — валиден",
            isValidPostponeTarget(anchor, RecurrenceMode.YEARLY, null, mar1_2025, LocalDate.of(2025, 6, 1)))
        assertFalse("сам next regular occurrence — crossing запрещён",
            isValidPostponeTarget(anchor, RecurrenceMode.YEARLY, null, mar1_2025, mar1_2026))

        val postponed = item(anchor, RecurrenceMode.YEARLY.toRRule()).copy(
            postponedFromOccurrenceDate = mar1_2025.toString(), postponedToDate = "2025-03-20"
        )
        val pending = postponed.pendingOccurrence(LocalDate.of(2025, 2, 1))
        assertEquals("series identity — canonical occurrence этого года (Mar1), не Feb29 (не существует в 2025)",
            mar1_2025, pending?.seriesDate)

        // После завершения — следующая recurrence считается по ОРИГИНАЛЬНОМУ
        // anchor (Feb29): в leap year (2028) снова возвращается Feb29, а не
        // «застревает» на Mar1 навсегда.
        val afterDone = item(anchor, RecurrenceMode.YEARLY.toRRule(), lastCompletedOccurrenceDate = mar1_2025.toString())
        assertEquals(LocalDate.of(2028, 2, 29), afterDone.pendingOccurrenceDate(LocalDate.of(2028, 1, 1)))
    }
}
