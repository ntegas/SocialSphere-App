package com.aistudio.socialsphere.crmlxb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.abs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.*

@Composable
fun ContactAvatar(name: String, size: Int = 48, color: Color = AppleTheme.colors.brand.copy(alpha = 0.10f)) {
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial, fontWeight = FontWeight.Bold, fontSize = (size / 2.8).sp,
            color = AppleTheme.colors.brand
        )
    }
}

@Composable
fun ConfirmDeleteDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = AppleTheme.colors.red) },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text  = { Text(body) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.red)) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = AppleTheme.colors.separator)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
    }
}

@Composable
fun DetailCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = SocialShape.Card,
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/**
 * Удобный выбор даты через Material3 DatePicker. Хранит/возвращает дату в ISO
 * (ГГГГ-ММ-ДД). Поле только для чтения, по тапу открывается календарь.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        enabled = false,
        trailingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
        modifier = modifier.clickable { show = true },
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = AppleTheme.colors.label,
            disabledBorderColor = AppleTheme.colors.tertiaryLabel,
            disabledLabelColor = AppleTheme.colors.secondaryLabel,
            disabledTrailingIconColor = AppleTheme.colors.secondaryLabel
        )
    )
    if (show) {
        WheelDateSheet(
            value     = value,
            onConfirm = { onValueChange(it) },
            onDismiss = { show = false }
        )
    }
}

/**
 * Удобный выбор времени через Material3 TimePicker. Хранит/возвращает "HH:mm".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        enabled = false,
        trailingIcon = { Icon(Icons.Filled.Schedule, null) },
        modifier = modifier.clickable { show = true },
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = AppleTheme.colors.label,
            disabledBorderColor = AppleTheme.colors.tertiaryLabel,
            disabledLabelColor = AppleTheme.colors.secondaryLabel,
            disabledTrailingIconColor = AppleTheme.colors.secondaryLabel
        )
    )
    if (show) {
        WheelTimeSheet(
            value     = value,
            onConfirm = { onValueChange(it) },
            onDismiss = { show = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  Барабан-пикеры (wheel) для даты и времени — iOS-style прокрутка,
//  компактно, с быстрыми пресетами. Без сторонних библиотек.
// ─────────────────────────────────────────────────────────────────

/**
 * Универсальный вертикальный барабан. Центрированный элемент выделен,
 * прокрутка прилипает (snap). Сообщает выбор по оседанию прокрутки.
 * Реализация через невидимые spacer-элементы сверху/снизу, чтобы крайние
 * значения могли встать в центр.
 */
