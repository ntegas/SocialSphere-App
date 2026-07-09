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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onNavigateBack: () -> Unit) {
    val isDark = AppSettings.isDarkTheme.value
    val currentAccent by AppSettings.accentColor

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
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.settings_appearance), fontSize = 28.sp)
            }

            // ── ТЕМА — две карточки-превью ──
            CapsLabel(stringResource(R.string.appearance_theme))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                ThemePreview(
                    label = stringResource(R.string.appearance_light),
                    selected = !isDark,
                    previewBg = Color(0xFFF1EDE6),
                    stripColor = Color(0xFFFCFBF8),
                    lineColor = Color(0x1F232018),
                    modifier = Modifier.weight(1f)
                ) { AppSettings.isDarkTheme.value = false }
                ThemePreview(
                    label = stringResource(R.string.appearance_dark),
                    selected = isDark,
                    previewBg = Color(0xFF13110D),
                    stripColor = Color(0xFF23201A),
                    lineColor = Color(0x29F3EFE8),
                    modifier = Modifier.weight(1f)
                ) { AppSettings.isDarkTheme.value = true }
            }

            // ── АКЦЕНТ — круги + превью ──
            Spacer(Modifier.height(22.dp))
            CapsLabel(stringResource(R.string.appearance_accent))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AccentColor.values().forEach { ac ->
                    val sel = currentAccent == ac
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(52.dp).clip(CircleShape)
                                .then(if (sel) Modifier.border(2.dp, Color(ac.rgb), CircleShape) else Modifier)
                                .clickable { AppSettings.accentColor.value = ac }
                                .padding(5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(ac.rgb)))
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            stringResource(ac.labelRes),
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (sel) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel
                        )
                    }
                }
            }

            // Превью акцента
            Box(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp)
                    .height(48.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14).background(AppleTheme.colors.brand),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
                    Text(stringResource(R.string.appearance_accent_preview), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // ── Примечание о тёмной теме ──
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large).background(AppleTheme.colors.brand.copy(alpha = 0.08f)).padding(14.dp)
            ) {
                Text(
                    stringResource(R.string.appearance_note),
                    fontSize = 13.sp, lineHeight = 19.sp,
                    color = AppleTheme.colors.secondaryLabel
                )
            }
        }
    }
}

@Composable
private fun CapsLabel(text: String) {
    // Тонкая обёртка канонической caps-подписи (ui/theme/AureliaComponents.kt)
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel(
        text,
        modifier = Modifier.padding(start = 22.dp, bottom = 10.dp)
    )
}

@Composable
private fun ThemePreview(
    label: String,
    selected: Boolean,
    previewBg: Color,
    stripColor: Color,
    lineColor: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(modifier = modifier.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth().height(120.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18).background(previewBg)
                .then(if (selected) Modifier.border(2.dp, AppleTheme.colors.brand, com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18) else Modifier)
                .padding(12.dp)
        ) {
            Column {
                Box(Modifier.fillMaxWidth().height(14.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R5).background(stripColor))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(10.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XSmall).background(lineColor))
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFE59A6B), Color(0xFFC45D34))))
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = if (selected) AppleTheme.colors.brand else Color.Transparent)
            Text(label.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (selected) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel)
        }
    }
}
