@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.*

/** Подпись под именем компании: отрасль + город, если есть (аналог contactSubtitle в DuplicatesScreen). */
@Composable
private fun companySubtitle(c: Company): String {
    val ctxLabel = LocalContext.current
    val city = AppStateStore.addresses.firstOrNull { it.ownerId == c.id && it.ownerType == AddressOwnerType.COMPANY }?.city
    return listOfNotNull(c.industry.label(ctxLabel), city?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/**
 * Шаг 1 объединения дублей КОМПАНИЙ — точный аналог DuplicatesScreen.kt (см.
 * комментарии там): полный список компаний с теми же фильтрами, что в общем
 * списке компаний (CompaniesScreen), плюс мультивыбор до 3 + автонайденные
 * пары (по названию/сайту/телефону/email — см. findCompanyDuplicatePairs)
 * сверху как быстрые подсказки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyDuplicatesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToResolve: (List<String>) -> Unit = {}
) {
    val ctxLabel = LocalContext.current
    var searchQuery      by remember { mutableStateOf("") }
    var filterIndustries by remember { mutableStateOf(emptySet<Industry>()) }
    var cityFilter        by remember { mutableStateOf("") }
    var showFilterSheet   by remember { mutableStateOf(false) }
    var selected          by remember { mutableStateOf(setOf<String>()) }
    // Freemium (2026-08): «слить все дубли одним тапом» — Pro.
    var showBulkMergePaywall by remember { mutableStateOf(false) }

    val hasActiveFilters = filterIndustries.isNotEmpty() || cityFilter.isNotBlank()

    val filteredCompanies by remember {
        derivedStateOf {
            AppStateStore.companies.applyCompanyFilters(
                query = searchQuery, industries = filterIndustries,
                cityFilter = cityFilter, sortOrder = CompanySortOrder.NAME_AZ
            )
        }
    }

    val autoPairs by remember { derivedStateOf { AppStateStore.findCompanyDuplicatePairs() } }

    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }, shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Sheet) {
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.comp_industry), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Industry.values().forEach { ind ->
                            FilterChip(
                                selected = ind in filterIndustries,
                                onClick  = { filterIndustries = if (ind in filterIndustries) filterIndustries - ind else filterIndustries + ind },
                                label    = { Text(ind.label(ctxLabel), fontSize = 13.sp) },
                                shape    = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Full
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.filter_city), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                    OutlinedTextField(
                        value = cityFilter, onValueChange = { cityFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.comp_city_hint)) },
                        singleLine = true, shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Small
                    )
                }
                Button(onClick = { showFilterSheet = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_apply)) }
            }
        }
    }

    if (showBulkMergePaywall) {
        PaywallSheet(onDismiss = { showBulkMergePaywall = false })
    }

    Scaffold(containerColor = AppleTheme.colors.groupedBackground) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.dup_company_pick_title), fontSize = 24.sp)
            }
            Text(
                stringResource(R.string.dup_company_pick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.comp_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                BadgedBox(badge = { if (hasActiveFilters) Badge(containerColor = AppleTheme.colors.red) {} }) {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Default.FilterList, stringResource(R.string.contacts_filters))
                    }
                }
            }

            if (autoPairs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.dup_hint_pairs_title),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(autoPairs.size, key = { autoPairs[it].a.id + "_" + autoPairs[it].b.id }) { i ->
                        val pair = autoPairs[i]
                        val label = pair.a.name + " + " + pair.b.name
                        Row(
                            modifier = Modifier
                                .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R13)
                                .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                                .clickable { selected = setOf(pair.a.id, pair.b.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Sync, null, Modifier.size(14.dp), tint = AppleTheme.colors.brand)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = AppleTheme.colors.brand,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Freemium (2026-08): «слить все дубли одним тапом» — Pro.
                // Зеркало DuplicatesScreen.kt — поштучный выбор пары выше
                // остаётся бесплатным и нетронутым, это кнопка-сосед.
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        if (AppSettings.isPremium()) {
                            val n = AppStateStore.mergeAllCompanyDuplicates()
                            android.widget.Toast.makeText(
                                ctxLabel, ctxLabel.getString(R.string.dup_merged_all_toast, n), android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showBulkMergePaywall = true
                        }
                    },
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.WorkspacePremium, null, Modifier.size(16.dp), tint = AppleTheme.colors.brand)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dup_merge_all_cta, autoPairs.size))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (filteredCompanies.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.comp_no_filtered), color = AppleTheme.colors.secondaryLabel)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredCompanies.size, key = { filteredCompanies[it].id }) { i ->
                        val c = filteredCompanies[i]
                        val isSelected = c.id in selected
                        val limitReached = selected.size >= 3 && !isSelected
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = !limitReached) {
                                    selected = if (isSelected) selected - c.id else selected + c.id
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Checkbox(checked = isSelected, enabled = !limitReached, onCheckedChange = {
                                selected = if (isSelected) selected - c.id else selected + c.id
                            })
                            Box(
                                modifier = Modifier.size(38.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R13)
                                    .background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.companyBrushFor(c.id)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(c.name.take(1).uppercase(), fontWeight = FontWeight.W700, fontSize = 14.sp, color = Color.White)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = companySubtitle(c)
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                                    color = AppleTheme.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            // ── Нижняя панель: счётчик + «Далее» ──
            Column(
                Modifier.fillMaxWidth()
                    .background(AppleTheme.colors.card)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (selected.size >= 3) {
                    Text(
                        stringResource(R.string.dup_company_max_reached),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Button(
                    onClick = { onNavigateToResolve(selected.toList()) },
                    enabled = selected.size in 2..3,
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text(
                        stringResource(R.string.dup_selected_count, selected.size) + " · " + stringResource(R.string.dup_next),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
