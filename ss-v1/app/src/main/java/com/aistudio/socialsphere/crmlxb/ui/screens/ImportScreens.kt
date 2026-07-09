package com.aistudio.socialsphere.crmlxb.ui.screens
import androidx.compose.ui.graphics.Color

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.ContactImporter
import com.aistudio.socialsphere.crmlxb.utils.parseVCard
import com.aistudio.socialsphere.crmlxb.utils.parseCsv
import com.aistudio.socialsphere.crmlxb.utils.DuplicateStatus
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportContactsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPreview: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            permissionDenied = false
            isLoading = true
            coroutineScope.launch {
                val contacts = withContext(Dispatchers.IO) {
                    ContactImporter.getDeviceContacts(context)
                }
                ImportSession.clear()
                ImportSession.candidates.addAll(contacts)
                isLoading = false
                onNavigateToPreview()
            }
        } else {
            permissionDenied = true
        }
    }

    // vCard file picker
    val vcfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isLoading = true
        fileError = null
        coroutineScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    val content = context.contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText() ?: ""
                    parseVCard(content)
                }
                if (candidates.isEmpty()) {
                    fileError = context.getString(R.string.imp_no_vcard_contacts)
                } else {
                    ImportSession.clear()
                    ImportSession.candidates.addAll(candidates)
                    onNavigateToPreview()
                }
            } catch (e: Exception) {
                fileError = context.getString(R.string.imp_read_error, e.localizedMessage ?: "")
            } finally {
                isLoading = false
            }
        }
    }

    // CSV file picker
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isLoading = true
        fileError = null
        coroutineScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    val content = context.contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText() ?: ""
                    parseCsv(content)
                }
                if (candidates.isEmpty()) {
                    fileError = context.getString(R.string.imp_no_csv_contacts)
                } else {
                    ImportSession.clear()
                    ImportSession.candidates.addAll(candidates)
                    onNavigateToPreview()
                }
            } catch (e: Exception) {
                fileError = context.getString(R.string.imp_read_error, e.localizedMessage ?: "")
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imp_contacts_title), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    Box(
                        modifier = Modifier.padding(start = 12.dp).size(36.dp).clip(CircleShape)
                            .background(AppleTheme.colors.fill).clickable { onNavigateBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.size(20.dp), tint = AppleTheme.colors.label)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (permissionDenied) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(64.dp), tint = AppleTheme.colors.red)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.imp_need_permission), style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text(stringResource(R.string.imp_allow))
                }
                TextButton(onClick = onNavigateBack) {
                    Text(stringResource(R.string.common_back))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            isLoading = true
                            coroutineScope.launch {
                                val contacts = withContext(Dispatchers.IO) {
                                    ContactImporter.getDeviceContacts(context)
                                }
                                ImportSession.clear()
                                ImportSession.candidates.addAll(contacts)
                                isLoading = false
                                onNavigateToPreview()
                            }
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Contacts, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.imp_from_phonebook))
                }
                
                // ── Error message ─────────────────────────────
                if (fileError != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = AppleTheme.colors.red.copy(alpha = 0.12f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null,
                                tint = AppleTheme.colors.red)
                            Text(
                                fileError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.red
                            )
                        }
                    }
                }

                // ── vCard ─────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AppleTheme.colors.card.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.ContactPhone, null,
                                tint = AppleTheme.colors.brand)
                            Text(stringResource(R.string.imp_vcard_title),
                                fontWeight = FontWeight.Bold)
                        }
                        Text(
                            stringResource(R.string.imp_vcard_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppleTheme.colors.secondaryLabel
                        )
                        Button(
                            onClick = { vcfLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.imp_pick_vcard))
                        }
                    }
                }

                // ── CSV ───────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AppleTheme.colors.card.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.TableChart, null,
                                tint = AppleTheme.colors.brand)
                            Text(stringResource(R.string.imp_csv_title),
                                fontWeight = FontWeight.Bold)
                        }
                        Text(
                            stringResource(R.string.imp_csv_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppleTheme.colors.secondaryLabel
                        )
                        Button(
                            onClick = { csvLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.imp_pick_csv))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreviewScreen(
    onNavigateBack: () -> Unit,
    onNavigateToResult: () -> Unit,
    onNavigateToDuplicates: () -> Unit
) {
    val candidates = ImportSession.candidates
    val ctx = LocalContext.current
    var isCheckingDuplicates by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Simple duplicate check on load
        val existingContacts = AppStateStore.contacts
        candidates.forEach { c ->
            var matchedContact: Contact? = null

            // Уже связан с этим самым контактом телефона — точное совпадение,
            // проверяем в первую очередь (надёжнее любого сравнения по полям).
            val deviceMatch = existingContacts.find { it.deviceContactId == c.id }
            if (deviceMatch != null) matchedContact = deviceMatch
            else {
                // Сравнение по НОРМАЛИЗОВАННЫМ 10 цифрам (было — посимвольное
                // сравнение строк, из-за чего «+7 900…» и «89001112233» — один
                // и тот же номер — считались разными, и дубли не находились).
                val phoneMatch = existingContacts.find { existing ->
                    existing.phones.any { e -> c.phones.any { cp ->
                        val d = AppStateStore.phoneDigits(e.number)
                        d.length >= 7 && d == AppStateStore.phoneDigits(cp.number)
                    } }
                }
                if (phoneMatch != null) matchedContact = phoneMatch
                else {
                    val emailMatch = existingContacts.find { existing -> existing.emails.any { e -> c.emails.any { ce -> e.email.equals(ce.email, ignoreCase = true) } } }
                    if (emailMatch != null) matchedContact = emailMatch
                    else {
                        val nameMatch = existingContacts.find { it.firstName == c.firstName && it.lastName == c.lastName && c.firstName.isNotBlank() }
                        if (nameMatch != null) matchedContact = nameMatch
                    }
                }
            }
            
            if (matchedContact != null) {
                c.duplicateStatus = DuplicateStatus.POSSIBLE_DUPLICATE
                c.selectedForImport = false
                c.matchedContactId = matchedContact?.id
            } else {
                c.duplicateStatus = DuplicateStatus.NEW
                c.selectedForImport = true
            }
        }
        // Force recomposition
        val copy = candidates.toList()
        candidates.clear()
        candidates.addAll(copy)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imp_preview), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    Box(
                        modifier = Modifier.padding(start = 12.dp).size(36.dp).clip(CircleShape)
                            .background(AppleTheme.colors.fill).clickable { onNavigateBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.size(20.dp), tint = AppleTheme.colors.label)
                    }
                },
                actions = {
                    if (candidates.any { it.duplicateStatus == DuplicateStatus.POSSIBLE_DUPLICATE }) {
                        IconButton(onClick = onNavigateToDuplicates) {
                            Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = stringResource(R.string.imp_dups))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
        },
        bottomBar = {
            val selectedCount = candidates.count { it.selectedForImport }
            val scope = rememberCoroutineScope()
            var isImporting by remember { mutableStateOf(false) }

            BottomAppBar(containerColor = AppleTheme.colors.card) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.imp_selected, selectedCount), fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            isImporting = true
                            scope.launch {
                                // FIX: run on IO thread, not UI
                                withContext(Dispatchers.IO) {
                                    performImport(candidates.filter { it.selectedForImport }, ctx)
                                }
                                isImporting = false
                                onNavigateToResult()
                            }
                        },
                        enabled = selectedCount > 0 && !isImporting
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(stringResource(R.string.imp_import_btn))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(candidates, key = { it.id }) { candidate ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = candidate.selectedForImport,
                            onCheckedChange = { checked ->
                                // FIX: proper snapshot mutation for SnapshotStateList
                                val idx = candidates.indexOfFirst { it.id == candidate.id }
                                if (idx >= 0) {
                                    candidates[idx] = candidates[idx].copy(selectedForImport = checked)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "${candidate.firstName} ${candidate.lastName}".trim().takeIf { it.isNotBlank() } ?: stringResource(R.string.imp_no_name), fontWeight = FontWeight.Bold)
                            if (candidate.companyName != null || candidate.jobTitle != null) {
                                Text(text = listOfNotNull(candidate.jobTitle, candidate.companyName).joinToString(stringResource(R.string.imp_job_at)), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.brand)
                            }
                            if (candidate.phones.isNotEmpty()) {
                                Text(text = candidate.phones.first().number, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                            }
                            if (candidate.emails.isNotEmpty()) {
                                Text(text = candidate.emails.first().email, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        val statusText = when (candidate.duplicateStatus) {
                            DuplicateStatus.NEW -> stringResource(R.string.imp_status_new)
                            DuplicateStatus.POSSIBLE_DUPLICATE -> stringResource(R.string.imp_status_dup)
                            DuplicateStatus.WILL_UPDATE -> stringResource(R.string.imp_status_update)
                            DuplicateStatus.SKIPPED -> stringResource(R.string.imp_status_skip)
                        }
                        val statusColor = if (candidate.duplicateStatus == DuplicateStatus.NEW) AppleTheme.colors.brand else AppleTheme.colors.red
                        Box(modifier = Modifier.clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Small).background(statusColor.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                        }
                    }
                }
            }
        }
    }
}

