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
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
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
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 2 — СВЯЗЬ (каналы коммуникации)
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.communicationTab(contact: Contact, ctxLabel: android.content.Context, editing: Boolean = false, onEditingChange: (Boolean) -> Unit = {}) {
    // Кнопка «Изменить»/«Готово» этой вкладки убрана — режим правки теперь
    // включается ОДНОЙ кнопкой в шапке карточки контакта, общей на все вкладки.
    // Каналы связи — телефон/email/мессенджеры объединены в одну карточку
    // (как в макете), а не 3 отдельные. Логика/диалоги/состояния каждого типа
    // не менялись — изменилась только визуальная группировка.
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val phones = contact.phones.distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
        if (phones.isNotEmpty() || contact.emails.isNotEmpty() || contact.messengers.isNotEmpty() || editing) {
            CardBlock(title = stringResource(R.string.cd_comm_channels)) {
                var editPhone by remember { mutableStateOf<ContactPhone?>(null) }
                var showPhoneDialog by remember { mutableStateOf(false) }
                var pendingRemovePhone by remember { mutableStateOf<ContactPhone?>(null) }

                phones.forEach { phone ->
                    ChannelRow(
                        icon = Icons.Outlined.Phone, iconTint = AppleTheme.colors.brand, iconBg = AppleTheme.colors.brand.copy(alpha = 0.10f),
                        value = phone.number, subtitle = phone.type.label(ctxLabel), isPrimary = phone.isPrimary,
                        editing = editing,
                        onClick = { editPhone = phone; showPhoneDialog = true },
                        onRemove = { pendingRemovePhone = phone }
                    ) {
                        ActionSquare(Icons.Outlined.Phone, stringResource(R.string.cd_call)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(ctx, phone.number) }
                        ActionSquare(Icons.Default.Sms, stringResource(R.string.cd_write)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(ctx, phone.number) }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                if (editing) {
                    TextButton(onClick = { editPhone = null; showPhoneDialog = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_phone))
                    }
                }

                if (showPhoneDialog) {
                    val base = editPhone
                    var num by remember { mutableStateOf(base?.number ?: "") }
                    var pType by remember { mutableStateOf(base?.type ?: PhoneType.MOBILE) }
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(if (base == null) R.string.cd_add_phone else R.string.ce_edit_phone),
                        onDismiss = { showPhoneDialog = false; editPhone = null },
                        confirmText = stringResource(if (base == null) R.string.common_add else R.string.common_save),
                        confirmEnabled = num.isNotBlank(),
                        onConfirm = {
                            val list = contact.phones.toMutableList()
                            if (base == null) {
                                list.add(ContactPhone(java.util.UUID.randomUUID().toString(), contact.id, num.trim(), pType, list.isEmpty()))
                            } else {
                                val i = list.indexOfFirst { it.id == base.id }
                                if (i >= 0) list[i] = base.copy(number = num.trim(), type = pType)
                            }
                            AppStateStore.updateContact(contact.copy(phones = list))
                            showPhoneDialog = false; editPhone = null
                        }
                    ) {
                        OutlinedTextField(value = num, onValueChange = { num = it }, keyboardOptions = PhoneKeyboard, label = { Text(stringResource(R.string.ce_number)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        DropdownField(stringResource(R.string.ce_type), pType.label(ctxLabel), PhoneType.values().map { it.label(ctxLabel) }) { v -> pType = PhoneType.values().firstOrNull { it.label(ctxLabel) == v } ?: pType }
                    }
                }
                pendingRemovePhone?.let { rp ->
                    AlertDialog(
                        onDismissRequest = { pendingRemovePhone = null },
                        title = { Text(stringResource(R.string.ce_remove_phone_q), fontWeight = FontWeight.Bold) },
                        text = { Text(rp.number) },
                        confirmButton = {
                            Button(onClick = {
                                AppStateStore.updateContact(contact.copy(phones = contact.phones.filter { it.id != rp.id }))
                                pendingRemovePhone = null
                            }) { Text(stringResource(R.string.common_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { pendingRemovePhone = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
                }

                var editEmail by remember { mutableStateOf<ContactEmail?>(null) }
                var showEmailDialog by remember { mutableStateOf(false) }
                var pendingRemoveEmail by remember { mutableStateOf<ContactEmail?>(null) }

                contact.emails.forEach { email ->
                    ChannelRow(
                        icon = Icons.Outlined.Email, iconTint = AureliaTheme.colors.gold, iconBg = AureliaTheme.colors.gold.copy(alpha = 0.14f),
                        value = email.email, subtitle = email.type.label(ctxLabel), isPrimary = email.isPrimary,
                        editing = editing,
                        onClick = { editEmail = email; showEmailDialog = true },
                        onRemove = { pendingRemoveEmail = email }
                    ) {
                        ActionSquare(Icons.Outlined.Email, stringResource(R.string.cd_email_action)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(ctx, email.email) }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                if (editing) {
                    TextButton(onClick = { editEmail = null; showEmailDialog = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_email))
                    }
                }

                if (showEmailDialog) {
                    val base = editEmail
                    var addr by remember { mutableStateOf(base?.email ?: "") }
                    var eType by remember { mutableStateOf(base?.type ?: EmailType.PERSONAL) }
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(if (base == null) R.string.cd_add_email else R.string.ce_edit_email),
                        onDismiss = { showEmailDialog = false; editEmail = null },
                        confirmText = stringResource(if (base == null) R.string.common_add else R.string.common_save),
                        confirmEnabled = addr.isNotBlank(),
                        onConfirm = {
                            val list = contact.emails.toMutableList()
                            if (base == null) {
                                list.add(ContactEmail(java.util.UUID.randomUUID().toString(), contact.id, addr.trim(), eType, list.isEmpty()))
                            } else {
                                val i = list.indexOfFirst { it.id == base.id }
                                if (i >= 0) list[i] = base.copy(email = addr.trim(), type = eType)
                            }
                            AppStateStore.updateContact(contact.copy(emails = list))
                            showEmailDialog = false; editEmail = null
                        }
                    ) {
                        OutlinedTextField(value = addr, onValueChange = { addr = it }, keyboardOptions = EmailKeyboard, label = { Text(stringResource(R.string.ce_email)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        DropdownField(stringResource(R.string.ce_type), eType.label(ctxLabel), EmailType.values().map { it.label(ctxLabel) }) { v -> eType = EmailType.values().firstOrNull { it.label(ctxLabel) == v } ?: eType }
                    }
                }
                pendingRemoveEmail?.let { re ->
                    AlertDialog(
                        onDismissRequest = { pendingRemoveEmail = null },
                        title = { Text(stringResource(R.string.ce_remove_email_q), fontWeight = FontWeight.Bold) },
                        text = { Text(re.email) },
                        confirmButton = {
                            Button(onClick = {
                                AppStateStore.updateContact(contact.copy(emails = contact.emails.filter { it.id != re.id }))
                                pendingRemoveEmail = null
                            }) { Text(stringResource(R.string.common_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { pendingRemoveEmail = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
                }

                var editMsg by remember { mutableStateOf<Messenger?>(null) }
                var showMsgDialog by remember { mutableStateOf(false) }
                var pendingRemoveMsg by remember { mutableStateOf<Messenger?>(null) }

                contact.messengers.forEach { m ->
                    ChannelRow(
                        icon = Icons.AutoMirrored.Filled.Send, iconTint = AppleTheme.colors.red, iconBg = AppleTheme.colors.red.copy(alpha = 0.10f),
                        value = m.value,
                        subtitle = m.type.label(ctxLabel) + (m.comment?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        isPrimary = m.isPrimary,
                        editing = editing,
                        onClick = { editMsg = m; showMsgDialog = true },
                        onRemove = { pendingRemoveMsg = m }
                    ) {
                        ActionSquare(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_write)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(ctx, m) }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                if (editing) {
                    TextButton(onClick = { editMsg = null; showMsgDialog = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_messenger))
                    }
                }

                if (showMsgDialog) {
                    val base = editMsg
                    var uname by remember { mutableStateOf(base?.value ?: "") }
                    var mType by remember { mutableStateOf(base?.type ?: MessengerType.TELEGRAM) }
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(if (base == null) R.string.cd_add_messenger else R.string.ce_edit_messenger),
                        onDismiss = { showMsgDialog = false; editMsg = null },
                        confirmText = stringResource(if (base == null) R.string.common_add else R.string.common_save),
                        confirmEnabled = uname.isNotBlank(),
                        onConfirm = {
                            val list = contact.messengers.toMutableList()
                            if (base == null) {
                                list.add(Messenger(id = java.util.UUID.randomUUID().toString(), contactId = contact.id, type = mType, value = uname.trim(), isPrimary = list.isEmpty()))
                            } else {
                                val i = list.indexOfFirst { it.id == base.id }
                                if (i >= 0) list[i] = base.copy(type = mType, value = uname.trim())
                            }
                            AppStateStore.updateContact(contact.copy(messengers = list))
                            showMsgDialog = false; editMsg = null
                        }
                    ) {
                        PillChoiceRow(
                            options = MessengerType.values().map { it.label(ctxLabel) },
                            selected = mType.label(ctxLabel),
                            onSelect = { v -> mType = MessengerType.values().firstOrNull { it.label(ctxLabel) == v } ?: mType }
                        )
                        OutlinedTextField(value = uname, onValueChange = { uname = it }, label = { Text(stringResource(R.string.ce_username)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
                pendingRemoveMsg?.let { rm ->
                    AlertDialog(
                        onDismissRequest = { pendingRemoveMsg = null },
                        title = { Text(stringResource(R.string.ce_remove_messenger_q), fontWeight = FontWeight.Bold) },
                        text = { Text(rm.value) },
                        confirmButton = {
                            Button(onClick = {
                                AppStateStore.updateContact(contact.copy(messengers = contact.messengers.filter { it.id != rm.id }))
                                pendingRemoveMsg = null
                            }) { Text(stringResource(R.string.common_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { pendingRemoveMsg = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
                }
            }
        }
    }

    // Addresses
    item {
        val workTypes = setOf(AddressType.WORK, AddressType.OFFICE, AddressType.BRANCH, AddressType.LEGAL)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val allAddrs = AppStateStore.addresses.filter { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
        val addresses = allAddrs.filter { it.addressType !in workTypes }
        if (addresses.isNotEmpty() || editing) {
            CardBlock(title = stringResource(R.string.cd_addresses)) {
                var editAddr by remember { mutableStateOf<Address?>(null) }
                var showAddrDialog by remember { mutableStateOf(false) }
                var pendingRemoveAddr by remember { mutableStateOf<Address?>(null) }
                // Скоуп уровня карточки: переживает закрытие диалога (геокод в фоне)
                val addrScope = rememberCoroutineScope()

                addresses.forEach { addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (editing) Modifier.clickable { editAddr = addr; showAddrDialog = true } else Modifier)
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOf(addr.addressLine, addr.city, addr.postalCode.orEmpty(), addr.country).filter { it.isNotBlank() }.joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(addr.addressType.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        if (editing) {
                            IconButton(onClick = { pendingRemoveAddr = addr }) {
                                Icon(Icons.Default.RemoveCircle, stringResource(R.string.common_delete), Modifier.size(20.dp), tint = AppleTheme.colors.red)
                            }
                        } else {
                            ActionSquare(Icons.Outlined.Map, stringResource(R.string.cd_map)) {
                                if (addr.latitude != null && addr.longitude != null)
                                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(ctx, addr.latitude, addr.longitude)
                                else
                                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(ctx, "${addr.addressLine}, ${addr.city}")
                            }
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                if (editing) {
                    TextButton(onClick = { editAddr = null; showAddrDialog = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cd_add_address))
                    }
                }

                if (showAddrDialog) {
                    // ЕДИНЫЙ диалог адреса (AddressComponents.kt) — со ВСЕМИ типами:
                    // смена «домашний → рабочий» переносит адрес на вкладку Работа.
                    AddressEditDialog(
                        base = editAddr,
                        ownerId = contact.id,
                        scope = addrScope,
                        onDismiss = { showAddrDialog = false; editAddr = null },
                        onCommit = { a ->
                            val updated = if (allAddrs.none { it.id == a.id }) allAddrs + a
                                          else allAddrs.map { if (it.id == a.id) a else it }
                            AppStateStore.updateContact(contact.copy(addresses = updated))
                        },
                        onGeocoded = { a ->
                            AppStateStore.getContact(contact.id)?.let { fresh ->
                                AppStateStore.updateContact(fresh.copy(
                                    addresses = fresh.addresses.map { if (it.id == a.id) a else it }
                                ))
                            }
                        }
                    )
                }
                pendingRemoveAddr?.let { ra ->
                    AlertDialog(
                        onDismissRequest = { pendingRemoveAddr = null },
                        title = { Text(stringResource(R.string.ce_remove_address_q), fontWeight = FontWeight.Bold) },
                        text = { Text(listOf(ra.addressLine, ra.city).filter { it.isNotBlank() }.joinToString(", ")) },
                        confirmButton = {
                            Button(onClick = {
                                AppStateStore.updateContact(contact.copy(addresses = allAddrs.filter { it.id != ra.id }))
                                pendingRemoveAddr = null
                            }) { Text(stringResource(R.string.common_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { pendingRemoveAddr = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
                }
            }
        }
    }

    // Запись в телефонную книгу: полный vCard (имя, ВСЕ телефоны/почты с главным,
    // адреса с типом, день рождения, заметки + app-поля, компания/должность,
    // мессенджеры) → системный импорт «Контакты». Раньше уходили только имя+
    // телефоны+почты (Insert-интент), остальное терялось.
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val scope = rememberCoroutineScope()
        Button(
            onClick = {
                scope.launch {
                    val file = com.aistudio.socialsphere.crmlxb.utils.ExportManager
                        .exportContactVCard(ctx, contact)
                    com.aistudio.socialsphere.crmlxb.utils.ExportManager
                        .openVcfInContacts(ctx, file)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp).padding(end = 6.dp))
            Text(stringResource(R.string.cd_save_to_phone))
        }
    }

    // Синхронизация с телефонной книгой (Фаза A: связать + тянуть, без записи в
    // книгу телефона). «Обновить из телефона» аддитивно подтягивает имя/тел/почты/
    // адреса связанного контакта, ничего не удаляя.
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val scope = rememberCoroutineScope()
        var showLink by remember { mutableStateOf(false) }
        var deviceList by remember { mutableStateOf<List<ImportContactCandidate>>(emptyList()) }
        var search by remember { mutableStateOf("") }

        fun loadDevices(thenShow: Boolean) {
            scope.launch {
                val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ContactImporter.getDeviceContacts(ctx)
                }
                deviceList = list
                if (thenShow) showLink = true
            }
        }
        val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) loadDevices(true) }
        fun ensureThen(show: Boolean) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) loadDevices(show)
            else permLauncher.launch(android.Manifest.permission.READ_CONTACTS)
        }

        fun pullFromPhone() {
            val id = contact.deviceContactId ?: return
            scope.launch {
                val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ContactImporter.getDeviceContacts(ctx)
                }
                val dev = list.firstOrNull { it.id == id }
                if (dev == null) {
                    // Связанный контакт исчез из телефонной книги — говорим, а не молчим
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.sync_pull_gone), android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                // Сравнение по нормализованным 10 цифрам (AppStateStore.phoneDigits) —
                // раньше было своё сравнение "только цифры" без отсечения кода
                // страны, из-за чего один и тот же номер в формате +7/8 считался
                // разным и добавлялся повторно (найдено при разборе жалобы 2026-07-04).
                val newPhones = dev.phones
                    .filter { d -> contact.phones.none { AppStateStore.phoneDigits(it.number) == AppStateStore.phoneDigits(d.number) } }
                    .map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = contact.id) }
                val newEmails = dev.emails
                    .filter { d -> contact.emails.none { it.email.equals(d.email, ignoreCase = true) } }
                    .map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = contact.id) }
                val newAddrs = dev.addresses
                    .filter { d -> contact.addresses.none { it.addressLine == d.addressLine && it.city == d.city } }
                    .map { it.copy(id = java.util.UUID.randomUUID().toString(), ownerId = contact.id, ownerType = AddressOwnerType.CONTACT) }
                // Заметка телефона — раньше подтягивалась только при массовом
                // импорте, точечная кнопка «Обновить из телефона» её игнорировала.
                val notePrefix = ctx.getString(R.string.imp_note_from_import, "")
                val newNote = dev.notes?.takeIf { it.isNotBlank() }
                    ?.takeIf { text -> contact.notes.none { it.text == notePrefix + text } }
                val hasContactUpdates = newPhones.isNotEmpty() || newEmails.isNotEmpty() || newAddrs.isNotEmpty() || newNote != null
                if (hasContactUpdates) {
                    val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    AppStateStore.updateContact(contact.copy(
                        firstName  = contact.firstName.ifBlank { dev.firstName },
                        lastName   = contact.lastName.ifBlank { dev.lastName },
                        middleName = contact.middleName ?: dev.middleName.ifBlank { null },
                        phones     = contact.phones + newPhones,
                        emails     = contact.emails + newEmails,
                        addresses  = contact.addresses + newAddrs,
                        notes      = if (newNote != null) contact.notes + Note(
                            id = java.util.UUID.randomUUID().toString(),
                            contactId = contact.id,
                            type = NoteType.GENERAL,
                            text = notePrefix + newNote,
                            isImportant = false,
                            createdAt = now, updatedAt = now
                        ) else contact.notes
                    ))
                }
                // Работа/должность — раньше точечная кнопка её игнорировала (фидбэк
                // 2026-07-04: «работу тоже подтянуть»), теперь та же функция, что
                // и у массового импорта (find-or-create компанию + связь, либо
                // заметка, если компании нет — без дублей при повторном вызове).
                val companyAdded = applyImportedCompany(dev.companyName, dev.jobTitle, contact.id, ctx)
                if (!hasContactUpdates && !companyAdded) {
                    // Раньше кнопка молчала и казалась неработающей — теперь честный ответ
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.sync_pull_nothing), android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.sync_pull_result, newPhones.size, newEmails.size, newAddrs.size, if (companyAdded) 1 else 0),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { ensureThen(true) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Link, null, Modifier.size(16.dp).padding(end = 4.dp))
                Text(
                    stringResource(
                        if (contact.deviceContactId == null) R.string.sync_link_phone
                        else R.string.sync_change_link
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (contact.deviceContactId != null) {
                OutlinedButton(onClick = { pullFromPhone() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Sync, null, Modifier.size(16.dp).padding(end = 4.dp))
                    Text(stringResource(R.string.sync_update_from_phone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (contact.deviceContactId != null) {
            Text(
                stringResource(R.string.sync_linked),
                style = MaterialTheme.typography.labelSmall,
                color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (showLink) {
            val filtered = deviceList.filter {
                "${it.firstName} ${it.lastName}".contains(search, ignoreCase = true)
            }
            AlertDialog(
                onDismissRequest = { showLink = false; search = "" },
                title = { Text(stringResource(R.string.sync_pick_contact), fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = search, onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text(stringResource(R.string.ce_search_contact)) }
                        )
                        filtered.take(20).forEach { d ->
                            Text(
                                "${d.firstName} ${d.lastName}".trim().ifBlank { d.phones.firstOrNull()?.number ?: "—" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        AppStateStore.updateContact(contact.copy(deviceContactId = d.id))
                                        showLink = false; search = ""
                                    }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showLink = false; search = "" }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
    }
}

/**
 * Строка канала связи с иконкой-плиткой (как в макете «Каналы связи»).
 * Просмотр: значение + подпись слева, действия (позвонить/написать) справа,
 * бейдж «Основной» если это главный контакт. Изменить: тап открывает диалог
 * правки, справа — кнопка удаления. Поведение идентично прежнему, изменена
 * только визуальная обёртка (иконка-плитка вместо голого текста).
 */
@Composable
private fun ChannelRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    iconBg: Color,
    value: String,
    subtitle: String,
    isPrimary: Boolean,
    editing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (editing) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(iconBg),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(18.dp), tint = iconTint) }
        Column(Modifier.weight(1f)) {
            // Одна строка всегда: раньше капсула «Основной» справа отжимала ширину
            // и длинный номер переносился на 2 строки — теперь признак «основной»
            // живёт в подписи («Мобильный · Основной»), номер получает всю ширину.
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                if (isPrimary) {
                    Text(" · ", style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                    Text(
                        stringResource(R.string.cd_primary_badge),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AureliaTheme.colors.gold
                    )
                }
            }
        }
        if (editing) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.RemoveCircle, stringResource(R.string.common_delete), Modifier.size(20.dp), tint = AppleTheme.colors.red)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}
