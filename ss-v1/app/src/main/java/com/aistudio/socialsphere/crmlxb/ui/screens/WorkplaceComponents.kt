package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.label

/**
 * ЕДИНЫЙ поток «добавить место работы» (пикер компании с поиском/созданием →
 * должность + режим добавления + заметка). Используется и в Обзоре (блок O),
 * и на вкладке Работа — фидбэк владельца 2026-07-03: функции не должны
 * дублироваться разными реализациями по экранам.
 */
@Composable
fun WorkplaceAddFlow(
    contact: Contact,
    onDismiss: () -> Unit,
) {
    val ctxLabel = LocalContext.current
    var compSelected by remember { mutableStateOf<Company?>(null) }
    var showNewCompany by remember { mutableStateOf(false) }
    var compPosition by remember { mutableStateOf("") }
    var compNote by remember { mutableStateOf("") }

    if (showNewCompany) {
        var newCompanyName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewCompany = false },
            title = { Text(stringResource(R.string.ce_new_company_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newCompanyName,
                    onValueChange = { newCompanyName = it }, keyboardOptions = CapWords,
                    label = { Text(stringResource(R.string.ce_company_name_req)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            },
            confirmButton = {
                Button(
                    enabled = newCompanyName.isNotBlank(),
                    onClick = {
                        val clean = newCompanyName.trim()
                        val existing = AppStateStore.companies
                            .firstOrNull { it.name.equals(clean, ignoreCase = true) }
                        if (existing != null) {
                            compSelected = existing
                        } else {
                            val now = java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            val company = Company(
                                id = java.util.UUID.randomUUID().toString(),
                                name = clean,
                                industry = Industry.OTHER,
                                createdAt = now, updatedAt = now
                            )
                            AppStateStore.addCompany(company)
                            compSelected = company
                        }
                        showNewCompany = false
                    }
                ) { Text(stringResource(R.string.ce_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewCompany = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // Шаг 1: канонический пикер компаний (уже связанные не предлагаем)
    if (compSelected == null) {
        if (!showNewCompany) com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
            title = stringResource(R.string.cd_add_company),
            items = AppStateStore.companies
                .filter { c -> contact.companyRelations.none { it.companyId == c.id } }
                .map { c ->
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                        id = c.id,
                        title = c.name,
                        subtitle = c.industry.label(ctxLabel),
                        isCompany = true
                    )
                },
            onPick = { picked -> compSelected = AppStateStore.companies.firstOrNull { it.id == picked.id } },
            onDismiss = onDismiss,
            searchPlaceholder = stringResource(R.string.ce_search_company),
            emptyText = stringResource(R.string.cd_company_none_hint),
            createNewText = stringResource(R.string.ce_new_company),
            onCreateNew = { showNewCompany = true }
        )
    } else compSelected?.let { sel ->
        // Шаг 2: должность + режим добавления + необязательная заметка.
        // Режим (фидбэк «ставлю второе место, оба текущие»):
        // 0 = новое основное (старые текущие → прошлые), 1 = параллельное, 2 = прошлое.
        var addMode by remember(sel.id) { mutableStateOf(0) }
        val hasCurrentWork = contact.companyRelations
            .any { it.employmentStatus == EmploymentStatus.CURRENT }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(sel.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = compPosition, onValueChange = { compPosition = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_position)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    if (hasCurrentWork) {
                        val modeLabels = listOf(
                            stringResource(R.string.cd_work_mode_primary),
                            stringResource(R.string.cd_work_mode_parallel),
                            stringResource(R.string.cd_work_mode_former)
                        )
                        PillChoiceRow(
                            options = modeLabels,
                            selected = modeLabels[addMode],
                            onSelect = { v -> addMode = modeLabels.indexOf(v).coerceAtLeast(0) }
                        )
                        if (addMode == 0) Text(
                            stringResource(R.string.cd_work_mode_primary_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppleTheme.colors.secondaryLabel
                        )
                    }
                    OutlinedTextField(
                        value = compNote, onValueChange = { compNote = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_fact_note_optional)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newRel = ContactCompanyRelation(
                        id = java.util.UUID.randomUUID().toString(),
                        contactId = contact.id,
                        companyId = sel.id,
                        position = compPosition.ifBlank { null },
                        employmentStatus = if (hasCurrentWork && addMode == 2) EmploymentStatus.FORMER
                                           else EmploymentStatus.CURRENT,
                        isPrimary = !hasCurrentWork || addMode == 0
                    )
                    val restRels = when {
                        hasCurrentWork && addMode == 0 -> contact.companyRelations.map {
                            if (it.employmentStatus == EmploymentStatus.CURRENT)
                                it.copy(employmentStatus = EmploymentStatus.FORMER, isPrimary = false)
                            else it.copy(isPrimary = false)
                        }
                        else -> contact.companyRelations
                    }
                    AppStateStore.updateContact(contact.copy(companyRelations = restRels + newRel))
                    if (compNote.isNotBlank()) {
                        val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        AppStateStore.addNote(Note(
                            id = java.util.UUID.randomUUID().toString(),
                            contactId = contact.id,
                            companyId = null, calendarItemId = null, giftId = null,
                            type = NoteType.WORK,
                            text = compNote.trim(),
                            date = java.time.LocalDate.now().toString(),
                            isImportant = false,
                            createdAt = now, updatedAt = now
                        ))
                    }
                    onDismiss()
                }) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = { TextButton(onClick = { compSelected = null }) { Text(stringResource(R.string.common_back)) } }
        )
    }
}

/**
 * ЕДИНЫЙ диалог правки существующего места работы: должность, отдел,
 * статус Текущее/Прошлое, переключатель «Основное место». Используется
 * в Обзоре и на вкладке Работа.
 */
@Composable
fun WorkplaceEditDialog(
    contact: Contact,
    rel: ContactCompanyRelation,
    onDismiss: () -> Unit,
) {
    val ctxLabel = LocalContext.current
    val companyName = AppStateStore.getCompany(rel.companyId)?.name ?: ""
    var position by remember(rel.id) { mutableStateOf(rel.position ?: "") }
    var department by remember(rel.id) { mutableStateOf(rel.department ?: "") }
    var isCurrent by remember(rel.id) { mutableStateOf(rel.employmentStatus != EmploymentStatus.FORMER) }
    var isPrimary by remember(rel.id) { mutableStateOf(rel.isPrimary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(companyName, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = position, onValueChange = { position = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_position)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = department, onValueChange = { department = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_department)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                val statusLabels = listOf(
                    EmploymentStatus.CURRENT.label(ctxLabel),
                    EmploymentStatus.FORMER.label(ctxLabel)
                )
                PillChoiceRow(
                    options = statusLabels,
                    selected = if (isCurrent) statusLabels[0] else statusLabels[1],
                    onSelect = { v -> isCurrent = v == statusLabels[0] }
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = isPrimary, onCheckedChange = { isPrimary = it })
                    Text(stringResource(R.string.cd_work_primary_place),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val updated = rel.copy(
                    position = position.trim().ifBlank { null },
                    department = department.trim().ifBlank { null },
                    employmentStatus = if (isCurrent) EmploymentStatus.CURRENT else EmploymentStatus.FORMER,
                    isPrimary = isPrimary
                )
                AppStateStore.updateContact(contact.copy(
                    companyRelations = contact.companyRelations.map {
                        when {
                            it.id == rel.id -> updated
                            // Основное место — одно: включили здесь → у прочих снимаем
                            isPrimary -> it.copy(isPrimary = false)
                            else -> it
                        }
                    }
                ))
                onDismiss()
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
