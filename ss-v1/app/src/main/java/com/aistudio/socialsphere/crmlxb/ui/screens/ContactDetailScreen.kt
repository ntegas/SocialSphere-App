package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactDetailScreen(
    contactId: String,
    onNavigateBack: () -> Unit,
    onNavigateToCalendarItem: (String) -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToCreateCalendarItem: () -> Unit,
    onNavigateToContact: (String) -> Unit = {},
    onNavigateToCheatSheet: () -> Unit = {}
) {
    val contact = AppStateStore.getContact(contactId)
    if (contact == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Контакт не найден")
        }
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Обзор", "Работа", "Связь", "Подарки", "Заметки")

    var showAddDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Add note state
    var noteText by remember { mutableStateOf("") }
    var noteType by remember { mutableStateOf(NoteType.GENERAL) }
    var noteIsImportant by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Удалить контакт?", fontWeight = FontWeight.Bold) },
            text  = { Text("«${contact.firstName} ${contact.lastName}» будет удалён без возможности восстановления.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        AppStateStore.deleteContact(contactId)
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(
                    "${contact.firstName} ${contact.lastName}".trim(),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                ) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Редактировать")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
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
        ) {
            // Header
            ContactHeader(contact, onNavigateToCheatSheet)

            Spacer(modifier = Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Tab Content
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTab) {
                    0 -> overviewTab(contact, onNavigateToCreateCalendarItem, onNavigateToCalendarItem, onNavigateToContact)
                    1 -> workTab(contact)
                    2 -> communicationTab(contact)
                    3 -> giftsTab(contact, onNavigateToCalendarItem)
                    4 -> notesTab(
                        contact     = contact,
                        onShowAdd   = { showAddDialog = true },
                        onShowVoice = { showVoiceDialog = true }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; noteText = "" },
            title = { Text("Добавить заметку", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Текст заметки") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        maxLines = 5
                    )
                    DropdownField("Тип заметки", noteType.label(), NoteType.values().map { it.label() }) {
                        noteType = NoteType.values().firstOrNull { n -> n.label() == it } ?: NoteType.GENERAL
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = noteIsImportant, onCheckedChange = { noteIsImportant = it })
                        Text("Важная заметка", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteText.isNotBlank()) {
                            val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            AppStateStore.addNote(
                                Note(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contactId = contactId,
                                    companyId = null, calendarItemId = null, giftId = null,
                                    type = noteType,
                                    text = noteText.trim(),
                                    date = java.time.LocalDate.now().toString(),
                                    isImportant = noteIsImportant,
                                    createdAt = now, updatedAt = now
                                )
                            )
                            noteText = ""; noteIsImportant = false; showAddDialog = false
                        }
                    },
                    enabled = noteText.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false; noteText = "" }) { Text("Отмена") } }
        )
    }

    // Quick personal detail state
    var pdText     by remember { mutableStateOf("") }
    var pdCategory by remember { mutableStateOf(PersonalDetailCategory.INTERESTS) }

    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false; pdText = "" },
            title = { Text("Добавить личную деталь", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // Category selector
                    DropdownField(
                        label         = "Категория",
                        selectedValue = pdCategory.label(),
                        options       = PersonalDetailCategory.values().map { it.label() }
                    ) { selected ->
                        pdCategory = PersonalDetailCategory.values()
                            .firstOrNull { it.label() == selected }
                            ?: PersonalDetailCategory.INTERESTS
                    }

                    // Value input
                    OutlinedTextField(
                        value         = pdText,
                        onValueChange = { pdText = it },
                        label         = { Text("Значение") },
                        placeholder   = {
                            val hint = when (pdCategory) {
                                PersonalDetailCategory.FOOD        -> "Японская кухня, суши"
                                PersonalDetailCategory.DRINKS      -> "Зелёный чай, без алкоголя"
                                PersonalDetailCategory.INTERESTS   -> "Теннис, яхты, книги"
                                PersonalDetailCategory.HABITS      -> "Встаёт рано, не любит звонки"
                                PersonalDetailCategory.ALLERGIES   -> "Орехи, лактоза"
                                PersonalDetailCategory.RESTRICTIONS-> "Вегетарианец"
                                PersonalDetailCategory.LIKES       -> "Хорошее вино"
                                PersonalDetailCategory.DISLIKES    -> "Опоздания"
                                else                               -> "Введи значение"
                            }
                            Text(hint, color = MaterialTheme.colorScheme.outlineVariant)
                        },
                        modifier  = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Size info shortcut
                    if (pdCategory == PersonalDetailCategory.OTHER) {
                        val sizeInfo = contact.sizeInfo
                        if (sizeInfo != null) {
                            Text(
                                "Размеры уже сохранены: " +
                                listOfNotNull(
                                    sizeInfo.clothingSize?.let { "одежда $it" },
                                    sizeInfo.shoeSize?.let     { "обувь $it" },
                                    sizeInfo.ringSize?.let     { "кольцо $it" }
                                ).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    // Existing details preview
                    val existing = contact.personalDetails
                        .filter { it.category == pdCategory }
                    if (existing.isNotEmpty()) {
                        Text(
                            "Уже добавлено:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement   = Arrangement.spacedBy(4.dp)
                        ) {
                            existing.forEach { pd ->
                                Surface(
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        pd.value,
                                        style    = MaterialTheme.typography.labelSmall,
                                        color    = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pdText.isNotBlank()) {
                            val now = java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            val newDetail = PersonalDetail(
                                id         = java.util.UUID.randomUUID().toString(),
                                contactId  = contact.id,
                                category   = pdCategory,
                                value      = pdText.trim(),
                                note       = null
                            )
                            // Save to DB via updateContact
                            val updated = contact.copy(
                                personalDetails = contact.personalDetails + newDetail,
                                updatedAt       = now
                            )
                            AppStateStore.updateContact(updated)
                            pdText = ""
                            showVoiceDialog = false
                        }
                    },
                    enabled = pdText.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showVoiceDialog = false; pdText = "" }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun ContactHeader(contact: Contact, onNavigateToCheatSheet: () -> Unit = {}) {
    val compRel  = contact.companyRelations.firstOrNull { it.isPrimary } ?: contact.companyRelations.firstOrNull()
    val company  = compRel?.companyId?.let { AppStateStore.getCompany(it) }?.name ?: ""
    val position = compRel?.position ?: ""
    val address  = AppStateStore.addresses.find { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
    val city     = address?.city ?: ""
    val name     = "${contact.firstName} ${contact.lastName}".trim()

    // Last contact — most recent note/event
    val lastNoteDate = AppStateStore.notes
        .filter { it.contactId == contact.id }
        .maxByOrNull { it.createdAt }?.createdAt?.take(10)

    // Days since last contact
    val daysSince = if (!contact.lastContactDate.isNullOrBlank()) {
        try {
            val last = java.time.LocalDate.parse(contact.lastContactDate)
            java.time.ChronoUnit.DAYS.between(last, java.time.LocalDate.now())
        } catch (e: Exception) { null }
    } else if (!lastNoteDate.isNullOrBlank()) {
        try {
            val last = java.time.LocalDate.parse(lastNoteDate)
            java.time.ChronoUnit.DAYS.between(last, java.time.LocalDate.now())
        } catch (e: Exception) { null }
    } else null

    // Nearest upcoming date
    val nearestDate = AppStateStore.calendarItems
        .filter { it.links.any { l -> l.targetId == contact.id } && it.status == CalendarItemStatus.ACTIVE }
        .filter { it.startDate >= java.time.LocalDate.now().toString() }
        .minByOrNull { it.startDate }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar + статус-бейдж
        val today = java.time.LocalDate.now()
        val weekAgo = today.minusDays(7).toString()
        val rhythmDays: Long? = when (contact.communicationRhythm) {
            CommunicationRhythm.WEEKLY         -> 7L
            CommunicationRhythm.MONTHLY        -> 30L
            CommunicationRhythm.EVERY_3_MONTHS -> 90L
            CommunicationRhythm.EVERY_6_MONTHS -> 180L
            CommunicationRhythm.YEARLY         -> 365L
            else -> null
        }
        val daysSinceLast = contact.lastContactDate?.let { dateStr ->
            try {
                java.time.ChronoUnit.DAYS.between(java.time.LocalDate.parse(dateStr), today)
            } catch (e: Exception) { null }
        }
        val badgeColor = when {
            contact.contactStatus == ContactStatus.ARCHIVED -> null
            rhythmDays != null && daysSinceLast != null && daysSinceLast > rhythmDays ->
                androidx.compose.ui.graphics.Color(0xFFDC2626) // overdue — красный
            contact.createdAt.take(10) >= weekAgo ->
                androidx.compose.ui.graphics.Color(0xFF7C3AED) // новый — фиолетовый
            else ->
                androidx.compose.ui.graphics.Color(0xFF059669) // всё ок — зелёный
        }

        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = contact.firstName.take(1) + contact.lastName.take(1),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            // Бейдж статуса
            if (badgeColor != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Name + nickname
        Text(text = name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (!contact.nickname.isNullOrBlank())
            Text("«${contact.nickname}»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

        // Company · position
        if (company.isNotEmpty() || position.isNotEmpty())
            Text(
                listOf(company, position).filter { it.isNotEmpty() }.joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        if (city.isNotEmpty())
            Text(city, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

        Spacer(Modifier.height(10.dp))

        // Status row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(contact.contactStatus.label(), fontSize = 10.sp) })
            AssistChip(onClick = {}, label = { Text(contact.relationshipType.label(), fontSize = 10.sp) })
            AssistChip(onClick = {}, label = { Text(contact.importanceLevel.label(), fontSize = 10.sp) })
        }

        Spacer(Modifier.height(8.dp))

        // Last contact + next step
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (daysSince != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.AccessTime, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    val label = when {
                        daysSince == 0L -> "Общались сегодня"
                        daysSince == 1L -> "Общались вчера"
                        else            -> "Последний контакт: $daysSince дн. назад"
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        color = if (daysSince > 30) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.secondary)
                }
            }
            if (!contact.nextStep.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(contact.nextStep, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
            if (nearestDate != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Event, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Text("${nearestDate.title}: ${nearestDate.startDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (!contact.communicationRhythm.equals(CommunicationRhythm.NOT_TRACKED)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Repeat, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(contact.communicationRhythm.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Quick action buttons
        val context = androidx.compose.ui.platform.LocalContext.current
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickActionIcon(Icons.Outlined.Phone, "Позвонить") {
                val phone = contact.phones.find { it.isPrimary }?.number ?: contact.phones.firstOrNull()?.number
                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(context, phone)
            }
            // Write — use primary messenger, fallback to SMS
            QuickActionIcon(Icons.Outlined.ChatBubbleOutline, "Написать") {
                val m = contact.messengers.find { it.isPrimary } ?: contact.messengers.firstOrNull()
                if (m != null) com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(context, m)
                else com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(context, contact.phones.firstOrNull()?.number)
            }
            QuickActionIcon(Icons.Outlined.Email, "Email") {
                val email = contact.emails.find { it.isPrimary }?.email ?: contact.emails.firstOrNull()?.email
                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(context, email)
            }
            if (address != null) QuickActionIcon(Icons.Outlined.Map, "Карта") {
                if (address.latitude != null && address.longitude != null)
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                else
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
            }
            QuickActionIcon(Icons.Default.Lightbulb, "Шпаргалка") {
                onNavigateToCheatSheet()
            }
        }
    }
}

@Composable
fun QuickActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = cd, tint = MaterialTheme.colorScheme.primary)
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.overviewTab(
    contact: Contact,
    onNavigateToCreateCalendarItem: () -> Unit,
    onNavigateToCalendarItem: (String) -> Unit = {},
    onNavigateToContact: (String) -> Unit = {}
) {
    // ── Краткий контекст ────────────────────────────────────
    val impNotes = AppStateStore.notes.filter {
        it.contactId == contact.id && it.type == NoteType.IMPORTANT_TO_REMEMBER
    }
    if (impNotes.isNotEmpty()) {
        item {
            CardBlock(title = "Важно помнить") {
                impNotes.forEach { note ->
                    Text("• ${note.text}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    // ── F — FAMILY / Семья ──────────────────────────────────
    item {
        val relations = AppStateStore.contactRelations.filter {
            it.firstContactId == contact.id || it.secondContactId == contact.id
        }
        val familyRoles = setOf("Муж", "Жена", "Партнёр", "Отец", "Мать",
            "Сын", "Дочь", "Брат", "Сестра", "Родственник")
        val familyRelations = relations.filter { rel ->
            val role = if (rel.firstContactId == contact.id) rel.firstRole else rel.secondRole
            role in familyRoles
        }

        FordBlock(
            letter     = "F",
            title      = "Семья",
            color      = androidx.compose.ui.graphics.Color(0xFFE53935),
            isEmpty    = familyRelations.isEmpty()
        ) {
            if (familyRelations.isNotEmpty()) {
                familyRelations.forEach { rel ->
                    val isFirst   = rel.firstContactId == contact.id
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    val role      = if (isFirst) rel.firstRole else rel.secondRole
                    val other     = AppStateStore.getContact(otherId)
                    val otherName = other?.let { "${it.firstName} ${it.lastName}".trim() }
                        ?: "Контакт удалён"
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        Arrangement.SpaceBetween, Alignment.CenterVertically
                    ) {
                        Text(
                            role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(0.4f)
                        )
                        Text(
                            otherName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(0.6f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            } else {
                FordEmptyHint("Добавь родственников и близких во вкладке «Связи»")
            }
        }
    }

    // ── O — OCCUPATION / Работа ─────────────────────────────
    item {
        val compRel = contact.companyRelations.firstOrNull { it.isPrimary }
            ?: contact.companyRelations.firstOrNull()
        val hasWork = compRel != null

        FordBlock(
            letter  = "O",
            title   = "Работа",
            color   = androidx.compose.ui.graphics.Color(0xFF1E88E5),
            isEmpty = !hasWork
        ) {
            if (compRel != null) {
                val companyName = AppStateStore.getCompany(compRel.companyId)?.name ?: ""
                if (companyName.isNotBlank()) InfoRow("Компания", companyName)
                if (!compRel.position.isNullOrBlank())       InfoRow("Должность",   compRel.position)
                if (!compRel.department.isNullOrBlank())     InfoRow("Отдел",        compRel.department)
                if (!compRel.responsibilities.isNullOrBlank()) InfoRow("Задачи",     compRel.responsibilities)
                if (!compRel.workNote.isNullOrBlank())       InfoRow("Заметка",      compRel.workNote)
                // Work notes
                val workNotes = AppStateStore.notes.filter {
                    it.contactId == contact.id && it.type == NoteType.WORK
                }
                workNotes.forEach { n ->
                    Text("• ${n.text}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                FordEmptyHint("Укажи компанию при редактировании контакта")
            }
        }
    }

    // ── R — RECREATION / Интересы ───────────────────────────
    item {
        val interestCategories = setOf(
            PersonalDetailCategory.INTERESTS,
            PersonalDetailCategory.FOOD,
            PersonalDetailCategory.DRINKS,
            PersonalDetailCategory.HABITS,
            PersonalDetailCategory.BRANDS
        )
        val interests = contact.personalDetails.filter { it.category in interestCategories }

        FordBlock(
            letter  = "R",
            title   = "Интересы и привычки",
            color   = androidx.compose.ui.graphics.Color(0xFF43A047),
            isEmpty = interests.isEmpty()
        ) {
            if (interests.isNotEmpty()) {
                // Group by category
                interests.groupBy { it.category }.forEach { (cat, items) ->
                    Text(
                        cat.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement   = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items.forEach { detail ->
                            Surface(
                                shape  = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                color  = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    detail.value,
                                    style    = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color    = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            } else {
                FordEmptyHint("Добавь хобби, еду, напитки через редактирование")
            }
        }
    }

    // ── D — DREAMS / Цели и мечты ───────────────────────────
    item {
        val dreamNotes = AppStateStore.notes.filter {
            it.contactId == contact.id && it.type == NoteType.PERSONAL_DETAIL
        }
        val restrictCategories = setOf(
            PersonalDetailCategory.ALLERGIES,
            PersonalDetailCategory.RESTRICTIONS
        )
        val restrictions = contact.personalDetails.filter { it.category in restrictCategories }

        FordBlock(
            letter  = "D",
            title   = "Цели, мечты и важное",
            color   = androidx.compose.ui.graphics.Color(0xFFFB8C00),
            isEmpty = dreamNotes.isEmpty() && restrictions.isEmpty()
        ) {
            if (dreamNotes.isNotEmpty()) {
                dreamNotes.forEach { note ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🎯", style = MaterialTheme.typography.bodySmall)
                        Text(note.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (restrictions.isNotEmpty()) {
                Text(
                    "Ограничения",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                restrictions.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠️", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${r.category.label()}: ${r.value}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (dreamNotes.isEmpty() && restrictions.isEmpty()) {
                FordEmptyHint("Добавь заметки типа «Личная деталь» при редактировании")
            }
        }
    }

    // ── Ближайшие события ───────────────────────────────────
    item {
        val upcoming = AppStateStore.calendarItems.filter {
            it.links.any { link -> link.targetId == contact.id } &&
            it.status == CalendarItemStatus.ACTIVE
        }.sortedBy { it.startDate }.take(3)

        CardBlock(title = "Ближайшее") {
            if (upcoming.isNotEmpty()) {
                upcoming.forEach { item ->
                    InfoRow(item.startDate, item.title)
                }
            } else {
                Text(
                    "Нет запланированных событий",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNavigateToCreateCalendarItem,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить дату / событие")
            }
        }
    }

    // ── Статус и классификация (из вкладки Детали) ───────────
    item {
        CardBlock(title = "Статус и классификация") {
            InfoRow("Статус",          contact.contactStatus.label())
            InfoRow("Тип отношений",   contact.relationshipType.label())
            InfoRow("Уровень связи",   contact.connectionLevel.label())
            InfoRow("Важность",        contact.importanceLevel.label())
            InfoRow("Социальная роль", contact.socialRole.label())
            InfoRow("Ритм общения",    contact.communicationRhythm.label())
        }
    }

    // ── Теги ─────────────────────────────────────────────────
    item {
        if (contact.tags.isNotEmpty()) {
            CardBlock(title = "Теги") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(6.dp)
                ) {
                    contact.tags.forEach { tag ->
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                tag,
                                style    = MaterialTheme.typography.labelMedium,
                                color    = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Важные даты ───────────────────────────────────────────
    item {
        val dates = AppStateStore.calendarItems.filter { item ->
            item.links.any { it.targetId == contact.id } &&
            item.type in listOf(
                CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
                CalendarItemType.IMPORTANT_DATE
            )
        }
        if (dates.isNotEmpty()) {
            CardBlock(title = "Важные даты") {
                dates.forEach { date ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToCalendarItem(date.id) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (date.type == CalendarItemType.BIRTHDAY)
                                    Icons.Default.Cake
                                else Icons.Outlined.Event,
                                null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(date.title, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            date.startDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }

    // ── Связанные люди ────────────────────────────────────────
    item {
        val relations = AppStateStore.contactRelations.filter {
            it.firstContactId == contact.id || it.secondContactId == contact.id
        }
        if (relations.isNotEmpty()) {
            CardBlock(title = "Связанные люди") {
                relations.forEach { rel ->
                    val isFirst   = rel.firstContactId == contact.id
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    val myRole    = if (isFirst) rel.firstRole else rel.secondRole
                    val other     = AppStateStore.getContact(otherId)
                    val otherName = other?.let { "${it.firstName} ${it.lastName}".trim() } ?: "Неизвестно"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToContact(otherId) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (other?.firstName?.firstOrNull()?.toString() ?: "?") +
                                    (other?.lastName?.firstOrNull()?.toString() ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column {
                                Text(otherName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (!myRole.isNullOrBlank())
                                    Text(myRole, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ─── FORD block wrapper ───────────────────────────────────────
@Composable
private fun FordBlock(
    letter: String,
    title: String,
    color: androidx.compose.ui.graphics.Color,
    isEmpty: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                // Letter badge
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        letter,
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color      = color
                    )
                }
                Text(
                    title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isEmpty) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "пусто",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun FordEmptyHint(text: String) {
    Text(
        text,
        style    = MaterialTheme.typography.bodySmall,
        color    = MaterialTheme.colorScheme.outlineVariant
    )
}

fun androidx.compose.foundation.lazy.LazyListScope.connectionsTab(
    contact: Contact,
    onNavigateToContact: (String) -> Unit = {}
) {
    // ── Компании ─────────────────────────────────────────────
    if (contact.companyRelations.isNotEmpty()) {
        item {
            Text(
                "Компании",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                contact.companyRelations.forEach { rel ->
                    val company = AppStateStore.getCompany(rel.companyId)
                    if (company != null) {
                        CardBlock {
                            Row(
                                Modifier.fillMaxWidth(),
                                Arrangement.SpaceBetween,
                                Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(company.name, fontWeight = FontWeight.Bold)
                                    if (!rel.position.isNullOrBlank())
                                        Text(
                                            rel.position,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    if (!rel.department.isNullOrBlank())
                                        Text(
                                            rel.department,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    if (rel.employmentStatus == EmploymentStatus.FORMER) {
                                        Text(
                                            "Бывшее место работы",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Default.Business,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    // ── Связанные люди ────────────────────────────────────────
    item {
        Text(
            "Связанные контакты",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
    }

    item {
        // FIX: correct role — if I am firstContact, show MY role (firstRole)
        // and the OTHER person's role towards me (secondRole)
        val relations = AppStateStore.contactRelations.filter {
            it.firstContactId == contact.id || it.secondContactId == contact.id
        }

        if (relations.isEmpty()) {
            CardBlock {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.PersonAdd,
                        null,
                        Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Column {
                        Text(
                            "Нет связанных контактов",
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Добавь связи при редактировании контакта",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                relations.forEach { rel ->
                    // FIX: correct mapping
                    val isFirst   = rel.firstContactId == contact.id
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    // myRole = how this contact relates to the other
                    val myRole    = if (isFirst) rel.firstRole  else rel.secondRole
                    // theirRole = how the other relates to this contact
                    val theirRole = if (isFirst) rel.secondRole else rel.firstRole

                    val other = AppStateStore.getContact(otherId)
                    val otherName = other?.let {
                        "${it.firstName} ${it.lastName}".trim()
                    } ?: "Контакт не найден"

                    val compRel = other?.companyRelations
                        ?.firstOrNull { it.isPrimary }
                        ?: other?.companyRelations?.firstOrNull()
                    val otherCompany = compRel?.companyId
                        ?.let { AppStateStore.getCompany(it)?.name } ?: ""

                    Card(
                        onClick   = { if (other != null) onNavigateToContact(otherId) },
                        modifier  = Modifier.fillMaxWidth(),
                        shape     = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors    = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(1.dp),
                        enabled   = other != null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Avatar
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (other?.firstName?.firstOrNull()?.toString() ?: "?"),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(otherName, fontWeight = FontWeight.SemiBold)
                                if (otherCompany.isNotBlank()) {
                                    Text(
                                        otherCompany,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                // Show the relationship
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            myRole,
                                            style    = MaterialTheme.typography.labelSmall,
                                            color    = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    if (theirRole != myRole) {
                                        Surface(
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                theirRole,
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                                if (!rel.note.isNullOrBlank()) {
                                    Text(
                                        rel.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                tint = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.importantTab(
    contact: Contact,
    onNavigateToCalendarItem: (String) -> Unit,
    onShowAdd: () -> Unit,
    onShowVoice: () -> Unit
) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Button(onClick = onShowAdd, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить")
            }
            Button(onClick = onShowVoice, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("+ Деталь")
            }
        }
    }

    item {
        val validDateTypes = listOf(CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY, CalendarItemType.IMPORTANT_DATE, CalendarItemType.CUSTOM, CalendarItemType.NOTE)
        val dates = AppStateStore.calendarItems.filter { item ->
            item.links.any { link -> link.targetId == contact.id } && item.type in validDateTypes
        }
        
        CardBlock(title = "Даты") {
            if (dates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    dates.forEach { date ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onNavigateToCalendarItem(date.id) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isBirthday = date.type == CalendarItemType.BIRTHDAY
                            val iconTint = if (isBirthday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            val icon = if (isBirthday) Icons.Default.Cake else Icons.Outlined.Event
                            
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(date.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isBirthday) FontWeight.Bold else FontWeight.Medium)
                                    if (date.importance in listOf(ImportanceLevel.IMPORTANT, ImportanceLevel.KEY)) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                    }
                                }
                                Text(date.startDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (date.recurrenceRule != null) {
                                    Icon(Icons.Outlined.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                                }
                                if (date.reminders.isNotEmpty()) {
                                    Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет дат", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val gifts = AppStateStore.gifts.filter { it.contactId == contact.id }
        CardBlock(title = "Подарки") {
            if (gifts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    gifts.forEach { gift ->
                        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(gift.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (gift.link != null) Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                    if (gift.reminderId != null) Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                AssistChip(onClick = {}, label = { Text(gift.status.label(), fontSize = 10.sp) })
                                if (gift.date != null) {
                                    Text(gift.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            if (gift.note != null) {
                                Text(gift.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет подарков", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val size = AppStateStore.sizeInfos.find { it.contactId == contact.id }
        CardBlock(title = "Размеры") {
            if (size != null && (size.clothingSize != null || size.shoeSize != null || size.ringSize != null)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (size.clothingSize != null) SizeChip("Одежда", size.clothingSize)
                    if (size.shoeSize != null) SizeChip("Обувь", size.shoeSize)
                    if (size.ringSize != null) SizeChip("Кольцо", size.ringSize)
                }
            } else {
                Text("Пока нет размеров", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val pd = AppStateStore.personalDetails.filter { it.contactId == contact.id }
        CardBlock(title = "Личные детали") {
            if (pd.isNotEmpty()) {
                val grouped = pd.groupBy { it.category }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    grouped.forEach { (category, details) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(category.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            details.forEach { detail ->
                                Column {
                                    Text("• ${detail.value}", style = MaterialTheme.typography.bodyMedium)
                                    if (detail.note != null) {
                                        Text(detail.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет личных деталей", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    item {
        val notes = AppStateStore.notes.filter { it.contactId == contact.id && it.type in listOf(NoteType.IMPORTANT_TO_REMEMBER, NoteType.PERSONAL_DETAIL, NoteType.GIFT, NoteType.DATE_EVENT) }
        CardBlock(title = "Заметки") {
            if (notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    notes.forEach { note ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(note.type.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (note.isImportant) {
                                        Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                    if (note.date != null) {
                                        Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
                                    }
                                    if (note.calendarItemId != null) {
                                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(note.text, style = MaterialTheme.typography.bodyMedium)
                            if (note.date != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(note.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            } else {
                Text("Пока нет заметок", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.historyTab(contact: Contact) {
    item {
        var hasHistory = false
        val pastEvents = AppStateStore.calendarItems.filter { it.links.any { link -> link.targetId == contact.id } && it.status == CalendarItemStatus.COMPLETED }
        if (pastEvents.isNotEmpty()) {
            hasHistory = true
            CardBlock(title = "Прошедшие события") {
                pastEvents.forEach { InfoRow(it.startDate, it.title) }
            }
        }
        
        val givenGifts = AppStateStore.gifts.filter { it.contactId == contact.id && it.status == GiftStatus.GIVEN }
        if (givenGifts.isNotEmpty()) {
            hasHistory = true
            CardBlock(title = "Подарено") {
                givenGifts.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        
        if (!hasHistory) {
             Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                 Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                     Icon(Icons.Outlined.History, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                     Text("История пуста", color = MaterialTheme.colorScheme.secondary)
                 }
             }
        }
    }
}

@Composable
fun StatChip(
    label: String,
    bgColor: androidx.compose.ui.graphics.Color,
    fgColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = bgColor
    ) {
        Text(
            label,
            style    = MaterialTheme.typography.labelSmall,
            color    = fgColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun CardBlock(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).let { 
        if (onClick != null) it.clickable { onClick() } else it 
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SizeChip(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 1 — РАБОТА
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.workTab(contact: Contact) {
    item {
        val compRels = contact.companyRelations
        if (compRels.isEmpty()) {
            CardBlock {
                Text(
                    "Нет данных о работе",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            return@item
        }
        compRels.forEach { rel ->
            val company = AppStateStore.getCompany(rel.companyId)
            CardBlock(title = if (rel.isPrimary) "Основное место работы" else "Дополнительно") {
                if (company != null) InfoRow("Компания", company.name)
                if (!rel.position.isNullOrBlank())       InfoRow("Должность",   rel.position)
                if (!rel.department.isNullOrBlank())     InfoRow("Отдел",       rel.department)
                if (!rel.role.isNullOrBlank())           InfoRow("Роль",        rel.role)
                if (!rel.responsibilities.isNullOrBlank()) InfoRow("Задачи",    rel.responsibilities)
                InfoRow("Статус", rel.employmentStatus.label())
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

    // Где познакомились
    item {
        if (!contact.meetContext.isNullOrBlank() || !contact.meetDate.isNullOrBlank()) {
            CardBlock(title = "Где познакомились") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.Top
                ) {
                    Icon(Icons.Default.Handshake, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (!contact.meetContext.isNullOrBlank())
                            Text(contact.meetContext, style = MaterialTheme.typography.bodyMedium)
                        if (!contact.meetDate.isNullOrBlank())
                            Text(contact.meetDate, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }

    // Чем может помочь / Чем я могу помочь
    item {
        CardBlock(title = "Взаимная польза") {
            if (!contact.canHelpWith.isNullOrBlank()) {
                Text(
                    "Чем может помочь",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(contact.canHelpWith, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            if (!contact.iCanHelpWith.isNullOrBlank()) {
                Text(
                    "Чем я могу помочь",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(contact.iCanHelpWith, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            if (!contact.talkingPoints.isNullOrBlank()) {
                Text(
                    "Темы для разговора",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(contact.talkingPoints, style = MaterialTheme.typography.bodyMedium)
            }
            if (contact.canHelpWith.isNullOrBlank() && contact.iCanHelpWith.isNullOrBlank() && contact.talkingPoints.isNullOrBlank()) {
                Text(
                    "Заполни при редактировании",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }

    // Work notes
    item {
        val workNotes = AppStateStore.notes.filter {
            it.contactId == contact.id && it.type == NoteType.WORK
        }
        if (workNotes.isNotEmpty()) {
            CardBlock(title = "Рабочие заметки") {
                workNotes.forEach { note ->
                    Text("• ${note.text}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 2 — СВЯЗЬ (каналы коммуникации)
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.communicationTab(contact: Contact) {
    // Phones
    item {
        CardBlock(title = "Телефоны") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (contact.phones.isEmpty()) {
                Text("Нет телефонов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                contact.phones.forEach { phone ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(phone.number, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(phone.type.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(ctx, phone.number) }) {
                                Icon(Icons.Outlined.Phone, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(ctx, phone.number) }) {
                                Icon(Icons.Default.Sms, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }

    // Emails
    item {
        CardBlock(title = "Email") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (contact.emails.isEmpty()) {
                Text("Нет email", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                contact.emails.forEach { email ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(email.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(email.type.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(ctx, email.email) }) {
                            Icon(Icons.Outlined.Email, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }

    // Messengers
    item {
        CardBlock(title = "Мессенджеры") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (contact.messengers.isEmpty()) {
                Text("Нет мессенджеров", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                contact.messengers.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(m.type.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            if (!m.comment.isNullOrBlank())
                                Text(m.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        IconButton(onClick = { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(ctx, m) }) {
                            Icon(Icons.Default.Chat, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }

    // Addresses
    item {
        val addresses = AppStateStore.addresses.filter {
            it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT
        }
        CardBlock(title = "Адреса") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (addresses.isEmpty()) {
                Text("Нет адресов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                addresses.forEach { addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOf(addr.addressLine, addr.city, addr.country).filter { it.isNotBlank() }.joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(addr.addressType.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = {
                            if (addr.latitude != null && addr.longitude != null)
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(ctx, addr.latitude, addr.longitude)
                            else
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(ctx, "${addr.addressLine}, ${addr.city}")
                        }) {
                            Icon(Icons.Outlined.Map, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 3 — ПОДАРКИ
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.giftsTab(
    contact: Contact,
    onNavigateToCalendarItem: (String) -> Unit
) {
    // ── Важные даты вверху ───────────────────────────────────
    item {
        val today = java.time.LocalDate.now()
        val importantDates = AppStateStore.calendarItems.filter { item ->
            item.links.any { it.targetId == contact.id } &&
            item.type in listOf(
                CalendarItemType.BIRTHDAY,
                CalendarItemType.ANNIVERSARY,
                CalendarItemType.IMPORTANT_DATE
            )
        }
        if (importantDates.isNotEmpty()) {
            CardBlock(title = "Важные даты") {
                importantDates.forEach { date ->
                    val daysUntil = try {
                        val d = java.time.LocalDate.parse(date.startDate)
                        // Переносим на текущий год
                        val thisYear = d.withYear(today.year)
                        val next = if (thisYear < today) thisYear.plusYears(1) else thisYear
                        java.time.ChronoUnit.DAYS.between(today, next)
                    } catch (e: Exception) { null }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToCalendarItem(date.id) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val emoji = when (date.type) {
                                CalendarItemType.BIRTHDAY    -> "🎂"
                                CalendarItemType.ANNIVERSARY -> "💍"
                                else                         -> "⭐"
                            }
                            Text(emoji, fontSize = 16.sp)
                            Text(
                                date.title,
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (daysUntil != null) {
                            Text(
                                when {
                                    daysUntil == 0L -> "Сегодня! 🎉"
                                    daysUntil > 0   -> "через $daysUntil дн."
                                    else            -> "${-daysUntil} дн. назад"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    daysUntil in 0..7 -> MaterialTheme.colorScheme.error
                                    daysUntil in 8..30 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // ── Подарки с 3 статусами ────────────────────────────────
    item {
        val gifts = AppStateStore.gifts.filter { it.contactId == contact.id }
        val ideas  = gifts.filter { it.status == GiftStatus.IDEA }
        val bought = gifts.filter { it.status == GiftStatus.BOUGHT }
        val given  = gifts.filter { it.status == GiftStatus.GIVEN }

        // Идеи
        CardBlock(title = "Идеи подарков") {
            if (ideas.isEmpty()) {
                Text(
                    "Нет идей",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                ideas.forEach { gift ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("✨", fontSize = 14.sp)
                            Column {
                                Text(
                                    gift.title,
                                    style      = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (!gift.note.isNullOrBlank())
                                    Text(
                                        gift.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                if (!gift.link.isNullOrBlank())
                                    Text(
                                        gift.link,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                            }
                        }
                        // Кнопка перехода в статус BOUGHT
                        TextButton(
                            onClick = { AppStateStore.updateGift(gift.copy(status = GiftStatus.BOUGHT)) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Купить 🛍️", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }

        // Куплено
        if (bought.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardBlock(title = "Куплено 🛍️") {
                bought.forEach { gift ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            gift.title,
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier   = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { AppStateStore.updateGift(gift.copy(status = GiftStatus.GIVEN)) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Подарить ✅", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }

        // Подарено
        if (given.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CardBlock(title = "Подарено ранее ✅") {
                given.forEach { gift ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("• ${gift.title}", style = MaterialTheme.typography.bodyMedium)
                        if (!gift.date.isNullOrBlank())
                            Text(
                                gift.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                    }
                }
            }
        }
    }

    // ── Предпочтения ─────────────────────────────────────────
    item {
        val size = AppStateStore.sizeInfos.find { it.contactId == contact.id }
        val prefCats = listOf(
            PersonalDetailCategory.FOOD, PersonalDetailCategory.DRINKS,
            PersonalDetailCategory.LIKES, PersonalDetailCategory.DISLIKES,
            PersonalDetailCategory.ALLERGIES, PersonalDetailCategory.RESTRICTIONS
        )
        val prefs = contact.personalDetails.filter { it.category in prefCats }

        if (size != null || prefs.isNotEmpty()) {
            CardBlock(title = "Предпочтения и размеры") {
                if (size != null) {
                    Text(
                        "Размеры",
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!size.clothingSize.isNullOrBlank()) SizeChip("Одежда", size.clothingSize)
                        if (!size.shoeSize.isNullOrBlank())    SizeChip("Обувь",   size.shoeSize)
                        if (!size.ringSize.isNullOrBlank())    SizeChip("Кольцо",  size.ringSize)
                        if (!size.other.isNullOrBlank())       SizeChip("Другое",  size.other)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (prefs.isNotEmpty()) {
                    prefs.groupBy { it.category }.forEach { (cat, items) ->
                        Text(
                            cat.label(),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        items.forEach { Text("• ${it.value}", style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(6.dp))
                    }
                } else if (size == null) {
                    Text(
                        "Нет предпочтений",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════
// TAB 4 — ЗАМЕТКИ (Timeline по месяцам)
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.notesTab(
    contact: Contact,
    onShowAdd: () -> Unit,
    onShowVoice: () -> Unit
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onShowAdd,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor   = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить")
            }
            Button(
                onClick = onShowVoice,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor   = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.PersonAdd, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("+ Деталь")
            }
        }
    }

    item {
        val allNotes = AppStateStore.notes
            .filter { it.contactId == contact.id }
            .sortedByDescending { it.createdAt }

        if (allNotes.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.Notes, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant)
                    Text("Пока нет заметок", color = MaterialTheme.colorScheme.secondary)
                    Text("Нажми «Добавить» чтобы записать",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            return@item
        }

        // Статистика вверху
        val importantCnt = allNotes.count { it.isImportant }
        val workCnt      = allNotes.count { it.type == NoteType.WORK }
        val eventCnt     = allNotes.count { it.type == NoteType.DATE_EVENT }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (importantCnt > 0) StatChip("⭐ $importantCnt важных",
                MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
            if (workCnt > 0) StatChip("💼 $workCnt рабочих",
                MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary)
            if (eventCnt > 0) StatChip("📅 $eventCnt событий",
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        }

        // Группировка по месяцам
        val ruMonths = mapOf(
            1 to "Январь", 2 to "Февраль", 3 to "Март", 4 to "Апрель",
            5 to "Май", 6 to "Июнь", 7 to "Июль", 8 to "Август",
            9 to "Сентябрь", 10 to "Октябрь", 11 to "Ноябрь", 12 to "Декабрь"
        )
        val notesByMonth = allNotes.groupBy { note ->
            try {
                val d = java.time.LocalDate.parse(note.createdAt.take(10))
                "${ruMonths[d.monthValue]} ${d.year}"
            } catch (e: Exception) { "Другое" }
        }

        Column {
            notesByMonth.forEach { (monthLabel, monthNotes) ->
                // Заголовок месяца
                Text(
                    monthLabel,
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.secondary,
                    modifier   = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                // Заметки месяца с Timeline
                monthNotes.forEachIndexed { idx, note ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Линия + точка
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (note.type) {
                                            NoteType.IMPORTANT_TO_REMEMBER ->
                                                MaterialTheme.colorScheme.error
                                            NoteType.WORK ->
                                                MaterialTheme.colorScheme.tertiary
                                            NoteType.DATE_EVENT ->
                                                MaterialTheme.colorScheme.primary
                                            else ->
                                                MaterialTheme.colorScheme.outline
                                        }
                                    )
                            )
                            if (idx < monthNotes.size - 1) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(if (note.text.length > 80) 80.dp else 56.dp)
                                        .background(
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                )
                            }
                        }
                        // Карточка заметки
                        Card(
                            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                            shape    = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            colors   = CardDefaults.cardColors(
                                containerColor = when {
                                    note.isImportant ->
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                    note.type == NoteType.WORK ->
                                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                                    else ->
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                }
                            ),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    Arrangement.SpaceBetween,
                                    Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment     = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                note.type.label(),
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(
                                                    horizontal = 6.dp, vertical = 2.dp
                                                )
                                            )
                                        }
                                        if (note.isImportant)
                                            Icon(Icons.Outlined.Star, null,
                                                Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.error)
                                    }
                                    Text(
                                        note.createdAt.take(10),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(note.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    // Личные детали
    item {
        val pd = contact.personalDetails.filter {
            it.category in setOf(
                PersonalDetailCategory.INTERESTS, PersonalDetailCategory.HABITS,
                PersonalDetailCategory.BRANDS, PersonalDetailCategory.COMMUNICATION_STYLE,
                PersonalDetailCategory.OTHER
            )
        }
        if (pd.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CardBlock(title = "Личные детали") {
                pd.groupBy { it.category }.forEach { (cat, items) ->
                    Text(cat.label(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    items.forEach { d ->
                        Text("• ${d.value}", style = MaterialTheme.typography.bodyMedium)
                        if (!d.note.isNullOrBlank())
                            Text("  ${d.note}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TAB 5 — ДЕТАЛИ
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.detailsTab(
    contact: Contact,
    onNavigateToCalendarItem: (String) -> Unit,
    onNavigateToContact: (String) -> Unit = {}
) {
    // Status & classification
    item {
        CardBlock(title = "Статус и классификация") {
            InfoRow("Статус",          contact.contactStatus.label())
            InfoRow("Тип отношений",   contact.relationshipType.label())
            InfoRow("Уровень связи",   contact.connectionLevel.label())
            InfoRow("Важность",        contact.importanceLevel.label())
            InfoRow("Социальная роль", contact.socialRole.label())
            InfoRow("Ритм общения",    contact.communicationRhythm.label())
        }
    }

    // Tags
    item {
        CardBlock(title = "Теги") {
            if (contact.tags.isEmpty()) {
                Text("Нет тегов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    contact.tags.forEach { tag ->
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }

    // Dates
    item {
        val dates = AppStateStore.calendarItems.filter { item ->
            item.links.any { it.targetId == contact.id } &&
            item.type in listOf(CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY, CalendarItemType.IMPORTANT_DATE)
        }
        CardBlock(title = "Важные даты") {
            if (dates.isEmpty()) {
                Text("Нет дат", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                dates.forEach { date ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onNavigateToCalendarItem(date.id) }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (date.type == CalendarItemType.BIRTHDAY) Icons.Default.Cake else Icons.Outlined.Event,
                                null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary
                            )
                            Text(date.title, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(date.startDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }

    // Connected contacts
    item {
        val relations = AppStateStore.contactRelations.filter {
            it.firstContactId == contact.id || it.secondContactId == contact.id
        }
        CardBlock(title = "Связанные люди") {
            if (relations.isEmpty()) {
                Text("Нет связанных контактов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                relations.forEach { rel ->
                    val isFirst   = rel.firstContactId == contact.id
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    val myRole    = if (isFirst) rel.firstRole else rel.secondRole
                    val other     = AppStateStore.getContact(otherId)
                    val otherName = other?.let { "${it.firstName} ${it.lastName}".trim() } ?: "—"
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { if (other != null) onNavigateToContact(otherId) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(otherName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(myRole, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }

    // History
    item {
        val completed = AppStateStore.calendarItems.filter {
            it.links.any { l -> l.targetId == contact.id } && it.status == CalendarItemStatus.COMPLETED
        }
        val givenGifts = AppStateStore.gifts.filter {
            it.contactId == contact.id && it.status == GiftStatus.GIVEN
        }
        if (completed.isNotEmpty() || givenGifts.isNotEmpty()) {
            CardBlock(title = "История") {
                completed.forEach { InfoRow(it.startDate, it.title) }
                givenGifts.forEach { Text("🎁 ${it.title}${if (!it.date.isNullOrBlank()) " · ${it.date}" else ""}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
