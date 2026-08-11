package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.platform.LocalContext
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
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

// SearchEngine.matchField приходит как стабильный ASCII-ключ (см. SearchEngine.kt,
// аудит хардкода 2026-07-22) — здесь резолвится в локализованную подпись чипа.
// Ветка else покрывает значения из MessengerType/PersonalDetailCategory.labelKey()
// (уже в основном language-neutral имена собственные вроде "Telegram"/"VK").
@Composable
private fun searchMatchFieldLabel(key: String): String = when (key) {
    "name" -> stringResource(R.string.srch_field_name)
    "surname" -> stringResource(R.string.srch_field_surname)
    "patronymic" -> stringResource(R.string.srch_field_patronymic)
    "nickname" -> stringResource(R.string.srch_field_nickname)
    "phone" -> stringResource(R.string.srch_field_phone)
    "email" -> stringResource(R.string.srch_field_email)
    "company" -> stringResource(R.string.srch_field_company)
    "position" -> stringResource(R.string.srch_field_position)
    "profession" -> stringResource(R.string.srch_field_profession)
    "department" -> stringResource(R.string.srch_field_department)
    "city" -> stringResource(R.string.srch_field_city)
    "country" -> stringResource(R.string.srch_field_country)
    "district" -> stringResource(R.string.srch_field_district)
    "note" -> stringResource(R.string.srch_field_note)
    "gift" -> stringResource(R.string.srch_field_gift)
    "tag" -> stringResource(R.string.srch_field_tag)
    "group" -> stringResource(R.string.srch_field_group)
    "type" -> stringResource(R.string.srch_field_type)
    "next_step" -> stringResource(R.string.srch_field_next_step)
    "company_name" -> stringResource(R.string.srch_field_company_name)
    "industry" -> stringResource(R.string.srch_field_industry)
    "description" -> stringResource(R.string.srch_field_description)
    "website" -> stringResource(R.string.srch_field_website)
    "employee" -> stringResource(R.string.srch_field_employee)
    else -> key
}

// ─── Local data wrappers ──────────────────────────────────────
private data class HomeContact(
    val id: String,
    val name: String,
    val company: String,
    val position: String,
    val daysSince: Long? = null,
    val overdueLabel: String? = null,
    // «Недавно добавленные» раньше показывали ТОЛЬКО компанию/должность — если
    // ни того ни другого нет (частый случай для только что созданного контакта),
    // карточка не показывала вообще ничего под именем, хотя тип отношений уже
    // задан (владелец: «после создания не отображается в ленте тип отношений»).
    val relationshipLabel: String = ""
)

