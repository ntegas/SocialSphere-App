package com.example.ui.screens

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
import com.example.data.AppStateStore
import com.example.model.*

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

    var phones by remember { mutableStateOf(originalCompany?.phones ?: emptyList()) }
    var emails by remember { mutableStateOf(originalCompany?.emails ?: emptyList()) }
    var addresses by remember { mutableStateOf(originalCompany?.addresses ?: emptyList()) }

    var companyNote by remember { mutableStateOf("") }
    
    // People
    val relatedRelations = remember(companyId) { 
        if (companyId != null) AppStateStore.companyRelations.filter { it.companyId == companyId } else emptyList() 
    }
    var showRelationEditDialog by remember { mutableStateOf<ContactCompanyRelation?>(null) }
    var showSelectContactDropdown by remember { mutableStateOf(false) }

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
                
                DropdownField("Индустрия", industry.name, Industry.values().map { it.name }) { industry = Industry.valueOf(it) }
                
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
                Text("Телефоны", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (phones.isEmpty()) {
                    OutlinedTextField(
                        value = "", onValueChange = {},
                        label = { Text("Телефон компании") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    phones.forEach { phone ->
                        OutlinedTextField(
                            value = phone.number,
                            onValueChange = {},
                            label = { Text(phone.type.name) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { if (phone.isPrimary) Icon(Icons.Default.Star, "Основной", tint = MaterialTheme.colorScheme.primary) }
                        )
                    }
                }
                TextButton(onClick = { }) { Text("+ Добавить ещё телефон") }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Email", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (emails.isEmpty()) {
                    OutlinedTextField(
                        value = "", onValueChange = {},
                        label = { Text("Email компании") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    emails.forEach { email ->
                        OutlinedTextField(
                            value = email.email,
                            onValueChange = {},
                            label = { Text(email.type.name) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { if (email.isPrimary) Icon(Icons.Default.Star, "Основной", tint = MaterialTheme.colorScheme.primary) }
                        )
                    }
                }
                TextButton(onClick = { }) { Text("+ Добавить ещё email") }
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
