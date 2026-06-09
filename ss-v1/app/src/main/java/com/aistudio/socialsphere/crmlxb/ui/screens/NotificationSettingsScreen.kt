package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsScreen(onNavigateBack: () -> Unit) {

    // ── Read/write from AppSettings ───────────────────────────
    var notificationsEnabled   by remember { AppSettings.isNotificationsEnabled }
    var defaultReminderTime    by remember { AppSettings.defaultReminderTime }
    var birthdayTimes          by remember { AppSettings.birthdayReminderTimes }
    var giftTime               by remember { AppSettings.giftReminderTime }
    var meetingTime            by remember { AppSettings.meetingReminderTime }
    var callTime               by remember { AppSettings.callReminderTime }
    var showOverdue            by remember { AppSettings.showOverdue }
    var repeatOverdue          by remember { AppSettings.repeatOverdueVisually }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Общие ─────────────────────────────────────────
            NotifCard("Общие настройки") {
                SwitchRow(
                    label   = "Включить уведомления",
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )

                if (notificationsEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Стандартное напоминание",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    // FIX: single-select, saves to AppSettings
                    SingleSelectChips(
                        options   = listOf("за 1 день", "за 1 час", "в момент события"),
                        selected  = defaultReminderTime,
                        onSelect  = { defaultReminderTime = it }
                    )
                }
            }

            if (notificationsEnabled) {
                // ── Дни рождения ──────────────────────────────
                NotifCard("Дни рождения") {
                    Text(
                        "Можно выбрать несколько",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(6.dp))
                    // FIX: multi-select, saves to AppSettings
                    MultiSelectChips(
                        options   = listOf(
                            "без уведомления", "в день события",
                            "за 1 день", "за 3 дня", "за неделю"
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
                NotifCard("Подарки") {
                    SingleSelectChips(
                        options  = listOf("за неделю", "за 3 дня", "за 1 день"),
                        selected = giftTime,
                        onSelect = { giftTime = it }
                    )
                }

                // ── Встречи ───────────────────────────────────
                NotifCard("Встречи") {
                    SingleSelectChips(
                        options  = listOf("за 10 минут", "за 30 минут", "за 1 час", "за 1 день"),
                        selected = meetingTime,
                        onSelect = { meetingTime = it }
                    )
                }

                // ── Звонки и сообщения ────────────────────────
                NotifCard("Звонки и сообщения") {
                    SingleSelectChips(
                        options  = listOf("в момент события", "за 10 минут", "за 1 час"),
                        selected = callTime,
                        onSelect = { callTime = it }
                    )
                }

                // ── Просроченные ──────────────────────────────
                NotifCard("Просроченные события") {
                    SwitchRow(
                        label   = "Показывать просроченные",
                        checked = showOverdue,
                        onCheckedChange = { showOverdue = it }
                    )
                    SwitchRow(
                        label   = "Повторять напоминание визуально",
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
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Текущие настройки",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Дни рождения: ${birthdayTimes.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Встречи: $meetingTime",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Подарки: $giftTime",
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
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            content()
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
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    selectedMultiple: Set<String> = emptySet()
) {
    var singleSel   by remember { mutableStateOf(selected) }
    var multipleSel by remember { mutableStateOf(selectedMultiple) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSelected = if (allowMultiple) opt in multipleSel else opt == singleSel
            FilterChip(
                selected = isSelected,
                onClick  = {
                    if (allowMultiple)
                        multipleSel = if (opt in multipleSel) multipleSel - opt else multipleSel + opt
                    else
                        singleSel = opt
                },
                label = { Text(opt, fontSize = 12.sp) }
            )
        }
    }
}
