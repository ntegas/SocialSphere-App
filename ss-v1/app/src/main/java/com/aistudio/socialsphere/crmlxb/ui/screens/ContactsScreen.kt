@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.utils.*

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
    val ctxLabel = LocalContext.current
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
    var filterStatus      by remember { mutableStateOf(emptySet<ContactStatus>()) }
    var filterTag         by remember { mutableStateOf("") }
    var cityFilter        by remember { mutableStateOf("") }
    var showFilterSheet   by remember { mutableStateOf(false) }

    val hasActiveFilters = filterRelTypes.isNotEmpty() || filterImportance.isNotEmpty() ||
        filterConnLevel.isNotEmpty() || filterRhythm.isNotEmpty() ||
        filterStatus.isNotEmpty() ||
        cityFilter.isNotBlank() || filterTag.isNotBlank()

    // All unique tags across contacts for suggestion
    val allTags by remember {
        derivedStateOf {
            AppStateStore.contacts.flatMap { it.tags }.distinct().sorted()
        }
    }

    // ── Filtered list (derivedStateOf = recompute only when deps change) ──
    val filteredContacts by remember {
        derivedStateOf {
            AppStateStore.contacts.applyContactFilters(
                query               = searchQuery,
                relationshipTypes   = filterRelTypes,
                importanceLevels    = filterImportance,
                connectionLevels    = filterConnLevel,
                communicationRhythms= filterRhythm,
                contactStatuses     = filterStatus,
                cityFilter          = cityFilter,
                tagFilter           = filterTag,
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
                Text(stringResource(R.string.contacts_sort_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ContactSortOrder.values().forEach { order ->
                    val label = when (order) {
                        ContactSortOrder.NAME_AZ        -> stringResource(R.string.contacts_sort_name_az)
                        ContactSortOrder.NAME_ZA        -> stringResource(R.string.contacts_sort_name_za)
                        ContactSortOrder.RECENTLY_ADDED -> stringResource(R.string.home_recently_added)
                        ContactSortOrder.IMPORTANCE     -> stringResource(R.string.contacts_sort_importance)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { sortOrder = order; showSortSheet = false }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        if (sortOrder == order) Icon(Icons.Default.Check, null, tint = AppleTheme.colors.brand)
                    }
                    if (order != ContactSortOrder.values().last()) HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ── Filter bottom sheet ───────────────────────────────────
    if (showFilterSheet) {
        ContactFilterSheet(
            filterRelTypes     = filterRelTypes,
            filterImportance   = filterImportance,
            filterConnLevel    = filterConnLevel,
            filterRhythm       = filterRhythm,
            filterStatus       = filterStatus,
            cityFilter         = cityFilter,
            tagFilter          = filterTag,
            allTags            = allTags,
            onRelTypesChange   = { filterRelTypes   = it },
            onImportanceChange = { filterImportance = it },
            onConnLevelChange  = { filterConnLevel  = it },
            onRhythmChange     = { filterRhythm     = it },
            onStatusChange     = { filterStatus     = it },
            onCityChange       = { cityFilter       = it },
            onTagChange        = { filterTag        = it },
            onClear            = {
                filterRelTypes = emptySet(); filterImportance = emptySet()
                filterConnLevel = emptySet(); filterRhythm = emptySet()
                filterStatus = emptySet()
                cityFilter = ""; filterTag = ""
            },
            onDismiss          = { showFilterSheet = false }
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
          if (searchActive) {
            TopAppBar(
                title = {
                    if (!searchActive) {
                        Text(stringResource(R.string.common_contacts), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("contacts_search_input"),
                            placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.contacts_close_search))
                        }
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, stringResource(R.string.common_search))
                        }
                    } else if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.common_clear))
                        }
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        BadgedBox(badge = {
                            if (hasActiveFilters) Badge(containerColor = AppleTheme.colors.red) {}
                        }) {
                            Icon(Icons.Default.FilterList, stringResource(R.string.contacts_filters))
                        }
                    }
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.contacts_sort_title))
                    }
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(if (isGridView) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Default.GridView, stringResource(R.string.contacts_view_toggle))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
          }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // Header + капсула поиска показываются только когда поиск НЕ активен —
            // в активном режиме поле ввода живёт в TopAppBar, иначе было два поля.
            if (!searchActive) {
                // Header по макету: заголовок + круглая «+»
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(stringResource(R.string.common_contacts))
                    Box(
                        Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppleTheme.colors.brand).clickable { onNavigateToCreateContact() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Add, stringResource(R.string.contacts_add), tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
                // Капсула поиска (спека: r13 h40, заливка .09, плейсхолдер 16sp #9A9284)
                Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp)).background(Color(0x17787880)).clickable { searchActive = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, null, tint = AppleTheme.colors.tertiaryLabel, modifier = Modifier.size(17.dp))
                        Text(stringResource(R.string.contacts_search_placeholder), fontSize = 16.sp, color = AppleTheme.colors.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Controls: сортировка + вид + фильтр
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ContactsSortChip(stringResource(R.string.contacts_sort_chip_az), sortOrder == ContactSortOrder.NAME_AZ) { sortOrder = ContactSortOrder.NAME_AZ }
                    ContactsSortChip(stringResource(R.string.contacts_sort_chip_new), sortOrder == ContactSortOrder.RECENTLY_ADDED) { sortOrder = ContactSortOrder.RECENTLY_ADDED }
                    ContactsSortChip(stringResource(R.string.contacts_sort_chip_importance), sortOrder == ContactSortOrder.IMPORTANCE) { sortOrder = ContactSortOrder.IMPORTANCE }
                }
                Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)).background(Color(0x1F767680)).padding(2.dp)) {
                    Box(Modifier.size(width = 30.dp, height = 26.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)).background(if (!isGridView) AppleTheme.colors.card else Color.Transparent).clickable { isGridView = false }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null, Modifier.size(16.dp), tint = if (!isGridView) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel)
                    }
                    Box(Modifier.size(width = 30.dp, height = 26.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)).background(if (isGridView) AppleTheme.colors.card else Color.Transparent).clickable { isGridView = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GridView, null, Modifier.size(16.dp), tint = if (isGridView) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel)
                    }
                }
                Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x1F767680)).clickable { showFilterSheet = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                    if (hasActiveFilters) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppleTheme.colors.red))
                }
            }

            // ── Active filter chips strip ─────────────────────
            val activeChips = buildList {
                filterRelTypes.forEach  { add(it.label(ctxLabel) to { filterRelTypes   = filterRelTypes   - it }) }
                filterImportance.forEach{ add(it.label(ctxLabel) to { filterImportance = filterImportance - it }) }
                filterConnLevel.forEach { add(it.label(ctxLabel) to { filterConnLevel  = filterConnLevel  - it }) }
                filterStatus.forEach    { add(it.label(ctxLabel) to { filterStatus     = filterStatus     - it }) }
                if (filterTag.isNotBlank())  add("#$filterTag"    to { filterTag   = "" })
                if (cityFilter.isNotBlank()) add("📍 $cityFilter" to { cityFilter  = "" })
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

            // Сегментные чипы по макету: Все · N / Ключевые / Клиенты / Семья
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val noQuick = filterImportance.isEmpty() && !filterRelTypes.contains(RelationshipType.CLIENT) && !filterRelTypes.contains(RelationshipType.FAMILY)
                ContactsSegChip(stringResource(R.string.contacts_seg_all) + " · " + AppStateStore.contacts.size, noQuick) {
                    filterImportance = emptySet(); filterRelTypes = emptySet()
                }
                ContactsSegChip(stringResource(R.string.contacts_seg_key), filterImportance.contains(ImportanceLevel.KEY)) {
                    filterImportance = if (filterImportance.contains(ImportanceLevel.KEY)) emptySet() else setOf(ImportanceLevel.KEY)
                }
                ContactsSegChip(stringResource(R.string.contacts_seg_clients), filterRelTypes.contains(RelationshipType.CLIENT)) {
                    filterRelTypes = if (filterRelTypes.contains(RelationshipType.CLIENT)) emptySet() else setOf(RelationshipType.CLIENT)
                }
                ContactsSegChip(stringResource(R.string.contacts_seg_family), filterRelTypes.contains(RelationshipType.FAMILY)) {
                    filterRelTypes = if (filterRelTypes.contains(RelationshipType.FAMILY)) emptySet() else setOf(RelationshipType.FAMILY)
                }
            }

            // ── List / Grid ────────────────────────────────────
            if (AppStateStore.isLoading && AppStateStore.contacts.isEmpty()) {
                // Холодный старт: показываем спиннер, а не «ничего не найдено»,
                // иначе пустой экран выглядел как потеря данных.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredContacts.isEmpty()) {
                // Пустое состояние Aurelia: круг 100dp (бренд .08) + бренд-иконка.
                // Разделяем «совсем нет контактов» и «ничего не нашлось по фильтру».
                val noContactsAtAll = AppStateStore.contacts.isEmpty() && searchQuery.isBlank()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 40.dp)
                    ) {
                        Box(
                            Modifier.size(100.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(AppleTheme.colors.brand.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (noContactsAtAll) Icons.Outlined.PersonAddAlt else Icons.Outlined.SearchOff,
                                null, Modifier.size(44.dp), tint = AppleTheme.colors.brand
                            )
                        }
                        Text(
                            if (noContactsAtAll) stringResource(R.string.contacts_empty_title)
                            else stringResource(R.string.home_nothing_found, searchQuery),
                            fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                            fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                            color = AppleTheme.colors.label,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Text(
                            if (noContactsAtAll) stringResource(R.string.contacts_empty_sub)
                            else stringResource(R.string.contacts_no_filtered),
                            color = AppleTheme.colors.secondaryLabel,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (hasActiveFilters) {
                            TextButton(onClick = {
                                filterRelTypes = emptySet(); filterImportance = emptySet()
                                filterConnLevel = emptySet(); filterRhythm = emptySet(); cityFilter = ""
                            }) { Text(stringResource(R.string.contacts_reset_filters)) }
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
                val grouped = if (sortOrder == ContactSortOrder.NAME_AZ || sortOrder == ContactSortOrder.NAME_ZA)
                    filteredContacts.groupBy { (it.firstName.ifEmpty { it.lastName }).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
                        .toList().sortedBy { it.first }.let { if (sortOrder == ContactSortOrder.NAME_ZA) it.reversed() else it }
                else listOf("" to filteredContacts)
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier       = Modifier.fillMaxSize()
                ) {
                    grouped.forEach { (letter, group) ->
                        if (letter.isNotEmpty()) item(key = "h_$letter") {
                            Text(letter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 7.dp))
                        }
                        item(key = "c_$letter") {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                                elevation = CardDefaults.cardElevation(1.dp)
                            ) {
                                Column {
                                    group.forEachIndexed { i, contact ->
                                        ContactListCard(
                                            contact          = contact,
                                            highlight        = searchQuery,
                                            onClick          = { onNavigateToContact(contact.id) },
                                            onFilterByType   = { type -> filterRelTypes = setOf(type) },
                                            onFilterByRhythm = { rhythm -> filterRhythm = setOf(rhythm) }
                                        )
                                        if (i < group.lastIndex) com.aistudio.socialsphere.crmlxb.ui.theme.AppleDivider(71.dp)
                                    }
                                }
                            }
                        }
                        if (letter.isNotEmpty()) item(key = "s_$letter") { Spacer(Modifier.height(10.dp)) }
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
    filterStatus: Set<ContactStatus> = emptySet(),
    cityFilter: String,
    tagFilter: String = "",
    allTags: List<String> = emptyList(),
    onRelTypesChange: (Set<RelationshipType>) -> Unit,
    onImportanceChange: (Set<ImportanceLevel>) -> Unit,
    onConnLevelChange: (Set<ConnectionLevel>) -> Unit,
    onRhythmChange: (Set<CommunicationRhythm>) -> Unit,
    onStatusChange: (Set<ContactStatus>) -> Unit = {},
    onCityChange: (String) -> Unit,
    onTagChange: (String) -> Unit = {},
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctxLabel = LocalContext.current
    // Локальная копия выбора: тапы по чипам мгновенные (рекомпозится только
    // шторка), тяжёлый пересчёт списка у родителя — один раз при «Применить»/
    // закрытии, а не на каждый тап (раньше это заметно тормозило).
    var lRelTypes   by remember { mutableStateOf(filterRelTypes) }
    var lImportance by remember { mutableStateOf(filterImportance) }
    var lConnLevel  by remember { mutableStateOf(filterConnLevel) }
    var lRhythm     by remember { mutableStateOf(filterRhythm) }
    var lStatus     by remember { mutableStateOf(filterStatus) }
    var lCity       by remember { mutableStateOf(cityFilter) }
    var lTag        by remember { mutableStateOf(tagFilter) }
    fun pushAndClose() {
        onStatusChange(lStatus); onRelTypesChange(lRelTypes); onImportanceChange(lImportance)
        onConnLevelChange(lConnLevel); onRhythmChange(lRhythm); onCityChange(lCity); onTagChange(lTag)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = { pushAndClose() }, shape = SocialShape.Sheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(stringResource(R.string.contacts_filters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    lRelTypes = emptySet(); lImportance = emptySet(); lConnLevel = emptySet()
                    lRhythm = emptySet(); lStatus = emptySet(); lCity = ""; lTag = ""
                }) { Text(stringResource(R.string.contacts_reset_all)) }
            }

            // Status
            FilterSection(stringResource(R.string.filter_status)) {
                ContactStatus.values().forEach { status ->
                    MultiSelectChip(status.label(ctxLabel), status in lStatus) {
                        lStatus = if (status in lStatus) lStatus - status else lStatus + status
                    }
                }
            }

            // Relationship type
            FilterSection(stringResource(R.string.filter_relation)) {
                listOf(RelationshipType.FAMILY, RelationshipType.FRIEND, RelationshipType.COLLEAGUE,
                       RelationshipType.CLIENT, RelationshipType.PARTNER, RelationshipType.ACQUAINTANCE).forEach { type ->
                    MultiSelectChip(type.label(ctxLabel), type in lRelTypes) {
                        lRelTypes = if (type in lRelTypes) lRelTypes - type else lRelTypes + type
                    }
                }
            }

            // Importance
            FilterSection(stringResource(R.string.filter_importance)) {
                ImportanceLevel.values().forEach { level ->
                    MultiSelectChip(level.label(ctxLabel), level in lImportance) {
                        lImportance = if (level in lImportance) lImportance - level else lImportance + level
                    }
                }
            }

            // Connection level
            FilterSection(stringResource(R.string.filter_connection)) {
                listOf(ConnectionLevel.CLOSE, ConnectionLevel.NORMAL, ConnectionLevel.WEAK, ConnectionLevel.NEW).forEach { lvl ->
                    MultiSelectChip(lvl.label(ctxLabel), lvl in lConnLevel) {
                        lConnLevel = if (lvl in lConnLevel) lConnLevel - lvl else lConnLevel + lvl
                    }
                }
            }

            // Rhythm
            FilterSection(stringResource(R.string.filter_rhythm)) {
                listOf(CommunicationRhythm.WEEKLY, CommunicationRhythm.MONTHLY,
                       CommunicationRhythm.EVERY_3_MONTHS, CommunicationRhythm.EVERY_6_MONTHS).forEach { r ->
                    MultiSelectChip(r.label(ctxLabel), r in lRhythm) {
                        lRhythm = if (r in lRhythm) lRhythm - r else lRhythm + r
                    }
                }
            }

            // City
            FilterSection(stringResource(R.string.filter_city)) {
                OutlinedTextField(
                    value = lCity,
                    onValueChange = { lCity = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.filter_city_hint)) },
                    leadingIcon  = { Icon(Icons.Default.LocationCity, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (lCity.isNotEmpty()) IconButton(onClick = { lCity = "" }) { Icon(Icons.Default.Clear, null, Modifier.size(16.dp)) }
                    },
                    singleLine = true,
                    shape = SocialShape.Small
                )
            }

            // Tags
            if (allTags.isNotEmpty()) {
                FilterSection(stringResource(R.string.filter_tag)) {
                    OutlinedTextField(
                        value = lTag,
                        onValueChange = { lTag = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.filter_tag_hint)) },
                        leadingIcon  = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (lTag.isNotEmpty()) IconButton(onClick = { lTag = "" }) {
                                Icon(Icons.Default.Clear, null, Modifier.size(16.dp))
                            }
                        },
                        singleLine = true,
                        shape = SocialShape.Small
                    )
                    Spacer(Modifier.height(6.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        allTags.take(8).forEach { tag ->
                            FilterChip(
                                selected = tag.equals(lTag, ignoreCase = true),
                                onClick  = { lTag = if (tag.equals(lTag, ignoreCase = true)) "" else tag },
                                label    = { Text(tag, fontSize = 12.sp) },
                                shape    = SocialShape.Full
                            )
                        }
                    }
                }
            }

            Button(onClick = { pushAndClose() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_apply)) }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
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
private fun ContactsSortChip(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(30.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp)).background(Color(0x1F767680)).clickable { onClick() }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, color = if (active) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel)
    }
}

