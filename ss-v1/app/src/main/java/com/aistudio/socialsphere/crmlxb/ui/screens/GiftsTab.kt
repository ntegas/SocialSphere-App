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
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
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
                        val d = parseFlexibleDate(date.startDate) ?: error("bad date")
                        // Переносим на текущий год; недавно прошедшие (≤30 дн.)
                        // оставляем в прошлом — покажется «N дн. назад»
                        val thisYear = d.withYear(today.year)
                        val next = if (thisYear.isBefore(today.minusDays(30))) thisYear.plusYears(1) else thisYear
                        java.time.temporal.ChronoUnit.DAYS.between(today, next)
                    } catch (e: Exception) { null }

                    // «Через N дней» — отдельной строкой ПОД названием (фидбэк владельца:
                    // сбоку метка сжималась длинным заголовком и выглядела сбитой).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToCalendarItem(date.id) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        val emoji = when (date.type) {
                            CalendarItemType.BIRTHDAY    -> "🎂"
                            CalendarItemType.ANNIVERSARY -> "💍"
                            else                         -> "⭐"
                        }
                        Text(emoji, fontSize = 16.sp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(date.title, date.type, androidx.compose.ui.platform.LocalContext.current),
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
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
                                        du in 0L..7L  -> AppleTheme.colors.red
                                        du in 8L..30L -> AppleTheme.colors.orange
                                        else          -> AppleTheme.colors.secondaryLabel
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
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
                            modifier = Modifier.fillMaxWidth(),
                            allowNoYear = true // ДР/годовщина без известного года — «--MM-DD»
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
                    color = AppleTheme.colors.secondaryLabel
                )
            } else {
                ideas.forEachIndexed { i, gift ->
                    GiftRow(
                        gift = gift,
                        actionLabel = stringResource(R.string.cd_gift_buy),
                        onAction = { AppStateStore.updateGift(gift.copy(status = GiftStatus.BOUGHT)) },
                        onEdit = { onEditGift(gift) },
                        onDelete = { onDeleteGift(gift) }
                    )
                    if (i < ideas.lastIndex) HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
            }
        }

        // Куплено
        if (bought.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardBlock(title = stringResource(R.string.cd_gift_bought)) {
                bought.forEachIndexed { i, gift ->
                    GiftRow(
                        gift = gift,
                        actionLabel = stringResource(R.string.cd_gift_give),
                        onAction = { AppStateStore.updateGift(gift.copy(status = GiftStatus.GIVEN)) },
                        onEdit = { onEditGift(gift) },
                        onDelete = { onDeleteGift(gift) }
                    )
                    if (i < bought.lastIndex) HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
            }
        }

        // Подарено
        if (given.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardBlock(title = stringResource(R.string.cd_gift_given_before)) {
                given.forEachIndexed { i, gift ->
                    GiftRow(
                        gift = gift,
                        actionLabel = null,
                        onAction = null,
                        onEdit = { onEditGift(gift) },
                        onDelete = { onDeleteGift(gift) }
                    )
                    if (i < given.lastIndex) HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
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
                    // Кнопка «+ Размеры» — только когда размеров ещё нет. Если они
                    // заданы, редактирование вынесено к самой секции «Размеры» ниже
                    // (иконка-карандаш), иначе слово «Размеры» дублировалось.
                    if (size == null) {
                        TextButton(onClick = onEditSizes, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.cd_sizes), fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = onAddPref, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_preference), fontSize = 12.sp)
                    }
                }
                if (size != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.cd_sizes),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = AppleTheme.colors.brand,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(onClick = onEditSizes, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Default.Edit, stringResource(R.string.cd_sizes),
                                Modifier.size(13.dp), tint = AppleTheme.colors.brand)
                        }
                    }
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
                            color      = if (cat == PersonalDetailCategory.ALLERGIES || cat == PersonalDetailCategory.RESTRICTIONS) AppleTheme.colors.red else AppleTheme.colors.brand,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        items.forEach { pref ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("• ${pref.value}", style = MaterialTheme.typography.bodySmall, color = if (cat == PersonalDetailCategory.ALLERGIES || cat == PersonalDetailCategory.RESTRICTIONS) AppleTheme.colors.red else AppleTheme.colors.label, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onDeletePref(pref) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, stringResource(R.string.common_delete),
                                        Modifier.size(12.dp),
                                        tint = AppleTheme.colors.secondaryLabel)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                } else if (size == null) {
                    Text(
                        stringResource(R.string.cd_hint_sizes),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel
                    )
                }
            }
        }
    }
}

@Composable
private fun GiftRow(
    gift: GiftIdea,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AureliaTheme.colors.gold.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CardGiftcard, null, Modifier.size(20.dp), tint = AureliaTheme.colors.gold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(gift.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!gift.note.isNullOrBlank())
                Text(gift.note, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
            else if (!gift.link.isNullOrBlank())
                Text(gift.link, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.brand)
            else if (!gift.date.isNullOrBlank())
                Text(gift.date, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
        }
        GiftStatusPill(gift.status)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text(actionLabel, fontSize = 11.sp, color = AppleTheme.colors.brand)
            }
        }
        GiftMenu(onEdit = onEdit, onDelete = onDelete)
    }
}

@Composable
private fun GiftStatusPill(status: GiftStatus) {
    val ctx = LocalContext.current
    val (bg, fg) = when (status) {
        GiftStatus.IDEA   -> AureliaTheme.colors.gold.copy(alpha = 0.14f) to AureliaTheme.colors.gold
        GiftStatus.BOUGHT -> AppleTheme.colors.brand.copy(alpha = 0.12f) to AppleTheme.colors.brand
        GiftStatus.GIVEN  -> AppleTheme.colors.fill to AppleTheme.colors.secondaryLabel
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .padding(horizontal = 11.dp, vertical = 4.dp)
    ) {
        Text(status.label(ctx), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}
