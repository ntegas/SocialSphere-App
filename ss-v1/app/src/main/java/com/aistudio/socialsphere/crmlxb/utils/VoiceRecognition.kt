package com.aistudio.socialsphere.crmlxb.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * VOICE PHASE 1 — диагностика on-device распознавания речи. Чистая логика без Android-зависимостей
 * (JVM-тестируется целиком). Android-часть — AndroidVoicePlatform.kt, UI — VoiceRecognitionSheet.kt.
 *
 * ИНВАРИАНТЫ:
 *  • Единственный выход — текст транскрипта в UI. Ни Contact, ни AppStateStore здесь не используются.
 *  • Никакого облачного фолбэка: платформа создаёт ТОЛЬКО on-device движок или не создаёт никакого.
 *  • Не более одного живого движка; микрофон освобождается на любом выходе (cancel/dispose/ошибка/финал).
 *  • Устаревший колбэк (после cancel/restart/dispose) ничего не меняет — проверка по номеру сессии.
 *  • Транскрипт и любые данные речи НИКОГДА не логируются.
 */

/** Локали распознавания, доступные для ручного теста фазы 1. */
enum class VoiceLocale(val tag: String) {
    RUSSIAN("ru-RU"),
    ENGLISH("en-US"),
    GREEK("el-GR");

    companion object {
        /** Код языка приложения (AppLanguage.code) → локаль распознавания; неизвестный → английский (базовый язык приложения). */
        fun fromLanguageCode(code: String): VoiceLocale = when (code) {
            "ru" -> RUSSIAN
            "el" -> GREEK
            else -> ENGLISH
        }
    }
}

enum class VoiceUnavailableReason {
    /** Android < 12: on-device SpeechRecognizer API (createOnDeviceSpeechRecognizer) появился в API 31. */
    API_TOO_LOW,
    /** API достаточен, но на устройстве нет on-device службы распознавания. */
    ON_DEVICE_NOT_AVAILABLE
}

enum class VoiceError {
    NO_SPEECH, NO_MATCH, AUDIO, BUSY, CLIENT, INSUFFICIENT_PERMISSIONS,
    LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE,
    /** On-device распознаватель сообщил о сети — не должно случаться; показываем честно, не скрываем. */
    NETWORK_UNEXPECTED,
    OTHER
}

/** Коды SpeechRecognizer.ERROR_* → VoiceError. Числа, а не константы: часть констант есть только на API 31/33. */
object VoiceErrors {
    fun fromAndroidCode(code: Int): VoiceError = when (code) {
        3 -> VoiceError.AUDIO                       // ERROR_AUDIO
        5 -> VoiceError.CLIENT                      // ERROR_CLIENT
        6 -> VoiceError.NO_SPEECH                   // ERROR_SPEECH_TIMEOUT
        7 -> VoiceError.NO_MATCH                    // ERROR_NO_MATCH
        8, 10 -> VoiceError.BUSY                    // ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS
        9 -> VoiceError.INSUFFICIENT_PERMISSIONS    // ERROR_INSUFFICIENT_PERMISSIONS
        12 -> VoiceError.LANGUAGE_NOT_SUPPORTED     // ERROR_LANGUAGE_NOT_SUPPORTED
        13 -> VoiceError.LANGUAGE_UNAVAILABLE       // ERROR_LANGUAGE_UNAVAILABLE
        1, 2, 4 -> VoiceError.NETWORK_UNEXPECTED    // ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_SERVER
        else -> VoiceError.OTHER                    // 11 SERVER_DISCONNECTED, 14, 15, неизвестные
    }
}

sealed interface VoiceUiState {
    /** Готов к запуску (также — после отмены/сворачивания). Ничего не слушается. */
    data object Idle : VoiceUiState
    data object AwaitingPermission : VoiceUiState
    data class PermissionDenied(val permanent: Boolean) : VoiceUiState
    data class Unavailable(val reason: VoiceUnavailableReason) : VoiceUiState
    /** [partial] — последний частичный результат; [stopping] — пользователь нажал «Стоп», ждём финал. */
    data class Listening(val partial: String = "", val stopping: Boolean = false) : VoiceUiState
    data class Completed(val transcript: String) : VoiceUiState
    data class Failed(val error: VoiceError) : VoiceUiState
}

sealed interface VoiceAvailability {
    data object Available : VoiceAvailability
    data class Unavailable(val reason: VoiceUnavailableReason) : VoiceAvailability
}

interface VoiceEngineListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: VoiceError)
}

interface VoiceEngine {
    fun start(localeTag: String)
    /** Закончить запись и выдать финальный результат. */
    fun stop()
    /** Прервать без результата. */
    fun cancel()
    /** Освободить распознаватель и микрофон. После этого движок не используется. */
    fun destroy()
}

