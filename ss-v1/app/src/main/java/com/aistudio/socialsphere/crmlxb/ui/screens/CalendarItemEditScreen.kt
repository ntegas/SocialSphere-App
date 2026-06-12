@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarItemEditScreen(
    calendarItemId: String?,
    onNavigateBack: () -> Unit,
    prefillContactId: String? = null
) {
    val isEditMode = calendarItemId != null
    val originalItem = remember { calendarItemId?.let { AppStateStore.calendarItems.find { item -> item.id == it } } }

    var title by remember { mutableStateOf(originalItem?.title ?: "") }
    var description by remember { mutableStateOf(originalItem?.description ?: "") }
    var type by remember { mutableStateOf(originalItem?.type ?: CalendarItemType.MEETING) }
    var importance by remember { mutableStateOf(originalItem?.importance ?: ImportanceLevel.NORMAL) }
    var status by remember { mutableStateOf(originalItem?.status ?: CalendarItemStatus.ACTIVE) }

    var startDate by remember { mutableStateOf(originalItem?.startDate ?: "") }
    var startTime by remember { mutableStateOf(originalItem?.startTime ?: "") }
    var endDate by remember { mutableStateOf(originalItem?.endDate ?: "") }
    var endTime by remember { mutableStateOf(originalItem?.endTime ?: "") }
    var isAllDay by remember { mutableStateOf(originalItem?.isAllDay ?: false) }

    var recurrenceRule by remember { mutableStateOf(originalItem?.recurrenceRule ?: "Не повторять") }
    var reminders by remember { mutableStateOf(originalItem?.reminders ?: emptyList()) }

    var linkedContact by remember {
        mutableStateOf<Contact?>(
            originalItem?.links?.firstOrNull { it.targetType == CalendarTargetType.CONTACT }
                ?.let { AppStateStore.getContact(it.targetId) }
                // ТЗ: «Создать событие» из карточки — контакт предзаполнен
                ?: prefillContactId?.let { AppStateStore.getContact(it) }
        )
    }
    var linkedCompany by remember {
        mutableStateOf<Company?>(
            originalItem?.links?.firstOrNull { it.targetType == CalendarTargetType.COMPANY }
                ?.let { AppStateStore.getCompany(it.targetId) }
        )
    }

    var showContactDropdown by remember { mutableStateOf(false) }
    var showCompanyDropdown by remember { mutableStateOf(false) }
    var showTypeDropdown by remember { mutableStateOf(false) }
    var showRecurrenceDropdown by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    var selectedReminders by remember(reminders) {
        val initialSelection = mutableSetOf<String>()
        if (reminders.isEmpty()) {
            initialSelection.add("без уведомления")
        } else {
            reminders.forEach { r ->
                when {
                    r.reminderType == ReminderType.AT_TIME -> initialSelection.add("в момент события")
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 10 && r.offsetUnit == ReminderOffsetUnit.MINUTES -> initialSelection.add("за 10 минут")
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.HOURS -> initialSelection.add("за 1 час")
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.DAYS -> initialSelection.add("за 1 день")
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.WEEKS -> initialSelection.add("за неделю")
                }
            }
        }
        if (initialSelection.isEmpty() && isEditMode) initialSelection.add("в момент события") // Default fallback for edited if empty
        else if (initialSelection.isEmpty()) initialSelection.add("без уведомления")
        mutableStateOf(initialSelection.toSet())
    }

    LaunchedEffect(originalItem) {
        if (originalItem != null) {
            val contactLink = originalItem.links.find { it.targetType == CalendarTargetType.CONTACT }
            if (contactLink != null) {
                linkedContact = AppStateStore.getContact(contactLink.targetId)
            }
            val companyLink = originalItem.links.find { it.targetType == CalendarTargetType.COMPANY }
            if (companyLink != null) {
                linkedCompany = AppStateStore.getCompany(companyLink.targetId)
            }
            if (originalItem.recurrenceRule == null) {
                recurrenceRule = "Не повторять"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Редактирование" else "Новое событие", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val links = mutableListOf<CalendarItemLink>()
                            linkedContact?.let { links.add(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = "", targetType = CalendarTargetType.CONTACT, targetId = it.id)) }
                            linkedCompany?.let { links.add(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = "", targetType = CalendarTargetType.COMPANY, targetId = it.id)) }
                            
                            val itemId = originalItem?.id ?: java.util.UUID.randomUUID().toString()
                            
                            val newReminderRules = mutableListOf<ReminderRule>()
                            if (!selectedReminders.contains("без уведомления")) {
                                selectedReminders.forEach { opt ->
                                    val rule = when (opt) {
                                        "в момент события" -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
                                        "за 10 минут" -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 10, offsetUnit = ReminderOffsetUnit.MINUTES)
                                        "за 1 час" -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.HOURS)
                                        "за 1 день" -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.DAYS)
                                        "за неделю" -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.WEEKS)
                                        else -> null
                                    }
                                    if (rule != null) newReminderRules.add(rule)
                                }
                            }

                            val recurrenceStr = if (recurrenceRule == "Не повторять") null else if (recurrenceRule == "Каждый год") "FREQ=YEARLY" else recurrenceRule

                            val newItem = CalendarItem(
                                id = itemId,
                                title = title,
                                description = description.takeIf { it.isNotBlank() },
                                type = type,
                                startDate = startDate,
                                startTime = startTime.takeIf { it.isNotBlank() },
                                endDate = endDate.takeIf { it.isNotBlank() },
                                endTime = endTime.takeIf { it.isNotBlank() },
                                isAllDay = isAllDay,
                                importance = importance,
                                status = status,
                                links = links,
                                recurrenceRule = recurrenceStr,
                                reminders = newReminderRules,
                                createdAt = originalItem?.createdAt ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            )
                            if (isEditMode) {
                                AppStateStore.updateCalendarItem(newItem)
                            } else {
                                AppStateStore.addCalendarItem(newItem)
                            }
                            
                            val context = context
                            com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler.rescheduleReminders(context, originalItem?.reminders ?: emptyList(), newItem)

                            onNavigateBack()
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Сохранить")
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
            // Main info
            SectionCard("Основное") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                // Type Dropdown
                Box {
                    OutlinedTextField(
                        value = type.label(),
                        onValueChange = {},
                        label = { Text("Тип события") },
                        modifier = Modifier.fillMaxWidth().clickable { showTypeDropdown = true },
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    DropdownMenu(expanded = showTypeDropdown, onDismissRequest = { showTypeDropdown = false }) {
                        CalendarItemType.values().forEach { t ->
                            DropdownMenuItem(text = { Text(t.label()) }, onClick = {
                                type = t
                                if (t == CalendarItemType.BIRTHDAY) {
                                    isAllDay = true
                                    recurrenceRule = "Каждый год"
                                }
                                showTypeDropdown = false
                            })
                        }
                    }
                }

                Text("Важность", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportanceLevel.values().forEach { imp ->
                        FilterChip(
                            selected = importance == imp,
                            onClick = { importance = imp },
                            label = { Text(imp.label(), fontSize = 12.sp) }
                        )
                    }
                }
                
                if (isEditMode) {
                    Text("Статус", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalendarItemStatus.values().forEach { stat ->
                            FilterChip(
                                selected = status == stat,
                                onClick = { status = stat },
                                label = { Text(stat.label(), fontSize = 12.sp) }
                            )
                        }
                    }
                }
                
                // Блок «Цвет (метка)» удалён: цвет события определяется его типом
                // автоматически (см. CalendarScreen), ручной выбор не предусмотрен моделью.
            }

            // Linked To
            SectionCard("Связано с") {
                // Contact
                Box {
                    OutlinedTextField(
                        value = linkedContact?.let { "${it.firstName} ${it.lastName}" } ?: "",
                        onValueChange = {},
                        label = { Text("Контакт") },
                        modifier = Modifier.fillMaxWidth().clickable { showContactDropdown = true },
                        enabled = false,
                        trailingIcon = {
                            if (linkedContact != null) {
                                IconButton(onClick = { linkedContact = null }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Очистить")
                                }
                            } else {
                                Icon(Icons.Default.PersonSearch, contentDescription = null)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    DropdownMenu(expanded = showContactDropdown, onDismissRequest = { showContactDropdown = false }) {
                        AppStateStore.contacts.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.firstName} ${c.lastName}") },
                                onClick = { linkedContact = c; showContactDropdown = false }
                            )
                        }
                    }
                }

                // Company
                Box {
                    OutlinedTextField(
                        value = linkedCompany?.name ?: "",
                        onValueChange = {},
                        label = { Text("Компания") },
                        modifier = Modifier.fillMaxWidth().clickable { showCompanyDropdown = true },
                        enabled = false,
                        trailingIcon = {
                            if (linkedCompany != null) {
                                IconButton(onClick = { linkedCompany = null }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Очистить")
                                }
                            } else {
                                Icon(Icons.Default.Business, contentDescription = null)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    DropdownMenu(expanded = showCompanyDropdown, onDismissRequest = { showCompanyDropdown = false }) {
                        AppStateStore.companies.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { linkedCompany = c; showCompanyDropdown = false }
                            )
                        }
                    }
                }
            }

            // Date and time
            SectionCard("Дата и время") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAllDay, onCheckedChange = { isAllDay = it })
                    Text("Весь день")
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = startDate, onValueChange = { startDate = it },
                        label = { Text("Дата начала") }, modifier = Modifier.weight(1f)
                    )
                    if (!isAllDay) {
                        OutlinedTextField(
                            value = startTime, onValueChange = { startTime = it },
                            label = { Text("Время") }, modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = endDate, onValueChange = { endDate = it },
                        label = { Text("Дата окончания (опц)") }, modifier = Modifier.weight(1f)
                    )
                    if (!isAllDay) {
                        OutlinedTextField(
                            value = endTime, onValueChange = { endTime = it },
                            label = { Text("Время (опц)") }, modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Reminders
            SectionCard("Напоминания") {
                Text("Уведомления (визуально)", style = MaterialTheme.typography.labelMedium)
                val options = listOf("без уведомления", "в момент события", "за 10 минут", "за 1 час", "за 1 день", "за неделю")
                
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = selectedReminders.contains(opt),
                            onClick = { 
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && opt != "без уведомления" && !selectedReminders.contains(opt)) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                val newReminders = selectedReminders.toMutableSet()
                                if (opt == "без уведомления") {
                                    newReminders.clear()
                                    newReminders.add("без уведомления")
                                } else {
                                    newReminders.remove("без уведомления")
                                    if (newReminders.contains(opt)) newReminders.remove(opt) else newReminders.add(opt)
                                    if (newReminders.isEmpty()) newReminders.add("без уведомления")
                                }
                                selectedReminders = newReminders
                            },
                            label = { Text(opt, fontSize = 12.sp) }
                        )
                    }
                }
            }

            // Recurrence
            SectionCard("Повтор и отображение") {
                Box {
                    OutlinedTextField(
                        value = recurrenceRule,
                        onValueChange = {},
                        label = { Text("Повтор") },
                        modifier = Modifier.fillMaxWidth().clickable { showRecurrenceDropdown = true },
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    DropdownMenu(expanded = showRecurrenceDropdown, onDismissRequest = { showRecurrenceDropdown = false }) {
                        listOf("Не повторять", "Каждый день", "Каждую неделю", "Каждый месяц", "Каждый год").forEach { opt ->
                            DropdownMenuItem(text = { Text(opt) }, onClick = {
                                recurrenceRule = opt
                                showRecurrenceDropdown = false
                            })
                        }
                    }
                }
                
                // Свитчи «Показывать на главной / в карточке» удалены:
                // в модели CalendarItem нет таких полей — контролы ничего не делали
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
