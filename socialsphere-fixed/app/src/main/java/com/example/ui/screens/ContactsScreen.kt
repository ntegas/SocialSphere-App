package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppStateStore
import com.example.model.*
import com.example.ui.theme.SocialShape
import com.example.utils.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun ContactsScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToContact: (String) -> Unit,
    onNavigateToCreateContact: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ── Search state ──────────────────────────────────────────
    var searchQuery   by remember { mutableStateOf("") }
    var searchActive  by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // ── View / sort ───────────────────────────────────────────
    var isGridView    by remember { mutableStateOf(false) }
    var sortOrder     by remember { mutableStateOf(ContactSortOrder.NAME_AZ) }
    var showSortSheet by remember { mutableStateOf(false) }

    // ── Active filters ────────────────────────────────────────
    var filterRelTypes    by remember { mutableStateOf(emptySet<RelationshipType>()) }
    var filterImportance  by remember { mutableStateOf(emptySet<ImportanceLevel>()) }
    var filterConnLevel   by remember { mutableStateOf(emptySet<ConnectionLevel>()) }
    var filterRhythm      by remember { mutableStateOf(emptySet<CommunicationRhythm>()) }
    var cityFilter        by remember { mutableStateOf("") }
    var showFilterSheet   by remember { mutableStateOf(false) }

    val hasActiveFilters = filterRelTypes.isNotEmpty() || filterImportance.isNotEmpty() ||
        filterConnLevel.isNotEmpty() || filterRhythm.isNotEmpty() || cityFilter.isNotBlank()

    // ── Filtered list (derivedStateOf = recompute only when deps change) ──
    val filteredContacts by remember {
        derivedStateOf {
            AppStateStore.contacts.applyContactFilters(
                query               = searchQuery,
                relationshipTypes   = filterRelTypes,
                importanceLevels    = filterImportance,
                connectionLevels    = filterConnLevel,
                communicationRhythms= filterRhythm,
                cityFilter          = cityFilter,
                sortOrder           = sortOrder
            )
        }
    }

    // ── Quick search suggestions (top-5 by score) ─────────────
    val suggestions by remember {
        derivedStateOf {
            if (searchQuery.length >= 2)
                SearchEngine.searchContacts(searchQuery).take(5)
            else emptyList()
        }
    }

    // ── Sort bottom sheet ─────────────────────────────────────
    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }, shape = SocialShape.Sheet) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сортировка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ContactSortOrder.values().forEach { order ->
                    val label = when (order) {
                        ContactSortOrder.NAME_AZ        -> "Имя А → Я"
                        ContactSortOrder.NAME_ZA        -> "Имя Я → А"
                        ContactSortOrder.RECENTLY_ADDED -> "Недавно добавленные"
                        ContactSortOrder.IMPORTANCE     -> "По важности"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { sortOrder = order; showSortSheet = false }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        if (sortOrder == order) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    if (order != ContactSortOrder.values().last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ── Filter bottom sheet ───────────────────────────────────
    if (showFilterSheet) {
        ContactFilterSheet(
            filterRelTypes    = filterRelTypes,
            filterImportance  = filterImportance,
            filterConnLevel   = filterConnLevel,
            filterRhythm      = filterRhythm,
            cityFilter        = cityFilter,
            onRelTypesChange  = { filterRelTypes = it },
            onImportanceChange= { filterImportance = it },
            onConnLevelChange = { filterConnLevel = it },
            onRhythmChange    = { filterRhythm = it },
            onCityChange      = { cityFilter = it },
            onClear           = {
                filterRelTypes = emptySet(); filterImportance = emptySet()
                filterConnLevel = emptySet(); filterRhythm = emptySet(); cityFilter = ""
            },
            onDismiss         = { showFilterSheet = false }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    if (!searchActive) {
                        Text("Контакты", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("contacts_search_input"),
                            placeholder = { Text("Имя, телефон, компания, город…") },
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
                            Icon(Icons.Default.ArrowBack, "Закрыть поиск")
                        }
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, "Поиск")
                        }
                    } else if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "Очистить")
                        }
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        BadgedBox(badge = {
                            if (hasActiveFilters) Badge(containerColor = MaterialTheme.colorScheme.error) {}
                        }) {
                            Icon(Icons.Default.FilterList, "Фильтры")
                        }
                    }
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.Default.Sort, "Сортировка")
                    }
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(if (isGridView) Icons.Default.FormatListBulleted else Icons.Default.GridView, "Вид")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateContact, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, "Добавить контакт")
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // ── Active filter chips strip ─────────────────────
            val activeChips = buildList {
                filterRelTypes.forEach  { add(it.label() to { filterRelTypes   = filterRelTypes   - it }) }
                filterImportance.forEach{ add(it.label() to { filterImportance = filterImportance - it }) }
                filterConnLevel.forEach { add(it.label() to { filterConnLevel  = filterConnLevel  - it }) }
                if (cityFilter.isNotBlank()) add("📍 $cityFilter" to { cityFilter = "" })
            }
            if (activeChips.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeChips.forEach { chip ->
                        val label  = chip.first
                        val remove = chip.second
                        InputChip(
                            selected  = true,
                            onClick   = remove,
                            label     = { Text(label, fontSize = 12.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                            shape     = SocialShape.Full
                        )
                    }
                }
            }

            // ── Results count ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (searchQuery.isBlank() && !hasActiveFilters) "${AppStateStore.contacts.size} контактов"
                    else "${filteredContacts.size} из ${AppStateStore.contacts.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                val sortLabel = when (sortOrder) {
                    ContactSortOrder.NAME_AZ        -> "А→Я"
                    ContactSortOrder.NAME_ZA        -> "Я→А"
                    ContactSortOrder.RECENTLY_ADDED -> "Новые"
                    ContactSortOrder.IMPORTANCE     -> "Важность"
                }
                TextButton(onClick = { showSortSheet = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(sortLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Default.UnfoldMore, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }

            // ── List / Grid ────────────────────────────────────
            if (filteredContacts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.SearchOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            if (searchQuery.isNotBlank()) "Ничего не найдено по «$searchQuery»"
                            else "Нет контактов по выбранным фильтрам",
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (hasActiveFilters) {
                            TextButton(onClick = {
                                filterRelTypes = emptySet(); filterImportance = emptySet()
                                filterConnLevel = emptySet(); filterRhythm = emptySet(); cityFilter = ""
                            }) { Text("Сбросить фильтры") }
                        }
                    }
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement   = Arrangement.spacedBy(10.dp),
                    contentPadding        = PaddingValues(16.dp),
                    modifier              = Modifier.fillMaxSize()
                ) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        ContactGridCard(contact = contact, highlight = searchQuery, onClick = { onNavigateToContact(contact.id) })
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding      = PaddingValues(16.dp),
                    modifier            = Modifier.fillMaxSize()
                ) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        ContactListCard(contact = contact, highlight = searchQuery, onClick = { onNavigateToContact(contact.id) })
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// FILTER BOTTOM SHEET
// ═══════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactFilterSheet(
    filterRelTypes: Set<RelationshipType>,
    filterImportance: Set<ImportanceLevel>,
    filterConnLevel: Set<ConnectionLevel>,
    filterRhythm: Set<CommunicationRhythm>,
    cityFilter: String,
    onRelTypesChange: (Set<RelationshipType>) -> Unit,
    onImportanceChange: (Set<ImportanceLevel>) -> Unit,
    onConnLevelChange: (Set<ConnectionLevel>) -> Unit,
    onRhythmChange: (Set<CommunicationRhythm>) -> Unit,
    onCityChange: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = SocialShape.Sheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Фильтры", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClear) { Text("Сбросить всё") }
            }

            // Relationship type
            FilterSection("Тип отношений") {
                listOf(RelationshipType.FAMILY, RelationshipType.FRIEND, RelationshipType.COLLEAGUE,
                       RelationshipType.CLIENT, RelationshipType.PARTNER, RelationshipType.ACQUAINTANCE).forEach { type ->
                    MultiSelectChip(type.label(), type in filterRelTypes) {
                        onRelTypesChange(if (type in filterRelTypes) filterRelTypes - type else filterRelTypes + type)
                    }
                }
            }

            // Importance
            FilterSection("Важность") {
                ImportanceLevel.values().forEach { level ->
                    MultiSelectChip(level.label(), level in filterImportance) {
                        onImportanceChange(if (level in filterImportance) filterImportance - level else filterImportance + level)
                    }
                }
            }

            // Connection level
            FilterSection("Уровень связи") {
                listOf(ConnectionLevel.CLOSE, ConnectionLevel.NORMAL, ConnectionLevel.WEAK, ConnectionLevel.NEW).forEach { lvl ->
                    MultiSelectChip(lvl.label(), lvl in filterConnLevel) {
                        onConnLevelChange(if (lvl in filterConnLevel) filterConnLevel - lvl else filterConnLevel + lvl)
                    }
                }
            }

            // Rhythm
            FilterSection("Ритм общения") {
                listOf(CommunicationRhythm.WEEKLY, CommunicationRhythm.MONTHLY,
                       CommunicationRhythm.EVERY_3_MONTHS, CommunicationRhythm.EVERY_6_MONTHS).forEach { r ->
                    MultiSelectChip(r.label(), r in filterRhythm) {
                        onRhythmChange(if (r in filterRhythm) filterRhythm - r else filterRhythm + r)
                    }
                }
            }

            // City
            FilterSection("Город") {
                OutlinedTextField(
                    value = cityFilter,
                    onValueChange = onCityChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Например: Афины") },
                    leadingIcon  = { Icon(Icons.Default.LocationCity, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (cityFilter.isNotEmpty()) IconButton(onClick = { onCityChange("") }) { Icon(Icons.Default.Clear, null, Modifier.size(16.dp)) }
                    },
                    singleLine = true,
                    shape = SocialShape.Small
                )
            }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Применить") }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun MultiSelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 13.sp) }, shape = SocialShape.Full)
}

