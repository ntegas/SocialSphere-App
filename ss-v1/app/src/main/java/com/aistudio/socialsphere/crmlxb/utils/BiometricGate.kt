package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Разблокировка «Защищено» биометрией ИЛИ кодом устройства (PIN/паттерн) —
 * системный BiometricPrompt сам предлагает доступный способ.
 * Свои коды не храним: вся проверка на стороне Android.
 */
object BiometricGate {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Есть ли на устройстве настроенная биометрия или код блокировки. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Показывает системный диалог; onSuccess — только при подтверждении.
     * Если биометрия недоступна (не настроена/сломана) — сразу onSuccess,
     * чтобы владелец не оказался заперт от собственных данных.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
    ) {
        if (!isAvailable(activity)) { onSuccess(); return }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                // Ошибка/отмена — просто не разблокируем, без сообщений:
                // системный диалог уже показал причину.
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
