package com.aistudio.socialsphere.crmlxb.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
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
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null,
                                tint = MaterialTheme.colorScheme.error)
                            Text(
                                fileError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // ── vCard ─────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                                tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.imp_vcard_title),
                                fontWeight = FontWeight.Bold)
                        }
                        Text(
                            stringResource(R.string.imp_vcard_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
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
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                                tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.imp_csv_title),
                                fontWeight = FontWeight.Bold)
                        }
                        Text(
                            stringResource(R.string.imp_csv_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
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
            
            val phoneMatch = existingContacts.find { existing -> existing.phones.any { e -> c.phones.any { cp -> e.number == cp.number } } }
            if (phoneMatch != null) matchedContact = phoneMatch
            else {
                val emailMatch = existingContacts.find { existing -> existing.emails.any { e -> c.emails.any { ce -> e.email == ce.email } } }
                if (emailMatch != null) matchedContact = emailMatch
                else {
                    val nameMatch = existingContacts.find { it.firstName == c.firstName && it.lastName == c.lastName && c.firstName.isNotBlank() }
                    if (nameMatch != null) matchedContact = nameMatch
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
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (candidates.any { it.duplicateStatus == DuplicateStatus.POSSIBLE_DUPLICATE }) {
                        IconButton(onClick = onNavigateToDuplicates) {
                            Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = stringResource(R.string.imp_dups))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            val selectedCount = candidates.count { it.selectedForImport }
            val scope = rememberCoroutineScope()
            var isImporting by remember { mutableStateOf(false) }

            BottomAppBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                                color = MaterialTheme.colorScheme.onPrimary
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                                Text(text = listOfNotNull(candidate.jobTitle, candidate.companyName).joinToString(stringResource(R.string.imp_job_at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (candidate.phones.isNotEmpty()) {
                                Text(text = candidate.phones.first().number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            if (candidate.emails.isNotEmpty()) {
                                Text(text = candidate.emails.first().email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        val statusText = when (candidate.duplicateStatus) {
                            DuplicateStatus.NEW -> stringResource(R.string.imp_status_new)
                            DuplicateStatus.POSSIBLE_DUPLICATE -> stringResource(R.string.imp_status_dup)
                            DuplicateStatus.WILL_UPDATE -> stringResource(R.string.imp_status_update)
                            DuplicateStatus.SKIPPED -> stringResource(R.string.imp_status_skip)
                        }
                        val statusColor = if (candidate.duplicateStatus == DuplicateStatus.NEW) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(statusColor.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                        }
                    }
                }
            }
        }
    }
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
            photoUri = null,
            relationshipType = RelationshipType.OTHER,
            connectionLevel = ConnectionLevel.NORMAL,
            importanceLevel = ImportanceLevel.NORMAL,
            socialRole = SocialRole.REGULAR,
            communicationRhythm = CommunicationRhythm.NOT_TRACKED,
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

        // Страж: компания не создаётся, если название пустое после trim
        // или совпадает с должностью (классический симптом кривого маппинга)
        val cleanCompanyName = candidate.companyName?.trim()
        if (!cleanCompanyName.isNullOrBlank() &&
            !cleanCompanyName.equals(candidate.jobTitle?.trim(), ignoreCase = true)) {
            // Find or create company
            var company = AppStateStore.companies.find { it.name.equals(cleanCompanyName, ignoreCase = true) }
            if (company == null) {
                company = Company(
                    id = java.util.UUID.randomUUID().toString(),
                    name = cleanCompanyName,
                    industry = Industry.OTHER,
                    createdAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
                AppStateStore.addCompany(company)
                companiesCreated++
            }
            val relation = ContactCompanyRelation(
                    id = java.util.UUID.randomUUID().toString(),
                    contactId = contactId,
                    companyId = company.id,
                    position = candidate.jobTitle ?: "",
                    employmentStatus = EmploymentStatus.CURRENT,
                    isPrimary = true
                )
            val contactToUpdate = AppStateStore.contacts.find { it.id == contactId }
            if (contactToUpdate != null) {
                AppStateStore.updateContact(contactToUpdate.copy(companyRelations = contactToUpdate.companyRelations + relation))
            }
        } else if (!candidate.jobTitle.isNullOrBlank()) {
             // Job title without company, keep in note
             val newNote = Note(
                     id = java.util.UUID.randomUUID().toString(),
                     contactId = contactId,
                     type = NoteType.GENERAL,
                     text = context.getString(R.string.imp_job_on_import, candidate.jobTitle),
                     isImportant = false,
                     createdAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                     updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                 )
             val contactToUpdate = AppStateStore.contacts.find { it.id == contactId }
             if (contactToUpdate != null) {
                 AppStateStore.updateContact(contactToUpdate.copy(notes = contactToUpdate.notes + newNote))
             }
        }

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
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        if (duplicateCandidates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.imp_no_unresolved), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
             Text(stringResource(R.string.imp_choose_dup_action), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            items(duplicateCandidates, key = { it.id }) { candidate ->
                val scope = rememberCoroutineScope()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
    
    val newPhones = candidate.phones.filter { cp -> existingContact.phones.none { it.number == cp.number } }
        .map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = existingId) }
    val newEmails = candidate.emails.filter { ce -> existingContact.emails.none { it.email == ce.email } }
        .map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = existingId) }
    
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
    val cleanCompanyName2 = candidate.companyName?.trim()
    if (!cleanCompanyName2.isNullOrBlank() &&
        !cleanCompanyName2.equals(candidate.jobTitle?.trim(), ignoreCase = true)) {
        val hasCompanyRelation = existingContact.companyRelations.any { relation -> 
            val comp = AppStateStore.companies.find { it.id == relation.companyId }
            comp?.name.equals(cleanCompanyName2, ignoreCase = true)
        }
        if (!hasCompanyRelation) {
            var company = AppStateStore.companies.find { it.name.equals(cleanCompanyName2, ignoreCase = true) }
            if (company == null) {
                company = Company(
                    id = java.util.UUID.randomUUID().toString(),
                    name = cleanCompanyName2,
                    industry = Industry.OTHER,
                    createdAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
                AppStateStore.addCompany(company)
                ImportResultStats.companiesCreated++
            }
            val relation = ContactCompanyRelation(
                id = java.util.UUID.randomUUID().toString(),
                contactId = existingId,
                companyId = company.id,
                position = candidate.jobTitle ?: "",
                employmentStatus = EmploymentStatus.CURRENT,
                isPrimary = existingContact.companyRelations.isEmpty()
            )
            AppStateStore.updateContact(existingContact.copy(companyRelations = existingContact.companyRelations + relation))
        }
    }
    
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
    
    if (newPhones.isNotEmpty() || newEmails.isNotEmpty()) {
        val freshExisting = AppStateStore.contacts.find { it.id == existingId } ?: existingContact
        AppStateStore.updateContact(freshExisting.copy(
            phones = freshExisting.phones + newPhones,
            emails = freshExisting.emails + newEmails,
            updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ))
        ImportResultStats.phonesAdded += newPhones.size
        ImportResultStats.emailsAdded += newEmails.size
    }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.imp_success), modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.imp_done), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
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
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