interface VoicePlatform {
    val sdkInt: Int
    fun availability(): VoiceAvailability
    fun hasRecordPermission(): Boolean
    /**
     * Создаёт ТОЛЬКО on-device движок. [UnsupportedOperationException] — on-device недоступен
     * (контроллер покажет Unavailable). Никакого фолбэка на сетевой распознаватель.
     */
    fun createEngine(listener: VoiceEngineListener): VoiceEngine
}

class VoiceRecognitionController(
    private val platform: VoicePlatform,
    initialLocale: VoiceLocale
) {
    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _locale = MutableStateFlow(initialLocale)
    val locale: StateFlow<VoiceLocale> = _locale.asStateFlow()

    private var engine: VoiceEngine? = null
    private var session = 0
    private var disposed = false

    /** Единая точка запуска/повтора: доступность → разрешение → слушаем. Прежний движок всегда освобождается первым. */
    fun begin() {
        if (disposed) return
        releaseEngine(cancel = true)
        val availability = platform.availability()
        if (availability is VoiceAvailability.Unavailable) {
            _state.value = VoiceUiState.Unavailable(availability.reason)
            return
        }
        if (!platform.hasRecordPermission()) {
            _state.value = VoiceUiState.AwaitingPermission
            return
        }
        startEngine()
    }

    /** Результат системного запроса разрешения. Устаревший ответ (экран уже закрыт/отменён) игнорируется. */
    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (disposed || _state.value != VoiceUiState.AwaitingPermission) return
        if (granted) startEngine() else _state.value = VoiceUiState.PermissionDenied(permanentlyDenied)
    }

    fun stopListening() {
        val s = _state.value
        if (disposed || s !is VoiceUiState.Listening || s.stopping) return
        _state.value = s.copy(stopping = true)
        try {
            engine?.stop()
        } catch (e: Exception) {
            releaseEngine(cancel = true)
            _state.value = VoiceUiState.Failed(VoiceError.CLIENT)
        }
    }

    /** Отмена пользователем/закрытие шторки: микрофон освобождён, состояние Idle, НИЧЕГО не записано. */
    fun cancel() {
        if (disposed) return
        releaseEngine(cancel = true)
        _state.value = VoiceUiState.Idle
    }

    /** Приложение ушло в фон: слушающую сессию прерываем; ожидание разрешения не трогаем. */
    fun onBackgrounded() {
        if (_state.value is VoiceUiState.Listening) cancel()
    }

    fun changeLocale(newLocale: VoiceLocale) {
        if (disposed) return
        _locale.value = newLocale
        if (_state.value is VoiceUiState.Listening) begin()
    }

    /** Экран ушёл из композиции: освободить всё, дальнейшее использование — no-op. */
    fun dispose() {
        if (disposed) return
        releaseEngine(cancel = true)
        disposed = true
        _state.value = VoiceUiState.Idle
    }

    private fun startEngine() {
        val sessionId = ++session
        val listener = object : VoiceEngineListener {
            override fun onPartial(text: String) {
                if (!isLive(sessionId)) return
                val s = _state.value
                if (s is VoiceUiState.Listening) _state.value = s.copy(partial = text)
            }

            override fun onFinal(text: String) {
                if (!isLive(sessionId)) return
                releaseEngine(cancel = false)
                _state.value =
                    if (text.isBlank()) VoiceUiState.Failed(VoiceError.NO_MATCH) else VoiceUiState.Completed(text)
            }

            override fun onError(error: VoiceError) {
                if (!isLive(sessionId)) return
                releaseEngine(cancel = false)
                _state.value = VoiceUiState.Failed(error)
            }
        }
        val created = try {
            platform.createEngine(listener)
        } catch (e: UnsupportedOperationException) {
            _state.value = VoiceUiState.Unavailable(VoiceUnavailableReason.ON_DEVICE_NOT_AVAILABLE)
            return
        } catch (e: Exception) {
            _state.value = VoiceUiState.Failed(VoiceError.CLIENT)
            return
        }
        engine = created
        _state.value = VoiceUiState.Listening()
        try {
            created.start(_locale.value.tag)
        } catch (e: Exception) {
            releaseEngine(cancel = true)
            _state.value = VoiceUiState.Failed(VoiceError.CLIENT)
        }
    }

    private fun isLive(sessionId: Int): Boolean =
        !disposed && sessionId == session && engine != null && _state.value is VoiceUiState.Listening

    /** Инвалидирует сессию ДО обращения к движку — колбэки, вызванные самим cancel/destroy, уже считаются устаревшими. */
    private fun releaseEngine(cancel: Boolean) {
        val e = engine
        engine = null
        session++
        if (e == null) return
        if (cancel) {
            try { e.cancel() } catch (ex: Exception) { /* уже освобождаем */ }
        }
        try { e.destroy() } catch (ex: Exception) { /* уже освобождаем */ }
    }
}
