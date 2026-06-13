package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.label
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyEditScreen(
    companyId: String?,
    onNavigateBack: () -> Unit
) {
    val isEditMode = companyId != null
    val ctxLabel = LocalContext.current
    val originalCompany = remember { companyId?.let { AppStateStore.getCompany(it) } }

    var name by remember { mutableStateOf(originalCompany?.name ?: "") }
    var industry by remember { mutableStateOf(originalCompany?.industry ?: Industry.OTHER) }
    var description by remember { mutableStateOf(originalCompany?.description ?: "") }
    var website by remember { mutableStateOf(originalCompany?.website ?: "") }

    var phones by remember { mutableStateOf(originalCompany?.phones ?: emptyList<ContactPhone>()) }
    var emails by remember { mutableStateOf(originalCompany?.emails ?: emptyList<ContactEmail>()) }
    var addresses by remember { mutableStateOf(originalCompany?.addresses ?: emptyList<Address>()) }

    var companyNote by remember { mutableStateOf("") }

    // Dialog states for phone/email
    var showAddPhone     by remember { mutableStateOf(false) }
    var newPhoneNumber   by remember { mutableStateOf("") }
    var newPhoneType     by remember { mutableStateOf(PhoneType.WORK) }

    var showAddEmail     by remember { mutableStateOf(false) }
    var newEmailAddress  by remember { mutableStateOf("") }
    var newEmailType     by remember { mutableStateOf(EmailType.WORK) }
    
    // People
    val relatedRelations = remember(companyId) { 
        if (companyId != null) AppStateStore.companyRelations.filter { it.companyId == companyId } else emptyList() 
    }
    var showRelationEditDialog by remember { mutableStateOf<ContactCompanyRelation?>(null) }
    var showSelectContactDropdown by remember { mutableStateOf(false) }

    // ── Add phone dialog ──────────────────────────────────────
    if (showAddPhone) {
        AlertDialog(
            onDismissRequest = { showAddPhone = false; newPhoneNumber = "" },
            title = { Text(stringResource(R.string.cce_add_phone), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newPhoneNumber,
                        onValueChange = { newPhoneNumber = it },
                        label = { Text(stringResource(R.string.cce_phone_number)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    DropdownField(
                        label = stringResource(R.string.ce_type),
                        selectedValue = newPhoneType.label(ctxLabel),
                        options = PhoneType.values().map { it.label(ctxLabel) }
                    ) { selected ->
                        newPhoneType = PhoneType.values()
                            .firstOrNull { it.label(ctxLabel) == selected } ?: PhoneType.WORK
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPhoneNumber.isNotBlank()) {
                            val compId = originalCompany?.id ?: java.util.UUID.randomUUID().toString()
                            phones = phones + ContactPhone(
                                id        = java.util.UUID.randomUUID().toString(),
                                contactId = compId,
                                number    = newPhoneNumber.trim(),
                                type      = newPhoneType,
                                isPrimary = phones.isEmpty()
                            )
                            newPhoneNumber = ""
                            showAddPhone = false
                        }
                    },
                    enabled = newPhoneNumber.isNotBlank()
                ) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddPhone = false; newPhoneNumber = "" }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // ── Add email dialog ──────────────────────────────────────
    if (showAddEmail) {
        AlertDialog(
            onDismissRequest = { showAddEmail = false; newEmailAddress = "" },
            title = { Text(stringResource(R.string.cce_add_email), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newEmailAddress,
                        onValueChange = { newEmailAddress = it },
                        label = { Text(stringResource(R.string.cce_email_addr)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    DropdownField(
                        label = stringResource(R.string.ce_type),
                        selectedValue = newEmailType.label(ctxLabel),
                        options = EmailType.values().map { it.label(ctxLabel) }
                    ) { selected ->
                        newEmailType = EmailType.values()
                            .firstOrNull { it.label(ctxLabel) == selected } ?: EmailType.WORK
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newEmailAddress.isNotBlank()) {
                            val compId = originalCompany?.id ?: java.util.UUID.randomUUID().toString()
                            emails = emails + ContactEmail(
                                id        = java.util.UUID.randomUUID().toString(),
                                contactId = compId,
                                email     = newEmailAddress.trim(),
                                type      = newEmailType,
                                isPrimary = emails.isEmpty()
                            )
                            newEmailAddress = ""
                            showAddEmail = false
                        }
                    },
                    enabled = newEmailAddress.isNotBlank()
                ) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddEmail = false; newEmailAddress = "" }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) stringResource(R.string.cce_edit) else stringResource(R.string.cce_new), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val newCompany = Company(
                                id = originalCompany?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name,
                                industry = industry,
                                description = description,
                                website = website,
                                phones = phones,
                                emails = emails,
                                addresses = addresses,
                                createdAt = originalCompany?.createdAt ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            )
                            if (isEditMode) {
                                AppStateStore.updateCompany(newCompany)
                            } else {
                                AppStateStore.addCompany(newCompany)
                            }
                            onNavigateBack()
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Main info
            SectionCard(stringResource(R.string.cce_basic)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Business, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.cce_name)) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        singleLine = true
                    )
                }
                
                DropdownField(stringResource(R.string.cce_industry), industry.label(ctxLabel), Industry.values().map { it.label(ctxLabel) }) { selected -> industry = Industry.values().firstOrNull { it.label(ctxLabel) == selected } ?: industry }
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.cce_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                
                OutlinedTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = { Text(stringResource(R.string.cce_website)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // Contact Data
            SectionCard(stringResource(R.string.cce_contacts)) {
                Text(stringResource(R.string.cce_phones), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))

                if (phones.isEmpty()) {
                    Text(stringResource(R.string.cce_no_phones),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        phones.forEachIndexed { idx, phone ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = phone.number,
                                    onValueChange = { num ->
                                        phones = phones.toMutableList().also { list ->
                                            list[idx] = phone.copy(number = num)
                                        }
                                    },
                                    label = { Text(phone.type.label(ctxLabel)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    leadingIcon = {
                                        if (phone.isPrimary)
                                            Icon(Icons.Default.Star, null,
                                                Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                                IconButton(
                                    onClick = {
                                        phones = phones.toMutableList().also { it.removeAt(idx) }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Close, stringResource(R.string.common_delete),
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { showAddPhone = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cce_add_phone))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Email", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))

                if (emails.isEmpty()) {
                    Text(stringResource(R.string.cce_no_email),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        emails.forEachIndexed { idx, email ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = email.email,
                                    onValueChange = { addr ->
                                        emails = emails.toMutableList().also { list ->
                                            list[idx] = email.copy(email = addr)
                                        }
                                    },
                                    label = { Text(email.type.label(ctxLabel)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    leadingIcon = {
                                        if (email.isPrimary)
                                            Icon(Icons.Default.Star, null,
                                                Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                                IconButton(
                                    onClick = {
                                        emails = emails.toMutableList().also { it.removeAt(idx) }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Close, stringResource(R.string.common_delete),
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { showAddEmail = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cce_add_email))
                }
            }

            // Addresses
            SectionCard(stringResource(R.string.cce_addresses)) {
                val mainOffice = addresses.find { it.addressType == AddressType.OFFICE }
                val branchOffice = addresses.find { it.addressType == AddressType.BRANCH }
                val legalAddress = addresses.find { it.addressType == AddressType.LEGAL }
                val otherAddress = addresses.find { it.addressType == AddressType.OTHER }

                AddressBlock(stringResource(R.string.cce_main_office), mainOffice)
                Spacer(modifier = Modifier.height(12.dp))
                AddressBlock(stringResource(R.string.cce_branch), branchOffice)
                Spacer(modifier = Modifier.height(12.dp))
                AddressBlock(stringResource(R.string.cce_legal_addr), legalAddress)
                Spacer(modifier = Modifier.height(12.dp))
                AddressBlock(stringResource(R.string.cce_other_addr), otherAddress)
            }

            // People
            SectionCard(stringResource(R.string.cce_people)) {
                if (relatedRelations.isEmpty()) {
                    Text(stringResource(R.string.cce_no_related), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        relatedRelations.forEach { relation ->
                            val contact = AppStateStore.getContact(relation.contactId)
                            if (contact != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(contact.firstName + " " + contact.lastName, style = MaterialTheme.typography.titleSmall)
                                            }
                                            TextButton(onClick = { showRelationEditDialog = relation }) {
                                                Text(stringResource(R.string.cce_change_relation), fontSize = 12.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (!relation.position.isNullOrEmpty()) Text(stringResource(R.string.cce_rel_position, relation.position), style = MaterialTheme.typography.bodySmall)
                                        if (!relation.department.isNullOrEmpty()) Text(stringResource(R.string.cce_rel_department, relation.department), style = MaterialTheme.typography.bodySmall)
                                        if (!relation.role.isNullOrEmpty()) Text(stringResource(R.string.cce_rel_role, relation.role), style = MaterialTheme.typography.bodySmall)
                                        if (!relation.responsibilities.isNullOrEmpty()) Text(stringResource(R.string.cce_rel_resp, relation.responsibilities), style = MaterialTheme.typography.bodySmall)
                                        if (!relation.managedAccounts.isNullOrEmpty()) Text(stringResource(R.string.cce_rel_accounts, relation.managedAccounts), style = MaterialTheme.typography.bodySmall)
                                        if (!relation.workNote.isNullOrEmpty()) Text(stringResource(R.string.cce_rel_note, relation.workNote), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                
                Box {
                    Button(
                        onClick = { showSelectContactDropdown = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text(stringResource(R.string.cce_add_person))
                    }
                    DropdownMenu(expanded = showSelectContactDropdown, onDismissRequest = { showSelectContactDropdown = false }) {
                        AppStateStore.contacts.forEach { contact ->
                            DropdownMenuItem(
                                text = { Text("${contact.firstName} ${contact.lastName}") },
                                onClick = { showSelectContactDropdown = false }
                            )
                        }
                    }
                }
            }

            // Notes
            SectionCard(stringResource(R.string.cce_notes)) {
                OutlinedTextField(
                    value = companyNote,
                    onValueChange = { companyNote = it },
                    label = { Text(stringResource(R.string.cce_work_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
        
        showRelationEditDialog?.let { relation ->
            val contact = AppStateStore.getContact(relation.contactId)
            AlertDialog(
                onDismissRequest = { showRelationEditDialog = null },
                title = { Text(stringResource(R.string.cce_rel_with, "${contact?.firstName} ${contact?.lastName}")) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(value = relation.position ?: "", onValueChange = {}, label = { Text(stringResource(R.string.cce_position)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.department ?: "", onValueChange = {}, label = { Text(stringResource(R.string.cce_department)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.role ?: "", onValueChange = {}, label = { Text(stringResource(R.string.cce_role)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.employmentStatus.label(ctxLabel), onValueChange = {}, label = { Text(stringResource(R.string.common_status)) }, modifier = Modifier.fillMaxWidth(), enabled = false)
                        OutlinedTextField(value = relation.responsibilities ?: "", onValueChange = {}, label = { Text(stringResource(R.string.cce_responsibility)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.managedAccounts ?: "", onValueChange = {}, label = { Text(stringResource(R.string.cce_accounts_directions)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.workNote ?: "", onValueChange = {}, label = { Text(stringResource(R.string.cce_work_note)) }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = relation.isPrimary, onCheckedChange = {}, enabled = false)
                            Text(stringResource(R.string.cce_main_company))
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showRelationEditDialog = null }) { Text(stringResource(R.string.cce_done)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRelationEditDialog = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }
    }
}
