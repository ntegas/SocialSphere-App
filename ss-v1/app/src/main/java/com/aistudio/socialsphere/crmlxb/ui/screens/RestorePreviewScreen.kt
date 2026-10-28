package com.aistudio.socialsphere.crmlxb.ui.screens
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.RestoreDifference
import com.aistudio.socialsphere.crmlxb.data.RestoreDifferenceType
import com.aistudio.socialsphere.crmlxb.data.RestoreDiffEngine
import com.aistudio.socialsphere.crmlxb.data.RestoreFieldKind
import com.aistudio.socialsphere.crmlxb.data.RestorePreviewFilter
import com.aistudio.socialsphere.crmlxb.data.RestorePreviewGroup
import com.aistudio.socialsphere.crmlxb.data.filtered
import com.aistudio.socialsphere.crmlxb.data.ContactScalarField
import com.aistudio.socialsphere.crmlxb.data.CompanyScalarField
import com.aistudio.socialsphere.crmlxb.data.CalendarScalarField
import com.aistudio.socialsphere.crmlxb.model.TombstoneEntityType
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCard
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaConfirmDialog
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22).
 *
 * Инвариант экрана: НИ ОДНОЙ мутации Room до нажатия «Восстановить выбранное» и подтверждения диалога.
 * Выбор ([RestorePreviewSession.selectedKeys]) живёт отдельно от фильтра/поиска этого экрана — смена
 * фильтра НИКОГДА не трогает уже сделанный выбор (requirement 25).
 *
 * Внутренние термины (SafeMerge/Tombstone/EntityId/RestoreAuthorization) здесь нигде не показываются —
 * только обычный человеческий текст (requirement 33).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestorePreviewScreen(
    onNavigateBack: () -> Unit,
    onRestoreComplete: () -> Unit,
) {
    val preview = RestorePreviewSession.preview
    if (preview == null) {
        // Защитный путь: сюда попали без активной сессии (например, экран пережил process death) —
        // предпросмотра больше нет, показывать нечего.
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RestorePreviewFilter.ALL) }
    var isApplying by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val selectedKeys = RestorePreviewSession.selectedKeys
    val visibleGroups = remember(filter, searchQuery, preview) { preview.filtered(filter, searchQuery) }
    // selectedKeys.size как ключ remember — SnapshotStateList меняется мутацией, не заменой ссылки.
    val selectedSet = remember(selectedKeys.size) { selectedKeys.toSet() }
    val summary = remember(selectedSet, preview) { RestoreDiffEngine.summarize(preview, selectedSet) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rp_title), fontFamily = null, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    Box(Modifier.padding(start = 12.dp)) {
                        AureliaBackButton(contentDescription = stringResource(R.string.common_back), onClick = onNavigateBack)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = AppleTheme.colors.card) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        stringResource(R.string.rp_selected_count, selectedSet.size),
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { showConfirm = true },
                        enabled = selectedSet.isNotEmpty() && !isApplying,
                        modifier = Modifier.fillMaxWidth().testTag("rp_restore_selected_button"),
                    ) {
                        if (isApplying) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                        } else {
                            Text(stringResource(R.string.rp_restore_selected_button), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        containerColor = AppleTheme.colors.groupedBackground,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.rp_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filterChips().forEach { (f, labelRes) ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    preview.groups.forEach { g ->
                        if (g.wholeEntityMissing) g.wholeKey?.let { RestorePreviewSession.setSelected(it, true) }
                        else g.differences.filter { it.differenceType == RestoreDifferenceType.MISSING }
                            .forEach { RestorePreviewSession.setSelected(it.key, true) }
                    }
                }) { Text(stringResource(R.string.rp_select_all_missing_global)) }
                TextButton(onClick = { RestorePreviewSession.selectedKeys.clear() }) {
                    Text(stringResource(R.string.rp_clear_selection))
                }
            }

            if (visibleGroups.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.rp_empty), color = AppleTheme.colors.secondaryLabel, fontSize = 14.sp,
                        modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleGroups, key = { it.wholeKey ?: "${it.ownerType}|${it.ownerId}" }) { group ->
                        RestorePreviewGroupCard(group)
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }

    if (showConfirm) {
        AureliaConfirmDialog(
            onDismiss = { showConfirm = false },
            title = stringResource(R.string.rp_confirm_title),
            text = if (summary.replacements > 0)
                stringResource(R.string.rp_confirm_text_with_replacements, summary.additions, summary.replacements)
            else
                stringResource(R.string.rp_confirm_text_simple, summary.additions),
            confirmText = stringResource(R.string.rp_confirm_button),
            onConfirm = {
                showConfirm = false
                isApplying = true
                val keysNow = RestorePreviewSession.selectedKeys.toSet()
                scope.launch {
                    val applied = try {
                        withContext(Dispatchers.IO) { ExportManager.applyRestoreSelection(preview, keysNow) }
                    } finally {
                        isApplying = false
                    }
                    // ФИКС (найден Compose-тестом finalConfirmation_...): после withContext(Dispatchers.IO)
                    // возврат НЕ гарантированно попадает на главный поток в тестовом окружении — Toast
                    // обязан явно исполняться на Main, иначе "Can't toast on a thread that has not called
                    // Looper.prepare()".
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.rp_done_toast, applied), android.widget.Toast.LENGTH_LONG).show()
                    }
                    RestorePreviewSession.clear()
                    onRestoreComplete()
                }
            },
        )
    }
}

