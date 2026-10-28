package com.aistudio.socialsphere.crmlxb.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VOICE PHASE 1 — машина состояний интеграции (НЕ точность распознавателя: её JVM-тестом не проверить,
 * это ручной тест на реальном устройстве). Транскрипты в тестах — условные строки, не персональные данные.
 */
class VoiceRecognitionControllerTest {

    private fun controller(platform: FakeVoicePlatform, locale: VoiceLocale = VoiceLocale.ENGLISH) =
        VoiceRecognitionController(platform, locale)

    // ── AC1: кнопка запускает поток доступность → разрешение → слушаем ─────────

    @Test
    fun begin_availableAndPermitted_createsExactlyOneEngineAndListens() {
        val p = FakeVoicePlatform()
        val c = controller(p, VoiceLocale.RUSSIAN)
        c.begin()
        assertEquals(VoiceUiState.Listening(), c.state.value)
        assertEquals(1, p.createCalls)
        assertEquals("ru-RU", p.last.startedTag)
    }

    @Test
    fun begin_permissionMissing_awaitsPermission_andDoesNotCreateEngine() {
        val p = FakeVoicePlatform(permission = false)
        val c = controller(p)
        c.begin()
        assertEquals(VoiceUiState.AwaitingPermission, c.state.value)
        assertEquals(0, p.createCalls)
    }

    @Test
    fun begin_checksAvailabilityBeforeAskingForPermission() {
        val p = FakeVoicePlatform(
            availability = VoiceAvailability.Unavailable(VoiceUnavailableReason.ON_DEVICE_NOT_AVAILABLE),
            permission = false
        )
        controller(p).begin()
        assertEquals(1, p.availabilityChecks)
        assertEquals("микрофон не спрашиваем, если слушать всё равно нечем", 0, p.permissionChecks)
    }

    @Test
    fun permissionGranted_afterAwait_startsListening() {
        val p = FakeVoicePlatform(permission = false)
        val c = controller(p)
        c.begin()
        c.onPermissionResult(granted = true, permanentlyDenied = false)
        assertTrue(c.state.value is VoiceUiState.Listening)
        assertEquals(1, p.createCalls)
    }

    // ── AC2: отказ в разрешении возвращает к рабочей форме ─────────────────────

    @Test
    fun permissionDenied_isExplained_noEngine_andRetryIsPossible() {
        val p = FakeVoicePlatform(permission = false)
        val c = controller(p)
        c.begin()
        c.onPermissionResult(granted = false, permanentlyDenied = false)
        assertEquals(VoiceUiState.PermissionDenied(permanent = false), c.state.value)
        assertEquals(0, p.createCalls)

        p.permission = true            // пользователь разрешил позже
        c.begin()
        assertTrue(c.state.value is VoiceUiState.Listening)
    }

    @Test
    fun permissionPermanentlyDenied_isFlaggedForSettingsRoute() {
        val p = FakeVoicePlatform(permission = false)
        val c = controller(p)
        c.begin()
        c.onPermissionResult(granted = false, permanentlyDenied = true)
        assertEquals(VoiceUiState.PermissionDenied(permanent = true), c.state.value)
    }

    @Test
    fun staleOrUnexpectedPermissionResult_isIgnored() {
        val p = FakeVoicePlatform(permission = false)
        val c = controller(p)
        c.onPermissionResult(granted = true, permanentlyDenied = false)   // никто не просил
        assertEquals(VoiceUiState.Idle, c.state.value)
        c.begin()
        c.cancel()                                                         // закрыли шторку до ответа
        c.onPermissionResult(granted = true, permanentlyDenied = false)
        assertEquals(VoiceUiState.Idle, c.state.value)
        assertEquals(0, p.createCalls)
    }

    // ── AC3: недоступный on-device → аккуратное состояние, никакого облака ─────

    @Test
    fun apiBelow31_isGracefullyUnavailable_andNeverCreatesAnEngine() {
        val p = FakeVoicePlatform(
            sdk = 30,
            availability = VoiceAvailability.Unavailable(VoiceUnavailableReason.API_TOO_LOW)
        )
        val c = controller(p)
        c.begin()
        assertEquals(VoiceUiState.Unavailable(VoiceUnavailableReason.API_TOO_LOW), c.state.value)
        assertEquals("никакого фолбэка на другой распознаватель", 0, p.createCalls)
        assertTrue(p.engines.isEmpty())
    }

