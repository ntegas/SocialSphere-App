package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleDivider
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.IconTile
import com.aistudio.socialsphere.crmlxb.ui.theme.InsetGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToImportExport: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToDuplicates: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val currentLang = AppSettings.currentLanguage.value.displayName

    // Цвета иконок-плиток по макету Aurelia
    val cAppearance = Color(0xFF7E5180) // аметист
    val cImport     = Color(0xFF3E7E7A) // тил
    val cLanguage   = Color(0xFF5E78C4) // синий

    Scaffold(
        modifier = modifier,
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(bottom = 28.dp)
        ) {
            // ── Шапка: круглая кнопка назад + заголовок (по макету) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp).clip(CircleShape).background(AppleTheme.colors.fill)
                        .clickable { onNavigateBack() }
                        .testTag("settings_back_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back),
                        Modifier.size(20.dp), tint = AppleTheme.colors.label)
                }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(
                    text = stringResource(R.string.settings_title)
                )
            }

            InsetGroup {
                SettingsRow(cAppearance, Icons.Default.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub), "setting_appearance", onNavigateToAppearance)
                AppleDivider()
                SettingsRow(AppleTheme.colors.red, Icons.Default.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_sub), "setting_notif", onNavigateToNotifications)
                AppleDivider()
                SettingsRow(cImport, Icons.Default.SwapVert, stringResource(R.string.settings_import_export), stringResource(R.string.settings_import_export_sub), "setting_import_export", onNavigateToImportExport)
                AppleDivider()
                SettingsRow(cLanguage, Icons.Default.Language, stringResource(R.string.settings_language), currentLang, "setting_lang", onNavigateToLanguage)
                AppleDivider()
                SettingsRow(AppleTheme.colors.brand, Icons.Default.CalendarMonth, stringResource(R.string.settings_calendar), stringResource(R.string.settings_calendar_sub), "setting_calendar", onNavigateToCalendar)
                AppleDivider()
                SettingsRow(AppleTheme.colors.orange, Icons.Default.Lock, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_sub), "setting_privacy", onNavigateToPrivacy)
                AppleDivider()
                SettingsRow(AppleTheme.colors.red, Icons.Default.Merge, stringResource(R.string.settings_duplicates), stringResource(R.string.settings_duplicates_sub), "setting_duplicates", onNavigateToDuplicates)
            }

            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.settings_subtitle),
                fontSize = 13.sp,
                color = AppleTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
            )
        }
    }
}

@Composable
private fun SettingsRow(
    tile: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        IconTile(tile) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppleTheme.colors.label)
            Text(subtitle, fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppleTheme.colors.tertiaryLabel, modifier = Modifier.size(20.dp))
    }
}
