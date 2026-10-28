package com.aistudio.socialsphere.crmlxb.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor
import com.aistudio.socialsphere.crmlxb.utils.AndroidVoicePlatform
import com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler
import com.aistudio.socialsphere.crmlxb.utils.VoiceAvailability
import com.aistudio.socialsphere.crmlxb.utils.VoiceError
import com.aistudio.socialsphere.crmlxb.utils.VoiceLocale
import com.aistudio.socialsphere.crmlxb.utils.VoicePlatform
import com.aistudio.socialsphere.crmlxb.utils.VoiceRecognitionController
import com.aistudio.socialsphere.crmlxb.utils.VoiceUiState
import com.aistudio.socialsphere.crmlxb.utils.VoiceUnavailableReason
import com.aistudio.socialsphere.crmlxb.utils.findActivity

/** Точка подмены платформы в тестах; в продакшене null → [AndroidVoicePlatform]. */
val LocalVoicePlatform = staticCompositionLocalOf<VoicePlatform?> { null }

/**
 * VOICE PHASE 1 — шторка диагностики on-device распознавания речи. Показывает слушание, частичный и
 * финальный транскрипт. НИЧЕГО не передаёт в Contact и не сохраняет: у шторки нет ни одного колбэка,
 * принимающего текст. Выход (кнопки, scrim, назад, уход экрана, сворачивание приложения) освобождает микрофон.
 */
@Composable
fun VoiceRecognitionSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val platform = LocalVoicePlatform.current ?: remember(context) { AndroidVoicePlatform(context) }
    val controller = remember(platform) {
        VoiceRecognitionController(
            platform,
            VoiceLocale.fromLanguageCode(AppSettings.currentLanguage.value.code)
        )
    }
    val state by controller.state.collectAsState()
    val locale by controller.locale.collectAsState()
    val onDeviceAvailable = remember(platform) { platform.availability() is VoiceAvailability.Available }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val activity = context.findActivity()
        // «Больше не спрашивать»: после отказа система больше не показывает rationale.
        val permanent = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        controller.onPermissionResult(granted, permanent)
    }

    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(controller) { controller.begin() }
    LaunchedEffect(state) {
        if (state is VoiceUiState.AwaitingPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onBackgrounded()
                // Вернулись из системных настроек с выданным разрешением — продолжаем сами.
                Lifecycle.Event.ON_RESUME ->
                    if (controller.state.value is VoiceUiState.PermissionDenied && platform.hasRecordPermission()) {
                        controller.begin()
                    }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val close = {
        controller.cancel()
        onDismiss()
    }
    val openSettings = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        ExternalActionHandler.startIntentSafely(context, intent)
        Unit
    }

    // dismissOnDrag по умолчанию (true): в шторке нет прокручиваемого списка (причина запрета в AureliaPickerSheet,
    // §72), а при false тап по затемнению не закрывает шторку — выход обязан быть доступен любым жестом и всегда
    // идти через close(), который освобождает микрофон. Проверено на эмуляторе: при false скрим не закрывал.
    AureliaSheet(onDismiss = close) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val title = stringResource(R.string.ce_capture_voice)
            Text(
                title,
                fontFamily = aureliaSerifFor(title), fontWeight = FontWeight.W700,
                fontSize = 20.sp, color = AppleTheme.colors.label
            )
            Text(
                stringResource(
                    R.string.voice_diag,
                    platform.sdkInt,
                    stringResource(if (onDeviceAvailable) R.string.voice_yes else R.string.voice_no),
                    locale.tag
                ),
                style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel
            )
            PillChoiceRow(
                options = VoiceLocale.values().map { it.chipLabel() },
                selected = locale.chipLabel(),
                onSelect = { picked ->
                    VoiceLocale.values().firstOrNull { it.chipLabel() == picked }?.let(controller::changeLocale)
                }
            )
            VoiceStateBody(
                state = state,
                onStart = controller::begin,
                onStop = controller::stopListening,
                onClose = close,
                onOpenSettings = openSettings
            )
            Text(
                stringResource(R.string.voice_test_note),
                style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.tertiaryLabel
            )
        }
    }
}

/** Названия языков — как в выборе языка приложения (на собственном языке, не переводятся). */
private fun VoiceLocale.chipLabel(): String = when (this) {
    VoiceLocale.RUSSIAN -> AppLanguage.RUSSIAN.displayName
    VoiceLocale.ENGLISH -> AppLanguage.ENGLISH.displayName
    VoiceLocale.GREEK -> AppLanguage.GREEK.displayName
}

