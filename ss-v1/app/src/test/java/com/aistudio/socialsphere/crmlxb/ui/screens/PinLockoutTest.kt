package com.aistudio.socialsphere.crmlxb.ui.screens

import org.junit.Assert.*
import org.junit.Test

/**
 * SEC-003 (release-hardening) — TEST GROUP 1 (LOCKOUT TIME).
 *
 * AppSettings.computePinLockCheck()/pinLockoutDurationMs() — чистые функции,
 * вынесенные из pinLockRemainingMs() специально, чтобы reboot-safety можно было
 * проверить без SharedPreferences/PersistedMutableState/Android (см. комментарий
 * над computePinLockCheck в SettingsState.kt).
 *
 * Ничего не мокается: функции принимают elapsedRealtime-подобные Long напрямую,
 * как их видел бы pinLockRemainingMs() в реальном приложении.
 */
class PinLockoutTest {

    // ── TEST 1: после превышения числа попыток — locked == true ──
    @Test
    fun escalation_locksAfterAttemptThreshold() {
        // Первые 4 попытки — без блокировки (pinLockoutDurationMs — эскалация,
        // не сам факт блокировки, но именно она решает, ставить ли deadline).
        assertEquals(0L, AppSettings.pinLockoutDurationMs(1))
        assertEquals(0L, AppSettings.pinLockoutDurationMs(4))
        // 5-я попытка — уже блокировка (первая ступень эскалации).
        assertTrue(AppSettings.pinLockoutDurationMs(5) > 0L)
    }

    // ── TEST 2: до deadline — locked == true ──
    @Test
    fun beforeDeadline_stillLocked() {
        val check = AppSettings.computePinLockCheck(
            lockedUntilElapsed = 100_000L,
            lastKnownElapsed = 90_000L,
            nowElapsed = 95_000L, // до deadline, реального ребута не было
            failCount = 5 // без ребута failCount не используется — берём произвольный "заблокированный" уровень
        )
        assertTrue("locked == true expected before deadline", check.remainingMs > 0L)
        assertEquals(100_000L, check.newLockedUntil) // deadline не тронут
    }

    // ── TEST 3: после deadline — locked == false ──
    @Test
    fun afterDeadline_unlocked() {
        val check = AppSettings.computePinLockCheck(
            lockedUntilElapsed = 100_000L,
            lastKnownElapsed = 95_000L,
            nowElapsed = 100_001L, // время реально прошло, без ребута
            failCount = 5
        )
        assertEquals(0L, check.remainingMs)
    }

    // ── TEST 4: process recreation с persistent deadline не снимает lockout ──
    // computePinLockCheck() — чистая функция без собственного состояния между
    // вызовами: единственный источник истины — переданные значения (как если
    // бы их только что прочитали из SharedPreferences после смерти процесса).
    // Повторный "холодный" вызов с теми же персистентными числами обязан дать
    // тот же результат — иначе персист не имел бы смысла.
    @Test
    fun processRecreation_withPersistentDeadline_lockoutSurvives() {
        val persistedLockedUntil = 250_000L
        val persistedLastElapsed = 200_000L
        // "Новый процесс" читает те же самые персистентные числа и вызывает
        // функцию заново — как будто объекта из предыдущего вызова никогда не было.
        val afterRecreation = AppSettings.computePinLockCheck(
            lockedUntilElapsed = persistedLockedUntil,
            lastKnownElapsed = persistedLastElapsed,
            nowElapsed = 210_000L, // реального ребута не было, uptime продолжается
            failCount = 5
        )
        assertTrue("process restart must not lift an active lockout", afterRecreation.remainingMs > 0L)
    }

