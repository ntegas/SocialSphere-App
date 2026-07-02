package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var showWipeConfirm by remember { mutableStateOf(false) }
    var wipeDone        by remember { mutableStateOf(false) }

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
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppleTheme.colors.brand.copy(alpha = 0.08f))
                    .border(1.dp, AppleTheme.colors.brand.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AppleTheme.colors.brand),
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

            // ── Разрешения Android (функция сохранена) ──
            Spacer(Modifier.height(22.dp))
            SectionCaps(stringResource(R.string.priv_android_perms))
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(18.dp)).background(AppleTheme.colors.card)
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
                    .clip(RoundedCornerShape(18.dp)).background(AppleTheme.colors.card)
                    .border(1.dp, AppleTheme.colors.alarmRed.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
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

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text(stringResource(R.string.priv_delete_q), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.priv_delete_warning)) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.red),
                    onClick = {
                        showWipeConfirm = false
                        // wipeDone = ok: при ошибке БД сообщение «удалено» НЕ
                        // показываем, чтобы не давать ложного подтверждения.
                        AppStateStore.wipeAllData { ok -> wipeDone = ok }
                    }
                ) { Text(stringResource(R.string.priv_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
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
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(16.dp), tint = iconTint) }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (danger) AppleTheme.colors.alarmRed else AppleTheme.colors.label)
            Text(subtitle, fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)
        }
        if (chevron) Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = AppleTheme.colors.tertiaryLabel)
    }
}
