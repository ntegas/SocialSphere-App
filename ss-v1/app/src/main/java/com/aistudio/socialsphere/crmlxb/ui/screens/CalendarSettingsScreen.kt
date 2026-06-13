package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
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
                title = { Text(stringResource(R.string.settings_calendar), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
<<<<<<< HEAD
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
=======
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
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
<<<<<<< HEAD
            CardBlock(stringResource(R.string.calset_display)) {
                Text(stringResource(R.string.calset_default_mode), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilterChipsRow(
                    // calendarDefaultMode хранит enum CalendarViewMode;
                    // title() (из CalendarScreen.kt, тот же пакет) даёт локализованный лейбл
                    options  = CalendarViewMode.entries.map { it.title() },
                    selected = AppSettings.calendarDefaultMode.value.title(),
                    onSelect = { label ->
                        CalendarViewMode.entries.find { it.title() == label }
                            ?.let { AppSettings.calendarDefaultMode.value = it }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.calset_mode_hint),
=======
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
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(16.dp))
<<<<<<< HEAD
                Text(stringResource(R.string.calset_first_day), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                val monLabel = stringResource(R.string.calset_monday)
                val sunLabel = stringResource(R.string.calset_sunday)
                FilterChipsRow(
                    options  = listOf(monLabel, sunLabel),
                    selected = if (AppSettings.calendarFirstDayMonday.value) monLabel else sunLabel,
                    onSelect = { AppSettings.calendarFirstDayMonday.value = it == monLabel }
=======
                Text("Первый день недели", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilterChipsRow(
                    options  = listOf("понедельник", "воскресенье"),
                    selected = if (AppSettings.calendarFirstDayMonday.value) "понедельник" else "воскресенье",
                    onSelect = { AppSettings.calendarFirstDayMonday.value = it == "понедельник" }
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                )
                // «Формат времени» вернётся, когда события получат время суток
            }

            CardBlock(stringResource(R.string.calset_colors)) {
                val colorMap = mapOf(
                    stringResource(R.string.evt_birthday) to MaterialTheme.colorScheme.primaryContainer,
                    stringResource(R.string.evt_meeting) to MaterialTheme.colorScheme.tertiaryContainer,
                    stringResource(R.string.evt_call) to MaterialTheme.colorScheme.secondaryContainer,
                    stringResource(R.string.evt_gift) to MaterialTheme.colorScheme.primaryContainer,
                    stringResource(R.string.evt_task) to MaterialTheme.colorScheme.surfaceVariant,
                    stringResource(R.string.evt_note) to MaterialTheme.colorScheme.surfaceVariant,
                    stringResource(R.string.evt_company) to MaterialTheme.colorScheme.secondaryContainer
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

<<<<<<< HEAD
            CardBlock(stringResource(R.string.calset_visibility)) {
                Text(
                    stringResource(R.string.calset_visibility_hint),
=======
            CardBlock("Видимость типов событий") {
                Text(
                    "Выключенные типы скрываются в календаре и блоке «Ближайшее»",
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val typeRows = listOf(
<<<<<<< HEAD
                    stringResource(R.string.evt_birthdays)       to CalendarItemType.BIRTHDAY,
                    stringResource(R.string.evt_anniversary)     to CalendarItemType.ANNIVERSARY,
                    stringResource(R.string.evt_important_date)  to CalendarItemType.IMPORTANT_DATE,
                    stringResource(R.string.evt_meetings)        to CalendarItemType.MEETING,
                    stringResource(R.string.evt_calls)           to CalendarItemType.CALL,
                    stringResource(R.string.evt_gifts)           to CalendarItemType.GIFT,
                    stringResource(R.string.evt_tasks)           to CalendarItemType.TASK,
                    stringResource(R.string.evt_company_events)  to CalendarItemType.COMPANY_EVENT
=======
                    "Дни рождения"      to CalendarItemType.BIRTHDAY,
                    "Годовщины"         to CalendarItemType.ANNIVERSARY,
                    "Важные даты"       to CalendarItemType.IMPORTANT_DATE,
                    "Встречи"           to CalendarItemType.MEETING,
                    "Звонки"            to CalendarItemType.CALL,
                    "Подарки"           to CalendarItemType.GIFT,
                    "Задачи"            to CalendarItemType.TASK,
                    "События компаний"  to CalendarItemType.COMPANY_EVENT
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
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