@Composable
private fun ContactsSegChip(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(if (active) AppleTheme.colors.brand else AppleTheme.colors.card)
            .clickable { onClick() }.padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) Color.White else AppleTheme.colors.label)
    }
}

@Composable
fun ContactListCard(
    contact: Contact,
    highlight: String = "",
    onClick: () -> Unit,
    onFilterByType: ((RelationshipType) -> Unit)? = null,
    onFilterByRhythm: ((CommunicationRhythm) -> Unit)? = null
) {
    val ctxLabel = LocalContext.current
    val (company, position, city) = getContactVisuals(contact)
    val name = "${contact.firstName} ${contact.lastName}".trim()
    val ctx  = LocalContext.current

    val importanceTint = when (contact.importanceLevel) {
        ImportanceLevel.KEY       -> AppleTheme.colors.red
        ImportanceLevel.IMPORTANT -> AppleTheme.colors.orange
        else                      -> Color.Transparent
    }

    // Палитра аватаров Aurelia (терракот/сейдж/слива/тил/золото) вместо iOS-радуги.
    val grads = listOf(
        listOf(Color(0xFFE59A6B), Color(0xFFC45D34)),
        listOf(Color(0xFF9DBE92), Color(0xFF5E8C66)),
        listOf(Color(0xFFB58CB6), Color(0xFF7E5180)),
        listOf(Color(0xFF7FBDB2), Color(0xFF3E7E7A)),
        listOf(Color(0xFFD8B26A), Color(0xFFB68A36))
    )
    val g = grads[kotlin.math.abs(contact.id.hashCode()) % grads.size]
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(g)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = (contact.firstName.firstOrNull()?.toString() ?: "") + (contact.lastName.firstOrNull()?.toString() ?: ""),
                fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Color.White
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (importanceTint != Color.Transparent)
                    Box(Modifier.size(6.dp).clip(CircleShape).background(importanceTint))
            }
            val sub = listOf(contact.relationshipType.label(ctxLabel), position.ifEmpty { company }).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty())
                Text(sub, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallActionIcon(Icons.Outlined.Phone) {
                val phone = contact.phones.find { it.isPrimary }?.number ?: contact.phones.firstOrNull()?.number
                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(ctx, phone)
            }
            SmallActionIcon(Icons.Outlined.ChatBubbleOutline) {
                val m = contact.messengers.find { it.isPrimary }
                if (m != null) com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(ctx, m)
                else com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(ctx, contact.phones.firstOrNull()?.number)
            }
        }
    }
}

@Composable
fun ContactGridCard(contact: Contact, highlight: String = "", onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
    val (company, position, _) = getContactVisuals(contact)
    val name = "${contact.firstName} ${contact.lastName}".trim()
    val importanceTint = when (contact.importanceLevel) {
        ImportanceLevel.KEY       -> AppleTheme.colors.red
        ImportanceLevel.IMPORTANT -> AppleTheme.colors.orange
        else                      -> AppleTheme.colors.brand.copy(alpha = 0.10f)
    }

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth().height(156.dp),
        shape     = SocialShape.Card,
        colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
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
                    Text(company, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel, maxLines = 1)
                if (position.isNotEmpty())
                    Text(position, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(contact.relationshipType.label(ctxLabel), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.brand)
                if (contact.importanceLevel != ImportanceLevel.NORMAL)
                    Box(Modifier.size(7.dp).clip(CircleShape).background(importanceTint))
            }
        }
    }
}

@Composable
private fun SmallActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0x1F767680)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(16.dp), tint = AppleTheme.colors.brand) }
}

// kept for compat with HomeScreen usage
@Composable
fun ContactFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(16.dp))
}
