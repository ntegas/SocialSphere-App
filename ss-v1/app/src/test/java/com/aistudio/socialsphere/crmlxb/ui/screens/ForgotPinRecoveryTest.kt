package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-002 (release-hardening) — TEST GROUP 3 (FORGOT PIN).
 *
 * Один @Test-метод намеренно — AppSettings является object-синглтоном на весь
 * JVM-процесс с `by lazy`-полями (та же причина, что в AppSettingsSubscriptionTest.kt
 * и в комментарии AppStateStoreRestoreTest.kt) — гонка между тестовыми методами
 * исключена по построению, независимо от порядка запуска.
 *
 * Batch 1B (устранение test theater) — раньше здесь была simulateForgotPinRecovery(),
 * которая буквально КОПИРОВАЛА ветвление onConfirm в AppLockComponents.kt. Это
 * означало, что регрессия В САМОМ onConfirm (например, кто-то случайно уберёт
 * BiometricGate.authenticate() и снова вызовет сброс напрямую) не обязательно
 * сломала бы этот тест — тест и продакшен были двумя параллельными реализациями
 * одной и той же логики.
 *
 * Проверка результата аутентификации ("любой результат кроме SUCCESS не
 * вызывает колбэк вообще") сюда НЕ дублируется — она уже проверена в
 * BiometricGateAuthResultTest на dispatchAuthResult(), той же самой функции,
 * которую вызывает КАЖДАЯ ветка BiometricGate.authenticate(), включая
 * forgot-pin (AppLockComponents.kt.showForgotPinConfirm.onConfirm). Этот тест
 * проверяет единственное, что специфично для forgot-pin: что именно происходит,
 * когда колбэк всё-таки вызван — реальную performForgotPinRecovery(), ту же
 * функцию верхнего уровня, которую вызывает onSuccess в проде, а не её копию.
 */
@RunWith(RobolectricTestRunner::class)
class ForgotPinRecoveryTest {

    @Test
    fun performForgotPinRecovery_actuallyClearsPinAndAppLock() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.init(ctx)

        AppSettings.setPin("1234")
        AppSettings.appLockEnabled.value = true
        assertTrue("precondition: PIN must be set before testing recovery", AppSettings.hasPinSet())
        assertTrue("precondition: App Lock must be enabled before testing recovery", AppSettings.appLockEnabled.value)

        // Реальная production-функция (AppLockComponents.kt) — та же, что
        // вызывается из onSuccess-колбэка BiometricGate.authenticate() в
        // showForgotPinConfirm.onConfirm, не её копия/мираж.
        performForgotPinRecovery()

        assertFalse("recovery must actually clear the PIN", AppSettings.hasPinSet())
        assertFalse("recovery must actually disable App Lock", AppSettings.appLockEnabled.value)
    }
}