/** Инверсия роли для НАШЕГО контакта относительно смежного (см. androidRelationRole
 *  в ContactImporter.kt) — большинство ролей асимметричны и требуют знания пола
 *  нашего контакта, которого Android не даёт, поэтому безопасный дефолт — «Родственник». */
private fun inverseImportedRole(role: String): String = when (role) {
    "Друг" -> "Друг"
    "Партнёр" -> "Партнёр"
    "Коллега" -> "Коллега"
    else -> "Родственник"
}

/** Ищет контакт приложения по полному имени из Android Relation.NAME
 *  (не структурировано — только строка целиком). */
private fun findContactByFullName(fullName: String): Contact? {
    val words = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return null
    return AppStateStore.contacts.find { c ->
        val cFull = listOfNotNull(c.firstName.trim(), c.middleName?.trim(), c.lastName.trim())
            .filter { it.isNotBlank() }.joinToString(" ")
        cFull.equals(fullName.trim(), ignoreCase = true) ||
            (words.size == 1 && (c.firstName.equals(words[0], ignoreCase = true) || c.lastName.equals(words[0], ignoreCase = true))) ||
            (words.size >= 2 && c.firstName.equals(words.first(), ignoreCase = true) && c.lastName.equals(words.last(), ignoreCase = true))
    }
}

