@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.utils.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.launch

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

    // ── View / sort (контролы теперь внутри листа «Фильтры», по макету) ──
    var isGridView    by remember { mutableStateOf(false) }
    var sortOrder     by remember { mutableStateOf(ContactSortOrder.NAME_AZ) }
    val listState        = androidx.compose.foundation.lazy.rememberLazyListState()
    val indexScrollScope = rememberCoroutineScope()

    // ── Active filters ────────────────────────────────────────
    var filterRelTypes    by remember { mutableStateOf(emptySet<RelationshipType>()) }
    // Свои типы отношений («статусы») — раньше не могли быть фильтром вообще
    // (фидбэк владельца 2026-07-05: «создаю статус сам — не входит в фильтры»)
    var filterCustomRelTypes by remember { mutableStateOf(emptySet<String>()) }
    var filterRhythm      by remember { mutableStateOf(emptySet<CommunicationRhythm>()) }
    var filterStatus      by remember { mutableStateOf(emptySet<ContactStatus>()) }
    var filterGroups      by remember { mutableStateOf(emptySet<String>()) } // id групп
    var filterTag         by remember { mutableStateOf("") }
    // Новая управляемая система тегов (Entity Tag с id/category) — отдельно от
    // легаси filterTag выше (свободный текст Contact.tags). НЕ путать: это
    // фильтры по AppStateStore.tags / distinctCategories().
    var filterTagIds        by remember { mutableStateOf(emptySet<String>()) }
    var filterTagCategories by remember { mutableStateOf(emptySet<String>()) }
    var cityFilter        by remember { mutableStateOf("") }
    var showFilterSheet   by remember { mutableStateOf(false) }

    val hasActiveFilters = filterRelTypes.isNotEmpty() ||
        filterRhythm.isNotEmpty() ||
        filterStatus.isNotEmpty() || filterGroups.isNotEmpty() ||
        cityFilter.isNotBlank() || filterTag.isNotBlank() || filterCustomRelTypes.isNotEmpty() ||
        filterTagIds.isNotEmpty() || filterTagCategories.isNotEmpty()

    // All unique tags across contacts for suggestion — единый источник, см. AppStateStore.allTags()
    val allTags by remember {
        derivedStateOf { AppStateStore.allTags() }
    }

    // ── Filtered list (derivedStateOf = recompute only when deps change) ──
    val filteredContacts by remember {
        derivedStateOf {
            AppStateStore.contacts.applyContactFilters(
                query               = searchQuery,
                relationshipTypes   = filterRelTypes,
                importanceLevels    = emptySet(),
                communicationRhythms= filterRhythm,
                contactStatuses     = filterStatus,
                cityFilter          = cityFilter,
                tagFilter           = filterTag,
                groupIds            = filterGroups,
                customRelTypes      = filterCustomRelTypes,
                tagIds              = filterTagIds,
                tagCategories       = filterTagCategories,
                sortOrder           = sortOrder,
                nameSortField       = AppSettings.contactSortField.value
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

    // ── Filter bottom sheet (включает сортировку и вид — по макету, одна точка входа) ──
    if (showFilterSheet) {
        ContactFilterSheet(
            filterRelTypes     = filterRelTypes,
            filterRhythm       = filterRhythm,
            filterStatus       = filterStatus,
            filterGroups       = filterGroups,
            filterCustomRelTypes = filterCustomRelTypes,
            cityFilter         = cityFilter,
            tagFilter          = filterTag,
            allTags            = allTags,
            filterTagIds       = filterTagIds,
            filterTagCategories = filterTagCategories,
            searchQuery        = searchQuery,
            sortOrder          = sortOrder,
            isGridView         = isGridView,
            onRelTypesChange   = { filterRelTypes   = it },
            onRhythmChange     = { filterRhythm     = it },
            onStatusChange     = { filterStatus     = it },
            onGroupsChange     = { filterGroups     = it },
            onCustomRelTypesChange = { filterCustomRelTypes = it },
            onCityChange       = { cityFilter       = it },
            onTagChange        = { filterTag        = it },
            onTagIdsChange     = { filterTagIds     = it },
            onTagCategoriesChange = { filterTagCategories = it },
            onSortOrderChange  = { sortOrder        = it },
            onGridViewChange   = { isGridView       = it },
            onClear            = {
                filterRelTypes = emptySet()
                filterRhythm = emptySet()
                filterStatus = emptySet(); filterGroups = emptySet()
                cityFilter = ""; filterTag = ""; filterCustomRelTypes = emptySet()
                filterTagIds = emptySet(); filterTagCategories = emptySet()
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
                        Text(stringResource(R.string.common_contacts), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(stringResource(R.string.common_contacts)))
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
                        Modifier.fillMaxWidth().height(40.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R13).background(AppleTheme.colors.neutralFill).clickable { searchActive = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, null, tint = AppleTheme.colors.tertiaryLabel, modifier = Modifier.size(17.dp))
                        Text(stringResource(R.string.contacts_search_placeholder), fontSize = 16.sp, color = AppleTheme.colors.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // ── Active filter chips strip ─────────────────────
            // ФИКС (аудит 2026-08-11, жалоба «фильтры куда-то пропадают» — сами
            // значения не сбрасывались, но 4 типа фильтра не показывали чип, из-за
            // чего активный фильтр выглядел невидимым/забытым): добавлены Ритм,
            // Свои типы отношений, Теги и Категории — раньше сужали список молча.
            val activeChips = buildList {
                filterRelTypes.forEach  { add(it.label(ctxLabel) to { filterRelTypes   = filterRelTypes   - it }) }
                filterStatus.forEach    { add(it.label(ctxLabel) to { filterStatus     = filterStatus     - it }) }
                filterRhythm.forEach    { add(it.label(ctxLabel) to { filterRhythm     = filterRhythm     - it }) }
                filterCustomRelTypes.forEach { ct -> add(ct to { filterCustomRelTypes = filterCustomRelTypes - ct }) }
                filterGroups.forEach { gid ->
                    val gName = AppStateStore.groups.firstOrNull { it.id == gid }?.name ?: return@forEach
                    add("👥 $gName" to { filterGroups = filterGroups - gid })
                }
                filterTagCategories.forEach { cat -> add(cat to { filterTagCategories = filterTagCategories - cat }) }
                filterTagIds.forEach { tid ->
                    val tName = AppStateStore.tags.firstOrNull { it.id == tid }?.name ?: return@forEach
                    add("#$tName" to { filterTagIds = filterTagIds - tid })
                }
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
                            shape     = SocialShape.Full,
                            colors    = InputChipDefaults.inputChipColors(
                                selectedContainerColor = AppleTheme.colors.brand.copy(alpha = 0.12f),
                                selectedLabelColor     = AppleTheme.colors.brand,
                                selectedTrailingIconColor = AppleTheme.colors.brand
                            )
                        )
                    }
                }
            }

            // Сегментные чипы + фильтр — одна строка по макету: Все · N / Ключевые / Клиенты / Семья + ⚙
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val noQuick = !filterRelTypes.contains(RelationshipType.CLIENT) && !filterRelTypes.contains(RelationshipType.FAMILY)
                    ContactsSegChip(stringResource(R.string.contacts_seg_all) + " · " + AppStateStore.contacts.size, noQuick) {
                        filterRelTypes = emptySet()
                    }
                    ContactsSegChip(stringResource(R.string.contacts_seg_clients), filterRelTypes.contains(RelationshipType.CLIENT)) {
                        filterRelTypes = if (filterRelTypes.contains(RelationshipType.CLIENT)) emptySet() else setOf(RelationshipType.CLIENT)
                    }
                    ContactsSegChip(stringResource(R.string.contacts_seg_family), filterRelTypes.contains(RelationshipType.FAMILY)) {
                        filterRelTypes = if (filterRelTypes.contains(RelationshipType.FAMILY)) emptySet() else setOf(RelationshipType.FAMILY)
                    }
                }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(AppleTheme.colors.neutralFill).clickable { showFilterSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Tune, stringResource(R.string.contacts_filters), Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                    if (hasActiveFilters) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(7.dp).clip(CircleShape).background(AppleTheme.colors.red))
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
                            fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(
                                if (noContactsAtAll) stringResource(R.string.contacts_empty_title)
                                else stringResource(R.string.home_nothing_found, searchQuery)
                            ),
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
                            // Раньше чистило только 3 из 7 фильтров — filterStatus/filterGroups/
                            // filterTag/filterCustomRelTypes оставались активными, и пользователь
                            // не понимал, почему список остаётся пустым после «Сбросить фильтры».
                            // Приведено в соответствие с onClear в ContactFilterSheet.
                            TextButton(onClick = {
                                filterRelTypes = emptySet(); filterRhythm = emptySet()
                                filterStatus = emptySet(); filterGroups = emptySet()
                                cityFilter = ""; filterTag = ""; filterCustomRelTypes = emptySet()
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
                val nameSortField = AppSettings.contactSortField.value
                val isAlphaSort = sortOrder == ContactSortOrder.NAME_AZ || sortOrder == ContactSortOrder.NAME_ZA
                val grouped = if (isAlphaSort)
                    filteredContacts.groupBy { contactSortLetter(it, nameSortField) }
                        .toList().sortedBy { it.first }.let { if (sortOrder == ContactSortOrder.NAME_ZA) it.reversed() else it }
                else listOf("" to filteredContacts)
                // Буква → индекс item в LazyColumn (заголовок группы), для алфавитного индекса.
                val letterItemIndex = remember(grouped) {
                    val map = mutableMapOf<String, Int>()
                    var idx = 0
                    grouped.forEach { (letter, _) ->
                        if (letter.isNotEmpty()) { map[letter] = idx; idx += 3 } else idx += 1
                    }
                    map
                }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state          = listState,
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
                                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R22,
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
                    // Алфавитный индекс — только когда список реально отсортирован по имени/фамилии
                    // (для «Недавние»/«Важность» буквенных секций нет, индекс был бы бессмысленным).
                    if (isAlphaSort && grouped.size > 1) {
                        AlphabetIndexBar(
                            letters          = alphabetForContacts(AppSettings.currentLanguage.value, letterItemIndex.keys),
                            activeLetters    = letterItemIndex.keys,
                            onLetterSelected = { letter ->
                                letterItemIndex[letter]?.let { idx ->
                                    indexScrollScope.launch { listState.scrollToItem(idx) }
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 16.dp)
                        )
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
internal fun ContactFilterSheet(
    filterRelTypes: Set<RelationshipType>,
    // Важность (ImportanceLevel) убрана из UI (2026-07-23) — параметры оставлены
    // с дефолтами ради обратной совместимости вызова из DuplicatesScreen.kt
    // (список для слияния дублей больше не показывает и не редактирует чип
    // важности, но сигнатуру шторки менять по всем вызовам не требовалось).
    filterImportance: Set<ImportanceLevel> = emptySet(),
    filterRhythm: Set<CommunicationRhythm>,
    filterStatus: Set<ContactStatus> = emptySet(),
    filterGroups: Set<String> = emptySet(),
    filterCustomRelTypes: Set<String> = emptySet(),
    cityFilter: String,
    tagFilter: String = "",
    allTags: List<String> = emptyList(),
    // Новая управляемая система тегов (Entity Tag с id/category) — отдельные
    // параметры от легаси tagFilter/allTags выше (свободный текст Contact.tags).
    // НЕ путать секции в UI ниже: filter_tag (легаси) vs filter_category/filter_tag_new (новое).
    filterTagIds: Set<String> = emptySet(),
    filterTagCategories: Set<String> = emptySet(),
    searchQuery: String = "",
    sortOrder: ContactSortOrder,
    isGridView: Boolean,
    onRelTypesChange: (Set<RelationshipType>) -> Unit,
    onImportanceChange: (Set<ImportanceLevel>) -> Unit = {},
    onRhythmChange: (Set<CommunicationRhythm>) -> Unit,
    onStatusChange: (Set<ContactStatus>) -> Unit = {},
    onGroupsChange: (Set<String>) -> Unit = {},
    onCustomRelTypesChange: (Set<String>) -> Unit = {},
    onCityChange: (String) -> Unit,
    onTagChange: (String) -> Unit = {},
    onTagIdsChange: (Set<String>) -> Unit = {},
    onTagCategoriesChange: (Set<String>) -> Unit = {},
    onSortOrderChange: (ContactSortOrder) -> Unit,
    onGridViewChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctxLabel = LocalContext.current
    // Локальная копия выбора: тапы по чипам мгновенные (рекомпозится только
    // шторка), тяжёлый пересчёт списка у родителя — один раз при «Применить»/
    // закрытии, а не на каждый тап (раньше это заметно тормозило).
    var lRelTypes   by remember { mutableStateOf(filterRelTypes) }
    var lImportance by remember { mutableStateOf(filterImportance) }
    var lRhythm     by remember { mutableStateOf(filterRhythm) }
    var lStatus     by remember { mutableStateOf(filterStatus) }
    var lGroups     by remember { mutableStateOf(filterGroups) }
    var lCustomRelTypes by remember { mutableStateOf(filterCustomRelTypes) }
    var lCity       by remember { mutableStateOf(cityFilter) }
    var lTag        by remember { mutableStateOf(tagFilter) }
    var lTagIds        by remember { mutableStateOf(filterTagIds) }
    var lTagCategories by remember { mutableStateOf(filterTagCategories) }
    // Живой счётчик результатов на кнопке «Применить» (по макету) — сортировка
    // и вид списка не влияют на количество, применяются сразу, без буфера.
    val previewCount by remember {
        derivedStateOf {
            AppStateStore.contacts.applyContactFilters(
                query = searchQuery, relationshipTypes = lRelTypes, importanceLevels = lImportance,
                communicationRhythms = lRhythm, contactStatuses = lStatus,
                cityFilter = lCity, tagFilter = lTag, groupIds = lGroups, customRelTypes = lCustomRelTypes,
                tagIds = lTagIds, tagCategories = lTagCategories,
                sortOrder = sortOrder
            ).size
        }
    }
    fun pushAndClose() {
        onStatusChange(lStatus); onRelTypesChange(lRelTypes); onImportanceChange(lImportance)
        onRhythmChange(lRhythm); onGroupsChange(lGroups)
        onCustomRelTypesChange(lCustomRelTypes)
        onCityChange(lCity); onTagChange(lTag)
        onTagIdsChange(lTagIds); onTagCategoriesChange(lTagCategories)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = { pushAndClose() }, shape = SocialShape.Sheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.contacts_filters),
                    fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(stringResource(R.string.contacts_filters)),
                    fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = AppleTheme.colors.label
                )
                TextButton(onClick = {
                    lRelTypes = emptySet(); lImportance = emptySet()
                    lRhythm = emptySet(); lStatus = emptySet(); lGroups = emptySet(); lCity = ""; lTag = ""
                    lCustomRelTypes = emptySet()
                    lTagIds = emptySet(); lTagCategories = emptySet()
                }) { Text(stringResource(R.string.contacts_reset_all), color = AppleTheme.colors.brand, fontWeight = FontWeight.SemiBold) }
            }

            // Сортировка (была отдельным рядом чипов над списком — перенесена
            // сюда, в один узел контролов вместе с фильтрами, по макету)
            FilterSection(stringResource(R.string.contacts_sort_title)) {
                // ImportanceLevel убран из UI (2026-07-23, решение владельца) — этот
                // вариант сортировки скрыт из списка тем же паттерном, что и
                // CommunicationRhythm.CUSTOM выше по коду (enum-значение остаётся,
                // просто не рендерится чипом).
                ContactSortOrder.values().filter { it != ContactSortOrder.IMPORTANCE }.forEach { order ->
                    val label = when (order) {
                        ContactSortOrder.NAME_AZ        -> stringResource(R.string.contacts_sort_name_az)
                        ContactSortOrder.NAME_ZA        -> stringResource(R.string.contacts_sort_name_za)
                        ContactSortOrder.RECENTLY_ADDED -> stringResource(R.string.home_recently_added)
                        ContactSortOrder.IMPORTANCE     -> stringResource(R.string.contacts_sort_importance)
                    }
                    MultiSelectChip(label, sortOrder == order) { onSortOrderChange(order) }
                }
            }

            // Вид списка
            FilterSection(stringResource(R.string.contacts_view_toggle)) {
                MultiSelectChip(stringResource(R.string.view_mode_list), !isGridView) { onGridViewChange(false) }
                MultiSelectChip(stringResource(R.string.view_mode_grid), isGridView) { onGridViewChange(true) }
            }

            // Статус («Поддерживать» = пометка «с кем нужно общаться») — владелец
            // вернул после удаления 2026-07-02: «это была важная штука».
            FilterSection(stringResource(R.string.filter_status)) {
                ContactStatus.values().forEach { status ->
                    MultiSelectChip(status.label(ctxLabel), status in lStatus) {
                        lStatus = if (status in lStatus) lStatus - status else lStatus + status
                    }
                }
            }

            // Группы (как в телефонной книге): чипы + «+ Новая» прямо здесь.
            // Состав групп контакта правится в карточке: «⋯» → «Группы».
            run {
                var showNewGroup by remember { mutableStateOf(false) }
                var newGroupName by remember { mutableStateOf("") }
                FilterSection(stringResource(R.string.filter_groups)) {
                    AppStateStore.groups.sortedBy { it.name.lowercase() }.forEach { g ->
                        MultiSelectChip(g.name, g.id in lGroups) {
                            lGroups = if (g.id in lGroups) lGroups - g.id else lGroups + g.id
                        }
                    }
                    MultiSelectChip("+ " + stringResource(R.string.group_new), false) {
                        showNewGroup = true
                    }
                }
                if (showNewGroup) {
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.group_new),
                        onDismiss = { showNewGroup = false; newGroupName = "" },
                        confirmText = stringResource(R.string.ce_create),
                        confirmEnabled = newGroupName.isNotBlank(),
                        onConfirm = {
                            AppStateStore.addGroup(newGroupName)?.let { lGroups = lGroups + it.id }
                            newGroupName = ""; showNewGroup = false
                        },
                        secondaryText = stringResource(R.string.common_cancel),
                        onSecondary = { showNewGroup = false; newGroupName = "" }
                    ) {
                        OutlinedTextField(
                            value = newGroupName, onValueChange = { newGroupName = it },
                            label = { Text(stringResource(R.string.group_name)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
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

            // Свои типы отношений («статусы») — раньше эти значения нигде не
            // были видны как фильтр (relationshipType у них = OTHER, не чип из
            // списка выше). Фидбэк владельца 2026-07-05: «создаю статус сам —
            // не входит в фильтры... не могу редактировать, то есть удалять».
            // Переименование/удаление — бьёт по ВСЕМ контактам с этим значением
            // разом (как у групп).
            run {
                val customTypes by remember { derivedStateOf { AppStateStore.distinctCustomRelationshipTypes() } }
                if (customTypes.isNotEmpty()) {
                    var editingCustomType by remember { mutableStateOf<String?>(null) }
                    var customTypeDraft by remember { mutableStateOf("") }
                    FilterSection(stringResource(R.string.filter_custom_status)) {
                        customTypes.forEach { ct ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                MultiSelectChip(ct, ct in lCustomRelTypes) {
                                    lCustomRelTypes = if (ct in lCustomRelTypes) lCustomRelTypes - ct else lCustomRelTypes + ct
                                }
                                IconButton(
                                    onClick = { editingCustomType = ct; customTypeDraft = ct },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, stringResource(R.string.common_edit),
                                        Modifier.size(14.dp), tint = AppleTheme.colors.secondaryLabel)
                                }
                            }
                        }
                    }
                    editingCustomType?.let { ct ->
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { editingCustomType = null }) {
                            Text(
                                ct,
                                fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(ct),
                                fontSize = 20.sp, fontWeight = FontWeight.W700,
                                color = AppleTheme.colors.label,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            OutlinedTextField(
                                value = customTypeDraft, onValueChange = { customTypeDraft = it },
                                label = { Text(stringResource(R.string.filter_custom_status)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                enabled = customTypeDraft.isNotBlank(),
                                onClick = {
                                    AppStateStore.renameCustomRelationshipType(ct, customTypeDraft)
                                    lCustomRelTypes = lCustomRelTypes - ct + customTypeDraft.trim()
                                    editingCustomType = null
                                },
                                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold) }
                            TextButton(
                                onClick = {
                                    AppStateStore.deleteCustomRelationshipType(ct)
                                    lCustomRelTypes = lCustomRelTypes - ct
                                    editingCustomType = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.common_delete), color = AppleTheme.colors.red) }
                        }
                    }
                }
            }

            // ImportanceLevel убран из UI ПОЛНОСТЬЮ (2026-07-23, решение владельца) —
            // фильтр по важности здесь больше не рендерится (было FilterSection
            // filter_importance с чипами Обычный/Важный/Ключевой). lImportance
            // остаётся в состоянии шторки только для обратной совместимости
            // сигнатуры ContactFilterSheet, которую всё ещё вызывает DuplicatesScreen.kt.

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
                            MultiSelectChip(tag, tag.equals(lTag, ignoreCase = true)) {
                                lTag = if (tag.equals(lTag, ignoreCase = true)) "" else tag
                            }
                        }
                    }
                }
            }

            // Новая управляемая система тегов (AppStateStore.tags/distinctCategories) —
            // ДВЕ ОТДЕЛЬНЫЕ секции с однозначно другими подписями (filter_category /
            // filter_tag_new), намеренно расположены сразу после легаси-секции «Тег»
            // выше, чтобы визуально было видно, что это разные механики. Легаси-секция
            // выше НЕ переименована и НЕ тронута.
            run {
                val categories by remember { derivedStateOf { AppStateStore.distinctCategories() } }
                if (categories.isNotEmpty()) {
                    FilterSection(stringResource(R.string.filter_category)) {
                        categories.forEach { cat ->
                            MultiSelectChip(cat, cat in lTagCategories) {
                                lTagCategories = if (cat in lTagCategories) lTagCategories - cat else lTagCategories + cat
                            }
                        }
                    }
                }
            }
            run {
                val managedTags by remember { derivedStateOf { AppStateStore.tags.sortedBy { it.name.lowercase() } } }
                if (managedTags.isNotEmpty()) {
                    FilterSection(stringResource(R.string.filter_tag_new)) {
                        managedTags.forEach { t ->
                            MultiSelectChip(t.name, t.id in lTagIds) {
                                lTagIds = if (t.id in lTagIds) lTagIds - t.id else lTagIds + t.id
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { pushAndClose() },
                colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White),
                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R15,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("${stringResource(R.string.common_apply)} · $previewCount", fontWeight = FontWeight.Bold) }
        }
    }
}

// Приглушённое золото подписей — теперь темозависимый токен
// AppleTheme.colors.goldLabel (тёмное золото на светлом, светлое на тёмном).

@Composable
fun FilterSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp, color = AppleTheme.colors.goldLabel
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

// Свой Row вместо M3 FilterChip: у FilterChip нет заданной цветовой схемы
// (MaterialTheme без colorScheme, см. §12 KNOWLEDGE.md), поэтому selected-
// состояние рендерилось дефолтным Material You, а не малахитом.
@Composable
fun MultiSelectChip(label: String, selected: Boolean, gold: Boolean = false, onClick: () -> Unit) {
    val bg = when { selected && gold -> AureliaTheme.colors.gold.copy(alpha = 0.18f); selected -> AppleTheme.colors.brand; else -> AppleTheme.colors.card }
    val border = when { selected && gold -> AppleTheme.colors.goldLabel; selected -> AppleTheme.colors.brand; else -> AppleTheme.colors.separator }
    val fg = when { selected && gold -> AppleTheme.colors.goldLabel; selected -> Color.White; else -> AppleTheme.colors.secondaryLabel }
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
            .background(bg)
            .border(1.dp, border, com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, color = fg)
    }
}

// ═══════════════════════════════════════════════════════════
// CONTACT CARDS
// ═══════════════════════════════════════════════════════════
@Composable
private fun getContactVisuals(contact: Contact): Triple<String, String, String> {
    val compRel = contact.companyRelations.firstOrNull { it.isPrimary } ?: contact.companyRelations.firstOrNull()
    val company  = compRel?.companyId?.let { AppStateStore.getCompany(it) }?.name ?: ""
    // ФИКС (аудит 2026-08-11): показываем должность в компании И профессию,
    // если обе заполнены и различаются — раньше одно молча прятало другое
    // (см. тот же фикс в ContactDetailScreen.ContactHeader).
    val companyPosition = compRel?.position?.takeIf { it.isNotBlank() }
    val profession = contact.profession?.trim()?.takeIf { it.isNotBlank() }
    val position = listOfNotNull(companyPosition, profession?.takeIf { it != companyPosition }).joinToString(" · ")
    val city     = AppStateStore.addresses.find { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }?.city ?: ""
    return Triple(company, position, city)
}

@Composable
private fun ContactsSegChip(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
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
    val name = formatContactName(contact, AppSettings.contactNameFormat.value)

    // Цвет аватара — ЕДИНСТВЕННЫЙ источник AureliaAvatars.brushFor(id):
    // один и тот же контакт одного цвета на всех экранах (баг §28: Анна была
    // зелёной в списке и оранжевой на карточке из-за локальных копий палитры).
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        val listPhoto = contact.photoUri?.let { java.io.File(it) }?.takeIf { it.exists() }
        if (listPhoto != null) {
            coil.compose.AsyncImage(
                model = listPhoto, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape)
            )
        } else Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.brushFor(contact.id)),
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
            }
            val relLabel = contact.customRelationshipType?.takeIf { it.isNotBlank() } ?: contact.relationshipType.label(ctxLabel)
            val sub = listOf(relLabel, position.ifEmpty { company }).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty())
                Text(sub, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        // Шеврон по макету Aurelia (тап по строке → карточка контакта; звонок/
        // сообщение — из карточки). Раньше тут были две быстрые кнопки.
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
    }
}

