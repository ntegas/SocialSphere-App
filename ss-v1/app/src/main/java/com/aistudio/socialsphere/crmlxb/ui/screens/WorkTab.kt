@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.CopyableText
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 1 — РАБОТА
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.workTab(contact: Contact, onNavigateToCompany: (String) -> Unit = {}, ctxLabel: android.content.Context) {
    // РЕШЕНИЕ ВЛАДЕЛЬЦА 2026-10-08: у карточки нет своего режима правки — профессия,
    // основная работа, «чем могу помочь» и рабочие адреса правятся в единой форме
    // ContactEditScreen (кнопка «Редактировать» в шапке). Здесь — просмотр и управление
    // ДОПОЛНИТЕЛЬНЫМИ местами работы (форма редактирует только основное), всегда видимое.
    // Профессия без компании (v12) — видна на вкладке Работа даже когда
    // мест работы нет («электрик», «нотариус»).
    item {
        // Без CardBlock-заголовка: ProfessionRow сам подписывает поле —
        // повторять «Профессия» дважды (заголовок карточки + внутренний
        // лейбл) не нужно.
        // ФИКС (2026-07-12, фидбэк владельца): блок виден ВСЕГДА.
        CardBlock {
            ProfessionRow(contact = contact)
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
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaConfirmDialog(
                onDismiss = { removingRel = null },
                title = stringResource(R.string.cd_remove_company_title),
                confirmText = stringResource(R.string.cd_remove),
                destructive = true,
                onConfirm = {
                    AppStateStore.updateContact(contact, contact.copy(
                        companyRelations = contact.companyRelations.filter { it.id != rel.id }
                    ))
                    removingRel = null
                }
            )
        }
        TextButton(onClick = { showAddWork = true }) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.cd_add_company))
        }
        val compRels = contact.companyRelations
        if (compRels.isEmpty()) return@item
        compRels.forEach { rel ->
            val company = AppStateStore.getCompany(rel.companyId)
            CardBlock(title = if (rel.isPrimary) stringResource(R.string.cd_main_workplace) else stringResource(R.string.cd_more)) {
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
                            CopyableText(company.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(company.industry.label(ctxLabel), company.addresses.firstOrNull()?.city.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel
                            )
                        }
                        val isFormer = rel.employmentStatus == EmploymentStatus.FORMER
                        Box(
                            Modifier.clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Full)
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
                                Modifier.clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Full).background(AppleTheme.colors.brand.copy(alpha = 0.10f))
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


    // Чем может помочь / Чем я могу помочь. Правка — в единой форме ContactEditScreen
    // (строка «Следующий шаг и ценность»); здесь только просмотр.
    item {
        val hasMB = !contact.canHelpWith.isNullOrBlank() || !contact.iCanHelpWith.isNullOrBlank() || !contact.talkingPoints.isNullOrBlank()
        // ФИКС (2026-07-12, фидбэк владельца): блок виден ВСЕГДА, не только когда
        // заполнен — иначе пустое поле в просмотре негде было найти.
        CardBlock(title = stringResource(R.string.cd_mutual_value)) {
            if (!hasMB) {
                Text(
                    stringResource(R.string.cd_ford_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.secondaryLabel
                )
            }
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
        // ФИКС (2026-07-12, фидбэк владельца): блок и «+Добавить» видны ВСЕГДА.
        run {
            CardBlock(title = stringResource(R.string.cd_work_address)) {
                var showAddrDialog by remember { mutableStateOf(false) }

                workAddrs.forEach { addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOf(addr.addressLine, addr.district.orEmpty(), addr.city, addr.postalCode.orEmpty(), addr.country).filter { it.isNotBlank() }.joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(addr.addressType.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        ActionSquare(Icons.Outlined.Map, stringResource(R.string.cd_map)) {
                            if (addr.latitude != null && addr.longitude != null)
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(ctx, addr.latitude, addr.longitude)
                            else
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(ctx, "${addr.addressLine}, ${addr.city}")
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                TextButton(onClick = { showAddrDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_address))
                }

                if (showAddrDialog) {
                    // ФИКС (аудит 2026-07-06): раньше здесь был свой дубль диалога с
                    // урезанным списком типов (только WORK/OFFICE/BRANCH/LEGAL) —
                    // сменить тип обратно на «домашний»/«другое» можно было только
                    // со вкладки Связь. Общий AddressEditDialog (AddressComponents.kt)
                    // даёт ПОЛНЫЙ список типов везде, как и задумано.
                    val addrScope = rememberCoroutineScope()
                    AddressEditDialog(
                        base = null,
                        ownerId = contact.id,
                        scope = addrScope,
                        // Новый адрес с ЭТОЙ вкладки по умолчанию — рабочий, иначе
                        // (дефолт HOME) он сразу пропал бы из списка Работы, в
                        // котором показаны только рабочие типы (workAddrs выше).
                        defaultType = AddressType.WORK,
                        onDismiss = { showAddrDialog = false },
                        onCommit = { a ->
                            val updated = if (allAddrs.none { it.id == a.id }) allAddrs + a
                                          else allAddrs.map { if (it.id == a.id) a else it }
                            AppStateStore.updateContact(contact, contact.copy(addresses = updated))
                        },
                        onGeocoded = { a ->
                            AppStateStore.getContact(contact.id)?.let { fresh ->
                                AppStateStore.updateContact(fresh, fresh.copy(
                                    addresses = fresh.addresses.map { if (it.id == a.id) a else it }
                                ))
                            }
                        }
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
        CopyableText(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
