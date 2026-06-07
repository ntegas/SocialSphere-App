package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

data class DemoContact(val name: String, val company: String, val position: String, val relType: String, val importance: String, val isRecentlyAdded: Boolean = false)
data class DemoEvent(val id: String, val title: String, val text: String, val date: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val colorIndex: Int = 0)

private val tempColors = listOf(Color(0xFFE8593C), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF6452D8))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val today = LocalDate.now()

    // ── Global search state ───────────────────────────────────
    var searchQuery   by remember { mutableStateOf("") }
    var searchActive  by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val searchResults by remember {
        derivedStateOf {
            if (searchQuery.length >= 2) SearchEngine.globalSearch(searchQuery, limit = 15)
            else emptyList()
        }
    }

    // ── Data for dashboard ────────────────────────────────────
    val upcomingEvents by remember {
        derivedStateOf {
            AppStateStore.calendarItems
                .filter { it.status == CalendarItemStatus.ACTIVE }
                .sortedBy { it.startDate }
                .take(10)
                .map { event ->
                    val icon = when (event.type) {
                        CalendarItemType.BIRTHDAY -> Icons.Default.Cake
                        CalendarItemType.CALL     -> Icons.Default.Phone
                        CalendarItemType.MEETING  -> Icons.Default.Group
                        CalendarItemType.GIFT     -> Icons.Default.CardGiftcard
                        CalendarItemType.TASK     -> Icons.Default.CheckCircle
                        else                      -> Icons.Default.Event
                    }
                    val colorIdx = when (event.type) { CalendarItemType.BIRTHDAY -> 0; CalendarItemType.MEETING -> 2; CalendarItemType.GIFT -> 1; else -> 3 }
                    val target = event.links.mapNotNull { link ->
                        if (link.targetType == CalendarTargetType.CONTACT) AppStateStore.getContact(link.targetId)?.let { "${it.firstName} ${it.lastName}" }
                        else if (link.targetType == CalendarTargetType.COMPANY) AppStateStore.getCompany(link.targetId)?.name
                        else null
                    }.joinToString(", ")
                    DemoEvent(event.id, event.title, target, event.startDate, icon, colorIdx)
                }
        }
    }

    val needAttention by remember {
        derivedStateOf {
            AppStateStore.contacts
                .filter { it.importanceLevel != ImportanceLevel.NORMAL || it.connectionLevel == ConnectionLevel.CLOSE }
                .sortedByDescending { when (it.importanceLevel) { ImportanceLevel.KEY -> 3; ImportanceLevel.IMPORTANT -> 2; else -> 1 } }
                .take(5)
                .map { c ->
                    val compRel = c.companyRelations.firstOrNull { it.isPrimary } ?: c.companyRelations.firstOrNull()
                    val companyName = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
                    DemoContact("${c.firstName} ${c.lastName}".trim(), companyName, compRel?.position ?: "", c.relationshipType.name, c.importanceLevel.name)
                }
        }
    }

    val recentlyAdded by remember {
        derivedStateOf {
            AppStateStore.contacts.sortedByDescending { it.createdAt }.take(6).map { c ->
                val compRel = c.companyRelations.firstOrNull { it.isPrimary } ?: c.companyRelations.firstOrNull()
                DemoContact("${c.firstName} ${c.lastName}".trim(), compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: "", compRel?.position ?: "", c.relationshipType.name, c.importanceLevel.name, true)
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
                            Text("Socialsphere", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text(
                                today.format(DateTimeFormatter.ofPattern("d MMMM, EEEE")),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("home_search_input"),
                            placeholder = { Text("Поиск людей, компаний…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor   = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor   = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                        LaunchedEffect(searchActive) { if (searchActive) focusRequester.requestFocus() }
                    }
                },
                navigationIcon = {
                    if (searchActive) {
                        IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                            Icon(Icons.Default.ArrowBack, "Назад")
                        }
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }, modifier = Modifier.testTag("home_settings_button")) {
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // ── SEARCH RESULTS OVERLAY ────────────────────────
            if (searchActive && searchQuery.length >= 2) {
                GlobalSearchResults(
                    results  = searchResults,
                    query    = searchQuery,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // ── DASHBOARD ─────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                ) {
                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard(Modifier.weight(1f), Icons.Outlined.People,        statsContacts.toString(),  "Контакты",  MaterialTheme.colorScheme.primaryContainer,   MaterialTheme.colorScheme.onPrimaryContainer)
                        StatCard(Modifier.weight(1f), Icons.Outlined.Business,      statsCompanies.toString(), "Компании",  MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        StatCard(Modifier.weight(1f), Icons.Outlined.CalendarToday, statsEvents.toString(),    "События",   MaterialTheme.colorScheme.tertiaryContainer,  MaterialTheme.colorScheme.onTertiaryContainer)
                    }

                    // Upcoming events
                    if (upcomingEvents.isNotEmpty()) {
                        SectionLabel("Ближайшее", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(upcomingEvents) { e -> EventCard(e) }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Contacts needing attention
                    if (needAttention.isNotEmpty()) {
                        SectionLabel("Актуальные контакты", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            needAttention.forEachIndexed { idx, contact -> ActiveContactRow(contact, idx) }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Recently added
                    if (recentlyAdded.isNotEmpty()) {
                        SectionLabel("Недавно добавленные", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(recentlyAdded) { c -> RecentContactCard(c) }
                        }
                    }

                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

// ─── Global search results ────────────────────────────────────
@Composable
private fun GlobalSearchResults(
    results: List<SearchResult>,
    query: String,
    modifier: Modifier = Modifier
) {
    if (results.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.SearchOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Text("Ничего не найдено по «$query»", color = MaterialTheme.colorScheme.secondary)
            }
        }
        return
    }

    val contacts  = results.filterIsInstance<SearchResult.ContactResult>()
    val companies = results.filterIsInstance<SearchResult.CompanyResult>()

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Contacts section
        if (contacts.isNotEmpty()) {
            item {
                Text("Контакты", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            items(contacts) { r ->
                SearchContactRow(r)
            }
        }
        // Companies section
        if (companies.isNotEmpty()) {
            item {
                Text("Компании", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(companies) { r ->
                SearchCompanyRow(r)
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun SearchContactRow(result: SearchResult.ContactResult) {
    val c = result.contact
    val compRel = c.companyRelations.firstOrNull { it.isPrimary } ?: c.companyRelations.firstOrNull()
    val company = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = SocialShape.Medium,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    (c.firstName.firstOrNull()?.toString() ?: "") + (c.lastName.firstOrNull()?.toString() ?: ""),
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(Modifier.weight(1f)) {
                Text("${c.firstName} ${c.lastName}".trim(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                val sub = listOf(company, compRel?.position).filter { !it.isNullOrEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            // Match field badge
            Surface(
                shape  = SocialShape.Full,
                color  = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            ) {
                Text(result.matchField, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun SearchCompanyRow(result: SearchResult.CompanyResult) {
    val c = result.company
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = SocialShape.Medium,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Business, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(c.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(c.industry.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Surface(shape = SocialShape.Full, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                Text(result.matchField, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

// ─── Dashboard composables ────────────────────────────────────
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun StatCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, bg: Color, fg: Color) {
    Card(modifier = modifier.height(88.dp), shape = SocialShape.Card, colors = CardDefaults.cardColors(containerColor = bg), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, Modifier.size(20.dp), tint = fg)
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = fg)
                Text(label, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EventCard(event: DemoEvent) {
    val accentColor = tempColors.getOrElse(event.colorIndex) { MaterialTheme.colorScheme.primary }
    Card(modifier = Modifier.width(200.dp), shape = SocialShape.Card, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.15f)), Alignment.Center) {
                Icon(event.icon, null, Modifier.size(20.dp), tint = accentColor)
            }
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (event.text.isNotEmpty()) Text(event.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(event.date, style = MaterialTheme.typography.labelSmall, color = accentColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ActiveContactRow(contact: DemoContact, index: Int) {
    val importanceColor = when (contact.importance) { "KEY" -> MaterialTheme.colorScheme.error; "IMPORTANT" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
    Card(modifier = Modifier.fillMaxWidth(), shape = SocialShape.Medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                Text(contact.name.take(1), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOf(contact.company, contact.position).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(importanceColor))
        }
    }
}

@Composable
private fun RecentContactCard(contact: DemoContact) {
    val bg = MaterialTheme.colorScheme.primaryContainer
    Card(modifier = Modifier.width(96.dp), shape = SocialShape.Large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(bg), Alignment.Center) {
                Text(contact.name.take(1), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(contact.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (contact.company.isNotEmpty()) Text(contact.company, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ColorScheme.textInputVariant() = if (AppSettings.isDarkTheme.value) outline.copy(alpha = 0.4f) else outline.copy(alpha = 0.2f)
