@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.aistudio.socialsphere.crmlxb.ui.screens
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.CalendarViewMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val ctxLabel = LocalContext.current
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Шапка ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.settings_calendar), fontSize = 28.sp)
            }
            CardBlock(stringResource(R.string.calset_display)) {
                Text(stringResource(R.string.calset_default_mode), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilterChipsRow(
                    // calendarDefaultMode хранит enum CalendarViewMode;
                    // title() (из CalendarScreen.kt, тот же пакет) даёт локализованный лейбл
                    options  = CalendarViewMode.entries.map { it.title(ctxLabel) },
                    selected = AppSettings.calendarDefaultMode.value.title(ctxLabel),
                    onSelect = { label ->
                        CalendarViewMode.entries.find { it.title(ctxLabel) == label }
                            ?.let { AppSettings.calendarDefaultMode.value = it }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.calset_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.secondaryLabel
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.calset_first_day), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                val mon = AppSettings.calendarFirstDayMonday.value
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegButton(stringResource(R.string.calset_monday), mon, Modifier.weight(1f)) { AppSettings.calendarFirstDayMonday.value = true }
                    SegButton(stringResource(R.string.calset_sunday), !mon, Modifier.weight(1f)) { AppSettings.calendarFirstDayMonday.value = false }
                }
                // «Формат времени» вернётся, когда события получат время суток
            }

            // Блок «Цвета» удалён: легенда показывала ВЫДУМАННЫЕ цвета (заливки
            // brand@10%/fill/card, не реальные цвета типов) — обманка (§2 KNOWLEDGE).
            // Настоящие цвета типов видны квадратиками в «Видимости типов» ниже;
            // в прототипе отдельной легенды нет.

            // «Видимость типов»: caps-заголовок снаружи карточки (прототип)
            Column {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel(
                    stringResource(R.string.calset_visibility),
                    modifier = Modifier.padding(start = 4.dp, bottom = 9.dp)
                )
                CardBlock(null) {
                Text(
                    stringResource(R.string.calset_visibility_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val typeRows = listOf(
                    stringResource(R.string.evt_birthdays)       to CalendarItemType.BIRTHDAY,
                    stringResource(R.string.evt_anniversary)     to CalendarItemType.ANNIVERSARY,
                    stringResource(R.string.evt_important_date)  to CalendarItemType.IMPORTANT_DATE,
                    stringResource(R.string.evt_meetings)        to CalendarItemType.MEETING,
                    stringResource(R.string.evt_calls)           to CalendarItemType.CALL,
                    stringResource(R.string.evt_gifts)           to CalendarItemType.GIFT,
                    stringResource(R.string.evt_tasks)           to CalendarItemType.TASK,
                    stringResource(R.string.evt_company_events)  to CalendarItemType.COMPANY_EVENT
                )
                val hidden = AppSettings.calendarHiddenTypes.value
                // Свой цвет типа (фидбэк 2026-07-04) — тап по квадратику открывает
                // палитру. Тип+подпись сохраняются для заголовка шторки.
                var colorPickerFor by remember { mutableStateOf<Pair<CalendarItemType, String>?>(null) }
                typeRows.forEach { (name, type) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                Modifier.size(14.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R5)
                                    .background(eventTypeColor(type))
                                    .clickable { colorPickerFor = type to name }
                            )
                            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label)
                        }
                        Switch(
                            checked = type.name !in hidden,
                            onCheckedChange = { visible ->
                                AppSettings.calendarHiddenTypes.value =
                                    if (visible) hidden - type.name else hidden + type.name
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.colors.brand)
                        )
                    }
                }
                colorPickerFor?.let { (type, name) ->
                    EventColorPickerSheet(
                        typeName = name,
                        currentColor = eventTypeColor(type),
                        onDismiss = { colorPickerFor = null },
                        onPick = { color ->
                            AppSettings.calendarTypeColors.value =
                                AppSettings.calendarTypeColors.value + (type.name to color.toArgb())
                            colorPickerFor = null
                        },
                        onReset = {
                            AppSettings.calendarTypeColors.value =
                                AppSettings.calendarTypeColors.value - type.name
                            colorPickerFor = null
                        }
                    )
                }
                }
            }
        }
    }
}

// Палитра выбора цвета типа события (фидбэк 2026-07-04) — оттенки уже
// используемые в дизайн-системе приложения (макетные акценты + доп. тона),
// без произвольного HSV-колесa: выбор ограничен согласованными цветами.
private val EventColorPalette = listOf(
    androidx.compose.ui.graphics.Color(0xFF1C6B4C), // малахит
    androidx.compose.ui.graphics.Color(0xFFB68A36), // золото
    androidx.compose.ui.graphics.Color(0xFFC45D34), // терракот
    androidx.compose.ui.graphics.Color(0xFF5E8C66), // сейдж
    androidx.compose.ui.graphics.Color(0xFF2A5DB0), // сапфир
    androidx.compose.ui.graphics.Color(0xFF7E5180), // аметист
    androidx.compose.ui.graphics.Color(0xFF3E7E7A), // тил
    androidx.compose.ui.graphics.Color(0xFFC0492F), // тревожный красный
)

@Composable
private fun EventColorPickerSheet(
    typeName: String,
    currentColor: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    onPick: (androidx.compose.ui.graphics.Color) -> Unit,
    onReset: () -> Unit,
) {
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = onDismiss) {
        Text(
            stringResource(R.string.calset_pick_color, typeName),
            fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(stringResource(R.string.calset_pick_color, typeName)),
            fontSize = 18.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.label,
            modifier = Modifier.padding(bottom = 14.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EventColorPalette.forEach { swatch ->
                val selected = swatch.value == currentColor.value
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(swatch)
                        .then(
                            if (selected) Modifier.border(3.dp, AppleTheme.colors.label, CircleShape)
                            else Modifier
                        )
                        .clickable { onPick(swatch) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Default.Check, null, Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.calset_reset_color),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel,
            modifier = Modifier.clickable { onReset() }.padding(vertical = 6.dp)
        )
    }
}

// Сегмент-кнопка (первый день недели) по макету Aurelia.
@Composable
private fun SegButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.height(38.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R11)
            .background(if (selected) AppleTheme.colors.brand else AppleTheme.colors.card)
            .then(
                if (!selected) Modifier.border(1.dp, AppleTheme.colors.separator,
                    com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R11)
                else Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label.replaceFirstChar { it.uppercase() },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) androidx.compose.ui.graphics.Color.White else AppleTheme.colors.secondaryLabel
        )
    }
}
