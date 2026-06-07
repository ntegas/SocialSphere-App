package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Общие уведомления
            CardBlock("Общие настройки") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Включить уведомления", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = com.example.ui.screens.AppSettings.isNotificationsEnabled.value, onCheckedChange = { com.example.ui.screens.AppSettings.isNotificationsEnabled.value = it })
                }
                Text("Стандартное напоминание", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                FilterChipsRow(listOf("за 1 день", "за 1 час", "в момент события"), "за 1 день")
            }

            // Дни рождения
            CardBlock("Дни рождения") {
                FilterChipsRow(
                    listOf("без уведомления", "в день события", "за 1 день", "за 3 дня", "за неделю", "свой вариант"),
                    "за 1 день",
                    allowMultiple = true,
                    selectedMultiple = setOf("в день события", "за 1 день")
                )
            }

            // Подарки
            CardBlock("Подарки") {
                FilterChipsRow(
                    listOf("за неделю", "за 3 дня", "за 1 день", "свой вариант"),
                    "за 3 дня"
                )
            }

            // Встречи
            CardBlock("Встречи") {
                FilterChipsRow(
                    listOf("за 10 минут", "за 30 минут", "за 1 час", "за 1 день"),
                    "за 1 час"
                )
            }

            // Звонки и сообщения
            CardBlock("Звонки и сообщения") {
                FilterChipsRow(
                    listOf("в момент события", "за 10 минут", "за 1 час"),
                    "за 10 минут"
                )
            }

            // Просроченные события
            CardBlock("Просроченные события") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Показывать просроченные", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = true, onCheckedChange = {})
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Повторять напоминание визуально", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = false, onCheckedChange = {})
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterChipsRow(
    options: List<String>,
    selected: String,
    allowMultiple: Boolean = false,
    selectedMultiple: Set<String> = emptySet()
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSelected = if (allowMultiple) selectedMultiple.contains(opt) else selected == opt
            FilterChip(
                selected = isSelected,
                onClick = {},
                label = { Text(opt, fontSize = 12.sp) }
            )
        }
    }
}
