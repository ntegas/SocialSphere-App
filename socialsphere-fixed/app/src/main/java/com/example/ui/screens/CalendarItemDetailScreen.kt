package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppStateStore
import com.example.model.*
import com.example.utils.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarItemDetailScreen(
    calendarItemId: String,
    onNavigateBack: () -> Unit,
    onNavigateToContact: (String) -> Unit,
    onNavigateToCompany: (String) -> Unit,
    onNavigateToEdit: () -> Unit
) {
    val event = AppStateStore.calendarItems.find { it.id == calendarItemId }
    if (event == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Событие не найдено")
        }
        return
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Удалить событие?", fontWeight = FontWeight.Bold) },
            text  = { Text("«${event.title}» будет удалено без возможности восстановления.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; AppStateStore.deleteCalendarItem(calendarItemId); onNavigateBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(event.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Редактировать")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                EventHeader(event)
            }
            
            val links = event.links
            if (links.isNotEmpty()) {
                item {
                    Text("Связано с", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    links.forEach { link ->
                        when (link.targetType) {
                            CalendarTargetType.CONTACT -> {
                                val contact = AppStateStore.getContact(link.targetId)
                                if (contact != null) {
                                    RelatedContactCard(contact, onClick = { onNavigateToContact(contact.id) })
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                            CalendarTargetType.COMPANY -> {
                                val company = AppStateStore.getCompany(link.targetId)
                                if (company != null) {
                                    RelatedCompanyCard(company, onClick = { onNavigateToCompany(company.id) })
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                            CalendarTargetType.GIFT -> {
                                val gift = AppStateStore.gifts.find { it.id == link.targetId }
                                if (gift != null) {
                                    CardBlock(title = "Подарок") {
                                        Text(gift.title, fontWeight = FontWeight.Bold)
                                        gift.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                            CalendarTargetType.NOTE -> {
                                val note = AppStateStore.notes.find { it.id == link.targetId }
                                if (note != null) {
                                     CardBlock(title = "Заметка") {
                                        Text(note.text, style = MaterialTheme.typography.bodyMedium)
                                     }
                                     Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            event.description?.let { desc ->
                item {
                    CardBlock(title = "Описание") {
                        Text(desc, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                CardBlock(title = "Напоминания") {
                    if (event.reminders.isNotEmpty()) {
                        event.reminders.forEach { reminder ->
                            val text = when (reminder.reminderType) {
                                ReminderType.AT_TIME -> "В момент события"
                                ReminderType.BEFORE -> "За ${reminder.offsetValue} ${reminder.offsetUnit?.name ?: ""}"
                                ReminderType.CUSTOM_DATE_TIME -> "В ${reminder.exactDateTime}"
                                ReminderType.NONE -> "Нет"
                            }
                            // Crude status indication for visual purposes
                            val isPastApprox = event.startDate.compareTo(java.time.LocalDate.now().toString()) < 0 && event.recurrenceRule?.contains("YEARLY") != true
                            val statusStr = if (isPastApprox) " (Не запланировано, прошло)" else " (Запланировано)"
                            Text("• $text$statusStr", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text("Нет напоминаний", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            item {
                CardBlock(title = "Повтор") {
                    Text(event.recurrenceRule ?: "Без повтора", style = MaterialTheme.typography.bodyMedium)
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {}, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Text("Выполнено")
                    }
                    Button(onClick = {}, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text("Перенести")
                    }
                }
            }
        }
    }
}

@Composable
fun EventHeader(event: CalendarItem) {
    val iconInfo = when (event.type) {
        CalendarItemType.BIRTHDAY -> Pair(Icons.Default.Cake, MaterialTheme.colorScheme.primaryContainer)
        CalendarItemType.CALL -> Pair(Icons.Default.Phone, MaterialTheme.colorScheme.secondaryContainer)
        CalendarItemType.MEETING -> Pair(Icons.Default.Group, MaterialTheme.colorScheme.tertiaryContainer)
        CalendarItemType.GIFT -> Pair(Icons.Default.CardGiftcard, MaterialTheme.colorScheme.primaryContainer)
        else -> Pair(Icons.Default.Event, MaterialTheme.colorScheme.surfaceVariant)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(iconInfo.second),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconInfo.first, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = event.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text = event.startDate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }

        if (!event.startTime.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(text = "${event.startTime}" + (if (!event.endTime.isNullOrEmpty()) " - ${event.endTime}" else ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(event.type.label(), fontSize = 12.sp) })
            AssistChip(onClick = {}, label = { Text(event.status.label(), fontSize = 12.sp) })
            if (event.importance in listOf(ImportanceLevel.IMPORTANT, ImportanceLevel.KEY)) {
                 AssistChip(
                     onClick = {},
                     label = { Text(event.importance.label(), fontSize = 12.sp) },
                     leadingIcon = { Box(modifier = Modifier.clip(CircleShape).size(8.dp).background(MaterialTheme.colorScheme.error)) }
                 )
            }
        }
    }
}

@Composable
fun RelatedContactCard(contact: Contact, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.firstName.take(1) + contact.lastName.take(1),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${contact.firstName} ${contact.lastName}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = contact.relationshipType.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
fun RelatedCompanyCard(company: Company, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val addresses = AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Business, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = company.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = company.industry.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (addresses.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    val address = addresses.firstOrNull { it.addressType == AddressType.OFFICE } ?: addresses.firstOrNull()
                    if (address != null) {
                        if (address.latitude != null && address.longitude != null) {
                            com.example.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                        } else {
                            com.example.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
                        }
                    }
                }, modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Icon(Icons.Default.Directions, contentDescription = "Маршрут", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