@Composable
private fun VoiceStateBody(
    state: VoiceUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state) {
            VoiceUiState.Idle -> {
                VoiceMessage(stringResource(R.string.voice_ready))
                VoicePrimaryButton(stringResource(R.string.voice_start), onStart)
                VoiceTextAction(stringResource(R.string.common_close), onClose)
            }
            VoiceUiState.AwaitingPermission -> {
                VoiceProgressRow(stringResource(R.string.voice_waiting_permission))
                VoiceTextAction(stringResource(R.string.common_cancel), onClose)
            }
            is VoiceUiState.PermissionDenied -> {
                VoiceMessage(
                    stringResource(
                        if (state.permanent) R.string.voice_perm_denied_permanent else R.string.voice_perm_denied
                    )
                )
                if (state.permanent) {
                    VoicePrimaryButton(stringResource(R.string.voice_open_settings), onOpenSettings)
                } else {
                    VoicePrimaryButton(stringResource(R.string.voice_allow_mic), onStart)
                }
                VoiceTextAction(stringResource(R.string.common_close), onClose)
            }
            is VoiceUiState.Unavailable -> {
                VoiceMessage(
                    stringResource(
                        when (state.reason) {
                            VoiceUnavailableReason.API_TOO_LOW -> R.string.voice_unavailable_api
                            VoiceUnavailableReason.ON_DEVICE_NOT_AVAILABLE -> R.string.voice_unavailable_device
                        }
                    )
                )
                VoiceTextAction(stringResource(R.string.common_close), onClose)
            }
            is VoiceUiState.Listening -> {
                VoiceProgressRow(
                    stringResource(if (state.stopping) R.string.voice_processing else R.string.voice_listening)
                )
                if (state.partial.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            state.partial,
                            style = MaterialTheme.typography.bodyLarge, color = AppleTheme.colors.label
                        )
                    }
                }
                if (!state.stopping) VoicePrimaryButton(stringResource(R.string.voice_stop), onStop)
                VoiceTextAction(stringResource(R.string.common_cancel), onClose)
            }
            is VoiceUiState.Completed -> {
                Text(
                    stringResource(R.string.voice_result_label),
                    style = MaterialTheme.typography.labelMedium, color = AppleTheme.colors.secondaryLabel
                )
                SelectionContainer {
                    Text(
                        state.transcript,
                        style = MaterialTheme.typography.bodyLarge, color = AppleTheme.colors.label,
                        modifier = Modifier.fillMaxWidth()
                            .clip(SocialShape.R14)
                            .background(AppleTheme.colors.fill)
                            .padding(12.dp)
                    )
                }
                VoicePrimaryButton(stringResource(R.string.voice_retry), onStart)
                VoiceTextAction(stringResource(R.string.common_close), onClose)
            }
            is VoiceUiState.Failed -> {
                VoiceMessage(stringResource(state.error.messageRes()))
                VoicePrimaryButton(stringResource(R.string.voice_retry), onStart)
                VoiceTextAction(stringResource(R.string.common_close), onClose)
            }
        }
    }
}

private fun VoiceError.messageRes(): Int = when (this) {
    VoiceError.NO_SPEECH -> R.string.voice_err_no_speech
    VoiceError.NO_MATCH -> R.string.voice_err_no_match
    VoiceError.AUDIO -> R.string.voice_err_audio
    VoiceError.BUSY -> R.string.voice_err_busy
    VoiceError.CLIENT -> R.string.voice_err_client
    VoiceError.INSUFFICIENT_PERMISSIONS -> R.string.voice_err_permission
    VoiceError.LANGUAGE_NOT_SUPPORTED -> R.string.voice_err_lang_unsupported
    VoiceError.LANGUAGE_UNAVAILABLE -> R.string.voice_err_lang_unavailable
    VoiceError.NETWORK_UNEXPECTED -> R.string.voice_err_network
    VoiceError.OTHER -> R.string.voice_err_other
}

@Composable
private fun VoiceMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AppleTheme.colors.label)
}

@Composable
private fun VoiceProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
    }
}

@Composable
private fun VoicePrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = SocialShape.R14,
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

@Composable
private fun VoiceTextAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel)
    }
}
