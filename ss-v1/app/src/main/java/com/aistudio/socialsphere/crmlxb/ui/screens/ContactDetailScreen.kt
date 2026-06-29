@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.ui.components.TabEditBar
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
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
    onNavigateToCheatSheet: () -> Unit = {},
    onNavigateToCompany: (String) -> Unit = {}
) {
    val contact = AppStateStore.getContact(contactId)
    val ctxLabel = LocalContext.current
    if (contact == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.cd_not_found))
        }
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.cd_tab_overview), stringResource(R.string.cd_tab_work),
        stringResource(R.string.cd_tab_comm), stringResource(R.string.cd_tab_gifts), stringResource(R.string.cd_tab_notes))

    var showAddDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var editingNote  by remember { mutableStateOf<Note?>(null) }
    var deletingNote by remember { mutableStateOf<Note?>(null) }
    var showAddGift     by remember { mutableStateOf(false) }
    var editingGift     by remember { mutableStateOf<GiftIdea?>(null) }
    var deletingGift    by remember { mutableStateOf<GiftIdea?>(null) }
    var showSizesDialog by remember { mutableStateOf(false) }
    var showAddPref     by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Add note state
    var noteText by remember { mutableStateOf("") }
    var noteType by remember { mutableStateOf(NoteType.GENERAL) }
    var noteIsImportant by remember { mutableStateOf(false) }
    // Режим приватности — скрывает «защищённые» (важные) заметки блюром.
    // Только на сессию, без персиста (как в макете).
    var privacyMode by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = AppleTheme.colors.red) },
            title = { Text(stringResource(R.string.cd_delete_q), fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.cd_delete_warning, "${contact.firstName} ${contact.lastName}")) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        AppStateStore.deleteContact(contactId)
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.red)
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    Row(
                        modifier = Modifier.clickable { onNavigateBack() }.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.common_back), tint = AppleTheme.colors.brand, modifier = Modifier.size(28.dp))
                        Text(stringResource(R.string.common_back), color = AppleTheme.colors.brand, fontSize = 17.sp)
                    }
                },
                actions = {
                    // Замок приватности (по макету): малахит-кружок когда вкл.
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (privacyMode) AppleTheme.colors.brand
                                else AppleTheme.colors.fill
                            )
                            .clickable { privacyMode = !privacyMode },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (privacyMode) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.cd_privacy_toggle),
                            modifier = Modifier.size(18.dp),
                            tint = if (privacyMode) Color.White else AppleTheme.colors.secondaryLabel
                        )
                    }
                    TextButton(onClick = onNavigateToEdit) {
                        Text(stringResource(R.string.cd_edit_short), color = AppleTheme.colors.brand, fontSize = 17.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppleTheme.colors.groupedBackground,
                    navigationIconContentColor = AppleTheme.colors.brand,
                    actionIconContentColor = AppleTheme.colors.brand,
                    titleContentColor = AppleTheme.colors.label
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Header
            ContactHeader(contact, onNavigateToCheatSheet, onNavigateToCreateCalendarItem)

            // Сегмент-контрол (спека HTML): трек fill r9, активный — белый r7
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(AppleTheme.colors.fill)
                    .padding(2.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val sel = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .then(if (sel) Modifier.background(AppleTheme.colors.card) else Modifier)
                            .clickable { selectedTab = index }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (sel) AppleTheme.colors.label else AppleTheme.colors.secondaryLabel
                        )
                    }
                }
            }

            var editingOverview by remember { mutableStateOf(false) }
            var editingComm by remember { mutableStateOf(false) }
            var editingWork by remember { mutableStateOf(false) }
            LaunchedEffect(selectedTab) { editingOverview = false; editingComm = false; editingWork = false }

            // Tab Content
            // ВАЖНО: weight(1f), НЕ fillMaxSize() — внутри Column fillMaxSize даёт
            // списку всю высоту экрана, он встаёт под шапкой/табами и низ уходит
            // за край, скролл схлопывается до «полэкрана» (повторяющийся баг).
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> overviewTab(contact, onNavigateToCreateCalendarItem, onNavigateToCalendarItem, onNavigateToContact, onNavigateToCompany, ctxLabel = ctxLabel, editing = editingOverview, onEditingChange = { editingOverview = it }, onDelete = { showDeleteDialog = true })
                    1 -> workTab(contact, onNavigateToCompany, ctxLabel = ctxLabel, editing = editingWork, onEditingChange = { editingWork = it })
                    2 -> communicationTab(contact, ctxLabel = ctxLabel, editing = editingComm, onEditingChange = { editingComm = it })
                    3 -> giftsTab(
                        contact, onNavigateToCalendarItem,
                        onAddGift    = { showAddGift = true },
                        onEditGift   = { editingGift = it },
                        onDeleteGift = { deletingGift = it },
                        onEditSizes  = { showSizesDialog = true },
                        onAddPref    = { showAddPref = true },
                        onDeletePref = { pref ->
                            AppStateStore.updateContact(contact.copy(
                                personalDetails = contact.personalDetails.filter { it.id != pref.id }
                            ))
                        }
                    , ctxLabel = ctxLabel)
                    4 -> notesTab(
                        contact      = contact,
                        onShowAdd    = { showAddDialog = true },
                        onShowVoice  = { showVoiceDialog = true },
                        onEditNote   = { editingNote = it },
                        onDeleteNote = { deletingNote = it }
                    , ctxLabel = ctxLabel,
                        privacyMode = privacyMode,
                        onTogglePrivacy = { privacyMode = false })
                }
            }
        }
    }

    // ── Добавление / правка идеи подарка ──
    if (showAddGift || editingGift != null) {
        val g = editingGift
        var gTitle by remember(g?.id ?: "new") { mutableStateOf(g?.title ?: "") }
        var gNote  by remember(g?.id ?: "new") { mutableStateOf(g?.note ?: "") }
        var gLink  by remember(g?.id ?: "new") { mutableStateOf(g?.link ?: "") }
        AlertDialog(
            onDismissRequest = { showAddGift = false; editingGift = null },
            title = { Text(if (g == null) stringResource(R.string.cd_gift_idea) else stringResource(R.string.cd_gift_edit), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = gTitle, onValueChange = { gTitle = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_title_req)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = gNote, onValueChange = { gNote = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_note)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    OutlinedTextField(value = gLink, onValueChange = { gLink = it },
                        label = { Text(stringResource(R.string.cd_link)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Button(enabled = gTitle.isNotBlank(), onClick = {
                    if (g == null) {
                        AppStateStore.addGift(GiftIdea(
                            id = java.util.UUID.randomUUID().toString(),
                            contactId = contact.id,
                            title = gTitle.trim(),
                            note  = gNote.trim().ifBlank { null },
                            link  = gLink.trim().ifBlank { null },
                            status = GiftStatus.IDEA
                        ))
                    } else {
                        AppStateStore.updateGift(g.copy(
                            title = gTitle.trim(),
                            note  = gNote.trim().ifBlank { null },
                            link  = gLink.trim().ifBlank { null }
                        ))
                    }
                    showAddGift = false; editingGift = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddGift = false; editingGift = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // ── Подтверждение удаления идеи ──
    deletingGift?.let { gift ->
        AlertDialog(
            onDismissRequest = { deletingGift = null },
            title = { Text(stringResource(R.string.cd_gift_delete_q), fontWeight = FontWeight.Bold) },
            text = { Text(gift.title) },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.red),
                    onClick = { AppStateStore.deleteGift(gift.id); deletingGift = null }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingGift = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // ── Размеры ──
    if (showSizesDialog) {
        val existing = AppStateStore.sizeInfos.find { it.contactId == contact.id }
        var sClothing by remember { mutableStateOf(existing?.clothingSize ?: "") }
        var sShoe     by remember { mutableStateOf(existing?.shoeSize ?: "") }
        var sRing     by remember { mutableStateOf(existing?.ringSize ?: "") }
        var sOther    by remember { mutableStateOf(existing?.other ?: "") }
        AlertDialog(
            onDismissRequest = { showSizesDialog = false },
            title = { Text(stringResource(R.string.cd_sizes), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = sClothing, onValueChange = { sClothing = it },
                        label = { Text(stringResource(R.string.cd_clothes_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = sShoe, onValueChange = { sShoe = it },
                        label = { Text(stringResource(R.string.cd_shoes_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = sRing, onValueChange = { sRing = it },
                        label = { Text(stringResource(R.string.cd_ring_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = sOther, onValueChange = { sOther = it },
                        label = { Text(stringResource(R.string.common_other)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    AppStateStore.setSizeInfo(contact.id, SizeInfo(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        contactId    = contact.id,
                        clothingSize = sClothing.trim().ifBlank { null },
                        shoeSize     = sShoe.trim().ifBlank { null },
                        ringSize     = sRing.trim().ifBlank { null },
                        other        = sOther.trim().ifBlank { null }
                    ))
                    showSizesDialog = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showSizesDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // ── Добавление предпочтения ──
    if (showAddPref) {
        val prefCategories = listOf(
            PersonalDetailCategory.FOOD, PersonalDetailCategory.DRINKS,
            PersonalDetailCategory.LIKES, PersonalDetailCategory.DISLIKES,
            PersonalDetailCategory.BRANDS, PersonalDetailCategory.ALLERGIES,
            PersonalDetailCategory.RESTRICTIONS
        )
        var prefCat   by remember { mutableStateOf(PersonalDetailCategory.FOOD) }
        var prefValue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPref = false },
            title = { Text(stringResource(R.string.cd_preference), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DropdownField(stringResource(R.string.cd_category), prefCat.label(ctxLabel), prefCategories.map { it.label(ctxLabel) }) { picked ->
                        prefCat = prefCategories.firstOrNull { it.label(ctxLabel) == picked } ?: prefCat
                    }
                    OutlinedTextField(value = prefValue, onValueChange = { prefValue = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_value_hint)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Button(enabled = prefValue.isNotBlank(), onClick = {
                    AppStateStore.updateContact(contact.copy(
                        personalDetails = contact.personalDetails + PersonalDetail(
                            id        = java.util.UUID.randomUUID().toString(),
                            contactId = contact.id,
                            category  = prefCat,
                            value     = prefValue.trim()
                        )
                    ))
                    showAddPref = false
                }) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = { TextButton(onClick = { showAddPref = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // ── Правка заметки ──
    editingNote?.let { note ->
        var editText by remember(note.id) { mutableStateOf(note.text) }
        var editType by remember(note.id) { mutableStateOf(note.type) }
        var editImportant by remember(note.id) { mutableStateOf(note.isImportant) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text(stringResource(R.string.cd_note_edit), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_note_text)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        maxLines = 5
                    )
                    DropdownField(stringResource(R.string.cd_note_type), editType.label(ctxLabel), NoteType.values().filter { it != NoteType.GIFT }.map { it.label(ctxLabel) }) {
                        editType = NoteType.values().firstOrNull { n -> n.label(ctxLabel) == it } ?: editType
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = editImportant, onCheckedChange = { editImportant = it })
                        Text(stringResource(R.string.cd_note_important), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = editText.isNotBlank(),
                    onClick = {
                        AppStateStore.updateNote(note.copy(
                            text = editText.trim(),
                            type = editType,
                            isImportant = editImportant
                        ))
                        editingNote = null
                    }
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // ── Подтверждение удаления заметки ──
    deletingNote?.let { note ->
        AlertDialog(
            onDismissRequest = { deletingNote = null },
            title = { Text(stringResource(R.string.cd_note_delete_q), fontWeight = FontWeight.Bold) },
            text = { Text(note.text.take(120) + if (note.text.length > 120) "…" else "") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppleTheme.colors.red
                    ),
                    onClick = {
                        AppStateStore.deleteNote(note.id)
                        deletingNote = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingNote = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; noteText = "" },
            title = { Text(stringResource(R.string.cd_add_note), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_note_text)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        maxLines = 5
                    )
                    DropdownField(stringResource(R.string.cd_note_type), noteType.label(ctxLabel), NoteType.values().filter { it != NoteType.GIFT }.map { it.label(ctxLabel) }) {
                        noteType = NoteType.values().firstOrNull { n -> n.label(ctxLabel) == it } ?: NoteType.GENERAL
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = noteIsImportant, onCheckedChange = { noteIsImportant = it })
                        Text(stringResource(R.string.cd_note_important), style = MaterialTheme.typography.bodyMedium)
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
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false; noteText = "" }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // Quick personal detail state
    var pdText     by remember { mutableStateOf("") }
    var pdCategory by remember { mutableStateOf(PersonalDetailCategory.INTERESTS) }

    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false; pdText = "" },
            title = { Text(stringResource(R.string.cd_add_personal_detail), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // Category selector
                    DropdownField(
                        label         = stringResource(R.string.cd_category),
                        selectedValue = pdCategory.label(ctxLabel),
                        options       = PersonalDetailCategory.values().map { it.label(ctxLabel) }
                    ) { selected ->
                        pdCategory = PersonalDetailCategory.values()
                            .firstOrNull { it.label(ctxLabel) == selected }
                            ?: PersonalDetailCategory.INTERESTS
                    }

                    // Value input
                    OutlinedTextField(
                        value         = pdText,
                        onValueChange = { pdText = it },
                        label         = { Text(stringResource(R.string.cd_value)) },
                        placeholder   = {
                            val hint = when (pdCategory) {
                                PersonalDetailCategory.FOOD        -> stringResource(R.string.cd_food_hint)
                                PersonalDetailCategory.DRINKS      -> stringResource(R.string.cd_drink_hint2)
                                PersonalDetailCategory.INTERESTS   -> stringResource(R.string.cd_int_hint)
                                PersonalDetailCategory.HABITS      -> stringResource(R.string.cd_habit_hint)
                                PersonalDetailCategory.ALLERGIES   -> stringResource(R.string.cd_allergy_hint)
                                PersonalDetailCategory.RESTRICTIONS-> stringResource(R.string.cd_vegan_hint)
                                PersonalDetailCategory.LIKES       -> stringResource(R.string.cd_drink_hint)
                                PersonalDetailCategory.DISLIKES    -> stringResource(R.string.cd_late_hint)
                                else                               -> stringResource(R.string.cd_enter_value)
                            }
                            Text(hint, color = AppleTheme.colors.separator)
                        },
                        modifier  = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Size info shortcut
                    if (pdCategory == PersonalDetailCategory.OTHER) {
                        val sizeInfo = contact.sizeInfo
                        if (sizeInfo != null) {
                            Text(
                                stringResource(R.string.cd_sizes_saved) + " " +
                                listOfNotNull(
                                    sizeInfo.clothingSize?.let { stringResource(R.string.cd_clothes).lowercase() + " $it" },
                                    sizeInfo.shoeSize?.let     { stringResource(R.string.cd_shoes).lowercase() + " $it" },
                                    sizeInfo.ringSize?.let     { stringResource(R.string.cd_ring).lowercase() + " $it" }
                                ).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel
                            )
                        }
                    }

                    // Existing details preview
                    val existing = contact.personalDetails
                        .filter { it.category == pdCategory }
                    if (existing.isNotEmpty()) {
                        Text(
                            stringResource(R.string.cd_already_added),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppleTheme.colors.secondaryLabel
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement   = Arrangement.spacedBy(4.dp)
                        ) {
                            existing.forEach { pd ->
                                Surface(
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                    color = AppleTheme.colors.fill
                                ) {
                                    Text(
                                        pd.value,
                                        style    = MaterialTheme.typography.labelSmall,
                                        color    = AppleTheme.colors.label,
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
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showVoiceDialog = false; pdText = "" }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactHeader(contact: Contact, onNavigateToCheatSheet: () -> Unit = {}, onNavigateToCreateCalendarItem: () -> Unit = {}) {
    val ctxLabel = LocalContext.current
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
            val last = java.time.LocalDate.parse(contact.lastContactDate?.take(10))
            java.time.temporal.ChronoUnit.DAYS.between(last, java.time.LocalDate.now())
        } catch (e: Exception) { null }
    } else if (!lastNoteDate.isNullOrBlank()) {
        try {
            val last = java.time.LocalDate.parse(lastNoteDate)
            java.time.temporal.ChronoUnit.DAYS.between(last, java.time.LocalDate.now())
        } catch (e: Exception) { null }
    } else null

    // Nearest upcoming date
    val nearestDate = AppStateStore.calendarItems
        .filter { it.links.any { l -> l.targetId == contact.id } && it.status == CalendarItemStatus.ACTIVE }
        .filter { it.startDate >= java.time.LocalDate.now().toString() }
        .minByOrNull { it.startDate }

    val initials = contact.firstName.take(1) + contact.lastName.take(1)
    val subtitle = listOf(company, position, city).filter { it.isNotEmpty() }.joinToString(" · ")
    val today = java.time.LocalDate.now()
    val weekAgo = today.minusDays(7).toString()
    val rhythmDays: Long? = when (contact.communicationRhythm) {
        CommunicationRhythm.WEEKLY -> 7L
        CommunicationRhythm.MONTHLY -> 30L
        CommunicationRhythm.EVERY_3_MONTHS -> 90L
        CommunicationRhythm.EVERY_6_MONTHS -> 180L
        CommunicationRhythm.YEARLY -> 365L
        else -> null
    }
    val daysSinceLast = contact.lastContactDate?.let {
        try { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it.take(10)), today) } catch (e: Exception) { null }
    }
    val badgeColor = when {
        contact.contactStatus == ContactStatus.ARCHIVED -> null
        rhythmDays != null && daysSinceLast != null && daysSinceLast > rhythmDays -> androidx.compose.ui.graphics.Color(0xFFDC2626)
        contact.createdAt.take(10) >= weekAgo -> androidx.compose.ui.graphics.Color(0xFF7C3AED)
        else -> androidx.compose.ui.graphics.Color(0xFF059669)
    }
    val nowIso = { java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Аватар — коралловый градиент (спека HTML)
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                        androidx.compose.ui.graphics.Color(0xFFFF6B6B),
                        androidx.compose.ui.graphics.Color(0xFFFF3B30)))),
                contentAlignment = Alignment.Center
            ) {
                Text(initials.uppercase(), color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
            }
            if (badgeColor != null) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(badgeColor)
                    .border(3.dp, AppleTheme.colors.groupedBackground, CircleShape))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(name, color = AppleTheme.colors.label, fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
            fontSize = 26.sp, fontWeight = FontWeight.W800, letterSpacing = (-0.01).em,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = AppleTheme.colors.secondaryLabel, fontSize = 15.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp))
        }
        if (!contact.nickname.isNullOrBlank()) {
            Text("«${contact.nickname}»", color = AppleTheme.colors.secondaryLabel, fontSize = 13.sp)
        }

        // Чипы — редактируемые (функция сохранена), вид по макету
        Spacer(Modifier.height(11.dp))
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)
        ) {
            EditableChip(
                current = contact.importanceLevel.label(ctxLabel),
                options = ImportanceLevel.values().map { it.label(ctxLabel) },
                container = AppleTheme.colors.red.copy(alpha = 0.12f), labelColor = AppleTheme.colors.red
            ) { picked -> ImportanceLevel.values().firstOrNull { it.label(ctxLabel) == picked }?.let { AppStateStore.updateContact(contact.copy(importanceLevel = it, updatedAt = nowIso())) } }
            EditableChip(
                current = contact.relationshipType.label(ctxLabel),
                options = RelationshipType.values().map { it.label(ctxLabel) },
                container = AppleTheme.colors.brand.copy(alpha = 0.10f), labelColor = AppleTheme.colors.brand
            ) { picked -> RelationshipType.values().firstOrNull { it.label(ctxLabel) == picked }?.let { AppStateStore.updateContact(contact.copy(relationshipType = it, updatedAt = nowIso())) } }
            EditableChip(
                current = contact.communicationRhythm.label(ctxLabel),
                options = CommunicationRhythm.values().filter { it != CommunicationRhythm.CUSTOM }.map { it.label(ctxLabel) },
                container = AppleTheme.colors.fill, labelColor = AppleTheme.colors.label
            ) { picked -> CommunicationRhythm.values().firstOrNull { it.label(ctxLabel) == picked }?.let { AppStateStore.updateContact(contact.copy(communicationRhythm = it, updatedAt = nowIso())) } }
            EditableChip(
                current = contact.contactStatus.label(ctxLabel),
                options = ContactStatus.values().map { it.label(ctxLabel) },
                container = AppleTheme.colors.green.copy(alpha = 0.14f), labelColor = AppleTheme.colors.green
            ) { picked -> ContactStatus.values().firstOrNull { it.label(ctxLabel) == picked }?.let { AppStateStore.updateContact(contact.copy(contactStatus = it, updatedAt = nowIso())) } }
        }

        // Теги (сохранены)
        if (contact.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                contact.tags.forEach { tag ->
                    Text(tag, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(AppleTheme.colors.fill).padding(horizontal = 9.dp, vertical = 3.dp))
                }
            }
        }

        // Контекст-пилюли (сохранены)
        val hasCtx = daysSince != null || !contact.nextStep.isNullOrBlank() || nearestDate != null
        if (hasCtx) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (daysSince != null) {
                    val lateColor = when { daysSince > 60 -> AppleTheme.colors.red; daysSince > 30 -> AppleTheme.colors.orange; else -> AppleTheme.colors.green }
                    val lbl = when { daysSince == 0L -> stringResource(R.string.cd_last_today); daysSince == 1L -> stringResource(R.string.cd_last_yesterday); else -> stringResource(R.string.cd_last_days_ago, daysSince) }
                    ContextPill(lbl, lateColor, lateColor.copy(alpha = 0.12f))
                }
                if (!contact.nextStep.isNullOrBlank()) {
                    ContextPill("→ " + contact.nextStep, AppleTheme.colors.brand, AppleTheme.colors.brand.copy(alpha = 0.10f), modifier = Modifier.weight(1f), ellipsize = true)
                }
                if (nearestDate != null) {
                    ContextPill(com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(nearestDate.title, nearestDate.type, ctxLabel) + " · " + nearestDate.startDate, AppleTheme.colors.orange, AppleTheme.colors.orange.copy(alpha = 0.12f))
                }
            }
        }

        // Быстрые действия — белые плитки (спека HTML); все функции сохранены
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            ActionTile(Icons.Outlined.Phone, stringResource(R.string.cd_call)) {
                val phone = contact.phones.find { it.isPrimary }?.number ?: contact.phones.firstOrNull()?.number
                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(context, phone)
            }
            ActionTile(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.cd_write)) {
                val m = contact.messengers.find { it.isPrimary } ?: contact.messengers.firstOrNull()
                if (m != null) com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(context, m)
                else com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(context, contact.phones.firstOrNull()?.number)
            }
            ActionTile(Icons.Outlined.Email, stringResource(R.string.cd_email_action)) {
                val email = contact.emails.find { it.isPrimary }?.email ?: contact.emails.firstOrNull()?.email
                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(context, email)
            }
            if (address != null) ActionTile(Icons.Outlined.Map, stringResource(R.string.cd_map)) {
                if (address.latitude != null && address.longitude != null)
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                else
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
            }
            ActionTile(Icons.Outlined.Event, stringResource(R.string.cd_create_event)) { onNavigateToCreateCalendarItem() }
            ActionTile(Icons.Default.Lightbulb, stringResource(R.string.cd_cheatsheet)) { onNavigateToCheatSheet() }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
            .background(AppleTheme.colors.card)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = AppleTheme.colors.brand, modifier = Modifier.size(22.dp))
        Text(label, color = AppleTheme.colors.secondaryLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun QuickActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(AppleTheme.colors.card).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = cd, tint = AppleTheme.colors.brand)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(13.dp))
                .background(if (accent) AppleTheme.colors.brand else AppleTheme.colors.card),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = if (accent) Color.White else AppleTheme.colors.brand, modifier = Modifier.size(21.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel, maxLines = 1)
    }
}

@Composable
private fun ContextPill(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    ellipsize: Boolean = false
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = if (ellipsize) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
internal fun ActionSquare(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AppleTheme.colors.card).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = cd, tint = AppleTheme.colors.brand, modifier = Modifier.size(18.dp))
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
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
internal fun GiftMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.cd_actions), Modifier.size(16.dp),
                tint = AppleTheme.colors.secondaryLabel)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.cd_edit_short)) },
                leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = AppleTheme.colors.red) },
                leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp),
                    tint = AppleTheme.colors.red) },
                onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun EditableChip(
    current: String,
    options: List<String>,
    container: androidx.compose.ui.graphics.Color? = null,
    labelColor: androidx.compose.ui.graphics.Color? = null,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current, fontSize = 10.sp) },
            colors = if (container != null)
                androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = container, labelColor = labelColor ?: AppleTheme.colors.label, trailingIconContentColor = labelColor ?: AppleTheme.colors.label)
            else androidx.compose.material3.AssistChipDefaults.assistChipColors()
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(AppleTheme.colors.card)
        ) {
            options.forEach { opt ->
                val selected = opt == current
                DropdownMenuItem(
                    text = { Text(opt, color = if (selected) AppleTheme.colors.brand else AppleTheme.colors.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand) } } else null,
                    onClick = { open = false; onPick(opt) }
                )
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).let { 
        if (onClick != null) it.clickable { onClick() } else it 
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppleTheme.colors.secondaryLabel, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SizeChip(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}
