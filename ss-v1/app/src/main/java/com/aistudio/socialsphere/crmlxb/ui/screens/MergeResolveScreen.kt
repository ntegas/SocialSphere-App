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
private fun fieldLabelRes(key: String): Int = when (key) {
    "firstName" -> R.string.scan_first_name
    "lastName" -> R.string.scan_last_name
    "middleName" -> R.string.ce_middle_name
    "namePrefix" -> R.string.ce_name_prefix
    "nameSuffix" -> R.string.ce_name_suffix
    "phoneticFirstName" -> R.string.ce_phonetic_first_name
    "phoneticMiddleName" -> R.string.ce_phonetic_middle_name
    "phoneticLastName" -> R.string.ce_phonetic_last_name
    "meetDate" -> R.string.ce_meet_date
    "photoUri" -> R.string.dup_field_photo
    "relationshipType" -> R.string.filter_relation
    "importanceLevel" -> R.string.filter_importance
    "socialRole" -> R.string.filter_social_role
    "communicationRhythm" -> R.string.filter_rhythm
    "contactStatus" -> R.string.filter_status
    "nickname" -> R.string.ce_nickname
    "profession" -> R.string.ce_profession
    "nextStep" -> R.string.ce_next_step
    "canHelpWith" -> R.string.ce_can_help
    "iCanHelpWith" -> R.string.ce_i_can_help
    "talkingPoints" -> R.string.ce_talking_points
    "meetContext" -> R.string.ce_meet_context
    "familyNote" -> R.string.cd_family_note
    "sizeClothing" -> R.string.cd_clothes
    "sizeShoe" -> R.string.cd_shoes
    "sizeRing" -> R.string.cd_ring
    "sizeOther" -> R.string.common_other
    else -> R.string.dup_field_photo
}

@androidx.annotation.StringRes
private fun listFieldLabelRes(key: String): Int = when (key) {
    "phones" -> R.string.dup_will_merge_phones
    "emails" -> R.string.dup_will_merge_emails
    "messengers" -> R.string.dup_will_merge_messengers
    "addresses" -> R.string.dup_will_merge_addresses
    "companyRelations" -> R.string.dup_will_merge_company
    "personalDetails" -> R.string.dup_will_merge_personal
    "tags" -> R.string.ce_tags
    "notes" -> R.string.dup_will_merge_notes
    "gifts" -> R.string.dup_will_merge_gifts
    else -> R.string.dup_field_photo
}

private fun enumDisplayLabel(context: android.content.Context, key: String, raw: String): String = try {
    when (key) {
        "relationshipType" -> enumValueOf<RelationshipType>(raw).label(context)
        "importanceLevel" -> enumValueOf<ImportanceLevel>(raw).label(context)
        "socialRole" -> enumValueOf<SocialRole>(raw).label(context)
        "communicationRhythm" -> enumValueOf<CommunicationRhythm>(raw).label(context)
        "contactStatus" -> enumValueOf<ContactStatus>(raw).label(context)
        else -> raw
    }
} catch (e: Exception) { raw }

private fun contactShortName(c: Contact): String = "${c.firstName} ${c.lastName}".trim().ifBlank { c.id.take(6) }

/**
 * Шаг 2 объединения: постатейный выбор. Списочные поля объединяются молча
 * (показаны как факт), текстовые/структурные поля (имя, фамилия, свободный
 * текст, размеры) — комбинируются через " / " с чипами включения, ничего не
 * пропадает даже без явного решения. Enum-поля модели и фото физически
 * одноместные — тут выбор неизбежен, но не выбранные варианты не исчезают
 * совсем: при подтверждении они автоматически сохраняются отдельной заметкой
 * у итогового контакта (фидбэк владельца 2026-07-13: «ничего не должно
 * пропадать, что остаётся — то я и правлю»).
 */
