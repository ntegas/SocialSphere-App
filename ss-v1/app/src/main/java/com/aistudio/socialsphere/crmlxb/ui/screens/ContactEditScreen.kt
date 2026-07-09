@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens
import androidx.compose.ui.graphics.Color

import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.utils.label
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactEditScreen(
    contactId: String?,
    onNavigateBack: () -> Unit,
    // После СОЗДАНИЯ контакта открываем его карточку (фидбэк владельца), а не список.
    onCreated: ((String) -> Unit)? = null
) {
    val isEditMode = contactId != null
    val ctxLabel = LocalContext.current
    val scope = rememberCoroutineScope()
    val originalContact = remember { contactId?.let { AppStateStore.getContact(it) } }
    val nowIso = { LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) }

    // Стабильный id: для нового контакта генерируется один раз,
    // чтобы связи (семья) могли ссылаться на него ещё до сохранения
    val editedContactId = remember { originalContact?.id ?: UUID.randomUUID().toString() }

    // ── Семья и отношения ──
    val originalRelations = remember {
        AppStateStore.contactRelations
            .filter { it.firstContactId == editedContactId || it.secondContactId == editedContactId }
            .toList()
    }
    var contactRelationsDraft by remember { mutableStateOf(originalRelations) }
    var showAddRelation by remember { mutableStateOf(false) }

    var firstName  by remember { mutableStateOf(originalContact?.firstName ?: "") }
    var lastName   by remember { mutableStateOf(originalContact?.lastName  ?: "") }
    var middleName by remember { mutableStateOf(originalContact?.middleName ?: "") }
    // Структура имени как в Android-контактах (v13): приставка/суффикс/фонетика
    var namePrefix        by remember { mutableStateOf(originalContact?.namePrefix ?: "") }
    var nameSuffix         by remember { mutableStateOf(originalContact?.nameSuffix ?: "") }
    var phoneticFirstName by remember { mutableStateOf(originalContact?.phoneticFirstName ?: "") }
    var phoneticLastName  by remember { mutableStateOf(originalContact?.phoneticLastName ?: "") }

    // Mutable lists for editing
    var phones     by remember { mutableStateOf(originalContact?.phones     ?: emptyList<ContactPhone>()) }
    var emails     by remember { mutableStateOf(originalContact?.emails     ?: emptyList<ContactEmail>()) }
    var messengers by remember { mutableStateOf(originalContact?.messengers ?: emptyList<Messenger>()) }
    var draftAddresses by remember { mutableStateOf(originalContact?.addresses ?: emptyList<Address>()) }

    // Company / work
    var selectedCompanyId by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull { it.isPrimary }?.companyId ?: originalContact?.companyRelations?.firstOrNull()?.companyId ?: "") }
    var companyPosition   by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.position ?: "") }
    var companyDept       by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.department ?: "") }
    var workNote          by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.workNote ?: "") }

    // Classification
    var relationshipType   by remember { mutableStateOf(originalContact?.relationshipType   ?: RelationshipType.ACQUAINTANCE) }
    // Свой тип отношений («Кум», «Тренер»…) — если непустой, показывается вместо стандартного
    var customRelationType by remember { mutableStateOf(originalContact?.customRelationshipType ?: "") }
    var showCustomRelDialog by remember { mutableStateOf(false) }
    var connectionLevel    by remember { mutableStateOf(originalContact?.connectionLevel    ?: ConnectionLevel.NORMAL) }
    var importanceLevel    by remember { mutableStateOf(originalContact?.importanceLevel    ?: ImportanceLevel.NORMAL) }
    var socialRole         by remember { mutableStateOf(originalContact?.socialRole         ?: SocialRole.REGULAR) }
    var communicationRhythm by remember { mutableStateOf(originalContact?.communicationRhythm ?: CommunicationRhythm.NOT_TRACKED) }
    var contactStatus      by remember { mutableStateOf(originalContact?.contactStatus      ?: ContactStatus.ACTIVE) }

    // Фото: абсолютный путь копии в filesDir/photos (см. PhotoStorage)
    var photoUri        by remember { mutableStateOf(originalContact?.photoUri) }

    // New fields
    var nickname        by remember { mutableStateOf(originalContact?.nickname ?: "") }
    var profession      by remember { mutableStateOf(originalContact?.profession ?: "") }
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
        // Связи с ДРУГИМИ компаниями (например, добавленные со стороны карточки
        // компании) сохраняем — раньше правка контакта затирала их одной основной,
        // и человек пропадал из остальных компаний.
        val otherCompRels = (originalContact?.companyRelations ?: emptyList())
            .filter { it.companyId != selectedCompanyId }
            .map { it.copy(isPrimary = false) }
        // Существующую связь ОБНОВЛЯЕМ copy() (баг: пересоздание затирало
        // employmentStatus/startDate/role/responsibilities/managedAccounts на
        // каждом сохранении формы и всё делало «Текущее»).
        val existingPrimaryRel = originalContact?.companyRelations
            ?.firstOrNull { it.companyId == selectedCompanyId }
        val compRelList = if (selectedCompanyId.isNotBlank()) listOf(
            existingPrimaryRel?.copy(
                position   = companyPosition.ifBlank { null },
                department = companyDept.ifBlank { null },
                workNote   = workNote.ifBlank { null },
                isPrimary  = true
            ) ?: ContactCompanyRelation(
                id = UUID.randomUUID().toString(),
                contactId  = editedContactId,
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
        ) + otherCompRels else originalContact?.companyRelations ?: emptyList()

        val newContact = Contact(
            id               = editedContactId,
            firstName        = firstName.trim(),
            lastName         = lastName.trim(),
            middleName       = middleName.trim().ifBlank { null },
            nickname         = nickname.trim().ifBlank { null },
            namePrefix       = namePrefix.trim().ifBlank { null },
            nameSuffix       = nameSuffix.trim().ifBlank { null },
            phoneticFirstName = phoneticFirstName.trim().ifBlank { null },
            phoneticLastName  = phoneticLastName.trim().ifBlank { null },
            phones           = phones,
            emails           = emails,
            messengers       = messengers,
            companyRelations = compRelList,
            addresses        = draftAddresses,
            notes            = originalContact?.notes ?: emptyList(),
            gifts            = originalContact?.gifts ?: emptyList(),
            sizeInfo         = originalContact?.sizeInfo,
            personalDetails  = originalContact?.personalDetails ?: emptyList(),
            relationshipType  = relationshipType,
            customRelationshipType = customRelationType.trim().ifBlank { null },
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
            photoUri         = photoUri,
            // Поля, не редактируемые этой формой, ОБЯЗАНЫ проноситься из оригинала —
            // иначе каждое сохранение молча стирало их (реальный баг: lastContactDate
            // и deviceContactId терялись при любом редактировании контакта)
            lastContactDate  = originalContact?.lastContactDate,
            deviceContactId  = originalContact?.deviceContactId,
            familyNote       = originalContact?.familyNote,
            profession       = profession.trim().ifBlank { null },
            createdAt        = originalContact?.createdAt ?: nowIso(),
            updatedAt        = nowIso()
        )
        if (isEditMode) AppStateStore.updateContact(newContact)
        else            AppStateStore.addContact(newContact)

        // Связи (семья): применяем разницу только при сохранении
        val keptIds = contactRelationsDraft.map { it.id }.toSet()
        originalRelations.filter { it.id !in keptIds }
            .forEach { AppStateStore.removeContactRelation(it.id) }
        val origIds = originalRelations.map { it.id }.toSet()
        contactRelationsDraft.filter { it.id !in origIds }
            .forEach { AppStateStore.addContactRelation(it) }

        if (!isEditMode && onCreated != null) onCreated(editedContactId) else onNavigateBack()
    }

    // ── Dialogs ──────────────────────────────────────────────────────
    if (showAddRelation) {
        val relationRoles = listOf("Жена", "Муж", "Партнёр", "Мать", "Отец",
            "Сын", "Дочь", "Брат", "Сестра", "Родственник", "Друг", "Коллега")
        // Обратная роль по умолчанию (пользователь может изменить)
        val inverseRole: (String) -> String = {
            when (it) {
                "Жена" -> "Муж"; "Муж" -> "Жена"
                "Партнёр" -> "Партнёр"; "Друг" -> "Друг"; "Коллега" -> "Коллега"
                else -> "Родственник"
            }
        }
        var newRelSelected  by remember { mutableStateOf<Contact?>(null) }
        var newRelOtherRole by remember { mutableStateOf("Родственник") }
        var newRelMyRole    by remember { mutableStateOf("Родственник") }
        var myRoleTouched   by remember { mutableStateOf(false) }
        val candidates = AppStateStore.contacts.filter { c ->
            c.id != editedContactId &&
            contactRelationsDraft.none { it.firstContactId == c.id || it.secondContactId == c.id }
        }

        // Шаг 1: канонический пикер (шторка + поиск + аватары) — единый дизайн
        // «добавить человека», как в OverviewTab; старый вложенный AlertDialog убран.
        if (newRelSelected == null) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                title = stringResource(R.string.ce_add_person),
                items = candidates
                    .sortedBy { "${it.firstName} ${it.lastName}".trim().lowercase() }
                    .map { c ->
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                            id = c.id,
                            title = "${c.firstName} ${c.lastName}".trim(),
                            subtitle = c.nickname?.takeIf { it.isNotBlank() }?.let { "«$it»" }
                                ?: c.customRelationshipType?.takeIf { it.isNotBlank() }
                                ?: c.relationshipType.label(ctxLabel)
                        )
                    },
                onPick = { picked -> newRelSelected = AppStateStore.getContact(picked.id) },
                onDismiss = { showAddRelation = false },
                searchPlaceholder = stringResource(R.string.ce_search_by_name),
                emptyText = stringResource(R.string.ce_no_contacts_avail)
            )
        } else newRelSelected?.let { sel ->
            // Шаг 2: роли (кто он мне / кто я ему)
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = "${sel.firstName} ${sel.lastName}".trim(),
                onDismiss = { showAddRelation = false; newRelSelected = null },
                confirmText = stringResource(R.string.common_add),
                onConfirm = {
                    contactRelationsDraft = contactRelationsDraft + ContactRelation(
                        id              = UUID.randomUUID().toString(),
                        firstContactId  = editedContactId,
                        secondContactId = sel.id,
                        firstRole       = newRelMyRole,
                        secondRole      = newRelOtherRole
                    )
                    newRelSelected = null
                    showAddRelation = false
                },
                secondaryText = stringResource(R.string.common_back),
                onSecondary = { newRelSelected = null }
            ) {
                DropdownField(stringResource(R.string.ce_who_relation), newRelOtherRole, relationRoles) { v ->
                    newRelOtherRole = v
                    if (!myRoleTouched) newRelMyRole = inverseRole(v)
                }
                DropdownField(stringResource(R.string.ce_who_am_i), newRelMyRole, relationRoles) { v ->
                    newRelMyRole = v; myRoleTouched = true
                }
            }
        }
    }
    if (showAddPhone) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_add_phone),
            onDismiss = { showAddPhone = false; newPhone = "" },
            confirmText = stringResource(R.string.common_add),
            onConfirm = {
                if (newPhone.isNotBlank()) {
                    phones = phones + ContactPhone(UUID.randomUUID().toString(), editedContactId, newPhone.trim(), newPhoneType, phones.isEmpty())
                    newPhone = ""; showAddPhone = false
                }
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddPhone = false; newPhone = "" }
        ) {
            OutlinedTextField(value = newPhone, onValueChange = { newPhone = it }, keyboardOptions = PhoneKeyboard, label = { Text(stringResource(R.string.ce_number)) }, modifier = Modifier.fillMaxWidth())
            DropdownField(stringResource(R.string.ce_type), newPhoneType.label(ctxLabel), PhoneType.values().map { it.label(ctxLabel) }) { v -> newPhoneType = PhoneType.values().firstOrNull { it.label(ctxLabel) == v } ?: PhoneType.MOBILE }
        }
    }

    if (showAddEmail) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_add_email),
            onDismiss = { showAddEmail = false; newEmail = "" },
            confirmText = stringResource(R.string.common_add),
            onConfirm = {
                if (newEmail.isNotBlank()) {
                    emails = emails + ContactEmail(UUID.randomUUID().toString(), editedContactId, newEmail.trim(), newEmailType, emails.isEmpty())
                    newEmail = ""; showAddEmail = false
                }
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddEmail = false; newEmail = "" }
        ) {
            OutlinedTextField(value = newEmail, onValueChange = { newEmail = it }, keyboardOptions = EmailKeyboard, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            DropdownField(stringResource(R.string.ce_type), newEmailType.label(ctxLabel), EmailType.values().map { it.label(ctxLabel) }) { v -> newEmailType = EmailType.values().firstOrNull { it.label(ctxLabel) == v } ?: EmailType.PERSONAL }
        }
    }

    if (showAddMessenger) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_add_messenger),
            onDismiss = { showAddMessenger = false; newMessenger = "" },
            confirmText = stringResource(R.string.common_add),
            onConfirm = {
                if (newMessenger.isNotBlank()) {
                    messengers = messengers + Messenger(UUID.randomUUID().toString(), editedContactId, newMessengerType, newMessenger.trim(), null, messengers.isEmpty())
                    newMessenger = ""; showAddMessenger = false
                }
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddMessenger = false; newMessenger = "" }
        ) {
            Text(stringResource(R.string.ce_platform), style = MaterialTheme.typography.labelMedium, color = AppleTheme.colors.secondaryLabel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MessengerType.values().forEach { mt ->
                    FilterChip(
                        selected = newMessengerType == mt,
                        onClick = { newMessengerType = mt },
                        label = { Text(mt.label(ctxLabel)) }
                    )
                }
            }
            OutlinedTextField(value = newMessenger, onValueChange = { newMessenger = it }, label = { Text(stringResource(R.string.ce_username_number)) }, modifier = Modifier.fillMaxWidth())
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    if (isEditMode) stringResource(R.string.ce_edit) else stringResource(R.string.ce_new_contact),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label
                )
                Button(
                    onClick = ::buildAndSave,
                    enabled = firstName.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White),
                    modifier = Modifier.height(34.dp)
                ) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold) }
            }

            // ── Фото по центру (как в макете) ─────────────────────────
            // Рабочий выбор фото: системный фото-пикер → копия в filesDir
            // (URI пикера временный) → photoUri в состоянии/сохранении.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var showPhotoMenu by remember { mutableStateOf(false) }
                val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        com.aistudio.socialsphere.crmlxb.utils.PhotoStorage
                            .saveContactPhoto(ctxLabel, uri, editedContactId)
                            ?.let { photoUri = it }
                    }
                }
                fun launchPhotoPicker() = photoPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
                Box(contentAlignment = Alignment.BottomEnd) {
                    val photoFile = photoUri?.let { java.io.File(it) }?.takeIf { it.exists() }
                    Box(
                        modifier = Modifier.size(76.dp).clip(CircleShape)
                            .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                            .clickable { if (photoFile != null) showPhotoMenu = true else launchPhotoPicker() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photoFile != null) {
                            coil.compose.AsyncImage(
                                model = photoFile, contentDescription = stringResource(R.string.ce_add_photo),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(76.dp).clip(CircleShape)
                            )
                        } else {
                            val initial = (firstName.firstOrNull() ?: lastName.firstOrNull())?.uppercaseChar()
                            if (initial != null) {
                                Text(initial.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AppleTheme.colors.brand)
                            } else {
                                Icon(Icons.Default.Person, null, Modifier.size(36.dp), tint = AppleTheme.colors.brand.copy(alpha = 0.6f))
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(AppleTheme.colors.brand)
                            .clickable { if (photoFile != null) showPhotoMenu = true else launchPhotoPicker() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (photoFile != null) Icons.Default.Edit else Icons.Default.Add,
                            null, Modifier.size(14.dp), tint = Color.White)
                    }
                    DropdownMenu(expanded = showPhotoMenu, onDismissRequest = { showPhotoMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ce_photo_replace)) },
                            onClick = { showPhotoMenu = false; launchPhotoPicker() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ce_photo_remove), color = AppleTheme.colors.red) },
                            onClick = {
                                showPhotoMenu = false
                                com.aistudio.socialsphere.crmlxb.utils.PhotoStorage
                                    .deleteContactPhotos(ctxLabel, editedContactId)
                                photoUri = null
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (photoUri == null) stringResource(R.string.ce_add_photo)
                    else stringResource(R.string.ce_photo_replace),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppleTheme.colors.brand,
                    modifier = Modifier.clickable { if (photoUri != null) showPhotoMenu = true else launchPhotoPicker() }
                )
            }

            // ── Полная структура имени как в Android-контактах (v13): приставка/
            // имя/фамилия/отчество/суффикс/фонетика — владелец попросил «хочу как
            // в андроид, идентично» (5 полей структуры + 2 фонетических). ──
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Приставка (Dr./г-н) — отдельная карточка, как в андроид-редакторе
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    BareFieldColumn(
                        label = stringResource(R.string.ce_name_prefix), value = namePrefix,
                        onValueChange = { namePrefix = it }, keyboardOptions = CapWords,
                        placeholder = stringResource(R.string.ce_name_prefix_hint),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        BareFieldColumn(
                            label = stringResource(R.string.ce_name_req), value = firstName,
                            onValueChange = { firstName = it }, keyboardOptions = CapWords,
                            modifier = Modifier.weight(1f).padding(12.dp)
                        )
                        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(AppleTheme.colors.separator))
                        BareFieldColumn(
                            label = stringResource(R.string.ce_surname), value = lastName,
                            onValueChange = { lastName = it }, keyboardOptions = CapWords,
                            modifier = Modifier.weight(1f).padding(12.dp)
                        )
                    }
                }
                // Отчество — как в телефонной книге (импортируется из vCard/устройства)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    BareFieldColumn(
                        label = stringResource(R.string.ce_middle_name), value = middleName,
                        onValueChange = { middleName = it }, keyboardOptions = CapWords,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                // Суффикс (мл./ст.) — как в андроид-редакторе, идёт после отчества/фамилии
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    BareFieldColumn(
                        label = stringResource(R.string.ce_name_suffix), value = nameSuffix,
                        onValueChange = { nameSuffix = it }, keyboardOptions = CapWords,
                        placeholder = stringResource(R.string.ce_name_suffix_hint),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    BareFieldColumn(
                        label = stringResource(R.string.ce_nickname), value = nickname,
                        onValueChange = { nickname = it }, keyboardOptions = CapWords,
                        placeholder = stringResource(R.string.ce_nickname_hint),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                // Фонетические имя/фамилия — для языков вроде японского, где
                // произношение не следует из письменной формы (как в андроид)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        BareFieldColumn(
                            label = stringResource(R.string.ce_phonetic_first_name), value = phoneticFirstName,
                            onValueChange = { phoneticFirstName = it }, keyboardOptions = CapWords,
                            placeholder = stringResource(R.string.ce_phonetic_hint),
                            modifier = Modifier.weight(1f).padding(12.dp)
                        )
                        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(AppleTheme.colors.separator))
                        BareFieldColumn(
                            label = stringResource(R.string.ce_phonetic_last_name), value = phoneticLastName,
                            onValueChange = { phoneticLastName = it }, keyboardOptions = CapWords,
                            placeholder = stringResource(R.string.ce_phonetic_hint),
                            modifier = Modifier.weight(1f).padding(12.dp)
                        )
                    }
                }
            }

            // ── Тип отношений (пилюли + свой вариант, как договорено) ──
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AureliaCaption(stringResource(R.string.ce_relation_type))
                val customLabel = customRelationType.trim()
                val addOwnLabel = stringResource(R.string.ce_relation_custom_add)
                PillChoiceRow(
                    // Если свой тип задан — он в списке и выбран; последняя пилюля «+ Свой»
                    options = RelationshipType.values().map { it.label(ctxLabel) } +
                        listOfNotNull(customLabel.takeIf { it.isNotBlank() }) +
                        listOf(addOwnLabel),
                    selected = customLabel.ifBlank { relationshipType.label(ctxLabel) },
                    onSelect = { v ->
                        when {
                            v == addOwnLabel -> showCustomRelDialog = true
                            v == customLabel && customLabel.isNotBlank() -> Unit // уже выбран
                            else -> RelationshipType.values().firstOrNull { it.label(ctxLabel) == v }?.let {
                                relationshipType = it
                                customRelationType = "" // стандартный выбор очищает свой
                            }
                        }
                    }
                )
            }
            if (showCustomRelDialog) {
                var draft by remember { mutableStateOf(customRelationType) }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                    title = stringResource(R.string.ce_relation_custom_title),
                    onDismiss = { showCustomRelDialog = false },
                    confirmText = stringResource(R.string.common_save),
                    confirmEnabled = draft.isNotBlank(),
                    onConfirm = {
                        customRelationType = draft.trim()
                        relationshipType = RelationshipType.OTHER
                        showCustomRelDialog = false
                    },
                    secondaryText = stringResource(R.string.common_cancel),
                    onSecondary = { showCustomRelDialog = false }
                ) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, keyboardOptions = CapSentences,
                        label = { Text(stringResource(R.string.ce_relation_custom_hint)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            }

            // ── Важность (пилюли, ключевой — золотом) ─────────────────
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AureliaCaption(stringResource(R.string.ce_importance))
                PillChoiceRow(
                    options = ImportanceLevel.values().map { it.label(ctxLabel) },
                    selected = importanceLevel.label(ctxLabel),
                    onSelect = { v -> importanceLevel = ImportanceLevel.values().firstOrNull { it.label(ctxLabel) == v } ?: importanceLevel },
                    goldFor = setOf(ImportanceLevel.KEY.label(ctxLabel))
                )
            }

            // ── Телефон ───────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AureliaCaption(stringResource(R.string.ce_phones))
                    Text(
                        "+ " + stringResource(R.string.common_add), color = AppleTheme.colors.brand,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clickable { showAddPhone = true }
                    )
                }
                if (phones.isEmpty()) {
                    Text(stringResource(R.string.ce_no_phones), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                } else {
                    phones.forEachIndexed { idx, phone ->
                        ContactItemRow(
                            icon = Icons.Default.Call, iconTint = AppleTheme.colors.brand, iconBg = AppleTheme.colors.brand.copy(alpha = 0.10f),
                            value = phone.number, onValueChange = { num -> phones = phones.toMutableList().also { it[idx] = phone.copy(number = num) } },
                            keyboardOptions = PhoneKeyboard, label = phone.type.label(ctxLabel),
                            isPrimary = phone.isPrimary, onTogglePrimary = { phones = phones.mapIndexed { i, p -> p.copy(isPrimary = i == idx) } },
                            onDelete = { phones = phones.toMutableList().also { it.removeAt(idx) } }
                        )
                    }
                }
            }

            // ── Email ─────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AureliaCaption("Email")
                    Text(
                        "+ " + stringResource(R.string.common_add), color = AppleTheme.colors.brand,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clickable { showAddEmail = true }
                    )
                }
                if (emails.isEmpty()) {
                    Text(stringResource(R.string.ce_no_email), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                } else {
                    emails.forEachIndexed { idx, email ->
                        ContactItemRow(
                            icon = Icons.Default.Email, iconTint = AureliaTheme.colors.gold, iconBg = AureliaTheme.colors.gold.copy(alpha = 0.14f),
                            value = email.email, onValueChange = { v -> emails = emails.toMutableList().also { it[idx] = email.copy(email = v) } },
                            keyboardOptions = EmailKeyboard, label = email.type.label(ctxLabel),
                            isPrimary = email.isPrimary, onTogglePrimary = { emails = emails.mapIndexed { i, e -> e.copy(isPrimary = i == idx) } },
                            onDelete = { emails = emails.toMutableList().also { it.removeAt(idx) } }
                        )
                    }
                }
            }

            // ── Мессенджеры ───────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AureliaCaption(stringResource(R.string.ce_messengers))
                    Text(
                        "+ " + stringResource(R.string.common_add), color = AppleTheme.colors.brand,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clickable { showAddMessenger = true }
                    )
                }
                if (messengers.isEmpty()) {
                    Text(stringResource(R.string.ce_no_messengers), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                } else {
                    messengers.forEachIndexed { idx, m ->
                        ContactItemRow(
                            icon = Icons.AutoMirrored.Filled.Send, iconTint = AppleTheme.colors.red, iconBg = AppleTheme.colors.red.copy(alpha = 0.10f),
                            value = m.value, onValueChange = { v -> messengers = messengers.toMutableList().also { it[idx] = m.copy(value = v) } },
                            label = m.type.label(ctxLabel),
                            onDelete = { messengers = messengers.toMutableList().also { it.removeAt(idx) } }
                        )
                    }
                }
            }

            // ── Company ───────────────────────────────────────────────
            SectionCard(stringResource(R.string.ce_addresses)) {
                var showAddrDialog by remember { mutableStateOf(false) }
                var editingAddr by remember { mutableStateOf<Address?>(null) }
                if (draftAddresses.isEmpty()) {
                    Text(
                        stringResource(R.string.ce_address_after_save),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel
                    )
                }
                draftAddresses.forEach { addr ->
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
                        IconButton(onClick = { draftAddresses = draftAddresses - addr }) {
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
                    // ЕДИНЫЙ диалог адреса (AddressComponents.kt) — та же реализация,
                    // что на вкладке Связь; геокодинг внутри компонента.
                    AddressEditDialog(
                        base = editingAddr,
                        ownerId = editedContactId,
                        scope = scope,
                        onDismiss = { showAddrDialog = false; editingAddr = null },
                        onCommit = { a ->
                            draftAddresses = if (draftAddresses.none { it.id == a.id }) draftAddresses + a
                                else draftAddresses.map { if (it.id == a.id) a else it }
                        },
                        onGeocoded = { a ->
                            draftAddresses = draftAddresses.map { if (it.id == a.id) a else it }
                        }
                    )
                }
            }

            SectionCard(stringResource(R.string.ce_company_work)) {
                // Профессия БЕЗ компании (фидбэк владельца 2026-07-03: «не могу
                // добавить профессию, не указав компанию») — поле видно всегда.
                OutlinedTextField(
                    value = profession, onValueChange = { profession = it },
                    keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_profession)) },
                    placeholder = { Text(stringResource(R.string.ce_profession_hint)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small
                )
                var showCompanyDropdown by remember { mutableStateOf(false) }
                var showNewCompanyDialog by remember { mutableStateOf(false) }
                val companies = AppStateStore.companies
                val selectedCompanyName = companies.find { it.id == selectedCompanyId }?.name ?: ""

                OutlinedTextField(
                    value = selectedCompanyName, onValueChange = {},
                    label = { Text(stringResource(R.string.ce_company)) },
                    modifier = Modifier.fillMaxWidth().clickable { showCompanyDropdown = true },
                    enabled = false, shape = SocialShape.Small,
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = AppleTheme.colors.label,
                        disabledBorderColor = AppleTheme.colors.tertiaryLabel,
                        disabledLabelColor = AppleTheme.colors.secondaryLabel
                    )
                )
                // Выбор компании — канонический пикер с поиском, строкой «создать»
                // и пунктом «Без компании» (сброс) первой строкой списка.
                if (showCompanyDropdown) {
                    val noCompanyLabel = stringResource(R.string.ce_no_company)
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                        title = stringResource(R.string.ce_company),
                        items = buildList {
                            if (selectedCompanyId.isNotBlank()) add(
                                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                                    id = "", title = noCompanyLabel
                                )
                            )
                            companies.sortedBy { it.name.lowercase() }.forEach { company ->
                                add(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                                    id = company.id,
                                    title = company.name,
                                    subtitle = company.industry.label(ctxLabel),
                                    isCompany = true
                                ))
                            }
                        },
                        onPick = { picked ->
                            selectedCompanyId = picked.id
                            showCompanyDropdown = false
                        },
                        onDismiss = { showCompanyDropdown = false },
                        searchPlaceholder = stringResource(R.string.ce_search_company),
                        emptyText = stringResource(R.string.picker_no_results),
                        createNewText = stringResource(R.string.ce_new_company),
                        onCreateNew = { showCompanyDropdown = false; showNewCompanyDialog = true }
                    )
                }
                if (showNewCompanyDialog) {
                    var newCompanyName by remember { mutableStateOf("") }
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.ce_new_company_title),
                        onDismiss = { showNewCompanyDialog = false },
                        confirmText = stringResource(R.string.ce_create),
                        confirmEnabled = newCompanyName.isNotBlank(),
                        onConfirm = {
                            val clean = newCompanyName.trim()
                            // Дедуп: компания с таким именем уже есть — выбираем её
                            val existing = AppStateStore.companies
                                .find { it.name.equals(clean, ignoreCase = true) }
                            if (existing != null) {
                                selectedCompanyId = existing.id
                            } else {
                                val now = java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                val company = Company(
                                    id = UUID.randomUUID().toString(),
                                    name = clean,
                                    industry = Industry.OTHER,
                                    createdAt = now,
                                    updatedAt = now
                                )
                                AppStateStore.addCompany(company)
                                selectedCompanyId = company.id
                            }
                            showNewCompanyDialog = false
                        },
                        secondaryText = stringResource(R.string.common_cancel),
                        onSecondary = { showNewCompanyDialog = false }
                    ) {
                        OutlinedTextField(
                            value = newCompanyName,
                            onValueChange = { newCompanyName = it }, keyboardOptions = CapWords,
                            label = { Text(stringResource(R.string.ce_company_name_req)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = SocialShape.Small
                        )
                    }
                }
                if (selectedCompanyId.isNotBlank()) {
                    OutlinedTextField(value = companyPosition, onValueChange = { companyPosition = it }, keyboardOptions = CapSentences, label = { Text(stringResource(R.string.ce_position)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                    OutlinedTextField(value = companyDept,     onValueChange = { companyDept     = it }, keyboardOptions = CapSentences, label = { Text(stringResource(R.string.ce_department)) },    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                    OutlinedTextField(value = workNote,        onValueChange = { workNote        = it }, keyboardOptions = CapSentences, label = { Text(stringResource(R.string.ce_work_note)) }, modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small)
                }
            }

            // ── Семья и отношения (ТЗ v2.0, секция 6) ────────────────
            SectionCard(stringResource(R.string.ce_family_relations)) {
                if (contactRelationsDraft.isEmpty()) {
                    Text(stringResource(R.string.ce_no_related),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                }
                contactRelationsDraft.forEach { rel ->
                    val isFirst   = rel.firstContactId == editedContactId
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    val otherRole = if (isFirst) rel.secondRole else rel.firstRole
                    val otherName = AppStateStore.getContact(otherId)
                        ?.let { "${it.firstName} ${it.lastName}".trim() } ?: stringResource(R.string.ce_contact_deleted)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(otherName, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(otherRole, style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel)
                        }
                        IconButton(onClick = {
                            contactRelationsDraft = contactRelationsDraft.filter { it.id != rel.id }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ce_remove_relation),
                                tint = AppleTheme.colors.red,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
                TextButton(onClick = { showAddRelation = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ce_add_person))
                }
            }

            // ── Теги ──────────────────────────────────────────────────
            SectionCard(stringResource(R.string.ce_tags)) {
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
                        label         = { Text(stringResource(R.string.ce_new_tag)) },
                        modifier      = Modifier.weight(1f),
                        singleLine    = true,
                        shape         = SocialShape.Small,
                        placeholder   = { Text(stringResource(R.string.ce_tag_hint)) }
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
                        Icon(Icons.Default.Add, null, tint = AppleTheme.colors.brand)
                    }
                }
                Text(
                    stringResource(R.string.ce_remove_tag_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppleTheme.colors.separator
                )
            }

            // ── Где познакомились ─────────────────────────────────────
            SectionCard(stringResource(R.string.ce_where_met)) {
                OutlinedTextField(
                    value = meetContext, onValueChange = { meetContext = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_meet_context)) },
                    placeholder = { Text(stringResource(R.string.ce_meet_context_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = meetDate, onValueChange = { meetDate = it },
                    label = { Text(stringResource(R.string.ce_meet_date)) },
                    placeholder = { Text("2024-11-15") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small,
                    singleLine = true
                )
            }

            // ── Следующий шаг и взаимная польза ──────────────────────
            SectionCard(stringResource(R.string.ce_next_step_benefit)) {
                OutlinedTextField(
                    value = nextStep, onValueChange = { nextStep = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_next_step)) },
                    placeholder = { Text(stringResource(R.string.ce_next_step_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = canHelpWith, onValueChange = { canHelpWith = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_can_help)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = iCanHelpWith, onValueChange = { iCanHelpWith = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_i_can_help)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = talkingPoints, onValueChange = { talkingPoints = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_talking_points)) },
                    placeholder = { Text(stringResource(R.string.ce_talking_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small,
                    minLines = 2
                )
            }

            // ── Классификация (тип отношений/важность — пилюлями наверху экрана) ──
            // Пикер «Статус» ВОЗВРАЩЁН (2026-07-03): владелец передумал после
            // удаления 2026-07-02 — «Поддерживать» = пометка «с кем нужно общаться».
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AureliaCaption(stringResource(R.string.filter_status))
                    PillChoiceRow(
                        options = ContactStatus.values().map { it.label(ctxLabel) },
                        selected = contactStatus.label(ctxLabel),
                        onSelect = { v -> contactStatus = ContactStatus.values().firstOrNull { it.label(ctxLabel) == v } ?: contactStatus }
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AureliaCaption(stringResource(R.string.ce_social_role))
                    PillChoiceRow(
                        options = SocialRole.values().map { it.label(ctxLabel) },
                        selected = socialRole.label(ctxLabel),
                        onSelect = { v -> socialRole = SocialRole.values().firstOrNull { it.label(ctxLabel) == v } ?: socialRole }
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AureliaCaption(stringResource(R.string.ce_rhythm))
                    // CUSTOM исключён: у контакта нет поля своих дней — выбор молча отключал бы отслеживание
                    PillChoiceRow(
                        options = CommunicationRhythm.values().filter { it != CommunicationRhythm.CUSTOM }.map { it.label(ctxLabel) },
                        selected = communicationRhythm.label(ctxLabel),
                        onSelect = { v -> communicationRhythm = CommunicationRhythm.values().firstOrNull { it.label(ctxLabel) == v } ?: communicationRhythm }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
            HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
            content()
        }
    }
}

/** Подпись-капс над секцией (как в макете: «ТЕЛЕФОН», «EMAIL»). */
@Composable
fun AureliaCaption(text: String) {
    // Тонкий алиас канонической caps-подписи из общего слоя
    // (ui/theme/AureliaComponents.kt) — единая реализация на всё приложение.
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel(
        text, color = AppleTheme.colors.secondaryLabel
    )
}

/** Поле без рамки внутри общей карточки (для парных имя/фамилия и прозвища). */
@Composable
fun BareFieldColumn(
    label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    placeholder: String? = null,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange, keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppleTheme.colors.label, fontWeight = FontWeight.SemiBold),
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleTheme.colors.brand),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = AppleTheme.colors.tertiaryLabel)
                }
                inner()
            }
        )
    }
}

/**
 * Ряд-пилюли для короткого enum-выбора (тип отношений, важность, ритм и т.п.).
 * [goldFor] — набор лейблов, которые при выборе подсвечиваются золотом+звездой
 * (используется для верхнего уровня важности «Ключевой»), остальные — заливка брендом.
 */
@Composable
fun PillChoiceRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    goldFor: Set<String> = emptySet(),
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSel = opt == selected
            val isGold = isSel && opt in goldFor
            // Яркое золото важности (importanceKey) вместо бледного базового
            val goldTone = AppleTheme.colors.importanceKey
            val bg = when { isGold -> goldTone.copy(alpha = 0.14f); isSel -> AppleTheme.colors.brand; else -> AppleTheme.colors.card }
            val borderColor = when { isGold -> goldTone; isSel -> AppleTheme.colors.brand; else -> AppleTheme.colors.separator }
            val textColor = when { isGold -> goldTone; isSel -> Color.White; else -> AppleTheme.colors.label }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(bg)
                    .border(1.dp, borderColor, RoundedCornerShape(percent = 50))
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isGold) Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = AureliaTheme.colors.gold)
                Text(opt, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, color = textColor)
            }
        }
    }
}

