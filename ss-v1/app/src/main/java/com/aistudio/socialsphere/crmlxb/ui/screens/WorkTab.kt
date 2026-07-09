@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 1 — РАБОТА
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.workTab(contact: Contact, onNavigateToCompany: (String) -> Unit = {}, ctxLabel: android.content.Context, editing: Boolean = false, onEditingChange: (Boolean) -> Unit = {}) {
    // Кнопка «Изменить»/«Готово» этой вкладки убрана — режим правки теперь
    // включается ОДНОЙ кнопкой в шапке карточки контакта, общей на все вкладки.
    // Профессия без компании (v12) — видна на вкладке Работа даже когда
    // мест работы нет («электрик», «нотариус»). ФИКС (аудит 2026-07-06):
    // раньше не показывалась пустой в режиме правки (единственное поле
    // вкладки без `|| editing`) — теперь общий ProfessionRow (тот же, что
    // и в Обзоре) сам решает это через свой параметр editing.
    item {
        // Без CardBlock-заголовка: ProfessionRow сам подписывает поле —
        // повторять «Профессия» дважды (заголовок карточки + внутренний
        // лейбл) не нужно.
        if (!contact.profession.isNullOrBlank() || editing) {
            CardBlock {
                ProfessionRow(contact = contact, editing = editing)
            }
        }
    }

    item {
        // Правка мест работы ПРЯМО на вкладке (фидбэк владельца 2026-07-03:
        // «чтобы я не искал, где это редактировать») — через ЕДИНЫЕ
        // WorkplaceAddFlow/WorkplaceEditDialog, общие с Обзором.
        var showAddWork by remember { mutableStateOf(false) }
        var editingRel by remember { mutableStateOf<ContactCompanyRelation?>(null) }
        var removingRel by remember { mutableStateOf<ContactCompanyRelation?>(null) }
        if (showAddWork) WorkplaceAddFlow(contact = contact, onDismiss = { showAddWork = false })
        editingRel?.let { rel ->
            WorkplaceEditDialog(contact = contact, rel = rel, onDismiss = { editingRel = null })
        }
        removingRel?.let { rel ->
            AlertDialog(
                onDismissRequest = { removingRel = null },
                title = { Text(stringResource(R.string.cd_remove_company_title), fontWeight = FontWeight.Bold) },
                confirmButton = {
                    Button(onClick = {
                        AppStateStore.updateContact(contact.copy(
                            companyRelations = contact.companyRelations.filter { it.id != rel.id }
                        ))
                        removingRel = null
                    }) { Text(stringResource(R.string.cd_remove)) }
                },
                dismissButton = { TextButton(onClick = { removingRel = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
        if (editing) {
            TextButton(onClick = { showAddWork = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.cd_add_company))
            }
        }
        val compRels = contact.companyRelations
        if (compRels.isEmpty()) return@item
        compRels.forEach { rel ->
            val company = AppStateStore.getCompany(rel.companyId)
            CardBlock(title = if (rel.isPrimary) stringResource(R.string.cd_main_workplace) else stringResource(R.string.cd_more)) {
                if (editing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { editingRel = rel }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, stringResource(R.string.common_edit),
                                Modifier.size(15.dp), tint = AppleTheme.colors.brand)
                        }
                        IconButton(onClick = { removingRel = rel }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.cd_remove_company),
                                Modifier.size(15.dp), tint = AppleTheme.colors.red)
                        }
                    }
                }
                if (company != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onNavigateToCompany(company.id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium).background(AppleTheme.colors.brand),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(company.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(company.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(company.industry.label(ctxLabel), company.addresses.firstOrNull()?.city.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel
                            )
                        }
                        val isFormer = rel.employmentStatus == EmploymentStatus.FORMER
                        Box(
                            Modifier.clip(RoundedCornerShape(percent = 50))
                                .background(if (isFormer) AppleTheme.colors.fill else AppleTheme.colors.brand.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(rel.employmentStatus.label(ctxLabel), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isFormer) AppleTheme.colors.secondaryLabel else AppleTheme.colors.brand)
                        }
                    }
                    if (!rel.position.isNullOrBlank() || !rel.department.isNullOrBlank() || !rel.startDate.isNullOrBlank()) {
                        HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                // Должность / Отдел / С года — три колонки, как в макете
                if (!rel.position.isNullOrBlank() || !rel.department.isNullOrBlank() || !rel.startDate.isNullOrBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!rel.position.isNullOrBlank()) MiniField(stringResource(R.string.cd_position), rel.position, Modifier.weight(1f))
                        if (!rel.department.isNullOrBlank()) MiniField(stringResource(R.string.cd_department), rel.department, Modifier.weight(1f))
                        if (!rel.startDate.isNullOrBlank()) MiniField(stringResource(R.string.cd_since_year), rel.startDate.take(4), Modifier.weight(1f))
                    }
                }
                if (!rel.role.isNullOrBlank()) InfoRow(stringResource(R.string.cd_role), rel.role)
                if (!rel.workNote.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rel.workNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel
                    )
                }
            }
            if (!rel.managedAccounts.isNullOrBlank()) {
                CardBlock(title = stringResource(R.string.cd_key_accounts)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rel.managedAccounts.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { acc ->
                            Box(
                                Modifier.clip(RoundedCornerShape(percent = 50)).background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text(acc, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.brand, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }
            if (!rel.responsibilities.isNullOrBlank()) {
                CardBlock(title = stringResource(R.string.cd_responsibility_zone)) {
                    Text(rel.responsibilities, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }


    // Чем может помочь / Чем я могу помочь. В режиме «Изменить» блок ВИДЕН
    // даже пустым и даёт текстовые поля (фидбэк владельца 2026-07-02: поля
    // были скрыты — заполнить «чем помочь» было негде).
    item {
        val hasMB = !contact.canHelpWith.isNullOrBlank() || !contact.iCanHelpWith.isNullOrBlank() || !contact.talkingPoints.isNullOrBlank()
        if (editing) CardBlock(title = stringResource(R.string.cd_mutual_value)) {
            var canHelp   by remember(contact.id) { mutableStateOf(contact.canHelpWith ?: "") }
            var iCanHelp  by remember(contact.id) { mutableStateOf(contact.iCanHelpWith ?: "") }
            var talking   by remember(contact.id) { mutableStateOf(contact.talkingPoints ?: "") }
            OutlinedTextField(value = canHelp, onValueChange = { canHelp = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_can_help)) },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = iCanHelp, onValueChange = { iCanHelp = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_i_can_help)) },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = talking, onValueChange = { talking = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_talking_points)) },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    AppStateStore.updateContact(contact.copy(
                        canHelpWith   = canHelp.trim().ifBlank { null },
                        iCanHelpWith  = iCanHelp.trim().ifBlank { null },
                        talkingPoints = talking.trim().ifBlank { null }
                    ))
                    onEditingChange(false)
                },
                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium,
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) { Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold) }
        }
        else if (hasMB) CardBlock(title = stringResource(R.string.cd_mutual_value)) {
            if (!contact.canHelpWith.isNullOrBlank()) {
                Text(
                    stringResource(R.string.cd_can_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppleTheme.colors.green,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(contact.canHelpWith, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            if (!contact.iCanHelpWith.isNullOrBlank()) {
                Text(
                    stringResource(R.string.cd_i_can_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppleTheme.colors.orange,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(contact.iCanHelpWith, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            if (!contact.talkingPoints.isNullOrBlank()) {
                Text(
                    stringResource(R.string.cd_talking_points),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppleTheme.colors.brand,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(contact.talkingPoints, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // Work notes
    item {
        val workNotes = AppStateStore.notes.filter {
            it.contactId == contact.id && it.type == NoteType.WORK
        }
        if (workNotes.isNotEmpty()) {
            CardBlock(title = stringResource(R.string.cd_work_notes)) {
                workNotes.forEach { note ->
                    Text("• ${note.text}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    // Рабочий адрес (WORK/OFFICE/BRANCH/LEGAL)
    item {
        val workTypes = setOf(AddressType.WORK, AddressType.OFFICE, AddressType.BRANCH, AddressType.LEGAL)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val allAddrs = AppStateStore.addresses.filter { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
        val workAddrs = allAddrs.filter { it.addressType in workTypes }
        if (workAddrs.isNotEmpty() || editing) {
            CardBlock(title = stringResource(R.string.cd_work_address)) {
                var editAddr by remember { mutableStateOf<Address?>(null) }
                var showAddrDialog by remember { mutableStateOf(false) }
                var pendingRemoveAddr by remember { mutableStateOf<Address?>(null) }

                workAddrs.forEach { addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (editing) Modifier.clickable { editAddr = addr; showAddrDialog = true } else Modifier)
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOf(addr.addressLine, addr.city, addr.postalCode.orEmpty(), addr.country).filter { it.isNotBlank() }.joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(addr.addressType.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        if (editing) {
                            IconButton(onClick = { pendingRemoveAddr = addr }) {
                                Icon(Icons.Default.RemoveCircle, stringResource(R.string.common_delete), Modifier.size(20.dp), tint = AppleTheme.colors.red)
                            }
                        } else {
                            ActionSquare(Icons.Outlined.Map, stringResource(R.string.cd_map)) {
                                if (addr.latitude != null && addr.longitude != null)
                                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(ctx, addr.latitude, addr.longitude)
                                else
                                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(ctx, "${addr.addressLine}, ${addr.city}")
                            }
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                if (editing) {
                    TextButton(onClick = { editAddr = null; showAddrDialog = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_address))
                    }
                }

                if (showAddrDialog) {
                    // ФИКС (аудит 2026-07-06): раньше здесь был свой дубль диалога с
                    // урезанным списком типов (только WORK/OFFICE/BRANCH/LEGAL) —
                    // сменить тип обратно на «домашний»/«другое» можно было только
                    // со вкладки Связь. Общий AddressEditDialog (AddressComponents.kt)
                    // даёт ПОЛНЫЙ список типов везде, как и задумано.
                    val addrScope = rememberCoroutineScope()
                    AddressEditDialog(
                        base = editAddr,
                        ownerId = contact.id,
                        scope = addrScope,
                        // Новый адрес с ЭТОЙ вкладки по умолчанию — рабочий, иначе
                        // (дефолт HOME) он сразу пропал бы из списка Работы, в
                        // котором показаны только рабочие типы (workAddrs выше).
                        defaultType = AddressType.WORK,
                        onDismiss = { showAddrDialog = false; editAddr = null },
                        onCommit = { a ->
                            val updated = if (allAddrs.none { it.id == a.id }) allAddrs + a
                                          else allAddrs.map { if (it.id == a.id) a else it }
                            AppStateStore.updateContact(contact.copy(addresses = updated))
                        },
                        onGeocoded = { a ->
                            AppStateStore.getContact(contact.id)?.let { fresh ->
                                AppStateStore.updateContact(fresh.copy(
                                    addresses = fresh.addresses.map { if (it.id == a.id) a else it }
                                ))
                            }
                        }
                    )
                }
                pendingRemoveAddr?.let { ra ->
                    AlertDialog(
                        onDismissRequest = { pendingRemoveAddr = null },
                        title = { Text(stringResource(R.string.ce_remove_address_q), fontWeight = FontWeight.Bold) },
                        text = { Text(listOf(ra.addressLine, ra.city).filter { it.isNotBlank() }.joinToString(", ")) },
                        confirmButton = {
                            Button(onClick = {
                                AppStateStore.updateContact(contact.copy(addresses = allAddrs.filter { it.id != ra.id }))
                                pendingRemoveAddr = null
                            }) { Text(stringResource(R.string.common_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { pendingRemoveAddr = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
                }
            }
        }
    }
}

/** Подписанное read-only мини-поле в сетке (Должность/Отдел/С года — как в макете). */
@Composable
private fun MiniField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
