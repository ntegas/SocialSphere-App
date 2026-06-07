package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.*
import com.example.data.AppStateStore
import com.example.ui.theme.*
import com.example.utils.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ─── Local data wrappers ──────────────────────────────────────
private data class HomeContact(
    val id: String,
    val name: String,
    val company: String,
    val position: String,
    val importance: ImportanceLevel
)

private data class HomeEvent(
    val id: String,
    val title: String,
    val subtitle: String,
    val date: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val colorIndex: Int = 0
)

private val eventColors = listOf(
    Color(0xFFE53935),  // Birthday — red
    Color(0xFF1E88E5),  // Meeting  — blue
    Color(0xFF43A047),  // Gift     — green
    Color(0xFF8E24AA)   // Other    — purple
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToContact: (String) -> Unit = {},
    onNavigateToCompany: (String) -> Unit = {},
    onNavigateToCalendarItem: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState  = rememberScrollState()
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
    val upcomingEvents by remember {
        derivedStateOf {
            AppStateStore.calendarItems
                .filter { it.status == CalendarItemStatus.ACTIVE }
                .sortedBy { it.startDate }
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
                    HomeEvent(event.id, event.title, subtitle, event.startDate, icon, colorIdx)
                }
        }
    }

    val needAttention by remember {
        derivedStateOf {
            AppStateStore.contacts
                .filter {
                    it.importanceLevel != ImportanceLevel.NORMAL ||
                    it.connectionLevel == ConnectionLevel.CLOSE
                }
                .sortedByDescending {
                    when (it.importanceLevel) {
                        ImportanceLevel.KEY       -> 3
                        ImportanceLevel.IMPORTANT -> 2
                        else -> 1
                    }
                }
                .take(5)
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
                            placeholder = { Text("Поиск людей, компаний…") },
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
                            Icon(Icons.Default.ArrowBack, "Закрыть")
                        }
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(
                            onClick  = { searchActive = true },
                            modifier = Modifier.testTag("home_settings_button")
                        ) {
                            Icon(Icons.Default.Search, "Поиск")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, "Настройки")
                        }
                    } else if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "Очистить")
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
                        HomeStatCard(
                            Modifier.weight(1f),
                            Icons.Outlined.People,
                            statsContacts.toString(),
                            "Контакты",
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        HomeStatCard(
                            Modifier.weight(1f),
                            Icons.Outlined.Business,
                            statsCompanies.toString(),
                            "Компании",
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        HomeStatCard(
                            Modifier.weight(1f),
                            Icons.Outlined.CalendarToday,
                            statsEvents.toString(),
                            "События",
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    // Upcoming events — clickable
                    if (upcomingEvents.isNotEmpty()) {
                        HomeSectionLabel(
                            "Ближайшее",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(upcomingEvents) { e ->
                                HomeEventCard(e) { onNavigateToCalendarItem(e.id) }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Active contacts — clickable
                    if (needAttention.isNotEmpty()) {
                        HomeSectionLabel(
                            "Актуальные контакты",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            needAttention.forEach { c ->
                                HomeContactRow(c) { onNavigateToContact(c.id) }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Recently added — clickable
                    if (recentlyAdded.isNotEmpty()) {
                        HomeSectionLabel(
                            "Недавно добавленные",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(recentlyAdded) { c ->
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
                    tint = MaterialTheme.colorScheme.outlineVariant)
                Text("Ничего не найдено по «$query»",
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
                Text("Контакты",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            items(contacts) { r ->
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
                Text("Компании",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(companies) { r ->
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
    bg: Color,
    fg: Color
) {
    Card(modifier = modifier.height(88.dp), shape = SocialShape.Card,
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, Modifier.size(20.dp), tint = fg)
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black, color = fg)
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun HomeContactRow(contact: HomeContact, onClick: () -> Unit) {
    val importanceColor = when (contact.importance) {
        ImportanceLevel.KEY       -> MaterialTheme.colorScheme.error
        ImportanceLevel.IMPORTANT -> MaterialTheme.colorScheme.tertiary
        else                      -> MaterialTheme.colorScheme.primary
    }
    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = SocialShape.Medium,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Text(contact.name.take(1), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOf(contact.company, contact.position)
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty())
                    Text(sub, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(importanceColor))
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.outlineVariant)
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