    @Test
    fun onDeviceRecognizerMissing_isGracefullyUnavailable_andNeverCreatesAnEngine() {
        val p = FakeVoicePlatform(
            availability = VoiceAvailability.Unavailable(VoiceUnavailableReason.ON_DEVICE_NOT_AVAILABLE)
        )
        val c = controller(p)
        c.begin()
        assertEquals(VoiceUiState.Unavailable(VoiceUnavailableReason.ON_DEVICE_NOT_AVAILABLE), c.state.value)
        assertEquals(0, p.createCalls)
    }

    @Test
    fun engineCreationUnsupported_mapsToUnavailable_otherFailureToClientError() {
        val p = FakeVoicePlatform()
        p.createFailure = UnsupportedOperationException("no on-device service")
        val c = controller(p)
        c.begin()
        assertEquals(VoiceUiState.Unavailable(VoiceUnavailableReason.ON_DEVICE_NOT_AVAILABLE), c.state.value)

        p.createFailure = IllegalStateException("boom")
        c.begin()
        assertEquals(VoiceUiState.Failed(VoiceError.CLIENT), c.state.value)
    }

    @Test
    fun engineStartThrowing_leavesListening_andReleasesTheEngine() {
        val p = FakeVoicePlatform()
        p.failEngineStartWith = IllegalStateException("start failed")
        val c = controller(p)
        c.begin()
        assertEquals(VoiceUiState.Failed(VoiceError.CLIENT), c.state.value)
        assertEquals(0, p.liveEngines)
    }

    // ── AC4: частичные результаты обновляют состояние ──────────────────────────

