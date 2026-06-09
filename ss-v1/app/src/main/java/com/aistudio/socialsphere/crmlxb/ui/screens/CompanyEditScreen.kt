package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyEditScreen(
    companyId: String?,
    onNavigateBack: () -> Unit
) {
    val isEditMode = companyId != null
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
            title = { Text("Добавить телефон", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newPhoneNumber,
                        onValueChange = { newPhoneNumber = it },
                        label = { Text("Номер телефона") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    DropdownField(
                        label = "Тип",
                        selectedValue = newPhoneType.label(),
                        options = PhoneType.values().map { it.label() }
                    ) { selected ->
                        newPhoneType = PhoneType.values()
                            .firstOrNull { it.label() == selected } ?: PhoneType.WORK
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
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddPhone = false; newPhoneNumber = "" }) {
                    Text("Отмена")
                }
            }
        )
    }

    // ── Add email dialog ──────────────────────────────────────
    if (showAddEmail) {
        AlertDialog(
            onDismissRequest = { showAddEmail = false; newEmailAddress = "" },
            title = { Text("Добавить email", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newEmailAddress,
                        onValueChange = { newEmailAddress = it },
                        label = { Text("Email адрес") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    DropdownField(
                        label = "Тип",
                        selectedValue = newEmailType.label(),
                        options = EmailType.values().map { it.label() }
                    ) { selected ->
                        newEmailType = EmailType.values()
                            .firstOrNull { it.label() == selected } ?: EmailType.WORK
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
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddEmail = false; newEmailAddress = "" }) {
                    Text("Отмена")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Редактирование" else "Новая компания", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
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
                        Text("Сохранить")
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
            SectionCard("Основное") {
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
                        label = { Text("Название компании") },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        singleLine = true
                    )
                }
                
                DropdownField("Индустрия", industry.label(), Industry.values().map { it.label() }) { selected -> industry = Industry.values().firstOrNull { it.label() == selected } ?: industry }
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                
                OutlinedTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = { Text("Сайт") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // Contact Data
            SectionCard("Контакты компании") {
                Text("Телефоны", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))

                if (phones.isEmpty()) {
                    Text("Нет телефонов",
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
                                    label = { Text(phone.type.label()) },
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
                                    Icon(Icons.Default.Close, "Удалить",
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
                    Text("Добавить телефон")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Email", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))

                if (emails.isEmpty()) {
                    Text("Нет email",
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
                                    label = { Text(email.type.label()) },
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
                                    Icon(Icons.Default.Close, "Удалить",
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
                    Text("Добавить email")
                }
            }

            // Addresses
            SectionCard("Адреса") {
                val mainOffice = addresses.find { it.addressType == AddressType.OFFICE }
                val branchOffice = addresses.find { it.addressType == AddressType.BRANCH }
                val legalAddress = addresses.find { it.addressType == AddressType.LEGAL }
                val otherAddress = addresses.find { it.addressType == AddressType.OTHER }

                AddressBlock("Основной офис", mainOffice)
                Spacer(modifier = Modifier.height(12.dp))
                AddressBlock("Филиал", branchOffice)
                Spacer(modifier = Modifier.height(12.dp))
                AddressBlock("Юридический адрес", legalAddress)
                Spacer(modifier = Modifier.height(12.dp))
                AddressBlock("Другой адрес", otherAddress)
            }

            // People
            SectionCard("Люди компании") {
                if (relatedRelations.isEmpty()) {
                    Text("Пока нет связанных людей", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
                                                Text("Изменить связь", fontSize = 12.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (!relation.position.isNullOrEmpty()) Text("Должность: ${relation.position}", style = MaterialTheme.typography.bodySmall)
                                        if (!relation.department.isNullOrEmpty()) Text("Отдел: ${relation.department}", style = MaterialTheme.typography.bodySmall)
                                        if (!relation.role.isNullOrEmpty()) Text("Роль: ${relation.role}", style = MaterialTheme.typography.bodySmall)
                                        if (!relation.responsibilities.isNullOrEmpty()) Text("Зоны отв-ти: ${relation.responsibilities}", style = MaterialTheme.typography.bodySmall)
                                        if (!relation.managedAccounts.isNullOrEmpty()) Text("Аккаунты: ${relation.managedAccounts}", style = MaterialTheme.typography.bodySmall)
                                        if (!relation.workNote.isNullOrEmpty()) Text("Заметка: ${relation.workNote}", style = MaterialTheme.typography.bodySmall)
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
                        Text("+ Добавить человека")
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
            SectionCard("Заметки") {
                OutlinedTextField(
                    value = companyNote,
                    onValueChange = { companyNote = it },
                    label = { Text("Рабочая заметка") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
        
        if (showRelationEditDialog != null) {
            val relation = showRelationEditDialog!!
            val contact = AppStateStore.getContact(relation.contactId)
            
            AlertDialog(
                onDismissRequest = { showRelationEditDialog = null },
                title = { Text("Связь: ${contact?.firstName} ${contact?.lastName}") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(value = relation.position ?: "", onValueChange = {}, label = { Text("Должность") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.department ?: "", onValueChange = {}, label = { Text("Отдел") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.role ?: "", onValueChange = {}, label = { Text("Роль") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.employmentStatus.name, onValueChange = {}, label = { Text("Статус") }, modifier = Modifier.fillMaxWidth(), enabled = false)
                        OutlinedTextField(value = relation.responsibilities ?: "", onValueChange = {}, label = { Text("Зона ответственности") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.managedAccounts ?: "", onValueChange = {}, label = { Text("Аккаунты / направления") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = relation.workNote ?: "", onValueChange = {}, label = { Text("Рабочая заметка") }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = relation.isPrimary, onCheckedChange = {})
                            Text("Основная компания")
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showRelationEditDialog = null }) { Text("Готово") }
                },
                dismissButton = {
                    TextButton(onClick = { showRelationEditDialog = null }) { Text("Отмена") }
                }
            )
        }
    }
}
