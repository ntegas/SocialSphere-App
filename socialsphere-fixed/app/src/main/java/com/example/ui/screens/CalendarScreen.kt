package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppStateStore
import com.example.model.*
import com.example.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToCalendarItem: (String) -> Unit,
    onNavigateToCreateCalendarItem: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedMode by remember { mutableStateOf("Список") }
    var selectedFilter by remember { mutableStateOf("Все") }

    val modes = listOf("Сегодня", "Список", "Неделя", "Месяц")
    val filters = listOf("Все", "Дни рождения", "Звонки", "Встречи", "Подарки", "Важное")

    val allEvents = AppStateStore.calendarItems

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "Календарь", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) },
                actions = { IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Настройки") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreateCalendarItem,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, "Добавить событие")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val modeScrollState = rememberScrollState()
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(modeScrollState).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                modes.forEach { mode ->
                    val isSelected = selectedMode == mode
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { selectedMode = mode }.padding(vertical = 8.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = mode, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            val filterScrollState = rememberScrollState()
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(filterScrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) },
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val filteredEvents = allEvents.filter { event ->
                when (selectedFilter) {
                    "Дни рождения" -> event.type == CalendarItemType.BIRTHDAY
                    "Звонки" -> event.type == CalendarItemType.CALL
                    "Встречи" -> event.type == CalendarItemType.MEETING
                    "Подарки" -> event.type == CalendarItemType.GIFT
                    "Важное" -> event.importance in listOf(ImportanceLevel.IMPORTANT, ImportanceLevel.KEY)
                    else -> true
                }
            }
            
            val todayDate    = java.time.LocalDate.now().toString()
            val tomorrowDate = java.time.LocalDate.now().plusDays(1).toString()
            val weekEnd      = java.time.LocalDate.now().plusDays(7).toString()

            val groupedEvents = mutableMapOf<String, List<CalendarItem>>()
            if (selectedMode == "Сегодня") {
                val todayEvents = filteredEvents.filter { it.startDate == todayDate }
                if (todayEvents.isNotEmpty()) {
                    groupedEvents["Сегодня"] = todayEvents
                } else {
                    groupedEvents["Ближайшие"] = filteredEvents.sortedBy { it.startDate }.take(5)
                }
            } else if (selectedMode == "Список") {
                val today    = filteredEvents.filter { it.startDate == todayDate }
                val tomorrow = filteredEvents.filter { it.startDate == tomorrowDate }
                val later    = filteredEvents.filter { it.startDate > tomorrowDate }.sortedBy { it.startDate }
                if (today.isNotEmpty())    groupedEvents["Сегодня"] = today
                if (tomorrow.isNotEmpty()) groupedEvents["Завтра"]  = tomorrow
                if (later.isNotEmpty())    groupedEvents["Позже"]   = later
            } else if (selectedMode == "Неделя") {
                val weekEvents = filteredEvents.filter { it.startDate in todayDate..weekEnd }.sortedBy { it.startDate }
                if (weekEvents.isNotEmpty()) groupedEvents["Ближайшие 7 дней"] = weekEvents
                else groupedEvents["Ближайшие 7 дней"] = emptyList()
            } else if (selectedMode == "Месяц") {
                val monthEnd = java.time.LocalDate.now().plusMonths(1).toString()
                val monthEvents = filteredEvents.filter { it.startDate in todayDate..monthEnd }.sortedBy { it.startDate }
                groupedEvents["События месяца"] = monthEvents
            }

            if (groupedEvents.isEmpty() || groupedEvents.all { it.value.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text("События не найдены", color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    groupedEvents.forEach { (header, items) ->
                        item {
                            Text(text = header, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        items(items) { event ->
                            CalendarEventItem(event = event, onClick = { onNavigateToCalendarItem(event.id) })
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarEventItem(event: CalendarItem, onClick: () -> Unit) {
    val iconInfo = when (event.type) {
        CalendarItemType.BIRTHDAY -> Pair(Icons.Default.Cake, MaterialTheme.colorScheme.primaryContainer)
        CalendarItemType.CALL -> Pair(Icons.Default.Phone, MaterialTheme.colorScheme.secondaryContainer)
        CalendarItemType.MEETING -> Pair(Icons.Default.Group, MaterialTheme.colorScheme.tertiaryContainer)
        CalendarItemType.GIFT -> Pair(Icons.Default.CardGiftcard, MaterialTheme.colorScheme.primaryContainer)
        else -> Pair(Icons.Default.Event, MaterialTheme.colorScheme.surfaceVariant)
    }
    
    val relatedText = event.links.mapNotNull { link ->
        when (link.targetType) {
            CalendarTargetType.CONTACT -> AppStateStore.getContact(link.targetId)?.let { "${it.firstName} ${it.lastName}" }
            CalendarTargetType.COMPANY -> AppStateStore.getCompany(link.targetId)?.name
            else -> null
        }
    }.joinToString(", ")

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(iconInfo.second),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = iconInfo.first, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = event.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(text = event.startDate, style = MaterialTheme.typography.labelSmall, color = if (event.type == CalendarItemType.BIRTHDAY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(4.dp))
                
                if (relatedText.isNotEmpty()) {
                    Text(text = relatedText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                }
                
                if (!event.startTime.isNullOrEmpty()) {
                     Text(text = "Время: ${event.startTime}" + (if (!event.endTime.isNullOrEmpty()) " - ${event.endTime}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                     Spacer(modifier = Modifier.height(2.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(event.type.label(), fontSize = 10.sp) })
                    if (event.importance in listOf(ImportanceLevel.IMPORTANT, ImportanceLevel.KEY)) {
                        Box(modifier = Modifier.clip(CircleShape).size(12.dp).background(MaterialTheme.colorScheme.error))
                    }
                    if (event.status != CalendarItemStatus.ACTIVE) {
                           AssistChip(onClick = {}, label = { Text(event.status.label() , fontSize = 10.sp) })
                    }
                }
            }
        }
    }
}