// ═══════════════════════════════════════════════════════════
// CONTACT CARDS
// ═══════════════════════════════════════════════════════════
@Composable
private fun getContactVisuals(contact: Contact): Triple<String, String, String> {
    val compRel = contact.companyRelations.firstOrNull { it.isPrimary } ?: contact.companyRelations.firstOrNull()
    val company  = compRel?.companyId?.let { AppStateStore.getCompany(it) }?.name ?: ""
    val position = compRel?.position ?: ""
    val city     = AppStateStore.addresses.find { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }?.city ?: ""
    return Triple(company, position, city)
}

@Composable
fun ContactListCard(contact: Contact, highlight: String = "", onClick: () -> Unit) {
    val (company, position, city) = getContactVisuals(contact)
    val name = "${contact.firstName} ${contact.lastName}".trim()
    val ctx  = LocalContext.current

    val importanceTint = when (contact.importanceLevel) {
        ImportanceLevel.KEY       -> MaterialTheme.colorScheme.error
        ImportanceLevel.IMPORTANT -> MaterialTheme.colorScheme.tertiary
        else                      -> Color.Transparent
    }

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = SocialShape.Card,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier  = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = (contact.firstName.firstOrNull()?.toString() ?: "") +
                            (contact.lastName.firstOrNull()?.toString() ?: ""),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Main info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (importanceTint != Color.Transparent)
                        Box(Modifier.size(6.dp).clip(CircleShape).background(importanceTint))
                }
                val sub = listOf(company, position).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty())
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (city.isNotEmpty())
                    Text(city, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)

                // Relationship chip
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {},
                        label   = { Text(contact.relationshipType.label(), fontSize = 10.sp) },
                        shape   = SocialShape.Full,
                        modifier = Modifier.height(22.dp)
                    )
                    if (contact.communicationRhythm != CommunicationRhythm.NOT_TRACKED) {
                        AssistChip(
                            onClick = {},
                            label   = { Text(contact.communicationRhythm.label(), fontSize = 10.sp) },
                            shape   = SocialShape.Full,
                            modifier = Modifier.height(22.dp)
                        )
                    }
                }
            }

            // Quick actions
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallActionIcon(Icons.Outlined.Phone) {
                    val phone = contact.phones.find { it.isPrimary }?.number ?: contact.phones.firstOrNull()?.number
                    com.example.utils.ExternalActionHandler.openDialer(ctx, phone)
                }
                SmallActionIcon(Icons.Outlined.ChatBubbleOutline) {
                    val m = contact.messengers.find { it.isPrimary }
                    if (m != null) com.example.utils.ExternalActionHandler.openMessenger(ctx, m)
                    else com.example.utils.ExternalActionHandler.openSms(ctx, contact.phones.firstOrNull()?.number)
                }
            }
        }
    }
}

@Composable
fun ContactGridCard(contact: Contact, highlight: String = "", onClick: () -> Unit) {
    val (company, position, _) = getContactVisuals(contact)
    val name = "${contact.firstName} ${contact.lastName}".trim()
    val importanceTint = when (contact.importanceLevel) {
        ImportanceLevel.KEY       -> MaterialTheme.colorScheme.error
        ImportanceLevel.IMPORTANT -> MaterialTheme.colorScheme.tertiary
        else                      -> MaterialTheme.colorScheme.primaryContainer
    }

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth().height(156.dp),
        shape     = SocialShape.Card,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(importanceTint.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (contact.firstName.firstOrNull()?.toString() ?: "") + (contact.lastName.firstOrNull()?.toString() ?: ""),
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = importanceTint
                    )
                }
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (company.isNotEmpty())
                    Text(company, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                if (position.isNotEmpty())
                    Text(position, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(contact.relationshipType.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (contact.importanceLevel != ImportanceLevel.NORMAL)
                    Box(Modifier.size(7.dp).clip(CircleShape).background(importanceTint))
            }
        }
    }
}

@Composable
private fun SmallActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary) }
}

// kept for compat with HomeScreen usage
@Composable
fun ContactFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(16.dp))
}