/**
 * Строка контактного метода (телефон/email/мессенджер) с иконкой-плиткой —
 * как в макете. Сохраняет прежнее поведение: инлайн-правка значения,
 * необязательный переключатель «основной» (звезда) и удаление.
 */
@Composable
fun ContactItemRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    isPrimary: Boolean? = null,
    onTogglePrimary: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14)
            .background(AppleTheme.colors.card)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R10).background(iconBg),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(18.dp), tint = iconTint) }
        OutlinedTextField(
            value = value, onValueChange = onValueChange, keyboardOptions = keyboardOptions,
            label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent
            )
        )
        if (isPrimary == true && onTogglePrimary == null) {
            // Статичный индикатор «основной» без возможности переключить (как было раньше)
            Icon(Icons.Default.Star, contentDescription = stringResource(R.string.ce_make_primary),
                modifier = Modifier.size(16.dp).padding(end = 6.dp), tint = AppleTheme.colors.brand)
        } else if (isPrimary != null && onTogglePrimary != null) {
            IconButton(onClick = onTogglePrimary, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (isPrimary) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.ce_make_primary),
                    modifier = Modifier.size(16.dp),
                    tint = if (isPrimary) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.common_delete), Modifier.size(16.dp), tint = AppleTheme.colors.red)
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
                disabledTextColor = AppleTheme.colors.label,
                disabledBorderColor = AppleTheme.colors.tertiaryLabel,
                disabledLabelColor = AppleTheme.colors.secondaryLabel
            )
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
            }
        }
    }
}

internal val CapWords = androidx.compose.foundation.text.KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words
)
internal val CapSentences = androidx.compose.foundation.text.KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
)
// Цифровая раскладка телефона (+ * # и цифры) и почтовая (@, без автокапитализации)
// для соответствующих полей ввода — как в макете.
internal val PhoneKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
)
internal val EmailKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
)