@Composable
private fun filterChips(): List<Pair<RestorePreviewFilter, Int>> = listOf(
    RestorePreviewFilter.ALL to R.string.rp_filter_all,
    RestorePreviewFilter.MISSING to R.string.rp_filter_missing,
    RestorePreviewFilter.CONFLICTS to R.string.rp_filter_conflicts,
    RestorePreviewFilter.CONTACTS to R.string.rp_filter_contacts,
    RestorePreviewFilter.COMPANIES to R.string.rp_filter_companies,
    RestorePreviewFilter.NOTES to R.string.rp_filter_notes,
    RestorePreviewFilter.GIFTS to R.string.rp_filter_gifts,
    RestorePreviewFilter.CALENDAR to R.string.rp_filter_calendar,
    RestorePreviewFilter.TAGS to R.string.rp_filter_tags,
)

@Composable
private fun restoreActionLabel(ownerType: TombstoneEntityType): String = when (ownerType) {
    TombstoneEntityType.CONTACT -> stringResource(R.string.rp_restore_contact)
    TombstoneEntityType.COMPANY -> stringResource(R.string.rp_restore_company)
    TombstoneEntityType.CALENDAR_ITEM -> stringResource(R.string.rp_restore_event)
    TombstoneEntityType.GROUP -> stringResource(R.string.rp_restore_group)
    else -> stringResource(R.string.rp_add_from_backup)
}

@Composable
private fun fieldLabel(diff: RestoreDifference): String = when (diff.fieldKind) {
    RestoreFieldKind.PHONE -> stringResource(R.string.rp_field_phone)
    RestoreFieldKind.EMAIL -> stringResource(R.string.rp_field_email)
    RestoreFieldKind.MESSENGER -> stringResource(R.string.rp_field_messenger)
    RestoreFieldKind.ADDRESS -> stringResource(R.string.rp_field_address)
    RestoreFieldKind.PERSONAL_DETAIL -> stringResource(R.string.rp_field_personal_detail)
    RestoreFieldKind.COMPANY_RELATION -> stringResource(R.string.rp_field_company_relation)
    RestoreFieldKind.SIZE_INFO -> stringResource(R.string.rp_field_size_info)
    RestoreFieldKind.WHOLE_NOTE -> stringResource(R.string.rp_field_note)
    RestoreFieldKind.WHOLE_GIFT -> stringResource(R.string.rp_field_gift)
    RestoreFieldKind.TAG -> stringResource(R.string.rp_field_tag)
    RestoreFieldKind.RELATION -> stringResource(R.string.rp_field_relation)
    RestoreFieldKind.GROUP_MEMBERSHIP -> stringResource(R.string.rp_field_group)
    RestoreFieldKind.CALENDAR_REMINDER -> stringResource(R.string.rp_field_reminder)
    RestoreFieldKind.CALENDAR_LINK -> stringResource(R.string.rp_field_link)
    RestoreFieldKind.SCALAR -> when (diff.scalarField) {
        ContactScalarField.PROFESSION -> stringResource(R.string.rp_field_profession)
        ContactScalarField.FAMILY_NOTE -> stringResource(R.string.rp_field_family_note)
        ContactScalarField.NEXT_STEP -> stringResource(R.string.rp_field_next_step)
        ContactScalarField.CAN_HELP_WITH -> stringResource(R.string.rp_field_can_help_with)
        ContactScalarField.I_CAN_HELP_WITH -> stringResource(R.string.rp_field_i_can_help_with)
        ContactScalarField.TALKING_POINTS -> stringResource(R.string.rp_field_talking_points)
        ContactScalarField.MEET_CONTEXT -> stringResource(R.string.rp_field_meet_context)
        ContactScalarField.CUSTOM_RELATIONSHIP_TYPE -> stringResource(R.string.rp_field_custom_relationship_type)
        CompanyScalarField.DESCRIPTION, CalendarScalarField.DESCRIPTION -> stringResource(R.string.rp_field_description)
        CompanyScalarField.WEBSITE -> stringResource(R.string.rp_field_website)
        CalendarScalarField.TITLE -> stringResource(R.string.rp_field_title)
        else -> diff.scalarField?.name ?: ""
    }
    else -> ""
}

