package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.BiometricGate
import com.aistudio.socialsphere.crmlxb.utils.findActivity

private const val PIN_LENGTH = 6
private const val PIN_MIN_LENGTH = 4

/** Обратный отсчёт блокировки после серии неверных PIN (см. AppSettings.pinLockRemainingMs()).
 *  Тикает раз в 0.5с, пока composable в композиции — не персистентное состояние,
 *  сама блокировка персистится в AppSettings. */
@Composable
private fun rememberPinLockSeconds(): Long {
    var seconds by remember { mutableStateOf((AppSettings.pinLockRemainingMs() + 999) / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            seconds = (AppSettings.pinLockRemainingMs() + 999) / 1000
            kotlinx.coroutines.delay(500)
        }
    }
    return seconds
}

/** Точки-индикаторы введённых цифр PIN (по образцу iOS/системных PIN-экранов). */
@Composable
private fun PinDots(enteredLength: Int, maxLength: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(maxLength) { i ->
            val filled = i < enteredLength
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(
                        when {
                            error -> AppleTheme.colors.red
                            filled -> AppleTheme.colors.brand
                            else -> AppleTheme.colors.separator
                        }
                    )
            )
        }
    }
}

/** Цифровая клавиатура 0-9 + backspace, используется и для ввода, и для смены PIN. */
@Composable
private fun PinKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9')
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { digit ->
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(AppleTheme.colors.card)
                            .clickable { onDigit(digit) },
                        contentAlignment = Alignment.Center
                    ) { Text(digit.toString(), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(64.dp)) // пустая ячейка под «0» слева
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(AppleTheme.colors.card)
                    .clickable { onDigit('0') },
                contentAlignment = Alignment.Center
            ) { Text("0", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label) }
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .clickable { onBackspace() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Backspace, null, tint = AppleTheme.colors.secondaryLabel) }
        }
    }
}

/**
 * Общий экран/блок ввода PIN: заголовок, точки, клавиатура, опциональная
 * кнопка биометрии. Используется и для проверки (unlock/reveal), и для
 * первичной установки/смены (см. [PinSetupSheet]).
 */
