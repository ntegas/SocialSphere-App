package com.aistudio.socialsphere.crmlxb.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * SEC-001/SEC-002 (release-hardening) — TEST GROUP 2 (AUTH DECISION).
 *
 * BiometricAuthResult.grantsAccess() — чистая функция «пускать/не пускать» по
 * состоянию, отделённая от Android/BiometricPrompt, чтобы её можно было
 * проверить без реального устройства/Activity. dispatchAuthResult() (Batch 1B)
 * — реальная production-функция, которая её использует и которую вызывает
 * BiometricGate.authenticate() из каждой своей ветки — тот же код здесь и там.
 *
 * Главный инвариант этого задания (INVARIANT S1/S2): result != SUCCESS →
 * protected data остаются заблокированы. Проверяется явно для каждого из 5
 * состояний, а не только "happy path", и на реальной production-функции
 * (dispatchAuthResult), не только на изолированном enum-контракте.
 */
class BiometricGateAuthResultTest {

    @Test
    fun onlySuccess_grantsAccess() {
        assertTrue(BiometricAuthResult.SUCCESS.grantsAccess())

        assertFalse(BiometricAuthResult.FAILED.grantsAccess())
        assertFalse(BiometricAuthResult.ERROR.grantsAccess())
        assertFalse(BiometricAuthResult.CANCELLED.grantsAccess())
        assertFalse(BiometricAuthResult.UNAVAILABLE.grantsAccess())
    }

    @Test
    fun everyNonSuccessState_deniesAccess() {
        // Явный негативный обход — падает при старом поведении, если бы кто-то
        // снова добавил fail-open (например ветку "UNAVAILABLE -> true").
        val deniedStates = BiometricAuthResult.values().filter { it != BiometricAuthResult.SUCCESS }
        assertEquals(4, deniedStates.size)
        deniedStates.forEach { state ->
            assertFalse("$state must not grant access", state.grantsAccess())
        }
    }

    /**
     * Batch 1B — устранение test theater. Раньше эти два теста проверяли ТОЛЬКО
     * grantsAccess() в изоляции: BiometricGate.authenticate() реально вызывал
     * onSuccess()/не вызывал его напрямую в каждой ветке, никогда не обращаясь
     * к grantsAccess(). Регрессия вида "onSuccess() снова в ветке UNAVAILABLE"
     * могла бы вернуться в продакшен, а этот тест остался бы зелёным.
     *
     * dispatchAuthResult() теперь — ЕДИНСТВЕННОЕ место, где BiometricAuthResult
     * превращается в реальный вызов onSuccess, и вызывается оно И отсюда, И из
     * каждой ветки BiometricGate.authenticate() (SUCCESS/ERROR/CANCELLED/FAILED/
     * UNAVAILABLE) — тот же код, не копия. Эти тесты проверяют именно его.
     */
    @Test
    fun dispatchAuthResult_success_invokesCallback() {
        var invoked = false
        dispatchAuthResult(BiometricAuthResult.SUCCESS) { invoked = true }
        assertTrue("SUCCESS must invoke the callback", invoked)
    }

    @Test
    fun dispatchAuthResult_everyNonSuccessState_neverInvokesCallback() {
        BiometricAuthResult.values().filter { it != BiometricAuthResult.SUCCESS }.forEach { state ->
            var invoked = false
            dispatchAuthResult(state) { invoked = true }
            assertFalse("$state must never invoke the callback", invoked)
        }
    }
}
