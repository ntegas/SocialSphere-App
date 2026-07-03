package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif
import com.aistudio.socialsphere.crmlxb.ui.theme.aureliaPress
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.effectiveDate
import com.aistudio.socialsphere.crmlxb.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun CalendarViewMode.title(context: android.content.Context): String = when (this) {
    CalendarViewMode.LIST  -> context.getString(R.string.cal_mode_list)
    CalendarViewMode.WEEK  -> context.getString(R.string.cal_mode_week)
    CalendarViewMode.MONTH -> context.getString(R.string.cal_mode_month)
}

fun CalendarEventFilter.title(context: android.content.Context): String = when (this) {
    CalendarEventFilter.ALL       -> context.getString(R.string.common_all)
    CalendarEventFilter.BIRTHDAYS -> context.getString(R.string.cal_f_birthdays)
    CalendarEventFilter.CALLS     -> context.getString(R.string.cal_f_calls)
    CalendarEventFilter.MEETINGS  -> context.getString(R.string.cal_f_meetings)
    CalendarEventFilter.GIFTS     -> context.getString(R.string.cd_tab_gifts)
    CalendarEventFilter.IMPORTANT -> context.getString(R.string.cal_f_important)
}

@Composable
private fun CalFilterChip(label: String, active: Boolean, dotColor: Color? = null, onClick: () -> Unit) {
    // Спека Aurelia: h28 r14, активный — бренд/белый 700; неактивный — card,
    // вторичный текст 600, тонкая инсет-обводка + цветная точка типа (как в макете).
    Box(
        Modifier.height(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(if (active) AppleTheme.colors.brand else AppleTheme.colors.card)
            .then(if (!active) Modifier.border(1.dp, AppleTheme.colors.separator, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) else Modifier)
            .clickable { onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dotColor != null && !active)
                Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                color = if (active) Color.White else AppleTheme.colors.secondaryLabel)
        }
    }
}

