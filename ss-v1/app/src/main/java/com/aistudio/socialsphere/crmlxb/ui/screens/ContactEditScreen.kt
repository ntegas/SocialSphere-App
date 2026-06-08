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
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactEditScreen(
    contactId: String?,
    onNavigateBack: () -> Unit
) {
    val isEditMode = contactId != null
    val originalContact = remember { contactId?.let { AppStateStore.getContact(it) } }
    val nowIso = { LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) }

    var firstName by remember { mutableStateOf(originalContact?.firstName ?: "") }
    var lastName  by remember { mutableStateOf(originalContact?.lastName  ?: "") }

    // Mutable lists for editing
    var phones     by remember { mutableStateOf(originalContact?.phones     ?: emptyList<ContactPhone>()) }
    var emails     by remember { mutableStateOf(originalContact?.emails     ?: emptyList<ContactEmail>()) }
    var messengers by remember { mutableStateOf(originalContact?.messengers ?: emptyList<Messenger>()) }

    // Company / work
    var selectedCompanyId by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull { it.isPrimary }?.companyId ?: originalContact?.companyRelations?.firstOrNull()?.companyId ?: "") }
    var companyPosition   by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.position ?: "") }
    var companyDept       by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.department ?: "") }
    var workNote          by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.workNote ?: "") }

    // Classification
    var relationshipType   by remember { mutableStateOf(originalContact?.relationshipType   ?: RelationshipType.ACQUAINTANCE) }
    var connectionLevel    by remember { mutableStateOf(originalContact?.connectionLevel    ?: ConnectionLevel.NORMAL) }
    var importanceLevel    by remember { mutableStateOf(originalContact?.importanceLevel    ?: ImportanceLevel.NORMAL) }
    var socialRole         by remember { mutableStateOf(originalContact?.socialRole         ?: SocialRole.REGULAR) }
    var communicationRhythm by remember { mutableStateOf(originalContact?.communicationRhythm ?: CommunicationRhythm.NOT_TRACKED) }
    var contactStatus      by remember { mutableStateOf(originalContact?.contactStatus      ?: ContactStatus.ACTIVE) }

    // New fields
    var nickname        by remember { mutableStateOf(originalContact?.nickname ?: "") }
    var nextStep        by remember { mutableStateOf(originalContact?.nextStep ?: "") }
    var canHelpWith     by remember { mutableStateOf(originalContact?.canHelpWith ?: "") }
    var iCanHelpWith    by remember { mutableStateOf(originalContact?.iCanHelpWith ?: "") }
    var talkingPoints   by remember { mutableStateOf(originalContact?.talkingPoints ?: "") }
    var meetContext     by remember { mutableStateOf(originalContact?.meetContext ?: "") }
    var meetDate        by remember { mutableStateOf(originalContact?.meetDate ?: "") }

    // Tags
    var tags            by remember { mutableStateOf(originalContact?.tags ?: emptyList<String>()) }
    var newTagText      by remember { mutableStateOf("") }

    // Add phone dialog state
    var showAddPhone    by remember { mutableStateOf(false) }
    var newPhone        by remember { mutableStateOf("") }
    var newPhoneType    by remember { mutableStateOf(PhoneType.MOBILE) }

    // Add email dialog state
    var showAddEmail    by remember { mutableStateOf(false) }
    var newEmail        by remember { mutableStateOf("") }
    var newEmailType    by remember { mutableStateOf(EmailType.PERSONAL) }

    // Add messenger dialog state
    var showAddMessenger by remember { mutableStateOf(false) }
    var newMessenger     by remember { mutableStateOf("") }
    var newMessengerType by remember { mutableStateOf(MessengerType.TELEGRAM) }

    fun buildAndSave() {
        val compRelList = if (selectedCompanyId.isNotBlank()) listOf(
            ContactCompanyRelation(
                id = originalContact?.companyRelations?.firstOrNull()?.id ?: UUID.randomUUID().toString(),
                contactId  = originalContact?.id ?: UUID.randomUUID().toString(),
                companyId  = selectedCompanyId,
                position   = companyPosition.ifBlank { null },
                department = companyDept.ifBlank { null },
                role       = null,
                employmentStatus = EmploymentStatus.CURRENT,
                startDate  = null, endDate = null,
                responsibilities = null, managedAccounts = null,
                workNote   = workNote.ifBlank { null },
                officeAddressId = null,
                isPrimary  = true
            )
        ) else originalContact?.companyRelations ?: emptyList()

        val newContact = Contact(
            id               = originalContact?.id ?: UUID.randomUUID().toString(),
            firstName        = firstName.trim(),
            lastName         = lastName.trim(),
            nickname         = nickname.trim().ifBlank { null },
            phones           = phones,
            emails           = emails,
            messengers       = messengers,
            companyRelations = compRelList,
            addresses        = originalContact?.addresses ?: emptyList(),
            notes            = originalContact?.notes ?: emptyList(),
            gifts            = originalContact?.gifts ?: emptyList(),
            sizeInfo         = originalContact?.sizeInfo,
            personalDetails  = originalContact?.personalDetails ?: emptyList(),
            relationshipType  = relationshipType,
            connectionLevel   = connectionLevel,
            importanceLevel   = importanceLevel,
            socialRole        = socialRole,
            communicationRhythm = communicationRhythm,
            contactStatus    = contactStatus,
            nextStep         = nextStep.trim().ifBlank { null },
            canHelpWith      = canHelpWith.trim().ifBlank { null },
            iCanHelpWith     = iCanHelpWith.trim().ifBlank { null },
            talkingPoints    = talkingPoints.trim().ifBlank { null },
            meetContext      = meetContext.trim().ifBlank { null },
            meetDate         = meetDate.trim().ifBlank { null },
            tags             = tags,
            photoUri         = originalContact?.photoUri,
            createdAt        = originalContact?.createdAt ?: nowIso(),
            updatedAt        = nowIso()
        )
        if (isEditMode) AppStateStore.updateContact(newContact)
        else            AppStateStore.addContact(newContact)
        onNavigateBack()
    }

    // ── Dialogs ──────────────────────────────────────────────────────
    if (showAddPhone) {
        AlertDialog(
            onDismissRequest = { showAddPhone = false; newPhone = "" },
            title = { Text("Добавить телефон", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newPhone, onValueChange = { newPhone = it }, label = { Text("Номер") }, modifier = Modifier.fillMaxWidth())
                    DropdownField("Тип", newPhoneType.name, PhoneType.values().map { it.name }) { newPhoneType = PhoneType.valueOf(it) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPhone.isNotBlank()) {
                        phones = phones + ContactPhone(UUID.randomUUID().toString(), originalContact?.id ?: "new", newPhone.trim(), newPhoneType, phones.isEmpty())
                        newPhone = ""; showAddPhone = false
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddPhone = false; newPhone = "" }) { Text("Отмена") } }
        )
    }

    if (showAddEmail) {
        AlertDialog(
            onDismissRequest = { showAddEmail = false; newEmail = "" },
            title = { Text("Добавить email", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newEmail, onValueChange = { newEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                    DropdownField("Тип", newEmailType.name, EmailType.values().map { it.name }) { newEmailType = EmailType.valueOf(it) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newEmail.isNotBlank()) {
                        emails = emails + ContactEmail(UUID.randomUUID().toString(), originalContact?.id ?: "new", newEmail.trim(), newEmailType, emails.isEmpty())
                        newEmail = ""; showAddEmail = false
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddEmail = false; newEmail = "" }) { Text("Отмена") } }
        )
    }

    if (showAddMessenger) {
        AlertDialog(
            onDismissRequest = { showAddMessenger = false; newMessenger = "" },
            title = { Text("Добавить мессенджер", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField("Платформа", newMessengerType.name, MessengerType.values().map { it.name }) { newMessengerType = MessengerType.valueOf(it) }
                    OutlinedTextField(value = newMessenger, onValueChange = { newMessenger = it }, label = { Text("Username / номер") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newMessenger.isNotBlank()) {
                        messengers = messengers + Messenger(UUID.randomUUID().toString(), originalContact?.id ?: "new", newMessengerType, newMessenger.trim(), null, messengers.isEmpty())
                        newMessenger = ""; showAddMessenger = false
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddMessenger = false; newMessenger = "" }) { Text("Отмена") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Редактирование" else "Новый контакт", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Назад") } },
                actions = {
                    Button(
                        onClick = ::buildAndSave,
                        enabled = firstName.isNotBlank(),
                        modifier = Modifier.padding(end = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) { Text("Сохранить") }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Name & photo ──────────────────────────────────────────
            SectionCard("Основное") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(
                            modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            val initial = (firstName.firstOrNull() ?: lastName.firstOrNull() ?: '?').uppercaseChar()
                            Text(initial.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Box(
                            modifier = Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.CameraAlt, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        OutlinedTextField(value = firstName, onValueChange = { firstName = it }, label = { Text("Имя *") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                        OutlinedTextField(value = lastName,  onValueChange = { lastName  = it }, label = { Text("Фамилия") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                        OutlinedTextField(value = nickname,  onValueChange = { nickname  = it }, label = { Text("Прозвище / как зову") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                    }
                }
            }

            // ── Phones ────────────────────────────────────────────────
            SectionCard("Телефоны") {
                if (phones.isEmpty()) {
                    Text("Нет телефонов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        phones.forEachIndexed { idx, phone ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = phone.number, onValueChange = { num -> phones = phones.toMutableList().also { it[idx] = phone.copy(number = num) } },
                                    label = { Text(phone.type.name) }, modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small,
                                    leadingIcon = { if (phone.isPrimary) Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                                )
                                IconButton(onClick = { phones = phones.toMutableList().also { it.removeAt(idx) } }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Close, "Удалить", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { showAddPhone = true }, modifier = Modifier.align(Alignment.Start)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Добавить телефон")
                }
            }

            // ── Emails ────────────────────────────────────────────────
            SectionCard("Email") {
                if (emails.isEmpty()) {
                    Text("Нет email", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        emails.forEachIndexed { idx, email ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = email.email, onValueChange = { v -> emails = emails.toMutableList().also { it[idx] = email.copy(email = v) } },
                                    label = { Text(email.type.name) }, modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small
                                )
                                IconButton(onClick = { emails = emails.toMutableList().also { it.removeAt(idx) } }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Close, "Удалить", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { showAddEmail = true }, modifier = Modifier.align(Alignment.Start)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Добавить email")
                }
            }

            // ── Messengers ────────────────────────────────────────────
            SectionCard("Мессенджеры") {
                if (messengers.isEmpty()) {
                    Text("Нет мессенджеров", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        messengers.forEachIndexed { idx, m ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = m.value, onValueChange = { v -> messengers = messengers.toMutableList().also { it[idx] = m.copy(value = v) } },
                                    label = { Text(m.type.name) }, modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small
                                )
                                IconButton(onClick = { messengers = messengers.toMutableList().also { it.removeAt(idx) } }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Close, "Удалить", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { showAddMessenger = true }, modifier = Modifier.align(Alignment.Start)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Добавить мессенджер")
                }
            }

            // ── Company ───────────────────────────────────────────────
            SectionCard("Компания и работа") {
                var showCompanyDropdown by remember { mutableStateOf(false) }
                val companies = AppStateStore.companies
                val selectedCompanyName = companies.find { it.id == selectedCompanyId }?.name ?: ""

                Box {
                    OutlinedTextField(
                        value = selectedCompanyName, onValueChange = {},
                        label = { Text("Компания") }, modifier = Modifier.fillMaxWidth().clickable { showCompanyDropdown = true },
                        enabled = false, shape = SocialShape.Small,
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    DropdownMenu(expanded = showCompanyDropdown, onDismissRequest = { showCompanyDropdown = false }) {
                        DropdownMenuItem(text = { Text("— Без компании —", color = MaterialTheme.colorScheme.secondary) }, onClick = { selectedCompanyId = ""; showCompanyDropdown = false })
                        HorizontalDivider()
                        companies.forEach { company ->
                            DropdownMenuItem(text = { Text(company.name) }, onClick = { selectedCompanyId = company.id; showCompanyDropdown = false })
                        }
                    }
                }
                if (selectedCompanyId.isNotBlank()) {
                    OutlinedTextField(value = companyPosition, onValueChange = { companyPosition = it }, label = { Text("Должность") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                    OutlinedTextField(value = companyDept,     onValueChange = { companyDept     = it }, label = { Text("Отдел") },    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                    OutlinedTextField(value = workNote,        onValueChange = { workNote        = it }, label = { Text("Заметка о работе") }, modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small)
                }
            }

            // ── Теги ──────────────────────────────────────────────────
            SectionCard("Теги") {
                if (tags.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick  = { tags = tags - tag },
                                label    = { Text(tag) },
                                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                                shape = SocialShape.Full
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value         = newTagText,
                        onValueChange = { newTagText = it },
                        label         = { Text("Новый тег") },
                        modifier      = Modifier.weight(1f),
                        singleLine    = true,
                        shape         = SocialShape.Small,
                        placeholder   = { Text("Клиент VIP, Инвестор…") }
                    )
                    IconButton(
                        onClick = {
                            val tag = newTagText.trim()
                            if (tag.isNotBlank() && tag !in tags) {
                                tags = tags + tag
                                newTagText = ""
                            }
                        },
                        enabled = newTagText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "Нажми × чтобы удалить тег",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // ── Где познакомились ─────────────────────────────────────
            SectionCard("Где познакомились") {
                OutlinedTextField(
                    value = meetContext, onValueChange = { meetContext = it },
                    label = { Text("Контекст знакомства") },
                    placeholder = { Text("Конференция TechSummit 2024…") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = meetDate, onValueChange = { meetDate = it },
                    label = { Text("Дата знакомства") },
                    placeholder = { Text("2024-11-15") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small,
                    singleLine = true
                )
            }

            // ── Следующий шаг и взаимная польза ──────────────────────
            SectionCard("Следующий шаг и польза") {
                OutlinedTextField(
                    value = nextStep, onValueChange = { nextStep = it },
                    label = { Text("Следующий шаг") },
                    placeholder = { Text("Позвонить на следующей неделе…") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = canHelpWith, onValueChange = { canHelpWith = it },
                    label = { Text("Чем может помочь") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = iCanHelpWith, onValueChange = { iCanHelpWith = it },
                    label = { Text("Чем я могу помочь") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = talkingPoints, onValueChange = { talkingPoints = it },
                    label = { Text("Темы для разговора") },
                    placeholder = { Text("Новый проект; путешествия; дети") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small,
                    minLines = 2
                )
            }

            // ── Classification ────────────────────────────────────────
            SectionCard("Классификация") {
                DropdownField("Статус", contactStatus.label(), ContactStatus.values().map { it.label() }) {
                    contactStatus = ContactStatus.values().firstOrNull { s -> s.label() == it } ?: ContactStatus.ACTIVE
                }
                Spacer(Modifier.height(10.dp))
                DropdownField("Уровень связи",  connectionLevel.name,    ConnectionLevel.values().map { it.name })    { connectionLevel    = ConnectionLevel.valueOf(it) }
                Spacer(Modifier.height(10.dp))
                DropdownField("Важность",       importanceLevel.name,    ImportanceLevel.values().map { it.name })    { importanceLevel    = ImportanceLevel.valueOf(it) }
                Spacer(Modifier.height(10.dp))
                DropdownField("Социальная роль",socialRole.name,         SocialRole.values().map { it.name })         { socialRole         = SocialRole.valueOf(it) }
                Spacer(Modifier.height(10.dp))
                DropdownField("Ритм общения",   communicationRhythm.name,CommunicationRhythm.values().map { it.name }){ communicationRhythm = CommunicationRhythm.valueOf(it) }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            content()
        }
    }
}

@Composable
fun DropdownField(label: String, selectedValue: String, options: List<String>, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selectedValue, onValueChange = {},
            label = { Text(label) }, modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            enabled = false, shape = SocialShape.Small,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
            }
        }
    }
}

@Composable
fun AddressBlock(title: String, address: Address?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(value = address?.addressLine ?: "", onValueChange = {}, label = { Text("Адрес") }, modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = address?.city ?: "",    onValueChange = {}, label = { Text("Город") },  modifier = Modifier.weight(1f), shape = SocialShape.Small)
            OutlinedTextField(value = address?.country ?: "", onValueChange = {}, label = { Text("Страна") }, modifier = Modifier.weight(1f), shape = SocialShape.Small)
        }
    }
}
