@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.utils.*
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CompaniesScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToCompany: (String) -> Unit,
    onNavigateToCreateCompany: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ── Search ────────────────────────────────────────────────
    val ctxLabel = LocalContext.current
    var searchQuery  by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // ── Filters ───────────────────────────────────────────────
    var filterIndustries  by remember { mutableStateOf(emptySet<Industry>()) }
    var cityFilter        by remember { mutableStateOf("") }
    var showFilterSheet   by remember { mutableStateOf(false) }
    var sortOrder         by remember { mutableStateOf(CompanySortOrder.NAME_AZ) }
    var showSortSheet     by remember { mutableStateOf(false) }

    val hasActiveFilters = filterIndustries.isNotEmpty() || cityFilter.isNotBlank()

    // ── Derived filtered list ─────────────────────────────────
    val filteredCompanies by remember {
        derivedStateOf {
            AppStateStore.companies.applyCompanyFilters(
                query      = searchQuery,
                industries = filterIndustries,
                cityFilter = cityFilter,
                sortOrder  = sortOrder
            )
        }
    }

    // ── Sort sheet ────────────────────────────────────────────
    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }, shape = SocialShape.Sheet) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.contacts_sort_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                CompanySortOrder.values().forEach { order ->
                    val label = when (order) {
                        CompanySortOrder.NAME_AZ        -> stringResource(R.string.comp_sort_name_az)
                        CompanySortOrder.NAME_ZA        -> stringResource(R.string.comp_sort_name_za)
                        CompanySortOrder.MOST_CONTACTS  -> stringResource(R.string.comp_sort_most)
                        CompanySortOrder.RECENTLY_ADDED -> stringResource(R.string.home_recently_added)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { sortOrder = order; showSortSheet = false }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        if (sortOrder == order) Icon(Icons.Default.Check, null, tint = AppleTheme.colors.brand)
                    }
                    if (order != CompanySortOrder.values().last()) HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ── Filter sheet ──────────────────────────────────────────
    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }, shape = SocialShape.Sheet) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(stringResource(R.string.contacts_filters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { filterIndustries = emptySet(); cityFilter = "" }) { Text(stringResource(R.string.contacts_reset_all)) }
                }

                // Industry multi-select
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.comp_industry), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Industry.values().forEach { ind ->
                            FilterChip(
                                selected = ind in filterIndustries,
                                onClick  = { filterIndustries = if (ind in filterIndustries) filterIndustries - ind else filterIndustries + ind },
                                label    = { Text(ind.label(ctxLabel), fontSize = 13.sp) },
                                shape    = SocialShape.Full
                            )
                        }
                    }
                }

                // City
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.filter_city), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                    OutlinedTextField(
                        value = cityFilter, onValueChange = { cityFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.comp_city_hint)) },
                        leadingIcon = { Icon(Icons.Default.LocationCity, null, Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (cityFilter.isNotEmpty()) IconButton(onClick = { cityFilter = "" }) { Icon(Icons.Default.Clear, null, Modifier.size(16.dp)) }
                        },
                        singleLine = true, shape = SocialShape.Small
                    )
                }

                Button(onClick = { showFilterSheet = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_apply)) }
            }
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
                        Text(stringResource(R.string.comp_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("companies_search_input"),
                            placeholder = { Text(stringResource(R.string.comp_search_hint)) },
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
                        IconButton(onClick = { searchActive = true }) { Icon(Icons.Default.Search, stringResource(R.string.common_search)) }
                    } else if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.common_clear)) }
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        BadgedBox(badge = {
                            if (hasActiveFilters) Badge(containerColor = AppleTheme.colors.red) {}
                        }) { Icon(Icons.Default.FilterList, stringResource(R.string.contacts_filters)) }
                    }
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.contacts_sort_title))
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
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(stringResource(R.string.comp_title))
                    Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppleTheme.colors.brand).clickable { onNavigateToCreateCompany() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, stringResource(R.string.comp_add), tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth().height(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp)).background(Color(0x17787880)).clickable { searchActive = true }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Search, null, tint = AppleTheme.colors.tertiaryLabel, modifier = Modifier.size(17.dp))
                        Text(stringResource(R.string.comp_search_hint), fontSize = 16.sp, color = AppleTheme.colors.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // ── Active filter chips ───────────────────────────
            val chips = buildList {
                filterIndustries.forEach { add(it.label(ctxLabel) to { filterIndustries = filterIndustries - it }) }
                if (cityFilter.isNotBlank()) add("📍 " + cityFilter to { cityFilter = "" })
            }
            if (chips.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chips.size) { idx ->
                        val (label, remove) = chips[idx]
                        InputChip(
                            selected = true, onClick = remove,
                            label = { Text(label, fontSize = 12.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                            shape = SocialShape.Full
                        )
                    }
                }
            }

            // Сегментные чипы по отраслям (по макету)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompaniesSegChip(stringResource(R.string.contacts_seg_all) + " · " + AppStateStore.companies.size, filterIndustries.isEmpty()) { filterIndustries = emptySet() }
                    AppStateStore.companies.map { it.industry }.distinct().forEach { ind ->
                        CompaniesSegChip(ind.label(ctxLabel), filterIndustries.contains(ind)) {
                            filterIndustries = if (filterIndustries.contains(ind)) emptySet() else setOf(ind)
                        }
                    }
                }
                Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x1F767680)).clickable { showFilterSheet = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                    if (hasActiveFilters) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppleTheme.colors.red))
                }
            }

            // ── List ──────────────────────────────────────────
            if (filteredCompanies.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.SearchOff, null, Modifier.size(56.dp), tint = AppleTheme.colors.separator)
                        Text(
                            if (searchQuery.isNotBlank()) stringResource(R.string.home_nothing_found, searchQuery)
                            else stringResource(R.string.comp_no_filtered),
                            color = AppleTheme.colors.secondaryLabel
                        )
                        if (hasActiveFilters) TextButton(onClick = { filterIndustries = emptySet(); cityFilter = "" }) { Text(stringResource(R.string.contacts_reset_filters)) }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding      = PaddingValues(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 24.dp),
                    modifier            = Modifier.fillMaxSize()
                ) {
                    items(filteredCompanies, key = { it.id }) { company ->
                        CompanyCardItem(company = company, onClick = { onNavigateToCompany(company.id) })
                    }
                }
            }
        }
    }
}

