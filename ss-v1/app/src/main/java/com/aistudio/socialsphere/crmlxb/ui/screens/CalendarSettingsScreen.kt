package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarSettingsScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Календарь", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            CardBlock("Отображение календаря") {
                Text("Режим по умолчанию", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilterChipsRow(listOf("Сегодня", "Список", "Неделя", "Месяц"), "Список")

                Spacer(modifier = Modifier.height(16.dp))
                Text("Первый день недели", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilterChipsRow(listOf("понедельник", "воскресенье"), "понедельник")

                Spacer(modifier = Modifier.height(16.dp))
                Text("Формат времени", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilterChipsRow(listOf("24 часа", "12 часов"), "24 часа")
            }

            CardBlock("Цвета типов событий") {
                val colorMap = mapOf(
                    "день рождения" to MaterialTheme.colorScheme.primaryContainer,
                    "встреча" to MaterialTheme.colorScheme.tertiaryContainer,
                    "звонок" to MaterialTheme.colorScheme.secondaryContainer,
                    "подарок" to MaterialTheme.colorScheme.primaryContainer,
                    "задача" to MaterialTheme.colorScheme.surfaceVariant,
                    "заметка" to MaterialTheme.colorScheme.surfaceVariant,
                    "событие компании" to MaterialTheme.colorScheme.secondaryContainer
                )
                colorMap.forEach { (name, color) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(color))
                    }
                }
            }

            CardBlock("Видимость типов событий") {
                listOf(
                    "дни рождения", "встречи", "звонки", "подарки", "задачи", "заметки", "события компаний"
                ).forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = true, onCheckedChange = {})
                    }
                }
            }
        }
    }
}
