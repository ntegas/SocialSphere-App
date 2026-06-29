package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import com.aistudio.socialsphere.crmlxb.ui.theme.InsetRow
import com.aistudio.socialsphere.crmlxb.ui.theme.SectionHeader

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
    val isDark = AppSettings.isDarkTheme.value
    val currentLang = AppSettings.currentLanguage.value.displayName

    Scaffold(
        modifier = modifier,
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_btn").minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = AppleTheme.colors.brand,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppleTheme.colors.groupedBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(bottom = 28.dp)
        ) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(
                text = stringResource(R.string.settings_title),
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 18.dp)
            )

            SectionHeader(stringResource(R.string.settings_system))
            InsetGroup {
                SettingsNavRow(AppleTheme.colors.brand, Icons.Default.Language, stringResource(R.string.settings_language), currentLang, "setting_lang", onNavigateToLanguage)
                AppleDivider()
                SettingsNavRow(AppleTheme.colors.red, Icons.Default.Notifications, stringResource(R.string.settings_notifications), null, "setting_notif", onNavigateToNotifications)
                AppleDivider()
                SettingsNavRow(AppleTheme.colors.orange, Icons.Default.CalendarMonth, stringResource(R.string.settings_calendar), null, "setting_calendar", onNavigateToCalendar)
                AppleDivider()
                SettingsNavRow(AppleTheme.colors.green, Icons.Default.SwapVert, stringResource(R.string.settings_import_export), null, "setting_import_export", onNavigateToImportExport)
                AppleDivider()
                SettingsNavRow(Color(0xFF34C759), Icons.Default.Merge, stringResource(R.string.settings_duplicates), null, "setting_duplicates", onNavigateToDuplicates)
                AppleDivider()
                InsetRow(
                    title = stringResource(R.string.settings_dark_theme),
                    leading = { IconTile(Color(0xFF5856D6)) { Icon(Icons.Default.Palette, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) } },
                    trailing = {
                        Switch(
                            checked = isDark,
                            onCheckedChange = { AppSettings.isDarkTheme.value = it },
                            modifier = Modifier.testTag("setting_theme"),
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.colors.green)
                        )
                    }
                )
                AppleDivider()
                SettingsNavRow(Color(0xFF8E8E93), Icons.Default.Lock, stringResource(R.string.settings_privacy), null, "setting_privacy", onNavigateToPrivacy)
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
private fun SettingsNavRow(
    tile: Color,
    icon: ImageVector,
    title: String,
    value: String?,
    testTag: String,
    onClick: () -> Unit
) {
    InsetRow(
        title = title,
        value = value,
        modifier = Modifier.clickable { onClick() }.testTag(testTag),
        leading = { IconTile(tile) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) } },
        trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppleTheme.colors.tertiaryLabel, modifier = Modifier.size(20.dp)) }
    )
}