@Composable
fun ContactGridCard(contact: Contact, highlight: String = "", onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
    val (company, position, _) = getContactVisuals(contact)
    val name = formatContactName(contact, AppSettings.contactNameFormat.value)
    // ImportanceLevel убран из UI (2026-07-23) — раньше red/orange-акцент
    // аватара и точка справа несли важность контакта, теперь фиксированный
    // приглушённый бренд-акцент, как у карточек без особого статуса.
    val avatarTint = AppleTheme.colors.brand

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
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(avatarTint.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (contact.firstName.firstOrNull()?.toString() ?: "") + (contact.lastName.firstOrNull()?.toString() ?: ""),
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = avatarTint
                    )
                }
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (company.isNotEmpty())
                    Text(company, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel, maxLines = 1)
                if (position.isNotEmpty())
                    Text(position, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(contact.customRelationshipType?.takeIf { it.isNotBlank() } ?: contact.relationshipType.label(ctxLabel), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.brand)
            }
        }
    }
}

// kept for compat with HomeScreen usage
@Composable
fun ContactFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
}

// ═══════════════════════════════════════════════════════════
// АЛФАВИТНЫЙ ИНДЕКС (fast-scroll sidebar, паттерн Android Contacts/iOS)
// ═══════════════════════════════════════════════════════════