/**
 * Смежные контакты из телефона (фидбэк владельца 2026-07-04: «в Android есть
 * смежные контакты — семья, друг… это должно сохраняться»). Общая функция для
 * НОВОГО контакта и слияния — раньше такой перенос не было вообще нигде.
 * Если есть контакт с таким именем — настоящая ContactRelation. Если нет —
 * заметка (решение владельца: «если про текстовое поле — добавляешь в заметки»),
 * локаль-безопасный шаблон (см. normalizeImportedNoteText/У62).
 */
private fun applyImportedRelations(candidate: ImportContactCandidate, contactId: String, context: android.content.Context) {
    if (candidate.relations.isEmpty()) return
    candidate.relations.forEach { rel ->
        val matched = findContactByFullName(rel.name)?.takeIf { it.id != contactId }
        if (matched != null) {
            val alreadyLinked = AppStateStore.contactRelations.any {
                (it.firstContactId == contactId && it.secondContactId == matched.id) ||
                (it.firstContactId == matched.id && it.secondContactId == contactId)
            }
            if (!alreadyLinked) {
                AppStateStore.addContactRelation(ContactRelation(
                    id = java.util.UUID.randomUUID().toString(),
                    firstContactId = contactId,
                    secondContactId = matched.id,
                    firstRole = inverseImportedRole(rel.role),
                    secondRole = rel.role
                ))
            }
        } else {
            val text = context.getString(R.string.imp_relation_from_phone, "${rel.role} — ${rel.name}")
            val current = AppStateStore.contacts.find { it.id == contactId } ?: return@forEach
            if (current.notes.none { it.text == text }) {
                val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                AppStateStore.updateContact(current.copy(notes = current.notes + Note(
                    id = java.util.UUID.randomUUID().toString(),
                    contactId = contactId,
                    type = NoteType.GENERAL,
                    text = text,
                    isImportant = false,
                    createdAt = now, updatedAt = now
                )))
            }
        }
    }
}

