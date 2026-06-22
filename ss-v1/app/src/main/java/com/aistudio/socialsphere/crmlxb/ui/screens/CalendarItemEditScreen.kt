@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.ui.components.TimePickerField
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarItemEditScreen(
    calendarItemId: String?,
    onNavigateBack: () -> Unit,
    prefillContactId: String? = null
) {
    val isEditMode = calendarItemId != null
    val originalItem = remember { calendarItemId?.let { AppStateStore.calendarItems.find { item -> item.id == it } } }

    val ctxLabel = LocalContext.current
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

    var recurrenceMode by remember { mutableStateOf(RecurrenceMode.fromRule(originalItem?.recurrenceRule)) }
    var reminders by remember { mutableStateOf(originalItem?.reminders ?: emptyList()) }

    var linkedContacts by remember {
        mutableStateOf<List<Contact>>(
            buildList {
                originalItem?.links
                    ?.filter { it.targetType == CalendarTargetType.CONTACT }
                    ?.forEach { l -> AppStateStore.getContact(l.targetId)?.let { add(it) } }
                if (originalItem == null) prefillContactId?.let { id ->
                    AppStateStore.getContact(id)?.let { add(it) }
                }
            }.distinctBy { it.id }
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
        val initialSelection = mutableSetOf<ReminderTime>()
        if (reminders.isEmpty()) {
            initialSelection.add(ReminderTime.NONE)
        } else {
            reminders.forEach { r ->
                when {
                    r.reminderType == ReminderType.AT_TIME -> initialSelection.add(ReminderTime.AT_EVENT)
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 10 && r.offsetUnit == ReminderOffsetUnit.MINUTES -> initialSelection.add(ReminderTime.MIN_10)
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.HOURS -> initialSelection.add(ReminderTime.HOUR_1)
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.DAYS -> initialSelection.add(ReminderTime.DAY_1)
                    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.WEEKS -> initialSelection.add(ReminderTime.WEEK_1)
                }
            }
        }
        if (initialSelection.isEmpty() && isEditMode) initialSelection.add(ReminderTime.AT_EVENT)
        else if (initialSelection.isEmpty()) initialSelection.add(ReminderTime.NONE)
        mutableStateOf(initialSelection.toSet())
    }

    LaunchedEffect(originalItem) {
        if (originalItem != null) {
            linkedContacts = originalItem.links
                .filter { it.targetType == CalendarTargetType.CONTACT }
                .mapNotNull { AppStateStore.getContact(it.targetId) }
                .distinctBy { it.id }
            val companyLink = originalItem.links.find { it.targetType == CalendarTargetType.COMPANY }
            if (companyLink != null) {
                linkedCompany = AppStateStore.getCompany(companyLink.targetId)
            }
            // recurrenceMode уже инициализирован через RecurrenceMode.fromRule
        }
    }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) stringResource(R.string.cie_edit) else stringResource(R.string.cie_new_event), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val links = mutableListOf<CalendarItemLink>()
                            linkedContacts.forEach { c -> links.add(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = "", targetType = CalendarTargetType.CONTACT, targetId = c.id)) }
                            linkedCompany?.let { links.add(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = "", targetType = CalendarTargetType.COMPANY, targetId = it.id)) }
                            
                            val itemId = originalItem?.id ?: java.util.UUID.randomUUID().toString()
                            
                            val newReminderRules = mutableListOf<ReminderRule>()
                            if (!selectedReminders.contains(ReminderTime.NONE)) {
                                selectedReminders.forEach { opt ->
                                    val rule = when (opt) {
                                        ReminderTime.AT_EVENT -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
                                        ReminderTime.MIN_10 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 10, offsetUnit = ReminderOffsetUnit.MINUTES)
                                        ReminderTime.HOUR_1 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.HOURS)
                                        ReminderTime.DAY_1 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.DAYS)
                                        ReminderTime.WEEK_1 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.WEEKS)
                                        else -> null
                                    }
                                    if (rule != null) newReminderRules.add(rule)
                                }
                            }

                            val recurrenceStr = recurrenceMode.toRRule()

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
                        Text(stringResource(R.string.common_save))
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Main info
            SectionCard(stringResource(R.string.cie_basic)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cie_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cie_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                // Type Dropdown
                Box {
                    OutlinedTextField(
                        value = type.label(ctxLabel),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.cie_event_type)) },
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
                            DropdownMenuItem(text = { Text(t.label(ctxLabel)) }, onClick = {
                                type = t
                                if (t == CalendarItemType.BIRTHDAY) {
                                    isAllDay = true
                                    recurrenceMode = RecurrenceMode.YEARLY
                                }
                                showTypeDropdown = false
                            })
                        }
                    }
                }

                Text(stringResource(R.string.cie_importance), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportanceLevel.values().forEach { imp ->
                        FilterChip(
                            selected = importance == imp,
                            onClick = { importance = imp },
                            label = { Text(imp.label(ctxLabel), fontSize = 12.sp) }
                        )
                    }
                }
                
                if (isEditMode) {
                    Text(stringResource(R.string.cie_status), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalendarItemStatus.values().forEach { stat ->
                            FilterChip(
                                selected = status == stat,
                                onClick = { status = stat },
                                label = { Text(stat.label(ctxLabel), fontSize = 12.sp) }
                            )
                        }
                    }
                }
                
                // Блок «Цвет (метка)» удалён: цвет события определяется его типом
                // автоматически (см. CalendarScreen), ручной выбор не предусмотрен моделью.
            }

            // Linked To
            SectionCard(stringResource(R.string.cie_linked_with)) {
                // Контакты — можно несколько
                Text(
                    stringResource(R.string.cie_contact),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (linkedContacts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        linkedContacts.forEach { c ->
                            InputChip(
                                selected = true,
                                onClick = { linkedContacts = linkedContacts.filter { it.id != c.id } },
                                label = { Text("${c.firstName} ${c.lastName}".trim()) },
                                trailingIcon = {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cie_clear), modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Box {
                    OutlinedButton(
                        onClick = { showContactDropdown = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cie_add_person))
                    }
                    DropdownMenu(expanded = showContactDropdown, onDismissRequest = { showContactDropdown = false }) {
                        AppStateStore.contacts
                            .filter { c -> linkedContacts.none { it.id == c.id } }
                            .forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.firstName} ${c.lastName}".trim()) },
                                    onClick = {
                                        linkedContacts = linkedContacts + c
                                        showContactDropdown = false
                                    }
                                )
                            }
                    }
                }

                // Company
                Box {
                    OutlinedTextField(
                        value = linkedCompany?.name ?: "",
                        onValueChange = {},
                        label = { Text(stringResource(R.string.cie_company)) },
                        modifier = Modifier.fillMaxWidth().clickable { showCompanyDropdown = true },
                        enabled = false,
                        trailingIcon = {
                            if (linkedCompany != null) {
                                IconButton(onClick = { linkedCompany = null }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cie_clear))
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
            SectionCard(stringResource(R.string.cie_datetime)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAllDay, onCheckedChange = { isAllDay = it })
                    Text(stringResource(R.string.cie_all_day))
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DatePickerField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = stringResource(R.string.cie_start_date),
                        modifier = Modifier.weight(1f)
                    )
                    if (!isAllDay) {
                        TimePickerField(
                            value = startTime,
                            onValueChange = { startTime = it },
                            label = stringResource(R.string.cie_time),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DatePickerField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = stringResource(R.string.cie_end_date),
                        modifier = Modifier.weight(1f)
                    )
                    if (!isAllDay) {
                        TimePickerField(
                            value = endTime,
                            onValueChange = { endTime = it },
                            label = stringResource(R.string.cie_time_opt),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Reminders
            SectionCard(stringResource(R.string.cie_reminders)) {
                Text(stringResource(R.string.cie_notifications_visual), style = MaterialTheme.typography.labelMedium)
                val options = listOf(ReminderTime.NONE, ReminderTime.AT_EVENT, ReminderTime.MIN_10, ReminderTime.HOUR_1, ReminderTime.DAY_1, ReminderTime.WEEK_1)
                
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = selectedReminders.contains(opt),
                            onClick = { 
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && opt != ReminderTime.NONE && !selectedReminders.contains(opt)) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                val newReminders = selectedReminders.toMutableSet()
                                if (opt == ReminderTime.NONE) {
                                    newReminders.clear()
                                    newReminders.add(ReminderTime.NONE)
                                } else {
                                    newReminders.remove(ReminderTime.NONE)
                                    if (newReminders.contains(opt)) newReminders.remove(opt) else newReminders.add(opt)
                                    if (newReminders.isEmpty()) newReminders.add(ReminderTime.NONE)
                                }
                                selectedReminders = newReminders
                            },
                            label = { Text(opt.label(ctxLabel), fontSize = 12.sp) }
                        )
                    }
                }
            }

            // Recurrence
            SectionCard(stringResource(R.string.cie_recur_display)) {
                Box {
                    val recurLabel = when (recurrenceMode) {
                        RecurrenceMode.NONE -> stringResource(R.string.rec_none)
                        RecurrenceMode.DAILY -> stringResource(R.string.rec_daily)
                        RecurrenceMode.WEEKLY -> stringResource(R.string.rec_weekly)
                        RecurrenceMode.MONTHLY -> stringResource(R.string.rec_monthly)
                        RecurrenceMode.YEARLY -> stringResource(R.string.rec_yearly)
                    }
                    OutlinedTextField(
                        value = recurLabel,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.cie_recurrence)) },
                        modifier = Modifier.fillMaxWidth().clickable { showRecurrenceDropdown = true },
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    DropdownMenu(expanded = showRecurrenceDropdown, onDismissRequest = { showRecurrenceDropdown = false }) {
                        RecurrenceMode.entries.forEach { mode ->
                            val lbl = when (mode) {
                                RecurrenceMode.NONE -> stringResource(R.string.rec_none)
                                RecurrenceMode.DAILY -> stringResource(R.string.rec_daily)
                                RecurrenceMode.WEEKLY -> stringResource(R.string.rec_weekly)
                                RecurrenceMode.MONTHLY -> stringResource(R.string.rec_monthly)
                                RecurrenceMode.YEARLY -> stringResource(R.string.rec_yearly)
                            }
                            DropdownMenuItem(text = { Text(lbl) }, onClick = {
                                recurrenceMode = mode
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
