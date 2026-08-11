package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Подпись под именем: телефон/email, если есть. */
private fun contactSubtitle(c: Contact): String =
    listOfNotNull(c.phones.firstOrNull()?.number, c.emails.firstOrNull()?.email).joinToString(" · ")

/**
 * Шаг 1 объединения дублей: полный список контактов с ТЕМИ ЖЕ фильтрами, что
 * в общем списке контактов (не урезанный автоподбор) + мультивыбор до 3 штук.
 * Автонайденные пары (по телефону/email) остаются сверху как быстрые
 * подсказки-чипы, но это больше не единственный путь найти дубли (фидбэк
 * владельца 2026-07-13: «10 контактов матери — вообще не похожи формально»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToResolve: (List<String>) -> Unit = {}
) {
    var searchQuery       by remember { mutableStateOf("") }
    var filterRelTypes    by remember { mutableStateOf(emptySet<RelationshipType>()) }
    var filterCustomRelTypes by remember { mutableStateOf(emptySet<String>()) }
    var filterImportance  by remember { mutableStateOf(emptySet<ImportanceLevel>()) }
    var filterRhythm      by remember { mutableStateOf(emptySet<CommunicationRhythm>()) }
    var filterStatus      by remember { mutableStateOf(emptySet<ContactStatus>()) }
    var filterGroups      by remember { mutableStateOf(emptySet<String>()) }
    var filterTag         by remember { mutableStateOf("") }
    var cityFilter        by remember { mutableStateOf("") }
    var showFilterSheet   by remember { mutableStateOf(false) }
    var selected          by remember { mutableStateOf(setOf<String>()) }

    val hasActiveFilters = filterRelTypes.isNotEmpty() || filterImportance.isNotEmpty() ||
        filterRhythm.isNotEmpty() || filterStatus.isNotEmpty() || filterGroups.isNotEmpty() ||
        cityFilter.isNotBlank() || filterTag.isNotBlank() || filterCustomRelTypes.isNotEmpty()

    val allTags by remember { derivedStateOf { AppStateStore.allTags() } }

    val filteredContacts by remember {
        derivedStateOf {
            AppStateStore.contacts.applyContactFilters(
                query = searchQuery, relationshipTypes = filterRelTypes,
                importanceLevels = filterImportance, communicationRhythms = filterRhythm,
                contactStatuses = filterStatus, cityFilter = cityFilter, tagFilter = filterTag,
                groupIds = filterGroups, customRelTypes = filterCustomRelTypes,
                sortOrder = ContactSortOrder.NAME_AZ
            )
        }
    }

    val autoPairs by remember { derivedStateOf { AppStateStore.findDuplicatePairs() } }

    if (showFilterSheet) {
        ContactFilterSheet(
            filterRelTypes = filterRelTypes, filterImportance = filterImportance,
            filterRhythm = filterRhythm, filterStatus = filterStatus, filterGroups = filterGroups,
            filterCustomRelTypes = filterCustomRelTypes, cityFilter = cityFilter, tagFilter = filterTag,
            allTags = allTags, searchQuery = searchQuery, sortOrder = ContactSortOrder.NAME_AZ,
            isGridView = false,
            onRelTypesChange = { filterRelTypes = it }, onImportanceChange = { filterImportance = it },
            onRhythmChange = { filterRhythm = it }, onStatusChange = { filterStatus = it },
            onGroupsChange = { filterGroups = it }, onCustomRelTypesChange = { filterCustomRelTypes = it },
            onCityChange = { cityFilter = it }, onTagChange = { filterTag = it },
            onSortOrderChange = {}, onGridViewChange = {},
            onClear = {
                filterRelTypes = emptySet(); filterImportance = emptySet(); filterRhythm = emptySet()
                filterStatus = emptySet(); filterGroups = emptySet()
                cityFilter = ""; filterTag = ""; filterCustomRelTypes = emptySet()
            },
            onDismiss = { showFilterSheet = false }
        )
    }

    Scaffold(containerColor = AppleTheme.colors.groupedBackground) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.dup_pick_title), fontSize = 24.sp)
            }
            Text(
                stringResource(R.string.dup_pick_hint),
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
                    placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
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
                        val label = "${pair.a.firstName} ${pair.a.lastName}".trim() + " + " +
                            "${pair.b.firstName} ${pair.b.lastName}".trim()
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
            }

            Spacer(Modifier.height(8.dp))

            if (filteredContacts.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.contacts_no_filtered), color = AppleTheme.colors.secondaryLabel)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredContacts.size, key = { filteredContacts[it].id }) { i ->
                        val c = filteredContacts[i]
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
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatar(
                                c.id, "${c.firstName} ${c.lastName}".trim(), size = 38.dp, fontSize = 14.sp)
                            Column(Modifier.weight(1f)) {
                                Text("${c.firstName} ${c.lastName}".trim(),
                                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = contactSubtitle(c)
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
                        stringResource(R.string.dup_max_reached),
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
