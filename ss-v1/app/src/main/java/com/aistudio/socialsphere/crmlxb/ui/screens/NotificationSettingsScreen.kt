@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsScreen(onNavigateBack: () -> Unit) {
    val ctxLabel = LocalContext.current

    // ── Read/write from AppSettings ───────────────────────────
    var notificationsEnabled   by remember { AppSettings.isNotificationsEnabled }
    var defaultReminderTime    by remember { AppSettings.defaultReminderTime }
    var birthdayTimes          by remember { AppSettings.birthdayReminderTimes }
    var giftTime               by remember { AppSettings.giftReminderTime }
    var meetingTime            by remember { AppSettings.meetingReminderTime }
    var callTime               by remember { AppSettings.callReminderTime }
    var showOverdue            by remember { AppSettings.showOverdue }
    var repeatOverdue          by remember { AppSettings.repeatOverdueVisually }
    var remindStale            by remember { AppSettings.remindStaleContacts }
    var remindBday             by remember { AppSettings.remindBirthdays }
    var remindNoStep           by remember { AppSettings.remindNoNextStep }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Шапка: круг-назад + заголовок (по макету) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppleTheme.colors.fill).clickable { onNavigateBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), Modifier.size(20.dp), tint = AppleTheme.colors.label)
                }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.notif_title))
            }

            // ── Общие ─────────────────────────────────────────
            NotifCard(stringResource(R.string.notif_general)) {
                SwitchRow(
                    label   = stringResource(R.string.notif_enable),
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )

                if (notificationsEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.notif_default_reminder),
                        style = MaterialTheme.typography.labelMedium,
                        color = AppleTheme.colors.brand
                    )
                    Spacer(Modifier.height(6.dp))
                    // FIX: single-select, saves to AppSettings
                    ReminderChips(
                        options   = listOf(ReminderTime.DAY_1, ReminderTime.HOUR_1, ReminderTime.AT_EVENT),
                        selected  = defaultReminderTime,
                        onSelect  = { defaultReminderTime = it }
                    )
                }
            }

            if (notificationsEnabled) {
                // ── Пора связаться (по ритму общения) ─────────
                NotifCard(stringResource(R.string.notif_general)) {
                    SwitchRow(
                        label   = stringResource(R.string.set_remind_stale),
                        checked = remindStale,
                        onCheckedChange = {
                            remindStale = it
                            NotificationScheduler.scheduleStaleCheck(ctxLabel)
                        }
                    )
                    SwitchRow(
                        label   = stringResource(R.string.set_remind_birthdays),
                        checked = remindBday,
                        onCheckedChange = {
                            remindBday = it
                            NotificationScheduler.scheduleStaleCheck(ctxLabel)
                        }
                    )
                    SwitchRow(
                        label   = stringResource(R.string.set_remind_no_next_step),
                        checked = remindNoStep,
                        onCheckedChange = {
                            remindNoStep = it
                            NotificationScheduler.scheduleStaleCheck(ctxLabel)
                        }
                    )
                }

                // ── Точные напоминания (Android 12+) ──────────
                // USE_EXACT_ALARM убран (политика Play). На 12+ при отсутствии
                // разрешения показываем кнопку перехода в системные настройки.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val am = ctxLabel.getSystemService(android.content.Context.ALARM_SERVICE)
                        as android.app.AlarmManager
                    if (!am.canScheduleExactAlarms()) {
                        NotifCard(stringResource(R.string.notif_general)) {
                            Text(
                                stringResource(R.string.set_exact_alarms_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppleTheme.colors.secondaryLabel
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                ctxLabel.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        android.net.Uri.parse("package:" + ctxLabel.packageName)
                                    )
                                )
                            }) { Text(stringResource(R.string.set_exact_alarms)) }
                        }
                    }
                }

                // ── Дни рождения ──────────────────────────────
                NotifCard(stringResource(R.string.notif_birthdays)) {
                    Text(
                        stringResource(R.string.notif_multi_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.secondaryLabel
                    )
                    Spacer(Modifier.height(6.dp))
                    // FIX: multi-select, saves to AppSettings
                    ReminderMultiChips(
                        options   = listOf(
                            ReminderTime.NONE, ReminderTime.ON_DAY,
                            ReminderTime.DAY_1, ReminderTime.DAY_3, ReminderTime.WEEK_1
                        ),
                        selected  = birthdayTimes,
                        onToggle  = { option ->
                            birthdayTimes = if (option in birthdayTimes)
                                birthdayTimes - option
                            else
                                birthdayTimes + option
                        }
                    )
                }

                // ── Подарки ───────────────────────────────────
                NotifCard(stringResource(R.string.notif_gifts)) {
                    ReminderChips(
                        options  = listOf(ReminderTime.WEEK_1, ReminderTime.DAY_3, ReminderTime.DAY_1),
                        selected = giftTime,
                        onSelect = { giftTime = it }
                    )
                }

                // ── Встречи ───────────────────────────────────
                NotifCard(stringResource(R.string.notif_meetings)) {
                    ReminderChips(
                        options  = listOf(ReminderTime.MIN_10, ReminderTime.MIN_30, ReminderTime.HOUR_1, ReminderTime.DAY_1),
                        selected = meetingTime,
                        onSelect = { meetingTime = it }
                    )
                }

                // ── Звонки и сообщения ────────────────────────
                NotifCard(stringResource(R.string.notif_calls)) {
                    ReminderChips(
                        options  = listOf(ReminderTime.AT_EVENT, ReminderTime.MIN_10, ReminderTime.HOUR_1),
                        selected = callTime,
                        onSelect = { callTime = it }
                    )
                }

                // ── Просроченные ──────────────────────────────
                NotifCard(stringResource(R.string.notif_overdue)) {
                    SwitchRow(
                        label   = stringResource(R.string.notif_show_overdue),
                        checked = showOverdue,
                        onCheckedChange = { showOverdue = it }
                    )
                    SwitchRow(
                        label   = stringResource(R.string.notif_repeat_visual),
                        checked = repeatOverdue,
                        onCheckedChange = { repeatOverdue = it }
                    )
                }
            }

            // Summary
            if (notificationsEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AppleTheme.colors.brand.copy(alpha = 0.10f).copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.notif_current),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleTheme.colors.brand
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.notif_cur_birthdays, birthdayTimes.joinToString(", ") { it.label(ctxLabel) }),
                            style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.notif_cur_meetings, meetingTime.label(ctxLabel)),
                            style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.notif_cur_gifts, giftTime.label(ctxLabel)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────
