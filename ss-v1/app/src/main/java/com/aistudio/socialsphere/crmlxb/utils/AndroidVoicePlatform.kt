package com.aistudio.socialsphere.crmlxb.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Voice phase 1: Android-реализация [VoicePlatform]. Используется ТОЛЬКО on-device распознаватель
 * (API 31+). Вызова SpeechRecognizer.createSpeechRecognizer() — сетевого/системного по умолчанию —
 * в приложении нет и быть не должно: речь контактов не должна молча уходить в непонятную службу.
 */
class AndroidVoicePlatform(context: Context) : VoicePlatform {
    private val appContext: Context = context.applicationContext

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun availability(): VoiceAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return VoiceAvailability.Unavailable(VoiceUnavailableReason.API_TOO_LOW)
        }
        val onDevice = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } catch (e: Exception) {
            false
        }
        return if (onDevice) VoiceAvailability.Available
        else VoiceAvailability.Unavailable(VoiceUnavailableReason.ON_DEVICE_NOT_AVAILABLE)
    }

    override fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun createEngine(listener: VoiceEngineListener): VoiceEngine {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw UnsupportedOperationException("On-device speech recognition requires API 31+")
        }
        return AndroidOnDeviceVoiceEngine(appContext, listener)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
internal class AndroidOnDeviceVoiceEngine(
    context: Context,
    private val listener: VoiceEngineListener
) : VoiceEngine {

    private val callingPackage: String = context.packageName

    /** Бросает UnsupportedOperationException, если on-device службы нет — контроллер покажет Unavailable. */
    private var recognizer: SpeechRecognizer? = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            // Сырое аудио намеренно не сохраняется и не передаётся дальше.
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                listener.onFinal(results.firstResult().orEmpty())
            }

            override fun onError(error: Int) {
                listener.onError(VoiceErrors.fromAndroidCode(error))
            }
        })
    }

    override fun start(localeTag: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
        }
        recognizer?.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun destroy() {
        val r = recognizer
        recognizer = null
        r?.destroy()
    }

    /** Лучший (первый) вариант распознавания; confidence не используем — не везде надёжен. */
    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
