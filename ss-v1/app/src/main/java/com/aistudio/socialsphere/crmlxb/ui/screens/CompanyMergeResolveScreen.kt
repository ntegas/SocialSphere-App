@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.*
import kotlinx.coroutines.launch

@androidx.annotation.StringRes
private fun companyFieldLabelRes(key: String): Int = when (key) {
    "name" -> R.string.cce_name
    "description" -> R.string.cce_description
    "website" -> R.string.cce_website
    "industry" -> R.string.cce_industry
    "logoUri" -> R.string.dup_field_logo
    else -> R.string.dup_field_logo
}

@androidx.annotation.StringRes
private fun companyListFieldLabelRes(key: String): Int = when (key) {
    "phones" -> R.string.dup_will_merge_phones
    "emails" -> R.string.dup_will_merge_emails
    "addresses" -> R.string.dup_will_merge_addresses
    else -> R.string.dup_field_logo
}

private fun companyEnumDisplayLabel(context: android.content.Context, key: String, raw: String): String = try {
    when (key) {
        "industry" -> enumValueOf<Industry>(raw).label(context)
        else -> raw
    }
} catch (e: Exception) { raw }

private fun companyShortName(c: Company): String = c.name.ifBlank { c.id.take(6) }

/**
 * Шаг 2 объединения дублей КОМПАНИЙ — точный аналог MergeResolveScreen.kt
 * (см. комментарии там подробно). Списочные поля компании (телефоны/email/
 * адреса) объединяются молча, свободный текст (название/описание/сайт) —
 * чипы включения через " / ", enum/фото поля (industry/logoUri) — явный
 * выбор с авто-заметкой о том, что не выбрано.
 */