@Composable
private fun <T> WheelPicker(
    items: List<T>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    itemHeight: androidx.compose.ui.unit.Dp = 38.dp,
    label: (T) -> String
) {
    if (items.isEmpty()) return
    val half = visibleCount / 2
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, items.lastIndex)
    )
    val fling = rememberSnapFlingBehavior(lazyListState = listState)

    val centerRealIndex by remember {
        derivedStateOf {
            val li = listState.layoutInfo
            val center = (li.viewportStartOffset + li.viewportEndOffset) / 2f
            val centeredLazy = li.visibleItemsInfo.minByOrNull {
                abs((it.offset + it.size / 2f) - center)
            }?.index
            if (centeredLazy == null) selectedIndex
            else (centeredLazy - half).coerceIn(0, items.lastIndex)
        }
    }

    // Сообщаем выбор только когда прокрутка осела
    LaunchedEffect(centerRealIndex, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && centerRealIndex != selectedIndex) {
            onSelectedIndexChange(centerRealIndex)
        }
    }
    // Внешнее изменение (пресет) — доезжаем до нужного значения
    LaunchedEffect(selectedIndex) {
        if (!listState.isScrollInProgress && centerRealIndex != selectedIndex) {
            listState.animateScrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
        }
    }

    Box(modifier.height(itemHeight * visibleCount), contentAlignment = Alignment.Center) {
        // Подсветка центральной полосы
        Box(
            Modifier.fillMaxWidth().height(itemHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(count = items.size + half * 2) { lazyIndex ->
                val realIndex = lazyIndex - half
                Box(
                    Modifier.fillMaxWidth().height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    if (realIndex in items.indices) {
                        val isCenter = realIndex == centerRealIndex
                        Text(
                            label(items[realIndex]),
                            fontSize = if (isCenter) 19.sp else 15.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCenter) AppleTheme.colors.label
                                    else AppleTheme.colors.secondaryLabel.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WheelDateSheet(
    value: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val today = java.time.LocalDate.now()
    val initial = remember(value) {
        try { if (value.isNotBlank()) java.time.LocalDate.parse(value.take(10)) else today }
        catch (e: Exception) { today }
    }
    var year  by remember { mutableStateOf(initial.year) }
    var month by remember { mutableStateOf(initial.monthValue) }
    var day   by remember { mutableStateOf(initial.dayOfMonth) }

    val monthNames = listOf(
        stringResource(R.string.month_1), stringResource(R.string.month_2),
        stringResource(R.string.month_3), stringResource(R.string.month_4),
        stringResource(R.string.month_5), stringResource(R.string.month_6),
        stringResource(R.string.month_7), stringResource(R.string.month_8),
        stringResource(R.string.month_9), stringResource(R.string.month_10),
        stringResource(R.string.month_11), stringResource(R.string.month_12)
    )
    val years  = remember { (1920..today.year + 5).toList() }
    val months = (1..12).toList()
    val daysInMonth = java.time.YearMonth.of(year, month).lengthOfMonth()
    if (day > daysInMonth) day = daysInMonth
    val days = (1..daysInMonth).toList()

    fun setDate(d: java.time.LocalDate) { year = d.year; month = d.monthValue; day = d.dayOfMonth }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "$day ${monthNames[month - 1]} $year",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { setDate(today) }, label = { Text(stringResource(R.string.cal_today)) })
                AssistChip(onClick = { setDate(today.plusDays(1)) }, label = { Text(stringResource(R.string.cal_tomorrow)) })
                AssistChip(onClick = { setDate(today.plusWeeks(1)) }, label = { Text("+7") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelPicker(days, days.indexOf(day).coerceAtLeast(0),
                    { day = days[it] }, Modifier.weight(1f)) { it.toString() }
                WheelPicker(months, month - 1,
                    { month = it + 1 }, Modifier.weight(1.5f)) { monthNames[it - 1] }
                WheelPicker(years, years.indexOf(year).coerceAtLeast(0),
                    { year = years[it] }, Modifier.weight(1f)) { it.toString() }
            }
            Button(
                onClick = {
                    val safeDay = day.coerceAtMost(java.time.YearMonth.of(year, month).lengthOfMonth())
                    onConfirm(java.time.LocalDate.of(year, month, safeDay).toString())
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WheelTimeSheet(
    value: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val parts = value.split(":")
    var hour   by remember { mutableStateOf(parts.getOrNull(0)?.toIntOrNull() ?: 9) }
    var minute by remember { mutableStateOf(parts.getOrNull(1)?.toIntOrNull() ?: 0) }
    val hours   = (0..23).toList()
    val minutes = (0..59).toList()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                String.format("%02d:%02d", hour, minute),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(9 to 0, 12 to 0, 18 to 0).forEach { (h, m) ->
                    AssistChip(
                        onClick = { hour = h; minute = m },
                        label = { Text(String.format("%02d:%02d", h, m)) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WheelPicker(hours, hour, { hour = it }, Modifier.weight(1f)) { String.format("%02d", it) }
                Text(":", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                WheelPicker(minutes, minute, { minute = it }, Modifier.weight(1f)) { String.format("%02d", it) }
            }
            Button(
                onClick = { onConfirm(String.format("%02d:%02d", hour, minute)); onDismiss() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}

/**
 * Единая шапка режима «Просмотр / Изменить» для вкладки (стиль iOS Контакты).
 * Просмотр: справа тихая «Изменить». Правка: слева «Отмена», справа «Готово».
 */
@Composable
fun TabEditBar(
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp).padding(top = 2.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isEditing) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
            }
        } else {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.tab_edit))
            }
        }
    }
}
