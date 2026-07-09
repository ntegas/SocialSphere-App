@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    // ── Следующий шаг — золотая карточка-акцент (спека Aurelia).
    // В режиме правки редактируется тапом; пустой — виден ТОЛЬКО в правке
    // (принцип: просмотр показывает заполненное, правка открывает все поля).
    val nextStepText = contact.nextStep?.takeIf { it.isNotBlank() }
    if (nextStepText != null || editing) {
        item {
            val gold = AppleTheme.colors.orange
            var showNextStepDialog by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(gold.copy(alpha = 0.16f), gold.copy(alpha = 0.07f))
                        )
                    )
                    .border(1.dp, gold.copy(alpha = 0.22f), com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18)
                    .then(if (editing) Modifier.clickable { showNextStepDialog = true } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column {
                    Text(
                        stringResource(R.string.ce_next_step).uppercase(),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.14.em, color = gold
                    )
                    Text(
                        nextStepText ?: stringResource(R.string.cd_next_step_hint),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                        color = if (nextStepText != null) AppleTheme.colors.label
                                else AppleTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }
            if (showNextStepDialog) {
                var draft by remember { mutableStateOf(contact.nextStep ?: "") }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                    title = stringResource(R.string.ce_next_step),
                    onDismiss = { showNextStepDialog = false },
                    confirmText = stringResource(R.string.common_save),
                    onConfirm = {
                        AppStateStore.updateContact(contact.copy(nextStep = draft.trim().ifBlank { null }))
                        showNextStepDialog = false
                    },
                    secondaryText = stringResource(R.string.common_cancel),
                    onSecondary = { showNextStepDialog = false }
                ) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, keyboardOptions = CapSentences,
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4
                    )
                }
            }
        }
    }

    // ── Ближайшие события контакта (фидбэк владельца 2026-07-02: событие,
    // созданное из карточки, было невозможно открыть/править из карточки —
    // блока не существовало; редактирование было только через Календарь) ──
    val upcomingItems = AppStateStore.calendarItems
        .filter { ev ->
            ev.status == CalendarItemStatus.ACTIVE &&
            ev.links.any { it.targetId == contact.id } &&
            ev.effectiveDate() >= java.time.LocalDate.now().toString()
        }
        .sortedBy { it.effectiveDate() }
        .take(5)
    if (upcomingItems.isNotEmpty()) {
        item {
            CardBlock(title = stringResource(R.string.home_upcoming)) {
                upcomingItems.forEachIndexed { i, ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToCalendarItem(ev.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(eventTypeColor(ev.type)))
                        Text(
                            com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle(ev.title, ev.type, ctxLabel),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = AppleTheme.colors.label,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // «Сегодня» / «завтра» / «через N дн.» + дата (фидбэк
                        // 2026-07-04: по одной дате не понять, насколько скоро)
                        val evDate = try { java.time.LocalDate.parse(ev.effectiveDate()) } catch (e: Exception) { null }
                        val daysUntil = evDate?.let {
                            java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), it)
                        }
                        val relLabel = when (daysUntil) {
                            null -> null
                            0L   -> stringResource(R.string.common_today)
                            1L   -> stringResource(R.string.common_tomorrow)
                            else -> String.format(stringResource(R.string.home_in_days), daysUntil)
                        }
                        val niceDate = evDate?.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))
                            ?: ev.effectiveDate()
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.End) {
                            Text(
                                relLabel ?: niceDate,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = when (daysUntil) {
                                    0L, 1L -> AppleTheme.colors.brand
                                    else   -> AppleTheme.colors.secondaryLabel
                                }
                            )
                            if (relLabel != null) Text(
                                niceDate,
                                fontSize = 11.sp,
                                color = AppleTheme.colors.tertiaryLabel
                            )
                        }
                    }
                    if (i < upcomingItems.lastIndex)
                        HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
            }
        }
    }

    // ── Краткий контекст ────────────────────────────────────
    val impNotes = AppStateStore.notes.filter {
        it.contactId == contact.id && it.type == NoteType.IMPORTANT_TO_REMEMBER
    }
    // В режиме правки блок виден и пустым — иначе «Важно помнить» негде было
    // завести (фидбэк владельца: в правке должны выходить ВСЕ поля).
    if (impNotes.isNotEmpty() || editing) {
        item {
            var showAddImp by remember { mutableStateOf(false) }
            var newImpVal  by remember { mutableStateOf("") }
            CardBlock(title = stringResource(R.string.cd_remember)) {
                impNotes.forEach { note ->
                    Text("• ${note.text}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
                if (editing) TextButton(onClick = { showAddImp = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_add))
                }
            }
            if (showAddImp) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                    onDismiss = { showAddImp = false; newImpVal = "" },
                    title = stringResource(R.string.cd_remember),
                    confirmText = stringResource(R.string.common_add),
                    confirmEnabled = newImpVal.isNotBlank(),
                    onConfirm = {
                        val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        AppStateStore.addNote(Note(
                            id = java.util.UUID.randomUUID().toString(),
                            contactId = contact.id,
                            companyId = null, calendarItemId = null, giftId = null,
                            type = NoteType.IMPORTANT_TO_REMEMBER,
                            text = newImpVal.trim(),
                            date = java.time.LocalDate.now().toString(),
                            isImportant = false,
                            createdAt = now, updatedAt = now
                        ))
                        newImpVal = ""; showAddImp = false
                    },
                    secondaryText = stringResource(R.string.common_cancel),
                    onSecondary = { showAddImp = false; newImpVal = "" }
                ) {
                    OutlinedTextField(
                        value = newImpVal, onValueChange = { newImpVal = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.cd_note_text)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        maxLines = 4
                    )
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
                // Кнопка добавления — только в режиме правки (карандаш в шапке);
                // в просмотре вкладка чистая (решение владельца 2026-07-02)
                if (editing) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showAddFamily = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_family))
                    }
                }
            } else {
                if (editing) TextButton(onClick = { showAddFamily = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_family))
                }
            }

            // Семья без карточек контактов («сын Петя, телефона нет») — свободный
            // текст под списком. Просмотр: только если заполнен; правка: поле ввода,
            // сохранение при выходе из правки / уходе с экрана.
            if (editing) {
                var familyNoteDraft by remember(contact.id) { mutableStateOf(contact.familyNote ?: "") }
                fun commitFamilyNote() {
                    val trimmed = familyNoteDraft.trim().ifBlank { null }
                    val current = AppStateStore.getContact(contact.id) ?: return
                    if (current.familyNote != trimmed)
                        AppStateStore.updateContact(current.copy(familyNote = trimmed))
                }
                DisposableEffect(Unit) { onDispose { commitFamilyNote() } }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = familyNoteDraft,
                    onValueChange = { familyNoteDraft = it },
                    keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_family_note)) },
                    placeholder = { Text(stringResource(R.string.cd_family_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4
                )
            } else if (!contact.familyNote.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    contact.familyNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTheme.colors.secondaryLabel
                )
            }
        }
        if (showAddFamily) {
            // Шаг 1: канонический пикер (шторка + поиск + аватары) — единый
            // дизайн для всех «добавить человека» вместо старых AlertDialog
            if (famSelected == null) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                    title = stringResource(R.string.cd_add_family),
                    items = AppStateStore.contacts
                        .filter { it.id != contact.id }
                        .map { c ->
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                                id = c.id,
                                title = "${c.firstName} ${c.lastName}".trim(),
                                subtitle = c.customRelationshipType?.takeIf { it.isNotBlank() }
                                    ?: c.relationshipType.label(ctxLabel)
                            )
                        },
                    onPick = { picked -> famSelected = AppStateStore.getContact(picked.id) },
                    onDismiss = { showAddFamily = false; famSearch = ""; famNote = "" },
                    searchPlaceholder = stringResource(R.string.ce_search_contact),
                    emptyText = stringResource(R.string.compd_no_candidates)
                )
            } else famSelected?.let { sel ->
                // Шаг 2: роли + необязательная заметка — шторка нового дизайна
                // (был AlertDialog — «старый дизайн» из фидбэка владельца)
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(
                    onDismiss = { showAddFamily = false; famSelected = null; famNote = "" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatar(
                                sel.id, "${sel.firstName} ${sel.lastName}".trim(), size = 38.dp, fontSize = 14.sp)
                            Text(
                                "${sel.firstName} ${sel.lastName}".trim(),
                                fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                                fontSize = 20.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.label
                            )
                        }
                        DropdownField(stringResource(R.string.ce_who_relation), famOtherRole, famRoles) { v -> famOtherRole = v }
                        DropdownField(stringResource(R.string.ce_who_am_i), famMyRole, famRoles) { v -> famMyRole = v }
                        OutlinedTextField(
                            value = famNote, onValueChange = { famNote = it }, keyboardOptions = CapSentences,
                            label = { Text(stringResource(R.string.cd_fact_note_optional)) },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3
                        )
                        Button(
                            onClick = {
                                AppStateStore.addContactRelation(ContactRelation(
                                    id = java.util.UUID.randomUUID().toString(),
                                    firstContactId = contact.id,
                                    secondContactId = sel.id,
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
                                famSelected = null; famSearch = ""; famNote = ""; showAddFamily = false
                            },
                            shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text(stringResource(R.string.common_add), fontWeight = FontWeight.Bold) }
                        Text(
                            stringResource(R.string.common_back),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                .clickable { famSelected = null }
                                .padding(6.dp)
                        )
                    }
                }
            }
        }
    }

    // ── O — OCCUPATION / Работа ─────────────────────────────
    item {
        val hasWork = contact.companyRelations.isNotEmpty()
        // ФИКС (аудит 2026-07-06): профессия «без компании» (v12) была видна
        // только на вкладке Работа — именно тот случай («электрик без
        // компании»), ради которого поле и заводили, не показывался в Обзоре,
        // где его ищут в первую очередь. ProfessionRow — общий компонент с
        // WorkTab (WorkplaceComponents.kt), не дубль.
        val hasProfession = !contact.profession.isNullOrBlank()
        var showAddCompany by remember { mutableStateOf(false) }
        var editingWorkRel by remember { mutableStateOf<ContactCompanyRelation?>(null) }
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
            isEmpty = !hasWork && !hasProfession,
            editing = editing
        ) {
            ProfessionRow(contact = contact, editing = editing)
            if (hasWork && hasProfession) HorizontalDivider(
                color = AppleTheme.colors.separator, thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            // ВСЕ места работы (раньше показывалось только одно — после добавления
            // второго казалось, что «ничего не произошло», а удаление «не работало»,
            // потому что на месте удалённого всплывало другое, скрытое).
            if (hasWork) {
                val rels = contact.companyRelations
                    .sortedWith(compareByDescending<ContactCompanyRelation> { it.isPrimary }
                        .thenBy { it.employmentStatus == EmploymentStatus.FORMER })
                rels.forEachIndexed { i, rel ->
                    if (i > 0) HorizontalDivider(
                        color = AppleTheme.colors.separator, thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    val companyName = AppStateStore.getCompany(rel.companyId)?.name ?: ""
                    val isFormer = rel.employmentStatus == EmploymentStatus.FORMER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            Modifier.weight(1f).clickable { onNavigateToCompany(rel.companyId) }
                        ) {
                            Text(
                                if (companyName.isNotBlank()) "$companyName ›" else stringResource(R.string.cd_company),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isFormer) AppleTheme.colors.secondaryLabel else AppleTheme.colors.label
                            )
                            val meta = listOfNotNull(
                                rel.position?.takeIf { it.isNotBlank() },
                                rel.department?.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                            if (meta.isNotBlank())
                                Text(meta, style = MaterialTheme.typography.bodySmall,
                                    color = AppleTheme.colors.secondaryLabel)
                        }
                        // Статус-капсула: текущее — бренд, прошлое — приглушённо
                        Box(
                            Modifier.clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (isFormer) AppleTheme.colors.fill
                                    else AppleTheme.colors.brand.copy(alpha = 0.12f)
                                )
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Text(
                                rel.employmentStatus.label(ctxLabel), fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isFormer) AppleTheme.colors.secondaryLabel else AppleTheme.colors.brand
                            )
                        }
                        if (editing) {
                            IconButton(
                                onClick = { editingWorkRel = rel },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Edit, stringResource(R.string.common_edit),
                                    Modifier.size(14.dp), tint = AppleTheme.colors.brand)
                            }
                            IconButton(
                                onClick = { pendingRemoveCompany = rel },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.cd_remove_company),
                                    Modifier.size(15.dp), tint = AppleTheme.colors.red)
                            }
                        }
                    }
                    if (!rel.workNote.isNullOrBlank())
                        Text(rel.workNote, style = MaterialTheme.typography.bodySmall,
                            color = AppleTheme.colors.secondaryLabel)
                }
                // Work notes
                val workNotes = AppStateStore.notes.filter {
                    it.contactId == contact.id && it.type == NoteType.WORK
                }
                workNotes.forEach { n ->
                    Text("• ${n.text}", style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                }
            }
            if (editing) TextButton(onClick = { showAddCompany = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.cd_add_company))
            }
        }
        // ЕДИНЫЙ поток добавления/правки места работы (WorkplaceComponents.kt) —
        // тот же код используется на вкладке Работа, никаких локальных копий.
        if (showAddCompany) {
            WorkplaceAddFlow(contact = contact, onDismiss = { showAddCompany = false })
        }
        editingWorkRel?.let { rel ->
            WorkplaceEditDialog(contact = contact, rel = rel, onDismiss = { editingWorkRel = null })
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
                                shape  = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Small,
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
                if (editing) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showAddInterest = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_interest))
                    }
                }
            } else {
                if (editing) TextButton(onClick = { showAddInterest = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_interest))
                }
            }
        }
        if (showAddInterest) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = stringResource(R.string.cd_add_interest),
                onDismiss = { showAddInterest = false; newInterestVal = "" },
                confirmText = stringResource(R.string.common_add),
                confirmEnabled = newInterestVal.isNotBlank(),
                onConfirm = {
                    AppStateStore.updateContact(contact.copy(
                        personalDetails = contact.personalDetails + PersonalDetail(
                            id        = java.util.UUID.randomUUID().toString(),
                            contactId = contact.id,
                            category  = newInterestCat,
                            value     = newInterestVal.trim()
                        )
                    ))
                    newInterestVal = ""; showAddInterest = false
                },
                secondaryText = stringResource(R.string.common_cancel),
                onSecondary = { showAddInterest = false; newInterestVal = "" }
            ) {
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
            if (editing) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showAddDream = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_dream))
                }
            }
        }
        if (showAddDream) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = stringResource(R.string.cd_add_dream),
                onDismiss = { showAddDream = false; newDreamVal = "" },
                confirmText = stringResource(R.string.common_add),
                confirmEnabled = newDreamVal.isNotBlank(),
                onConfirm = {
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
                },
                secondaryText = stringResource(R.string.common_cancel),
                onSecondary = { showAddDream = false; newDreamVal = "" }
            ) {
                OutlinedTextField(
                    value = newDreamVal, onValueChange = { newDreamVal = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_goals_dreams)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    maxLines = 4
                )
            }
        }
    }

    // ── Статус и классификация (из вкладки Детали) ───────────
    // Статус возвращён по решению владельца 2026-07-03 (после удаления 2026-07-02):
    // «Поддерживать» — рабочая пометка «с кем нужно общаться».
    item {
        CardBlock(title = stringResource(R.string.cd_status_class)) {
            InfoRow(stringResource(R.string.common_status),          contact.contactStatus.label(ctxLabel))
            InfoRow(stringResource(R.string.filter_relation),
                contact.customRelationshipType?.takeIf { it.isNotBlank() } ?: contact.relationshipType.label(ctxLabel))
            InfoRow(stringResource(R.string.filter_importance),        contact.importanceLevel.label(ctxLabel))
            InfoRow(stringResource(R.string.cd_social_role), contact.socialRole.label(ctxLabel))
            InfoRow(stringResource(R.string.filter_rhythm),    contact.communicationRhythm.label(ctxLabel))
        }
    }

    // ── Теги (по макету: caps-заголовок СНАРУЖИ, пилюли r15 + «+ Тег») ──
    item {
        var showAddTag by remember { mutableStateOf(false) }
        var newTag by remember { mutableStateOf("") }
        Column {
            Text(
                stringResource(R.string.cd_tags).uppercase(),
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp, color = AppleTheme.colors.goldLabel,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp)
            ) {
                contact.tags.forEach { tag ->
                    Surface(
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R15,
                        color = AppleTheme.colors.brand.copy(alpha = 0.10f)
                    ) {
                        Text(
                            tag,
                            style    = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color    = AppleTheme.colors.brand,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)
                        )
                    }
                }
                // «+ Тег» — быстрое добавление прямо с карточки (как в макете)
                Surface(
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R15,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppleTheme.colors.separator),
                    modifier = Modifier.clickable { showAddTag = true }
                ) {
                    Text(
                        stringResource(R.string.cd_add_tag),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)
                    )
                }
            }
        }
        if (showAddTag) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = stringResource(R.string.cd_add_tag_title),
                onDismiss = { showAddTag = false; newTag = "" },
                confirmText = stringResource(R.string.common_add),
                confirmEnabled = newTag.isNotBlank() &&
                    contact.tags.none { it.equals(newTag.trim(), ignoreCase = true) },
                onConfirm = {
                    AppStateStore.updateContact(contact.copy(tags = contact.tags + newTag.trim()))
                    newTag = ""; showAddTag = false
                },
                secondaryText = stringResource(R.string.common_cancel),
                onSecondary = { showAddTag = false; newTag = "" }
            ) {
                OutlinedTextField(
                    value = newTag, onValueChange = { newTag = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_tags)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
        }
    }

    // Где познакомились — пустое поле открывается в режиме правки (тап = диалог)
    item {
        val hasMeet = !contact.meetContext.isNullOrBlank() || !contact.meetDate.isNullOrBlank()
        var showMeetDialog by remember { mutableStateOf(false) }
        if (hasMeet || editing) {
            Box(Modifier.then(if (editing) Modifier.clickable { showMeetDialog = true } else Modifier)) {
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
                            if (!hasMeet)
                                Text(stringResource(R.string.cd_where_met_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppleTheme.colors.secondaryLabel)
                        }
                    }
                }
            }
        }
        if (showMeetDialog) {
            var ctxDraft by remember { mutableStateOf(contact.meetContext ?: "") }
            var dateDraft by remember { mutableStateOf(contact.meetDate ?: "") }
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = stringResource(R.string.cd_where_met),
                onDismiss = { showMeetDialog = false },
                confirmText = stringResource(R.string.common_save),
                onConfirm = {
                    AppStateStore.updateContact(contact.copy(
                        meetContext = ctxDraft.trim().ifBlank { null },
                        meetDate    = dateDraft.trim().ifBlank { null }
                    ))
                    showMeetDialog = false
                },
                secondaryText = stringResource(R.string.common_cancel),
                onSecondary = { showMeetDialog = false }
            ) {
                OutlinedTextField(
                    value = ctxDraft, onValueChange = { ctxDraft = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_where_met)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3
                )
                DatePickerField(
                    value = dateDraft,
                    onValueChange = { dateDraft = it },
                    label = stringResource(R.string.cd_date_iso),
                    modifier = Modifier.fillMaxWidth(),
                    allowNoYear = true
                )
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
            // Шаг 1: канонический пикер людей (поиск + аватары)
            if (relSelected == null) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                    title = stringResource(R.string.cd_related_people),
                    items = AppStateStore.contacts
                        .filter { it.id != contact.id }
                        .map { c ->
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                                id = c.id,
                                title = "${c.firstName} ${c.lastName}".trim(),
                                subtitle = c.customRelationshipType?.takeIf { it.isNotBlank() }
                                    ?: c.relationshipType.label(ctxLabel)
                            )
                        },
                    onPick = { picked -> relSelected = AppStateStore.getContact(picked.id) },
                    onDismiss = { showAddRelated = false; relSearch = "" },
                    searchPlaceholder = stringResource(R.string.ce_search_contact),
                    emptyText = stringResource(R.string.compd_no_candidates)
                )
            } else relSelected?.let { sel ->
                // Шаг 2: роли связи
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                    title = "${sel.firstName} ${sel.lastName}".trim(),
                    onDismiss = { showAddRelated = false; relSelected = null },
                    confirmText = stringResource(R.string.common_add),
                    onConfirm = {
                        AppStateStore.addContactRelation(ContactRelation(
                            id = java.util.UUID.randomUUID().toString(),
                            firstContactId = contact.id,
                            secondContactId = sel.id,
                            firstRole = relMyRole,
                            secondRole = relOtherRole
                        ))
                        relSelected = null; relSearch = ""; showAddRelated = false
                    },
                    secondaryText = stringResource(R.string.common_back),
                    onSecondary = { relSelected = null }
                ) {
                    DropdownField(stringResource(R.string.ce_who_relation), relOtherRole, relRoles) { v -> relOtherRole = v }
                    DropdownField(stringResource(R.string.ce_who_am_i), relMyRole, relRoles) { v -> relMyRole = v }
                }
            }
        }
    }

    // Удалить контакт — красная строка внизу (спека HTML)
    item {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18)
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
        shape     = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge,
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