@Composable
private fun RestorePreviewGroupCard(group: RestorePreviewGroup) {
    AureliaCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Text(group.ownerDisplayName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppleTheme.colors.label)
                if (group.previouslyDeleted) {
                    Text(stringResource(R.string.rp_previously_deleted), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.red)
                }
            }

            if (group.wholeEntityMissing) {
                group.childSummaryLines.forEach { line ->
                    Text(line, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel)
                }
                group.wholeKey?.let { key ->
                    val checked = RestorePreviewSession.isSelected(key)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().testTag("rp_whole_$key")
                            .clickable { RestorePreviewSession.toggle(key) }.padding(top = 4.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { RestorePreviewSession.setSelected(key, it) })
                        Spacer(Modifier.width(4.dp))
                        Text(restoreActionLabel(group.ownerType), fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    stringResource(R.string.rp_differences_count, group.differences.size),
                    fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel,
                )
                Row {
                    TextButton(onClick = {
                        group.differences.filter { it.differenceType == RestoreDifferenceType.MISSING }
                            .forEach { RestorePreviewSession.setSelected(it.key, true) }
                    }) { Text(stringResource(R.string.rp_select_all_missing_group), fontSize = 12.sp) }
                    TextButton(onClick = {
                        group.differences.forEach { RestorePreviewSession.setSelected(it.key, false) }
                    }) { Text(stringResource(R.string.rp_deselect_all_group), fontSize = 12.sp) }
                }
                group.differences.forEach { diff -> RestoreDifferenceRow(diff) }
            }
        }
    }
}

@Composable
private fun RestoreDifferenceRow(diff: RestoreDifference) {
    when (diff.differenceType) {
        RestoreDifferenceType.MISSING -> {
            val checked = RestorePreviewSession.isSelected(diff.key)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().testTag("rp_missing_${diff.key}")
                    .clickable { RestorePreviewSession.toggle(diff.key) }.padding(vertical = 2.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = { RestorePreviewSession.setSelected(diff.key, it) })
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(fieldLabel(diff), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label)
                    Text(diff.backupValue ?: "", fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)
                    if (diff.previouslyDeleted) {
                        Text(stringResource(R.string.rp_previously_deleted), fontSize = 11.sp, color = AppleTheme.colors.red)
                    }
                }
            }
        }
        RestoreDifferenceType.CONFLICT -> {
            val usingBackup = RestorePreviewSession.isSelected(diff.key)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(fieldLabel(diff), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label)
                Spacer(Modifier.height(4.dp))
                Text("${stringResource(R.string.rp_keep_current)}: ${diff.currentValue.orEmpty()}", fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)
                Text("${stringResource(R.string.rp_use_backup)}: ${diff.backupValue.orEmpty()}", fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)
                Spacer(Modifier.height(6.dp))
                ConflictChoicePillRow(diff, usingBackup)
            }
        }
    }
}

/** Локальный двух-пилюльный выбор для CONFLICT-строки (вместо общего [PillChoiceRow] — здесь нужен
 *  детерминированный testTag на каждой пилюле для UI-тестов, а не общий компонент, используемый ещё
 *  десятком других экранов). Визуально — тот же стиль: заливка брендом у выбранной, card+бордер у прочих. */
@Composable
private fun ConflictChoicePillRow(diff: RestoreDifference, usingBackup: Boolean) {
    val keepLabel = stringResource(R.string.rp_keep_current)
    val backupLabel = stringResource(R.string.rp_use_backup)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to keepLabel, true to backupLabel).forEach { (isBackupOption, label) ->
            val selected = isBackupOption == usingBackup
            val bg = if (selected) AppleTheme.colors.brand else AppleTheme.colors.card
            val borderColor = if (selected) AppleTheme.colors.brand else AppleTheme.colors.separator
            val textColor = if (selected) Color.White else AppleTheme.colors.label
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .border(1.dp, borderColor, RoundedCornerShape(50))
                    .testTag(if (isBackupOption) "rp_conflict_backup_${diff.key}" else "rp_conflict_keep_${diff.key}")
                    .clickable { RestorePreviewSession.setSelected(diff.key, isBackupOption) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, color = textColor)
            }
        }
    }
}
