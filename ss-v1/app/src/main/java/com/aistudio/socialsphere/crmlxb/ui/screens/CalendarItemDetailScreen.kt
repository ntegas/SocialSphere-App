package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.model.ReminderOffsetUnit

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
            Text(stringResource(R.string.cid_not_found))
        }
        return
    }

    val ctxLabel = LocalContext.current
    var showDeleteDialog   by remember { mutableStateOf(false) }
    var showPostponeDialog by remember { mutableStateOf(false) }
    var postponeDate       by remember { mutableStateOf("") }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.cid_delete_event_q), fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.cid_delete_warning, event.title)) },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; AppStateStore.deleteCalendarItem(calendarItemId); onNavigateBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // ── Postpone dialog ───────────────────────────────────────
    if (showPostponeDialog) {
        AlertDialog(
            onDismissRequest = { showPostponeDialog = false; postponeDate = "" },
            title = { Text(stringResource(R.string.cid_reschedule), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.cid_current_date, event.startDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    OutlinedTextField(
                        value       = postponeDate,
                        onValueChange = { v ->
                            // Allow only digits and dashes, max length yyyy-MM-dd = 10
                            val filtered = v.filter { it.isDigit() || it == '-' }.take(10)
                            postponeDate = filtered
                        },
                        label       = { Text(stringResource(R.string.cid_new_date)) },
                        placeholder = {
                            // Suggest next week as hint
                            val next = try {
                                java.time.LocalDate.parse(event.startDate)
                                    .plusWeeks(1).toString()
                            } catch (e: Exception) {
                                java.time.LocalDate.now().plusWeeks(1).toString()
                            }
                            Text(next, color = MaterialTheme.colorScheme.outlineVariant)
                        },
                        modifier    = Modifier.fillMaxWidth(),
                        singleLine  = true,
                        isError     = postponeDate.isNotBlank() && !isValidDate(postponeDate),
                        supportingText = {
                            if (postponeDate.isNotBlank() && !isValidDate(postponeDate))
                                Text(stringResource(R.string.cid_date_format),
                                    color = MaterialTheme.colorScheme.error)
                        }
                    )
                    // Quick-pick buttons
                    Text(stringResource(R.string.cid_quick_pick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(stringResource(R.string.cid_plus_1week) to 7L, stringResource(R.string.cid_plus_2weeks) to 14L, stringResource(R.string.cid_plus_1month) to 30L)
                            .forEach { (label, days) ->
                                OutlinedButton(
                                    onClick = {
                                        postponeDate = try {
                                            java.time.LocalDate.parse(event.startDate)
                                                .plusDays(days).toString()
                                        } catch (e: Exception) {
                                            java.time.LocalDate.now().plusDays(days).toString()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                            }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val now = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        AppStateStore.updateCalendarItem(
                            event.copy(
                                startDate = postponeDate,
                                status    = CalendarItemStatus.POSTPONED,
                                updatedAt = now
                            )
                        )
                        showPostponeDialog = false
                        postponeDate = ""
                    },
                    enabled = isValidDate(postponeDate)
                ) { Text(stringResource(R.string.cid_reschedule_short)) }
            },
            dismissButton = {
                TextButton(onClick = { showPostponeDialog = false; postponeDate = "" }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(event.title, event.type, ctxLabel), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cid_edit))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
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
                    Text(stringResource(R.string.cid_linked_with), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                    CardBlock(title = stringResource(R.string.evt_gift)) {
                                        Text(gift.title, fontWeight = FontWeight.Bold)
                                        gift.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                            CalendarTargetType.NOTE -> {
                                val note = AppStateStore.notes.find { it.id == link.targetId }
                                if (note != null) {
                                     CardBlock(title = stringResource(R.string.evt_note)) {
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
                    CardBlock(title = stringResource(R.string.cid_description)) {
                        Text(desc, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                CardBlock(title = stringResource(R.string.cid_reminders)) {
                    if (event.reminders.isNotEmpty()) {
                        event.reminders.forEach { reminder ->
                            val unitStr = when (reminder.offsetUnit) {
                                ReminderOffsetUnit.MINUTES -> stringResource(R.string.unit_minutes)
                                ReminderOffsetUnit.HOURS   -> stringResource(R.string.unit_hours)
                                ReminderOffsetUnit.DAYS    -> stringResource(R.string.unit_days)
                                ReminderOffsetUnit.WEEKS   -> stringResource(R.string.unit_weeks)
                                null -> ""
                            }
                            val text = when (reminder.reminderType) {
                                ReminderType.AT_TIME -> stringResource(R.string.cid_at_event)
                                ReminderType.BEFORE -> stringResource(R.string.cid_before, reminder.offsetValue.toString(), unitStr)
                                ReminderType.CUSTOM_DATE_TIME -> stringResource(R.string.cid_at_exact, reminder.exactDateTime ?: "")
                                ReminderType.NONE -> stringResource(R.string.cid_status_no)
                            }
                            // Crude status indication for visual purposes
                            val isPastApprox = event.startDate.compareTo(java.time.LocalDate.now().toString()) < 0 && event.recurrenceRule?.contains("YEARLY") != true
                            val statusStr = if (isPastApprox) stringResource(R.string.cid_status_past) else stringResource(R.string.cid_status_scheduled)
                            Text("• $text$statusStr", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(stringResource(R.string.cid_no_reminders), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            item {
                CardBlock(title = stringResource(R.string.cid_recurrence)) {
                    // Показываем человекочитаемый русский лейбл, а не сырой RRULE
                    // («FREQ=YEARLY» выглядело как английское «frequency»).
                    val recurText = when (RecurrenceMode.fromRule(event.recurrenceRule)) {
                        RecurrenceMode.NONE    -> stringResource(R.string.cid_no_recurrence)
                        RecurrenceMode.DAILY   -> stringResource(R.string.rec_daily)
                        RecurrenceMode.WEEKLY  -> stringResource(R.string.rec_weekly)
                        RecurrenceMode.MONTHLY -> stringResource(R.string.rec_monthly)
                        RecurrenceMode.YEARLY  -> stringResource(R.string.rec_yearly)
                    }
                    Text(recurText, style = MaterialTheme.typography.bodyMedium)
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // ── Выполнено (скрыто для дат: ДР/годовщина/важная дата) ──
                    val isDateType = event.type == CalendarItemType.BIRTHDAY ||
                        event.type == CalendarItemType.ANNIVERSARY ||
                        event.type == CalendarItemType.NAMEDAY ||
                        event.type == CalendarItemType.IMPORTANT_DATE
                    if (!isDateType) Button(
                        onClick = {
                            AppStateStore.markCalendarItemCompleted(event)
                            onNavigateBack()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = event.status != CalendarItemStatus.COMPLETED,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor   = MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (event.status == CalendarItemStatus.COMPLETED)
                                stringResource(R.string.cid_done_check) else stringResource(R.string.cid_done)
                        )
                    }
                    // ── Перенести ──────────────────────────────
                    Button(
                        onClick = { showPostponeDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor   = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cid_reschedule_short))
                    }
                }
            }
        }
    }
}

@Composable
fun EventHeader(event: CalendarItem) {
    val ctxLabel = LocalContext.current
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
        Text(text = com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(event.title, event.type, ctxLabel), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        
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
            AssistChip(onClick = {}, label = { Text(event.type.label(ctxLabel), fontSize = 12.sp) })
            AssistChip(onClick = {}, label = { Text(event.status.label(ctxLabel), fontSize = 12.sp) })
            if (event.importance in listOf(ImportanceLevel.IMPORTANT, ImportanceLevel.KEY)) {
                 AssistChip(
                     onClick = {},
                     label = { Text(event.importance.label(ctxLabel), fontSize = 12.sp) },
                     leadingIcon = { Box(modifier = Modifier.clip(CircleShape).size(8.dp).background(MaterialTheme.colorScheme.error)) }
                 )
            }
        }
    }
}

@Composable
fun RelatedContactCard(contact: Contact, onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
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
                Text(text = contact.relationshipType.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
fun RelatedCompanyCard(company: Company, onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
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
                Text(text = company.industry.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (addresses.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    val address = addresses.firstOrNull { it.addressType == AddressType.OFFICE } ?: addresses.firstOrNull()
                    if (address != null) {
                        if (address.latitude != null && address.longitude != null) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                        } else {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
                        }
                    }
                }, modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Icon(Icons.Default.Directions, contentDescription = stringResource(R.string.cid_route), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ─── Date validation helper ───────────────────────────────────
private fun isValidDate(date: String): Boolean {
    if (date.length != 10) return false
    return try {
        java.time.LocalDate.parse(date)
        true
    } catch (e: Exception) {
        false
    }
}
