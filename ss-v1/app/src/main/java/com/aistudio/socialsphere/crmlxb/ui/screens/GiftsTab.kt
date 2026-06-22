@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.ui.components.TabEditBar
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 3 — ПОДАРКИ
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.giftsTab(
    contact: Contact,
    onNavigateToCalendarItem: (String) -> Unit,
    onAddGift: () -> Unit = {},
    onEditGift: (GiftIdea) -> Unit = {},
    onDeleteGift: (GiftIdea) -> Unit = {},
    onEditSizes: () -> Unit = {},
    onAddPref: () -> Unit = {},
    onDeletePref: (PersonalDetail) -> Unit = {}
, ctxLabel: android.content.Context) {
    // ── Важные даты вверху ───────────────────────────────────
    item {
        val today = java.time.LocalDate.now()
        val importantDates = AppStateStore.calendarItems.filter { item ->
            item.links.any { it.targetId == contact.id } &&
            item.type in listOf(
                CalendarItemType.BIRTHDAY,
                CalendarItemType.ANNIVERSARY,
                CalendarItemType.NAMEDAY,
                CalendarItemType.IMPORTANT_DATE
            )
        }
        if (importantDates.isNotEmpty()) {
            CardBlock(title = stringResource(R.string.cd_important_dates)) {
                importantDates.forEach { date ->
                    val daysUntil = try {
                        val d = java.time.LocalDate.parse(date.startDate)
                        // Переносим на текущий год; недавно прошедшие (≤30 дн.)
                        // оставляем в прошлом — покажется «N дн. назад»
                        val thisYear = d.withYear(today.year)
                        val next = if (thisYear.isBefore(today.minusDays(30))) thisYear.plusYears(1) else thisYear
                        java.time.temporal.ChronoUnit.DAYS.between(today, next)
                    } catch (e: Exception) { null }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToCalendarItem(date.id) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val emoji = when (date.type) {
                                CalendarItemType.BIRTHDAY    -> "🎂"
                                CalendarItemType.ANNIVERSARY -> "💍"
                                else                         -> "⭐"
                            }
                            Text(emoji, fontSize = 16.sp)
                            Text(
                                com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(date.title, date.type, androidx.compose.ui.platform.LocalContext.current),
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (daysUntil != null) {
                            // Явная не-nullable копия: K2 не делал smart-cast,
                            // и -daysUntil давал ambiguity unaryMinus
                            val du: Long = daysUntil
                            Text(
                                when {
                                    du == 0L -> stringResource(R.string.cd_today_party)
                                    du > 0L  -> String.format(stringResource(R.string.home_in_days), du)
                                    else     -> String.format(stringResource(R.string.home_days_ago), -du)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    du in 0L..7L  -> MaterialTheme.colorScheme.error
                                    du in 8L..30L -> MaterialTheme.colorScheme.tertiary
                                    else          -> MaterialTheme.colorScheme.secondary
                                },
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // ── Добавить важную дату / праздник с напоминанием ───────
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var showAddDate by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showAddDate = true },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.cd_add_important_date))
        }
        if (showAddDate) {
            var dType by remember { mutableStateOf(CalendarItemType.BIRTHDAY) }
            var dTitle by remember { mutableStateOf("") }
            var dDate by remember { mutableStateOf("") }
            var dRemind by remember { mutableStateOf(ReminderTime.ON_DAY) }
            val typeOptions = listOf(
                CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
                CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE,
                CalendarItemType.CUSTOM
            )
            val remindOptions = listOf(
                ReminderTime.NONE, ReminderTime.ON_DAY, ReminderTime.DAY_1, ReminderTime.WEEK_1
            )
            AlertDialog(
                onDismissRequest = { showAddDate = false },
                title = { Text(stringResource(R.string.cd_add_important_date), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.cd_date_type), style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            typeOptions.forEach { t ->
                                FilterChip(selected = dType == t, onClick = { dType = t },
                                    label = { Text(t.label(ctx)) })
                            }
                        }
                        OutlinedTextField(
                            value = dTitle, onValueChange = { dTitle = it }, keyboardOptions = CapSentences,
                            label = { Text(stringResource(R.string.cd_date_title_opt)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        DatePickerField(
                            value = dDate,
                            onValueChange = { dDate = it },
                            label = stringResource(R.string.cd_date_iso),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(stringResource(R.string.cd_date_reminder), style = MaterialTheme.typography.labelMedium)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            remindOptions.forEach { r ->
                                FilterChip(selected = dRemind == r, onClick = { dRemind = r },
                                    label = { Text(r.label(ctx)) })
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = dDate.isNotBlank(),
                        onClick = {
                            val itemId = java.util.UUID.randomUUID().toString()
                            val now = java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            val reminders = when (dRemind) {
                                ReminderTime.ON_DAY, ReminderTime.AT_EVENT ->
                                    listOf(ReminderRule(java.util.UUID.randomUUID().toString(), itemId, ReminderType.AT_TIME))
                                ReminderTime.DAY_1 ->
                                    listOf(ReminderRule(java.util.UUID.randomUUID().toString(), itemId, ReminderType.BEFORE, 1, ReminderOffsetUnit.DAYS))
                                ReminderTime.WEEK_1 ->
                                    listOf(ReminderRule(java.util.UUID.randomUUID().toString(), itemId, ReminderType.BEFORE, 1, ReminderOffsetUnit.WEEKS))
                                else -> emptyList()
                            }
                            val item = CalendarItem(
                                id = itemId,
                                title = dTitle.ifBlank { dType.label(ctx) },
                                type = dType,
                                startDate = dDate.trim(),
                                isAllDay = true,
                                status = CalendarItemStatus.ACTIVE,
                                importance = if (dType == CalendarItemType.BIRTHDAY) ImportanceLevel.KEY else ImportanceLevel.NORMAL,
                                recurrenceRule = if (dType == CalendarItemType.BIRTHDAY ||
                                    dType == CalendarItemType.ANNIVERSARY || dType == CalendarItemType.NAMEDAY)
                                    RecurrenceMode.YEARLY.toRRule() else null,
                                links = listOf(CalendarItemLink(
                                    java.util.UUID.randomUUID().toString(), itemId,
                                    CalendarTargetType.CONTACT, contact.id)),
                                reminders = reminders,
                                createdAt = now, updatedAt = now
                            )
                            AppStateStore.addCalendarItem(item)
                            com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
                                .rescheduleReminders(ctx, emptyList(), item)
                            showAddDate = false
                        }
                    ) { Text(stringResource(R.string.common_add)) }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDate = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }
    }

    // ── Подарки с 3 статусами ────────────────────────────────
    item {
        val gifts = AppStateStore.gifts.filter { it.contactId == contact.id }
        val ideas  = gifts.filter { it.status == GiftStatus.IDEA }
        val bought = gifts.filter { it.status == GiftStatus.BOUGHT }
        val given  = gifts.filter { it.status == GiftStatus.GIVEN }

        // Идеи
        TextButton(onClick = onAddGift, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.cd_gift_idea))
        }
        CardBlock(title = stringResource(R.string.cd_gift_ideas)) {
            if (ideas.isEmpty()) {
                Text(
                    stringResource(R.string.cd_gift_ideas_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                ideas.forEach { gift ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("✨", fontSize = 14.sp)
                            Column {
                                Text(
                                    gift.title,
                                    style      = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (!gift.note.isNullOrBlank())
                                    Text(
                                        gift.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                if (!gift.link.isNullOrBlank())
                                    Text(
                                        gift.link,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                            }
                        }
                        // Кнопка перехода в статус BOUGHT
                        TextButton(
                            onClick = { AppStateStore.updateGift(gift.copy(status = GiftStatus.BOUGHT)) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.cd_gift_buy), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        GiftMenu(onEdit = { onEditGift(gift) }, onDelete = { onDeleteGift(gift) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }

        // Куплено
        if (bought.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardBlock(title = stringResource(R.string.cd_gift_bought)) {
                bought.forEach { gift ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            gift.title,
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier   = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { AppStateStore.updateGift(gift.copy(status = GiftStatus.GIVEN)) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.cd_gift_give), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        GiftMenu(onEdit = { onEditGift(gift) }, onDelete = { onDeleteGift(gift) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }

        // Подарено
        if (given.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardBlock(title = stringResource(R.string.cd_gift_given_before)) {
                given.forEach { gift ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("• ${gift.title}", style = MaterialTheme.typography.bodyMedium)
                        if (!gift.date.isNullOrBlank())
                            Text(
                                gift.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                    }
                }
            }
        }
    }

    // ── Предпочтения ─────────────────────────────────────────
    item {
        val size = AppStateStore.sizeInfos.find { it.contactId == contact.id }
        val prefCats = listOf(
            PersonalDetailCategory.FOOD, PersonalDetailCategory.DRINKS,
            PersonalDetailCategory.LIKES, PersonalDetailCategory.DISLIKES,
            PersonalDetailCategory.ALLERGIES, PersonalDetailCategory.RESTRICTIONS
        )
        val prefs = contact.personalDetails.filter { it.category in prefCats }

        // Блок виден всегда: раньше при пустых данных добавить размеры
        // и предпочтения из вкладки было негде в принципе
        run {
            CardBlock(title = stringResource(R.string.cd_prefs_sizes)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEditSizes, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Edit, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_sizes), fontSize = 12.sp)
                    }
                    TextButton(onClick = onAddPref, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_preference), fontSize = 12.sp)
                    }
                }
                if (size != null) {
                    Text(
                        stringResource(R.string.cd_sizes),
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!size.clothingSize.isNullOrBlank()) SizeChip(stringResource(R.string.cd_clothes), size.clothingSize)
                        if (!size.shoeSize.isNullOrBlank())    SizeChip(stringResource(R.string.cd_shoes),   size.shoeSize)
                        if (!size.ringSize.isNullOrBlank())    SizeChip(stringResource(R.string.cd_ring),  size.ringSize)
                        if (!size.other.isNullOrBlank())       SizeChip(stringResource(R.string.common_other),  size.other)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (prefs.isNotEmpty()) {
                    prefs.groupBy { it.category }.forEach { (cat, items) ->
                        Text(
                            cat.label(ctxLabel),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = if (cat == PersonalDetailCategory.ALLERGIES || cat == PersonalDetailCategory.RESTRICTIONS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        items.forEach { pref ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("• ${pref.value}", style = MaterialTheme.typography.bodySmall, color = if (cat == PersonalDetailCategory.ALLERGIES || cat == PersonalDetailCategory.RESTRICTIONS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onDeletePref(pref) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, stringResource(R.string.common_delete),
                                        Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                } else if (size == null) {
                    Text(
                        stringResource(R.string.cd_hint_sizes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
