package com.aistudio.socialsphere.crmlxb.utils

/** Управляемая подмена платформы/движка для проверки машины состояний Voice без реального SpeechRecognizer. */
class FakeVoiceEngine(val listener: VoiceEngineListener, private val onRelease: () -> Unit) : VoiceEngine {
    var startedTag: String? = null
    var stopCalls = 0
    var cancelCalls = 0
    var destroyed = false

    var failOnStart: Exception? = null

    override fun start(localeTag: String) {
        failOnStart?.let { throw it }
        startedTag = localeTag
    }

    override fun stop() { stopCalls++ }
    override fun cancel() { cancelCalls++ }
    override fun destroy() {
        if (!destroyed) { destroyed = true; onRelease() }
    }

    fun emitPartial(text: String) = listener.onPartial(text)
    fun emitFinal(text: String) = listener.onFinal(text)
    fun emitError(error: VoiceError) = listener.onError(error)
}

class FakeVoicePlatform(
    var sdk: Int = 34,
    var availability: VoiceAvailability = VoiceAvailability.Available,
    var permission: Boolean = true
) : VoicePlatform {
    val engines = mutableListOf<FakeVoiceEngine>()
    var availabilityChecks = 0
    var permissionChecks = 0
    var createCalls = 0
    var createFailure: Exception? = null
    var failEngineStartWith: Exception? = null

    private var live = 0
    var maxLiveEngines = 0
        private set

    val liveEngines: Int get() = live
    val last: FakeVoiceEngine get() = engines.last()

    override val sdkInt: Int get() = sdk

    override fun availability(): VoiceAvailability { availabilityChecks++; return availability }

    override fun hasRecordPermission(): Boolean { permissionChecks++; return permission }

    override fun createEngine(listener: VoiceEngineListener): VoiceEngine {
        createCalls++
        createFailure?.let { throw it }
        live++
        if (live > maxLiveEngines) maxLiveEngines = live
        return FakeVoiceEngine(listener) { live-- }.also {
            it.failOnStart = failEngineStartWith
            engines += it
        }
    }
}