// ─── Company card (moved here from CompaniesScreen old) ──────
@Composable
fun CompanyCardItem(company: Company, onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
    val ctx = LocalContext.current
    // Пересчёт только при смене данных, а не на каждой рекомпозиции при скролле
    val addresses by remember(company.id) {
        derivedStateOf {
            AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
        }
    }
    val relations by remember(company.id) {
        derivedStateOf {
            AppStateStore.companyRelations.filter { it.companyId == company.id }
        }
    }
    val mainCity = addresses.firstOrNull { it.addressType == AddressType.OFFICE || it.addressType == AddressType.LEGAL }?.city
        ?: addresses.firstOrNull()?.city ?: ""
    val peopleCount = relations.size
    val peopleSample = relations.take(2).mapNotNull {
        AppStateStore.getContact(it.contactId)?.let { c -> "${c.firstName} ${c.lastName}" }
    }.joinToString(", ")

    val keyCount = relations.count { AppStateStore.getContact(it.contactId)?.importanceLevel == ImportanceLevel.KEY }
    val importantCount = relations.count { AppStateStore.getContact(it.contactId)?.importanceLevel == ImportanceLevel.IMPORTANT }
    val grads = listOf(
        listOf(Color(0xFF5AC8FA), Color(0xFF007AFF)),
        listOf(Color(0xFFFF6B6B), Color(0xFFFF3B30)),
        listOf(Color(0xFF7B73E8), Color(0xFF5B53D6)),
        listOf(Color(0xFF30D158), Color(0xFF34C759)),
        listOf(Color(0xFFFF9F45), Color(0xFFFF9500))
    )
    val g = grads[kotlin.math.abs(company.id.hashCode()) % grads.size]
    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(
                    modifier = Modifier.size(50.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(g)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(company.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(company.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(company.industry.label(ctxLabel), mainCity).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (sub.isNotEmpty())
                        Text(sub, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
            }
            Row(modifier = Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyStatBox(Modifier.weight(1f), peopleCount.toString(), stringResource(R.string.comp_stat_contacts), AppleTheme.colors.label)
                CompanyStatBox(Modifier.weight(1f), keyCount.toString(), stringResource(R.string.comp_stat_key), AppleTheme.colors.red)
                CompanyStatBox(Modifier.weight(1f), importantCount.toString(), stringResource(R.string.comp_stat_important), AppleTheme.colors.label)
            }
        }
    }
}

@Composable
private fun CompanyStatBox(modifier: Modifier, value: String, label: String, valueColor: Color) {
    Column(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp)).background(AppleTheme.colors.groupedBackground).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = AppleTheme.colors.secondaryLabel, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun CompaniesSegChip(label: String, active: Boolean, onClick: () -> Unit) {
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