    @Test
    fun partialTranscripts_updateTheListeningState() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        p.last.emitPartial("alpha")
        assertEquals(VoiceUiState.Listening(partial = "alpha"), c.state.value)
        p.last.emitPartial("alpha beta")
        assertEquals(VoiceUiState.Listening(partial = "alpha beta"), c.state.value)
    }

    // ── AC5: финальный результат → Completed, микрофон освобождён ──────────────

    @Test
    fun finalTranscript_completes_andReleasesTheEngine() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        p.last.emitFinal("alpha beta gamma")
        assertEquals(VoiceUiState.Completed("alpha beta gamma"), c.state.value)
        assertTrue(p.last.destroyed)
        assertEquals(0, p.liveEngines)
    }

    @Test
    fun blankFinalTranscript_isNoMatch_notAnEmptySuccess() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        p.last.emitFinal("   ")
        assertEquals(VoiceUiState.Failed(VoiceError.NO_MATCH), c.state.value)
        assertEquals(0, p.liveEngines)
    }

    @Test
    fun stopListening_marksStopping_callsEngineStop_thenFinalCompletes() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        p.last.emitPartial("alpha")
        c.stopListening()
        assertEquals(VoiceUiState.Listening(partial = "alpha", stopping = true), c.state.value)
        assertEquals(1, p.last.stopCalls)
        c.stopListening()                                   // повторный «Стоп» — no-op
        assertEquals(1, p.last.stopCalls)
        p.last.emitFinal("alpha beta")
        assertEquals(VoiceUiState.Completed("alpha beta"), c.state.value)
    }

    // ── AC6: отмена ничего не меняет, поздние колбэки игнорируются ─────────────

    @Test
    fun cancel_whileListening_releasesEngine_andIgnoresLateCallbacks() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        val engine = p.last
        c.cancel()
        assertEquals(VoiceUiState.Idle, c.state.value)
        assertEquals(1, engine.cancelCalls)
        assertTrue(engine.destroyed)

        engine.emitPartial("late partial")
        engine.emitFinal("late final")
        engine.emitError(VoiceError.CLIENT)                 // ERROR_CLIENT, который шлёт сам cancel()
        assertEquals(VoiceUiState.Idle, c.state.value)
    }

    // ── AC7: любая ошибка выводит из «Слушаю…» и освобождает микрофон ──────────

    @Test
    fun everyAndroidErrorCode_leavesListening_andReleasesTheEngine() {
        val expected = mapOf(
            1 to VoiceError.NETWORK_UNEXPECTED, 2 to VoiceError.NETWORK_UNEXPECTED,
            3 to VoiceError.AUDIO, 4 to VoiceError.NETWORK_UNEXPECTED, 5 to VoiceError.CLIENT,
            6 to VoiceError.NO_SPEECH, 7 to VoiceError.NO_MATCH, 8 to VoiceError.BUSY,
            9 to VoiceError.INSUFFICIENT_PERMISSIONS, 10 to VoiceError.BUSY, 11 to VoiceError.OTHER,
            12 to VoiceError.LANGUAGE_NOT_SUPPORTED, 13 to VoiceError.LANGUAGE_UNAVAILABLE,
            14 to VoiceError.OTHER, 15 to VoiceError.OTHER, 99 to VoiceError.OTHER, 0 to VoiceError.OTHER
        )
        for ((code, error) in expected) {
            assertEquals("код $code", error, VoiceErrors.fromAndroidCode(code))
            val p = FakeVoicePlatform()
            val c = controller(p)
            c.begin()
            p.last.emitError(VoiceErrors.fromAndroidCode(code))
            val s = c.state.value
            assertFalse("код $code не должен оставлять Listening", s is VoiceUiState.Listening)
            assertEquals(VoiceUiState.Failed(error), s)
            assertEquals("код $code: микрофон освобождён", 0, p.liveEngines)
        }
    }

    // ── AC8: выход/уничтожение освобождает распознаватель ─────────────────────

    @Test
    fun dispose_whileListening_releasesEngine_andBlocksAnyFurtherUse() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        val engine = p.last
        c.dispose()
        assertTrue(engine.destroyed)
        assertEquals(0, p.liveEngines)

        engine.emitFinal("after dispose")
        assertEquals(VoiceUiState.Idle, c.state.value)

        c.begin()                                           // после dispose запуск невозможен
        c.changeLocale(VoiceLocale.GREEK)
        assertEquals(1, p.createCalls)
        assertEquals(VoiceUiState.Idle, c.state.value)
    }

    @Test
    fun retry_neverHoldsTwoEnginesAtOnce_andStaleCallbacksAreIgnored() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        val first = p.last
        c.begin()                                           // повтор/рестарт
        val second = p.last
        assertTrue(first.destroyed)
        assertEquals(1, p.maxLiveEngines)

        first.emitFinal("stale")                            // колбэк уже уничтоженной сессии
        assertEquals(VoiceUiState.Listening(), c.state.value)
        second.emitFinal("fresh")
        assertEquals(VoiceUiState.Completed("fresh"), c.state.value)
    }

    @Test
    fun onBackgrounded_cancelsListening_butNotThePermissionWait() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        c.begin()
        c.onBackgrounded()
        assertEquals(VoiceUiState.Idle, c.state.value)
        assertEquals(0, p.liveEngines)

        val p2 = FakeVoicePlatform(permission = false)
        val c2 = controller(p2)
        c2.begin()
        c2.onBackgrounded()                                 // системный диалог разрешения не должен сбивать ожидание
        assertEquals(VoiceUiState.AwaitingPermission, c2.state.value)
    }

    // ── Локаль ────────────────────────────────────────────────────────────────

    @Test
    fun changeLocale_whileListening_restartsWithTheNewTag_otherwiseOnlyRemembersIt() {
        val p = FakeVoicePlatform()
        val c = controller(p, VoiceLocale.ENGLISH)
        c.changeLocale(VoiceLocale.GREEK)                   // не слушаем — только запоминаем
        assertEquals(0, p.createCalls)
        assertEquals(VoiceLocale.GREEK, c.locale.value)

        c.begin()
        assertEquals("el-GR", p.last.startedTag)
        c.changeLocale(VoiceLocale.RUSSIAN)                 // слушаем — рестарт на новой локали
        assertEquals(2, p.createCalls)
        assertEquals("ru-RU", p.last.startedTag)
        assertEquals(1, p.maxLiveEngines)
    }

    @Test
    fun appLanguageCodes_mapToRecognitionLocales_unknownFallsBackToEnglish() {
        assertEquals(VoiceLocale.RUSSIAN, VoiceLocale.fromLanguageCode("ru"))
        assertEquals(VoiceLocale.ENGLISH, VoiceLocale.fromLanguageCode("en"))
        assertEquals(VoiceLocale.GREEK, VoiceLocale.fromLanguageCode("el"))
        assertEquals(VoiceLocale.ENGLISH, VoiceLocale.fromLanguageCode("fr"))
        assertEquals(listOf("ru-RU", "en-US", "el-GR"), VoiceLocale.values().map { it.tag })
    }

    @Test
    fun initialState_isIdle_withNothingStarted() {
        val p = FakeVoicePlatform()
        val c = controller(p)
        assertEquals(VoiceUiState.Idle, c.state.value)
        assertNull(p.engines.firstOrNull())
        assertEquals(0, p.availabilityChecks)
    }
}