/**
 * Компания/должность из телефона — ЕДИНАЯ функция для нового контакта, слияния
 * при импорте И точечной кнопки «Обновить из телефона» (фидбэк 2026-07-04:
 * «работу тоже подтянуть» — раньше эта логика была раздельно и почти дословно
 * продублирована между performImport/mergeCandidate, точечная кнопка её не
 * знала вовсе). Если есть компания — find-or-create + связь (если такой связи
 * ещё нет — повторный вызов не плодит дубли); если только должность без
 * компании — заметка (тоже с проверкой на дубль текста).
 * @return true, если контакт реально изменился (для тостов/статистики).
 */
internal fun applyImportedCompany(
    companyName: String?,
    jobTitle: String?,
    contactId: String,
    context: android.content.Context,
    onCompanyCreated: () -> Unit = {}
): Boolean {
    // Страж: компания не создаётся, если название пустое после trim
    // или совпадает с должностью (классический симптом кривого маппинга)
    val cleanCompanyName = companyName?.trim()
    if (!cleanCompanyName.isNullOrBlank() && !cleanCompanyName.equals(jobTitle?.trim(), ignoreCase = true)) {
        val current = AppStateStore.contacts.find { it.id == contactId } ?: return false
        val hasRelation = current.companyRelations.any { relation ->
            AppStateStore.getCompany(relation.companyId)?.name.equals(cleanCompanyName, ignoreCase = true)
        }
        if (hasRelation) return false
        var company = AppStateStore.companies.find { it.name.equals(cleanCompanyName, ignoreCase = true) }
        if (company == null) {
            val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            company = Company(
                id = java.util.UUID.randomUUID().toString(), name = cleanCompanyName,
                industry = Industry.OTHER, createdAt = now, updatedAt = now
            )
            AppStateStore.addCompany(company)
            onCompanyCreated()
        }
        val relation = ContactCompanyRelation(
            id = java.util.UUID.randomUUID().toString(),
            contactId = contactId,
            companyId = company.id,
            position = jobTitle ?: "",
            employmentStatus = EmploymentStatus.CURRENT,
            isPrimary = current.companyRelations.isEmpty()
        )
        AppStateStore.updateContact(current.copy(companyRelations = current.companyRelations + relation))
        return true
    } else if (!jobTitle.isNullOrBlank()) {
        val text = context.getString(R.string.imp_job_on_import, jobTitle)
        val current = AppStateStore.contacts.find { it.id == contactId } ?: return false
        if (current.notes.any { it.text == text }) return false
        val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        AppStateStore.updateContact(current.copy(notes = current.notes + Note(
            id = java.util.UUID.randomUUID().toString(), contactId = contactId,
            type = NoteType.GENERAL, text = text, isImportant = false,
            createdAt = now, updatedAt = now
        )))
        return true
    }
    return false
}

