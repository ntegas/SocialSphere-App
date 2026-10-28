@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.CalendarItemLink
import com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.CalendarTargetType
import com.aistudio.socialsphere.crmlxb.model.ImportanceLevel
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import com.aistudio.socialsphere.crmlxb.model.ReminderOffsetUnit
import com.aistudio.socialsphere.crmlxb.model.ReminderRule
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import com.aistudio.socialsphere.crmlxb.model.ReminderType
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
import com.aistudio.socialsphere.crmlxb.utils.label
import kotlinx.coroutines.Deferred

/**
 * ЕДИНОЕ «быстрое добавление даты контакта» — для карточки (вкладки «Обзор»/«Подарки») и для формы контакта.
 *
 * Владелец 2026-10-28: «быстрое добавление на карточке — дата, подарок, заметка — всё ещё сделано отдельно от общей
 * формы». У даты были ДВА разных механизма, создававших РАЗНЫЕ события: на карточке — тип из пяти, выбор даты
 * календарём, напоминание, повтор «каждый год», важность; в форме — тип из четырёх, дата текстом «2024-11-15»
 * (любая строка), БЕЗ напоминания и без явной настройки повтора. Из-за этого уведомления о днях рождения приходили
 * по одним контактам и не приходили по другим. Теперь шит и создание события — здесь, один раз.
 */
internal data class NewContactDate(
    val type: CalendarItemType,
    val title: String,
    val startDate: String,
    val remind: ReminderTime = ReminderTime.ON_DAY,
)

/** Варианты напоминания в шите (тот же набор, что был на карточке). */
internal val CONTACT_DATE_REMIND_OPTIONS: List<ReminderTime> =
    listOf(ReminderTime.NONE, ReminderTime.ON_DAY, ReminderTime.DAY_1, ReminderTime.WEEK_1)

/** Правила напоминания для выбранного варианта ([ReminderTime.NONE] и неподдерживаемые — без напоминания). */
internal fun contactDateReminders(itemId: String, remind: ReminderTime): List<ReminderRule> = when (remind) {
    ReminderTime.ON_DAY, ReminderTime.AT_EVENT ->
        listOf(ReminderRule(java.util.UUID.randomUUID().toString(), itemId, ReminderType.AT_TIME))
    ReminderTime.DAY_1 ->
        listOf(ReminderRule(java.util.UUID.randomUUID().toString(), itemId, ReminderType.BEFORE, 1, ReminderOffsetUnit.DAYS))
    ReminderTime.WEEK_1 ->
        listOf(ReminderRule(java.util.UUID.randomUUID().toString(), itemId, ReminderType.BEFORE, 1, ReminderOffsetUnit.WEEKS))
    else -> emptyList()
}

/**
 * Событие календаря для новой даты контакта: связь с контактом, повтор «каждый год» для дня рождения/годовщины/именин,
 * важность дня рождения, напоминание. Единственное место, где это собирается.
 */
internal fun buildContactDateItem(
    contactId: String,
    d: NewContactDate,
    typeLabel: String,
    now: String = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    itemId: String = java.util.UUID.randomUUID().toString(),
): CalendarItem = CalendarItem(
    id = itemId,
    title = d.title.ifBlank { typeLabel },
    type = d.type,
    startDate = d.startDate.trim(),
    isAllDay = true,
    status = CalendarItemStatus.ACTIVE,
    importance = if (d.type == CalendarItemType.BIRTHDAY) ImportanceLevel.KEY else ImportanceLevel.NORMAL,
    recurrenceRule = if (d.type == CalendarItemType.BIRTHDAY ||
        d.type == CalendarItemType.ANNIVERSARY || d.type == CalendarItemType.NAMEDAY)
        RecurrenceMode.YEARLY.toRRule() else null,
    // Modern new row (v19 schema-foundation) — recurrence state подтверждено текущей версией приложения при создании.
    recurrenceExplicitlySet = true,
    links = listOf(CalendarItemLink(java.util.UUID.randomUUID().toString(), itemId, CalendarTargetType.CONTACT, contactId)),
    reminders = contactDateReminders(itemId, d.remind),
    createdAt = now, updatedAt = now,
)

/** Создаёт дату контакта: событие в календаре + будильники напоминаний. Вызывается и карточкой, и формой при сохранении. */
internal fun addContactDate(ctx: Context, contactId: String, d: NewContactDate): Deferred<Unit> {
    val item = buildContactDateItem(contactId, d, d.type.label(ctx))
    val write = AppStateStore.addCalendarItem(item)
    NotificationScheduler.rescheduleReminders(ctx, emptyList(), item)
    return write
}

/** Единственный шит «Добавить важную дату»: тип, название (необязательно), дата календарём, напоминание. */
@Composable
internal fun ContactDateSheet(onDismiss: () -> Unit, onAdd: (NewContactDate) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dType by remember { mutableStateOf(CalendarItemType.BIRTHDAY) }
    var dTitle by remember { mutableStateOf("") }
    var dDate by remember { mutableStateOf("") }
    var dRemind by remember { mutableStateOf(ReminderTime.ON_DAY) }
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
        title = stringResource(R.string.cd_add_important_date),
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.common_add),
        confirmEnabled = dDate.isNotBlank(),
        onConfirm = { onAdd(NewContactDate(dType, dTitle.trim(), dDate.trim(), dRemind)) },
        secondaryText = stringResource(R.string.common_cancel),
        onSecondary = onDismiss,
    ) {
        Text(stringResource(R.string.cd_date_type), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Тот же список, по которому карточка потом ПОКАЗЫВАЕТ даты (ContactCardData.kt).
            CONTACT_CARD_DATE_TYPES.forEach { t ->
                FilterChip(selected = dType == t, onClick = { dType = t }, label = { Text(t.label(ctx)) })
            }
        }
        OutlinedTextField(
            value = dTitle, onValueChange = { dTitle = it }, keyboardOptions = CapSentences,
            label = { Text(stringResource(R.string.cd_date_title_opt)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        DatePickerField(
            value = dDate,
            onValueChange = { dDate = it },
            label = stringResource(R.string.cd_date_iso),
            modifier = Modifier.fillMaxWidth(),
            allowNoYear = true, // ДР/годовщина без известного года — «--MM-DD»
        )
        Text(stringResource(R.string.cd_date_reminder), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CONTACT_DATE_REMIND_OPTIONS.forEach { r ->
                FilterChip(selected = dRemind == r, onClick = { dRemind = r }, label = { Text(r.label(ctx)) })
            }
        }
    }
}
