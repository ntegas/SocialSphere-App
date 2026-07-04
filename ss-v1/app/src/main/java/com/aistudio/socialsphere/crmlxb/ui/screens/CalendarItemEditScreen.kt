@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
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
    var showRecurrenceDropdown by remember { mutableStateOf(false) }
    var contactQuery by remember { mutableStateOf("") }
    var showImportanceMenu by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    var showReminderSheet by remember { mutableStateOf(false) }
    
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
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Шапка: Отмена · заголовок · Готово (по макету) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.clickable { onNavigateBack() }
                )
                Text(
                    if (isEditMode) stringResource(R.string.cie_edit) else stringResource(R.string.cie_new_event),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label
                )
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
                                // colorKey в форме сейчас не редактируется — сохраняем как
                                // было, а не хардкодим null (см. У60)
                                colorKey = originalItem?.colorKey,
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
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold)
                }
            }
            // ── Основное: название · тип · важность ──
            SectionCard(stringResource(R.string.cie_basic)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    BareFieldColumn(
                        label = stringResource(R.string.cie_title), value = title,
                        onValueChange = { title = it }, keyboardOptions = CapSentences,
                        placeholder = type.label(ctxLabel),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                // Тип — пилюли (как в макете), вместо листа
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AureliaCaption(stringResource(R.string.cie_event_type))
                    PillChoiceRow(
                        options = CalendarItemType.values().map { it.label(ctxLabel) },
                        selected = type.label(ctxLabel),
                        onSelect = { v ->
                            val picked = CalendarItemType.values().firstOrNull { it.label(ctxLabel) == v } ?: return@PillChoiceRow
                            type = picked
                            if (picked == CalendarItemType.BIRTHDAY) { isAllDay = true; recurrenceMode = RecurrenceMode.YEARLY }
                        }
                    )
                }
                // Важность — компактная строка
                Box {
                    EventListRow(
                        label = stringResource(R.string.cie_importance),
                        value = importance.label(ctxLabel),
                        onClick = { showImportanceMenu = true }
                    )
                    DropdownMenu(expanded = showImportanceMenu, onDismissRequest = { showImportanceMenu = false }) {
                        ImportanceLevel.values().forEach { imp ->
                            DropdownMenuItem(text = { Text(imp.label(ctxLabel)) }, onClick = { importance = imp; showImportanceMenu = false })
                        }
                    }
                }
                if (isEditMode) {
                    Box {
                        EventListRow(
                            label = stringResource(R.string.cie_status),
                            value = status.label(ctxLabel),
                            onClick = { showStatusMenu = true }
                        )
                        DropdownMenu(expanded = showStatusMenu, onDismissRequest = { showStatusMenu = false }) {
                            CalendarItemStatus.values().forEach { stat ->
                                DropdownMenuItem(text = { Text(stat.label(ctxLabel)) }, onClick = { status = stat; showStatusMenu = false })
                            }
                        }
                    }
                }
            }

            // ── Когда: весь день · начало · конец · повтор ──
            SectionCard(stringResource(R.string.cie_datetime)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cie_all_day), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isAllDay, onCheckedChange = { isAllDay = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DatePickerField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = stringResource(R.string.cie_start_date),
                        modifier = Modifier.weight(1f),
                        // Год может быть неизвестен для дат-годовщин — «--MM-DD»
                        allowNoYear = type in listOf(
                            CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
                            CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE
                        )
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                // Повтор — компактная строка
                Box {
                    val recurLabel = when (recurrenceMode) {
                        RecurrenceMode.NONE -> stringResource(R.string.rec_none)
                        RecurrenceMode.DAILY -> stringResource(R.string.rec_daily)
                        RecurrenceMode.WEEKLY -> stringResource(R.string.rec_weekly)
                        RecurrenceMode.MONTHLY -> stringResource(R.string.rec_monthly)
                        RecurrenceMode.YEARLY -> stringResource(R.string.rec_yearly)
                    }
                    EventListRow(
                        label = stringResource(R.string.cie_recurrence),
                        value = recurLabel,
                        leadingIcon = Icons.Default.Autorenew,
                        onClick = { showRecurrenceDropdown = true }
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
                            DropdownMenuItem(text = { Text(lbl) }, onClick = { recurrenceMode = mode; showRecurrenceDropdown = false })
                        }
                    }
                }
            }

            // ── С кем: человек · компания ──
            SectionCard(stringResource(R.string.cie_linked_with)) {
                if (linkedContacts.isNotEmpty()) {
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
                }
                Box {
                    EventListRow(
                        label = stringResource(R.string.cie_add_person),
                        value = "",
                        leadingIcon = Icons.Default.PersonAdd,
                        onClick = { showContactDropdown = true }
                    )
                    DropdownMenu(
                        expanded = showContactDropdown,
                        onDismissRequest = { showContactDropdown = false; contactQuery = "" },
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        OutlinedTextField(
                            value = contactQuery,
                            onValueChange = { contactQuery = it },
                            placeholder = { Text(stringResource(R.string.ce_search_contact)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        AppStateStore.contacts
                            .filter { c -> linkedContacts.none { it.id == c.id } }
                            .filter { c -> contactQuery.isBlank() || "${c.firstName} ${c.lastName}".contains(contactQuery, ignoreCase = true) }
                            .take(30)
                            .forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.firstName} ${c.lastName}".trim()) },
                                    onClick = {
                                        linkedContacts = linkedContacts + c
                                        showContactDropdown = false; contactQuery = ""
                                    }
                                )
                            }
                    }
                }
                Box {
                    EventListRow(
                        label = stringResource(R.string.cie_company),
                        value = linkedCompany?.name ?: "—",
                        onClick = { showCompanyDropdown = true },
                        trailing = {
                            if (linkedCompany != null) {
                                IconButton(onClick = { linkedCompany = null }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cie_clear), modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = showCompanyDropdown,
                        onDismissRequest = { showCompanyDropdown = false },
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        AppStateStore.companies.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = { linkedCompany = c; showCompanyDropdown = false })
                        }
                    }
                }
            }

            // ── Детали: напоминание · заметка ──
            SectionCard(stringResource(R.string.cie_reminders)) {
                // Сводка выбранных напоминаний (можно несколько) — одной строкой,
                // тап открывает лист с галочками. Раньше чипы выглядели как
                // «выбери одно», хотя выбор множественный.
                val reminderSummary = run {
                    val active = selectedReminders.filter { it != ReminderTime.NONE }
                    if (active.isEmpty()) ReminderTime.NONE.label(ctxLabel)
                    else active.joinToString(", ") { it.label(ctxLabel) }
                }
                EventListRow(
                    label = stringResource(R.string.cie_reminders),
                    value = reminderSummary,
                    onClick = { showReminderSheet = true }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cie_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            if (showReminderSheet) {
                ReminderPickerSheet(
                    selected = selectedReminders,
                    onToggle = { opt ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            opt != ReminderTime.NONE && !selectedReminders.contains(opt)) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        val next = selectedReminders.toMutableSet()
                        if (opt == ReminderTime.NONE) {
                            next.clear(); next.add(ReminderTime.NONE)
                        } else {
                            next.remove(ReminderTime.NONE)
                            if (next.contains(opt)) next.remove(opt) else next.add(opt)
                            if (next.isEmpty()) next.add(ReminderTime.NONE)
                        }
                        selectedReminders = next
                    },
                    onDismiss = { showReminderSheet = false }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Apple-style: цвет типа события + компактная строка списка + пикер типа ──

// Палитра типов событий Aurelia (терракот/тил/малахит/золото) вместо iOS-цветов.
internal fun eventTypeColor(t: CalendarItemType): Color = when (t) {
    CalendarItemType.BIRTHDAY -> Color(0xFFB68A36) // золото (точно по макету)
    CalendarItemType.CALL     -> Color(0xFF5E8C66) // сейдж (точно по макету)
    CalendarItemType.MEETING  -> Color(0xFF1C6B4C) // малахит/акцент (по макету var(--ac))
    CalendarItemType.GIFT     -> Color(0xFFC45D34) // терракот
    else                      -> Color(0xFF1C6B4C)
}

/**
 * Компактная строка формы в стиле Apple: слева подпись (+опц. точка/иконка),
 * справа значение серым и шеврон/кастомный trailing. Вся строка кликабельна.
 */
@Composable
private fun EventListRow(
    label: String,
    value: String,
    leadingDot: Color? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingDot != null) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(leadingDot))
            Spacer(Modifier.width(10.dp))
        }
        if (leadingIcon != null) {
            Icon(leadingIcon, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTheme.colors.secondaryLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        if (trailing != null) trailing()
        else Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
    }
}

/**
 * Лист выбора напоминаний — МНОЖЕСТВЕННЫЙ выбор с галочками и явной подписью
 * «можно несколько». «Без напоминания» взаимоисключающее с остальными.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPickerSheet(
    selected: Set<ReminderTime>,
    onToggle: (ReminderTime) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val options = listOf(
        ReminderTime.NONE, ReminderTime.AT_EVENT, ReminderTime.MIN_10,
        ReminderTime.HOUR_1, ReminderTime.DAY_1, ReminderTime.WEEK_1
    )
    ModalBottomSheet(onDismissRequest = onDismiss, shape = SocialShape.Sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.cie_reminders), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.cie_reminder_multi),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.secondaryLabel
                )
            }
            options.forEach { opt ->
                val checked = selected.contains(opt)
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(opt) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(opt.label(ctx), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (checked) Icon(Icons.Default.Check, null, tint = AppleTheme.colors.brand)
                }
            }
        }
    }
}