@Composable
private fun NotifCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    // По макету: капс-заголовок секции над плоской inset-картой.
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            title.uppercase(),
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            color = AppleTheme.colors.tertiaryLabel,
            modifier = Modifier.padding(start = 6.dp)
        )
        Card(
            modifier  = Modifier.fillMaxWidth(),
            colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppleTheme.colors.label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.colors.brand)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun ReminderTime.label(context: android.content.Context): String = when (this) {
    ReminderTime.NONE     -> context.getString(R.string.rt_none)
    ReminderTime.AT_EVENT -> context.getString(R.string.rt_at_event)
    ReminderTime.ON_DAY   -> context.getString(R.string.rt_on_day)
    ReminderTime.MIN_10   -> context.getString(R.string.rt_min_10)
    ReminderTime.MIN_30   -> context.getString(R.string.rt_min_30)
    ReminderTime.HOUR_1   -> context.getString(R.string.rt_hour_1)
    ReminderTime.DAY_1    -> context.getString(R.string.rt_day_1)
    ReminderTime.DAY_3    -> context.getString(R.string.rt_day_3)
    ReminderTime.WEEK_1   -> context.getString(R.string.rt_week_1)
}

// Обёртки: работают на enum, маппинг label<->enum внутри —
// чтобы call-site не дублировал поиск, а строки оставались только в label()
@Composable
private fun ReminderChips(options: List<ReminderTime>, selected: ReminderTime, onSelect: (ReminderTime) -> Unit) {
    val ctxLabel = LocalContext.current
    val labels = options.map { it.label(ctxLabel) }
    SingleSelectChips(
        options = labels,
        selected = selected.label(ctxLabel),
        onSelect = { lbl -> options.find { it.label(ctxLabel) == lbl }?.let(onSelect) }
    )
}

@Composable
private fun ReminderMultiChips(options: List<ReminderTime>, selected: Set<ReminderTime>, onToggle: (ReminderTime) -> Unit) {
    val ctxLabel = LocalContext.current
    val selLabels = selected.map { it.label(ctxLabel) }.toSet()
    MultiSelectChips(
        options = options.map { it.label(ctxLabel) },
        selected = selLabels,
        onToggle = { lbl -> options.find { it.label(ctxLabel) == lbl }?.let(onToggle) }
    )
}

@Composable
private fun SingleSelectChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { opt ->
            FilterChip(
                selected = opt == selected,
                // FIX: onClick now saves selection
                onClick  = { onSelect(opt) },
                label    = { Text(opt, fontSize = 12.sp) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectChips(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { opt ->
            FilterChip(
                selected = opt in selected,
                // FIX: onClick toggles selection in set
                onClick  = { onToggle(opt) },
                label    = { Text(opt, fontSize = 12.sp) }
            )
        }
    }
}

// Kept for backward compat with CalendarSettingsScreen
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterChipsRow(
    options: List<String>,
    selected: String,
    allowMultiple: Boolean = false,
    selectedMultiple: Set<String> = emptySet(),
    // Без колбэков выбор никогда не покидал композабл —
    // все экраны настроек были декорациями
    onSelect: (String) -> Unit = {},
    onSelectMultiple: (Set<String>) -> Unit = {}
) {
    var singleSel   by remember(selected) { mutableStateOf(selected) }
    var multipleSel by remember(selectedMultiple) { mutableStateOf(selectedMultiple) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSelected = if (allowMultiple) opt in multipleSel else opt == singleSel
            FilterChip(
                selected = isSelected,
                onClick  = {
                    if (allowMultiple) {
                        multipleSel = if (opt in multipleSel) multipleSel - opt else multipleSel + opt
                        onSelectMultiple(multipleSel)
                    } else {
                        singleSel = opt
                        onSelect(opt)
                    }
                },
                label = { Text(opt, fontSize = 12.sp) }
            )
        }
    }
}