/** Базовый алфавит языка приложения (не locale устройства — приложение само
 *  переключает язык через AppLanguage). Диапазоны символов подобраны явно
 *  (не Char-range для греческого — U+03A2 в блоке прописных греческих букв
 *  не назначен, диапазон дал бы «пустой» символ). */
fun alphabetForLanguage(language: AppLanguage): List<String> = when (language) {
    AppLanguage.RUSSIAN -> ('А'..'Я').map { it.toString() } + "#"
    AppLanguage.GREEK   -> listOf(
        "Α","Β","Γ","Δ","Ε","Ζ","Η","Θ","Ι","Κ","Λ","Μ","Ν","Ξ","Ο","Π",
        "Ρ","Σ","Τ","Υ","Φ","Χ","Ψ","Ω"
    ) + "#"
    AppLanguage.ENGLISH -> ('A'..'Z').map { it.toString() } + "#"
}

/**
 * ФИКС (2026-07-11, живой тест владельца: «алфавитный индекс только на русском,
 * хотя контакты на разных языках»). `alphabetForLanguage` в баре показывал ТОЛЬКО
 * буквы текущего языка интерфейса — если UI на русском, а имя контакта латиницей
 * ("John"), буквы A-Z в баре не было вообще, кликнуть/проскрабить до неё было
 * нельзя (сам список группировался верно — `contactSortLetter` locale-независим
 * через `uppercaseChar()`, баг был только в сайдбаре). Как AOSP Contacts (ICU
 * AlphabeticIndex строит индекс из данных, не только из locale UI) — берём
 * родной алфавит языка интерфейса как основу (порядок/раскладка привычные) и
 * дописываем перед «#» те буквы, что реально встречаются у контактов, но не
 * входят в этот алфавит (другой скрипт).
 */
