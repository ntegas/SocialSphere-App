package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppStateStore
import com.example.model.*
import com.example.utils.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactDetailScreen(
    contactId: String,
    onNavigateBack: () -> Unit,
    onNavigateToCalendarItem: (String) -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToCreateCalendarItem: () -> Unit
) {
    val contact = AppStateStore.getContact(contactId)
    if (contact == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Контакт не найден")
        }
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Обзор", "Связи", "Важное", "История")

    var showAddDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Add note state
    var noteText by remember { mutableStateOf("") }
    var noteType by remember { mutableStateOf(NoteType.GENERAL) }
    var noteIsImportant by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Удалить контакт?", fontWeight = FontWeight.Bold) },
            text  = { Text("«${contact.firstName} ${contact.lastName}» будет удалён без возможности восстановления.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        AppStateStore.deleteContact(contactId)
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(
                    "${contact.firstName} ${contact.lastName}".trim(),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                ) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Header
            ContactHeader(contact)

            Spacer(modifier = Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Tab Content
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTab) {
                    0 -> overviewTab(contact, onNavigateToCreateCalendarItem)
                    1 -> connectionsTab(contact)
                    2 -> importantTab(
                        contact = contact,
                        onNavigateToCalendarItem = onNavigateToCalendarItem,
                        onShowAdd = { showAddDialog = true },
                        onShowVoice = { showVoiceDialog = true }
                    )
                    3 -> historyTab(contact)
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; noteText = "" },
            title = { Text("Добавить заметку", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Текст заметки") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        maxLines = 5
                    )
                    DropdownField("Тип заметки", noteType.label(), NoteType.values().map { it.label() }) {
                        noteType = NoteType.values().firstOrNull { n -> n.label() == it } ?: NoteType.GENERAL
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = noteIsImportant, onCheckedChange = { noteIsImportant = it })
                        Text("Важная заметка", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteText.isNotBlank()) {
                            val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            AppStateStore.addNote(
                                Note(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contactId = contactId,
                                    companyId = null, calendarItemId = null, giftId = null,
                                    type = noteType,
                                    text = noteText.trim(),
                                    date = java.time.LocalDate.now().toString(),
                                    isImportant = noteIsImportant,
                                    createdAt = now, updatedAt = now
                                )
                            )
                            noteText = ""; noteIsImportant = false; showAddDialog = false
                        }
                    },
                    enabled = noteText.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false; noteText = "" }) { Text("Отмена") } }
        )
    }

    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Голосовое добавление") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "«Мария любит японскую кухню, размер обуви 38, день рождения 12 марта.»",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Divider()
                    Text("Распознано:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("любит: японская кухня", fontSize = 10.sp) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("размер обуви: 38", fontSize = 10.sp) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("дата: 12 марта", fontSize = 10.sp) })
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showVoiceDialog = false }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoiceDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun ContactHeader(contact: Contact) {
    val compRel = contact.companyRelations.firstOrNull { it.isPrimary } ?: contact.companyRelations.firstOrNull()
    val company = compRel?.companyId?.let { AppStateStore.getCompany(it) }?.name ?: ""
    val position = compRel?.position ?: ""
    val address = AppStateStore.addresses.find { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
    val city = address?.city ?: ""
    val name = "${contact.firstName} ${contact.lastName}".trim()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.firstName.take(1) + contact.lastName.take(1),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (company.isNotEmpty() || position.isNotEmpty()) {
            Text(text = listOf(company, position).filter { it.isNotEmpty() }.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        if (city.isNotEmpty()) {
            Text(text = city, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(contact.relationshipType.label(), fontSize = 10.sp) })
            AssistChip(onClick = {}, label = { Text(contact.connectionLevel.label(), fontSize = 10.sp) })
            AssistChip(onClick = {}, label = { Text(contact.importanceLevel.label(), fontSize = 10.sp) })
            if (contact.socialRole != SocialRole.REGULAR) {
                AssistChip(onClick = {}, label = { Text(contact.socialRole.label(), fontSize = 10.sp) })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QuickActionIcon(Icons.Outlined.Phone, "Позвонить") {
                val phone = contact.phones.find { it.isPrimary }?.number ?: contact.phones.firstOrNull()?.number
                com.example.utils.ExternalActionHandler.openDialer(context, phone)
            }
            QuickActionIcon(Icons.Outlined.ChatBubbleOutline, "Сообщение") {
                val phone = contact.phones.find { it.isPrimary }?.number ?: contact.phones.firstOrNull()?.number
                com.example.utils.ExternalActionHandler.openSms(context, phone)
            }
            QuickActionIcon(Icons.Outlined.Email, "Email") {
                val email = contact.emails.find { it.isPrimary }?.email ?: contact.emails.firstOrNull()?.email
                com.example.utils.ExternalActionHandler.openEmail(context, email)
            }
            if (address != null) QuickActionIcon(Icons.Outlined.Map, "Карта") {
                if (address.latitude != null && address.longitude != null) {
                    com.example.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                } else {
                    com.example.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
                }
            }
        }
    }
}

@Composable
fun QuickActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = cd, tint = MaterialTheme.colorScheme.primary)
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.overviewTab(contact: Contact, onNavigateToCreateCalendarItem: () -> Unit) {
    item {
        CardBlock(title = "Рабочий контекст") {
            val compRel = contact.companyRelations.firstOrNull { it.isPrimary } ?: contact.companyRelations.firstOrNull()
            if (compRel != null) {
                val company = AppStateStore.getCompany(compRel.companyId)?.name ?: ""
                InfoRow("Компания", company)
                InfoRow("Должность", compRel.position ?: "")
                if (!compRel.department.isNullOrBlank()) InfoRow("Отдел", compRel.department)
                if (!compRel.responsibilities.isNullOrBlank()) InfoRow("Задачи", compRel.responsibilities)
                if (!compRel.workNote.isNullOrBlank()) InfoRow("Заметка", compRel.workNote)
            } else {
                Text("Нет данных о работе", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item {
        CardBlock(title = "Важно помнить") {
            val impNotes = AppStateStore.notes.filter { it.contactId == contact.id && it.type == NoteType.IMPORTANT_TO_REMEMBER }
            if (impNotes.isNotEmpty()) {
                impNotes.forEach { note ->
                    Text("• ${note.text}", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text("Пока нет важных заметок", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            AssistChip(onClick = {}, label = { Text("Шпаргалка") }, leadingIcon = { Icon(Icons.Outlined.Lightbulb, null, Modifier.size(16.dp)) })
        }
    }
    item {
        CardBlock(title = "Ближайшее") {
            val upcoming = AppStateStore.calendarItems.filter { it.links.any { link -> link.targetId == contact.id } && it.status == CalendarItemStatus.ACTIVE }
            if (upcoming.isNotEmpty()) {
                upcoming.forEach { item ->
                    InfoRow(item.startDate, item.title)
                }
            } else {
                Text("Нет запланированных событий", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNavigateToCreateCalendarItem,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Добавить дату/событие")
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.connectionsTab(contact: Contact) {
    item {
        Text("Компании", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        contact.companyRelations.forEach { rel ->
            val company = AppStateStore.getCompany(rel.companyId)
            CardBlock {
                Text(company?.name ?: "", fontWeight = FontWeight.Bold)
                Text(rel.position ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text("Связанные контакты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        val relations = AppStateStore.contactRelations.filter { it.firstContactId == contact.id || it.secondContactId == contact.id }
        if (relations.isEmpty()) {
             Text("Нет связанных контактов", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        } else {
            relations.forEach { rel ->
                val (otherId, role) = if (rel.firstContactId == contact.id) Pair(rel.secondContactId, rel.secondRole) else Pair(rel.firstContactId, rel.firstRole)
                val otherContact = AppStateStore.getContact(otherId)
                if (otherContact != null) {
                    CardBlock {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text("${otherContact.firstName} ${otherContact.lastName}", fontWeight = FontWeight.Bold)
                                Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.Outlined.Person, null)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.importantTab(
    contact: Contact,
    onNavigateToCalendarItem: (String) -> Unit,
    onShowAdd: () -> Unit,
    onShowVoice: () -> Unit
) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Button(onClick = onShowAdd, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить")
            }
            Button(onClick = onShowVoice, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Голосом")
            }
        }
    }

    item {
        val validDateTypes = listOf(CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY, CalendarItemType.IMPORTANT_DATE, CalendarItemType.CUSTOM, CalendarItemType.NOTE)
        val dates = AppStateStore.calendarItems.filter { item ->
            item.links.any { link -> link.targetId == contact.id } && item.type in validDateTypes
        }
        
        CardBlock(title = "Даты") {
            if (dates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    dates.forEach { date ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onNavigateToCalendarItem(date.id) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isBirthday = date.type == CalendarItemType.BIRTHDAY
                            val iconTint = if (isBirthday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            val icon = if (isBirthday) Icons.Default.Cake else Icons.Outlined.Event
                            
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(date.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isBirthday) FontWeight.Bold else FontWeight.Medium)
                                    if (date.importance in listOf(ImportanceLevel.IMPORTANT, ImportanceLevel.KEY)) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                    }
                                }
                                Text(date.startDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (date.recurrenceRule != null) {
                                    Icon(Icons.Outlined.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                                }
                                if (date.reminders.isNotEmpty()) {
                                    Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет дат", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val gifts = AppStateStore.gifts.filter { it.contactId == contact.id }
        CardBlock(title = "Подарки") {
            if (gifts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    gifts.forEach { gift ->
                        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(gift.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (gift.link != null) Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                    if (gift.reminderId != null) Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                AssistChip(onClick = {}, label = { Text(gift.status.label(), fontSize = 10.sp) })
                                if (gift.date != null) {
                                    Text(gift.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            if (gift.note != null) {
                                Text(gift.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет подарков", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val size = AppStateStore.sizeInfos.find { it.contactId == contact.id }
        CardBlock(title = "Размеры") {
            if (size != null && (size.clothingSize != null || size.shoeSize != null || size.ringSize != null)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (size.clothingSize != null) SizeChip("Одежда", size.clothingSize)
                    if (size.shoeSize != null) SizeChip("Обувь", size.shoeSize)
                    if (size.ringSize != null) SizeChip("Кольцо", size.ringSize)
                }
            } else {
                Text("Пока нет размеров", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val pd = AppStateStore.personalDetails.filter { it.contactId == contact.id }
        CardBlock(title = "Личные детали") {
            if (pd.isNotEmpty()) {
                val grouped = pd.groupBy { it.category }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    grouped.forEach { (category, details) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(category.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            details.forEach { detail ->
                                Column {
                                    Text("• ${detail.value}", style = MaterialTheme.typography.bodyMedium)
                                    if (detail.note != null) {
                                        Text(detail.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет личных деталей", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val notes = AppStateStore.notes.filter { it.contactId == contact.id && it.type in listOf(NoteType.IMPORTANT_TO_REMEMBER, NoteType.PERSONAL_DETAIL, NoteType.GIFT, NoteType.DATE_EVENT) }
        CardBlock(title = "Заметки") {
            if (notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    notes.forEach { note ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(note.type.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (note.isImportant) {
                                        Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                    if (note.date != null) {
                                        Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
                                    }
                                    if (note.calendarItemId != null) {
                                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(note.text, style = MaterialTheme.typography.bodyMedium)
                            if (note.date != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(note.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет заметок", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.historyTab(contact: Contact) {
    item {
        var hasHistory = false
        val pastEvents = AppStateStore.calendarItems.filter { it.links.any { link -> link.targetId == contact.id } && it.status == CalendarItemStatus.COMPLETED }
        if (pastEvents.isNotEmpty()) {
            hasHistory = true
            CardBlock(title = "Прошедшие события") {
                pastEvents.forEach { InfoRow(it.startDate, it.title) }
            }
        }
        
        val givenGifts = AppStateStore.gifts.filter { it.contactId == contact.id && it.status == GiftStatus.GIVEN }
        if (givenGifts.isNotEmpty()) {
            hasHistory = true
            CardBlock(title = "Подарено") {
                givenGifts.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        
        if (!hasHistory) {
             Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                 Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                     Icon(Icons.Outlined.History, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                     Text("История пуста", color = MaterialTheme.colorScheme.secondary)
                 }
             }
        }
    }
}

@Composable
fun CardBlock(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).let { 
        if (onClick != null) it.clickable { onClick() } else it 
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SizeChip(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}
