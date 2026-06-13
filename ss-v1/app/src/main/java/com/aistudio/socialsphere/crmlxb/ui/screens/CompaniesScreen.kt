@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
                        if (sortOrder == order) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    if (order != CompanySortOrder.values().last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
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
                    Text(stringResource(R.string.comp_industry), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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
                    Text(stringResource(R.string.filter_city), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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
        topBar = {
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
                            if (hasActiveFilters) Badge(containerColor = MaterialTheme.colorScheme.error) {}
                        }) { Icon(Icons.Default.FilterList, stringResource(R.string.contacts_filters)) }
                    }
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.Default.Sort, stringResource(R.string.contacts_sort_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateCompany, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, stringResource(R.string.comp_add))
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

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

            // ── Count row ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (searchQuery.isBlank() && !hasActiveFilters) stringResource(R.string.comp_count, AppStateStore.companies.size)
                    else stringResource(R.string.contacts_count_of, filteredCompanies.size, AppStateStore.companies.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                val sortLabel = when (sortOrder) {
                    CompanySortOrder.NAME_AZ        -> stringResource(R.string.contacts_sort_chip_az)
                    CompanySortOrder.NAME_ZA        -> stringResource(R.string.contacts_sort_chip_za)
                    CompanySortOrder.MOST_CONTACTS  -> stringResource(R.string.comp_sort_chip_most)
                    CompanySortOrder.RECENTLY_ADDED -> stringResource(R.string.contacts_sort_chip_new)
                }
                TextButton(onClick = { showSortSheet = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(sortLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Default.UnfoldMore, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }

            // ── List ──────────────────────────────────────────
            if (filteredCompanies.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.SearchOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            if (searchQuery.isNotBlank()) stringResource(R.string.home_nothing_found, searchQuery)
                            else stringResource(R.string.comp_no_filtered),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (hasActiveFilters) TextButton(onClick = { filterIndustries = emptySet(); cityFilter = "" }) { Text(stringResource(R.string.contacts_reset_filters)) }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = SocialShape.Card,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Icon / logo
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        company.name.take(2).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(company.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(company.industry.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        if (mainCity.isNotEmpty()) {
                            Text("·", color = MaterialTheme.colorScheme.outlineVariant)
                            Text(mainCity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

                // People count badge
                if (peopleCount > 0) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.People, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("$peopleCount", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }

            // People sample
            if (peopleSample.isNotEmpty()) {
                Text(
                    peopleSample + if (peopleCount > 2) " " + stringResource(R.string.comp_and_more, peopleCount - 2) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            // Description snippet
            if (!company.description.isNullOrBlank()) {
                Text(company.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            // Action row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!company.website.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = { ExternalActionHandler.openWebsite(ctx, company.website) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        shape = SocialShape.Full
                    ) {
                        Icon(Icons.Default.Language, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.comp_website), fontSize = 11.sp)
                    }
                }
                if (addresses.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            val a = addresses.firstOrNull()
                            if (a != null) {
                                if (a.latitude != null && a.longitude != null) ExternalActionHandler.openRouteByCoordinates(ctx, a.latitude, a.longitude)
                                else ExternalActionHandler.openRoute(ctx, "${a.addressLine}, ${a.city}, ${a.country}")
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        shape = SocialShape.Full
                    ) {
                        Icon(Icons.Default.Directions, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.map_route), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