fun alphabetForContacts(language: AppLanguage, activeLetters: Set<String>): List<String> {
    val base = alphabetForLanguage(language)
    val baseSet = base.toSet()
    val extra = activeLetters.filter { it != "#" && it !in baseSet }.sorted()
    if (extra.isEmpty()) return base
    return base.dropLast(1) + extra + "#"
}

private fun letterAtOffset(y: Float, barHeightPx: Float, letters: List<String>): String {
    if (letters.isEmpty()) return "#"
    if (barHeightPx <= 0f) return letters.first()
    val slot = barHeightPx / letters.size
    val idx = (y / slot).toInt().coerceIn(0, letters.lastIndex)
    return letters[idx]
}

/** Если под пальцем буква без контактов — прыгаем к ближайшей букве, у которой
 *  контакты реально есть (как в Android Contacts: серые буквы не мёртвые). */
private fun nearestActiveLetter(letter: String, letters: List<String>, active: Set<String>): String {
    if (letter in active || active.isEmpty()) return letter
    val idx = letters.indexOf(letter)
    if (idx < 0) return letter
    for (d in 1 until letters.size) {
        letters.getOrNull(idx - d)?.let { if (it in active) return it }
        letters.getOrNull(idx + d)?.let { if (it in active) return it }
    }
    return letter
}