@Composable
fun CompanyMergeResolveScreen(
    companyIds: List<String>,
    // null — вернуться на шаг выбора; non-null id — сразу открыть карточку
    // получившейся компании (см. MergeResolveScreen — тот же паттерн).
    onDone: (String?) -> Unit
) {
    val context = LocalContext.current
    val companiesList = remember(companyIds) { companyIds.mapNotNull { AppStateStore.getCompanyById(it) } }
    val preview = remember(companiesList) { computeCompanyMergePreview(companiesList) }

    var choiceWinners by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var textIncluded by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var submitted by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (companiesList.size != companyIds.size || companiesList.size < 2) {
        // Одна из выбранных компаний исчезла (уже слита/удалена где-то ещё) — не крашим.
        LaunchedEffect(Unit) { onDone(null) }
        return
    }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onDone(null) }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.dup_resolve_title), fontSize = 24.sp)
            }

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    companiesList.joinToString(" + ") { companyShortName(it) },
                    style = MaterialTheme.typography.bodyMedium, color = AppleTheme.colors.secondaryLabel
                )

                // ── Списочные поля: read-only факт, без вопроса ──
                val nonZeroLists = preview.listCounts.filterValues { counts -> counts.sum() > 0 }
                if (nonZeroLists.isNotEmpty()) {
                    MergeSectionCard(stringResource(R.string.dup_will_merge_header)) {
                        nonZeroLists.forEach { (key, counts) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(companyListFieldLabelRes(key)), style = MaterialTheme.typography.bodyMedium)
                                Text(counts.sum().toString() + " (" + counts.joinToString("+") + ")",
                                    style = MaterialTheme.typography.bodyMedium, color = AppleTheme.colors.secondaryLabel)
                            }
                        }
                    }
                }

                // ── Поля, где физически можно хранить только одно значение ──
                if (preview.choiceFields.isNotEmpty()) {
                    MergeSectionCard(stringResource(R.string.dup_conflicts_header)) {
                        Text(
                            stringResource(R.string.dup_company_conflicts_hint),
                            style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel
                        )
                        preview.choiceFields.forEach { field ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(stringResource(companyFieldLabelRes(field.key)),
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                if (field.kind == MergeFieldKind.PHOTO) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        field.options.forEach { opt ->
                                            val isSel = (choiceWinners[field.key] ?: field.options.first().contactId) == opt.contactId
                                            Box(
                                                Modifier.size(56.dp).clip(CircleShape)
                                                    .background(if (isSel) AppleTheme.colors.brand else Color.Transparent)
                                                    .padding(2.dp)
                                                    .clip(CircleShape)
                                                    .clickable { choiceWinners = choiceWinners + (field.key to opt.contactId) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                coil.compose.AsyncImage(
                                                    model = opt.displayValue, contentDescription = null,
                                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        field.options.forEach { opt ->
                                            val display = companyEnumDisplayLabel(context, field.key, opt.displayValue)
                                            val isSel = (choiceWinners[field.key] ?: field.options.first().contactId) == opt.contactId
                                            MultiSelectChip(display, isSel) {
                                                choiceWinners = choiceWinners + (field.key to opt.contactId)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Текстовые/структурные поля — комбинируются, не выбираются ──
                if (preview.textFields.isNotEmpty()) {
                    MergeSectionCard(stringResource(R.string.dup_text_header)) {
                        preview.textFields.forEach { field ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(stringResource(companyFieldLabelRes(field.key)),
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                val includedNow = textIncluded[field.key] ?: field.values.map { it.first }.toSet()
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    field.values.forEach { (cid, text) ->
                                        val isOn = cid in includedNow
                                        MultiSelectChip(text, isOn) {
                                            val next = if (isOn) includedNow - cid else includedNow + cid
                                            textIncluded = textIncluded + (field.key to next)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            Column(
                Modifier.fillMaxWidth().background(AppleTheme.colors.card).padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    enabled = !submitted,
                    onClick = {
                        if (submitted) return@Button
                        submitted = true
                        val resolution = MergeResolution(
                            choiceWinners = choiceWinners,
                            textIncluded = textIncluded
                        )
                        // Что НЕ выбрано в одноместных полях — не выбрасываем молча:
                        // фиксируем отдельной заметкой у итоговой компании, прежде
                        // чем данные-источники исчезнут после mergeCompanies().
                        val discardedLines = mutableListOf<String>()
                        preview.choiceFields.forEach { field ->
                            val winnerId = choiceWinners[field.key] ?: field.options.first().contactId
                            val label = context.getString(companyFieldLabelRes(field.key))
                            field.options.filter { it.contactId != winnerId }.forEach { opt ->
                                val fromName = companiesList.firstOrNull { it.id == opt.contactId }?.let { companyShortName(it) } ?: ""
                                val valueText = if (field.kind == MergeFieldKind.ENUM)
                                    companyEnumDisplayLabel(context, field.key, opt.displayValue)
                                else context.getString(R.string.dup_discarded_logo_value)
                                discardedLines.add("$label ($fromName): $valueText")
                            }
                        }

                        val snapshot = AppStateStore.mergeCompanies(companyIds, resolution)
                        val keepId = companyIds.firstOrNull { AppStateStore.getCompanyById(it) != null }

                        // Заметка создаётся ПОСЛЕ снапшота — undoMergeCompanies(snapshot)
                        // о ней не знает и не уберёт её при отмене. Запоминаем id, чтобы
                        // удалить её вручную, если пользователь нажмёт «Отменить».
                        var discardedNoteId: String? = null
                        if (snapshot != null && discardedLines.isNotEmpty() && keepId != null) {
                            discardedNoteId = AppStateStore.generateId()
                            val title = context.getString(R.string.dup_discarded_note_title)
                            AppStateStore.addNote(
                                Note(
                                    id = discardedNoteId,
                                    companyId = keepId,
                                    type = NoteType.GENERAL,
                                    text = title + "\n" + discardedLines.joinToString("\n"),
                                    isImportant = false,
                                    createdAt = "", updatedAt = ""
                                )
                            )
                        }

                        // Навигация — ПОСЛЕ снэкбара (см. MergeResolveScreen — тот же
                        // повод: экран уходит с композиции раньше корутины иначе).
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = context.getString(R.string.dup_merged_snackbar),
                                actionLabel = context.getString(R.string.dup_undo),
                                // Long, не Short — см. тот же фикс в MergeResolveScreen.kt
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed && snapshot != null) {
                                AppStateStore.undoMergeCompanies(snapshot)
                                discardedNoteId?.let { AppStateStore.deleteNote(it) }
                                onDone(null)
                            } else {
                                onDone(keepId)
                            }
                        }
                    },
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) { Text(stringResource(R.string.dup_confirm_merge), fontWeight = FontWeight.Bold) }
            }
        }
    }
}
