package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.effectiveDate
import com.aistudio.socialsphere.crmlxb.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun CalendarViewMode.title(context: android.content.Context): String = when (this) {
    CalendarViewMode.TODAY -> context.getString(R.string.cal_mode_today)
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

@OptIn(ExperimentalMaterial3Api::class)
private fun calFilterDot(f: CalendarEventFilter): Color? = when (f) {
    CalendarEventFilter.BIRTHDAYS -> Color(0xFFFF2D55)
    CalendarEventFilter.MEETINGS  -> Color(0xFF34C759)
    CalendarEventFilter.CALLS     -> Color(0xFF5B53D6)
    CalendarEventFilter.GIFTS     -> Color(0xFFFF9500)
    else -> null
}

@Composable
private fun CalFilterChip(label: String, active: Boolean, dot: Color?, onClick: () -> Unit) {
    Row(
        Modifier.height(30.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp))
            .background(if (active) AppleTheme.colors.brand else AppleTheme.colors.card)
            .clickable { onClick() }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (dot != null && !active) Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) Color.White else AppleTheme.colors.label)
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
                Text(stringResource(R.string.cal_title), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = AppleTheme.colors.label)
                Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x1F767680)).clickable { onNavigateToCreateCalendarItem() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, stringResource(R.string.cal_add_event), tint = AppleTheme.colors.brand, modifier = Modifier.size(21.dp))
                }
            }
            // Сегмент-контрол видов (равные сегменты)
            Row(
                modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)).background(Color(0x1F767680)).padding(2.dp)
            ) {
                val orderedModes = listOf(CalendarViewMode.LIST, CalendarViewMode.TODAY, CalendarViewMode.WEEK, CalendarViewMode.MONTH)
                orderedModes.forEach { mode ->
                    val isSelected = selectedMode == mode
                    Box(
                        modifier = Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)).background(if (isSelected) AppleTheme.colors.card else Color.Transparent).clickable { selectedMode = mode }.padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(mode.title(ctxLabel), fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, color = if (isSelected) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel)
                    }
                }
            }
            // Чипы типов с цветными точками
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { filter ->
                    CalFilterChip(filter.title(ctxLabel), selectedFilter == filter, calFilterDot(filter)) { selectedFilter = filter }
                }
            }

            // События после фильтра типа и скрытых типов — общие для списка и сетки
            val visibleEvents by remember {
                derivedStateOf {
                    val hiddenTypes = AppSettings.calendarHiddenTypes.value
                    allEvents.filter { event ->
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
            // чтобы смена языка пересчитала группировку
            val strToday      = stringResource(R.string.cal_today)
            val strTomorrow   = stringResource(R.string.cal_tomorrow)
            val strLater      = stringResource(R.string.cal_later)
            val strNearest    = stringResource(R.string.cal_nearest)
            val strNext7Days  = stringResource(R.string.cal_next7days)
            val strMonthEvents = stringResource(R.string.cal_month_events)

            // Пересчёт только при изменении фильтра, режима, данных или языка —
            // не на каждой рекомпозиции (derivedStateOf отслеживает snapshot-состояния)
            val groupedEvents by remember(strToday, strTomorrow, strLater, strNearest, strNext7Days, strMonthEvents) {
                derivedStateOf {
                    val filteredEvents = visibleEvents

                    val todayDate    = java.time.LocalDate.now().toString()
                    val tomorrowDate = java.time.LocalDate.now().plusDays(1).toString()
                    val weekEnd      = java.time.LocalDate.now().plusDays(7).toString()

                    val grouped = mutableMapOf<String, List<CalendarItem>>()
                    if (selectedMode == CalendarViewMode.TODAY) {
                        val todayEvents = filteredEvents.filter { it.effectiveDate() == todayDate }
                        if (todayEvents.isNotEmpty()) {
                            grouped[strToday] = todayEvents
                        } else {
                            grouped[strNearest] = filteredEvents.sortedBy { it.effectiveDate() }.take(5)
                        }
                    } else if (selectedMode == CalendarViewMode.LIST) {
                        val today    = filteredEvents.filter { it.effectiveDate() == todayDate }
                        val tomorrow = filteredEvents.filter { it.effectiveDate() == tomorrowDate }
                        val later    = filteredEvents.filter { it.effectiveDate() > tomorrowDate }.sortedBy { it.effectiveDate() }
                        if (today.isNotEmpty())    grouped[strToday] = today
                        if (tomorrow.isNotEmpty()) grouped[strTomorrow] = tomorrow
                        if (later.isNotEmpty())    grouped[strLater] = later
                    } else if (selectedMode == CalendarViewMode.WEEK) {
                        val weekEvents = filteredEvents.filter { it.effectiveDate() in todayDate..weekEnd }.sortedBy { it.effectiveDate() }
                        if (weekEvents.isNotEmpty()) grouped[strNext7Days] = weekEvents
                        else grouped[strNext7Days] = emptyList()
                    } else if (selectedMode == CalendarViewMode.MONTH) {
                        val monthEnd = java.time.LocalDate.now().plusMonths(1).toString()
                        val monthEvents = filteredEvents.filter { it.effectiveDate() in todayDate..monthEnd }.sortedBy { it.effectiveDate() }
                        grouped[strMonthEvents] = monthEvents
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
            } else if (groupedEvents.isEmpty() || groupedEvents.all { it.value.isEmpty() }) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.cal_no_events_found), color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    groupedEvents.forEach { (header, evItems) ->
                        item(key = "h_$header") {
                            Text(header.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 8.dp))
                        }
                        item(key = "c_$header") {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                                elevation = CardDefaults.cardElevation(1.dp)
                            ) {
                                Column {
                                    evItems.forEachIndexed { i, event ->
                                        CalendarEventItem(
                                            event          = event,
                                            onClick        = { onNavigateToCalendarItem(event.id) },
                                            onFilterByType = { filter -> selectedFilter = filter }
                                        )
                                        if (i < evItems.lastIndex) com.aistudio.socialsphere.crmlxb.ui.theme.AppleDivider(70.dp)
                                    }
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
                    java.time.LocalDate.parse(ev.startDate.take(10))
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
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
          Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
        // ── Шапка: ← Месяц Год → ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.cal_prev_month), tint = AppleTheme.colors.brand)
            }
            Text(
                "${monthNames[month.monthValue - 1]} ${month.year}",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = AppleTheme.colors.label
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.cal_next_month), tint = AppleTheme.colors.brand)
            }
        }

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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
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
                        modifier = Modifier.weight(1f).height(46.dp),
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
                                                when (ev.type) {
                                                    CalendarItemType.BIRTHDAY -> Color(0xFFFF2D55)
                                                    CalendarItemType.MEETING  -> Color(0xFF34C759)
                                                    CalendarItemType.CALL     -> Color(0xFF5B53D6)
                                                    CalendarItemType.GIFT     -> Color(0xFFFF9500)
                                                    else                      -> AppleTheme.colors.brand
                                                }
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
        }

        Spacer(Modifier.height(16.dp))

        // ── События выбранного дня ──
        val selEvents = eventsByDay[selectedDay.toString()].orEmpty()
        Text(
            "${selectedDay.dayOfMonth} ${monthNames[selectedDay.monthValue - 1].lowercase()}" +
                if (selEvents.isEmpty()) " " + stringResource(R.string.cal_no_events_day) else "",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = AppleTheme.colors.label,
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

@Composable
fun CalendarEventItem(
    event: CalendarItem,
    onClick: () -> Unit,
    onFilterByType: ((CalendarEventFilter) -> Unit)? = null
) {
    val ctxLabel = LocalContext.current
    val typeColor = when (event.type) {
        CalendarItemType.BIRTHDAY -> Color(0xFFFF2D55)
        CalendarItemType.CALL     -> Color(0xFF5B53D6)
        CalendarItemType.MEETING  -> Color(0xFF34C759)
        CalendarItemType.GIFT     -> Color(0xFFFF9500)
        else                      -> AppleTheme.colors.brand
    }
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
        Text(event.startDate, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (event.type == CalendarItemType.BIRTHDAY) typeColor else AppleTheme.colors.secondaryLabel)
    }
}

