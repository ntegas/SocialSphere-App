@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

    // ── Filter sheet ──────────────────────────────────────────
    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }, shape = SocialShape.Sheet) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(stringResource(R.string.contacts_filters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { filterIndustries = emptySet(); cityFilter = "" }) { Text(stringResource(R.string.contacts_reset_all)) }
                }

                // Сортировка (была отдельным рядом чипов над списком — перенесена
                // сюда, в один узел контролов вместе с фильтрами, по макету Contacts)
                FilterSection(stringResource(R.string.contacts_sort_title)) {
                    CompanySortOrder.values().forEach { order ->
                        val label = when (order) {
                            CompanySortOrder.NAME_AZ        -> stringResource(R.string.comp_sort_name_az)
                            CompanySortOrder.NAME_ZA        -> stringResource(R.string.comp_sort_name_za)
                            CompanySortOrder.MOST_CONTACTS  -> stringResource(R.string.comp_sort_most)
                            CompanySortOrder.RECENTLY_ADDED -> stringResource(R.string.home_recently_added)
                        }
                        MultiSelectChip(label, sortOrder == order) { sortOrder = order }
                    }
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
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCircleButton(
                        Icons.Default.Add, stringResource(R.string.comp_add),
                        style = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCircleStyle.Filled
                    ) { onNavigateToCreateCompany() }
                }
                Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth().height(40.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R13).background(AppleTheme.colors.neutralFill).clickable { searchActive = true }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    items(chips.size, key = { chips[it].first }) { idx ->
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

            // Сегментные чипы по отраслям (по макету) + один круглый значок фильтра
            // справа (в нём же теперь живёт сортировка — как в ContactsScreen.kt)
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
                Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppleTheme.colors.neutralFill).clickable { showFilterSheet = true }, contentAlignment = Alignment.Center) {
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
                // Счётчик «N компаний» над списком (прототип: 13px 700 tx2)
                Text(
                    stringResource(R.string.comp_count, filteredCompanies.size),
                    fontSize = 13.sp, fontWeight = FontWeight.W700,
                    color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(start = 24.dp, bottom = 7.dp)
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 24.dp),
                    modifier       = Modifier.fillMaxSize()
                ) {
                    // Сгруппированная inset-карточка со строками компаний (по макету Aurelia):
                    // лого + имя + «отрасль · город» + пилюля «N чел.», строки с разделителем.
                    item {
                        Card(
                            modifier  = Modifier.fillMaxWidth(),
                            shape     = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R22,
                            colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Column {
                                filteredCompanies.forEachIndexed { idx, company ->
                                    CompanyRow(company = company, onClick = { onNavigateToCompany(company.id) })
                                    if (idx < filteredCompanies.lastIndex)
                                        HorizontalDivider(
                                            modifier  = Modifier.padding(start = 74.dp),
                                            color     = AppleTheme.colors.separator,
                                            thickness = 0.5.dp
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

// ─── Строка компании в сгруппированной карточке (по макету Aurelia) ──────
// Простая строка: лого-квадрат + имя + «отрасль · город» + пилюля «N чел.».
// Заменила тяжёлую карточку с 3 стат-боксами (контакты/ключевые/важные) и
// шевроном — макет таких блоков не содержит (детали — в карточке компании).
@Composable
fun CompanyRow(company: Company, onClick: () -> Unit) {
    val ctxLabel = LocalContext.current
    val addresses by remember(company.id) {
        derivedStateOf {
            AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
        }
    }
    val peopleCount by remember(company.id) {
        derivedStateOf { AppStateStore.companyRelations.count { it.companyId == company.id } }
    }
    val mainCity = addresses.firstOrNull { it.addressType == AddressType.OFFICE || it.addressType == AddressType.LEGAL }?.city
        ?: addresses.firstOrNull()?.city ?: ""
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        // Лого 46/r14 — общая палитра компаний (AureliaAvatars.companyBrushFor)
        Box(
            modifier = Modifier.size(46.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14)
                .background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.companyBrushFor(company.id)),
            contentAlignment = Alignment.Center
        ) {
            Text(company.name.take(1).uppercase(), fontWeight = FontWeight.W700, fontSize = 17.sp, color = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(company.name, fontSize = 16.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(company.industry.label(ctxLabel), mainCity).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty())
                Text(sub, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (peopleCount > 0) {
            Box(
                Modifier.clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R11)
                    .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.comp_people_n, peopleCount),
                    fontSize = 12.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.brand
                )
            }
        }
    }
}

// Сегмент-чип (прототип: неактивный — card + inset-кольцо rgba(line,.06))
@Composable
private fun CompaniesSegChip(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
            .background(if (active) AppleTheme.colors.brand else AppleTheme.colors.card)
            .then(
                if (!active) Modifier.border(1.dp, AppleTheme.colors.separator,
                    com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
                else Modifier
            )
            .clickable { onClick() }.padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (active) FontWeight.W700 else FontWeight.SemiBold,
            color = if (active) Color.White else AppleTheme.colors.label)
    }
}
