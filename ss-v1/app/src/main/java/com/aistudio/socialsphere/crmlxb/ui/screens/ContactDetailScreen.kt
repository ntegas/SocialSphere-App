@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*
import kotlinx.coroutines.launch

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

    // Инлайн-редактирование вкладки — ОДНА кнопка в шапке (карандаш) вместо
    // отдельной «Изменить» на каждой вкладке. Обзор/Работа/Связь поддерживают
    // инлайн-правку; Подарки/Заметки — нет (там уже свои диалоги добавления),
    // для них кнопка неактивна. «⋯ → Редактировать» — отдельная, полная форма
    // редактирования контакта (ContactEditScreen), не путать с этим переключателем.
    var editingOverview by remember { mutableStateOf(false) }
    var editingComm by remember { mutableStateOf(false) }
    var editingWork by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) { editingOverview = false; editingComm = false; editingWork = false }
    val currentTabEditable = selectedTab in 0..2
    val currentTabEditing = when (selectedTab) {
        0 -> editingOverview
        1 -> editingWork
        2 -> editingComm
        else -> false
    }
    fun toggleCurrentTabEditing() {
        when (selectedTab) {
            0 -> editingOverview = !editingOverview
            1 -> editingWork = !editingWork
            2 -> editingComm = !editingComm
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var editingNote  by remember { mutableStateOf<Note?>(null) }
    var deletingNote by remember { mutableStateOf<Note?>(null) }
    var showAddGift     by remember { mutableStateOf(false) }
    var editingGift     by remember { mutableStateOf<GiftIdea?>(null) }
    var deletingGift    by remember { mutableStateOf<GiftIdea?>(null) }
    var showSizesDialog by remember { mutableStateOf(false) }
    var showAddPref     by remember { mutableStateOf(false) }
    // Правка СУЩЕСТВУЮЩЕЙ личной детали (интерес/еда/аллергия/бренд…) —
    // раньше записанное нельзя было ни изменить, ни (вне Подарков) удалить.
    var editingDetail   by remember { mutableStateOf<PersonalDetail?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showConvertDialog by remember { mutableStateOf(false) }
    var showGroupsSheet by remember { mutableStateOf(false) }
    // Единая шторка «⋯ Действия» из шапки (по макету): Редактировать /
    // Сохранить в телефон / Поделиться / Удалить.
    var showActionsSheet by remember { mutableStateOf(false) }

    // Add note state
    var noteText by remember { mutableStateOf("") }
    var noteType by remember { mutableStateOf(NoteType.GENERAL) }
    var noteIsImportant by remember { mutableStateOf(false) }
    // Приватность записи — отдельно от важности (2026-07-08, база знаний §29).
    var noteIsLocked by remember { mutableStateOf(false) }
    // Режим приватности — скрывает «защищённые» (важные) заметки блюром.
    // Только на сессию, без персиста (как в макете). Если в Настройках включена
    // биометрия — карточка стартует ЗАКРЫТОЙ, а снятие замочка идёт через
    // системный BiometricPrompt (отпечаток или код устройства).
    val bioLockOn = AppSettings.biometricLockSafe()
    var privacyMode by remember { mutableStateOf(bioLockOn) }
    // ФИКС (критичный баг, 2026-07-05): LocalContext.current здесь — обёртка
    // createConfigurationContext (см. LocalizedApp), не сама Activity — прямой
    // `as? FragmentActivity` ВСЕГДА давал null, requestReveal() уходил в else
    // и открывал «защищённые» заметки без единого запроса аутентификации.
    // findActivity() разматывает ContextWrapper до настоящей Activity.
    val bioActivity = LocalContext.current.findActivity()
    val bioTitle = stringResource(R.string.bio_prompt_title)
    var showPinReveal by remember { mutableStateOf(false) }
    fun requestReveal() {
        when {
            !bioLockOn -> privacyMode = false
            bioActivity != null && com.aistudio.socialsphere.crmlxb.utils.BiometricGate.isAvailable(bioActivity) ->
                com.aistudio.socialsphere.crmlxb.utils.BiometricGate.authenticate(
                    activity = bioActivity, title = bioTitle
                ) { privacyMode = false }
            AppSettings.hasPinSet() -> showPinReveal = true
            // Ни биометрии/кода устройства, ни своего PIN нет — нечем проверять,
            // не запираем владельца от собственных данных.
            else -> privacyMode = false
        }
    }

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

    // ── Шторка «⋯ Действия» (по макету): единая точка входа для действий,
    // раскиданных ранее по вкладкам/низу. Функции переиспользуют существующую
    // логику (vCard-экспорт, share-file, диалог удаления). ──
    if (showActionsSheet) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { showActionsSheet = false }) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.cd_actions),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
                )
                ActionSheetRow(Icons.Default.Edit, stringResource(R.string.common_edit)) {
                    showActionsSheet = false; onNavigateToEdit()
                }
                ActionSheetRow(Icons.Default.PersonAdd, stringResource(R.string.cd_save_to_phone)) {
                    showActionsSheet = false
                    scope.launch {
                        val file = com.aistudio.socialsphere.crmlxb.utils.ExportManager.exportContactVCard(ctx, contact)
                        com.aistudio.socialsphere.crmlxb.utils.ExportManager.openVcfInContacts(ctx, file)
                    }
                }
                ActionSheetRow(Icons.Default.Share, stringResource(R.string.cd_share)) {
                    showActionsSheet = false
                    scope.launch {
                        val file = com.aistudio.socialsphere.crmlxb.utils.ExportManager.exportContactVCard(ctx, contact)
                        com.aistudio.socialsphere.crmlxb.utils.ExportManager.shareFile(ctx, file, "text/x-vcard")
                    }
                }
                ActionSheetRow(Icons.Default.Group, stringResource(R.string.cd_groups)) {
                    showActionsSheet = false; showGroupsSheet = true
                }
                ActionSheetRow(Icons.Default.Business, stringResource(R.string.cd_convert_company)) {
                    showActionsSheet = false; showConvertDialog = true
                }
                ActionSheetRow(Icons.Default.Delete, stringResource(R.string.common_delete), destructive = true) {
                    showActionsSheet = false; showDeleteDialog = true
                }
            }
        }
    }

    // ── Группы контакта: чекбоксы членства + создание/правка/удаление групп ──
    if (showPinReveal) {
        PinVerifySheet(
            title = bioTitle,
            onSuccess = { privacyMode = false; showPinReveal = false },
            onDismiss = { showPinReveal = false }
        )
    }

    if (showGroupsSheet) {
        var editingGroup by remember { mutableStateOf<ContactGroup?>(null) }
        var showNewGroup by remember { mutableStateOf(false) }
        var groupNameDraft by remember { mutableStateOf("") }
        fun toggleGroup(groupId: String) {
            val current = AppStateStore.groupsOfContact(contactId).map { it.id }.toSet()
            AppStateStore.setContactGroups(
                contactId,
                if (groupId in current) current - groupId else current + groupId
            )
        }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { showGroupsSheet = false }) {
            Text(
                stringResource(R.string.cd_groups),
                fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                fontSize = 20.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.label,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            if (AppStateStore.groups.isEmpty()) {
                Text(
                    stringResource(R.string.groups_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            AppStateStore.groups.sortedBy { it.name.lowercase() }.forEach { g ->
                val checked = AppStateStore.groupMembers
                    .any { it.groupId == g.id && it.contactId == contactId }
                Row(
                    Modifier.fillMaxWidth().clickable { toggleGroup(g.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggleGroup(g.id) })
                    Text(g.name, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal)
                    Text(
                        AppStateStore.contactIdsInGroup(g.id).size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.tertiaryLabel
                    )
                    IconButton(onClick = { groupNameDraft = g.name; editingGroup = g }) {
                        Icon(Icons.Default.Edit, stringResource(R.string.common_edit),
                            Modifier.size(15.dp), tint = AppleTheme.colors.secondaryLabel)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .clickable { groupNameDraft = ""; showNewGroup = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                Text(stringResource(R.string.group_new), color = AppleTheme.colors.brand,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        if (showNewGroup) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = stringResource(R.string.group_new),
                onDismiss = { showNewGroup = false },
                confirmText = stringResource(R.string.ce_create),
                confirmEnabled = groupNameDraft.isNotBlank(),
                onConfirm = {
                    AppStateStore.addGroup(groupNameDraft)?.let { toggleGroup(it.id) }
                    showNewGroup = false
                },
                secondaryText = stringResource(R.string.common_cancel),
                onSecondary = { showNewGroup = false }
            ) {
                OutlinedTextField(
                    value = groupNameDraft, onValueChange = { groupNameDraft = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
        }
        editingGroup?.let { g ->
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { editingGroup = null }) {
                Text(
                    g.name,
                    fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                    fontSize = 20.sp, fontWeight = FontWeight.W700,
                    color = AppleTheme.colors.label,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = groupNameDraft, onValueChange = { groupNameDraft = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = groupNameDraft.isNotBlank(),
                    onClick = { AppStateStore.renameGroup(g.id, groupNameDraft); editingGroup = null },
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold) }
                TextButton(
                    onClick = { AppStateStore.deleteGroup(g.id); editingGroup = null },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.group_delete), color = AppleTheme.colors.red) }
            }
        }
    }

    // ── «Сделать компанией»: контакт → компания (телефоны/email/адреса/заметки
    // переезжают, карточка контакта удаляется) ──
    if (showConvertDialog) {
        AlertDialog(
            onDismissRequest = { showConvertDialog = false },
            icon = { Icon(Icons.Default.Business, null, tint = AppleTheme.colors.brand) },
            title = { Text(stringResource(R.string.cd_convert_company_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.cd_convert_company_text,
                "${contact.firstName} ${contact.lastName}".trim())) },
            confirmButton = {
                Button(onClick = {
                    showConvertDialog = false
                    val newCompanyId = AppStateStore.convertContactToCompany(contactId)
                    if (newCompanyId != null) {
                        onNavigateBack()
                        onNavigateToCompany(newCompanyId)
                    }
                }) { Text(stringResource(R.string.cd_convert)) }
            },
            dismissButton = { TextButton(onClick = { showConvertDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    Box(Modifier.padding(start = 12.dp)) {
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(
                            contentDescription = stringResource(R.string.common_back),
                            onClick = onNavigateBack
                        )
                    }
                },
                actions = {
                    // Замок приватности (по макету): малахит-кружок когда вкл.
                    // ФИКС (2026-07-08, владелец: «кнопка видна, даже если защита не
                    // включена в настройках»): раньше показывался безусловно — можно
                    // было тапнуть и «включить» приватность на сессию, даже когда
                    // «Защита записей» выключена в Настройках, что не имело смысла и
                    // путало (кнопка выглядела как реальный переключатель настройки).
                    if (bioLockOn) {
                        Box(Modifier.padding(end = 4.dp)) {
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCircleButton(
                                icon = if (privacyMode) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = stringResource(R.string.cd_privacy_toggle),
                                style = if (privacyMode) com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCircleStyle.Filled
                                        else com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCircleStyle.Neutral,
                                size = 36.dp, iconSize = 18.dp,
                                onClick = { if (privacyMode) requestReveal() else privacyMode = true }
                            )
                        }
                    }
                    // Круглая кнопка инлайн-правки ТЕКУЩЕЙ вкладки — заменяет старые
                    // отдельные «Изменить» на Обзоре/Работе/Связи (была одна на каждой
                    // вкладке + свой отступ — теперь одна общая здесь). Неактивна на
                    // Подарках/Заметках (там нет режима правки, только диалоги).
                    // Полная форма редактирования контакта — отдельно, через «⋯».
                    // Свой Box (не AureliaCircleButton) — нужно 3-е, «неактивное»
                    // состояние, которого нет в AureliaCircleStyle; цвета/размеры
                    // те же токены (neutralFill/brand), чтобы не расходиться с ним.
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !currentTabEditable -> AppleTheme.colors.neutralFill
                                    currentTabEditing    -> AppleTheme.colors.brand
                                    else                  -> AppleTheme.colors.brand.copy(alpha = 0.10f)
                                }
                            )
                            .then(
                                if (currentTabEditable) Modifier.clickable { toggleCurrentTabEditing() }
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (currentTabEditing) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = stringResource(if (currentTabEditing) R.string.common_done else R.string.common_edit),
                            modifier = Modifier.size(18.dp),
                            tint = when {
                                !currentTabEditable -> AppleTheme.colors.tertiaryLabel
                                currentTabEditing    -> Color.White
                                else                  -> AppleTheme.colors.brand
                            }
                        )
                    }
                    // Круглая кнопка «⋯» — единая шторка действий (по макету)
                    Box(Modifier.padding(start = 4.dp, end = 12.dp)) {
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCircleButton(
                            icon = Icons.Default.MoreHoriz,
                            contentDescription = stringResource(R.string.cd_actions),
                            style = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCircleStyle.Neutral,
                            size = 36.dp, iconSize = 20.dp,
                            onClick = { showActionsSheet = true }
                        )
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

            val dividerColor = AppleTheme.colors.separator
            // Табы по макету (сверено getComputedStyle): 14sp, зазор 15dp — было 15sp/20dp,
            // на реальном устройстве (плюс системный масштаб шрифта) 5 вкладок не помещались
            // в ширину и «Заметки» обрезалась до «Заме» (жалоба владельца). horizontalScroll —
            // подстраховка на ещё более узких экранах/крупном системном шрифте, ничего не обрезает.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .drawBehind {
                        val y = size.height - 0.5.dp.toPx()
                        drawLine(dividerColor, androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val sel = selectedTab == index
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { selectedTab = index }
                    ) {
                        Text(
                            text = title,
                            fontSize = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (sel) AppleTheme.colors.label else AppleTheme.colors.tertiaryLabel,
                            modifier = Modifier.padding(bottom = 11.dp)
                        )
                        Box(
                            Modifier.height(2.5.dp).width(28.dp)
                                .background(if (sel) AppleTheme.colors.brand else Color.Transparent,
                                    RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        )
                    }
                }
            }

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
                        onEditPref   = { editingDetail = it },
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
                        onDeleteNote = { deletingNote = it },
                        onEditDetail = { editingDetail = it }
                    , ctxLabel = ctxLabel,
                        privacyMode = privacyMode,
                        onTogglePrivacy = { requestReveal() })
                }
            }
        }
    }

    // ── Добавление / правка идеи подарка ──
    // ФИКС (аудит 2026-07-06): раньше ручной дубль каркаса AureliaSheet
    // (заголовок+кнопка вручную) вместо канонического AureliaFormSheet —
    // работало, но не по канону; поведение не изменилось, только каркас.
    if (showAddGift || editingGift != null) {
        val g = editingGift
        var gTitle by remember(g?.id ?: "new") { mutableStateOf(g?.title ?: "") }
        var gNote  by remember(g?.id ?: "new") { mutableStateOf(g?.note ?: "") }
        var gLink  by remember(g?.id ?: "new") { mutableStateOf(g?.link ?: "") }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = if (g == null) stringResource(R.string.cd_gift_idea) else stringResource(R.string.cd_gift_edit),
            onDismiss = { showAddGift = false; editingGift = null },
            confirmText = stringResource(R.string.common_save),
            confirmEnabled = gTitle.isNotBlank(),
            onConfirm = {
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
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddGift = false; editingGift = null }
        ) {
            OutlinedTextField(value = gTitle, onValueChange = { gTitle = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_title_req)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = gNote, onValueChange = { gNote = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_note)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            OutlinedTextField(value = gLink, onValueChange = { gLink = it },
                label = { Text(stringResource(R.string.cd_link)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
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
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.cd_sizes),
            onDismiss = { showSizesDialog = false },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                AppStateStore.setSizeInfo(contact.id, SizeInfo(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    contactId    = contact.id,
                    clothingSize = sClothing.trim().ifBlank { null },
                    shoeSize     = sShoe.trim().ifBlank { null },
                    ringSize     = sRing.trim().ifBlank { null },
                    other        = sOther.trim().ifBlank { null }
                ))
                showSizesDialog = false
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showSizesDialog = false }
        ) {
            OutlinedTextField(value = sClothing, onValueChange = { sClothing = it },
                label = { Text(stringResource(R.string.cd_clothes_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = sShoe, onValueChange = { sShoe = it },
                label = { Text(stringResource(R.string.cd_shoes_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = sRing, onValueChange = { sRing = it },
                label = { Text(stringResource(R.string.cd_ring_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = sOther, onValueChange = { sOther = it },
                label = { Text(stringResource(R.string.common_other)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
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
        // Шторка-редактор детали (макет PERSONAL DETAIL EDITOR): категория чипами +
        // поле + подсказка «куда попадёт» (фидбэк владельца: было непонятно,
        // где эта запись потом отобразится).
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { showAddPref = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.cd_preference),
                    fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                    fontSize = 20.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.label
                )
                PillChoiceRow(
                    options = prefCategories.map { it.label(ctxLabel) },
                    selected = prefCat.label(ctxLabel),
                    onSelect = { picked -> prefCat = prefCategories.firstOrNull { it.label(ctxLabel) == picked } ?: prefCat }
                )
                OutlinedTextField(value = prefValue, onValueChange = { prefValue = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_value_hint)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                // Куда попадёт запись — по выбранной категории
                Text(
                    stringResource(
                        if (prefCat == PersonalDetailCategory.ALLERGIES || prefCat == PersonalDetailCategory.RESTRICTIONS)
                            R.string.cd_goes_to_allergy else R.string.cd_goes_to_gifts
                    ),
                    fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel
                )
                Button(
                    enabled = prefValue.isNotBlank(),
                    onClick = {
                        AppStateStore.updateContact(contact.copy(
                            personalDetails = contact.personalDetails + PersonalDetail(
                                id        = java.util.UUID.randomUUID().toString(),
                                contactId = contact.id,
                                category  = prefCat,
                                value     = prefValue.trim()
                            )
                        ))
                        showAddPref = false
                    },
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(stringResource(R.string.common_add), fontWeight = FontWeight.Bold) }
            }
        }
    }

    // ── Правка заметки — шторка нового дизайна (тип чипами + «куда попадёт») ──
    // ФИКС (аудит 2026-07-06): AureliaSheet-дубль каркаса → канонический AureliaFormSheet.
    editingNote?.let { note ->
        var editText by remember(note.id) { mutableStateOf(note.text) }
        var editType by remember(note.id) { mutableStateOf(note.type) }
        var editImportant by remember(note.id) { mutableStateOf(note.isImportant) }
        var editLocked by remember(note.id) { mutableStateOf(note.isLocked) }
        val editNoteTypes = NoteType.values().filter { it != NoteType.GIFT }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.cd_note_edit),
            onDismiss = { editingNote = null },
            confirmText = stringResource(R.string.common_save),
            confirmEnabled = editText.isNotBlank(),
            onConfirm = {
                AppStateStore.updateNote(note.copy(
                    text = editText.trim(),
                    type = editType,
                    isImportant = editImportant,
                    isLocked = editLocked
                ))
                editingNote = null
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { editingNote = null }
        ) {
            PillChoiceRow(
                options = editNoteTypes.map { it.label(ctxLabel) },
                selected = editType.label(ctxLabel),
                onSelect = { picked -> editType = editNoteTypes.firstOrNull { n -> n.label(ctxLabel) == picked } ?: editType }
            )
            Text(
                stringResource(
                    when {
                        editImportant -> R.string.cd_goes_important
                        editType == NoteType.WORK -> R.string.cd_goes_work
                        editType == NoteType.PERSONAL_DETAIL -> R.string.cd_goes_personal
                        editType == NoteType.IMPORTANT_TO_REMEMBER -> R.string.cd_goes_important
                        else -> R.string.cd_goes_general
                    }
                ),
                fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel
            )
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_note_text)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 5
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = editImportant, onCheckedChange = { editImportant = it })
                Text(stringResource(R.string.cd_note_important), style = MaterialTheme.typography.bodyMedium)
            }
            if (bioLockOn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = editLocked, onCheckedChange = { editLocked = it })
                    Text(stringResource(R.string.cd_note_lock), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // ── Правка существующей личной детали (интерес/еда/аллергия/бренд…) —
    // категория + значение, с удалением. Открывается из ленты Заметок и
    // из «Предпочтений» на Подарках. ФИКС (аудит 2026-07-06): AureliaSheet-дубль
    // каркаса → канонический AureliaFormSheet; кнопка удаления — как доп.
    // действие внутри content(), т.к. AureliaFormSheet даёт только один
    // вторичный слот, а его цвет (secondaryLabel) не подходит для destructive. ──
    editingDetail?.let { det ->
        var detValue by remember(det.id) { mutableStateOf(det.value) }
        var detCat by remember(det.id) { mutableStateOf(det.category) }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.cd_edit_detail),
            onDismiss = { editingDetail = null },
            confirmText = stringResource(R.string.common_save),
            confirmEnabled = detValue.isNotBlank(),
            onConfirm = {
                AppStateStore.updateContact(contact.copy(
                    personalDetails = contact.personalDetails.map {
                        if (it.id == det.id) it.copy(category = detCat, value = detValue.trim()) else it
                    }
                ))
                editingDetail = null
            }
        ) {
            DropdownField(
                label         = stringResource(R.string.cd_category),
                selectedValue = detCat.label(ctxLabel),
                options       = PersonalDetailCategory.values().map { it.label(ctxLabel) }
            ) { selected ->
                detCat = PersonalDetailCategory.values()
                    .firstOrNull { it.label(ctxLabel) == selected } ?: detCat
            }
            OutlinedTextField(
                value = detValue, onValueChange = { detValue = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_value)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            TextButton(
                onClick = {
                    AppStateStore.updateContact(contact.copy(
                        personalDetails = contact.personalDetails.filter { it.id != det.id }
                    ))
                    editingDetail = null
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.common_delete), color = AppleTheme.colors.red) }
        }
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

    // Шторка «Добавить заметку»: тип — чипами + подсказка «куда попадёт»
    // (фидбэк владельца: логика заметок была непрозрачной; был AlertDialog).
    // ФИКС (аудит 2026-07-06): AureliaSheet-дубль каркаса → канонический AureliaFormSheet.
    if (showAddDialog) {
        val noteTypes = NoteType.values().filter { it != NoteType.GIFT }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.cd_add_note),
            onDismiss = { showAddDialog = false; noteText = "" },
            confirmText = stringResource(R.string.common_save),
            confirmEnabled = noteText.isNotBlank(),
            onConfirm = {
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
                        isLocked = noteIsLocked,
                        createdAt = now, updatedAt = now
                    )
                )
                noteText = ""; noteIsImportant = false; noteIsLocked = false; showAddDialog = false
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddDialog = false; noteText = "" }
        ) {
            PillChoiceRow(
                options = noteTypes.map { it.label(ctxLabel) },
                selected = noteType.label(ctxLabel),
                onSelect = { picked -> noteType = noteTypes.firstOrNull { n -> n.label(ctxLabel) == picked } ?: NoteType.GENERAL }
            )
            // Куда попадёт запись — по типу (важные — отдельная подсказка)
            Text(
                stringResource(
                    when {
                        noteIsImportant -> R.string.cd_goes_important
                        noteType == NoteType.WORK -> R.string.cd_goes_work
                        noteType == NoteType.PERSONAL_DETAIL -> R.string.cd_goes_personal
                        noteType == NoteType.IMPORTANT_TO_REMEMBER -> R.string.cd_goes_important
                        else -> R.string.cd_goes_general
                    }
                ),
                fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel
            )
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.cd_note_text)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 5
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = noteIsImportant, onCheckedChange = { noteIsImportant = it })
                Text(stringResource(R.string.cd_note_important), style = MaterialTheme.typography.bodyMedium)
            }
            // Приватность — своя, отдельная галочка (2026-07-08, база знаний §29):
            // владелец решает по каждой записи вручную, не через «важность».
            if (bioLockOn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = noteIsLocked, onCheckedChange = { noteIsLocked = it })
                    Text(stringResource(R.string.cd_note_lock), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // Quick personal detail state
    var pdText     by remember { mutableStateOf("") }
    var pdCategory by remember { mutableStateOf(PersonalDetailCategory.INTERESTS) }

    // Шторка «Личная деталь» (макет PERSONAL DETAIL EDITOR; был AlertDialog старого вида)
    if (showVoiceDialog) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(
            onDismiss = { showVoiceDialog = false; pdText = "" }
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        stringResource(R.string.cd_add_personal_detail),
                        fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                        fontSize = 20.sp, fontWeight = FontWeight.W700, color = AppleTheme.colors.label
                    )

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
                            Text(hint, color = AppleTheme.colors.tertiaryLabel)
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
                                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R6,
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
                    // Куда попадёт запись (прозрачность логики — фидбэк владельца)
                    val giftCats = listOf(
                        PersonalDetailCategory.FOOD, PersonalDetailCategory.DRINKS,
                        PersonalDetailCategory.LIKES, PersonalDetailCategory.DISLIKES,
                        PersonalDetailCategory.BRANDS
                    )
                    if (pdCategory in giftCats)
                        Text(stringResource(R.string.cd_goes_to_gifts),
                            fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)
                    else if (pdCategory == PersonalDetailCategory.ALLERGIES ||
                             pdCategory == PersonalDetailCategory.RESTRICTIONS)
                        Text(stringResource(R.string.cd_goes_to_allergy),
                            fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel)

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
                        enabled = pdText.isNotBlank(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold) }
                }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** Строка в шторке «⋯ Действия»: иконка + подпись, деструктив — красным. */
@Composable
private fun ActionSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (destructive) AppleTheme.colors.red else AppleTheme.colors.label
    Row(
        // Горизонталь 6dp: AureliaSheet уже даёт 18dp по краям (итого 24, как в макете)
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

@Composable
fun ContactHeader(contact: Contact, onNavigateToCheatSheet: () -> Unit = {}, onNavigateToCreateCalendarItem: () -> Unit = {}) {
    val ctxLabel = LocalContext.current
    val compRel  = contact.companyRelations.firstOrNull { it.isPrimary } ?: contact.companyRelations.firstOrNull()
    val company  = compRel?.companyId?.let { AppStateStore.getCompany(it) }?.name ?: ""
    // Должность в компании, иначе — свободная профессия (v12)
    val position = compRel?.position?.takeIf { it.isNotBlank() } ?: contact.profession ?: ""
    val address  = AppStateStore.addresses.find { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
    val city     = address?.city ?: ""
    // Полное имя с отчеством (как в телефонной книге)
    val name = listOfNotNull(
        contact.firstName.takeIf { it.isNotBlank() },
        contact.middleName?.takeIf { it.isNotBlank() },
        contact.lastName.takeIf { it.isNotBlank() }
    ).joinToString(" ")

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
    val nowIso = { java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
    ) {
        // Шапка по макету: аватар 56 слева (терракот-градиент, золотое кольцо,
        // инициалы Playfair) + имя/подзаголовок справа.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Важность — ЦВЕТНОЙ ОБОДОК аватара (решение владельца 2026-07-02),
            // не отдельный чип: Ключевой — золото, Важный — терракот, Обычный — без.
            // Тап по аватару меняет важность (функция быстрой правки сохранена).
            var showImportanceMenu by remember { mutableStateOf(false) }
            val importanceRing = when (contact.importanceLevel) {
                ImportanceLevel.KEY       -> AppleTheme.colors.importanceKey
                ImportanceLevel.IMPORTANT -> AppleTheme.colors.importanceHigh
                else                      -> Color.Transparent
            }
            Box {
                val headerPhoto = contact.photoUri?.let { java.io.File(it) }?.takeIf { it.exists() }
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                        // Цвет по хешу id (AureliaAvatars) — тот же, что в списке
                        // контактов; фикс §28 «зелёная в списке, оранжевая на карточке».
                        .background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.brushFor(contact.id))
                        .then(
                            if (importanceRing != Color.Transparent)
                                Modifier.border(2.5.dp, importanceRing, CircleShape)
                            else Modifier
                        )
                        .clickable { showImportanceMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (headerPhoto != null) {
                        coil.compose.AsyncImage(
                            model = headerPhoto, contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                        )
                    } else Text(initials.uppercase(), color = Color.White,
                        fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                        fontSize = 22.sp, fontWeight = FontWeight.W600)
                }
                DropdownMenu(expanded = showImportanceMenu, onDismissRequest = { showImportanceMenu = false }) {
                    ImportanceLevel.values().forEach { imp ->
                        DropdownMenuItem(
                            text = { Text(imp.label(ctxLabel)) },
                            trailingIcon = if (imp == contact.importanceLevel) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand) }
                            } else null,
                            onClick = {
                                showImportanceMenu = false
                                AppStateStore.updateContact(contact.copy(importanceLevel = imp, updatedAt = nowIso()))
                            }
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                // SelectionContainer: имя можно выделить долгим тапом и скопировать
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(name, color = AppleTheme.colors.label,
                        fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                        fontSize = 21.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.01).em,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (subtitle.isNotEmpty())
                    Text(subtitle, color = AppleTheme.colors.secondaryLabel, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp))
                if (!contact.nickname.isNullOrBlank())
                    Text("«${contact.nickname}»", color = AppleTheme.colors.secondaryLabel, fontSize = 12.sp)
            }
        }

        // Чипы — редактируемые (функция сохранена), слева под именем.
        // Состав по решению владельца 2026-07-02: важность ушла в ободок аватара,
        // уровень связи слит в статус; добавлена соц.роль; ритм «не отслеживается»
        // не показывается (шум) — правится через ритм-чип, когда задан, или форму.
        Spacer(Modifier.height(11.dp))
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EditableChip(
                current = contact.customRelationshipType?.takeIf { it.isNotBlank() }
                    ?: contact.relationshipType.label(ctxLabel),
                options = RelationshipType.values().map { it.label(ctxLabel) },
                container = AppleTheme.colors.brand.copy(alpha = 0.10f), labelColor = AppleTheme.colors.brand
            ) { picked -> RelationshipType.values().firstOrNull { it.label(ctxLabel) == picked }?.let {
                // Выбор стандартного типа очищает свой (кастомный задаётся в форме)
                AppStateStore.updateContact(contact.copy(relationshipType = it, customRelationshipType = null, updatedAt = nowIso()))
            } }
            // Чип «статус» ВОЗВРАЩЁН (2026-07-03, владелец передумал после удаления
            // 2026-07-02): «Поддерживать» — важная пометка «с кем нужно общаться».
            EditableChip(
                current = contact.contactStatus.label(ctxLabel),
                options = ContactStatus.values().map { it.label(ctxLabel) },
                container = AppleTheme.colors.fill, labelColor = AppleTheme.colors.secondaryLabel
            ) { picked -> ContactStatus.values().firstOrNull { it.label(ctxLabel) == picked }?.let { AppStateStore.updateContact(contact.copy(contactStatus = it, updatedAt = nowIso())) } }
            EditableChip(
                current = contact.socialRole.label(ctxLabel),
                options = SocialRole.values().map { it.label(ctxLabel) },
                container = AppleTheme.colors.orange.copy(alpha = 0.12f), labelColor = AppleTheme.colors.orange
            ) { picked -> SocialRole.values().firstOrNull { it.label(ctxLabel) == picked }?.let { AppStateStore.updateContact(contact.copy(socialRole = it, updatedAt = nowIso())) } }
            if (contact.communicationRhythm != CommunicationRhythm.NOT_TRACKED) {
                EditableChip(
                    current = contact.communicationRhythm.label(ctxLabel),
                    options = CommunicationRhythm.values().filter { it != CommunicationRhythm.CUSTOM }.map { it.label(ctxLabel) },
                    container = AppleTheme.colors.fill, labelColor = AppleTheme.colors.label
                ) { picked -> CommunicationRhythm.values().firstOrNull { it.label(ctxLabel) == picked }?.let { AppStateStore.updateContact(contact.copy(communicationRhythm = it, updatedAt = nowIso())) } }
            }
        }

        // Теги (сохранены)
        if (contact.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
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

        // Быстрые действия — круглые кнопки по макету (звонок залит брендом,
        // шпаргалка — золотом, остальные — карточка с тонкой обводкой). Функции сохранены.
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            QuickCircle(Icons.Outlined.Phone, stringResource(R.string.cd_call), filled = true) {
                val phone = contact.phones.find { it.isPrimary }?.number ?: contact.phones.firstOrNull()?.number
                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(context, phone)
            }
            QuickCircle(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.cd_write)) {
                val m = contact.messengers.find { it.isPrimary } ?: contact.messengers.firstOrNull()
                if (m != null) com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(context, m)
                else com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(context, contact.phones.firstOrNull()?.number)
            }
            QuickCircle(Icons.Outlined.Email, stringResource(R.string.cd_email_action)) {
                val email = contact.emails.find { it.isPrimary }?.email ?: contact.emails.firstOrNull()?.email
                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(context, email)
            }
            if (address != null) QuickCircle(Icons.Outlined.Map, stringResource(R.string.cd_map)) {
                if (address.latitude != null && address.longitude != null)
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                else
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
            }
            QuickCircle(Icons.Default.Lightbulb, stringResource(R.string.cd_cheatsheet), gold = true) { onNavigateToCheatSheet() }
            QuickCircle(Icons.Outlined.Event, stringResource(R.string.cd_create_event)) { onNavigateToCreateCalendarItem() }
        }
    }
}


/** Круглая быстрая кнопка карточки контакта (38dp) с подписью 9sp — по макету. */
@Composable
private fun QuickCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean = false,
    gold: Boolean = false,
    onClick: () -> Unit
) {
    val gold9A = AppleTheme.colors.orange
    Column(
        modifier = Modifier.width(52.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape)
                .background(
                    when {
                        filled -> AppleTheme.colors.brand
                        gold   -> gold9A.copy(alpha = 0.16f)
                        else   -> AppleTheme.colors.card
                    }
                )
                .then(
                    if (!filled && !gold)
                        Modifier.border(1.dp, AppleTheme.colors.separator, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = label,
                tint = when { filled -> Color.White; gold -> gold9A; else -> AppleTheme.colors.brand },
                modifier = Modifier.size(17.dp)
            )
        }
        Text(
            label, color = if (gold) gold9A else AppleTheme.colors.secondaryLabel,
            fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        modifier = Modifier.weight(1f).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R13).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(44.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R13)
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
        modifier = modifier.clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R10).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
internal fun ActionSquare(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R10).background(AppleTheme.colors.card).clickable { onClick() },
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
        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge,
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
        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge,
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
fun SizeChip(label: String, value: String, highlight: Boolean = false) {
    Card(
        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14,
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) AppleTheme.colors.red.copy(alpha = 0.1f) else AppleTheme.colors.card
        ),
        modifier = Modifier.padding(end = 9.dp, bottom = 9.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).widthIn(min = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlight) AppleTheme.colors.red else AppleTheme.colors.label
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) AppleTheme.colors.red else AppleTheme.colors.secondaryLabel
            )
        }
    }
}
