@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.CopyableText
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 2 — СВЯЗЬ (каналы коммуникации)
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.communicationTab(contact: Contact, ctxLabel: android.content.Context) {
    // РЕШЕНИЕ ВЛАДЕЛЬЦА 2026-10-08: правка и удаление телефонов/email/мессенджеров/адресов —
    // ТОЛЬКО в единой форме ContactEditScreen (кнопка «Редактировать» в шапке). Здесь остаётся
    // просмотр, действия (звонок/сообщение/карта) и быстрые кнопки «+ Добавить».
    // Каналы связи — телефон/email/мессенджеры объединены в одну карточку
    // (как в макете), а не 3 отдельные.
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Повторы НЕ прячем и НЕ удаляем (решение владельца 2026-10-10): показываем все записи, повтор
        // помечен «уже есть выше»; удалить — в форме контакта (карандаш), решает пользователь.
        val phones = contact.phones
        val repeatMarker = stringResource(R.string.ce_repeat_marker)
        val repeatedPhoneIds = com.aistudio.socialsphere.crmlxb.data.ContactChannelDedup.repeatedPhoneIds(contact.phones)
        val repeatedEmailIds = com.aistudio.socialsphere.crmlxb.data.ContactChannelDedup.repeatedEmailIds(contact.emails)
        // ФИКС (2026-07-12, фидбэк владельца): блок и кнопки «+Добавить» видны ВСЕГДА.
        run {
            CardBlock(title = stringResource(R.string.cd_comm_channels)) {
                var showPhoneDialog by remember { mutableStateOf(false) }

                phones.forEach { phone ->
                    ChannelRow(
                        icon = Icons.Outlined.Phone, iconTint = AppleTheme.colors.brand, iconBg = AppleTheme.colors.brand.copy(alpha = 0.10f),
                        value = phone.number,
                        subtitle = phone.type.label(ctxLabel) + if (phone.id in repeatedPhoneIds) " · $repeatMarker" else "",
                        isPrimary = phone.isPrimary
                    ) {
                        // Те же действия и ТА ЖЕ семантика, что у кнопок в шапке карточки:
                        // только открыть внешний канал, lastContactDate НЕ менять (см.
                        // комментарий у быстрых действий в ContactDetailScreen). Раньше здесь
                        // стоял markContactedNow («баг §35») — отметка без какой-либо проверки
                        // того, что контакт состоялся или хотя бы интент открылся.
                        ActionSquare(Icons.Outlined.Phone, stringResource(R.string.cd_call)) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(ctx, phone.number)
                        }
                        ActionSquare(Icons.Default.Sms, stringResource(R.string.cd_write)) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openSms(ctx, phone.number)
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                TextButton(onClick = { showPhoneDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_phone))
                }

                if (showPhoneDialog) {
                    var num by remember { mutableStateOf("") }
                    var pType by remember { mutableStateOf(PhoneType.MOBILE) }
                    var primary by remember { mutableStateOf(contact.phones.isEmpty()) }
                    // Такой номер уже есть — второй раз не создаём (решение владельца 2026-10-10).
                    val phoneExists = com.aistudio.socialsphere.crmlxb.data.ContactChannelDedup.phoneAlreadyListed(contact.phones, num)
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.cd_add_phone),
                        onDismiss = { showPhoneDialog = false },
                        confirmText = stringResource(R.string.common_add),
                        confirmEnabled = num.isNotBlank() && !phoneExists,
                        onConfirm = {
                            val newId = java.util.UUID.randomUUID().toString()
                            var list = contact.phones.toMutableList()
                            list.add(ContactPhone(newId, contact.id, num.trim(), pType, primary))
                            // Главный может быть только один — при выборе этого снимаем
                            // флаг с остальных. Затем главный поднимается наверх списка
                            // (фидбэк владельца 2026-07-19: выбрал главным — сразу видно).
                            if (primary) list = list.map { if (it.id == newId) it else it.copy(isPrimary = false) }.toMutableList()
                            AppStateStore.updateContact(contact, contact.copy(phones = list.sortedByDescending { it.isPrimary }))
                            showPhoneDialog = false
                        }
                    ) {
                        OutlinedTextField(
                            value = num, onValueChange = { num = it }, keyboardOptions = PhoneKeyboard,
                            label = { Text(stringResource(R.string.ce_number)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            isError = phoneExists,
                            supportingText = if (phoneExists) ({ Text(stringResource(R.string.ce_phone_exists)) }) else null
                        )
                        DropdownField(stringResource(R.string.ce_type), pType.label(ctxLabel), PhoneType.values().map { it.label(ctxLabel) }) { v -> pType = PhoneType.values().firstOrNull { it.label(ctxLabel) == v } ?: pType }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = primary, onCheckedChange = { primary = it })
                            Text(stringResource(R.string.ce_make_primary), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                var showEmailDialog by remember { mutableStateOf(false) }

                contact.emails.forEach { email ->
                    ChannelRow(
                        icon = Icons.Outlined.Email, iconTint = AureliaTheme.colors.gold, iconBg = AureliaTheme.colors.gold.copy(alpha = 0.14f),
                        value = email.email,
                        subtitle = email.type.label(ctxLabel) + if (email.id in repeatedEmailIds) " · $repeatMarker" else "",
                        isPrimary = email.isPrimary
                    ) {
                        ActionSquare(Icons.Outlined.Email, stringResource(R.string.cd_email_action)) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(ctx, email.email)
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                TextButton(onClick = { showEmailDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_email))
                }

                if (showEmailDialog) {
                    var addr by remember { mutableStateOf("") }
                    var eType by remember { mutableStateOf(EmailType.PERSONAL) }
                    var primary by remember { mutableStateOf(contact.emails.isEmpty()) }
                    // Такой адрес уже есть (весь адрес, регистр букв не важен) — второй раз не создаём.
                    val emailExists = com.aistudio.socialsphere.crmlxb.data.ContactChannelDedup.emailAlreadyListed(contact.emails, addr)
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.cd_add_email),
                        onDismiss = { showEmailDialog = false },
                        confirmText = stringResource(R.string.common_add),
                        confirmEnabled = addr.isNotBlank() && !emailExists,
                        onConfirm = {
                            val newId = java.util.UUID.randomUUID().toString()
                            var list = contact.emails.toMutableList()
                            list.add(ContactEmail(newId, contact.id, addr.trim(), eType, primary))
                            if (primary) list = list.map { if (it.id == newId) it else it.copy(isPrimary = false) }.toMutableList()
                            AppStateStore.updateContact(contact, contact.copy(emails = list.sortedByDescending { it.isPrimary }))
                            showEmailDialog = false
                        }
                    ) {
                        OutlinedTextField(
                            value = addr, onValueChange = { addr = it }, keyboardOptions = EmailKeyboard,
                            label = { Text(stringResource(R.string.ce_email)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            isError = emailExists,
                            supportingText = if (emailExists) ({ Text(stringResource(R.string.ce_email_exists)) }) else null
                        )
                        DropdownField(stringResource(R.string.ce_type), eType.label(ctxLabel), EmailType.values().map { it.label(ctxLabel) }) { v -> eType = EmailType.values().firstOrNull { it.label(ctxLabel) == v } ?: eType }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = primary, onCheckedChange = { primary = it })
                            Text(stringResource(R.string.ce_make_primary), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                var showMsgDialog by remember { mutableStateOf(false) }

                contact.messengers.forEach { m ->
                    ChannelRow(
                        icon = Icons.AutoMirrored.Filled.Send, iconTint = AppleTheme.colors.red, iconBg = AppleTheme.colors.red.copy(alpha = 0.10f),
                        value = m.value,
                        subtitle = m.type.label(ctxLabel) + (m.comment?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        isPrimary = m.isPrimary
                    ) {
                        ActionSquare(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_write)) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openMessenger(ctx, m)
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                TextButton(onClick = { showMsgDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_messenger))
                }

                if (showMsgDialog) {
                    var uname by remember { mutableStateOf("") }
                    var mType by remember { mutableStateOf(MessengerType.TELEGRAM) }
                    // ФИКС (2026-07-12): Messenger.link существовал в модели и уже
                    // использовался в ExternalActionHandler.openMessenger (приоритет
                    // над сгенерированной по типу ссылкой), но нигде в UI не выставлялся.
                    var mLink by remember { mutableStateOf("") }
                    var primary by remember { mutableStateOf(contact.messengers.isEmpty()) }
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.cd_add_messenger),
                        onDismiss = { showMsgDialog = false },
                        confirmText = stringResource(R.string.common_add),
                        confirmEnabled = uname.isNotBlank(),
                        onConfirm = {
                            val newId = java.util.UUID.randomUUID().toString()
                            var list = contact.messengers.toMutableList()
                            val link = mLink.trim().ifBlank { null }
                            list.add(Messenger(id = newId, contactId = contact.id, type = mType, value = uname.trim(), link = link, isPrimary = primary))
                            if (primary) list = list.map { if (it.id == newId) it else it.copy(isPrimary = false) }.toMutableList()
                            AppStateStore.updateContact(contact, contact.copy(messengers = list.sortedByDescending { it.isPrimary }))
                            showMsgDialog = false
                        }
                    ) {
                        PillChoiceRow(
                            options = MessengerType.values().map { it.label(ctxLabel) },
                            selected = mType.label(ctxLabel),
                            onSelect = { v -> mType = MessengerType.values().firstOrNull { it.label(ctxLabel) == v } ?: mType }
                        )
                        OutlinedTextField(value = uname, onValueChange = { uname = it }, label = { Text(stringResource(R.string.ce_username)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = mLink, onValueChange = { mLink = it }, keyboardOptions = UrlKeyboard, label = { Text(stringResource(R.string.ce_messenger_link)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = primary, onCheckedChange = { primary = it })
                            Text(stringResource(R.string.ce_make_primary), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
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
        // ФИКС (2026-07-12, фидбэк владельца): блок и «+Добавить» видны ВСЕГДА.
        run {
            CardBlock(title = stringResource(R.string.cd_addresses)) {
                var showAddrDialog by remember { mutableStateOf(false) }
                // Скоуп уровня карточки: переживает закрытие диалога (геокод в фоне)
                val addrScope = rememberCoroutineScope()

                addresses.forEach { addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            CopyableText(
                                listOf(addr.addressLine, addr.district.orEmpty(), addr.city, addr.postalCode.orEmpty(), addr.country).filter { it.isNotBlank() }.joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(addr.addressType.label(ctxLabel), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        ActionSquare(Icons.Outlined.Map, stringResource(R.string.cd_map)) {
                            if (addr.latitude != null && addr.longitude != null)
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(ctx, addr.latitude, addr.longitude)
                            else
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(ctx, "${addr.addressLine}, ${addr.city}")
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                TextButton(onClick = { showAddrDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_add_address))
                }

                if (showAddrDialog) {
                    // ЕДИНЫЙ диалог адреса (AddressComponents.kt) — со ВСЕМИ типами:
                    // смена «домашний → рабочий» переносит адрес на вкладку Работа.
                    AddressEditDialog(
                        base = null,
                        ownerId = contact.id,
                        scope = addrScope,
                        onDismiss = { showAddrDialog = false },
                        onCommit = { a ->
                            val updated = if (allAddrs.none { it.id == a.id }) allAddrs + a
                                          else allAddrs.map { if (it.id == a.id) a else it }
                            AppStateStore.updateContact(contact, contact.copy(addresses = updated))
                        },
                        onGeocoded = { a ->
                            AppStateStore.getContact(contact.id)?.let { fresh ->
                                AppStateStore.updateContact(fresh, fresh.copy(
                                    addresses = fresh.addresses.map { if (it.id == a.id) a else it }
                                ))
                            }
                        }
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
    //
    // PHONE PACK #4: вся бизнес-логика — PhoneContactSync.pullFromPhone (явный результат, ничего не
    // бросает); экран только отображает состояние. Связанный контакт обновляется НАПРЯМУЮ (без picker);
    // picker — только для несвязанного, для «Связать заново» после «контакт исчез» и для явного
    // «Изменить связь с телефоном».
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val scope = rememberCoroutineScope()
        var showLink by remember { mutableStateOf(false) }
        var showGone by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var deviceList by remember { mutableStateOf<List<ImportContactCandidate>>(emptyList()) }
        var search by remember { mutableStateOf("") }
        var retryAfterGrant by remember { mutableStateOf(false) }
        var retryTrigger by remember { mutableStateOf(0) }

        fun toast(res: Int, vararg args: Any, long: Boolean = false) =
            android.widget.Toast.makeText(
                ctx, ctx.getString(res, *args),
                if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
            ).show()

        fun loadDevices(thenShow: Boolean) {
            scope.launch {
                val list = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ContactImporter.getDeviceContacts(ctx) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    toast(R.string.sync_permission_needed, long = true); return@launch
                } catch (e: Exception) {
                    toast(R.string.sync_failed, long = true); return@launch
                }
                deviceList = list
                if (thenShow) showLink = true
            }
        }
        val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            when {
                !granted -> { retryAfterGrant = false; toast(R.string.sync_permission_needed, long = true) }
                retryAfterGrant -> { retryAfterGrant = false; retryTrigger++ }
                else -> loadDevices(true)
            }
        }
        fun ensureThen(show: Boolean) {
            if (DeviceContacts.hasPermission(ctx)) loadDevices(show)
            else permLauncher.launch(android.Manifest.permission.READ_CONTACTS)
        }

        suspend fun doPull() {
            if (busy) return
            busy = true
            try {
                val result = PhoneContactSync.pullFromPhone(ctx, contact.id)
                when (uiActionFor(result)) {
                    SyncUiAction.SHOW_APPLIED -> (result as PhoneSyncResult.Applied).let {
                        toast(R.string.sync_pull_result, it.phones, it.emails, it.addresses, if (it.workplace) 1 else 0, long = true)
                    }
                    SyncUiAction.SHOW_NOTHING_NEW -> toast(R.string.sync_pull_nothing)
                    SyncUiAction.REQUEST_PERMISSION -> {
                        // Разрешение отозвано/не выдано: просим его и после выдачи повторяем — без picker
                        retryAfterGrant = true
                        permLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                    }
                    SyncUiAction.OFFER_RELINK -> showGone = true      // объясняем и предлагаем «Связать заново»
                    SyncUiAction.SHOW_FAILED -> toast(R.string.sync_failed, long = true)
                    SyncUiAction.OPEN_PICKER -> ensureThen(true)      // не связан
                    SyncUiAction.NONE -> {}                           // сбой записи: сообщение уже показал корневой экран
                }
            } finally { busy = false }
        }
        fun pullFromPhone() { scope.launch { doPull() } }
        LaunchedEffect(retryTrigger) { if (retryTrigger > 0) doPull() }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { ensureThen(true) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Link, null, Modifier.size(16.dp).padding(end = 4.dp))
                Text(
                    stringResource(
                        if (contact.deviceContactId == null && contact.deviceLookupKey == null) R.string.sync_link_phone
                        else R.string.sync_change_link
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (contact.deviceContactId != null || contact.deviceLookupKey != null) {
                OutlinedButton(onClick = { pullFromPhone() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Sync, null, Modifier.size(16.dp).padding(end = 4.dp))
                    Text(stringResource(R.string.sync_update_from_phone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (contact.deviceContactId != null || contact.deviceLookupKey != null) {
            Text(
                stringResource(R.string.sync_linked),
                style = MaterialTheme.typography.labelSmall,
                color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (showGone) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaConfirmDialog(
                onDismiss = { showGone = false },
                title = stringResource(R.string.sync_gone_title),
                text = stringResource(R.string.sync_gone_body),
                confirmText = stringResource(R.string.sync_relink),
                onConfirm = { showGone = false; ensureThen(true) }
            )
        }

        if (showLink) {
            val filtered = deviceList.filter {
                "${it.firstName} ${it.middleName} ${it.lastName}".contains(search, ignoreCase = true)
            }
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { showLink = false; search = "" }) {
                Text(
                    stringResource(R.string.sync_pick_contact),
                    fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(stringResource(R.string.sync_pick_contact)),
                    fontSize = 20.sp, fontWeight = FontWeight.W700,
                    color = AppleTheme.colors.label,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.ce_search_contact)) }
                )
                Spacer(Modifier.height(8.dp))
                filtered.take(20).forEach { d ->
                    Text(
                        "${d.firstName} ${d.lastName}".trim().ifBlank { d.phones.firstOrNull()?.number ?: "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                // Связь = LOOKUP_KEY + кэш-id парой (устойчива к смене _ID/имени/номера)
                                PhoneContactSync.link(contact, d)
                                showLink = false; search = ""
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Строка канала связи с иконкой-плиткой (как в макете «Каналы связи»).
 * Значение + подпись слева, действия (позвонить/написать) справа, бейдж «Основной»
 * если это главный контакт. Правка/удаление — только в единой форме ContactEditScreen.
 */
@Composable
private fun ChannelRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    iconBg: Color,
    value: String,
    subtitle: String,
    isPrimary: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R11).background(iconBg),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(18.dp), tint = iconTint) }
        Column(Modifier.weight(1f)) {
            // Одна строка всегда: раньше капсула «Основной» справа отжимала ширину
            // и длинный номер переносился на 2 строки — теперь признак «основной»
            // живёт в подписи («Мобильный · Основной»), номер получает всю ширину.
            CopyableText(
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}
