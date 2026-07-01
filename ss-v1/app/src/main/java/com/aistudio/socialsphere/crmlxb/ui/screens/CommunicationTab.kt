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
import com.aistudio.socialsphere.crmlxb.ui.components.TabEditBar
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 2 — СВЯЗЬ (каналы коммуникации)
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.communicationTab(contact: Contact, ctxLabel: android.content.Context, editing: Boolean = false, onEditingChange: (Boolean) -> Unit = {}) {
    item {
        TabEditBar(isEditing = editing, onEdit = { onEditingChange(true) }, onDone = { onEditingChange(false) })
    }
    // Phones
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val phones = contact.phones.distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
        if (phones.isNotEmpty() || editing) {
            CardBlock(title = stringResource(R.string.cd_phones)) {
                var editPhone by remember { mutableStateOf<ContactPhone?>(null) }
                var showPhoneDialog by remember { mutableStateOf(false) }
                var pendingRemovePhone by remember { mutableStateOf<ContactPhone?>(null) }

                phones.forEach { phone ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (editing) Modifier.clickable { editPhone = phone; showPhoneDialog = true } else Modifier)
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(phone.number, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(phone.type.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        if (editing) {
                            IconButton(onClick = { pendingRemovePhone = phone }) {
                                Icon(Icons.Default.RemoveCircle, stringResource(R.string.common_delete), Modifier.size(20.dp), tint = AppleTheme.colors.red)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionSquare(Icons.Outlined.Phone, stringResource(R.string.cd_call)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(ctx, phone.number) }
                                ActionSquare(Icons.Default.Sms, stringResource(R.string.cd_write)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(ctx, phone.number) }
                            }
                        }
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
                    AlertDialog(
                        onDismissRequest = { showPhoneDialog = false; editPhone = null },
                        title = { Text(stringResource(if (base == null) R.string.cd_add_phone else R.string.ce_edit_phone), fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = num, onValueChange = { num = it }, keyboardOptions = PhoneKeyboard, label = { Text(stringResource(R.string.ce_number)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                DropdownField(stringResource(R.string.ce_type), pType.label(ctxLabel), PhoneType.values().map { it.label(ctxLabel) }) { v -> pType = PhoneType.values().firstOrNull { it.label(ctxLabel) == v } ?: pType }
                            }
                        },
                        confirmButton = {
                            Button(enabled = num.isNotBlank(), onClick = {
                                val list = contact.phones.toMutableList()
                                if (base == null) {
                                    list.add(ContactPhone(java.util.UUID.randomUUID().toString(), contact.id, num.trim(), pType, list.isEmpty()))
                                } else {
                                    val i = list.indexOfFirst { it.id == base.id }
                                    if (i >= 0) list[i] = base.copy(number = num.trim(), type = pType)
                                }
                                AppStateStore.updateContact(contact.copy(phones = list))
                                showPhoneDialog = false; editPhone = null
                            }) { Text(stringResource(if (base == null) R.string.common_add else R.string.common_save)) }
                        },
                        dismissButton = { TextButton(onClick = { showPhoneDialog = false; editPhone = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
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
            }
        }
    }

    // Emails
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (contact.emails.isNotEmpty() || editing) {
            CardBlock(title = stringResource(R.string.cd_email_action)) {
                var editEmail by remember { mutableStateOf<ContactEmail?>(null) }
                var showEmailDialog by remember { mutableStateOf(false) }
                var pendingRemoveEmail by remember { mutableStateOf<ContactEmail?>(null) }

                contact.emails.forEach { email ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (editing) Modifier.clickable { editEmail = email; showEmailDialog = true } else Modifier)
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(email.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(email.type.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        if (editing) {
                            IconButton(onClick = { pendingRemoveEmail = email }) {
                                Icon(Icons.Default.RemoveCircle, stringResource(R.string.common_delete), Modifier.size(20.dp), tint = AppleTheme.colors.red)
                            }
                        } else {
                            ActionSquare(Icons.Outlined.Email, stringResource(R.string.cd_email_action)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(ctx, email.email) }
                        }
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
                    AlertDialog(
                        onDismissRequest = { showEmailDialog = false; editEmail = null },
                        title = { Text(stringResource(if (base == null) R.string.cd_add_email else R.string.ce_edit_email), fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = addr, onValueChange = { addr = it }, keyboardOptions = EmailKeyboard, label = { Text(stringResource(R.string.ce_email)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                DropdownField(stringResource(R.string.ce_type), eType.label(ctxLabel), EmailType.values().map { it.label(ctxLabel) }) { v -> eType = EmailType.values().firstOrNull { it.label(ctxLabel) == v } ?: eType }
                            }
                        },
                        confirmButton = {
                            Button(enabled = addr.isNotBlank(), onClick = {
                                val list = contact.emails.toMutableList()
                                if (base == null) {
                                    list.add(ContactEmail(java.util.UUID.randomUUID().toString(), contact.id, addr.trim(), eType, list.isEmpty()))
                                } else {
                                    val i = list.indexOfFirst { it.id == base.id }
                                    if (i >= 0) list[i] = base.copy(email = addr.trim(), type = eType)
                                }
                                AppStateStore.updateContact(contact.copy(emails = list))
                                showEmailDialog = false; editEmail = null
                            }) { Text(stringResource(if (base == null) R.string.common_add else R.string.common_save)) }
                        },
                        dismissButton = { TextButton(onClick = { showEmailDialog = false; editEmail = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
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
            }
        }
    }

    // Messengers
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (contact.messengers.isNotEmpty() || editing) {
            CardBlock(title = stringResource(R.string.cd_messengers)) {
                var editMsg by remember { mutableStateOf<Messenger?>(null) }
                var showMsgDialog by remember { mutableStateOf(false) }
                var pendingRemoveMsg by remember { mutableStateOf<Messenger?>(null) }

                contact.messengers.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (editing) Modifier.clickable { editMsg = m; showMsgDialog = true } else Modifier)
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(m.type.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                            val c = m.comment
                            if (!c.isNullOrBlank())
                                Text(c, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.separator)
                        }
                        if (editing) {
                            IconButton(onClick = { pendingRemoveMsg = m }) {
                                Icon(Icons.Default.RemoveCircle, stringResource(R.string.common_delete), Modifier.size(20.dp), tint = AppleTheme.colors.red)
                            }
                        } else {
                            ActionSquare(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_write)) { com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(ctx, m) }
                        }
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
                    AlertDialog(
                        onDismissRequest = { showMsgDialog = false; editMsg = null },
                        title = { Text(stringResource(if (base == null) R.string.cd_add_messenger else R.string.ce_edit_messenger), fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.ce_platform), style = MaterialTheme.typography.labelMedium, color = AppleTheme.colors.secondaryLabel)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MessengerType.values().forEach { mt ->
                                        FilterChip(selected = mType == mt, onClick = { mType = mt }, label = { Text(mt.label(ctxLabel)) })
                                    }
                                }
                                OutlinedTextField(value = uname, onValueChange = { uname = it }, label = { Text(stringResource(R.string.ce_username)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        },
                        confirmButton = {
                            Button(enabled = uname.isNotBlank(), onClick = {
                                val list = contact.messengers.toMutableList()
                                if (base == null) {
                                    list.add(Messenger(id = java.util.UUID.randomUUID().toString(), contactId = contact.id, type = mType, value = uname.trim(), isPrimary = list.isEmpty()))
                                } else {
                                    val i = list.indexOfFirst { it.id == base.id }
                                    if (i >= 0) list[i] = base.copy(type = mType, value = uname.trim())
                                }
                                AppStateStore.updateContact(contact.copy(messengers = list))
                                showMsgDialog = false; editMsg = null
                            }) { Text(stringResource(if (base == null) R.string.common_add else R.string.common_save)) }
                        },
                        dismissButton = { TextButton(onClick = { showMsgDialog = false; editMsg = null }) { Text(stringResource(R.string.common_cancel)) } }
                    )
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
                    val base = editAddr
                    var aLine by remember { mutableStateOf(base?.addressLine ?: "") }
                    var aCity by remember { mutableStateOf(base?.city ?: "") }
                    var aPostal by remember { mutableStateOf(base?.postalCode ?: "") }
                    var aCountry by remember { mutableStateOf(base?.country ?: "") }
                    var aType by remember { mutableStateOf(base?.addressType ?: AddressType.HOME) }
                    val typeOptions = AddressType.values().filter { it !in workTypes }
                    AlertDialog(
                        onDismissRequest = { showAddrDialog = false; editAddr = null },
                        title = { Text(stringResource(if (base == null) R.string.ce_address else R.string.ce_edit_address), fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = aLine, onValueChange = { aLine = it }, keyboardOptions = CapWords, label = { Text(stringResource(R.string.ce_street_req)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = aCity, onValueChange = { aCity = it }, keyboardOptions = CapWords, label = { Text(stringResource(R.string.ce_city)) }, modifier = Modifier.weight(1f), singleLine = true)
                                    OutlinedTextField(value = aPostal, onValueChange = { aPostal = it }, label = { Text(stringResource(R.string.ce_postal_code)) }, modifier = Modifier.weight(1f), singleLine = true)
                                }
                                OutlinedTextField(value = aCountry, onValueChange = { aCountry = it }, keyboardOptions = CapWords, label = { Text(stringResource(R.string.ce_country)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                DropdownField(stringResource(R.string.ce_address_type), aType.label(ctxLabel), typeOptions.map { it.label(ctxLabel) }) { v -> aType = typeOptions.firstOrNull { it.label(ctxLabel) == v } ?: aType }
                            }
                        },
                        confirmButton = {
                            Button(enabled = aLine.isNotBlank(), onClick = {
                                val targetId = base?.id ?: java.util.UUID.randomUUID().toString()
                                val a = Address(
                                    id = targetId, ownerType = AddressOwnerType.CONTACT, ownerId = contact.id,
                                    addressType = aType, addressLine = aLine.trim(), city = aCity.trim(), country = aCountry.trim(),
                                    postalCode = aPostal.trim().ifBlank { null }, latitude = base?.latitude, longitude = base?.longitude
                                )
                                val updated = if (base == null) allAddrs + a else allAddrs.map { if (it.id == targetId) a else it }
                                AppStateStore.updateContact(contact.copy(addresses = updated))
                                showAddrDialog = false; editAddr = null
                            }) { Text(stringResource(if (base == null) R.string.common_add else R.string.common_save)) }
                        },
                        dismissButton = { TextButton(onClick = { showAddrDialog = false; editAddr = null }) { Text(stringResource(R.string.common_cancel)) } }
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
                val dev = list.firstOrNull { it.id == id } ?: return@launch
                fun digits(s: String) = s.filter { it.isDigit() }
                val mergedPhones = contact.phones + dev.phones
                    .filter { d -> contact.phones.none { digits(it.number) == digits(d.number) } }
                    .map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = contact.id) }
                val mergedEmails = contact.emails + dev.emails
                    .filter { d -> contact.emails.none { it.email.equals(d.email, ignoreCase = true) } }
                    .map { it.copy(id = java.util.UUID.randomUUID().toString(), contactId = contact.id) }
                val mergedAddrs = contact.addresses + dev.addresses
                    .filter { d -> contact.addresses.none { it.addressLine == d.addressLine && it.city == d.city } }
                    .map { it.copy(id = java.util.UUID.randomUUID().toString(), ownerId = contact.id, ownerType = AddressOwnerType.CONTACT) }
                AppStateStore.updateContact(contact.copy(
                    firstName = contact.firstName.ifBlank { dev.firstName },
                    lastName  = contact.lastName.ifBlank { dev.lastName },
                    phones    = mergedPhones,
                    emails    = mergedEmails,
                    addresses = mergedAddrs
                ))
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