fun performImport(selected: List<ImportContactCandidate>, context: android.content.Context) {
    var companiesCreated = 0
    var contactsImported = 0
    var phonesAdded = 0
    var emailsAdded = 0
    var addressesAdded = 0
    var birthdaysCreated = 0

    selected.forEach { candidate ->
        val contactId = java.util.UUID.randomUUID().toString()
        val newContact = Contact(
            id = contactId,
            firstName = candidate.firstName,
            lastName = candidate.lastName,
            middleName = candidate.middleName.ifBlank { null },
            namePrefix = candidate.namePrefix.ifBlank { null },
            nameSuffix = candidate.nameSuffix.ifBlank { null },
            phoneticFirstName = candidate.phoneticFirstName.ifBlank { null },
            phoneticLastName = candidate.phoneticLastName.ifBlank { null },
            photoUri = null,
            relationshipType = RelationshipType.OTHER,
            connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.NORMAL,
            socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.NOT_TRACKED,
            // Связь с контактом телефона — candidate.id уже в формате
            // "device_contact_<ID>" (см. ContactImporter.getDeviceContacts).
            // Раньше не проставлялось — импортированные так контакты никогда
            // не получали кнопку «Обновить из телефона» (баг найден 2026-07-04).
            // Для CSV/vCard-импорта (не из книги устройства) candidate.id — просто
            // случайный UUID, не совпадающий с device_contact_-форматом нигде
            // больше, поэтому присваивать его безопасно в любом случае.
            deviceContactId = candidate.id.takeIf { it.startsWith("device_contact_") },
            phones = candidate.phones.map { it.copy(contactId = contactId, id = java.util.UUID.randomUUID().toString()) },
            emails = candidate.emails.map { it.copy(contactId = contactId, id = java.util.UUID.randomUUID().toString()) },
            createdAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        AppStateStore.addContact(newContact)
        contactsImported++
        phonesAdded += newContact.phones.size
        emailsAdded += newContact.emails.size

        candidate.addresses.forEach { addr ->
            val newAddress = addr.copy(id = java.util.UUID.randomUUID().toString(), ownerId = contactId)
            val updatedContact = AppStateStore.contacts.find { it.id == contactId }
            if (updatedContact != null) {
                AppStateStore.updateContact(updatedContact.copy(addresses = updatedContact.addresses + newAddress))
                addressesAdded++
            }
        }

        // Группы телефонной книги (фидбэк 2026-07-04): find-or-create по имени +
        // привязка нового контакта. addGroup сам дедуплицирует по имени без регистра.
        if (candidate.groupNames.isNotEmpty()) {
            val groupIds = candidate.groupNames.mapNotNull { AppStateStore.addGroup(it)?.id }.toSet()
            if (groupIds.isNotEmpty()) AppStateStore.setContactGroups(contactId, groupIds)
        }

        applyImportedCompany(candidate.companyName, candidate.jobTitle, contactId, context) { companiesCreated++ }

        val alreadyHasBirthday = AppStateStore.calendarItems.any {
            it.type == CalendarItemType.BIRTHDAY &&
            it.links.any { l -> l.targetType == CalendarTargetType.CONTACT && l.targetId == contactId }
        }
        if (!candidate.birthday.isNullOrBlank() && !alreadyHasBirthday) {
            val calId = java.util.UUID.randomUUID().toString()
            AppStateStore.addCalendarItem(
                CalendarItem(
                    id = calId,
                    title = context.getString(R.string.imp_birthday_of, "${candidate.firstName} ${candidate.lastName}"),
                    type = CalendarItemType.BIRTHDAY,
                    status = CalendarItemStatus.ACTIVE,
                    importance = ImportanceLevel.NORMAL,
                    startDate = candidate.birthday,
                    isAllDay = true,
                    recurrenceRule = "FREQ=YEARLY",
                    reminders = emptyList(), // Can add default reminder based on settings
                    links = listOf(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = calId, targetType = CalendarTargetType.CONTACT, targetId = contactId)),
                    createdAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
            )
            birthdaysCreated++
        }
        
        if (!candidate.notes.isNullOrBlank()) {
             val newNote = Note(
                     id = java.util.UUID.randomUUID().toString(),
                     contactId = contactId,
                     type = NoteType.GENERAL,
                     text = context.getString(R.string.imp_note_from_import, candidate.notes),
                     isImportant = false,
                     createdAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                     updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                 )
             val contactToUpdate = AppStateStore.contacts.find { it.id == contactId }
             if (contactToUpdate != null) {
                 AppStateStore.updateContact(contactToUpdate.copy(notes = contactToUpdate.notes + newNote))
             }
        }

        applyImportedRelations(candidate, contactId, context)
    }

    // Store stats temporarily in ImportSession so Result screen can show it
    ImportResultStats.contactsImported = contactsImported
    ImportResultStats.companiesCreated = companiesCreated
    ImportResultStats.phonesAdded = phonesAdded
    ImportResultStats.emailsAdded = emailsAdded
    ImportResultStats.addressesAdded = addressesAdded
    ImportResultStats.birthdaysCreated = birthdaysCreated
}

object ImportResultStats {
    var contactsImported = 0
    var companiesCreated = 0
    var phonesAdded = 0
    var emailsAdded = 0
    var addressesAdded = 0
    var birthdaysCreated = 0
    var duplicatesSkipped = 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportDuplicatesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToResult: () -> Unit
) {
    val allCandidates = ImportSession.candidates
    val duplicateCandidates = allCandidates.filter { it.duplicateStatus == DuplicateStatus.POSSIBLE_DUPLICATE }
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imp_possible_dups), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    Box(
                        modifier = Modifier.padding(start = 12.dp).size(36.dp).clip(CircleShape)
                            .background(AppleTheme.colors.fill).clickable { onNavigateBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.size(20.dp), tint = AppleTheme.colors.label)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
        }
    ) { paddingValues ->
        if (duplicateCandidates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.imp_no_unresolved), style = MaterialTheme.typography.bodyLarge, color = AppleTheme.colors.secondaryLabel)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
             Text(stringResource(R.string.imp_choose_dup_action), style = MaterialTheme.typography.bodyMedium, color = AppleTheme.colors.secondaryLabel)
            }
            items(duplicateCandidates, key = { it.id }) { candidate ->
                val scope = rememberCoroutineScope()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AppleTheme.colors.card.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${candidate.firstName} ${candidate.lastName}".trim()
                                .takeIf { it.isNotBlank() } ?: stringResource(R.string.imp_no_name),
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    // FIX: run on IO, update by id not indexOf
                                    scope.launch {
                                        withContext(Dispatchers.IO) { mergeCandidate(candidate, ctx) }
                                        val idx = allCandidates.indexOfFirst { it.id == candidate.id }
                                        if (idx >= 0) allCandidates[idx] = allCandidates[idx].copy(
                                            duplicateStatus = DuplicateStatus.WILL_UPDATE,
                                            selectedForImport = false
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.imp_merge), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val idx = allCandidates.indexOfFirst { it.id == candidate.id }
                                    if (idx >= 0) allCandidates[idx] = allCandidates[idx].copy(
                                        duplicateStatus = DuplicateStatus.SKIPPED,
                                        selectedForImport = false
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.imp_skip), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun mergeCandidate(candidate: ImportContactCandidate, context: android.content.Context) {
    val existingId = candidate.matchedContactId ?: return
    val existingContact = AppStateStore.contacts.find { it.id == existingId } ?: return

    // Нормализованное сравнение (было — посимвольное; см. фикс в ImportPreviewScreen выше)
    val newPhones = candidate.phones.filter { cp ->
        existingContact.phones.none { AppStateStore.phoneDigits(it.number) == AppStateStore.phoneDigits(cp.number) }
    }.map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = existingId) }
    val newEmails = candidate.emails.filter { ce -> existingContact.emails.none { it.email.equals(ce.email, ignoreCase = true) } }
        .map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = existingId) }

    // Связь с телефоном — если контакт слился с записью из книги устройства,
    // а связи ещё не было, проставляем (тот же баг, что и в performImport).
    val deviceLink = candidate.id.takeIf { it.startsWith("device_contact_") }
    val newMiddleName = existingContact.middleName ?: candidate.middleName.ifBlank { null }
    val newNamePrefix = existingContact.namePrefix ?: candidate.namePrefix.ifBlank { null }
    val newNameSuffix = existingContact.nameSuffix ?: candidate.nameSuffix.ifBlank { null }
    val newPhoneticFirst = existingContact.phoneticFirstName ?: candidate.phoneticFirstName.ifBlank { null }
    val newPhoneticLast = existingContact.phoneticLastName ?: candidate.phoneticLastName.ifBlank { null }

    // Merge Addresses
    candidate.addresses.forEach { addr ->
        if (existingContact.addresses.none { it.addressLine == addr.addressLine }) {
            val newAddress = addr.copy(id = java.util.UUID.randomUUID().toString(), ownerId = existingId)
            val updatedContact = AppStateStore.contacts.find { it.id == existingId }
            if (updatedContact != null) {
                AppStateStore.updateContact(updatedContact.copy(addresses = updatedContact.addresses + newAddress))
                ImportResultStats.addressesAdded++
            }
        }
    }
    
    // Merge company relation
    applyImportedCompany(candidate.companyName, candidate.jobTitle, existingId, context) { ImportResultStats.companiesCreated++ }
    
    // Merge Birthday
    if (!candidate.birthday.isNullOrBlank()) {
        val hasBirthday = AppStateStore.calendarItems.any { it.type == CalendarItemType.BIRTHDAY && it.links.any { l -> l.targetId == existingId } }
        if (!hasBirthday) {
            val calId = java.util.UUID.randomUUID().toString()
            AppStateStore.addCalendarItem(
                CalendarItem(
                    id = calId,
                    title = context.getString(R.string.imp_birthday_of, "${existingContact.firstName} ${existingContact.lastName}"),
                    type = CalendarItemType.BIRTHDAY,
                    status = CalendarItemStatus.ACTIVE,
                    importance = ImportanceLevel.NORMAL,
                    startDate = candidate.birthday,
                    isAllDay = true,
                    recurrenceRule = "FREQ=YEARLY",
                    reminders = emptyList(),
                    links = listOf(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = calId, targetType = CalendarTargetType.CONTACT, targetId = existingId)),
                    createdAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
            )
            ImportResultStats.birthdaysCreated++
        }
    }
    
    // Заметка/группы телефона — раньше переносились ТОЛЬКО при создании нового
    // контакта; при слиянии с существующим (этот путь) молча терялись, хотя
    // ContactImporter их уже распарсил (найдено при разборе жалобы 2026-07-04).
    if (!candidate.notes.isNullOrBlank()) {
        val hasImportedNote = existingContact.notes.any {
            it.text == context.getString(R.string.imp_note_from_import, candidate.notes)
        }
        if (!hasImportedNote) {
            val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val freshForNote = AppStateStore.contacts.find { it.id == existingId } ?: existingContact
            AppStateStore.updateContact(freshForNote.copy(
                notes = freshForNote.notes + Note(
                    id = java.util.UUID.randomUUID().toString(),
                    contactId = existingId,
                    type = NoteType.GENERAL,
                    text = context.getString(R.string.imp_note_from_import, candidate.notes),
                    isImportant = false,
                    createdAt = now, updatedAt = now
                )
            ))
        }
    }
    if (candidate.groupNames.isNotEmpty()) {
        val groupIds = candidate.groupNames.mapNotNull { AppStateStore.addGroup(it)?.id }.toSet()
        if (groupIds.isNotEmpty()) {
            val current = AppStateStore.groupsOfContact(existingId).map { it.id }.toSet()
            AppStateStore.setContactGroups(existingId, current + groupIds)
        }
    }

    val nameFieldsChanged = newMiddleName != existingContact.middleName ||
        newNamePrefix != existingContact.namePrefix ||
        newNameSuffix != existingContact.nameSuffix ||
        newPhoneticFirst != existingContact.phoneticFirstName ||
        newPhoneticLast != existingContact.phoneticLastName
    if (newPhones.isNotEmpty() || newEmails.isNotEmpty() || deviceLink != null || nameFieldsChanged) {
        val freshExisting = AppStateStore.contacts.find { it.id == existingId } ?: existingContact
        AppStateStore.updateContact(freshExisting.copy(
            phones = freshExisting.phones + newPhones,
            emails = freshExisting.emails + newEmails,
            middleName = newMiddleName,
            namePrefix = newNamePrefix,
            nameSuffix = newNameSuffix,
            phoneticFirstName = newPhoneticFirst,
            phoneticLastName = newPhoneticLast,
            // Связь с телефоном проставляем, только если её ещё не было —
            // не затираем существующую (контакт мог быть связан вручную с
            // ДРУГОЙ карточкой телефона, это выбор владельца).
            deviceContactId = freshExisting.deviceContactId ?: deviceLink,
            updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ))
        ImportResultStats.phonesAdded += newPhones.size
        ImportResultStats.emailsAdded += newEmails.size
    }
    applyImportedRelations(candidate, existingId, context)
    ImportResultStats.duplicatesSkipped++
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportResultScreen(
    onNavigateToContacts: () -> Unit,
    onNavigateToCompanies: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imp_results), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.imp_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.imp_success), modifier = Modifier.size(64.dp), tint = AppleTheme.colors.brand)
            Text(stringResource(R.string.imp_done), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card.copy(alpha = 0.3f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ResultRow(stringResource(R.string.imp_contacts_imported), ImportResultStats.contactsImported)
                    ResultRow(stringResource(R.string.imp_companies_created), ImportResultStats.companiesCreated)
                    ResultRow(stringResource(R.string.imp_phones_added), ImportResultStats.phonesAdded)
                    ResultRow(stringResource(R.string.imp_emails_added), ImportResultStats.emailsAdded)
                    ResultRow(stringResource(R.string.imp_addresses_added), ImportResultStats.addressesAdded)
                    ResultRow(stringResource(R.string.imp_events_n), ImportResultStats.birthdaysCreated)
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onNavigateToContacts, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_contacts))
                }
                Button(onClick = onNavigateToCompanies, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_companies))
                }
            }
            OutlinedButton(onClick = onNavigateToSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.imp_back_to_settings))
            }
        }
    }
}

@Composable
fun ResultRow(label: String, value: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppleTheme.colors.secondaryLabel)
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
