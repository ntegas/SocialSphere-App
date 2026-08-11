package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler
import com.aistudio.socialsphere.crmlxb.utils.findActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit
) {
    // ФИКС (2026-07-11, тот же баг, что в ContactDetailScreen): LocalContext.current
    // здесь — createConfigurationContext из LocalizedApp, findActivity() из него
    // до Activity не доходит. LocalView.current.context — настоящий контекст
    // ComposeView, LocalizedApp его не подменяет.
    val context = androidx.compose.ui.platform.LocalView.current.context
    val activity = context.findActivity()
    var showWipeConfirm by remember { mutableStateOf(false) }
    var wipeDone        by remember { mutableStateOf(false) }
    val bioAvailable = remember {
        com.aistudio.socialsphere.crmlxb.utils.BiometricGate.isAvailable(context)
    }
    val confirmEnableTitle = stringResource(R.string.lock_confirm_enable_title)
    // Состояние блокировки приложения — объявлено на верхнем уровне функции
    // (не внутри Column), т.к. читается и из диалогов ниже Scaffold.
    var appLockEnabled by remember { AppSettings.appLockEnabled }
    var hasPin by remember { mutableStateOf(AppSettings.hasPinSet()) }
    var showPinSetup by remember { mutableStateOf(false) }
    var showRemovePinConfirm by remember { mutableStateOf(false) }
    // При ВКЛЮЧЕНИИ защиты не просто ставим флаг — сначала реально проверяем,
    // что способ разблокировки работает (иначе владелец узнал бы о поломке
    // только в момент, когда его уже заперло). При отключении проверка не нужна:
    // экран уже открыт разблокированным устройством/приложением.
    var showAppLockVerify by remember { mutableStateOf(false) }
    var showBioLockVerify by remember { mutableStateOf(false) }
    val canEnableLock = hasPin || bioAvailable
    // Поднято сюда (было внутри Column) — читается и пишется из PinVerifySheet
    // ниже Scaffold, как и appLockEnabled/hasPin.
    var bioLock by remember { AppSettings.biometricLock }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            // ── Шапка ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.priv_title), fontSize = 28.sp)
            }

            // ── Локальное хранение — акцент-карта ──
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18)
                    .background(AppleTheme.colors.brand.copy(alpha = 0.08f))
                    .border(1.dp, AppleTheme.colors.brand.copy(alpha = 0.16f), com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18)
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(
                            Modifier.size(34.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R10).background(AppleTheme.colors.brand),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Lock, null, Modifier.size(17.dp), tint = Color.White) }
                        Text(stringResource(R.string.priv_local_storage), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label)
                    }
                    Text(
                        stringResource(R.string.priv_local_desc),
                        fontSize = 13.sp, lineHeight = 19.sp,
                        color = AppleTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(top = 9.dp)
                    )
                }
            }

            // ── Защита записей: биометрия/код для «Защищено» ──
            Spacer(Modifier.height(22.dp))
            SectionCaps(stringResource(R.string.priv_protection))
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18).background(AppleTheme.colors.card)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .padding(horizontal = 15.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(30.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Small)
                            .background(AppleTheme.colors.brand.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Fingerprint, null, Modifier.size(16.dp), tint = AppleTheme.colors.brand) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.priv_biometric_title), fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, color = AppleTheme.colors.label)
                        Text(
                            if (bioAvailable) stringResource(R.string.priv_biometric_sub)
                            else stringResource(R.string.priv_biometric_unavailable),
                            fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel
                        )
                    }
                    Switch(
                        checked = bioLock,
                        // ФИКС (владелец сообщил: тап по тумблеру не делал ничего):
                        // раньше был `enabled = bioAvailable` — тумблер оставался
                        // НАВСЕГДА disabled на устройстве без биометрии, даже если
                        // задан свой PIN (requestReveal() в ContactDetailScreen.kt
                        // умеет проверять PIN как раз для этого случая, но включить
                        // саму защиту без биометрии было physически невозможно).
                        enabled = canEnableLock,
                        onCheckedChange = { turnOn ->
                            when {
                                !turnOn -> bioLock = false
                                // Реальная проверка ПЕРЕД включением, тем же
                                // приоритетом «биометрия, иначе свой PIN», что и
                                // у «Блокировки приложения» ниже.
                                bioAvailable && activity != null ->
                                    com.aistudio.socialsphere.crmlxb.utils.BiometricGate.authenticate(
                                        activity, confirmEnableTitle
                                    ) { bioLock = true }
                                hasPin -> showBioLockVerify = true
                                else -> {} // canEnableLock=false уже не даёт сюда попасть
                            }
                        }
                    )
                }
            }

            // ── Блокировка приложения: PIN и/или биометрия перед всем контентом ──
            Spacer(Modifier.height(22.dp))
            SectionCaps(stringResource(R.string.lock_app_section))
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18).background(AppleTheme.colors.card)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .padding(horizontal = 15.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Small)
                                .background(AppleTheme.colors.brand.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = AppleTheme.colors.brand) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.lock_app_title), fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, color = AppleTheme.colors.label)
                            Text(
                                if (canEnableLock) stringResource(R.string.lock_app_sub)
                                else stringResource(R.string.lock_app_needs_method),
                                fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel
                            )
                        }
                        Switch(
                            checked = appLockEnabled,
                            enabled = canEnableLock,
                            onCheckedChange = { turnOn ->
                                when {
                                    !turnOn -> appLockEnabled = false
                                    // Предпочитаем биометрию для проверки, если она
                                    // доступна; иначе — свой PIN через ту же шторку,
                                    // что используется для раскрытия заметок.
                                    bioAvailable && activity != null ->
                                        com.aistudio.socialsphere.crmlxb.utils.BiometricGate.authenticate(
                                            activity, confirmEnableTitle
                                        ) { appLockEnabled = true }
                                    hasPin -> showAppLockVerify = true
                                    else -> {} // canEnableLock=false уже не даёт сюда попасть
                                }
                            }
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(color = AppleTheme.colors.separator, modifier = Modifier.padding(start = 57.dp))
                    PrivacyRow(
                        icon = Icons.Default.Lock,
                        iconTint = AppleTheme.colors.brand,
                        title = if (hasPin) stringResource(R.string.lock_pin_change) else stringResource(R.string.lock_pin_set),
                        subtitle = if (hasPin) stringResource(R.string.lock_pin_is_set) else stringResource(R.string.lock_pin_not_set),
                        danger = false,
                        chevron = true
                    ) { showPinSetup = true }
                    if (hasPin) {
                        androidx.compose.material3.HorizontalDivider(color = AppleTheme.colors.separator, modifier = Modifier.padding(start = 57.dp))
                        PrivacyRow(
                            icon = Icons.Default.Delete,
                            iconTint = AppleTheme.colors.alarmRed,
                            title = stringResource(R.string.lock_pin_remove),
                            subtitle = stringResource(R.string.lock_remove_pin_warning),
                            danger = true,
                            chevron = false
                        ) { showRemovePinConfirm = true }
                    }
                }
            }

            // ── Разрешения Android (функция сохранена) ──
            Spacer(Modifier.height(22.dp))
            SectionCaps(stringResource(R.string.priv_android_perms))
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18).background(AppleTheme.colors.card)
            ) {
                PrivacyRow(
                    icon = Icons.Default.Security,
                    iconTint = AppleTheme.colors.brand,
                    title = stringResource(R.string.priv_manage_perms),
                    subtitle = stringResource(R.string.priv_perms_sub),
                    danger = false,
                    chevron = true
                ) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null)
                    )
                    ExternalActionHandler.startIntentSafely(context, intent)
                }
            }

            // ── Опасная зона ──
            Spacer(Modifier.height(22.dp))
            SectionCaps(stringResource(R.string.priv_danger_zone), AppleTheme.colors.alarmRed)
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18).background(AppleTheme.colors.card)
                    .border(1.dp, AppleTheme.colors.alarmRed.copy(alpha = 0.25f), com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18)
            ) {
                PrivacyRow(
                    icon = Icons.Default.DeleteForever,
                    iconTint = AppleTheme.colors.alarmRed,
                    title = stringResource(R.string.priv_delete_all),
                    subtitle = stringResource(R.string.priv_delete_all_sub),
                    danger = true,
                    chevron = false
                ) { showWipeConfirm = true }
            }
            if (wipeDone) {
                Text(
                    stringResource(R.string.priv_deleted),
                    fontSize = 13.sp,
                    color = AppleTheme.colors.brand,
                    modifier = Modifier.padding(start = 22.dp, top = 10.dp)
                )
            }
        }
    }

    if (showAppLockVerify) {
        PinVerifySheet(
            title = confirmEnableTitle,
            onSuccess = { appLockEnabled = true; showAppLockVerify = false },
            onDismiss = { showAppLockVerify = false }
        )
    }

    if (showBioLockVerify) {
        PinVerifySheet(
            title = confirmEnableTitle,
            onSuccess = { bioLock = true; showBioLockVerify = false },
            onDismiss = { showBioLockVerify = false }
        )
    }

    if (showPinSetup) {
        PinSetupSheet(
            onDone = { showPinSetup = false; hasPin = AppSettings.hasPinSet() },
            onDismiss = { showPinSetup = false }
        )
    }

    if (showRemovePinConfirm) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaConfirmDialog(
            onDismiss = { showRemovePinConfirm = false },
            title = stringResource(R.string.lock_remove_pin_q),
            text = stringResource(R.string.lock_remove_pin_warning),
            confirmText = stringResource(R.string.lock_pin_remove),
            destructive = true,
            onConfirm = {
                showRemovePinConfirm = false
                AppSettings.clearPin()
                hasPin = false
                // Без PIN и без биометрии блокировка приложения заперла
                // бы владельца без способа разблокировки — выключаем.
                if (!bioAvailable) appLockEnabled = false
            }
        )
    }

    if (showWipeConfirm) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaConfirmDialog(
            onDismiss = { showWipeConfirm = false },
            title = stringResource(R.string.priv_delete_q),
            text = stringResource(R.string.priv_delete_warning),
            confirmText = stringResource(R.string.priv_delete_confirm),
            destructive = true,
            onConfirm = {
                showWipeConfirm = false
                // wipeDone = ok: при ошибке БД сообщение «удалено» НЕ
                // показываем, чтобы не давать ложного подтверждения.
                AppStateStore.wipeAllData { ok -> wipeDone = ok }
            }
        )
    }
}

@Composable
private fun SectionCaps(text: String, color: Color = AppleTheme.colors.tertiaryLabel) {
    // Тонкая обёртка канонической caps-подписи (ui/theme/AureliaComponents.kt)
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel(
        text, color = color,
        modifier = Modifier.padding(start = 22.dp, bottom = 9.dp)
    )
}

@Composable
private fun PrivacyRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    danger: Boolean,
    chevron: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.heightIn(min = 56.dp).padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(30.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Small).background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(16.dp), tint = iconTint) }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (danger) AppleTheme.colors.alarmRed else AppleTheme.colors.label)
            Text(subtitle, fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)
        }
        if (chevron) Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = AppleTheme.colors.tertiaryLabel)
    }
}