/** Боковой скролл-индекс с drag-to-scrub и всплывающей буквой при перетаскивании —
 *  паттерн Android Contacts/iOS Контакты. Тап и протяжка обрабатываются вручную
 *  одним pointerInput (а не отдельными detectTapGestures/detectDragGestures),
 *  иначе первый детектор перехватывает down-событие и второй не запускается. */
@Composable
private fun AlphabetIndexBar(
    letters: List<String>,
    activeLetters: Set<String>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var barHeightPx by remember { mutableStateOf(0f) }
    var bubbleLetter by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.width(22.dp)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .onGloballyPositioned { barHeightPx = it.size.height.toFloat() }
                .pointerInput(letters, activeLetters) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var current = letterAtOffset(down.position.y, barHeightPx, letters)
                        bubbleLetter = current
                        onLetterSelected(nearestActiveLetter(current, letters, activeLetters))
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                val letter = letterAtOffset(change.position.y, barHeightPx, letters)
                                if (letter != current) {
                                    current = letter
                                    bubbleLetter = letter
                                    onLetterSelected(nearestActiveLetter(letter, letters, activeLetters))
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        bubbleLetter = null
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // weight(1f) вместо SpaceEvenly — гарантирует, что ВСЕ буквы (33 кириллицы
            // + "#") всегда влезают в доступную высоту, даже когда шрифт по умолчанию
            // даёт line-height больше fontSize (на реальном экране SpaceEvenly с
            // Text(fontSize=9.sp) без явного lineHeight обрезал буквы Ш–Я — каждая
            // строка занимала ~63px вместо ожидаемых ~24px из-за унаследованного
            // line-height типографики).
            letters.forEach { letter ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        letter,
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (letter in activeLetters) AppleTheme.colors.brand
                                else AppleTheme.colors.tertiaryLabel.copy(alpha = 0.5f)
                    )
                }
            }
        }

        bubbleLetter?.let { letter ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-38).dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(AppleTheme.colors.brand),
                contentAlignment = Alignment.Center
            ) {
                Text(letter, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