private data class HomeEvent(
    val id: String,
    val title: String,
    val subtitle: String,
    val date: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val colorIndex: Int = 0,
    val type: CalendarItemType = CalendarItemType.CUSTOM
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToContact: (String) -> Unit = {},
    onNavigateToCompany: (String) -> Unit = {},
    onNavigateToCalendarItem: (String) -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState  = rememberScrollState()
    val ctxLabel = LocalContext.current
    var expandBirthdayList  by remember { mutableStateOf(false) }
    // Y-позиция блока «Нужно связаться» внутри скролл-колонки (для счётчика ⚠️)
    var needAttentionSectionY by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    // FIX: explicit Locale.getDefault() to avoid crash on locale change
    val today        = LocalDate.now()
    // ФИКС (2026-07-12, фидбэк владельца: день недели с маленькой буквы —
    // «суббота» вместо «Суббота»). EEEE у DateTimeFormatter в ru/el-локалях
    // возвращает stand-alone (словарную) форму дня недели из CLDR, которая
    // грамматически пишется со строчной буквы вне начала предложения — это
    // особенность локали, не баг форматтера (в en не проявляется, там дни
    // недели капитализируются всегда). Капитализируем именно день недели
    // (последний сегмент после ", "), не всю строку — она начинается с числа.
    val dateLabel    = remember(today) {
        try {
            val raw = today.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.getDefault()))
            val idx = raw.lastIndexOf(", ")
            if (idx >= 0 && idx + 2 < raw.length)
                raw.substring(0, idx + 2) + raw.substring(idx + 2).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            else raw
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
    val strInDays       = stringResource(R.string.home_in_days)
    val strToday        = stringResource(R.string.common_today)
    val strTomorrow     = stringResource(R.string.common_tomorrow)
    val strAddedOn      = stringResource(R.string.home_added_on)
    val strSlBirthdays    = stringResource(R.string.home_sl_birthdays)
    val strSlBirthdaysSub = stringResource(R.string.home_sl_birthdays_sub)
    val strSlFollowup     = stringResource(R.string.home_sl_followup)
    val strSlFollowupSub  = stringResource(R.string.home_sl_followup_sub)
    val strSlNew          = stringResource(R.string.home_sl_new)
    val strSlNewSub       = stringResource(R.string.home_sl_new_sub)

    // Без ключа-строки (в отличие от needAttention/smartLists ниже) — тело НЕ
    // вызывает stringResource напрямую (иконки/цвет по enum, subtitle — имена
    // контакта/компании, не локализованный текст). Если сюда когда-нибудь
    // добавят stringResource — добавить его же в remember(...), иначе вернётся
    // баг «лаг при смене языка» (аудит 2026-07-06, ср. HomeScreen §52 в базе знаний).
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
                                    ?.let { formatContactName(it, AppSettings.contactNameFormat.value) }
                            CalendarTargetType.COMPANY ->
                                AppStateStore.getCompany(link.targetId)?.name
                            else -> null
                        }
                    }.joinToString(", ")
                    HomeEvent(event.id, event.title, subtitle, event.effectiveDate(), icon, colorIdx, event.type)
                }
        }
    }

    // ── Нужно связаться — по ритму + дней без общения ────────
    val needAttention by remember {
        derivedStateOf {
            val today = java.time.LocalDate.now()

            // Rhythm → max days allowed without contact (CUSTOM использует
            // c.customRhythmDays, см. StaleContacts.rhythmDays)
            fun rhythmDays(c: Contact): Long? =
                StaleContacts.rhythmDays(c.communicationRhythm, c.customRhythmDays)?.toLong()

            AppStateStore.contacts
                .filter { c ->
                    // Only contacts with a tracked rhythm
                    rhythmDays(c) != null
                }
                .mapNotNull { c ->
                    val maxDays = rhythmDays(c) ?: return@mapNotNull null

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

                    val compRel = c.companyRelations.firstOrNull { it.isPrimary }
                        ?: c.companyRelations.firstOrNull()
                    val company = compRel?.companyId
                        ?.let { AppStateStore.getCompany(it)?.name } ?: ""

                    HomeContact(
                        id         = c.id,
                        name       = formatContactName(c, AppSettings.contactNameFormat.value),
                        company    = company,
                        position   = compRel?.position ?: "",
                        daysSince  = daysSince
                    ) to overdueDays
                }
                // Sort: most overdue first
                .sortedByDescending { it.second }
                .take(7)
                .map { it.first }
        }
    }

    // Тот же случай, что и upcomingEvents выше — без stringResource внутри,
    // ключ от языка не нужен; если появится локализованный текст — добавить сюда.
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
                    val relLabel = c.customRelationshipType?.takeIf { it.isNotBlank() }
                        ?: c.relationshipType.label(ctxLabel)
                    HomeContact(c.id, formatContactName(c, AppSettings.contactNameFormat.value),
                        company, compRel?.position ?: "", relationshipLabel = relLabel)
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
                        (parseFlexibleDate(item.startDate) ?: error("bad date"))
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

    val smartLists by remember(strToday, strTomorrow, strInDays, strAddedOn,
        strSlBirthdays, strSlBirthdaysSub, strSlFollowup, strSlFollowupSub,
        strSlNew, strSlNewSub) {
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
                        val bday = (parseFlexibleDate(dateStr) ?: error("bad date"))
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
                        formatContactName(contact, AppSettings.contactNameFormat.value),
                        comp, compRel?.position ?: "",
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
                    androidx.compose.ui.graphics.Color(0xFFC45D34), // Aurelia: терракот
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
                    HomeContact(c.id, formatContactName(c, AppSettings.contactNameFormat.value),
                        comp, compRel?.position ?: "",
                        overdueLabel = c.nextStep)
                }
                .take(5)
            if (followUpContacts.isNotEmpty()) {
                lists.add(SmartList(
                    "followup", strSlFollowup,
                    strSlFollowupSub,
                    Icons.Default.CheckCircle,
                    androidx.compose.ui.graphics.Color(0xFF2E8B6B), // Aurelia: малахит
                    followUpContacts.size, followUpContacts
                ))
            }

            lists
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
          if (searchActive) {
            TopAppBar(
                title = {
                    if (!searchActive) {
                        Column {
                            Text("Socialsphere",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge)
                            Text(dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = AppleTheme.colors.secondaryLabel)
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
                    containerColor = AppleTheme.colors.groupedBackground
                )
            )
          }
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
                    // Header по макету: заголовок + дата + круглые кнопки
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(dateLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppleTheme.colors.secondaryLabel)
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(stringResource(R.string.home_today), modifier = Modifier.padding(top = 3.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 6.dp)) {
                            // Сканер визитки (по макету: акцент-заливка)
                            AureliaCircleButton(Icons.Default.DocumentScanner, stringResource(R.string.scan_title),
                                style = AureliaCircleStyle.Filled, testTag = "home_scan_button") { onNavigateToScan() }
                            // Лупу прячем когда поиск активен — поле ввода уже в
                            // TopAppBar, иначе на экране две лупы.
                            if (!searchActive) AureliaCircleButton(Icons.Default.Search, stringResource(R.string.common_search)) { searchActive = true }
                            AureliaCircleButton(Icons.Default.Settings, stringResource(R.string.common_settings),
                                testTag = "home_settings_button") { onNavigateToSettings() }
                        }
                    }
                    // Поисковой капсулы нет — в макете Aurelia поиск с Главной идёт
                    // через круглую кнопку-лупу в шапке (дублирующая капсула убрана).
                    // Stats
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 🎂 Дни рождения в этом месяце (плитка — золото, по макету)
                        AureliaStatCard(
                            modifier = Modifier.weight(1f),
                            icon     = Icons.Outlined.CardGiftcard,
                            tile     = AppleTheme.colors.orange,
                            value    = birthdaysThisMonth.toString(),
                            label    = stringResource(R.string.home_counter_birthdays),
                            onClick  = {
                                expandBirthdayList = true
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(100)
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            }
                        )
                        // 📞 Встречи/звонки на неделе (плитка — акцент var(--ac), по макету:
                        // следует за выбранным акцентом, не фиксированный малахит)
                        AureliaStatCard(
                            modifier = Modifier.weight(1f),
                            icon     = Icons.Outlined.EventAvailable,
                            tile     = AppleTheme.colors.brand,
                            value    = meetingsThisWeek.toString(),
                            label    = stringResource(R.string.home_counter_meetings),
                            onClick  = { onNavigateToCalendar() }
                        )
                        // ⚠️ Просроченных контактов (терракот и число, и плитка) — раньше
                        // showOverdue нигде не читался, счётчик был виден всегда независимо
                        // от настройки (баг §36).
                        if (AppSettings.showOverdue.value) {
                            AureliaStatCard(
                                modifier   = Modifier.weight(1f),
                                icon       = Icons.Outlined.WarningAmber,
                                tile       = AppleTheme.colors.red,
                                value      = overdueCount.toString(),
                                label      = stringResource(R.string.home_counter_overdue),
                                valueColor = AppleTheme.colors.red,
                                onClick    = {
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
                    }

                    // Нужно связаться (по макету: заголовок + Все + сгруппированная карточка)
                    if (AppSettings.showOverdue.value && needAttention.isNotEmpty()) {
                        AureliaSectionHeader(
                            title      = stringResource(R.string.home_need_attention),
                            actionText = stringResource(R.string.common_see_all),
                            onAction   = onNavigateToContacts,
                            modifier   = Modifier
                                .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 8.dp)
                                .onGloballyPositioned { coords ->
                                    needAttentionSectionY = coords.positionInParent().y.roundToInt()
                                }
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R22,
                            colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Column {
                                needAttention.forEachIndexed { i, c ->
                                    HomeContactRow(c, onNavigateToContact) { onNavigateToContact(c.id) }
                                    if (i < needAttention.lastIndex) com.aistudio.socialsphere.crmlxb.ui.theme.AppleDivider(73.dp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Upcoming events — clickable
                    if (upcomingEvents.isNotEmpty()) {
                        AureliaSectionHeader(
                            title      = stringResource(R.string.home_upcoming),
                            actionText = stringResource(R.string.view_all),
                            onAction   = onNavigateToCalendar,
                            modifier   = Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 12.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 0.dp)
                        ) {
                            items(upcomingEvents, key = { it.id }) { e ->
                                HomeEventCard(e) { onNavigateToCalendarItem(e.id) }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Smart lists (по макету: заголовок + сгруппированная карточка)
                    if (smartLists.isNotEmpty()) {
                        AureliaSectionHeader(
                            title    = stringResource(R.string.home_smart_lists),
                            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 10.dp)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R22,
                            colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Column {
                                smartLists.forEachIndexed { i, list ->
                                    SmartListCard(
                                        smartList    = list,
                                        onNavigateTo = onNavigateToContact,
                                        forceExpand  = expandBirthdayList && list.id == "birthdays"
                                    )
                                    if (i < smartLists.lastIndex) com.aistudio.socialsphere.crmlxb.ui.theme.AppleDivider(61.dp)
                                }
                            }
                        }
                    }

                    // Recently added (по макету: заголовок + карточки 128px)
                    if (recentlyAdded.isNotEmpty()) {
                        AureliaSectionHeader(
                            title    = stringResource(R.string.home_recently_added),
                            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 10.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 0.dp)
                        ) {
                            items(recentlyAdded, key = { it.id }) { c ->
                                HomeRecentCard(c) { onNavigateToContact(c.id) }
                            }
                        }
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
                    tint = AppleTheme.colors.separator)
                Text(stringResource(R.string.home_nothing_found, query),
                    color = AppleTheme.colors.secondaryLabel)
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
                    color = AppleTheme.colors.brand)
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
                        containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val searchRowName = formatContactName(c, AppSettings.contactNameFormat.value)
                        AureliaAvatar(c.id, searchRowName,
                            size = 40.dp, fontSize = 13.sp)
                        Column(Modifier.weight(1f)) {
                            Text(searchRowName,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            val sub = listOf(company, compRel?.position)
                                .filter { !it.isNullOrEmpty() }.joinToString(" · ")
                            if (sub.isNotEmpty())
                                Text(sub, style = MaterialTheme.typography.bodySmall,
                                    color = AppleTheme.colors.secondaryLabel)
                        }
                        Surface(shape = SocialShape.Full,
                            color = AppleTheme.colors.brand.copy(alpha = 0.10f).copy(0.6f)) {
                            Text(searchMatchFieldLabel(r.matchField),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppleTheme.colors.brand,
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
                    color = AppleTheme.colors.brand)
            }
            items(companies, key = { it.company.id }) { r ->
                // FIX: clickable search result
                val c = r.company
                Card(
                    onClick   = { onNavigateToCompany(c.id) },
                    modifier  = Modifier.fillMaxWidth(),
                    shape     = SocialShape.Medium,
                    colors    = CardDefaults.cardColors(
                        containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R10)
                                .background(AppleTheme.colors.brand),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Business, null, Modifier.size(20.dp),
                                tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(c.industry.label(LocalContext.current),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel)
                        }
                        Surface(shape = SocialShape.Full,
                            color = AppleTheme.colors.fill.copy(0.6f)) {
                            Text(searchMatchFieldLabel(r.matchField),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppleTheme.colors.secondaryLabel,
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
// Круглые кнопки шапки, стат-карты и заголовки секций — общие компоненты
// дизайн-системы (AureliaCircleButton / AureliaStatCard / AureliaSectionHeader,
// ui/theme/AureliaComponents.kt). Локальных копий не заводить.

@Composable
private fun HomeEventCard(event: HomeEvent, onClick: () -> Unit) {
    // Акцент из темы (адаптируется к тёмной): ДР/подарок — золото, встреча/звонок —
    // малахит, остальное — бренд. Раньше брались жёсткие iOS-цвета (не для Aurelia).
    val accent = when (event.type) {
        CalendarItemType.BIRTHDAY, CalendarItemType.GIFT -> AppleTheme.colors.orange
        CalendarItemType.MEETING, CalendarItemType.CALL  -> AppleTheme.colors.green
        else                                             -> AppleTheme.colors.brand
    }
    // Фидбэк владельца 2026-07-03: было «День рождения, день рождения…» без
    // понимания ЧЬЁ — теперь ИМЯ человека (привязка события) — главный текст,
    // тип события — подпись. Карточки фиксированного размера, не «прыгают».
    val ctxTitle = androidx.compose.ui.platform.LocalContext.current
    val displayTitle = com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(event.title, event.type, ctxTitle)
    val mainText = event.subtitle.ifEmpty { displayTitle }
    val subText  = if (event.subtitle.isEmpty()) "" else displayTitle
    Card(
        onClick   = onClick,
        modifier  = Modifier.width(182.dp).height(110.dp),
        shape     = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge,
        colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(event.icon, null, Modifier.size(19.dp), tint = accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(mainText,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (subText.isNotEmpty()) subText else " ",
                        fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            // «14 июля» вместо сырого ISO «2026-07-14»
            val niceDate = try {
                java.time.LocalDate.parse(event.date)
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))
            } catch (e: Exception) {
                com.aistudio.socialsphere.crmlxb.utils.displayEventDate(event.date)
            }
            Text(niceDate, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = accent, maxLines = 1)
        }
    }
}

@Composable
private fun HomeContactRow(
    contact: HomeContact,
    onNavigateToContact: (String) -> Unit = {},
    onClick: () -> Unit
) {
    // Срочность по палитре Aurelia (общая палитра, без локальных копий). Точка
    // с пульсом раньше несла важность контакта (importanceLevel, убран из UI
    // 2026-07-23) — теперь та же срочность, что и градиент аватара ниже.
    val urgencyColor = when {
        contact.daysSince != null && contact.daysSince > 60 -> AppleTheme.colors.red
        contact.daysSince != null && contact.daysSince > 14 -> AppleTheme.colors.orange
        else                                                -> AppleTheme.colors.brand
    }
    val avaGrad = AureliaAvatars.urgencyBrush(contact.daysSince)
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        AureliaAvatar(contact.id, contact.name, size = 46.dp, fontSize = 17.sp, brush = avaGrad)
        Column(Modifier.weight(1f)) {
            Text(
                contact.name,
                fontSize   = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color      = AppleTheme.colors.label,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            val sub = listOf(contact.company, contact.position)
                .filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty())
                Text(
                    sub,
                    fontSize = 13.sp,
                    color    = AppleTheme.colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            // «Повторять уведомление о просроченных» (§36, решение владельца): помимо
            // повторного push (см. фикс NotificationReceiver) — усиленная подсветка
            // самого списка. Раньше настройка ни на что не влияла.
            if (AppSettings.repeatOverdueVisually.value && contact.daysSince != null) {
                Text(
                    stringResource(R.string.home_overdue_days_badge, contact.daysSince),
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color      = AppleTheme.colors.red,
                    modifier   = Modifier.padding(top = 2.dp)
                )
            }
        }
        // Быстрая отметка «связались сегодня» — убирает контакт из списка
        // без похода в заметки/календарь (ответ на вопрос владельца: список
        // не должен висеть вечно, если общение было вне приложения).
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                .clickable { AppStateStore.markContactedNow(contact.id) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.home_mark_contacted),
                tint = AppleTheme.colors.brand,
                modifier = Modifier.size(18.dp)
            )
        }
        // Точка с пульсом (au-pulse из макета); цвет несёт срочность (дни без
        // общения) — функция сохранена, анимация по прототипу.
        AureliaPulseDot(color = urgencyColor)
    }
}

@Composable
private fun HomeRecentCard(contact: HomeContact, onClick: () -> Unit) {
    Card(
        onClick   = onClick,
        // Фиксированная высота: строка должности опциональна, без неё карточка
        // была ниже и карточки в ряду «прыгали» по размеру.
        modifier  = Modifier.width(128.dp).height(140.dp),
        shape     = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18,
        colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            AureliaAvatar(contact.id, contact.name, size = 54.dp, fontSize = 20.sp)
            Text(contact.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            val role = contact.position.ifEmpty { contact.company }.ifEmpty { contact.relationshipLabel }
            if (role.isNotEmpty())
                Text(role, fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
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

    Column(Modifier.fillMaxWidth()) {
            // Header row — always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Box(
                    Modifier.size(34.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R9)
                        .background(smartList.color.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(smartList.icon, null, Modifier.size(18.dp), tint = smartList.color)
                }
                Column(Modifier.weight(1f)) {
                    Text(smartList.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label)
                    Text(smartList.subtitle, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel, modifier = Modifier.padding(top = 2.dp))
                }
                Text(smartList.count.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight,
                    null,
                    Modifier.size(18.dp),
                    tint = AppleTheme.colors.tertiaryLabel
                )
            }

            // Expanded contacts list
            if (expanded) {
                HorizontalDivider(
                    color     = AppleTheme.colors.separator,
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
                                    .background(AppleTheme.colors.brand),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    contact.name.take(1),
                                    fontWeight = FontWeight.Bold,
                                    fontSize   = 12.sp,
                                    color      = Color.White
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
                                        color    = AppleTheme.colors.secondaryLabel,
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
                                tint = AppleTheme.colors.separator
                            )
                        }
                    }
                }
            }
        }
}