@Composable
fun MergeResolveScreen(
    contactIds: List<String>,
    // null — вернуться на шаг выбора (например, слияние не выполнено);
    // non-null id — сразу открыть карточку получившегося контакта
    // (владелец правит объединённое имя/поля сразу же, без лишнего перехода).
    onDone: (String?) -> Unit
) {
    val context = LocalContext.current
    val contacts = remember(contactIds) { contactIds.mapNotNull { AppStateStore.getContact(it) } }
    val preview = remember(contacts) { computeMergePreview(contacts) }

    var choiceWinners by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var textIncluded by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    // Защита от повторного тапа, пока идёт Snackbar-пауза перед переходом на карточку.
    var submitted by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (contacts.size != contactIds.size || contacts.size < 2) {
        // Один из выбранных контактов исчез (уже слит/удалён где-то ещё) — не крашим.
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
                    contacts.joinToString(" + ") { contactShortName(it) },
                    style = MaterialTheme.typography.bodyMedium, color = AppleTheme.colors.secondaryLabel
                )

                // ── Списочные поля: read-only факт, без вопроса ──
                val nonZeroLists = preview.listCounts.filterValues { counts -> counts.sum() > 0 }
                if (nonZeroLists.isNotEmpty()) {
                    MergeSectionCard(stringResource(R.string.dup_will_merge_header)) {
                        nonZeroLists.forEach { (key, counts) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(listFieldLabelRes(key)), style = MaterialTheme.typography.bodyMedium)
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
                            stringResource(R.string.dup_conflicts_hint),
                            style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel
                        )
                        preview.choiceFields.forEach { field ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(stringResource(fieldLabelRes(field.key)),
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
                                            val display = enumDisplayLabel(context, field.key, opt.displayValue)
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
                                Text(stringResource(fieldLabelRes(field.key)),
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
                        // фиксируем отдельной заметкой у итогового контакта, прежде
                        // чем данные-источники исчезнут после mergeContacts().
                        val discardedLines = mutableListOf<String>()
                        preview.choiceFields.forEach { field ->
                            val winnerId = choiceWinners[field.key] ?: field.options.first().contactId
                            val label = context.getString(fieldLabelRes(field.key))
                            field.options.filter { it.contactId != winnerId }.forEach { opt ->
                                val fromName = contacts.firstOrNull { it.id == opt.contactId }?.let { contactShortName(it) } ?: ""
                                val valueText = if (field.kind == MergeFieldKind.ENUM)
                                    enumDisplayLabel(context, field.key, opt.displayValue)
                                else context.getString(R.string.dup_discarded_photo_value)
                                discardedLines.add("$label ($fromName): $valueText")
                            }
                        }

                        val snapshot = AppStateStore.mergeContacts(contactIds, resolution)
                        val keepId = contactIds.firstOrNull { AppStateStore.getContact(it) != null }

                        // Заметка создаётся ПОСЛЕ снапшота — undoMerge(snapshot) о ней
                        // не знает и не уберёт её при отмене. Запоминаем id, чтобы
                        // удалить её вручную, если пользователь нажмёт «Отменить».
                        var discardedNoteId: String? = null
                        if (snapshot != null && discardedLines.isNotEmpty() && keepId != null) {
                            discardedNoteId = AppStateStore.generateId()
                            val title = context.getString(R.string.dup_discarded_note_title)
                            AppStateStore.addNote(
                                Note(
                                    id = discardedNoteId,
                                    contactId = keepId,
                                    type = NoteType.GENERAL,
                                    text = title + "\n" + discardedLines.joinToString("\n"),
                                    isImportant = false,
                                    createdAt = "", updatedAt = ""
                                )
                            )
                        }

                        // Навигация — ПОСЛЕ снэкбара (а не сразу), иначе «Объединено ·
                        // Отменить» физически не успевает показаться: экран слияния
                        // уходит с композиции раньше, чем корутина успевает его открыть.
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = context.getString(R.string.dup_merged_snackbar),
                                actionLabel = context.getString(R.string.dup_undo),
                                // Long (~10 сек), не Short (~4 сек) — слияние переносит
                                // данные нескольких карточек в одну, за 4 сек не всегда
                                // успеваешь среагировать (тот же паттерн, что у Gmail/
                                // Google Photos для undo-after-merge/delete).
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed && snapshot != null) {
                                AppStateStore.undoMerge(snapshot)
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

@Composable
internal fun MergeSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18,
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
