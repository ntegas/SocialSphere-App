package com.aistudio.socialsphere.crmlxb.ui.screens
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onNavigateBack: () -> Unit) {
    var themeChoice by remember { mutableStateOf(AppSettings.isDarkTheme.value) }  // храним bool, не строку

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_appearance), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Theme section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                    Spacer(Modifier.height(12.dp))
                    listOf(stringResource(R.string.appearance_light) to false, stringResource(R.string.appearance_dark) to true).forEach { (label, isDark) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                themeChoice = isDark
                                AppSettings.isDarkTheme.value = isDark
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                            if (themeChoice == isDark) {
                                Icon(Icons.Default.Check, null, tint = AppleTheme.colors.brand)
                            }
                        }
                        if (!isDark) HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                    }
                }
            }

            // Accent section (по макету: 4 круга 44dp с кольцом активного)
            val currentAccent by AppSettings.accentColor
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.appearance_accent),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AccentColor.values().forEach { ac ->
                            val sel = currentAccent == ac
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .then(
                                            if (sel) Modifier.border(2.dp, Color(ac.rgb), CircleShape)
                                            else Modifier
                                        )
                                        .clickable { AppSettings.accentColor.value = ac }
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier.size(44.dp).clip(CircleShape).background(Color(ac.rgb))
                                    )
                                }
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    stringResource(ac.labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (sel) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel
                                )
                            }
                        }
                    }
                }
            }

            // Note about dark mode
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.brand.copy(alpha = 0.10f).copy(alpha = 0.4f))
            ) {
                Text(
                    stringResource(R.string.appearance_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.brand,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}
