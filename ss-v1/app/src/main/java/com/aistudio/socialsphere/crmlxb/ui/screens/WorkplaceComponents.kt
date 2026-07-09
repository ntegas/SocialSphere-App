package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.label

/**
 * ЕДИНЫЙ блок «Профессия без компании» (v12, поле Contact.profession) —
 * используется и в Обзоре (блок O), и на вкладке Работа (фидбэк владельца
 * 2026-07-03: не дублировать логику по экранам). ФИКС (аудит 2026-07-06):
 * раньше на Работе поле показывалось ТОЛЬКО когда уже заполнено (без
 * `editing`-веточки, как у остальных полей вкладки), а в Обзоре не
 * показывалось вообще — ровно тот случай («электрик без компании»), ради
 * которого поле и заводили, был не виден там, где его ищут в первую очередь.
 */
@Composable
fun ProfessionRow(contact: Contact, editing: Boolean) {
    val professionText = contact.profession?.takeIf { it.isNotBlank() }
    if (professionText == null && !editing) return
    var showDialog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .then(if (editing) Modifier.clickable { showDialog = true } else Modifier)
            .padding(vertical = 4.dp)
    ) {
        Text(
            stringResource(R.string.ce_profession),
            style = MaterialTheme.typography.labelSmall,
            color = AppleTheme.colors.secondaryLabel
        )
        Text(
            professionText ?: stringResource(R.string.cd_profession_hint),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (professionText != null) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel
        )
    }
    if (showDialog) {
        var draft by remember { mutableStateOf(contact.profession ?: "") }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_profession),
            onDismiss = { showDialog = false },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                AppStateStore.updateContact(contact.copy(profession = draft.trim().ifBlank { null }))
                showDialog = false
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showDialog = false }
        ) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }
    }
}

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
    // «Без компании — только должность» (фидбэк 2026-07-04): пишем в
    // contact.profession — то же поле, что в форме контакта и WorkTab.
    var noCompanyMode by remember { mutableStateOf(false) }
    var compPosition by remember { mutableStateOf("") }
    var compNote by remember { mutableStateOf("") }

    if (noCompanyMode) {
        var prof by remember { mutableStateOf(contact.profession ?: "") }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_profession),
            onDismiss = { noCompanyMode = false; onDismiss() },
            confirmText = stringResource(R.string.common_save),
            confirmEnabled = prof.isNotBlank(),
            onConfirm = {
                AppStateStore.updateContact(contact.copy(profession = prof.trim()))
                noCompanyMode = false; onDismiss()
            },
            secondaryText = stringResource(R.string.common_back),
            onSecondary = { noCompanyMode = false }
        ) {
            OutlinedTextField(
                value = prof, onValueChange = { prof = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.ce_profession)) },
                placeholder = { Text(stringResource(R.string.ce_profession_hint), color = AppleTheme.colors.tertiaryLabel) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }
        return
    }

    if (showNewCompany) {
        var newCompanyName by remember { mutableStateOf("") }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_new_company_title),
            onDismiss = { showNewCompany = false },
            confirmText = stringResource(R.string.ce_create),
            confirmEnabled = newCompanyName.isNotBlank(),
            onConfirm = {
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
            },
            secondaryText = stringResource(R.string.common_back),
            onSecondary = { showNewCompany = false }
        ) {
            OutlinedTextField(
                value = newCompanyName,
                onValueChange = { newCompanyName = it }, keyboardOptions = CapWords,
                label = { Text(stringResource(R.string.ce_company_name_req)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }
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
            onCreateNew = { showNewCompany = true },
            extraActionText = stringResource(R.string.cd_work_no_company),
            onExtraAction = { noCompanyMode = true }
        )
    } else compSelected?.let { sel ->
        // Шаг 2: должность + режим добавления + необязательная заметка.
        // Режим (фидбэк «ставлю второе место, оба текущие»):
        // 0 = новое основное (старые текущие → прошлые), 1 = параллельное, 2 = прошлое.
        var addMode by remember(sel.id) { mutableStateOf(0) }
        val hasCurrentWork = contact.companyRelations
            .any { it.employmentStatus == EmploymentStatus.CURRENT }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = sel.name,
            onDismiss = onDismiss,
            confirmText = stringResource(R.string.common_add),
            secondaryText = stringResource(R.string.common_back),
            onSecondary = { compSelected = null },
            onConfirm = {
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
            }
        ) {
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
    // Роль/зона ответственности/ключевые аккаунты/заметка — были показаны (только
    // для чтения) в WorkTab, а в CompanyEditScreen редактировались ФИКТИВНО
    // (onValueChange={} на всех полях, кроме статуса — правки молча терялись).
    var role by remember(rel.id) { mutableStateOf(rel.role ?: "") }
    var responsibilities by remember(rel.id) { mutableStateOf(rel.responsibilities ?: "") }
    var managedAccounts by remember(rel.id) { mutableStateOf(rel.managedAccounts ?: "") }
    var workNote by remember(rel.id) { mutableStateOf(rel.workNote ?: "") }
    var isCurrent by remember(rel.id) { mutableStateOf(rel.employmentStatus != EmploymentStatus.FORMER) }
    var isPrimary by remember(rel.id) { mutableStateOf(rel.isPrimary) }
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
        title = companyName,
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            val updated = rel.copy(
                position = position.trim().ifBlank { null },
                department = department.trim().ifBlank { null },
                role = role.trim().ifBlank { null },
                responsibilities = responsibilities.trim().ifBlank { null },
                managedAccounts = managedAccounts.trim().ifBlank { null },
                workNote = workNote.trim().ifBlank { null },
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
        },
        secondaryText = stringResource(R.string.common_cancel),
        onSecondary = onDismiss
    ) {
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
        OutlinedTextField(
            value = role, onValueChange = { role = it }, keyboardOptions = CapSentences,
            label = { Text(stringResource(R.string.cce_role)) },
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
        OutlinedTextField(
            value = responsibilities, onValueChange = { responsibilities = it }, keyboardOptions = CapSentences,
            label = { Text(stringResource(R.string.cce_responsibility)) },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )
        OutlinedTextField(
            value = managedAccounts, onValueChange = { managedAccounts = it }, keyboardOptions = CapSentences,
            label = { Text(stringResource(R.string.cce_accounts_directions)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = workNote, onValueChange = { workNote = it }, keyboardOptions = CapSentences,
            label = { Text(stringResource(R.string.cce_work_note)) },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )
    }
}
