package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.aistudio.socialsphere.crmlxb.ui.components.TabEditBar
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 1 — РАБОТА
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.workTab(contact: Contact, onNavigateToCompany: (String) -> Unit = {}, ctxLabel: android.content.Context, editing: Boolean = false, onEditingChange: (Boolean) -> Unit = {}) {
    item {
        TabEditBar(isEditing = editing, onEdit = { onEditingChange(true) }, onDone = { onEditingChange(false) })
    }
    item {
        val compRels = contact.companyRelations
        if (compRels.isEmpty()) return@item
        compRels.forEach { rel ->
            val company = AppStateStore.getCompany(rel.companyId)
            CardBlock(title = if (rel.isPrimary) stringResource(R.string.cd_main_workplace) else stringResource(R.string.cd_more)) {
                if (company != null) InfoRow(stringResource(R.string.cd_company), "${company.name} ›",
                    onClick = { onNavigateToCompany(company.id) })
                if (!rel.position.isNullOrBlank())       InfoRow(stringResource(R.string.cd_position),   rel.position)
                if (!rel.department.isNullOrBlank())     InfoRow(stringResource(R.string.cd_department),       rel.department)
                if (!rel.role.isNullOrBlank())           InfoRow(stringResource(R.string.cd_role),        rel.role)
                if (!rel.responsibilities.isNullOrBlank()) InfoRow(stringResource(R.string.cd_tasks),    rel.responsibilities)
                InfoRow(stringResource(R.string.common_status), rel.employmentStatus.label(ctxLabel))
                if (!rel.workNote.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rel.workNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }


    // Чем может помочь / Чем я могу помочь
    item {
        val hasMB = !contact.canHelpWith.isNullOrBlank() || !contact.iCanHelpWith.isNullOrBlank() || !contact.talkingPoints.isNullOrBlank()
        if (hasMB) CardBlock(title = stringResource(R.string.cd_mutual_value)) {
            if (!contact.canHelpWith.isNullOrBlank()) {
                Text(
                    stringResource(R.string.cd_can_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFF059669),
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
                    color = androidx.compose.ui.graphics.Color(0xFFD97706),
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
                    color = androidx.compose.ui.graphics.Color(0xFF3B49C9),
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
                            Text(addr.addressType.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (editing) {
                            IconButton(onClick = { pendingRemoveAddr = addr }) {
                                Icon(Icons.Default.RemoveCircle, stringResource(R.string.common_delete), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
                if (editing) {
                    TextButton(onClick = { editAddr = null; showAddrDialog = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_address))
                    }
                }

                if (showAddrDialog) {
                    val base = editAddr
                    var aLine by remember { mutableStateOf(base?.addressLine ?: "") }
                    var aCity by remember { mutableStateOf(base?.city ?: "") }
                    var aPostal by remember { mutableStateOf(base?.postalCode ?: "") }
                    var aCountry by remember { mutableStateOf(base?.country ?: "") }
                    var aType by remember { mutableStateOf(base?.addressType ?: AddressType.WORK) }
                    val typeOptions = AddressType.values().filter { it in workTypes }
                    AlertDialog(
                        onDismissRequest = { showAddrDialog = false; editAddr = null },
                        title = { Text(stringResource(if (base == null) R.string.cd_add_address else R.string.ce_edit_address), fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = aLine, onValueChange = { aLine = it }, keyboardOptions = CapWords, label = { Text(stringResource(R.string.ce_street_req)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = aCity, onValueChange = { aCity = it }, keyboardOptions = CapWords, label = { Text(stringResource(R.string.ce_city)) }, modifier = Modifier.weight(1f), singleLine = true)
                                    OutlinedTextField(value = aPostal, onValueChange = { aPostal = it }, label = { Text(stringResource(R.string.ce_postal_code)) }, modifier = Modifier.weight(1f), singleLine = true)
                                }
                                OutlinedTextField(value = aCountry, onValueChange = { aCountry = it }, keyboardOptions = CapWords, label = { Text(stringResource(R.string.ce_country)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                DropdownField(stringResource(R.string.ce_address_type), aType.label(ctxLabel), typeOptions.map { it.label(ctxLabel) }) { v -> aType = typeOptions.firstOrNull { it.label(ctxLabel) == v } ?: aType }
                            }
                        },
                        confirmButton = {
                            Button(enabled = aLine.isNotBlank(), onClick = {
                                val targetId = base?.id ?: java.util.UUID.randomUUID().toString()
                                val a = Address(
                                    id = targetId, ownerType = AddressOwnerType.CONTACT, ownerId = contact.id,
                                    addressType = aType, addressLine = aLine.trim(), city = aCity.trim(), country = aCountry.trim(),
                                    postalCode = aPostal.trim().ifBlank { null }, latitude = base?.latitude, longitude = base?.longitude
                                )
                                val updated = if (base == null) allAddrs + a else allAddrs.map { if (it.id == targetId) a else it }
                                AppStateStore.updateContact(contact.copy(addresses = updated))
                                showAddrDialog = false; editAddr = null
                            }) { Text(stringResource(if (base == null) R.string.common_add else R.string.common_save)) }
                        },
                        dismissButton = { TextButton(onClick = { showAddrDialog = false; editAddr = null }) { Text(stringResource(R.string.common_cancel)) } }
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
