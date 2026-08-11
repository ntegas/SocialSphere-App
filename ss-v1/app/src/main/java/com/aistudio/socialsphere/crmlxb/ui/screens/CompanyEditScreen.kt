package com.aistudio.socialsphere.crmlxb.ui.screens
import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyEditScreen(
    companyId: String?,
    onNavigateBack: () -> Unit
) {
    val isEditMode = companyId != null
    val ctxLabel = LocalContext.current
    val scope = rememberCoroutineScope()
    val originalCompany = remember { companyId?.let { AppStateStore.getCompany(it) } }

    var name by remember { mutableStateOf(originalCompany?.name ?: "") }
    var industry by remember { mutableStateOf(originalCompany?.industry ?: Industry.OTHER) }
    var description by remember { mutableStateOf(originalCompany?.description ?: "") }
    var website by remember { mutableStateOf(originalCompany?.website ?: "") }

    // ФИКС (аудит 2026-08-03, владелец): в отличие от WorkplaceAddFlow/
    // ContactEditScreen/ImportScreens, этот экран не проверял имя новой
    // компании против уже существующих (trim + ignoreCase) — «Электрик» и
    // «электрик» превращались в две разные компании вместо одной группы.
    val duplicateCompany = if (!isEditMode) AppStateStore.findCompanyByName(name) else null

    var phones by remember { mutableStateOf(originalCompany?.phones ?: emptyList<ContactPhone>()) }
    var emails by remember { mutableStateOf(originalCompany?.emails ?: emptyList<ContactEmail>()) }
    var addresses by remember { mutableStateOf(originalCompany?.addresses ?: emptyList<Address>()) }

    // Единственная общая заметка компании — самая свежая GENERAL-заметка с этим
    // companyId. Раньше поле всегда стартовало пустым и введённый текст никуда
    // не сохранялся при «Готово» — тихая потеря данных при каждом редактировании.
    val existingCompanyNote = remember {
        originalCompany?.let { c ->
            AppStateStore.notes.filter { it.companyId == c.id && it.type == NoteType.GENERAL }
                .maxByOrNull { it.updatedAt }
        }
    }
    var companyNote by remember { mutableStateOf(existingCompanyNote?.text ?: "") }

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
    // id компании известен ДО сохранения (паттерн editedContactId из ContactEdit):
    // для новой компании генерируем сразу — связи «добавить сотрудника» не станут orphan
    val editedCompanyId = remember { companyId ?: java.util.UUID.randomUUID().toString() }
    // Отложенные сотрудники: применяются при «Готово» (отмена формы = ничего не меняется)
    val pendingPeople = remember { mutableStateListOf<Pair<Contact, String?>>() }
    var showAddPerson by remember { mutableStateOf(false) }
    var personSelected by remember { mutableStateOf<Contact?>(null) }
    var personPosition by remember { mutableStateOf("") }

    // ── Add phone dialog ──────────────────────────────────────
    if (showAddPhone) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.cce_add_phone),
            onDismiss = { showAddPhone = false; newPhoneNumber = "" },
            confirmText = stringResource(R.string.common_add),
            confirmEnabled = newPhoneNumber.isNotBlank(),
            onConfirm = {
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
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddPhone = false; newPhoneNumber = "" }
        ) {
            OutlinedTextField(
                value = newPhoneNumber,
                onValueChange = { newPhoneNumber = it },
                keyboardOptions = PhoneKeyboard,
                label = { Text(stringResource(R.string.cce_phone_number)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium
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
    }

    // ── Add email dialog ──────────────────────────────────────
    if (showAddEmail) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.cce_add_email),
            onDismiss = { showAddEmail = false; newEmailAddress = "" },
            confirmText = stringResource(R.string.common_add),
            confirmEnabled = newEmailAddress.isNotBlank(),
            onConfirm = {
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
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddEmail = false; newEmailAddress = "" }
        ) {
            OutlinedTextField(
                value = newEmailAddress,
                onValueChange = { newEmailAddress = it },
                keyboardOptions = EmailKeyboard,
                label = { Text(stringResource(R.string.cce_email_addr)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium
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
    }

    // ── Добавить сотрудника: канонический пикер (поиск) + должность ──
    if (showAddPerson) {
        if (personSelected == null) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                title = stringResource(R.string.cce_add_person),
                items = AppStateStore.contacts
                    .filter { c ->
                        relatedRelations.none { it.contactId == c.id } &&
                        pendingPeople.none { it.first.id == c.id }
                    }
                    .map { c ->
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                            id = c.id,
                            title = "${c.firstName} ${c.lastName}".trim(),
                            subtitle = c.companyRelations.firstOrNull()?.position
                        )
                    },
                onPick = { picked -> personSelected = AppStateStore.getContact(picked.id) },
                onDismiss = { showAddPerson = false; personPosition = "" },
                searchPlaceholder = stringResource(R.string.ce_search_contact),
                emptyText = stringResource(R.string.compd_no_candidates)
            )
        } else personSelected?.let { sel ->
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = "${sel.firstName} ${sel.lastName}".trim(),
                onDismiss = { showAddPerson = false; personSelected = null; personPosition = "" },
                confirmText = stringResource(R.string.common_add),
                onConfirm = {
                    pendingPeople.add(sel to personPosition.trim().ifBlank { null })
                    personSelected = null; personPosition = ""; showAddPerson = false
                },
                secondaryText = stringResource(R.string.common_back),
                onSecondary = { personSelected = null }
            ) {
                OutlinedTextField(
                    value = personPosition, onValueChange = { personPosition = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_position)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
        }
    }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Шапка: Отмена · заголовок · Готово (по макету) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.clickable { onNavigateBack() }
                )
                Text(
                    if (isEditMode) stringResource(R.string.cce_edit) else stringResource(R.string.cce_new),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label
                )
                Button(
                        onClick = {
                            val trimmedName = name.trim()
                            val finalCompanyId = duplicateCompany?.id ?: editedCompanyId
                            // Адреса уже создавались с ownerId = editedCompanyId (см.
                            // AddressEditDialog ниже) — если оказалось, что это дубль
                            // существующей компании, переносим их на её реальный id.
                            val finalAddresses = addresses.map {
                                if (it.ownerId == editedCompanyId) it.copy(ownerId = finalCompanyId) else it
                            }
                            val saveNowIso = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            if (duplicateCompany != null) {
                                // Объединяем в существующую компанию, а не затираем её
                                // индустрию/описание/сайт черновиком пустой формы —
                                // добавляем только то, что реально ново (телефоны/почты/адреса).
                                AppStateStore.updateCompany(
                                    duplicateCompany.copy(
                                        phones = duplicateCompany.phones + phones,
                                        emails = duplicateCompany.emails + emails,
                                        addresses = duplicateCompany.addresses + finalAddresses,
                                        updatedAt = saveNowIso
                                    )
                                )
                            } else {
                                val newCompany = Company(
                                    // editedCompanyId известен до сохранения — связи
                                    // «добавить сотрудника» указывают на верный id
                                    id = finalCompanyId,
                                    name = trimmedName,
                                    // logoUri в форме сейчас не редактируется — сохраняем как
                                    // было, а не хардкодим null (иначе будущая фича лого молча
                                    // стиралась бы каждым сохранением, см. У60)
                                    logoUri = originalCompany?.logoUri,
                                    industry = industry,
                                    description = description,
                                    website = website,
                                    phones = phones,
                                    emails = emails,
                                    addresses = finalAddresses,
                                    createdAt = originalCompany?.createdAt ?: saveNowIso,
                                    updatedAt = saveNowIso
                                )
                                if (isEditMode) {
                                    AppStateStore.updateCompany(newCompany)
                                } else {
                                    AppStateStore.addCompany(newCompany)
                                }
                            }
                            // Заметка компании — сохраняем/обновляем/удаляем отдельно,
                            // т.к. Company не хранит notes инлайн (см. AppStateStore.notes).
                            val trimmedNote = companyNote.trim()
                            val noteNow = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            when {
                                trimmedNote.isNotEmpty() && existingCompanyNote != null ->
                                    AppStateStore.updateNote(existingCompanyNote.copy(text = trimmedNote, updatedAt = noteNow))
                                trimmedNote.isNotEmpty() ->
                                    AppStateStore.addNote(Note(
                                        id = java.util.UUID.randomUUID().toString(),
                                        companyId = finalCompanyId,
                                        type = NoteType.GENERAL,
                                        text = trimmedNote,
                                        isImportant = false,
                                        createdAt = noteNow,
                                        updatedAt = noteNow
                                    ))
                                existingCompanyNote != null ->
                                    AppStateStore.deleteNote(existingCompanyNote.id)
                            }
                            // Отложенные сотрудники → реальные связи контакт↔компания
                            pendingPeople.forEach { (person, pos) ->
                                val fresh = AppStateStore.getContact(person.id) ?: return@forEach
                                if (fresh.companyRelations.none { it.companyId == finalCompanyId }) {
                                    AppStateStore.updateContact(fresh.copy(
                                        companyRelations = fresh.companyRelations + ContactCompanyRelation(
                                            id = java.util.UUID.randomUUID().toString(),
                                            contactId = fresh.id,
                                            companyId = finalCompanyId,
                                            position = pos,
                                            employmentStatus = EmploymentStatus.CURRENT,
                                            isPrimary = fresh.companyRelations.isEmpty()
                                        )
                                    ))
                                }
                            }
                            onNavigateBack()
                        },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Full,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold)
                }
            }
            // Main info
            SectionCard(stringResource(R.string.cce_basic)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
                            .background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Business, contentDescription = null, modifier = Modifier.size(36.dp), tint = AppleTheme.colors.brand)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(AppleTheme.colors.brand),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it }, keyboardOptions = CapWords,
                            label = { Text(stringResource(R.string.cce_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (duplicateCompany != null) {
                            Text(
                                stringResource(R.string.cce_name_duplicate_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppleTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AureliaCaption(stringResource(R.string.cce_industry))
                    // Drop-down вместо пилюль (фидбэк владельца 2026-07-02):
                    // список вырос до 21 индустрии — стеной чипов не выбрать.
                    DropdownField(
                        label = stringResource(R.string.cce_industry),
                        selectedValue = industry.label(ctxLabel),
                        options = Industry.values().map { it.label(ctxLabel) }
                    ) { v -> industry = Industry.values().firstOrNull { it.label(ctxLabel) == v } ?: industry }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it }, keyboardOptions = CapSentences,
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
                AureliaCaption(stringResource(R.string.cce_phones))

                if (phones.isEmpty()) {
                    Text(stringResource(R.string.cce_no_phones),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        phones.forEachIndexed { idx, phone ->
                            ContactItemRow(
                                icon = Icons.Default.Call, iconTint = AppleTheme.colors.brand, iconBg = AppleTheme.colors.brand.copy(alpha = 0.10f),
                                value = phone.number,
                                onValueChange = { num -> phones = phones.toMutableList().also { it[idx] = phone.copy(number = num) } },
                                keyboardOptions = PhoneKeyboard, label = phone.type.label(ctxLabel),
                                isPrimary = phone.isPrimary,
                                onDelete = { phones = phones.toMutableList().also { it.removeAt(idx) } }
                            )
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

                HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                AureliaCaption(stringResource(R.string.cce_email))

                if (emails.isEmpty()) {
                    Text(stringResource(R.string.cce_no_email),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        emails.forEachIndexed { idx, email ->
                            ContactItemRow(
                                icon = Icons.Default.Email, iconTint = AureliaTheme.colors.gold, iconBg = AureliaTheme.colors.gold.copy(alpha = 0.14f),
                                value = email.email,
                                onValueChange = { addr -> emails = emails.toMutableList().also { it[idx] = email.copy(email = addr) } },
                                keyboardOptions = EmailKeyboard, label = email.type.label(ctxLabel),
                                isPrimary = email.isPrimary,
                                onDelete = { emails = emails.toMutableList().also { it.removeAt(idx) } }
                            )
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

            // Addresses (редактируемые) — ФИКС (2026-07-23, владелец): раньше это
            // были 4 фиксированных именованных слота (офис/филиал/юрадрес/другое),
            // по одному на AddressType — upsertAddress искал `find { type == X }`,
            // т.е. второй адрес того же типа был физически невозможно завести
            // ИЛИ увидеть в форме (он молча становился «невидимым», хотя жил в БД —
            // так проявилось при слиянии двух компаний с адресами типа OFFICE).
            // Теперь — тот же динамический список + ЕДИНЫЙ AddressEditDialog, что
            // уже работает у контактов (AddressComponents.kt): произвольное число
            // адресов любых типов, плюс геокодинг, которого тут раньше не было.
            SectionCard(stringResource(R.string.cce_addresses)) {
                var showAddrDialog by remember { mutableStateOf(false) }
                var editingAddr by remember { mutableStateOf<Address?>(null) }
                if (addresses.isEmpty()) {
                    Text(
                        stringResource(R.string.ce_address_after_save),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel
                    )
                }
                addresses.forEach { addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { editingAddr = addr; showAddrDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                addr.addressLine,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                listOf(addr.addressType.label(ctxLabel), addr.city, addr.postalCode.orEmpty(), addr.country)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel
                            )
                        }
                        IconButton(onClick = { addresses = addresses - addr }) {
                            Icon(Icons.Default.Close, stringResource(R.string.ce_remove_address),
                                Modifier.size(16.dp),
                                tint = AppleTheme.colors.secondaryLabel)
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                TextButton(onClick = { editingAddr = null; showAddrDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ce_address))
                }
                if (showAddrDialog) {
                    AddressEditDialog(
                        base = editingAddr,
                        ownerId = editedCompanyId,
                        ownerType = AddressOwnerType.COMPANY,
                        defaultType = AddressType.OFFICE,
                        scope = scope,
                        onDismiss = { showAddrDialog = false; editingAddr = null },
                        onCommit = { a ->
                            addresses = if (addresses.none { it.id == a.id }) addresses + a
                                else addresses.map { if (it.id == a.id) a else it }
                        },
                        onGeocoded = { a ->
                            addresses = addresses.map { if (it.id == a.id) a else it }
                        }
                    )
                }
            }

            // People
            SectionCard(stringResource(R.string.cce_people)) {
                if (relatedRelations.isEmpty()) {
                    Text(stringResource(R.string.cce_no_related), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        relatedRelations.forEach { relation ->
                            val contact = AppStateStore.getContact(relation.contactId)
                            if (contact != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card.copy(alpha = 0.5f)),
                                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier.size(32.dp).clip(CircleShape).background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = AppleTheme.colors.brand)
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
                
                // Добавленные в ЭТОЙ сессии формы (применятся при «Готово»)
                if (pendingPeople.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pendingPeople.forEach { (c, pos) ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("${c.firstName} ${c.lastName}".trim(),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    if (!pos.isNullOrBlank())
                                        Text(pos, style = MaterialTheme.typography.bodySmall,
                                            color = AppleTheme.colors.secondaryLabel)
                                }
                                IconButton(onClick = { pendingPeople.removeAll { it.first.id == c.id } }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, stringResource(R.string.common_delete),
                                        Modifier.size(14.dp), tint = AppleTheme.colors.secondaryLabel)
                                }
                            }
                        }
                    }
                }
                // Пикер с поиском вместо старого DropdownMenu (тот был без поиска
                // и НЕ создавал связь — известный баг, теперь связь реально создаётся)
                Button(
                    onClick = { showAddPerson = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.card, contentColor = AppleTheme.colors.secondaryLabel)
                ) {
                    Text(stringResource(R.string.cce_add_person))
                }
            }

            // Notes
            SectionCard(stringResource(R.string.cce_notes)) {
                OutlinedTextField(
                    value = companyNote,
                    onValueChange = { companyNote = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cce_work_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
        
        // Раньше это был нередактируемый диалог (все поля onValueChange={},
        // правки молча терялись — найдено при аудите 2026-07-04). Теперь —
        // ЕДИНЫЙ WorkplaceEditDialog (WorkplaceComponents.kt), тот же, что
        // используется со стороны контакта (Обзор/Работа): реально сохраняет.
        showRelationEditDialog?.let { relation ->
            val relContact = AppStateStore.getContact(relation.contactId)
            if (relContact != null) {
                WorkplaceEditDialog(
                    contact = relContact,
                    rel = relation,
                    onDismiss = { showRelationEditDialog = null }
                )
            } else {
                showRelationEditDialog = null
            }
        }
    }
}

