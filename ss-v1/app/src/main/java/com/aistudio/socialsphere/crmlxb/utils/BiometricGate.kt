package com.aistudio.socialsphere.crmlxb.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Разматывает обёрнутый Context до Activity. НУЖНО, потому что `LocalContext.current`
 * внутри `LocalizedApp` (ui/screens/SettingsState.kt) — это `createConfigurationContext(...)`,
 * НЕ сама Activity, а отдельный Context-объект (не подкласс Activity/FragmentActivity).
 * ФИКС критичного бага (2026-07-05): `LocalContext.current as? FragmentActivity` в
 * ContactDetailScreen.kt всегда возвращал null из-за этого — requestReveal() уходил
 * в else-ветку и открывал «защищённые» заметки БЕЗ единого запроса аутентификации.
 */
tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is Activity -> null // обычная Activity, но не FragmentActivity — BiometricPrompt не встанет
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Разблокировка «Защищено» биометрией ИЛИ кодом устройства (PIN/паттерн) —
 * системный BiometricPrompt сам предлагает доступный способ.
 * Свои коды не храним: вся проверка на стороне Android.
 */
object BiometricGate {

    private const val BIOMETRIC = BiometricManager.Authenticators.BIOMETRIC_WEAK
    private const val DEVICE_CREDENTIAL = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private fun canAuth(context: Context, authenticators: Int): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * ФИКС (2026-07-08, владелец: «биометрия не включается», подтверждено на
     * эмуляторе — с реально настроенным кодом устройства `canAuthenticate`
     * комбинированным флагом `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` вернул
     * не-SUCCESS сразу для ОБЕИХ модальностей по отдельности И для комбинации,
     * подтверждено логами BiometricService: Status 7 везде, хотя код блокировки
     * был точно активен). Раньше был ОДИН вызов с объединённым флагом — если
     * конкретная комбинация не поддерживается на этой прошивке/API, весь
     * переключатель считался недоступным целиком, хотя способ РЕАЛЬНО работал.
     * Теперь проверяем каждый способ отдельно.
     *
     * ФИКС (2026-07-11, живой тест владельца на реальном устройстве: «включаю —
     * запрашивает только PIN, биометрия не появляется», не воспроизводилось на
     * эмуляторе). Причина — предыдущий фикс всё ещё СКЛЕИВАЛ флаги в один вызов
     * `canAuthenticate(BIOMETRIC or DEVICE_CREDENTIAL)`, когда доступны оба
     * способа. На части реальных прошивок (особенно с biometric class *Weak*)
     * системный BiometricPrompt при таком комбинированном флаге сразу уводит
     * на экран кода устройства, минуя сенсор — задокументированное поведение
     * AndroidX BiometricPrompt на некоторых OEM-сборках. Теперь при доступной
     * биометрии передаём ТОЛЬКО BIOMETRIC — код устройства всё равно остаётся
     * доступен как отдельный путь через `isAvailable`/DEVICE_CREDENTIAL-ветку.
     */
    private fun resolveAuthenticators(context: Context): Int? {
        val bio = canAuth(context, BIOMETRIC)
        val cred = canAuth(context, DEVICE_CREDENTIAL)
        return when {
            bio -> BIOMETRIC
            cred -> DEVICE_CREDENTIAL
            else -> null
        }
    }

    /** Есть ли на устройстве настроенная биометрия или код блокировки. */
    fun isAvailable(context: Context): Boolean = resolveAuthenticators(context) != null

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
        val authenticators = resolveAuthenticators(activity)
        if (authenticators == null) { onSuccess(); return }
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
            .setAllowedAuthenticators(authenticators)
            .apply {
                // Одиночный BIOMETRIC_WEAK (без DEVICE_CREDENTIAL в том же вызове)
                // ОБЯЗАН иметь кнопку отмены — иначе PromptInfo.Builder.build()
                // бросает IllegalArgumentException. DEVICE_CREDENTIAL сам
                // предоставляет системную кнопку отмены — с ним setNegativeButtonText
                // несовместим (конфликт на уровне API), поэтому только для BIOMETRIC.
                if (authenticators == BIOMETRIC) {
                    setNegativeButtonText(activity.getString(android.R.string.cancel))
                }
            }
            .build()
        prompt.authenticate(info)
    }
}
