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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// Приглушённое золото подписей — темозависимый токен AppleTheme.colors.goldLabel.

fun androidx.compose.foundation.lazy.LazyListScope.overviewTab(
    contact: Contact,
    onNavigateToCreateCalendarItem: () -> Unit,
    onNavigateToCalendarItem: (String) -> Unit = {},
    onNavigateToContact: (String) -> Unit = {},
    onNavigateToCompany: (String) -> Unit = {}
, ctxLabel: android.content.Context, editing: Boolean = false, onEditingChange: (Boolean) -> Unit = {}, onDelete: () -> Unit = {}) {
    // Кнопка «Изменить»/«Готово» этой вкладки убрана — режим правки теперь
    // включается ОДНОЙ кнопкой в шапке карточки контакта (см. ContactDetailScreen.kt),
    // общей на все вкладки с инлайн-редактированием.

    // ── Следующий шаг — золотая карточка-акцент (спека Aurelia) ──
    val nextStepText = contact.nextStep?.takeIf { it.isNotBlank() }
    if (nextStepText != null) {
        item {
            val gold = AppleTheme.colors.orange
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(gold.copy(alpha = 0.16f), gold.copy(alpha = 0.07f))
                        )
                    )
                    .border(1.dp, gold.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column {
                    Text(
                        stringResource(R.string.ce_next_step).uppercase(),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.14.em, color = gold
                    )
                    Text(
                        nextStepText,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp, color = AppleTheme.colors.label,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }
        }
    }

    // ── Краткий контекст ────────────────────────────────────
    val impNotes = AppStateStore.notes.filter {
        it.contactId == contact.id && it.type == NoteType.IMPORTANT_TO_REMEMBER
    }
    if (impNotes.isNotEmpty()) {
        item {
            CardBlock(title = stringResource(R.string.cd_remember)) {
                impNotes.forEach { note ->
                    Text("• ${note.text}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    // ── «ДОСЬЕ · FORD» — заголовок группы (по макету Aurelia; сами блоки
    // оставлены в полную длину, а не сжаты в сетку 2×2 — по решению владельца).
    // Без своего padding: родительский LazyColumn уже даёт spacedBy(12dp) между
    // ВСЕМИ item — добавление собственных отступов здесь удваивало зазор.
    item {
        Text(
            stringResource(R.string.cd_dossier).uppercase(),
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp, color = AppleTheme.colors.goldLabel
        )
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
        var showAddFamily by remember { mutableStateOf(false) }
        var famSearch     by remember { mutableStateOf("") }
        var famSelected   by remember { mutableStateOf<Contact?>(null) }
        var famOtherRole  by remember { mutableStateOf("Жена") }
        var famMyRole     by remember { mutableStateOf("Муж") }
        var famNote       by remember { mutableStateOf("") }
        val famRoles = listOf("Жена", "Муж", "Партнёр", "Мать", "Отец", "Сын", "Дочь", "Брат", "Сестра", "Родственник")
        var pendingRemoveFamily by remember { mutableStateOf<ContactRelation?>(null) }
        pendingRemoveFamily?.let { rel ->
            AlertDialog(
                onDismissRequest = { pendingRemoveFamily = null },
                title = { Text(stringResource(R.string.cd_remove_family_title), fontWeight = FontWeight.Bold) },
                confirmButton = {
                    Button(onClick = {
                        AppStateStore.removeContactRelation(rel.id)
                        pendingRemoveFamily = null
                    }) { Text(stringResource(R.string.cd_remove)) }
                },
                dismissButton = { TextButton(onClick = { pendingRemoveFamily = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }

        FordBlock(
            letter     = "F",
            title      = stringResource(R.string.cd_family),
            color      = androidx.compose.ui.graphics.Color(0xFFC45D34), // Aurelia: терракот
            isEmpty    = familyRelations.isEmpty(),
            editing = editing
        ) {
            // Кликабельные контакты с ролями
            if (familyRelations.isNotEmpty()) {
                familyRelations.forEach { rel ->
                    val isFirst   = rel.firstContactId == contact.id
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    // Роль ДРУГОГО человека (например, «Жена» рядом с именем жены)
                    val role      = if (isFirst) rel.secondRole else rel.firstRole
                    val other     = AppStateStore.getContact(otherId)
                    val otherName = other?.let { "${it.firstName} ${it.lastName}".trim() }
                        ?: stringResource(R.string.cd_deleted)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = other != null) { onNavigateToContact(otherId) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Аватар
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (other?.firstName?.firstOrNull()?.toString() ?: "?") +
                                    (other?.lastName?.firstOrNull()?.toString() ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleTheme.colors.brand
                                )
                            }
                            Column {
                                Text(
                                    otherName,
                                    style      = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (!role.isNullOrBlank())
                                    Text(
                                        role,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppleTheme.colors.brand
                                    )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (editing) {
                                IconButton(onClick = { pendingRemoveFamily = rel }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, stringResource(R.string.cd_remove_family),
                                        Modifier.size(16.dp), tint = AppleTheme.colors.secondaryLabel)
                                }
                            } else if (other != null) {
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp),
                                    tint = AppleTheme.colors.separator)
                            }
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showAddFamily = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_family))
                }
            } else {
                TextButton(onClick = { showAddFamily = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_family))
                }
            }
        }
        if (showAddFamily) {
            val candidates = AppStateStore.contacts.filter {
                it.id != contact.id &&
                "${it.firstName} ${it.lastName}".contains(famSearch, ignoreCase = true)
            }
            AlertDialog(
                onDismissRequest = { showAddFamily = false; famSelected = null; famSearch = ""; famNote = "" },
                title = { Text(stringResource(R.string.cd_add_family), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val sel = famSelected
                        if (sel == null) {
                            OutlinedTextField(
                                value = famSearch, onValueChange = { famSearch = it },
                                label = { Text(stringResource(R.string.ce_search_contact)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            candidates.take(8).forEach { c ->
                                Text(
                                    "${c.firstName} ${c.lastName}".trim(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { famSelected = c }
                                        .padding(vertical = 8.dp)
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${sel.firstName} ${sel.lastName}".trim(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { famSelected = null }) { Text(stringResource(R.string.ce_change)) }
                            }
                            DropdownField(stringResource(R.string.ce_who_relation), famOtherRole, famRoles) { v -> famOtherRole = v }
                            DropdownField(stringResource(R.string.ce_who_am_i), famMyRole, famRoles) { v -> famMyRole = v }
                            OutlinedTextField(
                                value = famNote, onValueChange = { famNote = it }, keyboardOptions = CapSentences,
                                label = { Text(stringResource(R.string.cd_fact_note_optional)) },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(enabled = famSelected != null, onClick = {
                        famSelected?.let { other ->
                            AppStateStore.addContactRelation(ContactRelation(
                                id = java.util.UUID.randomUUID().toString(),
                                firstContactId = contact.id,
                                secondContactId = other.id,
                                firstRole = famMyRole,
                                secondRole = famOtherRole
                            ))
                            if (famNote.isNotBlank()) {
                                val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                AppStateStore.addNote(Note(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contactId = contact.id,
                                    companyId = null, calendarItemId = null, giftId = null,
                                    type = NoteType.GENERAL,
                                    text = famNote.trim(),
                                    date = java.time.LocalDate.now().toString(),
                                    isImportant = false,
                                    createdAt = now, updatedAt = now
                                ))
                            }
                        }
                        famSelected = null; famSearch = ""; famNote = ""; showAddFamily = false
                    }) { Text(stringResource(R.string.common_add)) }
                },
                dismissButton = { TextButton(onClick = { showAddFamily = false; famSelected = null; famSearch = ""; famNote = "" }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
    }

    // ── O — OCCUPATION / Работа ─────────────────────────────
    item {
        val compRel = contact.companyRelations.firstOrNull { it.isPrimary }
            ?: contact.companyRelations.firstOrNull()
        val hasWork = compRel != null
        var showAddCompany by remember { mutableStateOf(false) }
        var compSearch   by remember { mutableStateOf("") }
        var compSelected by remember { mutableStateOf<Company?>(null) }
        var compPosition by remember { mutableStateOf("") }
        var compNote     by remember { mutableStateOf("") }
        var pendingRemoveCompany by remember { mutableStateOf<ContactCompanyRelation?>(null) }
        pendingRemoveCompany?.let { rel ->
            AlertDialog(
                onDismissRequest = { pendingRemoveCompany = null },
                title = { Text(stringResource(R.string.cd_remove_company_title), fontWeight = FontWeight.Bold) },
                confirmButton = {
                    Button(onClick = {
                        AppStateStore.updateContact(contact.copy(
                            companyRelations = contact.companyRelations.filter { it.id != rel.id }
                        ))
                        pendingRemoveCompany = null
                    }) { Text(stringResource(R.string.cd_remove)) }
                },
                dismissButton = { TextButton(onClick = { pendingRemoveCompany = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }

        FordBlock(
            letter  = "O",
            title   = stringResource(R.string.cd_tab_work),
            color   = androidx.compose.ui.graphics.Color(0xFF3E7E7A), // Aurelia: тил
            isEmpty = !hasWork,
            editing = editing
        ) {
            if (compRel != null) {
                val companyName = AppStateStore.getCompany(compRel.companyId)?.name ?: ""
                if (companyName.isNotBlank()) InfoRow(stringResource(R.string.cd_company), "$companyName ›",
                    onClick = { onNavigateToCompany(compRel.companyId) })
                if (!compRel.position.isNullOrBlank())       InfoRow(stringResource(R.string.cd_position),   compRel.position)
                if (!compRel.department.isNullOrBlank())     InfoRow(stringResource(R.string.cd_department),        compRel.department)
                if (!compRel.responsibilities.isNullOrBlank()) InfoRow(stringResource(R.string.cd_tasks),     compRel.responsibilities)
                if (!compRel.workNote.isNullOrBlank())       InfoRow(stringResource(R.string.cd_note),      compRel.workNote)
                // Work notes
                val workNotes = AppStateStore.notes.filter {
                    it.contactId == contact.id && it.type == NoteType.WORK
                }
                workNotes.forEach { n ->
                    Text("• ${n.text}", style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showAddCompany = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_company))
                }
                if (editing) TextButton(onClick = { pendingRemoveCompany = compRel }) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_remove_company))
                }
            } else {
                TextButton(onClick = { showAddCompany = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_company))
                }
            }
        }
        if (showAddCompany) {
            val comps = AppStateStore.companies.filter {
                it.name.contains(compSearch, ignoreCase = true)
            }
            AlertDialog(
                onDismissRequest = { showAddCompany = false; compSelected = null; compSearch = ""; compPosition = ""; compNote = "" },
                title = { Text(stringResource(R.string.cd_add_company), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val sel = compSelected
                        if (sel == null) {
                            OutlinedTextField(
                                value = compSearch, onValueChange = { compSearch = it },
                                label = { Text(stringResource(R.string.ce_search_company)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            comps.take(8).forEach { c ->
                                Text(
                                    c.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable { compSelected = c }.padding(vertical = 8.dp)
                                )
                            }
                            if (comps.isEmpty()) {
                                Text(stringResource(R.string.cd_company_none_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppleTheme.colors.secondaryLabel)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(sel.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { compSelected = null }) { Text(stringResource(R.string.ce_change)) }
                            }
                            OutlinedTextField(
                                value = compPosition, onValueChange = { compPosition = it }, keyboardOptions = CapSentences,
                                label = { Text(stringResource(R.string.cd_position)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            OutlinedTextField(
                                value = compNote, onValueChange = { compNote = it }, keyboardOptions = CapSentences,
                                label = { Text(stringResource(R.string.cd_fact_note_optional)) },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(enabled = compSelected != null, onClick = {
                        compSelected?.let { comp ->
                            AppStateStore.updateContact(contact.copy(
                                companyRelations = contact.companyRelations + ContactCompanyRelation(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contactId = contact.id,
                                    companyId = comp.id,
                                    position = compPosition.ifBlank { null },
                                    employmentStatus = EmploymentStatus.CURRENT,
                                    isPrimary = contact.companyRelations.isEmpty()
                                )
                            ))
                            if (compNote.isNotBlank()) {
                                val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                AppStateStore.addNote(Note(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contactId = contact.id,
                                    companyId = null, calendarItemId = null, giftId = null,
                                    type = NoteType.WORK,
                                    text = compNote.trim(),
                                    date = java.time.LocalDate.now().toString(),
                                    isImportant = false,
                                    createdAt = now, updatedAt = now
                                ))
                            }
                        }
                        compSelected = null; compSearch = ""; compPosition = ""; compNote = ""; showAddCompany = false
                    }) { Text(stringResource(R.string.common_add)) }
                },
                dismissButton = { TextButton(onClick = { showAddCompany = false; compSelected = null; compSearch = ""; compPosition = ""; compNote = "" }) { Text(stringResource(R.string.common_cancel)) } }
            )
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
        var showAddInterest by remember { mutableStateOf(false) }
        var newInterestCat  by remember { mutableStateOf(PersonalDetailCategory.INTERESTS) }
        var newInterestVal  by remember { mutableStateOf("") }
        val ctxInt = androidx.compose.ui.platform.LocalContext.current
        var pendingDeleteInterest by remember { mutableStateOf<PersonalDetail?>(null) }
        pendingDeleteInterest?.let { del ->
            AlertDialog(
                onDismissRequest = { pendingDeleteInterest = null },
                title = { Text(stringResource(R.string.cd_delete_interest_title), fontWeight = FontWeight.Bold) },
                text  = { Text(del.value) },
                confirmButton = {
                    Button(onClick = {
                        AppStateStore.updateContact(contact.copy(
                            personalDetails = contact.personalDetails.filter { it.id != del.id }
                        ))
                        pendingDeleteInterest = null
                    }) { Text(stringResource(R.string.common_delete)) }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteInterest = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }

        FordBlock(
            letter  = "R",
            title   = stringResource(R.string.cd_interests_habits),
            color   = androidx.compose.ui.graphics.Color(0xFF2E8B6B), // Aurelia: малахит
            isEmpty = interests.isEmpty(),
            editing = editing
        ) {
            if (interests.isNotEmpty()) {
                // Group by category
                interests.groupBy { it.category }.forEach { (cat, items) ->
                    Text(
                        cat.label(ctxLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.brand,
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
                                color  = AppleTheme.colors.fill,
                                modifier = if (editing) Modifier.clickable { pendingDeleteInterest = detail } else Modifier
                            ) {
                                Text(
                                    detail.value,
                                    style    = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color    = AppleTheme.colors.label
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showAddInterest = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_interest))
                }
            } else {
                TextButton(onClick = { showAddInterest = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_interest))
                }
            }
        }
        if (showAddInterest) {
            AlertDialog(
                onDismissRequest = { showAddInterest = false; newInterestVal = "" },
                title = { Text(stringResource(R.string.cd_add_interest), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                PersonalDetailCategory.INTERESTS, PersonalDetailCategory.HABITS,
                                PersonalDetailCategory.FOOD, PersonalDetailCategory.DRINKS,
                                PersonalDetailCategory.BRANDS
                            ).forEach { c ->
                                FilterChip(
                                    selected = newInterestCat == c,
                                    onClick  = { newInterestCat = c },
                                    label    = { Text(c.label(ctxInt)) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = newInterestVal, onValueChange = { newInterestVal = it }, keyboardOptions = CapSentences,
                            label = { Text(stringResource(R.string.cd_value)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(enabled = newInterestVal.isNotBlank(), onClick = {
                        AppStateStore.updateContact(contact.copy(
                            personalDetails = contact.personalDetails + PersonalDetail(
                                id        = java.util.UUID.randomUUID().toString(),
                                contactId = contact.id,
                                category  = newInterestCat,
                                value     = newInterestVal.trim()
                            )
                        ))
                        newInterestVal = ""; showAddInterest = false
                    }) { Text(stringResource(R.string.common_add)) }
                },
                dismissButton = { TextButton(onClick = { showAddInterest = false; newInterestVal = "" }) { Text(stringResource(R.string.common_cancel)) } }
            )
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
        var showAddDream by remember { mutableStateOf(false) }
        var newDreamVal  by remember { mutableStateOf("") }
        var pendingDeleteDream by remember { mutableStateOf<Note?>(null) }
        pendingDeleteDream?.let { note ->
            AlertDialog(
                onDismissRequest = { pendingDeleteDream = null },
                title = { Text(stringResource(R.string.cd_delete_dream_title), fontWeight = FontWeight.Bold) },
                text  = { Text(note.text) },
                confirmButton = {
                    Button(onClick = { AppStateStore.deleteNote(note.id); pendingDeleteDream = null }) {
                        Text(stringResource(R.string.common_delete))
                    }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteDream = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }

        FordBlock(
            letter  = "D",
            title   = stringResource(R.string.cd_goals_dreams),
            color   = androidx.compose.ui.graphics.Color(0xFFB68A36), // Aurelia: золото
            isEmpty = dreamNotes.isEmpty() && restrictions.isEmpty(),
            editing = editing
        ) {
            if (dreamNotes.isNotEmpty()) {
                dreamNotes.forEach { note ->
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (editing) Modifier.clickable { pendingDeleteDream = note } else Modifier)
                            .padding(vertical = 3.dp),
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
                    stringResource(R.string.cd_restrictions),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppleTheme.colors.red,
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
                            "${r.category.label(ctxLabel)}: ${r.value}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppleTheme.colors.red
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showAddDream = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.cd_add_dream))
            }
        }
        if (showAddDream) {
            AlertDialog(
                onDismissRequest = { showAddDream = false; newDreamVal = "" },
                title = { Text(stringResource(R.string.cd_add_dream), fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = newDreamVal, onValueChange = { newDreamVal = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_goals_dreams)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        maxLines = 4
                    )
                },
                confirmButton = {
                    Button(enabled = newDreamVal.isNotBlank(), onClick = {
                        val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        AppStateStore.addNote(
                            Note(
                                id = java.util.UUID.randomUUID().toString(),
                                contactId = contact.id,
                                companyId = null, calendarItemId = null, giftId = null,
                                type = NoteType.PERSONAL_DETAIL,
                                text = newDreamVal.trim(),
                                date = java.time.LocalDate.now().toString(),
                                isImportant = false,
                                createdAt = now, updatedAt = now
                            )
                        )
                        newDreamVal = ""; showAddDream = false
                    }) { Text(stringResource(R.string.common_add)) }
                },
                dismissButton = { TextButton(onClick = { showAddDream = false; newDreamVal = "" }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
    }

    // ── Статус и классификация (из вкладки Детали) ───────────
    item {
        CardBlock(title = stringResource(R.string.cd_status_class)) {
            InfoRow(stringResource(R.string.common_status),          contact.contactStatus.label(ctxLabel))
            InfoRow(stringResource(R.string.filter_relation),   contact.relationshipType.label(ctxLabel))
            InfoRow(stringResource(R.string.filter_connection),   contact.connectionLevel.label(ctxLabel))
            InfoRow(stringResource(R.string.filter_importance),        contact.importanceLevel.label(ctxLabel))
            InfoRow(stringResource(R.string.cd_social_role), contact.socialRole.label(ctxLabel))
            InfoRow(stringResource(R.string.filter_rhythm),    contact.communicationRhythm.label(ctxLabel))
        }
    }

    // ── Теги ─────────────────────────────────────────────────
    item {
        if (contact.tags.isNotEmpty()) {
            CardBlock(title = stringResource(R.string.cd_tags)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(6.dp)
                ) {
                    contact.tags.forEach { tag ->
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = AppleTheme.colors.brand.copy(alpha = 0.10f)
                        ) {
                            Text(
                                tag,
                                style    = MaterialTheme.typography.labelMedium,
                                color    = AppleTheme.colors.brand,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Где познакомились
    item {
        if (!contact.meetContext.isNullOrBlank() || !contact.meetDate.isNullOrBlank()) {
            CardBlock(title = stringResource(R.string.cd_where_met)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.Top
                ) {
                    Icon(Icons.Default.Handshake, null, Modifier.size(16.dp),
                        tint = AppleTheme.colors.brand)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (!contact.meetContext.isNullOrBlank())
                            Text(contact.meetContext, style = MaterialTheme.typography.bodyMedium)
                        if (!contact.meetDate.isNullOrBlank())
                            Text(contact.meetDate, style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel)
                    }
                }
            }
        }
    }

    // ── Связанные люди (несемейные — семья показана в блоке FORD) ──
    item {
        val familyRoles = setOf("Муж", "Жена", "Партнёр", "Отец", "Мать",
            "Сын", "Дочь", "Брат", "Сестра", "Родственник")
        val relations = AppStateStore.contactRelations.filter { rel ->
            val mine = rel.firstContactId == contact.id || rel.secondContactId == contact.id
            if (!mine) false else {
                val famRole = if (rel.firstContactId == contact.id) rel.firstRole else rel.secondRole
                famRole !in familyRoles
            }
        }
        var showAddRelated by remember { mutableStateOf(false) }
        var relSearch     by remember { mutableStateOf("") }
        var relSelected   by remember { mutableStateOf<Contact?>(null) }
        val relRoles = listOf("Друг", "Подруга", "Коллега", "Знакомый", "Сосед",
            "Одноклассник", "Однокурсник", "Партнёр по бизнесу", "Наставник", "Клиент")
        var relOtherRole by remember { mutableStateOf("Друг") }
        var relMyRole    by remember { mutableStateOf("Друг") }
        var pendingRemoveRelated by remember { mutableStateOf<ContactRelation?>(null) }
        pendingRemoveRelated?.let { rel ->
            AlertDialog(
                onDismissRequest = { pendingRemoveRelated = null },
                title = { Text(stringResource(R.string.cd_related_people), fontWeight = FontWeight.Bold) },
                confirmButton = {
                    Button(onClick = {
                        AppStateStore.removeContactRelation(rel.id)
                        pendingRemoveRelated = null
                    }) { Text(stringResource(R.string.cd_remove)) }
                },
                dismissButton = { TextButton(onClick = { pendingRemoveRelated = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
        if (relations.isNotEmpty() || editing) {
            CardBlock(title = stringResource(R.string.cd_related_people)) {

                relations.forEach { rel ->
                    val isFirst   = rel.firstContactId == contact.id
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    // Роль ДРУГОГО человека
                    val theirRole = if (isFirst) rel.secondRole else rel.firstRole
                    val other     = AppStateStore.getContact(otherId)
                    val otherName = other?.let { "${it.firstName} ${it.lastName}".trim() } ?: stringResource(R.string.common_unknown)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = other != null) { onNavigateToContact(otherId) }
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
                                    .background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (other?.firstName?.firstOrNull()?.toString() ?: "?") +
                                    (other?.lastName?.firstOrNull()?.toString() ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleTheme.colors.brand
                                )
                            }
                            Column {
                                Text(otherName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (!theirRole.isNullOrBlank())
                                    Text(theirRole, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.brand)
                            }
                        }
                        if (editing) {
                            IconButton(onClick = { pendingRemoveRelated = rel }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, stringResource(R.string.cd_remove),
                                    Modifier.size(16.dp), tint = AppleTheme.colors.secondaryLabel)
                            }
                        } else {
                            Icon(Icons.Default.ChevronRight, null, tint = AppleTheme.colors.separator)
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                if (editing) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showAddRelated = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_add))
                    }
                }
            }
        }
        if (showAddRelated) {
            val candidates = AppStateStore.contacts.filter {
                it.id != contact.id &&
                "${it.firstName} ${it.lastName}".contains(relSearch, ignoreCase = true)
            }
            AlertDialog(
                onDismissRequest = { showAddRelated = false; relSelected = null; relSearch = "" },
                title = { Text(stringResource(R.string.cd_related_people), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val sel = relSelected
                        if (sel == null) {
                            OutlinedTextField(
                                value = relSearch, onValueChange = { relSearch = it },
                                label = { Text(stringResource(R.string.ce_search_contact)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            candidates.take(8).forEach { c ->
                                Text(
                                    "${c.firstName} ${c.lastName}".trim(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { relSelected = c }
                                        .padding(vertical = 8.dp)
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${sel.firstName} ${sel.lastName}".trim(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { relSelected = null }) { Text(stringResource(R.string.ce_change)) }
                            }
                            DropdownField(stringResource(R.string.ce_who_relation), relOtherRole, relRoles) { v -> relOtherRole = v }
                            DropdownField(stringResource(R.string.ce_who_am_i), relMyRole, relRoles) { v -> relMyRole = v }
                        }
                    }
                },
                confirmButton = {
                    Button(enabled = relSelected != null, onClick = {
                        relSelected?.let { other ->
                            AppStateStore.addContactRelation(ContactRelation(
                                id = java.util.UUID.randomUUID().toString(),
                                firstContactId = contact.id,
                                secondContactId = other.id,
                                firstRole = relMyRole,
                                secondRole = relOtherRole
                            ))
                        }
                        relSelected = null; relSearch = ""; showAddRelated = false
                    }) { Text(stringResource(R.string.common_add)) }
                },
                dismissButton = { TextButton(onClick = { showAddRelated = false; relSelected = null; relSearch = "" }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
    }

    // Удалить контакт — красная строка внизу (спека HTML)
    item {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(AppleTheme.colors.card).clickable { onDelete() }.padding(13.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = AppleTheme.colors.red, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.cd_delete_contact), color = AppleTheme.colors.red, fontWeight = FontWeight.Medium)
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
    editing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(
            containerColor = AppleTheme.colors.card
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
            }
            HorizontalDivider(
                color     = AppleTheme.colors.separator,
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(10.dp))
            content()
            if (isEmpty && !editing) FordEmptyHint(stringResource(R.string.cd_ford_empty_hint))
        }
    }
}

@Composable
private fun FordEmptyHint(text: String) {
    // Раньше был AppleTheme.colors.separator — это цвет волосяных линий (~8%
    // альфа), как текст подсказки он был практически невидим.
    Text(
        text,
        style    = MaterialTheme.typography.bodySmall,
        color    = AppleTheme.colors.secondaryLabel
    )
}
