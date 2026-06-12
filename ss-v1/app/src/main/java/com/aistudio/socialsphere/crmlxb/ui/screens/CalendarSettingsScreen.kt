package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
                FilterChipsRow(
                    options  = listOf("Сегодня", "Список", "Неделя", "Месяц"),
                    selected = AppSettings.calendarDefaultMode.value,
                    onSelect = { AppSettings.calendarDefaultMode.value = it }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "С этим режимом календарь будет открываться при входе",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Первый день недели", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilterChipsRow(
                    options  = listOf("понедельник", "воскресенье"),
                    selected = if (AppSettings.calendarFirstDayMonday.value) "понедельник" else "воскресенье",
                    onSelect = { AppSettings.calendarFirstDayMonday.value = it == "понедельник" }
                )
                // «Формат времени» вернётся, когда события получат время суток
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
                Text(
                    "Выключенные типы скрываются в календаре и блоке «Ближайшее»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val typeRows = listOf(
                    "Дни рождения"      to CalendarItemType.BIRTHDAY,
                    "Годовщины"         to CalendarItemType.ANNIVERSARY,
                    "Важные даты"       to CalendarItemType.IMPORTANT_DATE,
                    "Встречи"           to CalendarItemType.MEETING,
                    "Звонки"            to CalendarItemType.CALL,
                    "Подарки"           to CalendarItemType.GIFT,
                    "Задачи"            to CalendarItemType.TASK,
                    "События компаний"  to CalendarItemType.COMPANY_EVENT
                )
                val hidden = AppSettings.calendarHiddenTypes.value
                typeRows.forEach { (name, type) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = type.name !in hidden,
                            onCheckedChange = { visible ->
                                AppSettings.calendarHiddenTypes.value =
                                    if (visible) hidden - type.name else hidden + type.name
                            }
                        )
                    }
                }
            }
        }
    }
}