    // ── TEST 5: persistent representation остаётся корректной после reboot ──
    // Точные числа из описания бага SEC-003: до ребута uptime=400_000_000,
    // deadline=400_300_000 (5 минут блокировки, failCount в 5-минутной ступени
    // эскалации — 9). После ребута uptime сброшен на 20_000.
    //
    // BATCH 1B — ИЗМЕНЁННЫЙ acceptance criterion (был неверным в Batch 1):
    // раньше тест ожидал remainingMs == 0 после ребута — это устраняло
    // многочасовую блокировку (без фикса: 400_300_000 - 20_000 = 400_280_000мс,
    // ~111 часов), НО заодно означало, что активную блокировку можно снять
    // простой перезагрузкой устройства — обход brute-force защиты. Правильно:
    // remainingMs > 0 (блокировка НЕ снята) И remainingMs <= потолок эскалации
    // для этого failCount (не часы/дни).
    @Test
    fun rebootScenario_reArmsBoundedLockout_notZeroNotHoursOrDays() {
        val failCount = 9 // 5-минутная ступень эскалации (pinLockoutDurationMs)
        val beforeReboot = AppSettings.computePinLockCheck(
            lockedUntilElapsed = 400_300_000L,
            lastKnownElapsed = 399_000_000L,
            nowElapsed = 400_000_000L,
            failCount = failCount
        )
        // До ребута — честно ещё заблокировано (300_000мс = 5 минут).
        assertEquals(300_000L, beforeReboot.remainingMs)

        val afterReboot = AppSettings.computePinLockCheck(
            lockedUntilElapsed = beforeReboot.newLockedUntil,
            lastKnownElapsed = beforeReboot.newLastElapsed, // 400_000_000 — старая сессия
            nowElapsed = 20_000L, // новая сессия после ребута — намного меньше
            failCount = failCount // persistent — ребут его не трогает
        )
        val maxDurationForFailCount = AppSettings.pinLockoutDurationMs(failCount)
        assertTrue("reboot must NOT silently lift an active lockout (bypass)", afterReboot.remainingMs > 0L)
        assertTrue("reboot-derived lockout must not exceed the configured escalation ceiling",
            afterReboot.remainingMs <= maxDurationForFailCount)
        // Без фикса Batch 1: 400_300_000 - 20_000 = 400_280_000мс (~111 часов).
        assertTrue("must not balloon into hours/days like the pre-fix bug", afterReboot.remainingMs < 3_600_000L)
        // Новый дедлайн отсчитан от НОВОГО boot-baseline (20_000), не от старого.
        assertEquals(20_000L + maxDurationForFailCount, afterReboot.newLockedUntil)
    }

    // ── TEST 6: без активного lockout (failCount ниже первой ступени) reboot
    // не должен ВЫДУМЫВАТЬ блокировку, которой не было. ──
    @Test
    fun rebootScenario_noPriorLockout_staysUnlocked() {
        val check = AppSettings.computePinLockCheck(
            lockedUntilElapsed = 0L,
            lastKnownElapsed = 400_000_000L,
            nowElapsed = 20_000L, // ребут задетектирован
            failCount = 2 // ниже первой ступени эскалации (< 5) — блокировки не было
        )
        assertEquals("no lockout should be invented where none was active", 0L, check.remainingMs)
        assertEquals(0L, check.newLockedUntil)
    }

    // ── TEST 7 (PIN-002, Batch 1C): lockout уже ИСТЁК до ребута — ребут не
    // должен изобретать новый. failCount остаётся высоким (не сброшен, т.к.
    // сброс требует УСПЕШНОГО verifyPin(), а не просто истечения времени), но
    // lockedUntilElapsed уже МЕНЬШЕ lastKnownElapsed — то есть на момент
    // последнего известного (дореbутного) состояния блокировка уже закончилась.
    // Раньше (после фикса Batch 1B) один высокий failCount ошибочно re-arm-ил
    // новый 5-минутный lockout на пустом месте при любой перезагрузке телефона,
    // даже никак не связанной с подбором PIN. ──
    @Test
    fun rebootScenario_expiredLockoutBeforeReboot_doesNotReArm() {
        val check = AppSettings.computePinLockCheck(
            lockedUntilElapsed = 100_000L,
            lastKnownElapsed = 100_001L, // lockout уже истёк ДО этого чтения
            nowElapsed = 20_000L, // ребут задетектирован
            failCount = 9 // высокий, но НЕ признак активного lockout сам по себе
        )
        assertEquals("expired lockout must not be reborn by a reboot", 0L, check.remainingMs)
        assertEquals(0L, check.newLockedUntil)
    }

    // ── Простой process restart (без ребута) НЕ должен путаться с ребутом ──
    @Test
    fun noReboot_monotonicElapsed_doesNotFalsePositive() {
        val check = AppSettings.computePinLockCheck(
            lockedUntilElapsed = 50_000L,
            lastKnownElapsed = 40_000L,
            nowElapsed = 45_000L, // больше lastKnownElapsed — ребута не было
            failCount = 5
        )
        assertTrue("must not treat normal time progression as a reboot", check.remainingMs > 0L)
        assertEquals(45_000L, check.newLastElapsed)
    }
}
