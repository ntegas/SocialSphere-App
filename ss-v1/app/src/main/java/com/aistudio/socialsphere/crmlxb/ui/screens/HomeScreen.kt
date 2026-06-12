package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.effectiveDate
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.ui.theme.*
import com.aistudio.socialsphere.crmlxb.utils.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ─── Local data wrappers ──────────────────────────────────────
private data class HomeContact(
    val id: String,
    val name: String,
    val company: String,
    val position: String,
    val importance: ImportanceLevel,
    val daysSince: Long? = null,
    val overdueLabel: String? = null
)

private data class HomeEvent(
    val id: String,
    val title: String,
    val subtitle: String,
    val date: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val colorIndex: Int = 0
)

private data class SmartList(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
    val count: Int,
    val contacts: List<HomeContact>
)

private val eventColors = listOf(
    Color(0xFFE53935),
    Color(0xFF1E88E5),
    Color(0xFF43A047),
    Color(0xFF8E24AA)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToContact: (String) -> Unit = {},
    onNavigateToCompany: (String) -> Unit = {},
    onNavigateToCalendarItem: (String) -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState  = rememberScrollState()
    var expandBirthdayList  by remember { mutableStateOf(false) }
    // Y-позиция блока «Нужно связаться» внутри скролл-колонки (для счётчика ⚠️)
    var needAttentionSectionY by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    // FIX: explicit Locale.getDefault() to avoid crash on locale change
    val today        = LocalDate.now()
    val dateLabel    = remember(today) {
        try {
            today.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.getDefault()))
        } catch (e: Exception) {
            today.toString()
        }
    }

    var searchQuery   by remember { mutableStateOf("") }
    var searchActive  by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val searchResults by remember {
        derivedStateOf {
            if (searchQuery.length >= 2)
                SearchEngine.globalSearch(searchQuery, limit = 15)
            else emptyList()
        }
    }

    // Dashboard data
    // stringResource нельзя вызывать внутри derivedStateOf —
    // захватываем локализованные строки здесь; они же — ключи remember,
    // чтобы смена языка пересчитала блоки
    val strNever        = stringResource(R.string.home_never_contacted)
    val strOverYear     = stringResource(R.string.home_over_year_ago)
    val strDaysAgo      = stringResource(R.string.home_days_ago)
    val strInDays       = stringResource(R.string.home_in_days)
    val strToday        = stringResource(R.string.common_today)
    val strTomorrow     = stringResource(R.string.common_tomorrow)
    val strNoNextStep   = stringResource(R.string.home_no_next_step_label)
    val strAddedOn      = stringResource(R.string.home_added_on)
    val strSlBirthdays    = stringResource(R.string.home_sl_birthdays)
    val strSlBirthdaysSub = stringResource(R.string.home_sl_birthdays_sub)
    val strSlFollowup     = stringResource(R.string.home_sl_followup)
    val strSlFollowupSub  = stringResource(R.string.home_sl_followup_sub)
    val strSlNoStep       = stringResource(R.string.home_sl_nonextstep)
    val strSlNoStepSub    = stringResource(R.string.home_sl_nonextstep_sub)
    val strSlNew          = stringResource(R.string.home_sl_new)
    val strSlNewSub       = stringResource(R.string.home_sl_new_sub)

    val upcomingEvents by remember {
        derivedStateOf {
            val todayStr = java.time.LocalDate.now().toString()
            val hiddenTypes = AppSettings.calendarHiddenTypes.value
            AppStateStore.calendarItems
                .filter { it.status == CalendarItemStatus.ACTIVE && it.type.name !in hiddenTypes }
                // ДР проецируются на ближайшее наступление — иначе
                // импортированные («1990-…») невидимы навсегда
                .filter { it.effectiveDate() >= todayStr }
                .sortedBy { it.effectiveDate() }
                .take(8)
                .mapNotNull { event ->
                    val icon = when (event.type) {
                        CalendarItemType.BIRTHDAY  -> Icons.Default.Cake
                        CalendarItemType.CALL      -> Icons.Default.Phone
                        CalendarItemType.MEETING   -> Icons.Default.Group
                        CalendarItemType.GIFT      -> Icons.Default.CardGiftcard
                        CalendarItemType.TASK      -> Icons.Default.CheckCircle
                        else                       -> Icons.Default.Event
                    }
                    val colorIdx = when (event.type) {
                        CalendarItemType.BIRTHDAY -> 0
                        CalendarItemType.MEETING  -> 1
                        CalendarItemType.GIFT     -> 2
                        else -> 3
                    }
                    val subtitle = event.links.mapNotNull { link ->
                        when (link.targetType) {
                            CalendarTargetType.CONTACT ->
                                AppStateStore.getContact(link.targetId)
                                    ?.let { "${it.firstName} ${it.lastName}".trim() }
                            CalendarTargetType.COMPANY ->
                                AppStateStore.getCompany(link.targetId)?.name
                            else -> null
                        }
                    }.joinToString(", ")
                    HomeEvent(event.id, event.title, subtitle, event.effectiveDate(), icon, colorIdx)
                }
        }
    }

    // ── Нужно связаться — по ритму + дней без общения ────────
    val needAttention by remember(strNever, strOverYear, strDaysAgo) {
        derivedStateOf {
            val today = java.time.LocalDate.now()

            // Rhythm → max days allowed without contact
            fun rhythmDays(r: CommunicationRhythm): Long? = when (r) {
                CommunicationRhythm.WEEKLY         -> 7L
                CommunicationRhythm.MONTHLY        -> 30L
                CommunicationRhythm.EVERY_3_MONTHS -> 90L
                CommunicationRhythm.EVERY_6_MONTHS -> 180L
                CommunicationRhythm.YEARLY         -> 365L
                else                               -> null  // NOT_TRACKED / CUSTOM — skip
            }

            AppStateStore.contacts
                .filter { c ->
                    // Only contacts with a tracked rhythm
                    rhythmDays(c.communicationRhythm) != null
                }
                .mapNotNull { c ->
                    val maxDays = rhythmDays(c.communicationRhythm) ?: return@mapNotNull null

                    // Last contact date — from model field or most recent note
                    val lastDate: java.time.LocalDate? = run {
                        val fromField = c.lastContactDate?.let {
                            try { java.time.LocalDate.parse(it.take(10)) } catch (e: Exception) { null }
                        }
                        val fromNotes = AppStateStore.notes
                            .filter { it.contactId == c.id }
                            .mapNotNull { n ->
                                try { java.time.LocalDate.parse(n.createdAt.take(10)) }
                                catch (e: Exception) { null }
                            }
                            .maxOrNull()
                        listOfNotNull(fromField, fromNotes).maxOrNull()
                    }

                    val daysSince: Long = if (lastDate != null)
                        java.time.temporal.ChronoUnit.DAYS.between(lastDate, today)
                    else
                        maxDays + 1  // Never contacted → treat as overdue

                    // Only show if overdue (days without contact > rhythm threshold)
                    if (daysSince < maxDays) return@mapNotNull null

                    val overdueDays = daysSince - maxDays   // how many days past deadline
                    val label = when {
                        lastDate == null -> strNever
                        daysSince > 365  -> strOverYear
                        else             -> String.format(strDaysAgo, daysSince)
                    }

                    val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                        ?: c.companyRelations.firstOrNull()
                    val company = compRel?.companyId
                        ?.let { AppStateStore.getCompany(it)?.name } ?: ""

                    HomeContact(
                        id           = c.id,
                        name         = "${c.firstName} ${c.lastName}".trim(),
                        company      = company,
                        position     = compRel?.position ?: "",
                        importance   = c.importanceLevel,
                        daysSince    = daysSince,
                        overdueLabel = label
                    ) to overdueDays
                }
                // Sort: most overdue first, then by importance
                .sortedWith(compareByDescending<Pair<HomeContact, Long>> { it.second }
                    .thenByDescending {
                        when (it.first.importance) {
                            ImportanceLevel.KEY       -> 3
                            ImportanceLevel.IMPORTANT -> 2
                            else -> 1
                        }
                    }
                )
                .take(7)
                .map { it.first }
        }
    }

    val recentlyAdded by remember {
        derivedStateOf {
            AppStateStore.contacts
                .sortedByDescending { it.createdAt }
                .take(6)
                .map { c ->
                    val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                        ?: c.companyRelations.firstOrNull()
                    val company = compRel?.companyId
                        ?.let { AppStateStore.getCompany(it)?.name } ?: ""
                    HomeContact(c.id, "${c.firstName} ${c.lastName}".trim(),
                        company, compRel?.position ?: "", c.importanceLevel)
                }
        }
    }

    val statsContacts  = AppStateStore.contacts.size
    val statsCompanies = AppStateStore.companies.size
    val statsEvents    = AppStateStore.calendarItems.count { it.status == CalendarItemStatus.ACTIVE }

    // ── Полезные счётчики (заменяют бесполезные контакты/компании/события)
    val birthdaysThisMonth by remember {
        derivedStateOf {
            val currentMonth = java.time.LocalDate.now().monthValue
            val currentYear  = java.time.LocalDate.now().year
            AppStateStore.calendarItems.count { item ->
                item.type == CalendarItemType.BIRTHDAY &&
                item.status == CalendarItemStatus.ACTIVE &&
                try {
                    // startDate может быть в формате "30 мая" или "2026-05-30"
                    val d = try {
                        java.time.LocalDate.parse(item.startDate)
                    } catch (e: Exception) {
                        null
                    }
                    d?.monthValue == currentMonth
                } catch (e: Exception) { false }
            }
        }
    }

    val meetingsThisWeek by remember {
        derivedStateOf {
            val today      = java.time.LocalDate.now()
            // Ровно 7 дней включая сегодня: сегодня + 6
            val endOfWeek  = today.plusDays(6)
            val todayStr   = today.toString()
            val endStr     = endOfWeek.toString()
            AppStateStore.calendarItems.count { item ->
                item.type in listOf(CalendarItemType.MEETING, CalendarItemType.CALL) &&
                item.status == CalendarItemStatus.ACTIVE &&
                item.startDate >= todayStr && item.startDate <= endStr
            }
        }
    }

    val overdueCount by remember { derivedStateOf { needAttention.size } }

    // ── Умные списки ──────────────────────────────────────────

    val smartLists by remember(strToday, strTomorrow, strInDays, strNoNextStep, strAddedOn,
        strSlBirthdays, strSlBirthdaysSub, strSlFollowup, strSlFollowupSub,
        strSlNoStep, strSlNoStepSub, strSlNew, strSlNewSub) {
        derivedStateOf {
            val today = java.time.LocalDate.now()
            val lists = mutableListOf<SmartList>()

            // 1. Дни рождения в ближайшие 30 дней
            val birthdayContacts = AppStateStore.calendarItems
                .filter { it.type == CalendarItemType.BIRTHDAY && it.status == CalendarItemStatus.ACTIVE }
                .mapNotNull { event ->
                    val dateStr = event.startDate
                    val contactLink = event.links.firstOrNull { it.targetType == CalendarTargetType.CONTACT }
                        ?: return@mapNotNull null
                    val contact = AppStateStore.getContact(contactLink.targetId)
                        ?: return@mapNotNull null
                    // Normalise birthday to current year
                    val daysUntil = try {
                        val bday = java.time.LocalDate.parse(dateStr)
                        val thisYear = bday.withYear(today.year)
                        val next = if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
                        java.time.temporal.ChronoUnit.DAYS.between(today, next)
                    } catch (e: Exception) { return@mapNotNull null }
                    if (daysUntil > 30) return@mapNotNull null
                    val compRel = contact.companyRelations.firstOrNull { it.isPrimary }
                        ?: contact.companyRelations.firstOrNull()
                    val comp = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
                    HomeContact(
                        contact.id,
                        "${contact.firstName} ${contact.lastName}".trim(),
                        comp, compRel?.position ?: "",
                        contact.importanceLevel,
                        daysSince    = daysUntil,
                        overdueLabel = when (daysUntil) {
                            0L   -> strToday
                            1L   -> strTomorrow
                            else -> String.format(strInDays, daysUntil)
                        }
                    )
                }
                .sortedBy { it.daysSince }
            if (birthdayContacts.isNotEmpty()) {
                lists.add(SmartList(
                    "birthdays", strSlBirthdays,
                    strSlBirthdaysSub,
                    Icons.Default.Cake,
                    androidx.compose.ui.graphics.Color(0xFFE53935),
                    birthdayContacts.size, birthdayContacts
                ))
            }

            // 2. Есть следующий шаг (nextStep заполнен)
            val followUpContacts = AppStateStore.contacts
                .filter { !it.nextStep.isNullOrBlank() }
                .map { c ->
                    val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                        ?: c.companyRelations.firstOrNull()
                    val comp = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
                    HomeContact(c.id, "${c.firstName} ${c.lastName}".trim(),
                        comp, compRel?.position ?: "", c.importanceLevel,
                        overdueLabel = c.nextStep)
                }
                .take(5)
            if (followUpContacts.isNotEmpty()) {
                lists.add(SmartList(
                    "followup", strSlFollowup,
                    strSlFollowupSub,
                    Icons.Default.CheckCircle,
                    androidx.compose.ui.graphics.Color(0xFF43A047),
                    followUpContacts.size, followUpContacts
                ))
            }

            // 3. Важные контакты без следующего шага
            val noNextStep = AppStateStore.contacts
                .filter {
                    it.importanceLevel != ImportanceLevel.NORMAL &&
                    it.nextStep.isNullOrBlank()
                }
                .map { c ->
                    val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                        ?: c.companyRelations.firstOrNull()
                    val comp = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
                    HomeContact(c.id, "${c.firstName} ${c.lastName}".trim(),
                        comp, compRel?.position ?: "", c.importanceLevel,
                        overdueLabel = strNoNextStep)
                }
                .take(5)
            if (noNextStep.isNotEmpty()) {
                lists.add(SmartList(
                    "nonextstep", strSlNoStep,
                    strSlNoStepSub,
                    Icons.Default.WarningAmber,
                    androidx.compose.ui.graphics.Color(0xFFFB8C00),
                    noNextStep.size, noNextStep
                ))
            }

            // 4. Новые контакты (за последние 7 дней)
            val weekAgo = today.minusDays(7).toString()
            val newContacts = AppStateStore.contacts
                .filter { it.createdAt.take(10) >= weekAgo }
                .sortedByDescending { it.createdAt }
                .map { c ->
                    val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                        ?: c.companyRelations.firstOrNull()
                    val comp = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
                    HomeContact(c.id, "${c.firstName} ${c.lastName}".trim(),
                        comp, compRel?.position ?: "", c.importanceLevel,
                        overdueLabel = String.format(strAddedOn, c.createdAt.take(10)))
                }
                .take(5)
            if (newContacts.isNotEmpty()) {
                lists.add(SmartList(
                    "new", strSlNew,
                    strSlNewSub,
                    Icons.Default.PersonAdd,
                    androidx.compose.ui.graphics.Color(0xFF1E88E5),
                    newContacts.size, newContacts
                ))
            }

            lists
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    if (!searchActive) {
                        Column {
                            Text("Socialsphere",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge)
                            Text(dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag("home_search_input"),
                            placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor   = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor   = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                        LaunchedEffect(searchActive) {
                            if (searchActive) focusRequester.requestFocus()
                        }
                    }
                },
                navigationIcon = {
                    if (searchActive) {
                        IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_close))
                        }
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(
                            onClick  = { searchActive = true },
                            modifier = Modifier.testTag("home_settings_button")
                        ) {
                            Icon(Icons.Default.Search, stringResource(R.string.common_search))
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, stringResource(R.string.common_settings))
                        }
                    } else if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.common_clear))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // ── SEARCH OVERLAY ────────────────────────────────
            if (searchActive && searchQuery.length >= 2) {
                HomeSearchResults(
                    results             = searchResults,
                    query               = searchQuery,
                    onNavigateToContact = onNavigateToContact,
                    onNavigateToCompany = onNavigateToCompany,
                    modifier            = Modifier.fillMaxSize()
                )
            } else {
                // ── DASHBOARD ─────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                ) {
                    // Stats
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 🎂 Дни рождения в этом месяце
                        HomeStatCard(
                            modifier  = Modifier.weight(1f),
                            icon      = Icons.Outlined.Cake,
                            value     = birthdaysThisMonth.toString(),
                            label     = stringResource(R.string.home_counter_birthdays),
                            bgColor   = MaterialTheme.colorScheme.primaryContainer,
                            fgColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick   = {
                                expandBirthdayList = true
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(100)
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            }
                        )
                        // 📞 Встречи/звонки на неделе
                        HomeStatCard(
                            modifier  = Modifier.weight(1f),
                            icon      = Icons.Outlined.CalendarToday,
                            value     = meetingsThisWeek.toString(),
                            label     = stringResource(R.string.home_counter_meetings),
                            bgColor   = MaterialTheme.colorScheme.secondaryContainer,
                            fgColor   = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick   = { onNavigateToCalendar() }
                        )
                        // ⚠️ Просроченных контактов
                        HomeStatCard(
                            modifier  = Modifier.weight(1f),
                            icon      = Icons.Outlined.NotificationsActive,
                            value     = overdueCount.toString(),
                            label     = stringResource(R.string.home_counter_overdue),
                            bgColor   = if (overdueCount > 0)
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.tertiaryContainer,
                            fgColor   = if (overdueCount > 0)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick   = {
                                if (overdueCount > 0) {
                                    // ТЗ: прокрутка к блоку «Нужно связаться»
                                    coroutineScope.launch {
                                        scrollState.animateScrollTo(needAttentionSectionY)
                                    }
                                } else {
                                    onNavigateToContacts()
                                }
                            }
                        )
                    }

                    // Upcoming events — clickable
                    if (upcomingEvents.isNotEmpty()) {
                        HomeSectionLabel(
                            stringResource(R.string.home_upcoming),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(upcomingEvents, key = { it.id }) { e ->
                                HomeEventCard(e) { onNavigateToCalendarItem(e.id) }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Нужно связаться
                    if (needAttention.isNotEmpty()) {
                        HomeSectionLabel(
                            stringResource(R.string.home_need_attention) + " · ${needAttention.size}",
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .onGloballyPositioned { coords ->
                                    needAttentionSectionY = coords.positionInParent().y.roundToInt()
                                }
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            needAttention.forEach { c ->
                                HomeContactRow(c, onNavigateToContact) { onNavigateToContact(c.id) }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Recently added — clickable
                    if (recentlyAdded.isNotEmpty()) {
                        HomeSectionLabel(
                            stringResource(R.string.home_recently_added),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(recentlyAdded, key = { it.id }) { c ->
                                HomeRecentCard(c) { onNavigateToContact(c.id) }
                            }
                        }
                    }

                    // Smart lists
                    if (smartLists.isNotEmpty()) {
                        HomeSectionLabel(
                            stringResource(R.string.home_smart_lists),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            smartLists.forEach { list ->
                                SmartListCard(
                                    smartList    = list,
                                    onNavigateTo = onNavigateToContact,
                                    forceExpand  = expandBirthdayList &&
                                        list.id == "birthdays"
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

// ─── Search results overlay ───────────────────────────────────
@Composable
private fun HomeSearchResults(
    results: List<SearchResult>,
    query: String,
    onNavigateToContact: (String) -> Unit,
    onNavigateToCompany: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (results.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.SearchOff, null, Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.home_nothing_found, query),
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
        return
    }

    val contacts  = results.filterIsInstance<SearchResult.ContactResult>()
    val companies = results.filterIsInstance<SearchResult.CompanyResult>()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (contacts.isNotEmpty()) {
            item {
                Text(stringResource(R.string.common_contacts),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            items(contacts, key = { it.contact.id }) { r ->
                // FIX: clickable search result
                val c = r.contact
                val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                    ?: c.companyRelations.firstOrNull()
                val company = compRel?.companyId
                    ?.let { AppStateStore.getCompany(it)?.name } ?: ""
                Card(
                    onClick   = { onNavigateToContact(c.id) },
                    modifier  = Modifier.fillMaxWidth(),
                    shape     = SocialShape.Medium,
                    colors    = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (c.firstName.firstOrNull()?.toString() ?: "") +
                                (c.lastName.firstOrNull()?.toString() ?: ""),
                                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${c.firstName} ${c.lastName}".trim(),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            val sub = listOf(company, compRel?.position)
                                .filter { !it.isNullOrEmpty() }.joinToString(" · ")
                            if (sub.isNotEmpty())
                                Text(sub, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary)
                        }
                        Surface(shape = SocialShape.Full,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(0.6f)) {
                            Text(r.matchField,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }

        if (companies.isNotEmpty()) {
            item {
                Text(stringResource(R.string.common_companies),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(companies, key = { it.company.id }) { r ->
                // FIX: clickable search result
                val c = r.company
                Card(
                    onClick   = { onNavigateToCompany(c.id) },
                    modifier  = Modifier.fillMaxWidth(),
                    shape     = SocialShape.Medium,
                    colors    = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Business, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(c.industry.label(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                        Surface(shape = SocialShape.Full,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(0.6f)) {
                            Text(r.matchField,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─── Dashboard components ─────────────────────────────────────
@Composable
private fun HomeSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier)
}

@Composable
private fun HomeStatCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    bgColor: Color,
    fgColor: Color,
    onClick: () -> Unit = {}
) {
    Card(
        onClick   = onClick,
        modifier  = modifier.height(88.dp),
        shape     = SocialShape.Card,
        colors    = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = fgColor)
            Column {
                Text(
                    value,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color      = fgColor
                )
                Text(
                    label,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = fgColor.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeEventCard(event: HomeEvent, onClick: () -> Unit) {
    val accent = eventColors.getOrElse(event.colorIndex) { MaterialTheme.colorScheme.primary }
    Card(
        onClick   = onClick,
        modifier  = Modifier.width(200.dp),
        shape     = SocialShape.Card,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center) {
                Icon(event.icon, null, Modifier.size(20.dp), tint = accent)
            }
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (event.subtitle.isNotEmpty())
                    Text(event.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(event.date, style = MaterialTheme.typography.labelSmall,
                    color = accent, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HomeContactRow(
    contact: HomeContact,
    onNavigateToContact: (String) -> Unit = {},
    onClick: () -> Unit
) {
    val importanceColor = when (contact.importance) {
        ImportanceLevel.KEY       -> MaterialTheme.colorScheme.error
        ImportanceLevel.IMPORTANT -> MaterialTheme.colorScheme.tertiary
        else                      -> MaterialTheme.colorScheme.primary
    }

    // Overdue color: red if >2x rhythm, orange if >1x
    val overdueColor = when {
        contact.daysSince != null && contact.daysSince > 60 -> MaterialTheme.colorScheme.error
        contact.daysSince != null && contact.daysSince > 14 -> MaterialTheme.colorScheme.tertiary
        else                                                 -> MaterialTheme.colorScheme.secondary
    }

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = SocialShape.Medium,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.name.take(1),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 18.sp,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    contact.name,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                val sub = listOf(contact.company, contact.position)
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty())
                    Text(
                        sub,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                // Overdue label — key visual from ТЗ
                if (!contact.overdueLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        contact.overdueLabel,
                        style      = MaterialTheme.typography.labelSmall,
                        color      = overdueColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(importanceColor))
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun HomeRecentCard(contact: HomeContact, onClick: () -> Unit) {
    Card(
        onClick   = onClick,
        modifier  = Modifier.width(96.dp),
        shape     = SocialShape.Large,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Text(contact.name.take(1), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(contact.name, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (contact.company.isNotEmpty())
                Text(contact.company, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ColorScheme.textInputVariant() =
    if (AppSettings.isDarkTheme.value) outline.copy(alpha = 0.4f)
    else outline.copy(alpha = 0.2f)

@Composable
private fun SmartListCard(
    smartList: SmartList,
    onNavigateTo: (String) -> Unit,
    forceExpand: Boolean = false
) {
    var expanded by remember { mutableStateOf(forceExpand) }
    LaunchedEffect(forceExpand) { if (forceExpand) expanded = true }

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = SocialShape.Card,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column {
            // Header row — always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(smartList.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(smartList.icon, null, Modifier.size(18.dp), tint = smartList.color)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        smartList.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        smartList.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Badge(containerColor = smartList.color.copy(alpha = 0.15f)) {
                    Text(
                        smartList.count.toString(),
                        color = smartList.color,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            }

            // Expanded contacts list
            if (expanded) {
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp
                )
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    smartList.contacts.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateTo(contact.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    contact.name.take(1),
                                    fontWeight = FontWeight.Bold,
                                    fontSize   = 12.sp,
                                    color      = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    contact.name,
                                    style      = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines   = 1,
                                    overflow   = TextOverflow.Ellipsis
                                )
                                if (contact.company.isNotEmpty())
                                    Text(
                                        contact.company,
                                        style    = MaterialTheme.typography.labelSmall,
                                        color    = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                            }
                            if (!contact.overdueLabel.isNullOrBlank())
                                Text(
                                    contact.overdueLabel,
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = smartList.color,
                                    fontWeight = FontWeight.Medium
                                )
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
