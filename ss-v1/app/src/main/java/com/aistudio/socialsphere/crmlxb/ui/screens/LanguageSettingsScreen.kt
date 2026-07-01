package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleDivider
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.InsetGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            // ── Шапка: круглая кнопка назад + заголовок (по макету) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(AppleTheme.colors.fill).clickable { onNavigateBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back),
                        Modifier.size(20.dp), tint = AppleTheme.colors.label)
                }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.settings_language))
            }

            InsetGroup {
                val langs = AppLanguage.values()
                langs.forEachIndexed { idx, lang ->
                    val selected = AppSettings.currentLanguage.value == lang
                    val badge = when (lang) {
                        AppLanguage.RUSSIAN -> "Ru"
                        AppLanguage.ENGLISH -> "En"
                        AppLanguage.GREEK   -> "Ελ"
                    }
                    LanguageRow(badge, lang.displayName, selected) { AppSettings.currentLanguage.value = lang }
                    if (idx < langs.lastIndex) AppleDivider(startInset = 58.dp)
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(badge: String, title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .heightIn(min = 56.dp)
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) AppleTheme.colors.brand.copy(alpha = 0.12f) else AppleTheme.colors.fill),
            contentAlignment = Alignment.Center
        ) {
            Text(badge, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                color = if (selected) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel)
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label,
            modifier = Modifier.weight(1f))
        Icon(Icons.Default.Check, contentDescription = if (selected) stringResource(R.string.common_done) else null,
            Modifier.size(20.dp), tint = if (selected) AppleTheme.colors.brand else Color.Transparent)
    }
}