@Composable
private fun PinEntryBody(
    title: String,
    subtitle: String? = null,
    error: Boolean,
    enteredLength: Int,
    maxLength: Int = PIN_LENGTH,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    showBiometric: Boolean = false,
    onBiometric: (() -> Unit)? = null,
    continueEnabled: Boolean = false,
    onContinue: (() -> Unit)? = null
) {
    // ФИКС: с клавиатурой (кнопка «Продолжить» + биометрия) полная высота этого
    // блока почти ровно упиралась в потолок AureliaSheet (heightIn(max=560.dp)),
    // а с учётом ЕЁ собственных внутренних отступов — превышала его, заставляя
    // прокручивать шторку, чтобы увидеть кнопку/нижний ряд клавиатуры (то самое
    // «коряво» — владелец о нём сообщил). Уменьшены отступы, чтобы контент
    // гарантированно помещался без скролла на типичном экране.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
    ) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel)
        PinDots(enteredLength, maxLength, error)
        PinKeypad(onDigit = onDigit, onBackspace = onBackspace)
        if (onContinue != null) {
            // ФИКС (владелец сообщил: PIN-экран выглядит «коряво») — раньше
            // единственная кнопка на голых Material3-дефолтах (маленькая пилюля
            // произвольной ширины) посреди кастомного дизайна; стилизована как
            // везде в проекте (широкая акцент-кнопка 48dp/r14, см. AureliaFormSheet).
            Button(
                onClick = onContinue,
                enabled = continueEnabled,
                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(stringResource(R.string.lock_continue), fontWeight = FontWeight.Bold) }
        }
        if (showBiometric && onBiometric != null) {
            TextButton(onClick = onBiometric) {
                Icon(Icons.Filled.Fingerprint, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.lock_use_biometric), color = AppleTheme.colors.brand, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Полноэкранная блокировка приложения — показывается ДО остального контента,
 * пока не введён верный PIN или не пройдена биометрия. Не даёт себя закрыть
 * (нет кнопки «назад»/dismiss) — иначе блокировка бессмысленна.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val bioAvailable = remember { activity != null && BiometricGate.isAvailable(context) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var showForgotPinConfirm by remember { mutableStateOf(false) }
    val bioTitle = stringResource(R.string.lock_unlock_title)
    val lockSeconds = rememberPinLockSeconds()
    val locked = lockSeconds > 0
    // ФИКС (2026-07-08, владелец: «показываешь 6 точек, хотя ввожу 4»): точек
    // теперь ровно столько, сколько реально задано при настройке PIN.
    val realPinLength = remember { AppSettings.currentPinLength().takeIf { it > 0 } ?: PIN_LENGTH }

    fun tryBiometric() {
        if (activity != null) {
            BiometricGate.authenticate(activity, bioTitle) { onUnlocked() }
        }
    }

    // Автоматически предлагаем биометрию сразу при показе экрана — не
    // заставляем владельца тыкать лишнюю кнопку, если способ уже под рукой.
    LaunchedEffect(Unit) { if (bioAvailable) tryBiometric() }

    Box(Modifier.fillMaxSize().background(AppleTheme.colors.groupedBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PinEntryBody(
                title = stringResource(R.string.lock_unlock_title),
                subtitle = when {
                    locked -> stringResource(R.string.lock_too_many_attempts_sec, lockSeconds)
                    error -> stringResource(R.string.lock_wrong_pin)
                    else -> null
                },
                error = error,
                enteredLength = pin.length,
                maxLength = realPinLength,
                onDigit = { d ->
                    if (!locked && pin.length < realPinLength) { pin += d; error = false }
                },
                onBackspace = { if (!locked && pin.isNotEmpty()) { pin = pin.dropLast(1); error = false } },
                showBiometric = bioAvailable,
                onBiometric = { tryBiometric() }
            )
            // PIN не шифрует данные — это программный гейт UI (сама БД в открытом
            // виде, см. §20 базы знаний). Забытый PIN + недоступная биометрия иначе
            // навсегда заперли бы владельца от РЕАЛЬНЫХ личных данных — недопустимо
            // по правилу проекта. Сброс снимает только замок, данные не трогает.
            TextButton(onClick = { showForgotPinConfirm = true }) {
                Text(
                    stringResource(R.string.lock_forgot_pin),
                    color = AppleTheme.colors.secondaryLabel,
                    fontSize = 13.sp
                )
            }
        }
    }

    if (showForgotPinConfirm) {
        AlertDialog(
            onDismissRequest = { showForgotPinConfirm = false },
            title = { Text(stringResource(R.string.lock_forgot_pin_q), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.lock_forgot_pin_warning)) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.red),
                    onClick = {
                        showForgotPinConfirm = false
                        AppSettings.clearPin()
                        AppSettings.appLockEnabled.value = false
                        onUnlocked()
                    }
                ) { Text(stringResource(R.string.lock_forgot_pin_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgotPinConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // Проверка PIN — теперь строго при достижении РЕАЛЬНОЙ длины (realPinLength),
    // не диапазона 4..6. Раньше проверяли на каждой длине из диапазона (не зная
    // настоящую длину) — работало, но точки на экране не совпадали с ней же.
    // При активной блокировке (серия неверных попыток) — не проверяем вовсе,
    // иначе продолжение набора цифр тихо продлевало бы саму блокировку.
    LaunchedEffect(pin) {
        if (!locked && pin.length == realPinLength && AppSettings.hasPinSet()) {
            if (AppSettings.verifyPin(pin)) {
                onUnlocked()
            } else {
                error = true
                pin = ""
            }
        }
    }
}

/**
 * Шторка проверки PIN для точечного сценария (например, раскрыть «защищённую»
 * заметку), когда биометрия недоступна. onSuccess вызывается один раз при
 * верном коде; onDismiss — закрыть без разблокировки.
 */
@Composable
fun PinVerifySheet(title: String, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val lockSeconds = rememberPinLockSeconds()
    val locked = lockSeconds > 0
    // ФИКС (2026-07-08): точки — по реальной длине заданного PIN, не всегда 6.
    val realPinLength = remember { AppSettings.currentPinLength().takeIf { it > 0 } ?: PIN_LENGTH }
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            PinEntryBody(
                title = title,
                subtitle = when {
                    locked -> stringResource(R.string.lock_too_many_attempts_sec, lockSeconds)
                    error -> stringResource(R.string.lock_wrong_pin)
                    else -> null
                },
                error = error,
                enteredLength = pin.length,
                maxLength = realPinLength,
                onDigit = { d ->
                    if (!locked && pin.length < realPinLength) { pin += d; error = false }
                },
                onBackspace = { if (!locked && pin.isNotEmpty()) { pin = pin.dropLast(1); error = false } }
            )
        }
    }
    LaunchedEffect(pin) {
        if (!locked && pin.length == realPinLength) {
            if (AppSettings.verifyPin(pin)) onSuccess()
            else { error = true; pin = "" }
        }
    }
}

/**
 * Установка/смена PIN: если PIN уже задан — сначала проверка старого,
 * затем ввод нового дважды (защита от опечатки, которая заперла бы владельца).
 */
@Composable
fun PinSetupSheet(onDone: () -> Unit, onDismiss: () -> Unit) {
    var stage by remember { mutableStateOf(if (AppSettings.hasPinSet()) Stage.VERIFY_OLD else Stage.ENTER_NEW) }
    var firstPin by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val lockSeconds = rememberPinLockSeconds()
    // Блокировка от подбора актуальна только пока проверяем СТАРЫЙ PIN —
    // на шагах ввода/подтверждения НОВОГО она бы просто мешала владельцу,
    // который свой же новый код набирает, а не подбирает чужой.
    val locked = stage == Stage.VERIFY_OLD && lockSeconds > 0

    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            val (title, subtitle) = when (stage) {
                Stage.VERIFY_OLD -> stringResource(R.string.lock_enter_current_pin) to null
                Stage.ENTER_NEW -> stringResource(R.string.lock_enter_new_pin) to stringResource(R.string.lock_pin_length_hint)
                Stage.CONFIRM_NEW -> stringResource(R.string.lock_confirm_new_pin) to null
            }
            PinEntryBody(
                title = title,
                subtitle = when {
                    locked -> stringResource(R.string.lock_too_many_attempts_sec, lockSeconds)
                    error -> stringResource(R.string.lock_wrong_pin)
                    else -> subtitle
                },
                error = error,
                enteredLength = draft.length,
                onDigit = { d ->
                    if (!locked && draft.length < PIN_LENGTH) { draft += d; error = false }
                },
                onBackspace = { if (!locked && draft.isNotEmpty()) { draft = draft.dropLast(1); error = false } },
                // «Продолжить» — только на шаге ввода нового PIN, т.к. длина
                // намеренно гибкая (4-6 цифр), автоперенос по максимуму
                // заставил бы всегда вводить ровно 6 цифр.
                continueEnabled = stage == Stage.ENTER_NEW && draft.length in PIN_MIN_LENGTH..PIN_LENGTH,
                onContinue = if (stage == Stage.ENTER_NEW) {
                    { firstPin = draft; draft = ""; error = false; stage = Stage.CONFIRM_NEW }
                } else null
            )
        }
    }

    LaunchedEffect(draft) {
        when (stage) {
            Stage.VERIFY_OLD -> {
                if (!locked && draft.length in PIN_MIN_LENGTH..PIN_LENGTH) {
                    if (AppSettings.verifyPin(draft)) {
                        draft = ""; stage = Stage.ENTER_NEW
                    } else if (draft.length == PIN_LENGTH) { error = true; draft = "" }
                }
            }
            Stage.ENTER_NEW -> {
                // Переход дальше — только по явному тапу «Продолжить» (см. onContinue выше).
            }
            Stage.CONFIRM_NEW -> {
                if (draft.length == firstPin.length) {
                    if (draft == firstPin) {
                        AppSettings.setPin(firstPin)
                        onDone()
                    } else {
                        error = true; draft = ""; firstPin = ""; stage = Stage.ENTER_NEW
                    }
                }
            }
        }
    }
}

private enum class Stage { VERIFY_OLD, ENTER_NEW, CONFIRM_NEW }