@Composable
fun CalendarScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToCalendarItem: (String) -> Unit,
    onNavigateToCreateCalendarItem: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctxLabel = LocalContext.current
    var selectedMode by remember { mutableStateOf(AppSettings.calendarDefaultMode.value) }
    var selectedFilter by remember { mutableStateOf(CalendarEventFilter.ALL) }

    val modes = CalendarViewMode.entries
    val filters = CalendarEventFilter.entries

    val allEvents = AppStateStore.calendarItems

    Scaffold(
        modifier = modifier,
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {},
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header по макету: заголовок + круглая «+»
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(stringResource(R.string.cal_title))
                Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppleTheme.colors.brand).clickable { onNavigateToCreateCalendarItem() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, stringResource(R.string.cal_add_event), tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            // Сегмент-контрол видов (спека Aurelia: трек fill r11 pad3, активный card r8 вес700)
            Row(
                // Трек — нейтрально-серый rgba(120,120,128,.10), как в прототипе
                modifier = Modifier.fillMaxWidth().height(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp)).background(AppleTheme.colors.neutralFill).padding(3.dp)
            ) {
                val orderedModes = listOf(CalendarViewMode.LIST, CalendarViewMode.WEEK, CalendarViewMode.MONTH)
                orderedModes.forEach { mode ->
                    val isSelected = selectedMode == mode
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(if (isSelected) AppleTheme.colors.card else Color.Transparent).clickable { selectedMode = mode },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(mode.title(ctxLabel), fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel)
                    }
                }
            }
            // Чипы типов с цветными точками
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { filter ->
                    CalFilterChip(filter.title(ctxLabel), selectedFilter == filter, dotColor = calFilterDot(filter)) { selectedFilter = filter }
                }
            }

            // События после фильтра типа и скрытых типов — общие для списка и сетки
            val visibleEvents by remember {
                derivedStateOf {
                    val hiddenTypes = AppSettings.calendarHiddenTypes.value
                    allEvents.filter { event ->
                        // Выполненные уходят из всех видов календаря (фидбэк владельца:
                        // «выполнено, а из списка не уходит» — фильтра по статусу не было)
                        if (event.status != CalendarItemStatus.ACTIVE) return@filter false
                        if (event.type.name in hiddenTypes) return@filter false
                        when (selectedFilter) {
                            CalendarEventFilter.BIRTHDAYS -> event.type == CalendarItemType.BIRTHDAY
                            CalendarEventFilter.CALLS -> event.type == CalendarItemType.CALL
                            CalendarEventFilter.MEETINGS -> event.type == CalendarItemType.MEETING
                            CalendarEventFilter.GIFTS -> event.type == CalendarItemType.GIFT
                            CalendarEventFilter.IMPORTANT -> event.importance in listOf(ImportanceLevel.IMPORTANT, ImportanceLevel.KEY)
                            CalendarEventFilter.ALL -> true
                        }
                    }
                }
            }

            // stringResource нельзя вызывать внутри derivedStateOf —
            // захватываем заголовки групп здесь; они же — ключи remember,
            // чтобы смена языка пересчитала группировку.
            // Группировка нужна только «Ленте» (LIST): «Неделя» — сетка-полоса,
            // «Месяц» — сетка месяца, обе рендерят visibleEvents напрямую.
            // По прототипу: «Сегодня · 27 июня» / «Завтра · 28 июня» / далее —
            // каждая дата своей группой («2 июля»), а не скопом «Позже».
            val strToday      = stringResource(R.string.cal_today)
            val strTomorrow   = stringResource(R.string.cal_tomorrow)
            val dayFmt        = remember { java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.getDefault()) }
            val todayHeader   = "$strToday · ${java.time.LocalDate.now().format(dayFmt)}"

            // Пересчёт только при изменении фильтра, режима, данных или языка —
            // не на каждой рекомпозиции (derivedStateOf отслеживает snapshot-состояния)
            val groupedEvents by remember(strToday, strTomorrow) {
                derivedStateOf {
                    val filteredEvents = visibleEvents

                    val todayLd      = java.time.LocalDate.now()
                    val todayDate    = todayLd.toString()
                    val tomorrowDate = todayLd.plusDays(1).toString()

                    val grouped = mutableMapOf<String, List<CalendarItem>>()
                    if (selectedMode == CalendarViewMode.LIST) {
                        val today    = filteredEvents.filter { it.effectiveDate() == todayDate }
                        val tomorrow = filteredEvents.filter { it.effectiveDate() == tomorrowDate }
                        if (today.isNotEmpty())    grouped[todayHeader] = today
                        if (tomorrow.isNotEmpty())
                            grouped["$strTomorrow · ${todayLd.plusDays(1).format(dayFmt)}"] = tomorrow
                        // Далее — по датам, каждая дата своей группой
                        filteredEvents
                            .filter { it.effectiveDate() > tomorrowDate }
                            .sortedBy { it.effectiveDate() }
                            .groupBy { it.effectiveDate() }
                            .forEach { (date, evs) ->
                                val label = try {
                                    java.time.LocalDate.parse(date).format(dayFmt)
                                } catch (e: Exception) { date }
                                grouped[label] = evs
                            }
                    }
                    grouped
                }
            }

            if (selectedMode == CalendarViewMode.MONTH) {
                MonthGridView(
                    events         = visibleEvents,
                    firstDayMonday = AppSettings.calendarFirstDayMonday.value,
                    onEventClick   = { onNavigateToCalendarItem(it) },
                    modifier       = Modifier.fillMaxSize().weight(1f)
                )
            } else if (selectedMode == CalendarViewMode.WEEK) {
                WeekStripView(
                    events         = visibleEvents,
                    firstDayMonday = AppSettings.calendarFirstDayMonday.value,
                    onEventClick   = { onNavigateToCalendarItem(it) },
                    modifier       = Modifier.fillMaxSize().weight(1f)
                )
            } else if (groupedEvents.isEmpty() || groupedEvents.all { it.value.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.cal_no_events_found), color = AppleTheme.colors.secondaryLabel)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    groupedEvents.forEach { (header, evItems) ->
                        val isTodayGroup = header == todayHeader
                        item(key = "h_$header") {
                            // Caps-заголовок группы: «Сегодня» — акцентом (прототип)
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel(
                                header,
                                color = if (isTodayGroup) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(start = 34.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
                            )
                        }
                        item(key = "c_$header") {
                            // Таймлайн-лента по макету: вертикальная линия + цветная
                            // точка типа у каждого события, карточки отдельные.
                            Column(Modifier.fillMaxWidth()) {
                                evItems.forEachIndexed { i, event ->
                                    CalendarTimelineRow(
                                        event   = event,
                                        isLast  = i == evItems.lastIndex,
                                        isToday = isTodayGroup,
                                        onClick = { onNavigateToCalendarItem(event.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Сетка месяца: дни с цветными точками событий (ДР проецируются через
 * effectiveDate на ближайшее наступление), листание месяцев, тап по дню —
 * события дня внизу, тап по событию — карточка с напоминаниями.
 */
@Composable
fun MonthGridView(
    events: List<CalendarItem>,
    firstDayMonday: Boolean,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var month       by remember { mutableStateOf(java.time.YearMonth.now()) }
    var selectedDay by remember { mutableStateOf(java.time.LocalDate.now()) }
    val today = java.time.LocalDate.now()

    // Для произвольного месяца сетки ДР проецируем на ГОД отображаемого месяца,
    // а не только на ближайшее наступление
    val eventsByDay = remember(events, month) {
        val map = mutableMapOf<String, MutableList<CalendarItem>>()
        events.forEach { ev ->
            val isYearly = ev.type == CalendarItemType.BIRTHDAY ||
                ev.recurrenceRule?.contains("YEARLY", ignoreCase = true) == true
            val key = if (isYearly) {
                try {
                    (parseFlexibleDate(ev.startDate) ?: error("bad date"))
                        .withYear(month.year).toString()
                } catch (e: Exception) { ev.startDate.take(10) }
            } else ev.startDate.take(10)
            map.getOrPut(key) { mutableListOf() }.add(ev)
        }
        map
    }

    val monthNames = listOf(
        stringResource(R.string.month_1), stringResource(R.string.month_2),
        stringResource(R.string.month_3), stringResource(R.string.month_4),
        stringResource(R.string.month_5), stringResource(R.string.month_6),
        stringResource(R.string.month_7), stringResource(R.string.month_8),
        stringResource(R.string.month_9), stringResource(R.string.month_10),
        stringResource(R.string.month_11), stringResource(R.string.month_12)
    )

    Column(modifier = modifier) {
      // Сетка месяца — прямо на фоне экрана, без карточки-обёртки (прототип)
      Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        // ── Шапка: «Июнь 2026» Playfair слева + стрелки справа (прототип) ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${monthNames[month.monthValue - 1]} ${month.year}",
                fontFamily = AureliaSerif,
                fontSize = 17.sp,
                fontWeight = FontWeight.W700,
                color = AppleTheme.colors.label
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.cal_prev_month),
                    Modifier.size(22.dp).aureliaPress { month = month.minusMonths(1) },
                    tint = AppleTheme.colors.secondaryLabel)
                Icon(Icons.Default.ChevronRight, stringResource(R.string.cal_next_month),
                    Modifier.size(22.dp).aureliaPress { month = month.plusMonths(1) },
                    tint = AppleTheme.colors.secondaryLabel)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── Дни недели ──
        val dow = if (firstDayMonday)
            listOf(stringResource(R.string.wd_mon), stringResource(R.string.wd_tue), stringResource(R.string.wd_wed), stringResource(R.string.wd_thu), stringResource(R.string.wd_fri), stringResource(R.string.wd_sat), stringResource(R.string.wd_sun))
        else
            listOf(stringResource(R.string.wd_sun), stringResource(R.string.wd_mon), stringResource(R.string.wd_tue), stringResource(R.string.wd_wed), stringResource(R.string.wd_thu), stringResource(R.string.wd_fri), stringResource(R.string.wd_sat))
        Row(Modifier.fillMaxWidth()) {
            dow.forEach { d ->
                Text(
                    d,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    color = AppleTheme.colors.tertiaryLabel
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // ── Сетка ──
        val firstDowIso = month.atDay(1).dayOfWeek.value      // 1=Пн … 7=Вс
        val offset      = if (firstDayMonday) firstDowIso - 1 else firstDowIso % 7
        val daysInMonth = month.lengthOfMonth()
        val rows        = (offset + daysInMonth + 6) / 7

        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayNum in 1..daysInMonth) {
                            val date    = month.atDay(dayNum)
                            val isToday = date == today
                            val isSel   = date == selectedDay
                            val dayEvts = eventsByDay[date.toString()].orEmpty()
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(if (isToday) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isToday -> AppleTheme.colors.brand
                                            isSel   -> AppleTheme.colors.brand.copy(alpha = 0.15f)
                                            else    -> Color.Transparent
                                        }
                                    )
                                    .clickable { selectedDay = date }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "$dayNum",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isToday -> Color.White
                                        else    -> AppleTheme.colors.label
                                    }
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    dayEvts.take(3).forEach { ev ->
                                        Box(
                                            Modifier.size(5.dp).clip(CircleShape).background(
                                                eventTypeColor(ev.type)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
      }

        Spacer(Modifier.height(16.dp))

        // ── События выбранного дня ──
        val selEvents = eventsByDay[selectedDay.toString()].orEmpty()
        val mDayHeaderFmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.getDefault()) }
        AureliaCapsLabel(
            selectedDay.format(mDayHeaderFmt) +
                if (selEvents.isEmpty()) " " + stringResource(R.string.cal_no_events_day) else "",
            color = AppleTheme.colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(10.dp))
        if (selEvents.isNotEmpty()) {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column {
                            selEvents.forEachIndexed { i, event ->
                                CalendarEventItem(event = event, onClick = { onEventClick(event.id) })
                                if (i < selEvents.lastIndex) com.aistudio.socialsphere.crmlxb.ui.theme.AppleDivider(70.dp)
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * «Неделя» по макету Aurelia: горизонтальная полоса из 7 дней (буква дня недели +
 * число, точки типов событий), выделенный день — малахитовая пилюля, сегодня —
 * кольцо. Ниже — события выбранного дня. Листание недель — стрелками.
 */
@Composable
fun WeekStripView(
    events: List<CalendarItem>,
    firstDayMonday: Boolean,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = java.time.LocalDate.now()
    fun startOfWeek(d: java.time.LocalDate): java.time.LocalDate {
        val dow = d.dayOfWeek.value // 1=Пн … 7=Вс
        val offset = if (firstDayMonday) dow - 1 else dow % 7
        return d.minusDays(offset.toLong())
    }
    var weekStart   by remember { mutableStateOf(startOfWeek(today)) }
    var selectedDay by remember { mutableStateOf(today) }
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }

    // События конкретного дня: годовые (ДР / YEARLY) сопоставляем по месяцу+числу,
    // остальные — по точной дате. Согласовано с MonthGridView.
    fun eventsOn(date: java.time.LocalDate): List<CalendarItem> = events.filter { ev ->
        val isYearly = ev.type == CalendarItemType.BIRTHDAY ||
            ev.recurrenceRule?.contains("YEARLY", ignoreCase = true) == true
        if (isYearly) {
            val d = parseFlexibleDate(ev.startDate)
            d != null && d.monthValue == date.monthValue && d.dayOfMonth == date.dayOfMonth
        } else ev.startDate.take(10) == date.toString()
    }

    val monthNames = listOf(
        stringResource(R.string.month_1), stringResource(R.string.month_2),
        stringResource(R.string.month_3), stringResource(R.string.month_4),
        stringResource(R.string.month_5), stringResource(R.string.month_6),
        stringResource(R.string.month_7), stringResource(R.string.month_8),
        stringResource(R.string.month_9), stringResource(R.string.month_10),
        stringResource(R.string.month_11), stringResource(R.string.month_12)
    )
    val dowLetters = if (firstDayMonday)
        listOf(stringResource(R.string.wd_mon), stringResource(R.string.wd_tue), stringResource(R.string.wd_wed), stringResource(R.string.wd_thu), stringResource(R.string.wd_fri), stringResource(R.string.wd_sat), stringResource(R.string.wd_sun))
    else
        listOf(stringResource(R.string.wd_sun), stringResource(R.string.wd_mon), stringResource(R.string.wd_tue), stringResource(R.string.wd_wed), stringResource(R.string.wd_thu), stringResource(R.string.wd_fri), stringResource(R.string.wd_sat))

    val first = days.first(); val last = days.last()
    val rangeLabel = if (first.monthValue == last.monthValue)
        "${first.dayOfMonth}–${last.dayOfMonth} ${monthNames[first.monthValue - 1]}"
    else
        "${first.dayOfMonth} ${monthNames[first.monthValue - 1]} – ${last.dayOfMonth} ${monthNames[last.monthValue - 1]}"

    Column(modifier = modifier) {
        // ── Шапка: диапазон Playfair 16 + стрелки (без карточки-обёртки, прототип) ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(rangeLabel, fontFamily = AureliaSerif, fontWeight = FontWeight.W700,
                fontSize = 16.sp, color = AppleTheme.colors.label)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.cal_prev_month),
                    Modifier.size(22.dp).aureliaPress { weekStart = weekStart.minusWeeks(1); selectedDay = weekStart },
                    tint = AppleTheme.colors.secondaryLabel)
                Icon(Icons.Default.ChevronRight, stringResource(R.string.cal_next_month),
                    Modifier.size(22.dp).aureliaPress { weekStart = weekStart.plusWeeks(1); selectedDay = weekStart },
                    tint = AppleTheme.colors.secondaryLabel)
            }
        }
        // ── Полоса дней: плитки h44/r12, выбранный — акцент-заливка (прототип) ──
        val ringAlpha = if (AppleTheme.colors.isDark) 0.10f else 0.05f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEachIndexed { i, date ->
                val isToday = date == today
                val isSel   = date == selectedDay
                val firstEv = eventsOn(date).firstOrNull()
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(dowLetters[i], fontSize = 10.sp,
                        fontWeight = if (isSel) FontWeight.W700 else FontWeight.W600,
                        color = if (isSel) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel)
                    Box(
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) AppleTheme.colors.brand else AppleTheme.colors.card)
                            .then(if (!isSel) Modifier.border(1.dp, AppleTheme.colors.label.copy(alpha = ringAlpha), RoundedCornerShape(12.dp)) else Modifier)
                            .aureliaPress { selectedDay = date },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${date.dayOfMonth}",
                                fontSize = 14.sp, fontWeight = FontWeight.W700,
                                color = when {
                                    isSel   -> Color.White
                                    isToday -> AppleTheme.colors.brand
                                    else    -> AppleTheme.colors.label
                                }
                            )
                            if (firstEv != null)
                                Box(Modifier.size(4.dp).clip(CircleShape)
                                    .background(if (isSel) AppleTheme.colors.card else eventTypeColor(firstEv.type)))
                        }
                    }
                }
            }
        }

        // ── События выбранного дня: caps-дата + время в гаттере + карточка с бортом ──
        val selEvents = eventsOn(selectedDay).sortedBy { it.startTime ?: "" }
        val dayHeaderFmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.getDefault()) }
        AureliaCapsLabel(
            selectedDay.format(dayHeaderFmt),
            color = AppleTheme.colors.secondaryLabel,
            modifier = Modifier.padding(start = 2.dp, top = 18.dp, bottom = 10.dp)
        )
        if (selEvents.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(selEvents, key = { it.id }) { event ->
                    WeekEventRow(event) { onEventClick(event.id) }
                }
            }
        } else {
            Text(stringResource(R.string.cal_no_events_found),
                fontSize = 14.sp, color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.padding(start = 2.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Строка события «Недели» (прототип): время в левом гаттере 40px (12 W600, tx3) +
 * карточка r12 с цветным левым бортом 3px по типу события.
 */
@Composable
private fun WeekEventRow(event: CalendarItem, onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
    val typeColor = eventTypeColor(event.type)
    val time = event.startTime.orEmpty()
    val names = event.links.mapNotNull { link ->
        when (link.targetType) {
            CalendarTargetType.CONTACT -> AppStateStore.getContact(link.targetId)?.let { "${it.firstName} ${it.lastName}".trim() }
            CalendarTargetType.COMPANY -> AppStateStore.getCompany(link.targetId)?.name
            else -> null
        }
    }.joinToString(", ")
    val timeRange = if (time.isNotEmpty())
        time + (if (!event.endTime.isNullOrEmpty()) " – ${event.endTime}" else "") else ""
    val sub = listOf(timeRange, names).filter { it.isNotEmpty() }.joinToString(" · ")

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).aureliaPress(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(time, fontSize = 12.sp, fontWeight = FontWeight.W600,
            color = AppleTheme.colors.tertiaryLabel,
            modifier = Modifier.width(40.dp).padding(top = 12.dp))
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(AppleTheme.colors.card)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(typeColor))
            Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                Text(com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(event.title, event.type, ctxLabel),
                    fontSize = 14.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty())
                    Text(sub, fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun CalendarEventItem(
    event: CalendarItem,
    onClick: () -> Unit,
    onFilterByType: ((CalendarEventFilter) -> Unit)? = null
) {
    val ctxLabel = LocalContext.current
    val typeColor = eventTypeColor(event.type)
    val typeIcon = when (event.type) {
        CalendarItemType.BIRTHDAY -> Icons.Default.Cake
        CalendarItemType.CALL     -> Icons.Default.Phone
        CalendarItemType.MEETING  -> Icons.Default.Group
        CalendarItemType.GIFT     -> Icons.Default.CardGiftcard
        else                      -> Icons.Default.Event
    }
    val relatedText = event.links.mapNotNull { link ->
        when (link.targetType) {
            CalendarTargetType.CONTACT -> AppStateStore.getContact(link.targetId)?.let { "${it.firstName} ${it.lastName}" }
            CalendarTargetType.COMPANY -> AppStateStore.getCompany(link.targetId)?.name
            else -> null
        }
    }.joinToString(", ")
    val timeText = if (!event.startTime.isNullOrEmpty()) event.startTime + (if (!event.endTime.isNullOrEmpty()) " – ${event.endTime}" else "") else ""
    val sub = listOf(relatedText, timeText).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(typeColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(typeIcon, null, Modifier.size(20.dp), tint = typeColor) }
        Column(modifier = Modifier.weight(1f)) {
            Text(com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(event.title, event.type, ctxLabel), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty())
                Text(sub, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text(com.aistudio.socialsphere.crmlxb.utils.displayEventDate(event.startDate), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (event.type == CalendarItemType.BIRTHDAY) typeColor else AppleTheme.colors.secondaryLabel)
    }
}

// Палитра аватаров — общая AureliaAvatars (ui/theme/AureliaComponents.kt).

// Цвет точки фильтр-чипа = цвет типа события (как в макете). «Все» — без точки.
private fun calFilterDot(f: CalendarEventFilter): Color? = when (f) {
    CalendarEventFilter.ALL       -> null
    CalendarEventFilter.MEETINGS  -> eventTypeColor(CalendarItemType.MEETING)
    CalendarEventFilter.BIRTHDAYS -> eventTypeColor(CalendarItemType.BIRTHDAY)
    CalendarEventFilter.CALLS     -> eventTypeColor(CalendarItemType.CALL)
    CalendarEventFilter.GIFTS     -> eventTypeColor(CalendarItemType.GIFT)
    CalendarEventFilter.IMPORTANT -> Color(0xFFC45D34)
}

/**
 * Строка таймлайн-ленты (макет Aurelia): слева гуттер с вертикальной линией и
 * цветной точкой типа, справа — отдельная карточка события (тип-лейбл + время,
 * заголовок, аватар+имя участника). Линии соседних строк визуально соединяются.
 */
@Composable
fun CalendarTimelineRow(event: CalendarItem, isLast: Boolean, isToday: Boolean = false, onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
    val typeColor = eventTypeColor(event.type)
    val firstContact = event.links.firstOrNull { it.targetType == CalendarTargetType.CONTACT }
        ?.let { AppStateStore.getContact(it.targetId) }
    val personName = event.links.mapNotNull { link ->
        when (link.targetType) {
            CalendarTargetType.CONTACT -> AppStateStore.getContact(link.targetId)?.let { "${it.firstName} ${it.lastName}".trim() }
            CalendarTargetType.COMPANY -> AppStateStore.getCompany(link.targetId)?.name
            else -> null
        }
    }.joinToString(", ")
    val time = if (!event.startTime.isNullOrEmpty())
        event.startTime + (if (!event.endTime.isNullOrEmpty()) " – ${event.endTime}" else "")
    else ""

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Гуттер 36px (точно по макету): вертикальная линия + точка типа 14px
        // с кольцом 4px цвета фона. Линия по прототипу — градиент акцент→золото:
        // сегодняшняя группа тонируется акцентом, дальше — приглушённое золото.
        val lineColor = if (isToday) AppleTheme.colors.brand.copy(alpha = 0.55f)
                        else AppleTheme.colors.orange.copy(alpha = 0.30f)
        Box(Modifier.width(36.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight()
                .background(lineColor))
            if (isToday) {
                // Сегодня: пульс (au-pulse 2.6s) + второе кольцо-ореол
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPulseDot(
                    color = typeColor, size = 14.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                        .border(1.dp, typeColor.copy(alpha = 0.18f), CircleShape)
                )
            } else {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(14.dp)
                    .clip(CircleShape).background(typeColor)
                    .border(4.dp, AppleTheme.colors.groupedBackground, CircleShape))
            }
        }
        Card(
            onClick   = onClick,
            modifier  = Modifier.weight(1f).padding(start = 14.dp, bottom = 12.dp),
            shape     = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(event.type.label(ctxLabel).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.08.em, color = typeColor, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (time.isNotEmpty())
                        Text(time, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label)
                }
                Spacer(Modifier.height(5.dp))
                Text(com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(event.title, event.type, ctxLabel),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (personName.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (firstContact != null) {
                            Box(Modifier.size(24.dp).clip(CircleShape)
                                .background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.brushFor(firstContact.id)),
                                contentAlignment = Alignment.Center) {
                                Text((firstContact.firstName.firstOrNull()?.toString() ?: "") +
                                     (firstContact.lastName.firstOrNull()?.toString() ?: ""),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Text(personName, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

